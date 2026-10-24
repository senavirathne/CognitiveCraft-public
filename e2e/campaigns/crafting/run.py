#!/usr/bin/env python3
"""Run the independent physical crafting campaign through one persistent client."""
from __future__ import annotations

import argparse
from collections import Counter
import contextlib
import json
from pathlib import Path
import secrets
import sys
import time
import traceback

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from suite import atomic, read, require  # noqa: E402
from runtime import digest  # noqa: E402
from campaigns.crafting.generator import generate, select, LOG, OAK, BIRCH, STICK, AXE, TABLE
from campaigns.crafting.oracle import requirements, sha, grids
from campaigns.crafting.harness import CraftingHarness, AdmissionBlocked, BoundaryMissed, coord
from campaigns.crafting.invariants import totals, recipe_balance, composition_balance, classify, delta, staging_verification
from campaigns.crafting.execution import (shortage, execute_blueprint, execute_recovery, execute_facility,
                                         execute_dynamic, execute_legacy, check_artifacts, refusal_reason)
from campaigns.crafting.evidence import evidence_path, record_problems
from campaigns.crafting.scheduling import interleave


class CampaignCapacityBlocked(RuntimeError):
    """A real owner reported finite durable storage exhaustion."""


def require_case_fence(result, identity):
    if result["status"] == "BLOCKED_CAPACITY":
        raise CampaignCapacityBlocked("STORAGE_LIMIT_REACHED on " + identity +
                                      "; remaining cases were not submitted and production quotas were preserved")
    if result.get("cleanupFenced") is False:
        raise RuntimeError("Case isolation was not established; no further fixtures may be reset")


def offsets(root):
    files = [root / "server/stdout.log", root / "server/observations.jsonl", root / "supervisor.jsonl",
             root / "clients/PlayerA/events.jsonl", root / "clients/PlayerA/stdout.log"]
    return {str(p.relative_to(root)): p.stat().st_size if p.exists() else 0 for p in files}


def log_slices(root, folder, before):
    rows = []
    for name, offset in before.items():
        path = root / name
        if not path.exists():
            rows.append({"path": name, "available": False}); continue
        target = folder / (name.replace("/", "_") + ".slice")
        end = path.stat().st_size
        if offset > end:
            raise ValueError("Raw log was truncated or replaced during the case: " + name)
        with path.open("rb") as source, target.open("wb") as output:
            source.seek(offset)
            remaining = end-offset
            while remaining:
                chunk = source.read(min(remaining, 1024 * 1024))
                if not chunk:
                    raise ValueError("Raw log ended before its captured byte boundary")
                output.write(chunk)
                remaining -= len(chunk)
        rows.append({"path": name, "startByte": offset, "endByte": end,
                     "slice": str(target.relative_to(root)), "sha256": digest(target), "available": True})
    return rows


def needed_artifacts(case):
    if case["mode"] == "recipe":
        return [case["output"]]
    if case["mode"] == "legacy":
        return []
    if case["mode"] == "facility":
        if case["output"] == TABLE:
            return ["placement"]
        if case["facility"] == "existing":
            return [AXE]
        if case["facility"] in {"stored-item", "reuse"}:
            return ["placement", AXE]
        return [OAK, STICK, AXE, TABLE, "placement"]
    if case["mode"] == "blueprint":
        return [OAK, "oak-acquisition"]
    if case.get("output") == BIRCH:
        return [BIRCH, "birch-acquisition"]
    if case["mode"] == "recovery":
        return ["placement", AXE] if case["boundary"] == "after-placement" else [OAK, STICK, AXE]
    output = case.get("output", AXE)
    distribution = case.get("distribution", "ready")
    needed = [output]
    if distribution in {"logs", "partial", "gather", "held", "split-containers"}:
        needed.append(OAK)
        if output == AXE and distribution != "sticks":
            needed.append(STICK)
    elif distribution == "sticks":
        needed.append(OAK)
    if case.get("worldLogs"):
        needed.append("oak-acquisition")
    return list(dict.fromkeys(needed))


def run_case(h, case):
    require(not record_problems({"case": case, "status": "NOT_EXECUTED", "attempted": False,
                                 "terminalObserved": False}), "Invalid generated or replayed case identity")
    folder = evidence_path(h.root, "cases/" + case["id"])
    folder.mkdir(parents=True, exist_ok=False)
    h.family, h.current_case, h.records = case["id"], case, folder
    h.case_attempted, h.case_requests, h.last_request = False, [], None
    start = time.time(); raw_offsets = offsets(h.root)
    row = {"schema": 1, "case": case, "status": "NOT_EXECUTED", "attempted": False,
           "terminalObserved": False, "boundaryWitnessed": None, "started": start,
           "metadata": read(h.root / "metadata.json"), "modelAndArtifacts": h.artifacts.copy()}
    atomic(folder / "case.json", case)
    request = result = expected = None
    try:
        if case["mode"] == "recipe":
            probe = h.probe(case)
            expected = requirements(probe, case["quantity"])
            row.update(vanillaProbe=probe, independentExpectation=expected)
            if case.get("missingAdapter"):
                row.update(status="GAP_NO_GAMEPLAY_ADAPTER", reason="Vanilla equivalent output has no registered player-facing production adapter")
                return row
        missing = [x for x in needed_artifacts(case) if x not in h.artifacts]
        if missing:
            raise AdmissionBlocked("No genuine admitted methods for " + ", ".join(missing))
        before = h.prepare(case, expected)
        client_before = h.client_snapshot("PlayerA")
        calls = h.model_calls()
        source, destination, facility = list(h.source), list(h.destination), list(h.facility)
        row.update(before=before, clientBefore=client_before, modelCallsBefore=calls,
                   bindings={"source": source, "destination": destination, "facility": facility,
                             "actor": h.entities["PlayerA"], "citizen": h.citizens["PlayerA"],
                             "player": h.players["PlayerA"]})
        feasibility = shortage(case, before, source, h.recipes, facility=facility,
                               blueprint_origin=getattr(h, "blueprint_origin", None)) if case["mode"] != "recipe" else {"feasible": True}
        if case["mode"] == "facility":
            if case["facility"] in {"occupied", "unsupported-floor", "entity-collision", "actor-collision"}:
                feasibility["feasible"] = False
                feasibility["siteRejectedAtSetup"] = case["facility"]
        if case["mode"] == "blueprint" and case["separateSource"]:
            feasibility["feasible"] = False
        row["independentShortage"] = feasibility
        if case.get("dynamic") == "fill-destination":
            h.stock(destination, {"minecraft:cobblestone": 64*26}, case["container"])
            h.console(["give PlayerA " + case["output"] + " 64"])
            before, client_before = h.snapshot(), h.client_snapshot("PlayerA")
            row.update(before=before, clientBefore=client_before)
        if case["mode"] == "blueprint":
            request, result = execute_blueprint(h, case)
        elif case["mode"] == "facility":
            request, result = execute_facility(h, case)
        elif case["mode"] == "recovery":
            request, result = execute_recovery(h, case)
        elif case["mode"] == "legacy":
            recipe = h.recipes["minecraft:bread"]
            probe_case = {"id": case["id"], "recipe": "minecraft:bread", "grid": grids(recipe)}
            probe = h.probe(probe_case)
            wheat_per_batch = probe["grid"].count("minecraft:wheat")
            require(wheat_per_batch > 0, "Actual vanilla bread wheat count missing")
            expected = requirements(probe, min(8, case["wheat"]//wheat_per_batch)*probe["count"])
            row.update(vanillaProbe=probe, independentExpectation=expected)
            request, result = execute_legacy(h, case, expected)
            before, client_before = result["before"], result["clientBefore"]
            row.update(before=before, clientBefore=client_before, requested=result["requested"])
            row["bindings"].update(actor=h.legacy_entity, citizen=None, controlSurface="legacy-food",
                                   source=list(h.source), destination=list(h.source), facility=list(h.facility))
            source = destination = list(h.source)
            feasibility["feasible"] = case["facility"] == "existing"
        elif case.get("dynamic"):
            request, result = execute_dynamic(h, case)
        else:
            request = h.submit_command(h.command_text(case))
            result = h.finish_request(request, 260 if case["mode"] in {"compose", "facility"} else 180)
        after, client_after = h.snapshot(), h.client_snapshot("PlayerA")
        if result.get("physicalAfter"):
            after, client_after = result["physicalAfter"], result["clientAfter"]
        if result.get("postInterruption"):
            after, client_after = result["postInterruption"]["state"], result["postInterruption"]["client"]
        before_totals, after_totals = totals(before, client_before), totals(after, client_after)
        conserved, balance = (recipe_balance(before_totals, after_totals, expected) if expected else
                              composition_balance(before_totals, after_totals, h.recipes))
        key = ",".join(map(str, destination))
        output = case.get("output", OAK)
        delivered = after["containers"].get(key, {}).get(output, 0) - before["containers"].get(key, {}).get(output, 0)
        requested = row.get("requested", case.get("quantity", 1))
        if case["mode"] == "facility" and output == TABLE:
            target = ",".join(map(str, facility))
            delivered = int(TABLE in after["blocks"].get(target, "")) - int(TABLE in before["blocks"].get(target, ""))
            if case["facility"] == "actor-collision" and result["completed"] and after.get(
                    "placementColumnsClear", {}).get(target) is True:
                feasibility["feasible"] = True
                row["actorCollisionClearedNaturally"] = True
        if case["mode"] == "blueprint":
            requested = feasibility["outstanding"]
            # SUPPLY may allocate pre-existing stock, whereas MAKE must prove new output.
            delivered = (result.get("allocation") or {}).get("quantity", 0)
            row["availableStaged"] = after["containers"].get(key, {}).get(OAK, 0)
            require(all(after["blocks"].get(",".join(map(str, c[:3]))) == before["blocks"].get(",".join(map(str, c[:3])))
                        for c in h.blueprint_cells), "Staging changed construction blocks")
        artifacts_ok, used = check_artifacts(h, result)
        if case["mode"] == "recipe" and result["completed"]:
            marker = result["views"][0].get("marker", {})
            artifacts_ok = artifacts_ok and marker.get("artifactSha256") == h.artifacts[output]
        require(artifacts_ok, "Known gameplay changed an admitted artifact identity")
        require(h.model_calls() == calls, "Known execution invoked a model")
        reason = refusal_reason(request, result)
        effects = any(r.get("stage") in {"WITHDRAW", "CRAFT", "PLACE", "DEPOSIT", "HARVEST", "PICKUP"}
                      for r in result.get("receipts", []))
        status = classify(feasible=feasibility["feasible"], completed=result["completed"],
                          equivalent=bool(case.get("metamorphicControlId")), conservation=conserved,
                          delivered=delivered, requested=requested, refusal_reason=reason, physical_effects=effects)
        row["reportedReason"] = reason
        capacity_blocked = reason == "STORAGE_LIMIT_REACHED" and not result["completed"] and conserved
        if case["mode"] == "blueprint" and result["completed"]:
            ok, allocation_facts = staging_verification(feasibility, row["availableStaged"], result.get("allocation"),
                                                       result.get("receipts", []), result["views"][0])
            row["allocationReconciliation"] = allocation_facts
            if not ok:
                status = "FAIL_SAFETY"
        if case.get("distribution") in {"held", "split-containers"} and not result["completed"] and conserved:
            status = "GAP_RESOURCE_DISTRIBUTION"
        if case.get("facility") == "held-item" and not result["completed"] and conserved:
            status = "GAP_ACTOR_CUSTODY_BINDING"
        if result.get("reuseVerified") is False:
            status = "FAIL_GAMEPLAY" if result["facilityReuse"]["conserved"] else "FAIL_SAFETY"
        if case["mode"] == "recovery":
            if not conserved or result.get("durableCustodyVerified") is False:
                status = "FAIL_SAFETY"
            elif not result.get("boundary"):
                status = "INCONCLUSIVE_BOUNDARY"
            elif not result.get("interruptionVerified"):
                status = "FAIL_GAMEPLAY"
            else:
                status = "PASS_RECOVERY"
        if case.get("dynamic"):
            witnessed = result.get("dynamic", {}).get("witnessed") or result.get("competitionWitnessed")
            if not witnessed:
                status = "INCONCLUSIVE_BOUNDARY"
            elif not conserved:
                status = "FAIL_SAFETY"
            elif case["dynamic"] == "competing-actors":
                completed = int(result["completed"]) + int(result["competitor"]["result"]["completed"])
                status = "PASS_CONTENTION" if completed <= 1 else "FAIL_SAFETY"
            else:
                status = "PASS_EXPECTED_INTERRUPTION" if not result["completed"] and delivered < requested else "FAIL_GAMEPLAY"
        if capacity_blocked:
            status = "BLOCKED_CAPACITY"
        # A real synchronized menu is required in addition to server-side inventory.
        menu = None
        if output != TABLE or case["mode"] != "facility":
            menu = result["rootMenuCount"] if "rootMenuCount" in result else h.menu_count("PlayerA", destination, output)
            require(menu == after["containers"].get(key, {}).get(output, 0), "Connected menu disagrees with physical stock")
        client_blocks = client_after.get("blocks", {})
        if case["mode"] == "facility" and result["completed"]:
            target = ",".join(map(str, facility))
            require(TABLE in client_blocks.get(target, ""), "Client did not observe the actual placed/reused facility")
        row.update(status=status, request=request, execution=result, after=after, clientAfter=client_after,
                   conservation=balance, conserved=conserved, delivered=delivered, requested=requested,
                   terminalObserved=result.get("terminal", False), clientMenuCount=menu,
                   artifactIdentityVerified=artifacts_ok, usedArtifacts=used, modelCallsAfter=h.model_calls(),
                   physicalDelta=delta(before_totals, after_totals),
                   boundaryWitnessed=bool(result.get("boundary") or result.get("dynamic", {}).get("witnessed") or
                                          result.get("competitionWitnessed")) if case["mode"] == "recovery" or case.get("dynamic") else None)
        receipts = result.get("receipts", [])
        attribution = all(r.get("origin", {}).get("principal", {}).get("id") == h.players["PlayerA"] and
                          r.get("actor", {}).get("citizenId") in {subject[0] for subject in h.subjects} for r in receipts)
        row["receiptReconciliation"] = {"attribution": attribution, "count": len(receipts),
                                         "stages": dict(Counter(r["stage"] for r in receipts))}
        if case["mode"] == "recipe" and result["completed"] and not case.get("dynamic"):
            produced = sum(r["quantity"] for r in receipts if r["stage"] == "CRAFT" and r["item"]["id"] == output)
            deposited = sum(r["quantity"] for r in receipts if r["stage"] == "DEPOSIT" and r["item"]["id"] == output)
            withdrawals = Counter()
            for receipt in receipts:
                if receipt["stage"] == "WITHDRAW":
                    withdrawals[receipt["item"]["id"]] += receipt["quantity"]
            reconciled = produced == expected["produced"] and deposited == requested and dict(withdrawals) == expected["consumed"]
            row["receiptReconciliation"].update(produced=produced, deposited=deposited,
                                                withdrawals=dict(withdrawals), exact=reconciled)
            if not reconciled or not attribution:
                row["status"] = "FAIL_SAFETY"
        elif not attribution:
            row["status"] = "FAIL_SAFETY"
        h.screenshot("PlayerA", "final-physical-state", chat=True)
    except AdmissionBlocked as error:
        row.update(status="BLOCKED_ADMISSION", reason=str(error))
    except BoundaryMissed as error:
        row.update(status="INCONCLUSIVE_BOUNDARY", reason=str(error), traceback=traceback.format_exc())
    except Exception as error:
        row.update(status="FAIL_HARNESS" if not h.case_attempted else "FAIL_UNCLASSIFIED",
                   reason=repr(error), traceback=traceback.format_exc())
    finally:
        row["attempted"] = h.case_attempted
        row["requests"] = h.case_requests
        if request is None and h.case_attempted:
            request = h.last_request
        # Preserve the exact failed attempt before cancellation or fixture mutation.
        with contextlib.suppress(Exception):
            row.setdefault("failureSnapshot", h.snapshot())
            row.setdefault("failureClient", h.client_snapshot("PlayerA"))
            if request:
                row.setdefault("request", request)
                row.setdefault("lastViews", h.views(request) if request["kind"] != "legacy" else [])
            if not row["status"].startswith("PASS"):
                h.screenshot("PlayerA", "preserved-failure", chat=True)
        row["evidenceCutoffBeforeCleanup"] = time.time()
        row["ended"] = time.time()
        row["seconds"] = row["ended"] - start
        row["screenshots"] = [x for x in h.screenshots if x["family"] == case["id"]]
        atomic(folder / "result.json", row)
        h.records = None
        try:
            h.cleanup()
            row["cleanupFenced"] = True
        except Exception as error:
            row.update(cleanupFenced=False, cleanupError=repr(error))
        if h.legacy_entity:
            with contextlib.suppress(Exception):
                h.command("PlayerA", "/aivillage stop BreadWorker", "stop: BreadWorker")
        if request and request["kind"] == "job":
            with contextlib.suppress(Exception):
                h.cancel_job("PlayerA", request["identity"])
        row["rawLogs"] = log_slices(h.root, folder, raw_offsets)
        atomic(folder / "result.json", row)
    return row


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--mode", choices=["smoke", "full"], default="smoke")
    parser.add_argument("--seed", type=int, default=20261024)
    parser.add_argument("--replay", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    root.mkdir(parents=True, exist_ok=True)
    require(not (root / "campaign.json").exists(), "Use a new run directory; failed evidence is immutable")
    frozen = {"schema": 1, "frozenAt": time.time(), "developmentSeed": args.seed,
              "definitionFiles": {str(p.relative_to(ROOT)): digest(p) for p in
                  sorted((ROOT / "campaigns/crafting").rglob("*")) if p.suffix in {".py", ".java", ".json"}},
              "productionBaseline": "ea7de8000b2b484b885576c34ed5358274936ce9"}
    inherited = [ROOT / name for name in ("suite.py", "runtime.py", "launch.py", "fixtures.py", "navigation.py", "compose.yml", "Dockerfile")]
    inherited += sorted((ROOT / "client").rglob("*")) + sorted((ROOT / "observer").rglob("*"))
    inherited += sorted((ROOT.parent / "scripts/campaign-crafting").rglob("*.py"))
    frozen["inheritedFiles"] = {str(path.relative_to(ROOT.parent)): digest(path) for path in inherited if path.is_file()}
    if args.replay:
        frozen["replayInputSha256"] = digest(args.replay)
    atomic(root / "definitions-frozen.json", frozen)
    h, rows, scheduled = None, [], []
    campaign = {"schema": 1, "mode": args.mode, "status": "STARTING", "productionBaseline": frozen["productionBaseline"],
                "definitionHash": sha(frozen), "results": rows, "clientCount": 1, "replay": bool(args.replay)}
    atomic(root / "campaign.json", campaign)
    try:
        replay_cases = read(args.replay) if args.replay else None
        if args.replay and not isinstance(replay_cases, list):
            replay_cases = [replay_cases]
        if args.replay:
            require(0 < len(replay_cases) <= 512 and all(isinstance(c, dict) for c in replay_cases),
                    "A bounded nonempty replay schedule is required")
        required = {item for case in replay_cases for item in needed_artifacts(case)} if replay_cases else None
        h = CraftingHarness(root, frozen["productionBaseline"])
        h.bootstrap(required)
        cases = generate(h.recipes, args.seed)
        atomic(root / "cases-development.json", cases)
        # Entropy is drawn only after definitions are frozen and baseline cases exist.
        held_seed = secrets.randbits(63)
        atomic(root / "held-out-seed.json", {"seed": held_seed, "generatedAt": time.time(), "definitionHash": sha(frozen)})
        held = generate(h.recipes, held_seed, held_out=True)
        atomic(root / "cases-held-out.json", held)
        preferred = select(held, "smoke")
        held_selected = []
        for family in sorted({c["family"] for c in held}):
            pool = [c for c in preferred if c["family"] == family]
            pool += [c for c in held if c["family"] == family and c not in pool]
            held_selected.extend(pool[:2])
        held_by_id = {c["id"]: c for c in held}
        for case in list(held_selected):
            control = held_by_id.get(case.get("metamorphicControlId"))
            if control and control not in held_selected:
                held_selected.append(control)
        scheduled = replay_cases if args.replay else interleave(select(cases, args.mode)) + interleave(held_selected)
        if not isinstance(scheduled, list):
            scheduled = [scheduled]
        atomic(root / "schedule.json", scheduled)
        campaign.update(status="RUNNING", scheduled=len(scheduled), generated=len(cases)+len(held), heldOutSeed=held_seed)
        for case in scheduled:
            result = run_case(h, case)
            rows.append({"id": case["id"], "family": case["family"], "status": result["status"],
                         "attempted": result["attempted"], "terminalObserved": result["terminalObserved"],
                         "path": "cases/" + case["id"] + "/result.json"})
            campaign["counts"] = dict(Counter(r["status"] for r in rows))
            atomic(root / "campaign.json", campaign)
            print(json.dumps(rows[-1]), flush=True)
            require_case_fence(result, case["id"])
        # Preserve failures, then make bounded fresh reproductions. These are
        # candidates for minimization, never silently substituted for originals.
        reproductions, groups = [], set()
        if not args.replay:
            for summary in list(rows):
                if not summary["status"].startswith("FAIL"):
                    continue
                original = read(root / summary["path"])
                case = original["case"]
                group = (case["mode"], case.get("output"), summary["status"])
                if group in groups or len(reproductions) >= 4:
                    continue
                groups.add(group)
                replay = {**case, "id": case["id"] + "-reproduction", "reproductionOf": case["id"]}
                if case["mode"] == "recipe" and not case.get("dynamic"):
                    replay.update(quantity=1, surplus=0, minimizedCandidate=True)
                reproductions.append(replay)
        if reproductions:
            scheduled.extend(reproductions)
            atomic(root / "schedule.json", scheduled)
            campaign["scheduled"] = len(scheduled)
            for case in reproductions:
                result = run_case(h, case)
                rows.append({"id": case["id"], "family": case["family"], "status": result["status"],
                             "attempted": result["attempted"], "terminalObserved": result["terminalObserved"],
                             "path": "cases/" + case["id"] + "/result.json"})
                atomic(root / "campaign.json", campaign)
                require_case_fence(result, case["id"])
        campaign["status"] = "RECORDED"
    except CampaignCapacityBlocked as error:
        campaign.update(status="BLOCKED_CAPACITY", error=str(error), reason="STORAGE_LIMIT_REACHED")
    except Exception as error:
        campaign.update(status="BLOCKED_INFRASTRUCTURE", error=repr(error), traceback=traceback.format_exc())
    finally:
        if h:
            h.finish()
        campaign["results"] = rows
        campaign["notExecuted"] = [c["id"] for c in scheduled if c["id"] not in {r["id"] for r in rows}]
        campaign["ended"] = time.time()
        atomic(root / "campaign.json", campaign)
    if campaign["status"] != "RECORDED":
        raise SystemExit(2)


if __name__ == "__main__":
    main()
