"""Execute and independently review the frozen B08 held-outs through real Jobs."""
from __future__ import annotations

import argparse
from collections import Counter
import json
import math
from pathlib import Path
import traceback

from .campaign import admissions
from .campaign_review import HELD_OUT_PROFILE as PROFILE
from .frozen_campaign import HELD_OUT_COUNT, HELD_OUT_SHA256, held_out
from .gameplay import NativeGameplay, point, witness_identity
from .native_evidence import (closed_job, exact_withdrawals, item_at, raw_slices, request_job, require_command,
                              require_native_primitive, stock_at, terminal_job, trace_offsets, verify_frames, verify_installation)
from campaigns.crafting.generator import BIRCH, OAK
from campaigns.crafting.harness import coord
from campaigns.crafting.invariants import delta, totals
from campaigns.crafting.oracle import requirements
from campaigns.crafting.run import log_slices
from parallel_b.guards import clean_owners
from runtime import digest
from suite import atomic, read, require

COLLECTED = "COLLECTED_PENDING_INDEPENDENT_REVIEW"


def enroll_peer(h):
    h.start("PlayerB")
    h.console(["op PlayerB", "summon minecraft:villager 81.5 208 76.5 {PersistenceRequired:1b}",
               "tp PlayerB 81.5 208 77.5", "gamemode survival PlayerB"])
    def arrived():
        state = h.client_snapshot("PlayerB")
        return state and math.dist(state["pos"], [81.5, 208, 77.5]) < 1
    h.wait(arrived, "Peer native connection and enrollment pose", 30)
    h.enroll("PlayerB", "Lina")
    require(h.players["PlayerB"] != h.players["PlayerA"] and h.entities["PlayerB"] != h.subjects[0][1],
            "Held-outs require two genuinely distinct principals and actors")
    h.console(["deop PlayerA", "deop PlayerB"], lifecycle=True)


def validate(case, row):
    binding, expected = case["nativeBindings"], case["expected"]
    player, actor = binding["player"], row["actor"]
    source, destination = point(binding["source"]), point(binding["destination"])
    material = case["bindings"]["material"]
    output = binding["goal"]["outputItem"]
    before, after = row["before"], row["after"]
    pose = next(subject for subject in before["actors"] if subject["uuid"] == actor)
    require(pose["alive"] and not pose["noAI"] and math.dist(pose["pos"], binding["actorPose"]) < 1,
            "Frozen actor pose differs in the pre-request native frame")
    require(before["knownPlayers"][player]["uuid"] == after["knownPlayers"][player]["uuid"] == row["principal"],
            "Foreign or drifted held-out principal")
    for client in (row["clientBefore"], row["clientAfter"]):
        require(client["uuid"] == row["principal"] and client["play"] is True and client["singleplayer"] is False,
                "Missing dedicated connected-client witness")
    require(not stock_at(before, actor) and item_at(before, source, material) == expected["inputLogs"]
            and item_at(before, destination, output) == 0, "Native held-out start differs from frozen stock")
    require(item_at(after, source, material) == 0 and item_at(after, destination, output) == expected["delivered"]
            and stock_at(after, actor).get(output, 0) == expected["actorSurplus"] and row["clientMenuCount"] == expected["delivered"],
            "Native held-out input, delivery or retained custody differs")
    require(delta(totals(before, row["clientBefore"]), totals(after, row["clientAfter"])) ==
            {material: -expected["inputLogs"], output: expected["physicalPlanks"]}, "Independent participant totals do not conserve material")
    oracle = requirements(row["vanillaProbe"], expected["rootRequested"])
    require(row["vanillaProbe"]["caseId"] == case["id"] and row["vanillaProbe"]["recipe"] == output and row["vanillaProbe"]["grid"] == [material]
            and oracle["consumed"] == {material: expected["inputLogs"]} and oracle["produced"] == expected["physicalPlanks"],
            "Held-out production differs from the loaded native recipe")
    job = row["originalJob"]
    arguments = job["request"]["request"]["arguments"]
    require(job["id"] == row["request"]["root"] and job["state"] == "SUCCEEDED" and closed_job(job)
            and job["fulfilled"] == arguments["amount"]["value"] == expected["rootRequested"], "Held-out root quantity or terminal settlement changed")
    require(arguments["actor"]["value"]["entityId"] == actor, "Root worker differs from native held-out binding")
    steps = [step for step in job["activity"]["steps"] if step.get("choice")]
    require(steps and {step["artifact"]["sha256"] for step in steps} == {row["methodPin"]}, "No actual selected recipe pin")
    receipts = Counter()
    actual = [receipt for attempt in job["attempts"] for receipt in attempt["resourceReceipts"]]
    for receipt in actual:
        require(receipt["origin"]["principal"]["id"] == row["principal"] and receipt["actor"]["entityId"] == actor,
                "Held-out effect authority changed")
        if receipt["stage"] != "WITHDRAW":
            receipts[receipt["stage"], receipt["item"]["id"]] += receipt["quantity"]
    require(exact_withdrawals(job, actual) == {material: expected["inputLogs"]}
            and receipts == {("CRAFT", output): expected["physicalPlanks"], ("DEPOSIT", output): expected["delivered"]},
            "Held-out native receipts differ from independent custody")
    require(row["modelCallsBefore"] == row["modelCallsAfter"], "Known-method held-out invoked the stopped model")


def run_case(h, case, folder):
    binding = case["nativeBindings"]
    player, output, quantity = binding["player"], binding["goal"]["outputItem"], binding["goal"]["quantity"]
    source, destination = point(binding["source"]), point(binding["destination"])
    actor = h.subjects[0][1] if player == "PlayerA" else h.entities[player]
    citizen = h.subjects[0][0] if player == "PlayerA" else h.citizens[player]
    offset = trace_offsets(h.root)
    h.family = case["id"]
    row = {"case": case, "status": "NOT_EXECUTED_REQUIRED"}
    material = case["bindings"]["material"]
    try:
        require(all(clean_owners(p) for p in h.snapshot()["knownPlayers"].values()), "Previous held-out owners remain unsettled")
        h.console(["fill 63 208 63 84 211 78 minecraft:air", "fill 62 207 62 85 207 80 minecraft:stone",
                   "kill @e[type=minecraft:item,x=63,y=207,z=63,dx=21,dy=5,dz=15]", "clear " + player,
                   "tp " + player + " 66.5 208 69.5"])
        for _, entity in [*h.subjects, (h.citizens["PlayerB"], h.entities["PlayerB"])]:
            h.actor_stock({}, entity)
        h.stock(source, binding["startingSource"])
        h.stock(destination, {})
        if binding["setupBlocks"]:
            h.console(["setblock " + coord(block["position"]) + " " + block["block"] for block in binding["setupBlocks"]])
        probe = h.probe({"id": case["id"], "recipe": output, "grid": [material]})
        h.assert_backend_stopped()
        require(output in h.artifacts, "No genuine admitted method for held-out " + output)
        h.clock("tick freeze")
        try:
            h.console(["tp " + actor + " " + coord(binding["actorPose"])])

            def posed():
                frame = h.snapshot()
                return frame if any(a["uuid"] == actor and math.dist(a["pos"], binding["actorPose"]) < 1
                                    for a in frame["actors"]) else None

            before = h.wait(posed, "Frozen actor position observed before gameplay", 30)
            row.update(principal=h.players[player], actor=actor, citizen=citizen, before=before,
                       clientBefore=h.client_snapshot(player), vanillaProbe=probe, modelCallsBefore=h.model_calls(), methodPin=h.artifacts[output])
        finally:
            h.clock("tick unfreeze")
        request = request_job(h, player, h.recipe_command(citizen, output, quantity, source, destination))
        row["request"] = request
        require(request["accepted"] and request["root"], "No real held-out Job admission")
        row.update(originalJob=terminal_job(h, player, request["root"]), after=h.snapshot(), clientAfter=h.client_snapshot(player),
                   modelCallsAfter=h.model_calls(), clientMenuCount=h.menu_count(player, destination, output))
        validate(case, row)
        h.wait(lambda: all(clean_owners(p) for p in h.snapshot()["knownPlayers"].values()), "Closed native held-out owners", 35)
        h.actions_active = False
        h.screenshot(player, "held-out-terminal", chat=True)
        row["status"] = COLLECTED
    except Exception as error:
        row.update(status="FAIL_NATIVE", reason=str(error), trace=traceback.format_exc())
    finally:
        row["rawLogs"] = log_slices(h.root, folder, offset)
        row["screenshots"] = [frame for frame in h.screenshots if frame["family"] == case["id"]]
        atomic(folder / "result.json", row)
    return row


def run(root):
    root.mkdir(parents=True, exist_ok=False)
    plan = held_out()
    result = {"profile": PROFILE, "status": "FAIL_REQUIRED_CASES", "frozenHeldOutSha256": HELD_OUT_SHA256,
              "cases": {case["id"]: "NOT_EXECUTED_REQUIRED" for case in plan["cases"]}}
    h = None
    try:
        h = NativeGameplay(root, profile=PROFILE)
        h.bootstrap({OAK, BIRCH})
        enroll_peer(h)
        for case in plan["cases"]:
            folder = root / "cases" / case["id"]
            folder.mkdir(parents=True)
            row = run_case(h, case, folder)
            result["cases"][case["id"]] = row["status"]
            atomic(root / "held-out.json", result)
            print("I00_HELD_OUT_CASE " + json.dumps({"id": case["id"], "status": row["status"], "reason": row.get("reason")}), flush=True)
            if h.actions_active:
                raise ValueError("Original held-out work remains active or unfenced")
        if all(status == COLLECTED for status in result["cases"].values()):
            result["status"] = COLLECTED
    except Exception as error:
        result.update(error=str(error), trace=traceback.format_exc())
    finally:
        if h:
            h.finish()
        atomic(root / "held-out.json", result)
    return result["status"] == COLLECTED


def review(root, source, jar):
    result = {"profile": PROFILE, "requiredCases": HELD_OUT_COUNT, "status": "FAIL_REQUIRED_CASES", "cases": []}
    try:
        metadata = read(root / "metadata.json")
        result.update(witness_identity(metadata, source, jar, PROFILE))
        verify_installation(root, metadata, ("PlayerA", "PlayerB"))
        pins = admissions(root)
        for case in held_out()["cases"]:
            path = root / "cases" / case["id"] / "result.json"
            try:
                row = read(path)
                require(row["status"] == COLLECTED and row["case"] == case, "Missing or foreign native held-out result")
                player, request = case["nativeBindings"]["player"], row["request"]
                events = f"clients/{player}/events.jsonl"
                paths = raw_slices(root, row, {events, "server/stdout.log", "server/observations.jsonl", "supervisor.jsonl"})
                require_command(paths[events], request["command"], request["feedback"], player)
                require_native_primitive(paths["server/stdout.log"])
                verify_frames(root, row["screenshots"], minimum=2)
                require(pins[case["nativeBindings"]["goal"]["outputItem"]] == row["methodPin"], "Missing real admitted held-out method")
                validate(case, row)
                result["cases"].append({"id": case["id"], "status": "PASS", "resultSha256": digest(path)})
            except Exception as error:
                result["cases"].append({"id": case["id"], "status": "FAIL_REVIEW", "reason": str(error)})
        if all(row["status"] == "PASS" for row in result["cases"]):
            result["status"] = "PASS_INDEPENDENT_NATIVE_REVIEW"
    except Exception as error:
        result.update(error=str(error), trace=traceback.format_exc())
    atomic(root / "review.json", result)
    return result["status"] == "PASS_INDEPENDENT_NATIVE_REVIEW"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("run", "review"):
        cmd = commands.add_parser(name)
        cmd.add_argument("--root", type=Path, required=True)
        if name == "review":
            cmd.add_argument("--source", required=True)
            cmd.add_argument("--jar-sha256", required=True)
    args = parser.parse_args()
    passed = run(args.root.resolve()) if args.command == "run" else review(args.root.resolve(), args.source, args.jar_sha256)
    if not passed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
