"""Execute and independently review shards of the exact 334-case native campaign."""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import shutil
import traceback
import runpy
from uuid import uuid4

from .frozen_campaign import ORIGINAL_SHA256, controls_for, material_controls, original, shard
from .gameplay import NativeGameplay, ROOT, witness_identity
from .native_evidence import exact_withdrawals, jsonl, raw_slices, require_command, verify_installation
from campaigns.crafting.harness import CraftingHarness, observer_inputs
from campaigns.crafting.evidence import PASS_STATUSES, evidence_path
from campaigns.crafting.invariants import composition_balance, recipe_balance, totals, delta
from campaigns.crafting.run import needed_artifacts, run_case
from parallel_b.guards import clean_owners, stopped_process
from runtime import digest
from suite import atomic, read, reconnect_players, require

PROFILE = "I00_ORIGINAL_334"
CASES_PER_WORLD = 16


def schedule_file(root, index, count):
    cases = shard(original(), index, count)
    controls = controls_for(cases, material_controls())
    value = {"frozenScheduleSha256": ORIGINAL_SHA256, "shardIndex": index, "shardCount": count, "cases": cases,
             "materialControls": controls}
    atomic(root / "schedule.json", value)
    return cases, controls


DIRECT_INGREDIENTS = {
    "minecraft:oak_planks": {"minecraft:oak_log"},
    "minecraft:birch_planks": {"minecraft:birch_log"},
    "minecraft:stick": {"minecraft:oak_planks"},
    "minecraft:crafting_table": {"minecraft:oak_planks"},
    "minecraft:wooden_axe": {"minecraft:oak_planks", "minecraft:stick"},
}


def requires_exact_choice(case):
    if case["mode"] != "recipe" or "grid" not in case:
        return False
    ingredients = {item for item in case["grid"] if item}
    return ingredients != DIRECT_INGREDIENTS.get(case["output"]) or bool(case.get("missingAdapter"))


class OriginalCampaign(NativeGameplay):
    acquisition_methods = ("oak-acquisition", "birch-acquisition")
    admission_order = tuple(name for name in NativeGameplay.admission_order
                            if name not in {"oak-acquisition", "birch-acquisition"}) + acquisition_methods

    def command_text(self, case):
        if not requires_exact_choice(case):
            return CraftingHarness.command_text(self, case)
        return super().command_text(case)

    def arm_recovery(self, case):
        control = {"token": str(uuid4()), "principal": self.players["PlayerA"],
                   "boundary": case["boundary"], "campaignOutput": case["output"]}
        atomic(self.root / "server/i00-native-boundary.json", control)
        self.event("i00-campaign-cut-armed", control)
        return control

    def recovery_cut(self, control, request):
        def reached():
            row = read(self.root / "server/i00-native-boundary-reached.json")
            return row if row and row["token"] == control["token"] else None

        mark = self.wait(reached, "Actual original campaign native interruption boundary", 155)
        key = "submission" if request["kind"] == "run" else "root"
        require(mark[key] == request["identity"] and mark["principal"] == control["principal"]
                and mark["boundary"] == control["boundary"] and mark["frozen"] and mark["rate"] == 20,
                "Actual boundary belongs to a different native request")
        self.event("i00-campaign-cut-frozen", mark)
        return mark

    def fence_measured_case(self, case, row):
        if not isolated(case):
            self.cleanup()
            return False
        process = read(self.root / "server/process.json")
        self.stop("server")
        self.actions_active = False
        row["stoppedIsolation"] = {"server": process, "worldHashes": self.world_hashes()}
        return True

    def certify_admission(self, row):
        if row["capability"] not in self.acquisition_methods:
            return
        from .walkthrough import completed_admission_deposit
        job = row["result"]["views"][0]["responsibility"]
        self.wait(lambda: clean_owners(self.snapshot()["knownPlayers"]["PlayerA"]), "Closed original log admission", 40)
        deposit = completed_admission_deposit(self.root, job["id"])
        require(deposit and deposit["intent"]["key"]["attempt"] == job["attempts"][-1]["id"],
                "Missing the original log admission's native deposit")
        saved = self.save_checkpoint()
        cold = read(self.root / "server/process.json")
        self.stop("server")
        self.start("server")
        reconnect_players(self, ["PlayerA"])

        def verified():
            actual = completed_admission_deposit(self.root, job["id"])
            return actual if actual and actual["intent"] == deposit["intent"] and actual["result"] == deposit["result"] \
                and (actual.get("verification") or {}).get("outcome") == "SAVED_COMPLETE" else None

        witness = self.wait(verified, "Original log admission custody verified before fixture changes", 65)
        warm = read(self.root / "server/process.json")
        require(warm["start"] > cold["start"] and cold["releaseSha256"] == warm["releaseSha256"],
                "Log admission did not reopen in a new matching server")
        atomic(self.root / f"admission-custody-{row['capability']}.json",
               {"before": deposit, "saved": saved, "verified": witness, "cold": cold, "warm": warm})
        self.command("PlayerA", "/aivillage kernel inference true", "admission=true")


def save_admission_world(root, scheduled):
    h = OriginalCampaign(root, profile=PROFILE)
    try:
        h.bootstrap({output for case in scheduled for output in needed_artifacts(case)}, after_admission=h.certify_admission)
        h.wait(lambda: clean_owners(h.snapshot()["knownPlayers"]["PlayerA"]), "Closed original admission Jobs", 40)
        h.save_checkpoint()
        h.stop("server")
        atomic(root / "admission-world.json", {"worldHashes": h.world_hashes(), "subjects": h.subjects,
               "artifacts": h.artifacts, "nativeAdmissionsCountedAsCampaign": 0})
        return read(root / "admission-world.json")
    finally:
        h.finish()


def open_world(folder, seed_root, seed):
    h = OriginalCampaign(folder, profile=PROFILE)
    try:
        world_path = "server/game/world"
        shutil.copytree(seed_root / world_path, folder / world_path, ignore=shutil.ignore_patterns("session.lock"))
        require(h.world_hashes() == seed["worldHashes"], "Saved admission world changed while being copied")
        atomic(folder / "admission-continuation.json", {"seedRecordSha256": digest(seed_root / "admission-world.json"),
               "copiedWorldHashes": h.world_hashes(), "nativeAdmissionsCountedAsCampaign": 0})
        h.subjects, h.artifacts = seed["subjects"], seed["artifacts"].copy()
        observer_inputs(folder)
        h.start("server")
        h.start("PlayerA")
        h.use_actor(0)
        h.console(["op PlayerA"])
        h.recipes = h.wait(lambda: read(folder / "oracle-recipes.json"), "Live vanilla recipe catalogue", 30)["recipes"]
        h.assert_backend_stopped()
        return h
    except Exception:
        h.finish()
        raise


def isolated(case):
    return case["mode"] in {"recovery", "facility"} or bool(case.get("dynamic"))


def run(root, index, count):
    root.mkdir(parents=True, exist_ok=False)
    cases, controls = schedule_file(root, index, count)
    statuses = {case["id"]: "NOT_EXECUTED_REQUIRED" for case in cases}
    supplemental = {row["id"]: "NOT_EXECUTED_REQUIRED" for row in controls}
    result = {"profile": PROFILE, "status": "FAIL_REQUIRED_CASES", "cases": statuses,
              "materialControls": supplemental, "caseWorlds": {}}
    scheduled = cases + [row["definition"] for row in controls]
    h = None
    try:
        seed_root = root / "seed"
        seed = save_admission_world(seed_root, scheduled)
        group = used = 0
        for case in scheduled:
            if h and (used == CASES_PER_WORLD or isolated(case)):
                h.finish()
                h = None
            if not h:
                group += 1
                used = 0
                h = open_world(root / "worlds" / f"{group:03d}", seed_root, seed)
            row = run_case(h, case, integrated_recipe=requires_exact_choice(case))
            result["caseWorlds"][case["id"]] = h.root.relative_to(root.resolve()).as_posix()
            used += 1
            (supplemental if case["id"] in supplemental else statuses)[case["id"]] = row["status"]
            atomic(root / "campaign.json", result)
            print("I00_ORIGINAL_CASE " + json.dumps({"id": case["id"], "status": row["status"],
                                                      "reason": row.get("reason")}), flush=True)
            if isolated(case) or row["status"] == "BLOCKED_CAPACITY" or row.get("cleanupFenced") is not True:
                h.finish()
                h = None
        if all(status in PASS_STATUSES for status in statuses.values()) and all(status == "PASS" for status in supplemental.values()):
            result["status"] = "COLLECTED_PENDING_INDEPENDENT_REVIEW"
    except Exception as error:
        result.update(error=str(error), trace=traceback.format_exc())
    finally:
        if h:
            h.finish()
        atomic(root / "campaign.json", result)
    return result["status"] == "COLLECTED_PENDING_INDEPENDENT_REVIEW"


def admissions(root):
    path = root / "admissions.json"
    if not path.is_file():
        return {}
    value = read(path)
    for item, method in value["artifacts"].items():
        rows = [row for row in value["rows"] if row["capability"] == item and row["status"] == "PASS"]
        require(len(rows) == 1, f"Missing genuine method admission {item}")
        result = rows[0]
        root_view = result["result"]["views"][0]
        require(root_view["marker"]["artifactSha256"] == method and root_view["marker"]["outcome"] == "ADMITTED"
                and bool(root_view["resourceReceipts"]) and result["callsAfter"]["generationCalls"] > result["callsBefore"]["generationCalls"],
                "Method was never genuinely admitted through measured gameplay: " + item)
    return value["artifacts"]


def review_admission_custody(seed, pins, jar):
    from .walkthrough import DEPOSIT_JOURNAL, completed_admission_deposit
    methods = set(OriginalCampaign.acquisition_methods) & pins.keys()
    if not methods:
        return
    events = list(jsonl(seed / "supervisor.jsonl"))
    admissions_rows = read(seed / "admissions.json")["rows"]
    for method in methods:
        row = next(item for item in admissions_rows if item["capability"] == method and item["status"] == "PASS")
        job = row["result"]["views"][0]["responsibility"]
        record = read(seed / f"admission-custody-{method}.json")
        before, verified = record["before"], record["verified"]
        require(verified == completed_admission_deposit(seed, job["id"])
                and verified["intent"] == before["intent"] and verified["result"] == before["result"]
                and verified["intent"]["key"]["attempt"] == job["attempts"][-1]["id"]
                and verified["verification"]["outcome"] == "SAVED_COMPLETE",
                "Original log admission is missing its exact native saved deposit")
        require(record["warm"]["start"] > record["cold"]["start"]
                and record["warm"]["releaseSha256"] == record["cold"]["releaseSha256"] == jar
                and any(event["kind"] == "acknowledged-world-checkpoint" and event["data"] == record["saved"] for event in events)
                and any(file["path"] == DEPOSIT_JOURNAL.removeprefix("server/game/world/") for file in record["saved"]["files"]),
                "Missing the original saved log admission and replacement JVM evidence")


def review_case(case, row, root, pins, recipes, validate):
    require(row["case"] == case, "Native case deviates from the frozen definition")
    require(row["status"] in PASS_STATUSES and row["attempted"] is True and row["terminalObserved"] is True,
            "No passing connected native execution")
    integrated = requires_exact_choice(case)
    problems = validate(row, root, integrated_recipe=integrated)
    require(not problems, "; ".join(problems))
    if isolated(case):
        from .walkthrough_review import world_hashes

        isolation = row["stoppedIsolation"]
        terminal, = [event["data"] for event in jsonl(root / "supervisor.jsonl")
                     if event["kind"] == "process-stop-witness" and event["time"] >= row["evidenceCutoffBeforeCleanup"]
                     and event["data"].get("process") == isolation["server"] and not event["data"]["hard"]]
        require(terminal["component"] == "server" and stopped_process(terminal["state"], hard=False)
                and terminal["state"]["ExitCode"] == 0
                and isolation["worldHashes"] == world_hashes(root / "server/game/world"),
                "The isolated measured world was not sealed after the physical outcome")
    if case["mode"] == "recovery":
        mark = row["execution"]["nativeClock"]
        request = row["request"]
        key = "submission" if request["kind"] == "run" else "root"
        require(mark == read(root / "server/i00-native-boundary-reached.json")
                and mark[key] == request["identity"] and mark["principal"] == row["bindings"]["player"]
                and mark["boundary"] == case["boundary"] and mark["rate"] == 20 and mark["frozen"],
                "Missing actual original campaign boundary under the normal clock")
    if integrated:
        events = "clients/PlayerA/events.jsonl"
        path = raw_slices(root, row, {events})[events]
        req = row["request"]
        require_command(path, req["command"], req["feedback"], "PlayerA")
    before, after = totals(row["before"], row["clientBefore"]), totals(row["after"], row["clientAfter"])
    balanced, _ = (recipe_balance(before, after, row["independentExpectation"])
                   if row.get("independentExpectation") else composition_balance(before, after, recipes))
    require(balanced and delta(before, after) == row["physicalDelta"], "Native world quantities do not independently reconcile")
    if case.get("dynamic") == "fill-destination":
        added = row["execution"]["dynamic"]["externalFill"]
        item = case["output"]
        def menu(frame, player_inventory):
            return sum(slot["count"] for slot in frame["slots"]
                       if slot["item"] == item and slot["playerInventory"] == player_inventory)
        opening, closing = added["before"], added["after"]
        original = sum(receipt["quantity"] for receipt in row["execution"]["receipts"]
                       if receipt["stage"] == "DEPOSIT" and receipt["item"]["id"] == item)
        require(opening["play"] and closing["play"] and opening["uuid"] == closing["uuid"] == row["bindings"]["player"]
                and opening["menu"] == closing["menu"] > 0
                and menu(opening, True)-menu(closing, True) == added["quantity"] > 0
                and menu(closing, False)-menu(opening, False) == added["quantity"]
                and row["delivered"] == original < row["requested"],
                "External fill and original native delivery are not physically attributable")
    if integrated and not case.get("dynamic"):
        output = case["output"]
        # Every frozen ordinary recipe is a valid positive, including the
        # historical missing-adapter entries. Refusal/containment cannot replace it.
        require(row["status"] == "PASS" and row["request"]["kind"] == "job"
                and row["request"]["command"].startswith("/aivillage kernel production-activity "),
                "Positive recipe did not execute through the integrated owner")
        job = row["execution"]["views"][0]
        require(job["id"] == row["request"]["identity"] == row["integratedRecipe"]["root"]
                and job["state"] == "SUCCEEDED" and job["fulfilled"] == case["quantity"]
                and job["request"]["request"]["arguments"]["amount"]["value"] == case["quantity"],
                "Original Job or requested quantity changed")
        steps = [step for step in job["activity"]["steps"] if step.get("choice")]
        require(steps == row["integratedRecipe"]["choices"] and len(steps) >= 1
                and {step["artifact"]["sha256"] for step in steps} == {pins[output]},
                "Exact recipe choice or genuine immutable method is missing")
        reconciled = row["receiptReconciliation"]
        require(reconciled["exact"] and reconciled["attribution"], "Exact input/output receipts are missing")
        actual = exact_withdrawals(job, row["execution"]["receipts"])
        require(actual == reconciled["withdrawals"] == row["independentExpectation"]["consumed"],
                "Actual bound batch materials differ from the frozen native oracle")
        require(row["delivered"] == case["quantity"], "Original exact goal was not delivered")
    return {"id": case["id"], "status": row["status"], "resultSha256": digest(root / "cases" / case["id"] / "result.json")}


def review_material(definition, row):
    expected = definition["expected"]
    receipts = row["receiptReconciliation"]
    require(row["status"] == "PASS" and row["delivered"] == expected["requested"]
            and row["physicalDelta"] == {expected["output"]: expected["physical"],
                                         **{item: -n for item, n in expected["input"].items()}}
            and receipts["exact"] and receipts["withdrawals"] == expected["input"],
            "Material control differs from the frozen output, input or exact surplus contract")
    return {"cohort": definition["cohort"], "originalId": definition["originalId"], "nativeQuantities": expected}


def review(root, source, jar):
    output = root / "review.json"
    result = {"profile": PROFILE, "status": "FAIL_REQUIRED_CASES", "cases": [], "materialControls": []}
    try:
        scheduled = read(root / "schedule.json")
        expected = shard(original(), scheduled["shardIndex"], scheduled["shardCount"])
        controls = controls_for(expected, material_controls())
        require(scheduled["cases"] == expected and scheduled["frozenScheduleSha256"] == ORIGINAL_SHA256
                and scheduled["materialControls"] == controls,
                "Changed or incomplete frozen case schedule")
        seed = root / "seed"
        manifest = read(seed / "metadata.json")
        result.update(witness_identity(manifest, source, jar, PROFILE), shardIndex=scheduled["shardIndex"],
                      shardCount=scheduled["shardCount"], requiredCases=len(expected))
        verify_installation(seed, manifest, ("PlayerA",))
        pins = admissions(seed)
        review_admission_custody(seed, pins, jar)
        seed_path = seed / "admission-world.json"
        seal = read(seed_path)
        from .walkthrough_review import world_hashes
        require(seal["artifacts"] == pins and seal["nativeAdmissionsCountedAsCampaign"] == 0
                and seal["worldHashes"] == world_hashes(seed / "server/game/world"), "Saved genuine admission world differs")
        case_worlds = read(root / "campaign.json")["caseWorlds"]
        verified_worlds = {}
        frozen_verifier = runpy.run_path(str(ROOT / "scripts/campaign-crafting/verify.py"))["validate_case"]
        for case, control in ([(case, None) for case in expected] + [(row["definition"], row) for row in controls]):
            results = result["materialControls"] if control else result["cases"]
            try:
                require(case["id"] in case_worlds, "Required case was not executed")
                world_name = case_worlds[case["id"]]
                world = evidence_path(root, world_name)
                if world_name not in verified_worlds:
                    child = read(world / "metadata.json")
                    witness_identity(child, source, jar, PROFILE)
                    verify_installation(world, child, ("PlayerA",))
                    link = read(world / "admission-continuation.json")
                    catalogue = read(world / "oracle-recipes.json")
                    require(link == {"seedRecordSha256": digest(seed_path), "copiedWorldHashes": seal["worldHashes"],
                                     "nativeAdmissionsCountedAsCampaign": 0}, "World belongs to a different saved admission")
                    require(catalogue["worldEffects"] == 0 and catalogue["minecraft"] == "26.3" and catalogue["serverPid"],
                            "Missing native read-only recipe catalogue")
                    verified_worlds[world_name] = child, catalogue["recipes"]
                child, recipes = verified_worlds[world_name]
                path = evidence_path(world, f"cases/{case['id']}/result.json")
                require(path.is_file(), "Required case was not executed")
                row = read(path)
                require(row["metadata"] == child, "Case belongs to a different native producer")
                verified = review_case(case, row, world, pins, recipes, frozen_verifier)
                if control:
                    verified.update(review_material(control, row))
                results.append(verified)
            except Exception as error:
                results.append({"id": case["id"], "status": "FAIL_REVIEW", "reason": str(error)})
        result["counts"] = dict(Counter(row["status"] for row in result["cases"]))
        if (all(row["status"] in PASS_STATUSES for row in result["cases"])
                and all(row["status"] == "PASS" for row in result["materialControls"])):
            result["status"] = "PASS_INDEPENDENT_NATIVE_REVIEW"
    except Exception as error:
        result.update(error=str(error), trace=traceback.format_exc())
    atomic(output, result)
    print("I00_ORIGINAL_REVIEW " + json.dumps({k: v for k, v in result.items() if k not in {"cases", "trace"}}), flush=True)
    return result["status"] == "PASS_INDEPENDENT_NATIVE_REVIEW"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    runner = commands.add_parser("run")
    runner.add_argument("--root", type=Path, required=True)
    runner.add_argument("--shard-index", type=int, default=0)
    runner.add_argument("--shard-count", type=int, default=1)
    reader = commands.add_parser("review")
    reader.add_argument("--root", type=Path, required=True)
    reader.add_argument("--source", required=True)
    reader.add_argument("--jar-sha256", required=True)
    args = parser.parse_args()
    passed = (run(args.root.resolve(), args.shard_index, args.shard_count) if args.command == "run"
              else review(args.root.resolve(), args.source, args.jar_sha256))
    if not passed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
