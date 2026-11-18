"""Qualify the eleven added immutable methods with a real client, then reuse them cold."""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import traceback

from .gameplay import NEW_PLANKS, NativeGameplay, PRODUCTION, variant_input, variant_material, witness_identity
from campaigns.crafting.evidence import evidence_path
from campaigns.crafting.harness import coord, fixture_observation
from campaigns.crafting.invariants import recipe_balance, totals
from campaigns.crafting.oracle import requirements
from parallel_b.guards import clean_owners
from runtime import digest
from suite import atomic, completed_jsonl, read, reconnect_players, require

PROFILE = "I00_VARIANT_ADMISSION"
REPOSITORY = "server/game/world/data/cognitivecraft/skills/v1"


def yield_size(output):
    return 2 if variant_material(output) == "bamboo" else 4


def settle(h):
    h.wait(lambda: clean_owners(h.snapshot()["knownPlayers"]["PlayerA"]),
           "Real origin, attempt and custody owners settled", 60)
    h.actions_active = False


def stopped_method_copies(root, artifacts):
    require(set(artifacts) == set(NEW_PLANKS), "The complete set of admitted native variants is required")
    source, destination = root / REPOSITORY, root / "stopped-skills"
    destination.mkdir()
    (destination / "bodies").mkdir()
    shutil.copy2(source / "manifest.json", destination / "manifest.json")
    for pin in artifacts.values():
        require(re.fullmatch("[a-f0-9]{64}", pin), "Invalid admitted artifact identity")
        name = pin + ".json"
        shutil.copy2(source / "bodies" / name, destination / "bodies" / name)


def cold_reuse(h, output, pin):
    recipe = h.recipes[output]
    quantity = yield_size(output) + 1
    h.family = "VARIANT-COLD-" + variant_material(output)
    case = {"id": h.family, "mode": "recipe", "output": output, "recipe": output, "quantity": quantity,
            "grid": h.admission_grid(output, recipe), "surplus": 0, "actor": 1, "location": 1,
            "layout": "split", "container": "barrel"}
    probe = h.probe(case)
    h.prepare(case, requirements(probe, quantity))
    before, client_before = fixture_observation(h)
    calls = h.model_calls()
    request = h.submit_command(h.admission_command_text(case))
    result = h.finish_request(request, 240)
    require(result["completed"] and result["views"][0]["marker"]["outcome"] == "SUCCEEDED",
            "Saved method did not complete as known gameplay: " + output)
    require(result["views"][0]["marker"]["artifactSha256"] == pin, "Cold request changed the immutable method")
    settle(h)
    after, client_after = h.snapshot(), h.client_snapshot("PlayerA")
    destination_count = h.menu_count("PlayerA", h.destination, output)
    frame = h.screenshot("PlayerA", "cold-variant-reuse", chat=True)
    return {"capability": output, "status": "PASS", "case": case, "nativeProbe": probe,
            "source": h.source, "destination": h.destination, "request": request, "result": result,
            "before": before, "after": after, "clientBefore": client_before, "clientAfter": client_after,
            "callsBefore": calls, "callsAfter": h.model_calls(), "destinationClientCount": destination_count,
            "frame": frame}


def run(root):
    root.mkdir(parents=True, exist_ok=False)
    result = {"profile": PROFILE, "status": "FAIL_REQUIRED_VARIANTS", "stage": "startup", "cold": []}
    h = None
    try:
        h = NativeGameplay(root, profile=PROFILE)
        witness_identity(read(root / "metadata.json"), os.environ["GITHUB_SHA"], digest(PRODUCTION), PROFILE)
        result["stage"] = "live-admission"
        h.bootstrap(set(NEW_PLANKS))
        result["live"] = [{"capability": row["capability"], "status": row["status"], "reason": row.get("error")}
                          for row in h.admissions]
        for row in result["live"]:
            print("I00_VARIANT_ADMISSION " + json.dumps(row), flush=True)
        require(set(h.artifacts) == set(NEW_PLANKS), "Missing genuine native admissions for: "
                + ", ".join(item for item in NEW_PLANKS if item not in h.artifacts))
        settle(h)
        result["stage"] = "stopped-world-restart"
        result.update(admissionComplete=len(h.artifacts), beforeStopCalls=h.model_calls(),
                      beforeStopObservation=h.snapshot(), checkpoint=h.save_checkpoint(), firstServer=read(root / "server/process.json"))
        h.stop("server")
        stopped_method_copies(root, h.artifacts)
        # Recreate only the acknowledged stopped container. Its world and
        # configuration remain the very same bind-mounted Minecraft files.
        h.compose("rm", "-f", "minecraft")
        h.start("server")
        reconnect_players(h, ["PlayerA"])
        h.assert_backend_stopped()
        h.command("PlayerA", "/aivillage kernel inference false", "admission=false")
        restart = h.snapshot()
        result.update(secondServer=read(root / "server/process.json"), afterRestartCalls=h.model_calls(),
                      restartObservation=restart)
        result["stage"] = "offline-reuse"
        for output in NEW_PLANKS:
            try:
                row = cold_reuse(h, output, h.artifacts[output])
            except Exception as error:
                row = {"capability": output, "status": "FAIL", "reason": str(error), "trace": traceback.format_exc()}
                h.cleanup()
            result["cold"].append(row)
            atomic(root / "variant-run.json", result)
            print("I00_VARIANT_REUSE " + json.dumps({"item": output, "status": row["status"],
                                                     "reason": row.get("reason")}), flush=True)
        if all(row["status"] == "PASS" for row in result["cold"]):
            result.update(status="COLLECTED_PENDING_INDEPENDENT_REVIEW", stage="complete")
    except Exception as error:
        result.update(reason=str(error), trace=traceback.format_exc())
    finally:
        if h:
            h.finish()
        atomic(root / "variant-run.json", result)
        print("I00_VARIANT_PRODUCER " + json.dumps({k: result[k] for k in ("status", "stage", "reason") if k in result}),
              flush=True)
    return result["status"] == "COLLECTED_PENDING_INDEPENDENT_REVIEW"


def canonical_digest(value):
    wire = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(wire.encode()).hexdigest()


def body_review(stopped, current, manifest, output, pin, owner):
    capability = "cognitivecraft:produce_" + variant_material(output) + "_planks"
    ref = {"capability": capability, "version": 1, "sha256": pin}
    entry = [entry for entry in manifest["entries"] if entry["ref"] == ref]
    require(len(entry) == 1 and entry[0]["admission"] == "ADMITTED", "No acknowledged admitted method entry")
    entry = entry[0]
    require(entry["gameTarget"] == "minecraft-26.3" and not entry["pinned"]
            and any(origin["owner"]["principal"] == owner for origin in entry["origins"]),
            "Method scope, game or dependencies differ")
    name = "bodies/" + pin + ".json"
    witness, final = evidence_path(stopped, name), evidence_path(current, name)
    require(witness.stat().st_size == entry["bodyBytes"] and digest(witness) == entry["bodySha256"]
            and digest(final) == entry["bodySha256"], "Admitted method bytes changed after actual stop/restart")
    body = read(witness)
    require(body["schema"] == 1 and body["descriptor"]["ref"] == ref
            and canonical_digest({"contract": 1, "outcome": body["spec"], "ir": body["ir"]}) == pin,
            "Method digest does not independently match the saved compiled contract and IR")
    instructions = body["ir"]["body"]
    cycle = ["move_to_input", "withdraw_plank_variant_input", "craft_plank_variant",
             "move_to_destination", "transfer_plank_variant"]
    require(len(instructions) == 3 and instructions[0]["id"] == "cognitivecraft:observe_plank_variant_inputs"
            and instructions[1]["count"]["right"] == {"int": yield_size(output)}
            and [node["id"] for node in instructions[1]["body"]] == ["cognitivecraft:" + item for item in cycle]
            and instructions[2] == {"op": "result", "value": {"param": "amount"}},
            "Saved method body does not bind the installed batch and original quantity")
    return entry["bodySha256"]


def review_observation(row, output, pin, *, cold):
    quantity = yield_size(output) + int(cold)
    ingredient = variant_input(output)
    request, case, probe = row["request"], row["case"], row["nativeProbe"]
    before, after, client_before, client_after = (row["before"], row["after"], row["clientBefore"], row["clientAfter"])
    owner = before["knownPlayers"]["PlayerA"]["uuid"]
    require(all(client["play"] is True and client["singleplayer"] is False and client["uuid"] == owner
                for client in (client_before, client_after))
            and after["knownPlayers"]["PlayerA"]["uuid"] == owner, "No independently matched dedicated-server client")
    require(case["quantity"] == quantity and case["recipe"] == output and probe["caseId"] == case["id"]
            and probe["grid"] == case["grid"] and [item for item in case["grid"] if item] == [ingredient]
            and probe["count"] == yield_size(output) and probe["output"] == output
            and probe["worldEffects"] == 0, "Incorrect native installed recipe or admission fixture")
    expected = requirements(probe, quantity)
    result, view = row["result"], row["result"]["views"][0]
    actor = view["marker"]["citizenId"]
    command = f"/aivillage kernel make-variant-planks {variant_material(output)} {actor} {quantity} " + coord(
        row["source"] + row["destination"])
    require(request["command"] == command and request["kind"] == "run" and request["accepted"] is True
            and request["identity"] == view["id"] and view["phase"] == "TERMINAL"
            and result["completed"] is True and view["marker"]["artifactSha256"] == pin
            and view["marker"]["outcome"] == ("SUCCEEDED" if cold else "ADMITTED"),
            "A different command, original run or immutable method was recorded")
    source, destination = (",".join(map(str, row[k])) for k in ("source", "destination"))
    balanced, balance = recipe_balance(totals(before, client_before), totals(after, client_after), expected)
    require(balanced and balance["batches"] == expected["batches"]
            and before["containers"][source].get(ingredient) == expected["batches"]
            and after["containers"][source].get(ingredient, 0) == 0
            and before["containers"][destination].get(output, 0) == 0
            and after["containers"][destination].get(output) == quantity
            and before["containers"][source].get("minecraft:cobblestone") == after["containers"][source].get("minecraft:cobblestone") == 2,
            "Independent input, native whole-batch output or delivered quantity differs")
    receipts = view["resourceReceipts"]
    stages = Counter()
    for receipt in receipts:
        phase = receipt["stage"]
        require(phase in {"WITHDRAW", "CRAFT", "DEPOSIT"} and receipt["actor"]["citizenId"] == actor
                and receipt["origin"]["principal"]["id"] == owner
                and receipt["item"]["id"] == (ingredient if phase == "WITHDRAW" else output)
                and receipt["transformation"]["recipe"]["key"] == output,
                "Effect receipt uses a different material, recipe or owner")
        stages[phase] += receipt["quantity"]
    require(stages == {"WITHDRAW": expected["batches"], "CRAFT": expected["produced"], "DEPOSIT": quantity}
            and len(receipts) == 3 * expected["batches"]
            and len({receipt["operationId"] for receipt in receipts}) == len(receipts),
            "Missing or repeated actual transformation receipts")
    if cold:
        require(row["callsBefore"] == row["callsAfter"] and row["destinationClientCount"] == quantity,
                "Cold method called a model or the actual client received a different quantity")
        require(all(row[column]["brokerStats"]["calls"] == row[column]["brokerStats"]["dispatched"] == 0
                    for column in ("before", "after")), "Cold JVM dispatched generation work")
        observed = after["actors"]
        entities = {receipt["actor"]["entityId"] for receipt in receipts}
        require(len(entities) == 1 and sum(a["inventory"].get(output, 0) for a in observed
                if a["uuid"] in entities) == expected["surplus"], "Actual actor retained surplus differs")
    else:
        require(row["callsAfter"]["generationCalls"] > row["callsBefore"]["generationCalls"],
                "Admission lacked a real model call")
    return owner, balance


def verify_server_frames(path, records):
    expected = {(record["time"], record["tick"], record["pid"]): record for record in records}
    with path.open() as stream:
        for line in stream:
            frame = json.loads(line)
            key = frame["time"], frame["tick"], frame["pid"]
            if key in expected:
                require(frame == expected.pop(key), "Copied physical frame differs from original server observation")
                if not expected:
                    return
    require(not expected, "Missing original native physical frames")


def verify_cold_backend(frame, server):
    # generationCalls describes retained durable run history. It survives a JVM
    # restart and can shrink when bounded history retires old entries. The
    # session broker observes actual new dispatches and measured backend calls.
    require(frame["pid"] == server["pid"] and frame["time"] >= server["start"] * 1000
            and frame["brokerStats"]["calls"] == frame["brokerStats"]["dispatched"] == 0,
            "Missing current-JVM evidence of zero new generation calls")


def review(root, source, jar):
    result = {"profile": PROFILE, "status": "FAIL_REQUIRED_VARIANTS", "cases": []}
    try:
        result.update(witness_identity(read(root / "metadata.json"), source, jar, PROFILE))
        collection, admission = read(root / "variant-run.json"), read(root / "admissions.json")
        require(collection and collection["status"] == "COLLECTED_PENDING_INDEPENDENT_REVIEW",
                "Producer failed during " + (collection or {}).get("stage", "unavailable") + ": "
                + (collection or {}).get("reason", "see native case outcomes"))
        require(admission and set(admission["artifacts"]) == set(NEW_PLANKS) and len(admission["rows"]) == len(NEW_PLANKS)
                and len(collection["cold"]) == len(NEW_PLANKS), "Missing full admission or cold-reuse denominator")
        first, second = collection["firstServer"], collection["secondServer"]
        require(first["start"] < second["start"] and first["releaseSha256"] == second["releaseSha256"] == jar
                and all(any(mod["name"] == PRODUCTION.name and mod["sha256"] == jar for mod in server["mods"])
                        for server in (first, second)),
                "The two Minecraft JVMs did not use the exact binary")
        events = completed_jsonl(root / "supervisor.jsonl")
        require(any(event["kind"] == "process-stop-witness" and event["data"]["process"] == first
                    and event["data"]["hard"] is False and event["data"]["state"]["ExitCode"] == 0 for event in events)
                and any(event["kind"] == "server-disconnection-witness" and event["data"]["server"] == first
                        and event["data"]["client"]["play"] is False for event in events),
                "No owned container exit or real client disconnection")
        restart = collection["restartObservation"]
        stop = collection["beforeStopObservation"]
        verify_cold_backend(restart, second)
        require(sum(event["kind"] == "crafting-backend-unavailable" for event in events) >= 2
                and collection["afterRestartCalls"]["generationCalls"] == restart["generationCalls"]
                and collection["beforeStopCalls"]["generationCalls"] >= len(NEW_PLANKS),
                "Missing verified unavailable inference after restart")
        frames = read(root / "screenshots.json")["captures"]
        commands = {event["data"]["text"] for event in events if event["kind"] == "command"}
        client = completed_jsonl(root / "clients/PlayerA/events.jsonl")
        client_commands = {event["data"]["text"] for event in client if event["kind"] == "action"
                           and event["data"]["action"] == "command"}
        require(stop["pid"] == first["pid"] and first["start"] * 1000 <= stop["time"] < second["start"] * 1000
                and stop["brokerStats"]["calls"] >= len(NEW_PLANKS), "Missing original warm-session measured calls")
        physical = [stop, restart]
        stopped, current = root / "stopped-skills", root / REPOSITORY
        saved = read(stopped / "manifest.json")
        content = {key: saved[key] for key in ("schema", "revision", "entries")}
        require(saved["schema"] == 1 and canonical_digest(content) == saved["checksum"], "Invalid saved method manifest checksum")
        for live, cold, output in zip(admission["rows"], collection["cold"], NEW_PLANKS, strict=True):
            require(live["capability"] == cold["capability"] == output and live["status"] == cold["status"] == "PASS",
                    "Variant missing in different runtime incarnations: " + output)
            pin = admission["artifacts"][output]
            live_owner, live_balance = review_observation(live, output, pin, cold=False)
            cold_owner, cold_balance = review_observation(cold, output, pin, cold=True)
            require(live_owner == cold_owner and live["request"]["identity"] != cold["request"]["identity"]
                    and live["case"]["actor"] != cold["case"]["actor"]
                    and live["source"] != cold["source"] and live["destination"] != cold["destination"],
                    "Cold reuse did not bind a new run, worker and physical addresses for the original principal")
            for row in (live, cold):
                frame = row["frame"]
                require(frame in frames and frame["nativeClientFrame"] is True
                        and digest(evidence_path(root, frame["path"])) == frame["sha256"]
                        and row["request"]["command"] in commands & client_commands,
                        "Missing original connected-client command/frame")
                server = first if row is live else second
                stop = second["start"] * 1000 if row is live else float("inf")
                for column in ("before", "after"):
                    snapshot = row[column]
                    require(snapshot["pid"] == server["pid"] and server["start"] * 1000 <= snapshot["time"] < stop,
                            "Physical frame belongs to the wrong server process")
                    physical.append(snapshot)
            result["cases"].append({"item": output, "method": pin, "live": live_balance, "cold": cold_balance,
                                    "bodySha256": body_review(stopped, current, saved, output, pin, live_owner)})
        verify_server_frames(root / "server/observations.jsonl", physical)
        result["modelCallsAcrossJvms"] = {"admissionSession": stop["brokerStats"]["calls"], "coldSession": 0,
                                        "cumulative": stop["brokerStats"]["calls"], "freshColdCalls": 0}
        result["status"] = "PASS_INDEPENDENT_NATIVE_REVIEW"
    except Exception as error:
        result.update(reason=str(error), trace=traceback.format_exc())
    atomic(root / "variant-review.json", result)
    print("I00_VARIANT_REVIEW " + json.dumps({**result, "cases": len(result["cases"])}), flush=True)
    return result["status"] == "PASS_INDEPENDENT_NATIVE_REVIEW"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parsers = parser.add_subparsers(dest="command", required=True)
    runner, reader = parsers.add_parser("run"), parsers.add_parser("review")
    runner.add_argument("--root", type=Path, required=True)
    reader.add_argument("--root", type=Path, required=True)
    reader.add_argument("--source", required=True)
    reader.add_argument("--jar-sha256", required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    ok = run(root) if args.command == "run" else review(root, args.source, args.jar_sha256)
    if not ok:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
