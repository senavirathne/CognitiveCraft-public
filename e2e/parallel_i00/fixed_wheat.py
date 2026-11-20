"""Run and independently check the frozen three-wheat, 32-travel fixture."""

import argparse
from collections import Counter
import math
from pathlib import Path
import re
import sys
import traceback

from .gameplay import NativeGameplay, ROOT, witness_identity
from .identity import sha256
from .native_evidence import item_at, job_at, jsonl, require_command, stock_at, terminal_job, verify_frames, verify_installation
from suite import Harness, acquire_real_skill, atomic, read, require

sys.path.insert(0, str(ROOT / "e2e/parallel_a"))
from review_lifecycle_connected import chest, entities, original, properties
from review_receipt_prefix import block_name


PROFILE = "I00_FIXED_WHEAT_TRAVEL32"
CASE = "fixture:b07-fixed-wheat-travel32"
ITEM = "minecraft:wheat"
SOURCE = [6, 201, 10, 6, 201, 12]
PLANTS = [(6, 201, z) for z in (10, 11, 12)]
DESTINATION = [7, 201, 9]
POSE = [6.5, 201, 9.5]
CALLS = ("generationCalls", "needleCalls")
STAGES = {"HARVEST": 3, "PICKUP": 3, "DEPOSIT": 3}


class WheatHarness(Harness):
    compose = NativeGameplay.compose

    def __init__(self, root):
        super().__init__(root, "compose", None)
        self.profile = "acquire"
        metadata = read(self.root / "metadata.json")
        metadata["i00Profile"] = PROFILE
        atomic(self.root / "metadata.json", metadata)


def mature(frame, point):
    block = frame["blocks"].get(",".join(map(str, point)), "")
    return "minecraft:wheat" in block and "age=7" in block


def measure(h, admission, reuse):
    row = {"id": CASE, "status": "NOT_EXECUTED_REQUIRED", "admission": admission, "offlineReuse": reuse,
           "principal": h.players["PlayerA"], "actor": h.entities["PlayerA"], "citizen": h.citizens["PlayerA"]}
    try:
        h.console(["setblock 7 201 9 minecraft:air", "setblock 7 201 9 minecraft:chest",
                   "kill @e[type=minecraft:item,x=5,y=200,z=9,dx=4,dy=3,dz=4]"]
                  + [f"setblock {x} {y-1} {z} minecraft:farmland[moisture=7]" for x, y, z in PLANTS]
                  + [f"setblock {x} {y} {z} minecraft:wheat[age=7]" for x, y, z in PLANTS])
        h.wait(lambda: all(mature(h.snapshot(), pos) for pos in PLANTS) and not h.container(DESTINATION),
               "Exactly three original mature wheat plants and an empty destination")
        h.console([f"tp {row['actor']} 6.5 201 9.5"])

        def posed():
            frame = h.snapshot()
            actor = next(a for a in frame["actors"] if a["uuid"] == row["actor"])
            return frame if actor["alive"] and not actor["noAI"] and math.dist(actor["pos"], POSE) < .8 else None

        row["before"] = h.wait(posed, "Measured fixed starting pose for the original wheat Job", 15)
        row["beforeClient"] = h.client_snapshot("PlayerA")
        row["command"] = f"/aivillage kernel queue {row['citizen']} 3 " + " ".join(map(str, SOURCE + DESTINATION))
        h.actions_active = True
        job = h.submit(amount=3, source=SOURCE, destination=DESTINATION, queued=True)
        row.update(root=job, feedback=h.last_submission)
        row["first"] = h.wait(lambda: h.job_view("PlayerA", job), "Original wheat responsibility is visible")
        atomic(h.root / "fixed-wheat.json", row)
        row["final"] = terminal_job(h, "PlayerA", job, 160)
        row["menuQuantity"] = h.menu_count("PlayerA", DESTINATION)
        row["after"] = h.snapshot()
        row["acknowledgedSave"] = h.save_checkpoint()
        h.stop("server")
        row["status"] = "COLLECTED_PENDING_INDEPENDENT_REVIEW"
        return row
    finally:
        row["screenshots"] = h.screenshots
        atomic(h.root / "fixed-wheat.json", row)


def review(root, identity):
    metadata = read(root / "metadata.json")
    exact = witness_identity(metadata, identity.source, identity.jar_sha256, PROFILE)
    verify_installation(root, metadata, ("PlayerA", "PlayerB"))
    path = root / "fixed-wheat.json"
    row = read(path)
    require(row["status"] == "COLLECTED_PENDING_INDEPENDENT_REVIEW" and row["id"] == CASE,
            "Missing complete native fixed wheat trial")
    spec = read(ROOT / "e2e/parallel_b/qualification-specs.json")
    contract = next(c["expected"] for c in spec["boundaries"] if c["id"] == CASE)
    before, after, first, final = (row[key] for key in ("before", "after", "first", "final"))
    actor = next(a for a in before["actors"] if a["uuid"] == row["actor"])
    require(sum(mature(before, pos) for pos in PLANTS) == contract["sourcePlants"]
            and all(not mature(after, pos) for pos in PLANTS)
            and actor["alive"] and not actor["noAI"] and math.dist(actor["pos"], POSE) < .8
            and row["beforeClient"]["uuid"] == row["principal"] and row["beforeClient"]["play"] is True
            and before["pid"] == after["pid"] and not item_at(before, DESTINATION, ITEM)
            and item_at(after, DESTINATION, ITEM) == row["menuQuantity"] == contract["requested"]
            and not stock_at(before, row["actor"]).get(ITEM) and not stock_at(after, row["actor"]).get(ITEM)
            and all(before[k] == after[k] for k in CALLS), "Observed wheat stock, original pose or offline counter changed")
    attempt, = final["attempts"]
    totals = Counter()
    for receipt in attempt["receipts"]:
        totals[receipt["stage"]] += receipt["wheat"]
        require(list(receipt["source"][k] for k in ("minX", "minY", "minZ", "maxX", "maxY", "maxZ")) == SOURCE
                and list(receipt["destination"][k] for k in ("x", "y", "z")) == DESTINATION
                and receipt["actor"]["entityId"] == row["actor"], "A receipt crossed the original binding")
    binding = final["request"]
    require(final["state"] == "SUCCEEDED" and final["fulfilled"] == contract["requested"] and totals == STAGES
            and not attempt["uncertain"] and attempt["terminal"]["status"] == "SUCCEEDED"
            and final["generation"] == attempt["generation"] == 1
            and first["id"] == final["id"] == row["root"] and binding == first["request"] == attempt["bound"]
            and binding["context"]["principal"]["id"] == row["principal"]
            and final["allowance"] == first["allowance"]
            and attempt["allowance"]["deadlineEpochMillis"] == final["allowance"]["deadlineEpochMillis"]
            and all(n <= final["allowance"]["maxima"].get(k, 0) for k, n in attempt["allowance"]["maxima"].items())
            and final["allowance"]["maxima"]["TRAVEL_BLOCKS"] == contract["travelBudget"]
            and 0 < final["usage"]["TRAVEL_BLOCKS"] <= contract["travelBudget"],
            "The original Job failed or changed its fixed cumulative allowance")
    world = root / "server/game/world"
    stored = original(world, row["root"])
    durable, = stored["attempts"]
    require(stored["submission"] == final["submissionId"] and stored["state"] == "SUCCEEDED"
            and stored["request"]["principal"] == row["principal"] and stored["request"]["world"] == binding["context"]["scope"]["worldId"]
            and stored["allowance"]["deadline"] == final["allowance"]["deadlineEpochMillis"]
            and stored["allowance"]["maxima"]["TRAVEL_BLOCKS"] == contract["travelBudget"]
            and stored["usage"] == final["usage"] and durable["id"] == attempt["id"]
            and durable["credited"] == contract["requested"]
            and [r["id"] for r in durable["receipts"]] == [r["receiptId"] for r in attempt["receipts"]]
            and [ref["artifact"]["sha256"] for ref in durable["executions"]] == [row["admission"]["artifact"]]
            and row["admission"]["artifact"] == row["offlineReuse"]["artifact"], "Saved original owner, receipts or method differs")
    require(chest(world, DESTINATION) == {ITEM: 3} and not any(s["id"] == ITEM for s in entities(world)[row["actor"]].get("Inventory", []))
            and all(block_name(world, pos) != ITEM or properties(world, list(pos)).get("age") != "7" for pos in PLANTS),
            "Saved Minecraft terrain, villager or chest differs from the connected witness")
    require_command(root / "clients/PlayerA/events.jsonl", row["command"], row["feedback"], "PlayerA")
    require(len([e for e in jsonl(root / "clients/PlayerA/events.jsonl") if e["kind"] == "action"
                 and e["data"].get("text") == row["command"]]) == 1 and re.search("accepted=true", row["feedback"]),
            "Expected one original ordinary queued request")
    views = root / "server/observations.jsonl"
    require(any(f == before for f in jsonl(views)) and any(job_at(f, "PlayerA", row["root"]) == final for f in jsonl(views)),
            "Original native intake or terminal frame is absent")
    require(any(e["kind"] == "offline-backends" and all(e["data"].values()) for e in jsonl(root / "supervisor.jsonl")),
            "No independently stopped model and local interpreter")
    verify_frames(root, row["screenshots"])
    return {**exact, "id": CASE, "status": "PASS_INDEPENDENT_FIXED_WHEAT", "sourcePlants": 3,
            "wheatDelivered": 3, "travelBudget": 32, "travelUsed": final["usage"]["TRAVEL_BLOCKS"], "budgetRenewals": 0,
            "originalRoot": final["id"], "originalAttempt": attempt["id"], "receipts": totals,
            "modelCalls": {k: after[k] - before[k] for k in CALLS}, "evidenceSha256": sha256(path)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("run", "review"))
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--source")
    parser.add_argument("--jar-sha256")
    args = parser.parse_args()
    if args.mode == "run":
        h = WheatHarness(args.root)
        try:
            acquire_real_skill(h, after_offline_reuse=measure)
        finally:
            h.finish()
        return
    try:
        result = review(args.root, args)
    except Exception as error:
        result = {"id": CASE, "status": "FAIL_FIXED_WHEAT", "reason": str(error), "trace": traceback.format_exc()}
    atomic(args.root / "review.json", result)
    print(result)
    if result["status"] != "PASS_INDEPENDENT_FIXED_WHEAT":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
