"""Execute and independently review shards of the exact 334-case native campaign."""
from __future__ import annotations

import argparse
import base64
from collections import Counter
import json
from pathlib import Path
import shutil
import traceback
import runpy
from uuid import uuid4

from .frozen_campaign import ORIGINAL_SHA256, controls_for, material_controls, original, shard
from .gameplay import NativeGameplay, ROOT, witness_identity
from .native_evidence import exact_withdrawals, jsonl, raw_slices, require_command, stock_at, verify_installation
from campaigns.crafting.harness import CraftingHarness, RECOVERY_REUSE, SECONDARY_STICKS, coord, observer_inputs
from campaigns.crafting.evidence import PASS_STATUSES, evidence_path
from campaigns.crafting.invariants import composition_balance, recipe_balance, totals, delta
from campaigns.crafting.run import needed_artifacts, run_case
from parallel_b.guards import clean_owners, stopped_process
from runtime import digest
from suite import atomic, read, reconnect_players, require

PROFILE = "I00_ORIGINAL_334"
CASES_PER_WORLD = 16
RECOVERY_GATE = [64, 208, 74]


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
    return (ingredients != DIRECT_INGREDIENTS.get(case["output"]) or bool(case.get("missingAdapter"))
            or case["output"] == "minecraft:birch_planks" and case["quantity"] > 32
            or case.get("dynamic") == "competing-actors")


class OriginalCampaign(NativeGameplay):
    acquisition_methods = ("oak-acquisition", "birch-acquisition")
    admission_order = tuple(name for name in NativeGameplay.admission_order
                            if name not in {"oak-acquisition", "birch-acquisition"}) + acquisition_methods

    def prepare(self, case, expected=None):
        state = super().prepare(case, expected)
        if case["mode"] != "recovery" or case["interruption"] != "restart":
            return state
        earlier = {actor["uuid"] for actor in state["actors"]}
        self.console(["fill 63 208 74 66 208 78 minecraft:oak_fence",
                      "fill 64 208 75 65 208 77 minecraft:air",
                      "setblock " + coord(RECOVERY_GATE) + " minecraft:oak_fence_gate[facing=north]",
                      "summon minecraft:villager 64.5 208 76.5 {PersistenceRequired:1b}"])

        def reserved():
            frame = self.snapshot()
            workers = [actor for actor in frame["actors"] if actor["uuid"] not in earlier]
            if len(workers) == 1 and workers[0]["alive"] and not workers[0]["noAI"] and not workers[0]["baby"]:
                return frame, workers[0]["uuid"]

        state, self.offline_worker = self.wait(reserved, "Unenrolled ordinary worker reserved before the restart fixture save")
        return state

    def constrain_unflushed_recovery(self):
        return self.command("PlayerA", "/save-off", "Automatic saving is now disabled", 25)

    def command_text(self, case):
        if not requires_exact_choice(case):
            command = CraftingHarness.command_text(self, case)
            if case.get("distribution") == "held":
                command += " use-held-sticks"
            if case.get("distribution") == "split-containers":
                command += " sticks-from " + coord(SECONDARY_STICKS)
            if case.get("facility") == "held-item":
                command += " use-held-table"
            return command
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

    def select_recovery_reuse_actor(self, case):
        self.walk("PlayerA", [64, 208, 73], reach=1)
        self.enroll("PlayerA", "Reserve")
        require(self.entities["PlayerA"] == self.offline_worker, "Offline reuse enrollment resolved to the wrong worker")
        self.action("PlayerA", "use", pos=RECOVERY_GATE)
        gate = ",".join(map(str, RECOVERY_GATE))
        self.wait(lambda: "open=true" in self.snapshot()["blocks"].get(gate, ""),
                  "Connected player releases the original reserve worker")

    def compete_for_stock(self, case):
        first = self.submit_command(self.command_text(case))
        require(first["accepted"] and first["kind"] == "job", "First shared-stock requester was not admitted")
        try:
            self.use_actor(1 - case["actor"])
            second = self.submit_command(self.command_text(case))
            require(second["accepted"] and second["kind"] == "job", "Second shared-stock requester was not admitted")

            def overlap():
                frame = self.snapshot()
                jobs = frame["knownPlayers"]["PlayerA"]["jobs"]
                owners = [jobs.get(order["identity"]) for order in (first, second)]
                return frame if all(job and job["state"] in {"READY", "WAITING", "ACTIVE"} for job in owners) else None

            both = self.wait(overlap, "Two original Jobs overlap under one physical stock constraint", 30)
        finally:
            self.use_actor(case["actor"])

        def allocation():
            views = [self.views(order) for order in (first, second)]
            if any(not v for v in views) or self.container(self.source, "minecraft:oak_log") != 0:
                return None
            states = [v[0]["state"] for v in views]
            if states.count("SUCCEEDED") == 1 and states.count("WAITING") == 1:
                return [v[0] for v in views]
            return None

        allocated = self.wait(allocation, "Real stock is exhausted by exactly one original owner", 110)
        loser = next(order for order, view in zip((first, second), allocated, strict=True) if view["state"] == "WAITING")
        require(not self.receipts_for(loser), "Waiting competitor has unaccounted native effects")
        cancellation = self.cancel_job("PlayerA", loser["identity"])
        first_result, second_result = (self.finish_request(order) for order in (first, second))
        first_result["competitor"] = {"request": second, "result": second_result}
        first_result["competitionWitnessed"] = True
        first_result["competition"] = {"overlap": both, "allocated": allocated,
                                       "loser": loser["identity"], "cancellation": cancellation}
        return first, first_result

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


def review_held_sticks(case, row, root):
    item = "minecraft:stick"
    quantity = case["quantity"]
    bindings = row["bindings"]
    request, job = row["request"], row["execution"]["views"][0]
    require(request["kind"] == "job" and request["identity"] == job["id"]
            and request["command"].endswith(" use-held-sticks") and job["state"] == "SUCCEEDED"
            and job["fulfilled"] == quantity and row["delivered"] == quantity,
            "Original owner did not explicitly authorize and complete held-input work")
    raw = "clients/PlayerA/events.jsonl"
    require_command(raw_slices(root, row, {raw})[raw], request["command"], request["feedback"], "PlayerA")
    source = ",".join(map(str, bindings["source"]))
    require(stock_at(row["before"], bindings["actor"]).get(item, 0) == 2 * quantity
            and not stock_at(row["after"], bindings["actor"]).get(item, 0)
            and not row["before"]["containers"][source].get(item, 0),
            "Originally held actor units do not reconcile with the real before and after stock")
    expected_actor = {"entity": bindings["actor"], "citizen": bindings["citizen"], "dimension": "minecraft:overworld"}
    count = 2 * quantity
    for step in job["activity"]["steps"]:
        choice = json.loads(base64.b64decode(step["choice"]["base64"], validate=True))
        for batch in choice["batches"]:
            for selected in batch["bindings"]:
                if selected["item"]["id"] == item:
                    require(selected.get("heldBy") == expected_actor and selected["sourceSlot"] == 0
                            and selected["expectedCount"] == count,
                            "Exact held slot lost its original actor or expected physical count")
                    count -= 1
    require(count == 0 and exact_withdrawals(job, row["execution"]["receipts"]) == {"minecraft:oak_planks": 3 * quantity},
            "Held inputs were counted as storage withdrawals or left unconsumed")


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
        if case["interruption"] == "restart":
            reuse = row["execution"]["offlineReuse"]
            old = row["execution"]["postInterruption"]["state"]
            before, after = reuse["before"], reuse["after"]
            source, destination, facility = [",".join(map(str, p)) for p in RECOVERY_REUSE]
            require(reuse["originalActor"] == row["bindings"]["actor"] and reuse["reuseActor"] != reuse["originalActor"]
                    and reuse["methodPins"] == [pins["minecraft:wooden_axe"]]
                    and reuse["request"]["identity"] != request["identity"] and reuse["result"]["completed"]
                    and before["containers"][source] == {"minecraft:oak_planks": 3, "minecraft:stick": 2}
                    and not before["containers"][destination] and "minecraft:crafting_table" in before["blocks"][facility]
                    and after["containers"][destination].get("minecraft:wooden_axe") == 1
                    and all(stock_at(old, worker["uuid"]) == stock_at(after, worker["uuid"])
                            for worker in old["actors"] if worker["uuid"] != reuse["reuseActor"])
                    and reuse["reuseActor"] not in {actor["actor"]["entityId"] for actor in
                                                   old["knownPlayers"]["PlayerA"]["citizens"]["citizens"]},
                    "Offline reuse lost its distinct actor, saved stock or original custody")
            events = "clients/PlayerA/events.jsonl"
            path = raw_slices(root, row, {events})[events]
            require_command(path, reuse["request"]["command"], reuse["request"]["feedback"], "PlayerA")
            if case["boundary"] == "before-checkpoint":
                require_command(path, "/save-off", row["execution"]["automaticSavePaused"], "PlayerA")
    if integrated:
        events = "clients/PlayerA/events.jsonl"
        path = raw_slices(root, row, {events})[events]
        req = row["request"]
        require_command(path, req["command"], req["feedback"], "PlayerA")
    before, after = totals(row["before"], row["clientBefore"]), totals(row["after"], row["clientAfter"])
    balanced, _ = (recipe_balance(before, after, row["independentExpectation"])
                   if row.get("independentExpectation") else composition_balance(before, after, recipes))
    require(balanced and delta(before, after) == row["physicalDelta"], "Native world quantities do not independently reconcile")
    if case["mode"] == "recipe" and case["output"] == "minecraft:birch_planks" and case["quantity"] > 32:
        job = row["execution"]["views"][0]
        choices = [json.loads(base64.b64decode(step["choice"]["base64"], validate=True))
                   for step in job["activity"]["steps"]]
        require(job["fulfilled"] == case["quantity"] and job["allowance"]["maxima"]["TRAVEL_BLOCKS"] == 128
                and job["usage"]["TRAVEL_BLOCKS"] <= 128 and any(choice.get("groupedPlanks") is True for choice in choices)
                and all(1 <= len(choice["batches"]) <= 4
                        and bool(choice.get("groupedPlanks")) == (len(choice["batches"]) > 1) for choice in choices)
                and sum(len(choice["batches"]) for choice in choices) == (case["quantity"] + 3) // 4,
                "The original large order did not retain its native batches, attribution and travel cap")
    if case.get("distribution") == "held":
        review_held_sticks(case, row, root)
    if case.get("distribution") == "split-containers":
        request, execution = row["request"], row["execution"]
        job = execution["views"][0]
        required = case["quantity"]
        box = ",".join(map(str, SECONDARY_STICKS))
        source = ",".join(map(str, row["bindings"]["source"]))
        primary = dict(zip(("x", "y", "z"), row["bindings"]["source"], strict=True), dimension="minecraft:overworld")
        secondary = dict(zip(("x", "y", "z"), SECONDARY_STICKS, strict=True), dimension="minecraft:overworld")
        require(request["kind"] == "job" and request["command"].endswith(" sticks-from " + coord(SECONDARY_STICKS))
                and request["identity"] == job["id"] and job["state"] == "SUCCEEDED" and job["fulfilled"] == required
                and row["before"]["containers"][box].get("minecraft:stick") == 2 * required
                and not row["before"]["containers"][source].get("minecraft:stick")
                and not row["after"]["containers"][box].get("minecraft:stick")
                and job["usage"]["COMMITTED_EFFECTS"] >= 4 * required
                and exact_withdrawals(job, execution["receipts"]) == {"minecraft:oak_planks": 3 * required, "minecraft:stick": 2 * required},
                "The separately authorized native stock or attributable result did not reconcile")
        for step in job["activity"]["steps"]:
            choice = json.loads(base64.b64decode(step["choice"]["base64"], validate=True))
            for batch in choice["batches"]:
                require(all(binding.get("source") == (secondary if binding["item"]["id"] == "minecraft:stick" else primary)
                            for binding in batch["bindings"]), "A chosen ingredient came from the wrong original slot owner")
        events = "clients/PlayerA/events.jsonl"
        require_command(raw_slices(root, row, {events})[events], request["command"], request["feedback"], "PlayerA")
    if case.get("facility") == "held-item":
        item = "minecraft:crafting_table"
        binding = row["bindings"]
        source = ",".join(map(str, binding["source"]))
        facility = ",".join(map(str, binding["facility"]))
        receipts = row["execution"]["receipts"]
        require(row["request"]["command"].endswith(" use-held-table") and row["delivered"] == 1
                and stock_at(row["before"], binding["actor"]).get(item) == 1
                and not stock_at(row["after"], binding["actor"]).get(item)
                and row["before"]["containers"][source] == row["after"]["containers"][source]
                and item in row["after"]["blocks"][facility] and item in row["clientAfter"]["blocks"][facility]
                and [receipt["stage"] for receipt in receipts] == ["WITHDRAW", "PLACE"]
                and all(receipt["placement"]["sourceIdentity"] == "held-table:" + binding["actor"] + ":0:1"
                        for receipt in receipts)
                and receipts[0]["placement"]["custodyAfter"] == receipts[1]["placement"]["custodyBefore"],
                "Physical held table, exact slots and placed block did not reconcile")
        events = "clients/PlayerA/events.jsonl"
        require_command(raw_slices(root, row, {events})[events], row["request"]["command"], row["request"]["feedback"], "PlayerA")
    if case.get("dynamic") in {"break-facility", "fill-destination", "remove-input"}:
        req = row["request"]
        mark = row["execution"]["dynamic"]["nativeClock"]
        key = "submission" if req["kind"] == "run" else "root"
        require(mark == read(root / "server/i00-native-boundary-reached.json") and mark[key] == req["identity"]
                and mark["principal"] == row["bindings"]["player"] and mark["boundary"] == "after-withdrawal"
                and mark["rate"] == 20 and mark["frozen"], "Connected mutation missed the original withdrawal at normal speed")
    if case.get("dynamic") == "competing-actors":
        execution = row["execution"]
        competition = execution["competition"]
        second = execution["competitor"]
        orders = [row["request"], second["request"]]
        ids = [order["identity"] for order in orders]
        views = [execution["views"][0], second["result"]["views"][0]]
        overlap = competition["overlap"]
        owner_view = overlap["knownPlayers"]["PlayerA"]
        observed = [owner_view["jobs"][identifier] for identifier in ids]
        require(owner_view["online"] and owner_view["uuid"] == row["bindings"]["player"]
                and [job["id"] for job in observed] == ids
                and len({job["request"]["request"]["arguments"]["actor"]["value"]["entityId"] for job in observed}) == 2
                and all(job["state"] in {"READY", "WAITING", "ACTIVE"} and job["fulfilled"] == 0 for job in observed)
                and [job["id"] for job in views] == ids and {job["state"] for job in views} == {"SUCCEEDED", "CANCELLED"}
                and sum(job["fulfilled"] for job in views) == row["requested"] == row["delivered"]
                and "cancellation=true" in competition["cancellation"]
                and not any(a["resourceReceipts"] for job in views if job["id"] == competition["loser"] for a in job["attempts"]),
                "Original contenders or attributed stock changed")
        events = "clients/PlayerA/events.jsonl"
        path = raw_slices(root, row, {events})[events]
        for order in orders:
            require_command(path, order["command"], order["feedback"], "PlayerA")
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
