"""Read the actual I00 player/world/stop traces without running game actions."""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import sys
import traceback

from .campaign import admissions
from .gameplay import witness_identity, ROOT
from . import recipe_journal
from .native_evidence import (item_at, jsonl, raw_slices, require_command,
                              require_native_primitive, stock_at, verify_frames, verify_installation)
from .walkthrough import (ASK, AXE, COLLECTED, COHORTS, CUTS, DESTINATION, PROFILE, SOURCE,
                          assert_goal, cases, tables, witnessed)
from campaigns.crafting.invariants import physical_custody
from parallel_b.guards import clock_response, stopped_process
from runtime import digest
from suite import atomic, read, require

sys.path.insert(0, str(ROOT / "e2e/parallel_a"))
from review_receipt_prefix import block_name, chunk, dimension_root, entity_uuid, journal, regions


def world_hashes(world):
    return [{"path": str(p.relative_to(world)), "sha256": digest(p)} for p in sorted(world.rglob("*"))
            if p.is_file() and p.name != "session.lock"]


def native_items(rows):
    result = Counter()
    for row in rows:
        result[row["id"]] += int(row.get("count", row.get("Count", 1)))
    return dict(result)


def certify_recipe_cut(world, world_id, row, actor_nbt, chest_rows):
    stage = {"after-craft": "CRAFT", "at-final-delivery": "DEPOSIT"}.get(row["requiredCut"])
    if not stage:
        return None
    raw = recipe_journal.read(world, expected_world=world_id)
    job = row["killObservation"]["job"]
    records = [receipt for attempt in job["attempts"] for receipt in attempt["resourceReceipts"]
               if receipt["stage"] == stage and receipt["item"]["id"] == AXE]
    require(len(records) == 1, "Expected exactly one original axe phase at this cut")
    receipt = records[0]
    witness = receipt["choice"]
    token = witness["journalToken"]
    effect = raw[token]
    intent, result = effect["prepare"], effect["result"]
    require(recipe_journal.positive(effect) and intent["phase"] == stage and intent["root"] == witness["root"] == job["id"]
            and intent["step"] == witness["step"] and intent["attempt"] == witness["attempt"]
            and intent["generation"] == witness["generation"] and intent["operation"] == receipt["operationId"]
            and intent["choice"] == witness["choiceDigest"] and intent["batch"] == witness["batch"],
            "Original axe effect does not bind the acknowledged native recipe frames")
    actor_address = "actor:minecraft:overworld/" + row["actor"]
    chest_address = "chest:minecraft:overworld/" + ",".join(map(str, DESTINATION))
    native = {actor_address: actor_nbt, chest_address: chest_rows[tuple(DESTINATION)]}
    addresses = [p["address"] for p in result["after"]]
    required = [actor_address] if stage == "CRAFT" else [actor_address, chest_address]
    require(addresses == required, "Incomplete or substituted saved native recipe participant set")
    markers = [recipe_journal.saved_marker(native[p["address"]], effect, p) for p in result["after"]]
    return {"journalToken": token, "stage": stage, "savedParticipants": addresses,
            "resultSha256": markers[0]["result"], "usage": result["usage"]}


def certify_placement_cut(world, world_id, row, actor_nbt, chest_rows):
    job = row["killObservation"]["job"]
    completed = [(attempt, receipt) for attempt in job["attempts"] for receipt in attempt["resourceReceipts"]
                 if receipt["stage"] == "PLACE" and receipt["item"]["id"] == "minecraft:crafting_table"]
    require(len(completed) == 1, "Cut lacks one attributable table placement")
    attempt, receipt = completed[0]
    evidence = recipe_journal.read(world, expected_world=world_id)
    trace = [phase for phase in recipe_journal.phases(evidence, job["id"])
             if phase["prepare"]["attempt"] == attempt["id"]]
    require([phase["prepare"]["phase"] for phase in trace] == ["WITHDRAW", "PLACE"]
            and all(map(recipe_journal.positive, trace)), "Incomplete original placement journal")
    withdrawal, placement = trace
    prepare, result = placement["prepare"], placement["result"]
    step = next(s for s in job["activity"]["steps"] if s["attempt"] == attempt["id"])
    require(prepare["step"] == step["id"] and prepare["method"] == step["artifact"]["sha256"]
            and prepare["generation"] == attempt["generation"] and prepare["operation"] == receipt["operationId"]
            and all(receipt["placement"][key] == result["placement"][key] for key in recipe_journal.PLACEMENT_FIELDS),
            "The stopped native placement differs from its original Job")
    site = tuple(receipt["placement"]["target"][axis] for axis in ("x", "y", "z"))
    actor = "actor:minecraft:overworld/" + row["actor"]
    source = "chest:minecraft:overworld/" + ",".join(map(str, SOURCE))
    block = "block:minecraft:overworld/" + ",".join(map(str, site))
    withdrawn = {p["address"]: p for p in withdrawal["result"]["after"]}
    placed = {p["address"]: p for p in result["after"]}
    require(set(withdrawn) == {actor, source, block} and set(placed) == {actor, block}
            and all(withdrawn[p["address"]] == p for p in prepare["before"])
            and placed[block]["revision"] == withdrawn[block]["revision"] + 1,
            "Original placement participants or effect order changed")
    x, _, z = site
    region = dimension_root(world) / "region" / f"r.{x // 512}.{z // 512}.mca"
    native = chunk(region, x // 16, z // 16)
    require(block_name(world, site) == "minecraft:crafting_table", "Original native placement cell is absent")
    recipe_journal.saved_marker(actor_nbt, placement, placed[actor])
    recipe_journal.saved_marker(chest_rows[tuple(SOURCE)], withdrawal, withdrawn[source])
    recipe_journal.saved_block_marker(native, placement, placed[block], site)
    return {"journalToken": prepare["token"], "site": list(site), "savedParticipants": list(placed) + [source]}


def independently_saved(world, row):
    cut, actor = row["killObservation"]["state"], row["actor"]
    header, records = journal(world / "data/cognitivecraft/jobs/v1/state.jsonl")
    root_id = row["request"]["root"]
    roots = [record for record in records if record.get("id") == root_id and record.get("kind") == "job"]
    require(len(roots) == 1 and roots[0]["activity"] and header["world"] == row["firstJob"]["request"]["context"]["scope"]["worldId"],
            "Stopped bytes lack the original durable activity root or world")
    actor_rows = [entity for chunk in regions(world, "entities") for entity in chunk.get("Entities", []) if entity_uuid(entity) == actor]
    require(len(actor_rows) == 1 and native_items(actor_rows[0].get("Inventory", [])) == stock_at(cut, actor),
            "Saved native villager custody differs from the witnessed cut")
    chest_rows = {tuple(entity[k] for k in ("x", "y", "z")): entity
                  for chunk in regions(world, "region") for entity in chunk.get("block_entities", [])}
    for point in (SOURCE, DESTINATION):
        expected = cut["containers"].get(",".join(map(str, point)), {})
        persisted = chest_rows[tuple(point)]
        require(persisted["id"] == "minecraft:chest" and native_items(persisted.get("Items", [])) == expected,
                "Persisted native chest custody differs from the cut")
    for site in tables(cut):
        require(block_name(world, tuple(map(int, site.split(",")))) == "minecraft:crafting_table", "Witnessed table is absent from actual saved terrain")
    return {"world": header["world"], "jobRevision": roots[0]["revision"], "nativeActorAndChestsReconciled": True,
            "certifiedExactRecipeCut": certify_recipe_cut(world, header["world"], row, actor_rows[0], chest_rows),
            "certifiedPlacementCut": certify_placement_cut(world, header["world"], row, actor_rows[0], chest_rows)
            if row["requiredCut"] == "after-table-placement" else None}


def original_lineage(row, *, resumed):
    initial = row["firstJob"]
    at_cut = row["killObservation"]["job"]
    warm = row["warmFirstJob"]
    final = row["originalJob"]
    for job in (at_cut, warm, final):
        require(job["id"] == initial["id"] == row["request"]["root"] and job["submissionId"] == initial["submissionId"]
                and job["request"] == initial["request"] and job["allowance"] == initial["allowance"],
                "Activity, original request or root allowances changed at restart")
    require(final["generation"] >= warm["generation"] >= initial["generation"], "Root generation was reset")
    if resumed:
        prior_attempts = [(a["id"], a["generation"]) for a in at_cut["attempts"]]
        final_attempts = [(a["id"], a["generation"]) for a in final["attempts"]]
        require(final_attempts[:len(prior_attempts)] == prior_attempts and warm["generation"] >= at_cut["generation"],
                "Saved original attempts were replaced instead of reconciled")
        require(all(warm["usage"].get(k, 0) >= quantity and final["usage"].get(k, 0) >= warm["usage"].get(k, 0)
                    for k, quantity in at_cut["usage"].items()), "Original cumulative allowance refilled across restart")


def restart_review(folder, row, paths, cohort, jar):
    root_id, cold, warm = row["request"]["root"], row["coldProcess"], row["warmProcess"]
    require(cold["releaseSha256"] == warm["releaseSha256"] == jar and warm["start"] > cold["start"],
            "No newly booted same-binary Minecraft JVM")
    events = list(jsonl(paths["supervisor.jsonl"]))
    stops = [event for event in events if event["kind"] == "process-stop-witness" and event["data"]["process"] == cold]
    require(len(stops) == 1 and stops[0]["data"]["hard"] is True, "No actual matching SIGKILL inspection")
    stop = stops[0]["data"]["state"]
    require(stopped_process(stop, hard=True) and stop["OOMKilled"] is False, "Signal or native process identity invalid")
    disconnected = [event["data"]["player"] for event in events if event["kind"] == "server-disconnection-witness"
                    and event["data"]["server"] == cold and event["data"]["client"]["play"] is False]
    require(set(disconnected) == {"PlayerA", "PlayerB"}, "Native clients did not observe server disconnection")
    native_freezes = [event for event in events if event["kind"] == "verified-native-clock" and event["data"]["command"] == "tick freeze"]
    require(len(native_freezes) == 1 and clock_response("tick freeze", native_freezes[0]["data"]["feedback"])
            and native_freezes[0]["time"] < stops[0]["time"], "No acknowledged native freeze preceding the actual stop")
    saves = [event["data"] for event in events if event["kind"] == "acknowledged-world-checkpoint" and event["time"] < stops[0]["time"]]
    if cohort == "unsaved":
        require(saves == [row["initialCheckpoint"]] and "afterCutSave" not in row, "Unsaved cut forced a checkpoint")
    else:
        require(saves == [row["initialCheckpoint"], row["afterCutSave"]], "Saved cut lost its real checkpoint acknowledgement")
    cut = row["requiredCut"]
    for stage in (row["cut"], row["killObservation"]):
        require(stage["job"]["id"] == root_id and witnessed(cut, stage["job"], stage["state"], row["actor"]),
                "Actual process stopped outside the requested physical cut")
    require(row["cut"]["client"]["uuid"] == row["principal"] and row["cut"]["client"]["play"] is True,
            "No connected original principal at the witnessed cut")
    world = folder / "stopped-world"
    retained = world_hashes(world)
    require(retained and retained == row["stoppedWorldHashes"], "Raw stopped world bytes missing or changed")
    resumed = row["status"] == COLLECTED
    original_lineage(row, resumed=resumed and cohort != "unsaved")
    if cohort == "saved-online":
        available = [event for event in events if event["kind"] == "i00-live-backend" and event["time"] > stops[0]["time"]]
        require(len(available) == 1 and available[0]["data"] == row["warmModelAvailable"] and available[0]["data"]["models"],
                "No live backend witness after the cold stop")
    else:
        unavailable = [event for event in events if event["kind"] == "crafting-backend-unavailable" and event["time"] > stops[0]["time"]]
        require(unavailable and row["warmModelUnavailable"] is True, "No failed bridge probe after the actual stop")
    # A new JVM has new process counters. Preserve both epochs and compute
    # totals, so an offline zero cannot erase cold pre-cut work.
    before, boundary, after = (row[key] for key in ("modelCallsBefore", "modelCallsAtCut", "modelCallsAfter"))
    require(before.keys() == boundary.keys() == after.keys() and all(boundary[k] >= v and after[k] == 0 for k, v in before.items()),
            "A known certified sequence called the model after restart or lost cold work")
    return {"originalRoot": root_id, "resumedCompletion": resumed, "cut": cut, "coldProcess": cold, "warmProcess": warm,
            "modelCallsBefore": before, "modelCallsAfter": after,
            "cumulativeMeasuredModelCalls": {key: boundary[key] - before[key] + after[key] for key in before},
            "independentSavedParticipants": independently_saved(world, row) if cohort != "unsaved" else None}


def external_review(row, client_events):
    external = row["externalRemoval"]
    initial, changed = row["originalJob"], external["job"]
    require(changed["id"] == initial["id"] == external["originalRoot"] and changed["fulfilled"] == initial["fulfilled"] == 1
            and changed["activity"]["checkpoints"] == initial["activity"]["checkpoints"], "External removal rewrote goal history")
    require(item_at(external["state"], DESTINATION, AXE) == 0 and "currentStock=0" in external["view"]["owner"]
            and sum(s["count"] for s in external["client"]["slots"] if s["playerInventory"] and s["item"] == AXE) == 1,
            "External removal lacks actual current native/client stock")
    require(any(event["kind"] == "action" and event["data"].get("action") == "menu-click" and event["data"].get("quick") is True
                and event["data"].get("slot") == external["clientAction"]["clicked"] for event in client_events),
            "No real client action caused the observed removal")
    return {"id": "checkpoint-stock-removed", "status": "PASS_NATIVE_CURRENT_STOCK_WITH_HISTORICAL_CREDIT",
            "originalRoot": changed["id"], "historicalCredit": changed["fulfilled"], "currentStock": 0}


def appeared(path, items, key=None):
    remaining = items.copy()
    for observed in jsonl(path):
        state = observed[key] if key else observed
        if state in remaining:
            remaining.remove(state)
            if not remaining:
                return True
    return False


def review_case(folder, case, row, cohort, pins, source, jar):
    metadata = read(folder / "metadata.json")
    witness_identity(metadata, source, jar, PROFILE)
    verify_installation(folder, metadata, ("PlayerA", "PlayerB"))
    require(row["id"] == case and row["cohort"] == cohort and row["requiredCut"] == (case if case in CUTS else None),
            "Evidence describes a different walkthrough case")
    require(row["status"] == COLLECTED or cohort == "unsaved" and row["status"] == "CONTAINMENT_ONLY",
            "Missing required physical outcome: " + row.get("reason", row["status"]))
    require(row["knownPins"] == pins, "Known method pins differ from the actual prelude admissions")
    events_a, observations_a = "clients/PlayerA/events.jsonl", "clients/PlayerA/observations.jsonl"
    events_b, server, views = "clients/PlayerB/events.jsonl", "server/stdout.log", "server/observations.jsonl"
    paths = raw_slices(folder, row, {events_a, observations_a, events_b, server, views, "supervisor.jsonl"})
    require(appeared(paths[observations_a], [row["clientBefore"], row["clientAfter"]], "state"),
            "Reported client state differs from original driver observations")
    raw_states = [row["before"], row["after"]] + ([row["killObservation"]["state"]] if case in CUTS else [])
    require(appeared(paths[views], raw_states), "Reported physical state is missing from the native observer")
    require_command(paths[events_a], ASK, row["request"]["feedback"], "PlayerA")
    events = list(jsonl(paths[events_a]))
    asks = [i for i, e in enumerate(events) if e["kind"] == "action" and e["data"].get("text") == ASK]
    require(len(asks) == 1 and any(e["kind"] == "action" and e["data"].get("action") == "look" and
                                  e["data"].get("pos") == DESTINATION for e in events[:asks[0]]),
            "The ordinary player did not aim at the target chest and issue exactly one goal")
    for key, player, path in (("owner", "PlayerA", events_a), ("foreign", "PlayerB", events_b)):
        view = row["publicView"]
        require_command(paths[path], view["command"], view[key], player)
    require(row["foreignPrincipal"] != row["principal"] and row["request"]["root"] not in row["publicView"]["foreign"],
            "Private activity reached an unrelated player")
    for snapshot in (row["clientBefore"], row["clientAfter"]):
        require(snapshot["play"] is True and snapshot["singleplayer"] is False and snapshot["uuid"] == row["principal"],
                "Missing dedicated Minecraft client identity")
    require_native_primitive(paths[server])
    verify_frames(folder, row["screenshots"], minimum=3 if case in CUTS else 2)
    result = {"id": case, "status": "PASS_NATIVE_RESUMED" if case in CUTS else "PASS_NATIVE_LIVE",
              "originalRoot": row["request"]["root"], "resultSha256": digest(folder / "result.json")}
    if case in CUTS:
        result.update(restart_review(folder, row, paths, cohort, jar))
    if row["status"] == "CONTAINMENT_ONLY":
        require(row["originalJob"]["state"] == "INTERRUPTED" and "next=RECOVER_OR_SETTLE" in row["publicView"]["owner"]
                and physical_custody(row["after"]) == physical_custody(row["afterFence"]), "Unsaved cut lacks scoped stable containment")
        result["status"] = "PASS_CONTAINMENT_ONLY"
    else:
        assert_goal(case, row)
    return result, external_review(row, events) if case == "sufficient-stock" else None


def review(root, cohort, source, jar):
    required = cases(cohort)
    result = {"schema": 1, "stage": "I00", "profile": PROFILE, "cohort": cohort, "status": "FAIL_REQUIRED_CASES",
              "requiredCases": list(required), "cases": [], "offlineResumedCompletions": 0}
    try:
        result.update(witness_identity(read(root / "seed/metadata.json"), source, jar, PROFILE))
        result["ci"] = {"GITHUB_RUN_ID": result["run"], "GITHUB_RUN_ATTEMPT": result["attempt"]}
        run_details = read(root / "walkthrough.json") or {}
        pins = admissions(root / "seed")
        for case in required:
            try:
                folder = root / "cases" / case
                record = read(folder / "result.json")
                if record is None:
                    result["cases"].append({"id": case, "status": "NOT_EXECUTED_REQUIRED",
                                            "reason": run_details.get("error", "Case has no native result")})
                    continue
                if record["status"] not in {COLLECTED, "CONTAINMENT_ONLY"}:
                    result["cases"].append({"id": case, "status": record["status"],
                                            "reason": record.get("reason", "Case did not produce the required observation"),
                                            "resultSha256": digest(folder / "result.json")})
                    continue
                row, external = review_case(folder, case, record, cohort, pins, source, jar)
                result["cases"].append(row)
                if external:
                    result["checkpointStockRemoved"] = external
            except Exception as error:
                result["cases"].append({"id": case, "status": "FAIL_REVIEW", "reason": str(error)})
        if cohort == "saved-offline":
            result["offlineResumedCompletions"] = sum(row["status"] == "PASS_NATIVE_RESUMED" for row in result["cases"])
        if all(row["status"].startswith("PASS_") for row in result["cases"]):
            result["status"] = "PASS_INDEPENDENT_NATIVE_REVIEW"
    except Exception as error:
        result.update(reason=str(error), trace=traceback.format_exc())
    atomic(root / "review.json", result)
    print("I00_WALKTHROUGH_REVIEW " + json.dumps({k: v for k, v in result.items() if k != "trace"}), flush=True)
    return result["status"] == "PASS_INDEPENDENT_NATIVE_REVIEW"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--cohort", choices=COHORTS, required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--jar-sha256", required=True)
    args = parser.parse_args()
    if not review(args.root.resolve(), args.cohort, args.source, args.jar_sha256):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
