"""Connected-client q3/y4 exact-choice and old-output-stock I00 controls."""

import argparse
from collections import Counter
import json
import gzip
from pathlib import Path
import re
import shutil
import subprocess
import sys
import traceback
from uuid import UUID

from .identity import PRODUCTION, ROOT, candidate, sha256, verify_metadata
from .gameplay import QUALIFICATION_COMPOSE

sys.path.insert(0, str(ROOT / "e2e/parallel_a"))
from native_nbt import read_nbt
from review_lifecycle_connected import chest, chest_row, entities, original
from review_receipt_prefix import item
from campaigns.crafting.oracle import candidates
from parallel_b.baseline import DESTINATION, LOG, OTHER, PLANK, SOURCE, coord
from parallel_b.guards import clean_owners, instruments, observer_inputs, stopped_process
from parallel_b.qualification import safe
from runtime import SDK
from suite import Harness, UUID_PATTERN, atomic, completed_jsonl, read, require


LIVE = {"birch-q3-root": (DESTINATION, 0), "birch-q3-same-container-old-stock": (SOURCE, 11)}
LOOKALIKE = "component-bearing-lookalike"
REMOVAL = "player-removes-checkpoint-stock"
PENDING = ("birch-q3-child", "birch-q3-crash-before-allocation", "contending-production-actors")
QUANTITIES = {"demand": 3, "yield": 4, "batches": 1, "produced": 4, "allocation": 3,
              "surplus": 1, "remainders": 0}
STAGES = ["WITHDRAW", "CRAFT", "DEPOSIT"]


class Controls(Harness):
    def compose(self, *args):
        command = ["docker", "compose", "-p", self.project, "-f", str(ROOT / "e2e/compose.yml"),
                   "-f", str(QUALIFICATION_COMPOSE), *args]
        result = subprocess.run(command, cwd=ROOT, env=self.env, text=True, capture_output=True, timeout=400)
        with (self.root / "docker.log").open("a") as log:
            log.write(result.stdout + result.stderr)
        if result.returncode:
            raise RuntimeError("I00 Docker Compose: " + result.stderr[-2000:])
        return result


def game_seed(h, seed, identity):
    verify_metadata(read(seed / "metadata.json"), identity)
    qualified = read(seed / "qualification.json")
    require((qualified["integrity"], qualified["mandatoryGameplay"]) == ("PASS", "PASS"),
            "The exact current legacy quantity seed did not qualify")
    admission = read(seed / "canonical.json")
    require(admission["view"]["marker"]["outcome"] == "ADMITTED"
            and admission["callsAfter"]["generationCalls"] > admission["callsBefore"]["generationCalls"],
            "A genuinely admitted existing birch method must be available")
    actor = read(seed / "q4.json")["root"]["request"]["request"]["arguments"]["actor"]["value"]
    game = h.root / "server/game"
    shutil.copytree(seed / "server/game", game, ignore=shutil.ignore_patterns("session.lock"))
    properties = game / "server.properties"
    body, count = re.subn(r"(?m)^server-port=.*$", f"server-port={h.port}", properties.read_text())
    require(count == 1, "The stopped seed must have exactly one server port")
    properties.write_text(body)
    h.citizens["PlayerA"], h.entities["PlayerA"] = actor["citizenId"], actor["entityId"]
    result = {"method": admission["view"]["marker"]["artifactSha256"], "actor": actor,
              "manifestSha256": sha256(seed / "manifest.json"), **identity, "importedPasses": 0}
    atomic(h.root / "seed.json", result)
    return result


def save_world(h, name, phase="saved-world"):
    h.save_checkpoint()
    shutil.copytree(h.root / "server/game/world", h.root / "cases" / name / phase,
                    ignore=shutil.ignore_patterns("session.lock"))


def production_command(h, destination):
    return ("/aivillage kernel production-activity " + h.citizens["PlayerA"]
            + " birch-planks 3 " + coord(SOURCE + destination))


def exercise(h, name, destination, old):
    slots = ['{Slot:26b,id:"minecraft:birch_log",count:1}', '{Slot:0b,id:"minecraft:cobblestone",count:2}']
    if old:
        slots.append('{Slot:1b,id:"minecraft:birch_planks",count:11}')
    h.console(["data merge block " + coord(SOURCE) + " {Items:[" + ",".join(slots) + "]}"]
              + ([] if old else ["data merge block " + coord(destination) + " {Items:[]}"]))
    h.wait(lambda: h.container(SOURCE, LOG) == 1 and h.container(destination, PLANK) == old,
           "Actual installed original q3 inventory")
    require(h.menu_count("PlayerA", SOURCE, LOG) == 1 and h.menu_count("PlayerA", destination, PLANK) == old,
            "Connected client did not acknowledge the original q3 start")
    before = h.snapshot()
    command = production_command(h, destination)
    h.actions_active = True
    feedback = h.command("PlayerA", command, r"activity=|job=|accepted=false|(?i:unknown|incorrect|unavailable)", 45)
    match = re.search(r"(?:activity|job)=(" + UUID_PATTERN + ")", feedback)
    require(match and "accepted=true" in feedback, "Public exact-choice admission refused: " + feedback)
    root = match.group(1)

    def terminal():
        frame = h.snapshot()
        job = frame["players"]["PlayerA"]["jobs"].get(root)
        if job and job["state"] in ("SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED"):
            return frame
        return None

    after = h.wait(terminal, "Original productive activity became terminal", 240)
    require(after["players"]["PlayerA"]["jobs"][root]["state"] == "SUCCEEDED", "Original production root failed")
    h.wait(lambda: clean_owners(h.snapshot()["knownPlayers"]["PlayerA"]), "Original runtime owners settled", 45)
    view = h.command("PlayerA", "/aivillage kernel activity " + root, r"activity=" + root)
    h.actions_active = False
    require(h.menu_count("PlayerA", destination, PLANK) == old + 3, "Connected client exact delivery differs")
    screenshot = h.screenshot("PlayerA", name, chat=True)
    save_world(h, name)
    return {"id": name, "status": "PASS_PENDING_INDEPENDENT_REVIEW", "root": root, "command": command,
            "feedback": feedback, "activityView": view, "before": before, "after": after, "screenshot": screenshot}


def remove_checkpoint(h, positive):
    root = positive["root"]
    h.walk("PlayerA", DESTINATION)
    h.action("PlayerA", "open", pos=DESTINATION)

    def open_menu():
        menu = h.client_snapshot("PlayerA")
        if menu and menu["menu"] and any(not s["playerInventory"] and s["item"] == PLANK and s["count"] == 3
                                         for s in menu["slots"]):
            return menu
        return None

    before = h.wait(open_menu, "Actual connected chest menu before checkpoint removal")
    old_slot = next(s["slot"] for s in before["slots"] if not s["playerInventory"] and s["item"] == PLANK)
    before_stock = menu_stock(before)
    h.action("PlayerA", "menu-click", slot=old_slot, quick=True)

    def transferred():
        menu = h.client_snapshot("PlayerA")
        if menu and menu["menu"] and menu_stock(menu) == (0, before_stock[1] + 3) and h.container(DESTINATION, PLANK) == 0:
            return menu
        return None

    after = h.wait(transferred, "Same connected principal transferred the actual delivered output")
    transfer_screen = h.screenshot("PlayerA", REMOVAL)
    h.action("PlayerA", "close-menu")
    view = h.command("PlayerA", "/aivillage kernel activity " + root, r"activity=" + root)
    now = h.snapshot()
    save_world(h, REMOVAL)
    return {"id": REMOVAL, "status": "PASS_PENDING_INDEPENDENT_REVIEW", "root": root, "actor": h.players["PlayerA"],
            "beforeMenu": before, "afterMenu": after, "after": now, "view": view, "screenshot": transfer_screen}


def menu_stock(menu):
    return tuple(sum(s["count"] for s in menu["slots"] if s["item"] == PLANK and s["playerInventory"] is player)
                 for player in (False, True))


def lookalike(h):
    h.console(['data merge block ' + coord(SOURCE) + ' {Items:[{Slot:26b,id:"minecraft:birch_log",count:1,'
               'components:{"minecraft:custom_name":{text:"I00 lookalike"}}},'
               '{Slot:0b,id:"minecraft:cobblestone",count:2}]}',
               "data merge block " + coord(DESTINATION) + " {Items:[]}"])
    h.wait(lambda: h.container(SOURCE, LOG) == 1 and h.container(DESTINATION, PLANK) == 0,
           "Named native item has the same ID and quantity as the valid input")
    require(h.menu_count("PlayerA", SOURCE, LOG) == 1, "Real client sees same item ID/quantity")
    save_world(h, LOOKALIKE, "starting-world")
    before = h.snapshot()
    command = production_command(h, DESTINATION)
    h.actions_active = True
    feedback = h.command("PlayerA", command, r"(?:activity|job)=|accepted=false", 45)
    match = re.search(r"(?:activity|job)=(" + UUID_PATTERN + ")", feedback)
    root = match.group(1) if match and "accepted=true" in feedback else None
    if root:
        h.wait(lambda: (job if (job := h.snapshot()["players"]["PlayerA"]["jobs"].get(root))
                        and job["state"] in ("FAILED", "CANCELLED", "INTERRUPTED", "SUCCEEDED") else None),
               "Original lookalike intake reached a definite native terminal", 200)
    else:
        require("accepted=false" in feedback, "No explicit intake rejection")
        h.quiet(40)
    h.actions_active = False
    after = h.snapshot()
    screenshot = h.screenshot("PlayerA", LOOKALIKE, chat=True)
    save_world(h, LOOKALIKE)
    return {"id": LOOKALIKE, "status": "PASS_PENDING_INDEPENDENT_REVIEW", "command": command,
            "feedback": feedback, "root": root, "before": before, "after": after, "screenshot": screenshot}


def run(args, identity):
    if args.root.exists() and any(args.root.iterdir()):
        raise ValueError("Integrated controls require a fresh evidence directory")
    h = Controls(args.root, "compose", None)
    h.family = "I00-INTEGRATED-QUANTITY"
    try:
        verify_metadata(read(h.root / "metadata.json"), identity)
        instruments(PRODUCTION, ROOT / "build/e2e-driver/observer.jar")
        game_seed(h, args.seed_root, identity)
        atomic(h.root / "oracle-candidates.json", candidates(SDK / "minecraft-client.jar"))
        observer_inputs(h.root)
        h.compose("build", "minecraft")
        h.start("server")
        h.start("PlayerA")
        h.wait(lambda: clean_owners(h.snapshot()["knownPlayers"]["PlayerA"]), "Initial legacy owners closed", 45)
        h.command("PlayerA", "/aivillage kernel inference false", "admission=false")
        for name, (destination, old) in LIVE.items():
            path = h.root / "cases" / name / "result.json"
            try:
                case = exercise(h, name, destination, old)
                atomic(path, case)
            except Exception as failure:
                atomic(path, {"id": name, "status": "FAIL", "reason": str(failure), "trace": traceback.format_exc()})
                raise
            if name == "birch-q3-root":
                atomic(h.root / "cases" / REMOVAL / "result.json", remove_checkpoint(h, case))
        atomic(h.root / "cases" / LOOKALIKE / "result.json", lookalike(h))
    finally:
        h.finish()


def proof(job, root, method):
    require(job["id"] == root and job["state"] == "SUCCEEDED" and job["fulfilled"] == 3
            and job["request"]["request"]["arguments"]["amount"]["value"] == 3, "Original exact Job demand changed")
    activity = job.get("activity") or {}
    require(activity.get("mode") == "MAKE_NEW" and len(activity["steps"]) == len(activity["checkpoints"]) == 1,
            "Expected one integrated exact choice, not a legacy producer")
    step, checkpoint, attempt = activity["steps"][0], activity["checkpoints"][0], job["attempts"][-1]
    require(step["quantities"] == QUANTITIES and step["choice"]["exactRecipe"] is True
            and step["artifact"]["sha256"] == method and len(job["attempts"]) == 1
            and attempt["terminal"]["status"] == "SUCCEEDED" and attempt["uncertain"] is False
            and attempt["id"] == step["attempt"] and attempt["credited"] == 3, "Exact choice, method, or attempt mismatch")
    receipts = attempt["resourceReceipts"]
    require([r["stage"] for r in receipts] == STAGES and [r["quantity"] for r in receipts] == [1, 4, 3],
            "Expected exactly one native withdrawal, craft, and delivery")
    witnesses = [r["choice"] for r in receipts]
    require(all((r["root"], r["step"], r["attempt"], r["generation"], r["choiceDigest"], r["batch"]) ==
                (root, step["id"], attempt["id"], attempt["generation"], step["choice"]["sha256"], 0) for r in witnesses)
            and len({r["journalToken"] for r in witnesses}) == 3
            and all(a["custodyAfter"] == b["custodyBefore"] for a, b in zip(witnesses, witnesses[1:])),
            "Native receipt custody or original execution identity differs")
    receipt_ids = [r["receiptId"] for r in receipts]
    require(checkpoint["receipts"] == receipt_ids and checkpoint["quantity"] == 3
            and checkpoint["attempt"] == attempt["id"], "Activity checkpoint did not retain original receipts")
    return {"step": step["id"], "attempt": attempt["id"], "generation": attempt["generation"],
            "choiceDigest": step["choice"]["sha256"], "receipts": receipt_ids,
            "journal": [r["journalToken"] for r in witnesses]}


def stock(frame, pos):
    return frame["containers"][",".join(map(str, pos))]


def frame_link(frames, *values):
    for frame in values:
        key = frame["pid"], frame["time"], frame["tick"]
        require(key not in frames or frames[key] == frame, "Different claims for one native observation")
        frames[key] = frame


def feedback_in(events, text):
    return any(e["kind"] == "feedback" and e["data"]["text"] == text for e in events)


def frame_png(raw, row):
    screen = row["screenshot"]
    path = safe(raw, screen["path"])
    require(sha256(path) == screen["sha256"] and path.stat().st_size > 1024
            and screen["nativeClientFrame"] and screen["player"] == "PlayerA", "Missing original connected client frame")
    with path.open("rb") as file:
        require(file.read(8) == b"\x89PNG\r\n\x1a\n", "Client frame is not PNG")


def verify_case(raw, row, name, seed, recipe, events, native_frames):
    destination, old = LIVE[name]
    actor_id = seed["actor"]["entityId"]
    root, before, after = row["root"], row["before"], row["after"]
    before_jobs, after_jobs = [frame["players"]["PlayerA"]["jobs"] for frame in (before, after)]
    require(root not in before_jobs and before["time"] <= after["time"], "The original root already existed")
    lineage = proof(after_jobs[root], root, seed["method"])
    frame_link(native_frames, before, after)
    require(stock(before, SOURCE).get(LOG) == 1 and stock(after, SOURCE).get(LOG, 0) == 0
            and stock(before, SOURCE).get(OTHER) == stock(after, SOURCE).get(OTHER) == 2
            and stock(before, destination).get(PLANK, 0) == old and stock(after, destination).get(PLANK) == old + 3,
            "Original native input, old output, unrelated stock or exact delivery differs")
    a, b = [next(r["inventory"] for r in frame["actors"] if r["uuid"] == actor_id) for frame in (before, after)]
    require(Counter(b) == Counter(a) + Counter({PLANK: 1}) and before["drops"] == after["drops"],
            "The independent physical quantity and retained surplus did not reconcile")
    require(all(before[key] == after[key] for key in ("needleCalls", "generationCalls")), "Known method regenerated")
    world = raw / "cases" / name / "saved-world"
    require(chest(world, SOURCE) == stock(after, SOURCE) and chest(world, destination) == stock(after, destination),
            "Actual saved container data disagrees with native frames")
    saved_actor_inventory = Counter()
    for stack in entities(world)[actor_id].get("Inventory", []):
        _, key, count, _ = item(stack)
        saved_actor_inventory[key] += count
    require(saved_actor_inventory == Counter(b), "The actual saved villager lost declared surplus")
    persisted = original(world, root)
    saved_receipts = persisted["attempts"][-1]["resourceReceipts"]
    require(persisted["state"] == "SUCCEEDED" and persisted["generation"] == lineage["generation"]
            and [r["id"] for r in saved_receipts] == lineage["receipts"]
            and [r["choice"]["journal"] for r in saved_receipts] == lineage["journal"]
            and [r["quantity"] for r in saved_receipts] == [1, 4, 3], "Save lost the original exact-choice lineage")
    frame_png(raw, row)
    require("state=SUCCEEDED" in row["activityView"]
            and "checkpoints=1/1" in row["activityView"] and "remaining=0" in row["activityView"]
            and all(feedback_in(events, row[key]) for key in ("feedback", "activityView")),
            "Missing real client and original activity feedback")
    definition = recipe["recipes"][PLANK]
    require(recipe["serverPid"] == after["pid"] and recipe["worldEffects"] == 0
            and definition["output"] == PLANK and definition["count"] == 4 and definition["remainderCount"] == 0
            and any(LOG in options for options in definition["ingredients"]), "Installed vanilla recipe identity differs")
    return {"id": name, "status": "PASS", "scope": "I00_CONNECTED_EXACT_CHOICE", "root": root,
            "proof": lineage, "oldOutput": old, "withdrawn": 1, "crafted": 4, "delivered": 3, "surplus": 1}


def verify_removal(raw, row, seed, events, frames, cases):
    positive = next(c for c in cases if c["id"] == "birch-q3-root" and c["status"] == "PASS")
    frame, root = row["after"], row["root"]
    require(root == positive["root"] and row["actor"] == frame["players"]["PlayerA"]["uuid"]
            and stock(frame, DESTINATION).get(PLANK, 0) == 0, "Wrong root, principal or chest after external removal")
    after = proof(frame["players"]["PlayerA"]["jobs"][root], root, seed["method"])
    require(after == positive["proof"] and "currentStock=0" in row["view"]
            and "checkpoints=1/1" in row["view"] and "remaining=0" in row["view"]
            and feedback_in(events, row["view"]), "External stock changed historical checkpoint/credit or did not update availability")
    prior_menu, next_menu = menu_stock(row["beforeMenu"]), menu_stock(row["afterMenu"])
    require(prior_menu[0] == 3 and next_menu == (0, prior_menu[1] + 3),
            "Connected menu did not transfer the exact originally delivered quantity")
    world = raw / "cases" / REMOVAL / "saved-world"
    player_path = world / "players/data" / (str(UUID(row["actor"])) + ".dat")
    with gzip.open(player_path, "rb") as player:
        saved_player = read_nbt(player.read(4 * 1024**2))
    held = sum(item(entry)[2] for entry in saved_player["Inventory"] if item(entry)[1] == PLANK)
    require(held == next_menu[1] and chest(world, DESTINATION).get(PLANK, 0) == 0
            and original(world, root)["state"] == "SUCCEEDED", "Saved native player, chest and original Job do not reconcile")
    frame_link(frames, frame)
    frame_png(raw, row)
    return {"id": REMOVAL, "status": "PASS", "scope": "I00_CONNECTED_EXACT_CHOICE", "root": root,
            "originalCredit": 3, "historicalLineageUnchanged": True, "playerRemoved": 3, "availableAfter": 0}


def verify_lookalike(raw, row, seed, events, frames):
    before, after = row["before"], row["after"]
    root = row["root"]
    require(feedback_in(events, row["feedback"]), "Missing actual connected lookalike request feedback")
    if root:
        job = after["players"]["PlayerA"]["jobs"][root]
        reason = job.get("reason")
        require(job["state"] == "FAILED" and not job["fulfilled"] and all(
            not a["effects"] and not a["resourceReceipts"] for a in job["attempts"]), "Lookalike generated a native mutation")
    else:
        match = re.search(r"reason=([A-Z_]+)", row["feedback"])
        reason = match.group(1) if match else None
        require(before["players"]["PlayerA"]["jobs"] == after["players"]["PlayerA"]["jobs"], "Refused intake created authoritative state")
    require(reason in ("RESOURCE_MISSING", "TARGET_INVALID", "TARGET_UNAVAILABLE"),
            "Unreadable or unrelated lookalike refusal mechanism")
    require(all(stock(before, p) == stock(after, p) for p in (SOURCE, DESTINATION))
            and before["drops"] == after["drops"] and all(before[k] == after[k] for k in ("generationCalls", "needleCalls")),
            "Negative custody or model counters changed")
    actor = seed["actor"]["entityId"]
    require(all(next(r["inventory"] for r in frame["actors"] if r["uuid"] == actor)
                == next(r["inventory"] for r in before["actors"] if r["uuid"] == actor) for frame in (before, after)),
            "Component-bearing source affected actor custody")
    phase = raw / "cases" / LOOKALIKE
    starts, ends = [chest_row(phase / world, SOURCE)["Items"] for world in ("starting-world", "saved-world")]
    materials = [item(entry) for entry in starts if item(entry)[1] == LOG]
    require(starts == ends and len(materials) == 1 and materials[0][2] == 1
            and "minecraft:custom_name" in materials[0][3], "Raw native NBT lacks the preserved same-item lookalike")
    frame_link(frames, before, after)
    frame_png(raw, row)
    return {"id": LOOKALIKE, "status": "PASS", "scope": "I00_CONNECTED_EXACT_CHOICE", "reason": reason,
            "realComponentBearingInput": True, "effectCount": 0}


def review(raw, identity):
    metadata = read(raw / "metadata.json")
    verify_metadata(metadata, identity)
    seed = read(raw / "seed.json")
    require(all(seed[key] == value for key, value in identity.items()) and seed["importedPasses"] == 0,
            "Initial methods and source are not pinned to current run/attempt")
    mods = raw / "server/game/mods"
    require(sha256(mods / PRODUCTION.name) == identity["productionSha256"]
            and sha256(mods / "observer.jar") == metadata["binaries"]["observer"]["sha256"],
            "Different real native server binary or observer")
    stops = [e["data"] for e in completed_jsonl(raw / "supervisor.jsonl")
             if e["kind"] == "process-stop-witness" and e["data"]["component"] == "server"]
    stop, = stops
    stopped_process(stop["state"], hard=False)
    require(stop["state"]["ExitCode"] == 0, "Exact native JVM did not stop cleanly")
    events = completed_jsonl(raw / "clients/PlayerA/events.jsonl")
    require(any(event["kind"] == "play" for event in events), "No live Minecraft client connected")
    recipe = read(raw / "oracle-recipes.json")
    frames, cases = {}, []
    for name in (*LIVE, REMOVAL, LOOKALIKE, *PENDING):
        row = read(raw / "cases" / name / "result.json")
        if not row or row.get("status") != "PASS_PENDING_INDEPENDENT_REVIEW":
            cases.append({"id": name, "status": row["status"] if row else "NOT_EXECUTED_REQUIRED"})
        elif name in LIVE:
            cases.append(verify_case(raw, row, name, seed, recipe, events, frames))
        elif name == REMOVAL:
            cases.append(verify_removal(raw, row, seed, events, frames, cases))
        elif name == LOOKALIKE:
            cases.append(verify_lookalike(raw, row, seed, events, frames))
        else:
            raise ValueError("No independent reviewer for the claimed case " + name)
    with (raw / "server/observations.jsonl").open() as stream:
        for line in stream:
            if not line.endswith("\n"):
                continue
            frame = json.loads(line)
            key = frame["pid"], frame["time"], frame["tick"]
            if key in frames:
                require(frame == frames.pop(key), "A selected observation differs from its raw native frame")
    require(not frames, "Missing original server observations")
    passes = sum(row["status"] == "PASS" for row in cases)
    return {"schema": 1, "stage": "I00", **identity, "requiredCases": len(cases), "reviewedPasses": passes,
            "status": "PASS_REQUIRED_INTEGRATED_CONTROLS" if passes == len(cases) else "FAIL_REQUIRED_INTEGRATED_CONTROLS",
            "cases": cases}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("run", "review"))
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--jar-sha256", required=True)
    extra = parser.add_mutually_exclusive_group(required=True)
    extra.add_argument("--seed-root", type=Path)
    extra.add_argument("--out", type=Path)
    args = parser.parse_args()
    identity = candidate(args.source, args.jar_sha256)
    if args.operation == "run":
        require(args.seed_root is not None, "Run requires the exact build's stopped B native seed")
        run(args, identity)
        return
    require(args.out is not None, "Review requires an output destination")
    try:
        report = review(args.root, identity)
    except Exception as error:
        report = {"schema": 1, "stage": "I00", **identity, "status": "FAIL_REQUIRED_INTEGRATED_CONTROLS",
                  "reason": str(error), "trace": traceback.format_exc()}
    atomic(args.out, report)
    print(json.dumps({"status": report["status"], "reviewedPasses": report.get("reviewedPasses", 0)}))
    if report["status"] != "PASS_REQUIRED_INTEGRATED_CONTROLS":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
