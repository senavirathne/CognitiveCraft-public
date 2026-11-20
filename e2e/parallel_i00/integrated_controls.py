"""Connected-client q3/y4 exact-choice and old-output-stock I00 controls."""

import argparse
from collections import Counter
import json
import gzip
import math
from pathlib import Path
import re
import shutil
import subprocess
import sys
import traceback
import time
from uuid import UUID, uuid4

from .identity import PRODUCTION, ROOT, candidate, sha256, verify_metadata
from .gameplay import QUALIFICATION_COMPOSE
from . import recipe_journal
from .native_evidence import exact_withdrawals, verify_frames

sys.path.insert(0, str(ROOT / "e2e/parallel_a"))
from native_nbt import read_nbt
from review_lifecycle_connected import allowance, chest, chest_row, entities, original
from review_receipt_prefix import item
from campaigns.crafting.oracle import candidates
from parallel_b.baseline import DESTINATION, LOG, OTHER, PLANK, SOURCE, coord
from parallel_b.guards import clean_owners, instruments, observer_inputs, stopped_process
from parallel_b.qualification import safe
from runtime import SDK
from suite import DENIED_PATTERN, Harness, UUID_PATTERN, atomic, completed_jsonl, read, reconnect_players, require


LIVE = {"birch-q3-root": (DESTINATION, 0), "birch-q3-same-container-old-stock": (SOURCE, 11)}
LOOKALIKE = "component-bearing-lookalike"
REMOVAL = "player-removes-checkpoint-stock"
RESTOCK = "unchanged-planning-then-player-restock"
WHOLE_GOAL = "legal-batch-insufficient-for-original-goal"
NO_METHOD = "installed-recipe-without-admitted-method"
CONTEND = "contending-production-actors"
PARTIAL_CANCEL = "cancelled-original-partial-craft"
SAVED_CRAFT = "birch-q3-saved-craft-continuation"
CANCEL_DESTINATION = [12, 201, 11]
PEER_DESTINATION = [7, 201, 9]
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


def production_command(h, destination, demand=3):
    return ("/aivillage kernel production-activity " + h.citizens["PlayerA"]
            + " birch-planks " + str(demand) + " " + coord(SOURCE + destination))


def fixture_slots(h, position, slots):
    require(not h.actions_active and all(0 <= slot < 27 and 1 <= count <= 64
                                        for slot, (_, count) in slots.items()), "Bounded pre-request inventory setup")
    h.console(["item replace block " + coord(position) + " container." + str(slot) + " with " +
               (slots[slot][0] + " " + str(slots[slot][1]) if slot in slots else "minecraft:air")
               for slot in range(27)])


def verified_clock(h, command):
    require(command in ("tick freeze", "tick unfreeze", "tick rate 2", "tick rate 20"),
            "Original bounded native clock command required")
    deadline = time.monotonic() + 30
    def observed():
        query = {"id": str(uuid4()), "positions": []}
        atomic(h.root / "server/b-probe.json", query)
        return h.wait(lambda: (frame if (frame := read(h.root / "server/b-probe-response.json"))
                      and frame["id"] == query["id"] and frame["worldEffects"] == 0 else None),
                      "Actual read-only native clock query", max(0, deadline - time.monotonic()))
    before = observed()
    expected = dict(before["nativeClock"])
    if command.startswith("tick rate "):
        expected["rate"] = int(command.split()[-1])
    else:
        expected["frozen"] = command == "tick freeze"
    require(expected != before["nativeClock"], "Native clock must actually change from its preceding state")
    request = {"id": str(uuid4()), "commands": [command]}
    atomic(h.root / "server/control.json", request)
    h.wait(lambda: (ack if (ack := read(h.root / "server/control-ack.json")) and ack["id"] == request["id"] else None),
           "Only console delivery; native clock observation is still required", max(0, deadline - time.monotonic()))
    after = h.wait(lambda: (frame if (frame := observed()) and frame["pid"] == before["pid"]
                   and frame["time"] > before["time"] and frame["sequence"] > before["sequence"]
                   and frame["nativeClock"] == expected else None),
                   "Observed native clock transition with both ordinary clients deopped", max(0, deadline - time.monotonic()))
    h.event("verified-native-clock-transition", {"request": request, "before": before, "after": after})
    return after


def exercise(h, name, destination, old):
    slots = {26: (LOG, 1), 0: ("minecraft:cobblestone", 2)}
    if old:
        slots[1] = (PLANK, 11)
    fixture_slots(h, SOURCE, slots)
    if not old:
        fixture_slots(h, destination, {})
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


def submit_root(h, command, player="PlayerA"):
    feedback = h.command(player, command, r"activity=|accepted=false", 45)
    match = re.search(r"activity=(" + UUID_PATTERN + ")", feedback)
    require(match and "accepted=true" in feedback, "Original public activity was not admitted: " + feedback)
    return match.group(1), feedback


def original_job(h, root, player="PlayerA"):
    return h.snapshot()["players"][player]["jobs"].get(root)


def dormant(h, root):
    job = original_job(h, root)
    control = (job.get("activity") or {}).get("production") if job else None
    return job if (job and job["state"] == "WAITING" and control
                   and control["memory"]["unchangedChecks"] == 4
                   and control["nextCheckMillis"] == 2**63 - 1) else None


def cancel_partial(h):
    h.console(["setblock " + coord(CANCEL_DESTINATION) + " minecraft:chest",
               "tp " + h.entities["PlayerA"] + " 5.5 201 7.5"])
    fixture_slots(h, SOURCE, {26: (LOG, 1), 0: (OTHER, 2)})
    fixture_slots(h, CANCEL_DESTINATION, {})
    h.wait(lambda: h.container(SOURCE, LOG) == 1 and h.container(CANCEL_DESTINATION, PLANK) == 0,
           "Original one-log partial-cancellation start")
    before = h.snapshot()
    actor = h.entities["PlayerA"]
    held = next(a["inventory"].get(PLANK, 0) for a in before["actors"] if a["uuid"] == actor)
    save_world(h, PARTIAL_CANCEL, "starting-world")
    command = production_command(h, CANCEL_DESTINATION)
    h.actions_active = True
    verified_clock(h, "tick rate 2")
    root, feedback = submit_root(h, command)
    def crafted():
        frame = h.snapshot()
        job = frame["knownPlayers"]["PlayerA"]["jobs"].get(root)
        receipts = job["attempts"][-1]["resourceReceipts"] if job and job["attempts"] else []
        return frame if (job and job["state"] == "ACTIVE" and [r["stage"] for r in receipts] == ["WITHDRAW", "CRAFT"]
                         and next(a["inventory"].get(PLANK, 0) for a in frame["actors"] if a["uuid"] == actor) == held + 4
                         and h.container(CANCEL_DESTINATION, PLANK) == 0) else None
    h.wait(crafted, "Actual original withdrawal/craft prefix before any delivery", 110)
    verified_clock(h, "tick freeze")
    cut = crafted()
    require(cut, "INCONCLUSIVE: original partial-craft boundary passed before native freeze")
    cancellation = h.cancel_job("PlayerA", root)
    require("cancellation=true" in cancellation, "Original public cancellation was not acknowledged")
    verified_clock(h, "tick unfreeze")
    verified_clock(h, "tick rate 20")
    after = h.wait(lambda: (frame if (frame := h.snapshot()) and
                   (job := frame["knownPlayers"]["PlayerA"]["jobs"].get(root)) and job["state"] == "CANCELLED"
                   and clean_owners(frame["knownPlayers"]["PlayerA"]) else None),
                   "Original partial-custody owner settles its accepted cancellation", 60)
    h.quiet(60)
    fence = h.snapshot()
    view = h.command("PlayerA", "/aivillage kernel activity " + root, "state=CANCELLED")
    h.actions_active = False
    screenshot = h.screenshot("PlayerA", PARTIAL_CANCEL, chat=True)
    save_world(h, PARTIAL_CANCEL)
    return {"id": PARTIAL_CANCEL, "status": "PASS_PENDING_INDEPENDENT_REVIEW", "root": root, "command": command,
            "feedback": feedback, "before": before, "cut": cut, "after": after, "afterFence": fence,
            "cancellation": cancellation, "activityView": view, "screenshot": screenshot}


def continue_saved_craft(h):
    h.console(["setblock " + coord(CANCEL_DESTINATION) + " minecraft:chest",
               "tp " + h.entities["PlayerA"] + " 5.5 201 7.5"])
    fixture_slots(h, SOURCE, {26: (LOG, 1), 0: (OTHER, 2)})
    fixture_slots(h, CANCEL_DESTINATION, {})
    h.wait(lambda: h.container(SOURCE, LOG) == 1 and h.container(CANCEL_DESTINATION, PLANK) == 0,
           "Original one-log saved continuation fixture")
    save_world(h, SAVED_CRAFT, "starting-world")
    before = h.snapshot()
    command = production_command(h, CANCEL_DESTINATION)
    h.actions_active = True
    verified_clock(h, "tick rate 2")
    root, feedback = submit_root(h, command)

    def crafted():
        frame = h.snapshot()
        job = frame["knownPlayers"]["PlayerA"]["jobs"].get(root)
        receipts = job["attempts"][-1]["resourceReceipts"] if job and job["attempts"] else []
        if job and job["state"] == "ACTIVE" and [r["stage"] for r in receipts] == ["WITHDRAW", "CRAFT"]:
            return frame
        return None

    h.wait(crafted, "Actual original craft committed before delivery", 110)
    verified_clock(h, "tick freeze")
    cut = crafted()
    require(cut, "INCONCLUSIVE: native craft window elapsed before the freeze")
    cut_image = h.screenshot("PlayerA", SAVED_CRAFT + "-cut", chat=True)
    save = h.save_checkpoint()
    cold = read(h.root / "server/process.json")
    h.stop("server", hard=True)
    h.wait(lambda: not h.action("PlayerA", "snapshot")["play"], "Connected owner observes the original JVM stop", 45)
    stopped = h.root / "cases" / SAVED_CRAFT / "stopped-world"
    shutil.copytree(h.root / "server/game/world", stopped, ignore=shutil.ignore_patterns("session.lock"))
    h.start("server")
    warm = read(h.root / "server/process.json")
    reconnect_players(h, ["PlayerA"])
    after = h.wait(lambda: (frame if (frame := h.snapshot()) and
                   (job := frame["knownPlayers"]["PlayerA"]["jobs"].get(root)) and job["state"] == "SUCCEEDED"
                   and clean_owners(frame["knownPlayers"]["PlayerA"]) else None),
                   "Original saved recipe attempt delivers the remaining output", 100)
    view = h.command("PlayerA", "/aivillage kernel activity " + root, "state=SUCCEEDED")
    received = h.menu_count("PlayerA", CANCEL_DESTINATION, PLANK)
    h.actions_active = False
    screenshot = h.screenshot("PlayerA", SAVED_CRAFT, chat=True)
    save_world(h, SAVED_CRAFT)
    return {"id": SAVED_CRAFT, "status": "PASS_PENDING_INDEPENDENT_REVIEW", "root": root, "command": command,
            "feedback": feedback, "before": before, "cut": cut, "after": after, "cutSave": save,
            "cold": cold, "warm": warm, "activityView": view, "received": received,
            "screenshot": screenshot, "cutScreenshot": cut_image}


def restock(h, name, demand, initial_logs):
    fixture_slots(h, SOURCE, {26: (LOG, initial_logs)} if initial_logs else {})
    fixture_slots(h, DESTINATION, {})
    h.console(["clear PlayerA", "give PlayerA " + LOG + " 1"])
    h.wait(lambda: h.container(SOURCE, LOG) == initial_logs and h.container(DESTINATION, PLANK) == 0,
           "Original stock is insufficient for all requested units")
    before = h.snapshot()
    command = production_command(h, DESTINATION, demand)
    h.actions_active = True
    root, feedback = submit_root(h, command)
    first = None
    if name == WHOLE_GOAL:
        first = h.wait(lambda: (frame if (frame := h.snapshot()) and
                       (job := frame["players"]["PlayerA"]["jobs"].get(root)) and
                       job["activity"]["production"]["blocker"] == "NO_SUPPORTED_WHOLE_GOAL_PATH" else None),
                       "One legal batch cannot satisfy the original seven-unit goal", 20)
    waiting = h.wait(lambda: dormant(h, root), "Finite unchanged planning checks reached their original stop", 40)
    h.quiet(80)
    still = h.snapshot()
    require(still["players"]["PlayerA"]["jobs"][root] == waiting, "Unchanged planning kept publishing")
    h.walk("PlayerA", SOURCE)
    h.action("PlayerA", "open", pos=SOURCE)
    def supply_slot():
        menu = h.client_snapshot("PlayerA")
        return (menu, next((s for s in menu["slots"] if s["playerInventory"] and s["item"] == LOG
                           and s["count"] == 1), None)) if menu and menu["menu"] else None
    menu, slot = h.wait(lambda: (value if (value := supply_slot()) and value[1] else None),
                        "Actual player holds the sole independently supplied log")
    verified_clock(h, "tick freeze")
    action = h.action("PlayerA", "menu-click", slot=slot["slot"], quick=True)
    def supplied():
        view = h.client_snapshot("PlayerA")
        return view if (view and view["menu"] and menu_quantity(view, LOG, False) == initial_logs + 1
                        and menu_quantity(view, LOG, True) == 0) else None
    transfer = h.wait(supplied, "Connected player adds the one missing log to the original source", 10)
    h.action("PlayerA", "close-menu")
    verified_clock(h, "tick unfreeze")
    after = h.wait(lambda: (frame if (frame := h.snapshot()) and
                   (job := frame["players"]["PlayerA"]["jobs"].get(root)) and job["state"] in
                   ("SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED") else None),
                   "Original dormant root responds to actual changed stock", 180)
    require(after["players"]["PlayerA"]["jobs"][root]["state"] == "SUCCEEDED", "Restocked original root failed")
    h.wait(lambda: clean_owners(h.snapshot()["knownPlayers"]["PlayerA"]), "Restocked original owners settled", 45)
    view = h.command("PlayerA", "/aivillage kernel activity " + root, "activity=" + root)
    h.actions_active = False
    screenshot = h.screenshot("PlayerA", name, chat=True)
    save_world(h, name)
    return {"id": name, "status": "PASS_PENDING_INDEPENDENT_REVIEW", "root": root, "command": command,
            "feedback": feedback, "before": before, "firstBlocked": first, "waiting": waiting, "still": still, "after": after,
            "beforeMenu": menu, "clientAction": action, "afterMenu": transfer, "activityView": view,
            "screenshot": screenshot}


def menu_quantity(menu, item_id, player):
    return sum(slot["count"] for slot in menu["slots"]
               if slot["playerInventory"] is player and slot["item"] == item_id)


def missing_method(h):
    fixture_slots(h, SOURCE, {26: ("minecraft:acacia_log", 1)})
    fixture_slots(h, DESTINATION, {})
    h.wait(lambda: h.container(SOURCE, "minecraft:acacia_log") == 1, "Real installed alternative input")
    require(h.menu_count("PlayerA", SOURCE, "minecraft:acacia_log") == 1, "Actual client did not see input")
    before = h.snapshot()
    command = ("/aivillage kernel production-activity " + h.citizens["PlayerA"] + " acacia-planks 3 "
               + coord(SOURCE + DESTINATION))
    h.actions_active = True
    root, feedback = submit_root(h, command)
    first = h.wait(lambda: (job if (job := original_job(h, root)) and
                   job["activity"]["production"]["blocker"] == "PINNED_METHOD_UNAVAILABLE_OFFLINE" else None),
                   "Current native planner reports the missing admitted witness", 20)
    waiting = h.wait(lambda: dormant(h, root), "Installed recipe cannot substitute for an admitted method", 40)
    require(waiting["activity"]["production"]["memory"]["lastProposalId"] == ""
            and not waiting["attempts"] and not waiting["activity"]["steps"], "Unadmitted native method executed")
    h.quiet(80)
    dormant_frame = h.snapshot()
    require(dormant_frame["players"]["PlayerA"]["jobs"][root] == waiting, "Missing witness caused an endless replan")
    h.cancel_job("PlayerA", root)
    after = h.wait(lambda: (frame if (frame := h.snapshot()) and
                   frame["players"]["PlayerA"]["jobs"][root]["state"] == "CANCELLED" else None),
                   "Original unsupported activity cancellation settled", 40)
    h.wait(lambda: clean_owners(h.snapshot()["knownPlayers"]["PlayerA"]), "No unsupported owner left open", 40)
    h.actions_active = False
    screenshot = h.screenshot("PlayerA", NO_METHOD, chat=True)
    save_world(h, NO_METHOD)
    return {"id": NO_METHOD, "status": "PASS_PENDING_INDEPENDENT_REVIEW", "root": root, "command": command,
            "feedback": feedback, "before": before, "firstBlocked": first, "waiting": waiting, "dormant": dormant_frame,
            "after": after, "screenshot": screenshot}


def contention(h):
    h.start("PlayerB")
    old_actors = {actor["uuid"] for actor in h.snapshot()["actors"]}
    h.console(["op PlayerB", "gamemode survival PlayerB", "tp PlayerB 15.5 201 7.5",
               'summon minecraft:villager 15.5 201 5.5 {PersistenceRequired:1b}',
               "clear PlayerB", "give PlayerB " + LOG + " 2"])
    def native_subject():
        frame = h.snapshot()
        actors = [a for a in frame["actors"] if a["uuid"] not in old_actors]
        client = h.client_snapshot("PlayerB")
        return actors[0] if (len(actors) == 1 and actors[0]["alive"] and not actors[0]["baby"]
                             and not actors[0]["noAI"] and client and client["play"]
                             and math.dist(client["pos"], [15.5, 201, 7.5]) < 1) else None
    peer = h.wait(native_subject, "Distinct connected principal and normal adult subject for contention", 35)
    h.enroll("PlayerB", "Lina")
    require(h.entities["PlayerB"] == peer["uuid"] and h.players["PlayerB"] != h.players["PlayerA"],
            "Contention did not use independent authorized actors")
    h.console(["deop PlayerB", "tp " + h.entities["PlayerA"] + " 5.5 201 7.5",
               "tp " + h.entities["PlayerB"] + " 6.5 201 6.5",
               "setblock " + coord(PEER_DESTINATION) + " minecraft:chest"])
    fixture_slots(h, SOURCE, {26: (LOG, 5)})
    fixture_slots(h, DESTINATION, {})
    fixture_slots(h, PEER_DESTINATION, {})
    h.wait(lambda: h.container(SOURCE, LOG) == 5 and all(any(a["uuid"] == h.entities[player]
                   and math.dist(a["pos"], pose) < 1 for a in h.snapshot()["actors"])
                   for player, pose in (("PlayerA", [5.5, 201, 7.5]), ("PlayerB", [6.5, 201, 6.5]))),
           "Five native logs and original bounded pre-request poses", 30)
    require(h.menu_count("PlayerB", SOURCE, LOG) == 5, "Second client did not observe the five-log start")
    h.walk("PlayerB", SOURCE)
    before = h.snapshot()
    commands = {player: ("/aivillage kernel production-activity " + h.citizens[player] + " birch-planks "
                        + str(amount) + " " + coord(SOURCE + destination))
                for player, amount, destination in (("PlayerA", 12, DESTINATION), ("PlayerB", 16, PEER_DESTINATION))}
    h.actions_active = True
    verified_clock(h, "tick rate 2")
    a, af = submit_root(h, commands["PlayerA"])
    claimed = h.wait(lambda: (frame if (frame := h.snapshot()) and any(
                    lease["owner"]["job"] == a and lease["state"] == "ACTIVE"
                    and lease["resource"]["item"] == "cognitivecraft:recipe_source"
                    for lease in frame["knownPlayers"]["PlayerA"]["leases"]) else None),
                    "A holds its actual imminent source claim before B is considered", 70)
    b, bf = submit_root(h, commands["PlayerB"], "PlayerB")
    overlapping = h.wait(lambda: (frame if (frame := h.snapshot()) and
                         frame["knownPlayers"]["PlayerA"]["jobs"][a]["state"] == "ACTIVE"
                         and b in frame["knownPlayers"]["PlayerB"]["jobs"]
                         and frame["knownPlayers"]["PlayerB"]["jobs"][b]["state"] == "WAITING" else None),
                         "Original A and waiting B overlap in one native world", 40)
    verified_clock(h, "tick rate 20")
    h.wait(lambda: (job if (job := original_job(h, a)) and job["state"] == "SUCCEEDED" else None),
           "Original A consumes three logs and settles twelve attributable planks", 180)
    def needs_two():
        frame = h.snapshot()
        job = frame["knownPlayers"]["PlayerB"]["jobs"].get(b)
        return frame if (h.container(SOURCE, LOG) == 2 and job and job["state"] == "WAITING"
                         and not job["attempts"] and not job["activity"]["steps"]
                         and clean_owners(frame["knownPlayers"]["PlayerA"])) else None
    waiting = h.wait(needs_two, "B cannot manufacture four-log demand from the two remaining logs", 45)
    h.quiet(60)
    h.action("PlayerB", "open", pos=SOURCE)
    def open_supply():
        menu = h.client_snapshot("PlayerB")
        slot = next((s for s in menu.get("slots", []) if s["playerInventory"] and s["item"] == LOG
                     and s["count"] == 2), None) if menu else None
        return (menu, slot) if menu and menu["menu"] and slot else None
    menu, slot = h.wait(open_supply, "B's client holds the independently recorded two-log addition", 25)
    verified_clock(h, "tick freeze")
    transfer_action = h.action("PlayerB", "menu-click", slot=slot["slot"], quick=True)
    transferred = h.wait(lambda: (menu if (menu := h.client_snapshot("PlayerB")) and menu["menu"]
                         and sum(s["count"] for s in menu["slots"] if not s["playerInventory"] and s["item"] == LOG) == 4
                         and not any(s["playerInventory"] and s["item"] == LOG for s in menu["slots"]) else None),
                         "Actual source increases from two to four through a real client transfer", 15)
    h.action("PlayerB", "close-menu")
    supplied = h.snapshot()
    verified_clock(h, "tick unfreeze")
    h.wait(lambda: (job if (job := original_job(h, b, "PlayerB")) and job["state"] in
           ("SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED") else None), "Original B responds to the supply change", 180)
    h.wait(lambda: all(clean_owners(frame) for frame in h.snapshot()["knownPlayers"].values()),
           "Both original contention owners and claims settled", 45)
    after = h.snapshot()
    require(after["knownPlayers"]["PlayerB"]["jobs"][b]["state"] == "SUCCEEDED", "Restocked B failed")
    h.actions_active = False
    screens = [h.screenshot(player, CONTEND + "-" + player, chat=True) for player in ("PlayerA", "PlayerB")]
    views = {player: h.command(player, "/aivillage kernel activity " + root, "activity=" + root)
             for player, root in (("PlayerA", a), ("PlayerB", b))}
    denied = {player: h.command(player, "/aivillage kernel activity " + root, DENIED_PATTERN)
              for player, root in (("PlayerA", b), ("PlayerB", a))}
    save_world(h, CONTEND)
    return {"id": CONTEND, "status": "PASS_PENDING_INDEPENDENT_REVIEW", "before": before, "claimed": claimed,
            "overlapping": overlapping, "waiting": waiting, "supplied": supplied, "after": after,
            "roots": {"PlayerA": a, "PlayerB": b}, "commands": commands,
            "feedback": {"PlayerA": af, "PlayerB": bf}, "beforeMenu": menu, "afterMenu": transferred,
            "clientAction": transfer_action, "views": views, "denied": denied, "screenshots": screens,
            "actors": {player: h.entities[player] for player in ("PlayerA", "PlayerB")}}


def lookalike(h):
    fixture_slots(h, SOURCE, {26: (LOG + '[minecraft:custom_name={text:"I00 lookalike"}]', 1),
                              0: ("minecraft:cobblestone", 2)})
    fixture_slots(h, DESTINATION, {})
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
        cold = read(h.root / "server/process.json")
        catalogue = h.wait(lambda: (value if (value := read(h.root / "oracle-recipes.json"))
                           and value["serverPid"] == cold["pid"] else None), "Installed cold recipe catalogue", 30)
        atomic(h.root / "cold-recipe-catalogue.json", catalogue)
        h.command("PlayerA", "/aivillage kernel inference false", "admission=false")
        h.console(["deop PlayerA"])
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
        atomic(h.root / "cases" / PARTIAL_CANCEL / "result.json", cancel_partial(h))
        atomic(h.root / "cases" / RESTOCK / "result.json", restock(h, RESTOCK, 3, 0))
        atomic(h.root / "cases" / WHOLE_GOAL / "result.json", restock(h, WHOLE_GOAL, 7, 1))
        atomic(h.root / "cases" / NO_METHOD / "result.json", missing_method(h))
        atomic(h.root / "cases" / CONTEND / "result.json", contention(h))
        atomic(h.root / "cases" / SAVED_CRAFT / "result.json", continue_saved_craft(h))
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


def verify_cancel_partial(raw, row, seed, events, frames):
    root, actor = row["root"], seed["actor"]["entityId"]
    before, cut, after, fence = (row[key] for key in ("before", "cut", "after", "afterFence"))
    first = cut["knownPlayers"]["PlayerA"]["jobs"][root]
    final = after["knownPlayers"]["PlayerA"]["jobs"][root]
    require(root not in before["knownPlayers"]["PlayerA"]["jobs"] and first["state"] == "ACTIVE"
            and final["state"] == "CANCELLED" and final["cancellationOutcome"] == "CANCELLED"
            and final["fulfilled"] == 0 and len(final["attempts"]) == len(final["activity"]["steps"]) == 1
            and not final["activity"]["checkpoints"]
            and final["activity"]["blocker"] == "SETTLED_CANCELLED_CUSTODY",
            "Partial cancellation was replaced, credited as a goal, or left without an explicit custody disposition")
    original_attempt, attempt = first["attempts"][0], final["attempts"][0]
    require(final["request"] == first["request"] and final["allowance"] == first["allowance"]
            and final["generation"] == first["generation"] == attempt["generation"]
            and attempt["id"] == original_attempt["id"]
            and attempt["terminal"]["status"] == "CANCELLED" and attempt["uncertain"] is False
            and attempt["effects"] == 2 and attempt["credited"] == 0,
            "Original authorizer, request, allowance or native attempt changed")
    receipts = attempt["resourceReceipts"]
    require(receipts == original_attempt["resourceReceipts"]
            and [r["stage"] for r in receipts] == ["WITHDRAW", "CRAFT"]
            and [r["quantity"] for r in receipts] == [1, 4]
            and exact_withdrawals(final, receipts) == Counter({LOG: 1}), "Partial native lineage differs")
    for frame in (after, fence):
        require(clean_owners(frame["knownPlayers"]["PlayerA"])
                and frame["knownPlayers"]["PlayerA"]["jobs"][root] == final
                and stock(frame, SOURCE).get(LOG, 0) == stock(frame, CANCEL_DESTINATION).get(PLANK, 0) == 0
                and stock(frame, SOURCE).get(OTHER) == 2
                and all(frame[key] == before[key] for key in ("generationCalls", "needleCalls")),
                "Cancelled owner emitted a late mutation, kept an owner open, or used inference")
    prior = next(a["inventory"].get(PLANK, 0) for a in before["actors"] if a["uuid"] == actor)
    prior_inventory = Counter(next(a["inventory"] for a in before["actors"] if a["uuid"] == actor))
    require(all(next(a["inventory"].get(PLANK, 0) for a in frame["actors"] if a["uuid"] == actor) == prior + 4
                for frame in (cut, after, fence)), "Actual retained output disappeared or was crafted twice")
    require(all(Counter(next(a["inventory"] for a in frame["actors"] if a["uuid"] == actor))
                == prior_inventory + Counter({PLANK: 4}) and frame["drops"] == before["drops"]
                for frame in (cut, after, fence)), "Partial craft changed unrelated custody")
    phase = raw / "cases" / PARTIAL_CANCEL
    saved = original(phase / "saved-world", root)
    require(saved["state"] == "CANCELLED" and allowance(saved["allowance"], final["allowance"])
            and saved["activity"]["blocker"] == "SETTLED_CANCELLED_CUSTODY"
            and not saved["attempts"][0]["uncertain"]
            and chest(phase / "starting-world", SOURCE)[LOG] == 1
            and chest(phase / "saved-world", SOURCE).get(LOG, 0) == 0
            and chest(phase / "saved-world", CANCEL_DESTINATION).get(PLANK, 0) == 0,
            "Saved native custody or original cancelled Job differs")
    saved_inventory = Counter()
    for stack in entities(phase / "saved-world")[actor].get("Inventory", []):
        _, key, quantity, _ = item(stack)
        saved_inventory[key] += quantity
    require(saved_inventory == prior_inventory + Counter({PLANK: 4}), "Native saved actor lost cancelled custody")
    require(all(feedback_in(events, row[key]) for key in ("feedback", "cancellation", "activityView")),
            "No original connected-client request/cancellation/activity feedback")
    frame_link(frames, before, cut, after, fence)
    frame_png(raw, row)
    return {"id": PARTIAL_CANCEL, "status": "PASS", "scope": "I00_CONNECTED_EXACT_CHOICE",
            "root": root, "attempt": attempt["id"], "generation": attempt["generation"],
            "retainedPlanks": 4, "goalCredit": 0, "lateEffects": 0, "savedProofFromLiveConversion": False}


def verify_continue_saved_craft(raw, row, seed, events, frames, supervisor, jar):
    root, actor = row["root"], seed["actor"]["entityId"]
    before, cut, after = (row[k] for k in ("before", "cut", "after"))
    first = cut["knownPlayers"]["PlayerA"]["jobs"][root]
    final = after["knownPlayers"]["PlayerA"]["jobs"][root]
    proof(final, root, seed["method"])
    cold, warm = row["cold"], row["warm"]
    require(cold["releaseSha256"] == warm["releaseSha256"] == jar and warm["start"] > cold["start"]
            and cut["pid"] == cold["pid"] and cut["time"] < warm["start"] * 1000
            and after["pid"] == warm["pid"] and after["time"] >= warm["start"] * 1000,
            "Saved prefix and completed delivery do not belong to the required original and restarted JVMs")
    stop, = [e for e in supervisor if e["kind"] == "process-stop-witness" and e["data"]["process"] == cold]
    require(stop["data"]["hard"] and stopped_process(stop["data"]["state"], hard=True),
            "No real original server process kill at the craft boundary")
    require(any(e["kind"] == "acknowledged-world-checkpoint" and e["data"] == row["cutSave"] and e["time"] < stop["time"]
                for e in supervisor) and any(e["kind"] == "server-disconnection-witness" and e["data"]["server"] == cold
                and e["data"]["player"] == "PlayerA" and not e["data"]["client"]["play"] for e in supervisor),
            "No matching checkpoint acknowledgement and client disconnection")
    old, current = first["attempts"][0], final["attempts"][0]
    step = first["activity"]["steps"][0]
    require(first["state"] == "ACTIVE" and first["fulfilled"] == 0 and len(first["attempts"]) == 1
            and final["request"] == first["request"] and final["allowance"] == first["allowance"]
            and final["generation"] == first["generation"] == current["generation"]
            and current["id"] == old["id"] and current["executions"] == old["executions"]
            and current["allowance"] == old["allowance"] and final["activity"]["steps"] == [{**step, "savedPrefixEffects": 2}]
            and after["gameTick"] < step["assignmentExpiresTick"]
            and old["resourceReceipts"] == current["resourceReceipts"][:2]
            and all(current["usage"].get(k, 0) >= n for k, n in old["usage"].items()),
            "The resumed delivery replaced or refilled the original attempt or replayed a committed phase")
    actor_items = lambda frame: Counter(next(a["inventory"] for a in frame["actors"] if a["uuid"] == actor))
    require(actor_items(cut) == actor_items(before) + Counter({PLANK: 4})
            and actor_items(after) == actor_items(before) + Counter({PLANK: 1})
            and stock(before, SOURCE) == {LOG: 1, OTHER: 2} and stock(cut, SOURCE) == stock(after, SOURCE) == {OTHER: 2}
            and stock(before, CANCEL_DESTINATION) == stock(cut, CANCEL_DESTINATION) == {}
            and stock(after, CANCEL_DESTINATION) == {PLANK: 3} and row["received"] == 3
            and after["brokerStats"]["calls"] == after["brokerStats"]["dispatched"] == after["needleCalls"] == 0,
            "Native saved continuation failed custody, client delivery, surplus or offline reuse")
    world = raw / "cases" / SAVED_CRAFT / "stopped-world"
    require(all(sha256(world / r["path"]) == r["sha256"] for r in row["cutSave"]["files"]),
            "Actual stopped world differs from the acknowledged native save")
    saved = original(world, root)
    require(saved["attempts"][0]["id"] == old["id"] and saved["activity"]["steps"][0]["assignmentExpiresTick"] == step["assignmentExpiresTick"],
            "Original native stopped Job has no matching assignment window")
    world_id = first["request"]["context"]["scope"]["worldId"]
    phases = recipe_journal.phases(recipe_journal.read(world, expected_world=world_id), root)
    require([p["prepare"]["phase"] for p in phases] == ["WITHDRAW", "CRAFT"] and all(map(recipe_journal.positive, phases)),
            "Stopped world lacks the original applied craft prefix")
    withdraw, craft = phases
    recipe_journal.saved_marker(entities(world)[actor], craft, craft["result"]["after"][0])
    recipe_journal.saved_marker(chest_row(world, SOURCE), withdraw, withdraw["result"]["after"][1])
    intended = craft["prepare"]["deliveryTarget"]
    target = json.loads(chest_row(world, CANCEL_DESTINATION)[recipe_journal.PARTICIPANT])
    require(intended["address"] == "chest:minecraft:overworld/" + ",".join(map(str, CANCEL_DESTINATION))
            and (intended["incarnation"], intended["revision"]) == (target["incarnation"], target["revision"])
            and chest(world, SOURCE) == {OTHER: 2} and chest(world, CANCEL_DESTINATION) == {},
            "Original saved delivery target or source differs")
    require(all(feedback_in(events, row[k]) for k in ("feedback", "activityView")), "Missing original connected client command and result")
    frame_link(frames, before, cut, after)
    frame_png(raw, row)
    frame_png(raw, {"screenshot": row["cutScreenshot"]})
    return {"id": SAVED_CRAFT, "status": "PASS", "scope": "I00_CONNECTED_EXACT_CHOICE",
            "root": root, "attempt": current["id"], "generation": current["generation"], "originalEffects": 2,
            "newEffects": 1, "delivered": 3, "retained": 1, "freshModelCalls": 0}


def verify_restock(raw, row, seed, events, frames):
    root, waiting = row["root"], row["waiting"]
    before, still, after = row["before"], row["still"], row["after"]
    final = after["players"]["PlayerA"]["jobs"][root]
    control = waiting["activity"]["production"]
    require(root not in before["players"]["PlayerA"]["jobs"] and waiting["id"] == root
            and waiting["state"] == "WAITING" and not waiting["attempts"] and not waiting["activity"]["steps"]
            and control["memory"]["unchangedChecks"] == 4 and control["nextCheckMillis"] == 2**63 - 1,
            "Native planner never reached the exact finite unchanged boundary")
    require(still["players"]["PlayerA"]["jobs"][root] == waiting and still["tick"] >= before["tick"] + 80,
            "Original no-progress decisions did not stop")
    require(final["allowance"] == waiting["allowance"]
            and all(final["usage"].get(k, 0) >= n for k, n in waiting["usage"].items())
            and final["activity"]["production"]["memory"]["originalBudgetDigest"] == control["memory"]["originalBudgetDigest"]
            and final["activity"]["production"]["memory"]["originalDeadlineMillis"] == control["memory"]["originalDeadlineMillis"],
            "Relevant world change reset the original allowance or deadline")
    lineage = proof(final, root, seed["method"])
    a, b = row["beforeMenu"], row["afterMenu"]
    quantities = lambda menu, player: sum(s["count"] for s in menu["slots"]
                                        if s["playerInventory"] is player and s["item"] == LOG)
    require((quantities(a, False), quantities(a, True), quantities(b, False), quantities(b, True)) == (0, 1, 1, 0)
            and any(e["kind"] == "action" and e["data"].get("action") == "menu-click"
                    and e["data"].get("quick") is True for e in events), "No actual independent player resupply")
    require(all(stock(frame, SOURCE).get(LOG, 0) == stock(frame, DESTINATION).get(PLANK, 0) == 0
                for frame in (before, still)) and stock(after, SOURCE).get(LOG, 0) == 0
            and stock(after, DESTINATION).get(PLANK) == 3
            and all(frame[k] == before[k] for frame in (still, after) for k in ("needleCalls", "generationCalls")),
            "Unchanged work made an effect, missed resupply, or invoked inference")
    persisted = original(raw / "cases" / RESTOCK / "saved-world", root)
    require(persisted["state"] == "SUCCEEDED" and allowance(persisted["allowance"], final["allowance"]),
            "Original restocked goal was not durably retained")
    require(feedback_in(events, row["feedback"]) and feedback_in(events, row["activityView"]), "No public feedback")
    frame_link(frames, before, still, after)
    frame_png(raw, row)
    return {"id": RESTOCK, "status": "PASS", "scope": "I00_CONNECTED_EXACT_CHOICE", "root": root,
            "stoppedUnchangedChecks": 4, "playerSupplied": 1, "delivered": 3,
            "originalAllowancePreserved": True, "newModelCalls": 0, "proof": lineage}


def verify_whole_goal(raw, row, seed, recipe, events, frames):
    root, waiting = row["root"], row["waiting"]
    before, blocked, still, after = (row[k] for k in ("before", "firstBlocked", "still", "after"))
    first = blocked["players"]["PlayerA"]["jobs"][root]
    final = after["players"]["PlayerA"]["jobs"][root]
    definition = recipe["recipes"][PLANK]
    require(recipe["serverPid"] == before["pid"] == after["pid"] and recipe["worldEffects"] == 0
            and definition["output"] == PLANK and definition["count"] == 4
            and definition["remainderCount"] == 0 and len(definition["ingredients"]) == 1
            and LOG in definition["ingredients"][0] and definition["slots"] == [0],
            "Installed recipe does not independently establish the one-log legal batch")
    require(root not in before["players"]["PlayerA"]["jobs"] and first["id"] == waiting["id"] == root
            and first["activity"]["production"]["blocker"] == "NO_SUPPORTED_WHOLE_GOAL_PATH"
            and waiting["activity"]["production"]["memory"]["unchangedChecks"] == 4
            and still["players"]["PlayerA"]["jobs"][root] == waiting
            and all(job["state"] == "WAITING" and not job["attempts"] and not job["activity"]["steps"]
                    and job["fulfilled"] == 0 for job in (first, waiting)),
            "The partial feasible prefix was not kept separate from whole-goal authorization")
    a, b = row["beforeMenu"], row["afterMenu"]
    require(tuple(menu_quantity(menu, LOG, player) for menu in (a, b) for player in (False, True)) == (1, 1, 2, 0)
            and any(e["kind"] == "action" and e["data"].get("action") == "menu-click"
                    and e["data"].get("quick") is True for e in events), "The legal whole-goal start lacks real player resupply")
    actor_id = seed["actor"]["entityId"]
    inventory = lambda frame: Counter(next(a["inventory"] for a in frame["actors"] if a["uuid"] == actor_id))
    require(all(stock(frame, SOURCE) == {LOG: 1} and not stock(frame, DESTINATION)
                and inventory(frame) == inventory(before) for frame in (blocked, still))
            and stock(before, SOURCE) == {LOG: 1} and not stock(before, DESTINATION)
            and not stock(after, SOURCE) and stock(after, DESTINATION) == {PLANK: 7}
            and inventory(after) == inventory(before) + Counter({PLANK: 1})
            and all(frame[k] == before[k] for frame in (still, after) for k in ("needleCalls", "generationCalls")),
            "Observed custody failed the whole-goal refusal or resupplied success")
    receipts = [receipt for attempt in final["attempts"] for receipt in attempt["resourceReceipts"]]
    totals = Counter()
    for receipt in receipts:
        totals[receipt["stage"]] += receipt["quantity"]
    require(final["state"] == "SUCCEEDED" and final["fulfilled"] == 7
            and waiting["request"] == final["request"] and waiting["allowance"] == final["allowance"]
            and final["request"]["request"]["arguments"]["amount"]["value"] == 7
            and totals == {"WITHDRAW": 2, "CRAFT": 8, "DEPOSIT": 7}
            and exact_withdrawals(final, receipts) == {LOG: 2}
            and {step["artifact"]["sha256"] for step in final["activity"]["steps"]} == {seed["method"]},
            "The original demand or certified recipe conservation changed")
    saved = raw / "cases" / WHOLE_GOAL / "saved-world"
    require(original(saved, root)["state"] == "SUCCEEDED" and chest(saved, DESTINATION) == {PLANK: 7}
            and feedback_in(events, row["feedback"]) and feedback_in(events, row["activityView"]),
            "Original seven-unit goal, connected feedback or saved delivery missing")
    frame_link(frames, before, blocked, still, after)
    frame_png(raw, row)
    return {"id": WHOLE_GOAL, "status": "PASS", "scope": "I00_CONNECTED_EXACT_CHOICE", "root": root,
            "legalBatchYield": 4, "originalDemand": 7, "effectsWhileInfeasible": 0,
            "playerSupplied": 1, "delivered": 7, "originalAllowancePreserved": True, "newModelCalls": 0}


def verify_missing_method(raw, row, recipe, events, frames):
    root, before, waiting, after = row["root"], row["before"], row["waiting"], row["after"]
    final = after["players"]["PlayerA"]["jobs"][root]
    control = waiting["activity"]["production"]
    installed = recipe["recipes"]["minecraft:acacia_planks"]
    require(installed["count"] == 4 and installed["remainderCount"] == 0
            and any("minecraft:acacia_log" in options for options in installed["ingredients"]),
            "No actual supported installed recipe was present")
    require(row["firstBlocked"]["id"] == root
            and row["firstBlocked"]["activity"]["production"]["blocker"] == "PINNED_METHOD_UNAVAILABLE_OFFLINE"
            and control["nextCheckMillis"] == 2**63 - 1 and control["memory"]["unchangedChecks"] == 4
            and row["dormant"]["players"]["PlayerA"]["jobs"][root] == waiting
            and not final["attempts"] and not final["activity"]["steps"] and not final["fulfilled"]
            and final["state"] == "CANCELLED", "Static recipe supplied false execution authority")
    require(all(stock(frame, SOURCE) == stock(before, SOURCE) and stock(frame, DESTINATION) == stock(before, DESTINATION)
                and frame["drops"] == before["drops"] and all(frame[k] == before[k] for k in ("needleCalls", "generationCalls"))
                for frame in (row["dormant"], after)), "Unadmitted method changed custody or called the model")
    world = raw / "cases" / NO_METHOD / "saved-world"
    require(original(world, root)["state"] == "CANCELLED" and chest(world, SOURCE).get("minecraft:acacia_log") == 1,
            "Settled unsupported root or original input is missing from the save")
    require(feedback_in(events, row["feedback"]), "No actual public submission")
    frame_link(frames, before, row["dormant"], after)
    frame_png(raw, row)
    return {"id": NO_METHOD, "status": "PASS", "scope": "I00_CONNECTED_EXACT_CHOICE", "root": root,
            "installedRecipe": True, "admittedWitnessMissing": True, "nativeEffects": 0, "newModelCalls": 0}


def verify_contention(raw, row, seed, frames):
    selected = [row[key] for key in ("before", "claimed", "overlapping", "waiting", "supplied", "after")]
    before, _, overlap, waiting, supplied, after = selected
    events = {player: completed_jsonl(raw / "clients" / player / "events.jsonl") for player in ("PlayerA", "PlayerB")}
    a, b = row["roots"]["PlayerA"], row["roots"]["PlayerB"]
    require(a != b and before["knownPlayers"]["PlayerA"]["uuid"] != before["knownPlayers"]["PlayerB"]["uuid"]
            and row["actors"]["PlayerA"] != row["actors"]["PlayerB"], "Contention collapsed original principals or workers")
    require(stock(before, SOURCE).get(LOG) == 5 and stock(waiting, SOURCE).get(LOG) == 2
            and stock(supplied, SOURCE).get(LOG) == 4 and stock(after, SOURCE).get(LOG, 0) == 0,
            "Five original logs and independent two-log addition do not reconcile")
    require(overlap["knownPlayers"]["PlayerA"]["jobs"][a]["state"] == "ACTIVE"
            and overlap["knownPlayers"]["PlayerB"]["jobs"][b]["state"] == "WAITING"
            and waiting["knownPlayers"]["PlayerA"]["jobs"][a]["state"] == "SUCCEEDED"
            and waiting["knownPlayers"]["PlayerB"]["jobs"][b]["state"] == "WAITING"
            and not waiting["knownPlayers"]["PlayerB"]["jobs"][b]["attempts"],
            "B did not wait for infeasible original stock while A made useful progress")
    claimed = [lease for lease in row["claimed"]["knownPlayers"]["PlayerA"]["leases"]
               if lease["owner"]["job"] == a and lease["state"] == "ACTIVE"]
    require(claimed and any(lease["resource"]["item"] == "cognitivecraft:recipe_source" for lease in claimed)
            and all(lease["granted"] < lease["expires"] <= lease["granted"] + 1200
                    and lease["renewals"] <= 8 for lease in claimed), "No finite actual imminent A claim")
    client_amount = lambda menu, player: sum(slot["count"] for slot in menu["slots"]
                                            if slot["item"] == LOG and slot["playerInventory"] is player)
    require((client_amount(row["beforeMenu"], False), client_amount(row["beforeMenu"], True),
             client_amount(row["afterMenu"], False), client_amount(row["afterMenu"], True)) == (2, 2, 4, 0)
            and any(event["kind"] == "action" and event["data"].get("action") == "menu-click"
                    and event["data"].get("quick") is True for event in events["PlayerB"]),
            "The independent two-log addition lacks actual client evidence")
    seen_receipts, summaries = set(), []
    for player, root, amount, inputs, destination in (("PlayerA", a, 12, 3, DESTINATION),
                                                     ("PlayerB", b, 16, 4, PEER_DESTINATION)):
        job = after["knownPlayers"][player]["jobs"][root]
        principal, actor = before["knownPlayers"][player]["uuid"], row["actors"][player]
        args = job["request"]["request"]["arguments"]
        require(job["state"] == "SUCCEEDED" and job["fulfilled"] == amount and not job.get("parent")
                and args["amount"]["value"] == amount and args["actor"]["value"]["entityId"] == actor
                and job["request"]["context"]["principal"]["id"] == principal
                and job["allowance"] == overlap["knownPlayers"][player]["jobs"][root]["allowance"],
                "Original contender authority, goal or completion changed")
        require([args["source"]["value"][axis] for axis in ("x", "y", "z")] == SOURCE
                and [args["destination"]["value"][axis] for axis in ("x", "y", "z")] == destination,
                "Original contention source/destination changed")
        receipts = [receipt for attempt in job["attempts"] for receipt in attempt["resourceReceipts"]]
        require(len(job["attempts"]) == len(job["activity"]["steps"]) == len(job["activity"]["checkpoints"]) == 1
                and all(not attempt["uncertain"] and attempt["terminal"]["status"] == "SUCCEEDED" for attempt in job["attempts"])
                and job["activity"]["steps"][0]["artifact"]["sha256"] == seed["method"]
                and exact_withdrawals(job, receipts) == {LOG: inputs}
                and [r["stage"] for r in receipts] == STAGES * inputs
                and sum(r["quantity"] for r in receipts if r["stage"] == "CRAFT") == amount
                and sum(r["quantity"] for r in receipts if r["stage"] == "DEPOSIT") == amount,
                "Contender repeated native work or lost exact recipe/attempt lineage")
        for receipt in receipts:
            require(receipt["receiptId"] not in seen_receipts and receipt["origin"]["principal"]["id"] == principal
                    and receipt["actor"]["entityId"] == actor and receipt["choice"]["root"] == root,
                    "Receipt authority crossed contenders or duplicated")
            seen_receipts.add(receipt["receiptId"])
        require(stock(before, destination).get(PLANK, 0) == 0 and stock(after, destination).get(PLANK) == amount
                and next(a["inventory"] for a in before["actors"] if a["uuid"] == actor)
                    == next(a["inventory"] for a in after["actors"] if a["uuid"] == actor)
                and feedback_in(events[player], row["feedback"][player]) and feedback_in(events[player], row["views"][player])
                and "remaining=0" in row["views"][player] and root not in row["denied"]["PlayerB" if player == "PlayerA" else "PlayerA"],
                "Actual contender delivery, residual custody or private view differs")
        saved = original(raw / "cases" / CONTEND / "saved-world", root)
        require(saved["state"] == "SUCCEEDED" and allowance(saved["allowance"], job["allowance"])
                and chest(raw / "cases" / CONTEND / "saved-world", destination).get(PLANK) == amount,
                "Actual saved contender is missing its original allowance or delivery")
        summaries.append({"root": root, "principal": principal, "actor": actor, "consumedLogs": inputs, "delivered": amount})
    require(all(frame[k] == before[k] for frame in selected for k in ("needleCalls", "generationCalls"))
            and all(clean_owners(frame) for frame in after["knownPlayers"].values())
            and not any(drop["item"] in (LOG, PLANK) for frame in (before, after) for drop in frame["drops"]),
            "Contention regenerated known methods or left original owners open")
    verify_frames(raw, row["screenshots"], minimum=2)
    require({screen["player"] for screen in row["screenshots"]} == {"PlayerA", "PlayerB"}, "Both real clients require frames")
    frame_link(frames, *selected)
    return {"id": CONTEND, "status": "PASS", "scope": "I00_CONNECTED_EXACT_CHOICE", "originalLogs": 5,
            "independentlyAddedLogs": 2, "consumedLogs": 7, "deliveredPlanks": 28,
            "originalOwnersSettled": 2, "newModelCalls": 0, "contenders": summaries}


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
    supervisor = completed_jsonl(raw / "supervisor.jsonl")
    stops = [e["data"] for e in supervisor if e["kind"] == "process-stop-witness"
             and e["data"]["component"] == "server" and not e["data"]["hard"]]
    stop, = stops
    stopped_process(stop["state"], hard=False)
    require(stop["state"]["ExitCode"] == 0, "Exact native JVM did not stop cleanly")
    events = completed_jsonl(raw / "clients/PlayerA/events.jsonl")
    require(any(event["kind"] == "play" for event in events), "No live Minecraft client connected")
    recipe = read(raw / "cold-recipe-catalogue.json")
    latest_recipe = read(raw / "oracle-recipes.json")
    require(recipe["recipes"] == latest_recipe["recipes"] and recipe["worldEffects"] == latest_recipe["worldEffects"] == 0,
            "Installed vanilla recipes changed across the original saved continuation")
    frames, cases = {}, []
    clocks = [event["data"] for event in supervisor if event["kind"] == "verified-native-clock-transition"]
    require([row["request"]["commands"] for row in clocks] == [[command] for command in (
            "tick rate 2", "tick freeze", "tick unfreeze", "tick rate 20",
            "tick freeze", "tick unfreeze", "tick freeze", "tick unfreeze",
            "tick rate 2", "tick rate 20", "tick freeze", "tick unfreeze",
            "tick rate 2", "tick freeze")],
            "Missing exact native clock transitions for restock and contention")
    clock_frames = {}
    for row in clocks:
        before, after = row["before"], row["after"]
        expected = dict(before["nativeClock"])
        command, = row["request"]["commands"]
        if command.startswith("tick rate "):
            expected["rate"] = int(command.split()[-1])
        else:
            expected["frozen"] = command == "tick freeze"
        require(before["pid"] == after["pid"] and before["time"] < after["time"]
                and before["sequence"] < after["sequence"] and before["nativeClock"] != expected == after["nativeClock"]
                and all(frame["worldEffects"] == 0 and frame["positions"] == [] for frame in (before, after)),
                "Clock delivery did not prove the actual native state transition")
        for frame in (before, after):
            clock_frames[frame["id"], frame["sequence"]] = frame
    for line in (raw / "server/i00-native-clock.jsonl").read_text().splitlines():
        frame = json.loads(line)
        key = frame["id"], frame["sequence"]
        if key in clock_frames:
            require(frame == clock_frames.pop(key), "Native clock evidence differs from the original read-only query")
    require(not clock_frames, "Missing original native clock queries")
    for name in (*LIVE, REMOVAL, LOOKALIKE, PARTIAL_CANCEL, RESTOCK, WHOLE_GOAL, NO_METHOD, CONTEND, SAVED_CRAFT):
        row = read(raw / "cases" / name / "result.json")
        if not row or row.get("status") != "PASS_PENDING_INDEPENDENT_REVIEW":
            cases.append({"id": name, "status": row["status"] if row else "NOT_EXECUTED_REQUIRED"})
        elif name in LIVE:
            cases.append(verify_case(raw, row, name, seed, recipe, events, frames))
        elif name == REMOVAL:
            cases.append(verify_removal(raw, row, seed, events, frames, cases))
        elif name == LOOKALIKE:
            cases.append(verify_lookalike(raw, row, seed, events, frames))
        elif name == RESTOCK:
            cases.append(verify_restock(raw, row, seed, events, frames))
        elif name == WHOLE_GOAL:
            cases.append(verify_whole_goal(raw, row, seed, recipe, events, frames))
        elif name == PARTIAL_CANCEL:
            cases.append(verify_cancel_partial(raw, row, seed, events, frames))
        elif name == SAVED_CRAFT:
            cases.append(verify_continue_saved_craft(raw, row, seed, events, frames, supervisor, identity["productionSha256"]))
        elif name == NO_METHOD:
            cases.append(verify_missing_method(raw, row, recipe, events, frames))
        elif name == CONTEND:
            cases.append(verify_contention(raw, row, seed, frames))
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
