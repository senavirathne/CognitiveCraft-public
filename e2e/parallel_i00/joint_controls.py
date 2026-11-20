"""Connected native I00 child allocation and conflicting original-custody cases."""
from __future__ import annotations

from collections import Counter
import argparse
import base64
import hashlib
import json
from pathlib import Path
import shutil
import traceback

from .gameplay import point, witness_identity
from .native_evidence import (exact_withdrawals, item_at, job_at, jsonl, require_command, stock_at,
                              request_job, terminal_job, verify_frames, verify_installation)
from . import recipe_challenges, recipe_journal, storage_pressure
from campaigns.crafting.generator import AXE, BIRCH, OAK, STICK, TABLE
from campaigns.crafting.harness import coord
from parallel_b.guards import stopped_process
from runtime import digest
from suite import atomic, read, reconnect_players, require


CHILD = "birch-q3-child"
CRASH = "birch-q3-crash-before-allocation"
AMBIGUOUS = "ambiguous-withdrawal-isolates-unrelated-actor"
IDENTICAL = "identical-table-ambiguity-fences"
REPLACED = "removed-replaced-table-invalidates-facility"
MIXED = "mixed-recipe-grid-from-two-authorized-sources"
REQUIRED = (CHILD, CRASH, AMBIGUOUS, IDENTICAL, REPLACED, MIXED, *recipe_challenges.CASES, storage_pressure.CASE)
PROFILE = "I00_JOINT_CUSTODY_CONTROLS"
BIRCH_LOG = "minecraft:birch_log"
OTHER = "minecraft:cobblestone"
SITE = [71, 208, 69]
C_SOURCE, C_DESTINATION = [81, 208, 75], [82, 208, 72]
SECOND_SOURCE = [71, 208, 68]
ACACIA, BAMBOO = "minecraft:acacia_planks", "minecraft:bamboo_planks"
CHILD_CAPABILITY = "cognitivecraft:produce_birch_planks"
AXE_CAPABILITY = "cognitivecraft:produce_wooden_axes"
EXPECTED_CHILD = {"demand": 3, "yield": 4, "batches": 1, "produced": 4,
                  "allocation": 3, "surplus": 1, "remainders": 0}


def live_frame(h, root, valid, reason, timeout=75):
    def predicate():
        state = h.snapshot()
        job = job_at(state, "PlayerA", root) if state else None
        return {"job": job, "state": state} if job and valid(job, state) else None
    return h.wait(predicate, reason, timeout)


def receipts(job):
    return [receipt for attempt in job["attempts"] for receipt in attempt["resourceReceipts"]]


def phases(job):
    return [r["stage"] for r in receipts(job)]


def quantities(job):
    result = Counter()
    for receipt in receipts(job):
        result[receipt["stage"], receipt["item"]["id"]] += receipt["quantity"]
    return result


def position_key(pos):
    return ",".join(map(str, pos))


def set_table(h, requested=True):
    h.console(["setblock " + coord(SITE) + (" minecraft:crafting_table" if requested else " minecraft:air")])
    h.wait(lambda: (TABLE in (h.snapshot()["blocks"].get(position_key(SITE)) or "")) == requested,
           "Original fixture table block before the measured request", 25)


def fixture(h, case):
    from .walkthrough import ACTOR_START, DESTINATION, SOURCE
    h.start("PlayerB")
    h.console(["fill 63 208 63 84 211 78 minecraft:air", "fill 62 207 62 85 207 80 minecraft:stone",
               "kill @e[type=minecraft:item,x=63,y=207,z=63,dx=21,dy=5,dz=15]",
               "clear PlayerA", "clear PlayerB", "gamemode survival PlayerA", "gamemode survival PlayerB",
               "tp PlayerA 67.5 208 69.5", "tp PlayerB 72.5 208 71.5"])
    for _, entity in h.subjects:
        h.actor_stock({}, entity)
    if case == MIXED:
        source = {ACACIA: 1, OTHER: 2}
        h.stock(SECOND_SOURCE, {BAMBOO: 1, OTHER: 3}, layout="last")
        require(h.menu_count("PlayerA", SECOND_SOURCE, BAMBOO) == 1, "Connected client did not see the second source")
    elif case in (*recipe_challenges.CASES, storage_pressure.CASE):
        source = {OAK: 2}
        if case == recipe_challenges.REMAINDERS:
            source = {OAK: 1, recipe_challenges.HONEY: 1}
            h.actor_stock(recipe_challenges.FILLED_ACTOR, h.entities["PlayerA"])
    elif case in (CHILD, CRASH):
        source = {BIRCH_LOG: 1, STICK: 2, OTHER: 2}
    elif case == AMBIGUOUS:
        source = {BIRCH_LOG: 2, OTHER: 2}
        h.stock(C_SOURCE, {BIRCH_LOG: 1})
        h.stock(C_DESTINATION, {})
    else:
        source = {OAK: 3, STICK: 2, OTHER: 2}
        if case == IDENTICAL:
            source[TABLE] = 1
        h.console(["give PlayerB " + TABLE + " 1"])
    h.stock(SOURCE, source)
    h.stock(DESTINATION, {})
    set_table(h, case not in (IDENTICAL, AMBIGUOUS))
    h.console(["deop PlayerA", "deop PlayerB",
               "tp " + h.subjects[-1][1] + " 81.5 208 73.5"])
    input_item = ACACIA if case == MIXED else BIRCH_LOG if case in (CHILD, CRASH, AMBIGUOUS) else OAK
    require(h.menu_count("PlayerA", SOURCE, input_item) == source[input_item],
            "Original connected client did not see the real input stock")
    initial = h.save_checkpoint()
    old = h.snapshot()
    h.console(["tp " + h.entities["PlayerA"] + " " + coord(ACTOR_START)])
    start = h.wait(lambda: (state if (state := h.snapshot()) and state["tick"] > old["tick"] and
                    any(a["uuid"] == h.entities["PlayerA"] and a["alive"] and not a["noAI"]
                        and sum((a - b)**2 for a, b in zip(a["pos"], ACTOR_START, strict=True)) < 1
                        for a in state["actors"]) else None), "Fresh actual original actor start", 25)
    return start, initial


def ask(h, actor, output, amount, source, destination):
    command = h.recipe_command(actor, output, amount, source, destination, SITE if output == AXE else None)
    from .native_evidence import request_job
    return request_job(h, "PlayerA", command)


def freeze(h, root, valid, reason):
    live_frame(h, root, valid, reason)
    h.clock("tick freeze")
    return live_frame(h, root, valid, "Actual freeze retained " + reason, 5)


def remove_from_chest(h, player, position, item, count):
    h.walk(player, position)
    h.action(player, "open", pos=position)
    def opened():
        state = h.client_snapshot(player)
        slot = next((s for s in state["slots"] if not s["playerInventory"] and s["item"] == item
                     and s["count"] == count), None) if state and state.get("menu") else None
        return (state, slot) if slot is not None else None
    before, slot = h.wait(opened, "Actual connected menu shows the original staged stock", 20)
    action = h.action(player, "menu-click", slot=slot["slot"], quick=True)
    after = h.wait(lambda: (state if (state := h.client_snapshot(player)) and state.get("menu")
                    and not any(not s["playerInventory"] and s["item"] == item for s in state["slots"])
                    and sum(s["count"] for s in state["slots"] if s["playerInventory"] and s["item"] == item)
                        == count + sum(s["count"] for s in before["slots"] if s["playerInventory"] and s["item"] == item)
                    else None), "Real client removed the exact original quantity", 20)
    h.action(player, "close-menu")
    return {"player": player, "item": item, "quantity": count, "slot": slot["slot"],
            "beforeMenu": before, "afterMenu": after, "action": action}


def replace_table(h):
    h.walk("PlayerB", SITE)
    broken = h.action("PlayerB", "break", pos=SITE)
    empty = h.wait(lambda: (f if (f := h.snapshot()) and "minecraft:air" in f["blocks"].get(position_key(SITE), "")
                            else None), "Independent PlayerB physically removed the table", 30)
    placed = h.action("PlayerB", "place", pos=[SITE[0], SITE[1]-1, SITE[2]])
    restored = h.wait(lambda: (f if (f := h.snapshot()) and TABLE in f["blocks"].get(position_key(SITE), "")
                               else None), "Independent PlayerB physically placed the identical block type", 20)
    return {"removed": empty, "replacement": restored, "break": broken, "place": placed}


def stop_world(h):
    saved = h.save_checkpoint()
    cold = read(h.root / "server/process.json")
    h.stop("server", hard=True)
    stopped_hashes = h.world_hashes()
    shutil.copytree(h.root / "server/game/world", h.root / "stopped-world", ignore=shutil.ignore_patterns("session.lock"))
    h.compose("stop", "ollama")
    h.assert_backend_stopped()
    h.start("server")
    warm = read(h.root / "server/process.json")
    require(cold["start"] < warm["start"] and cold["releaseSha256"] == warm["releaseSha256"],
            "Production evidence did not survive the exact process replacement")
    reconnect_players(h, ["PlayerA", "PlayerB"], unbound=("PlayerB",))
    return {"saved": saved, "cold": cold, "warm": warm, "hashes": stopped_hashes}


def unfreeze(h):
    h.clock("tick unfreeze")
    h.clock("tick rate 20")


def mixed_sources(h, row, source, destination):
    from .walkthrough import public_views

    primary = h.recipe_command(h.citizens["PlayerA"], STICK, 3, source, destination)
    row["unlisted"] = request_job(h, "PlayerA", primary)
    unlisted = row["unlisted"]["root"]
    require(row["unlisted"]["accepted"], "Original demand did not enter the planner")
    row["cut"] = live_frame(h, unlisted, lambda job, _: job["state"] == "WAITING" and not job["attempts"]
                            and job["activity"]["production"]["memory"]["unchangedChecks"] == 4,
                            "Original one-source request waits with the second source outside its authority", 40)
    row["cancelled"] = h.cancel_job("PlayerA", unlisted)
    terminal_job(h, "PlayerA", unlisted, 45)
    row["request"] = request_job(h, "PlayerA", primary + " " + coord(SECOND_SOURCE))
    root = row["request"]["root"]
    require(row["request"]["accepted"], "Explicitly authorized two-source recipe was refused")
    final = terminal_job(h, "PlayerA", root, 140)
    require(final["state"] == "SUCCEEDED", "Actual two-source demand did not deliver")
    row["after"] = live_frame(h, root, lambda job, _: job == final, "Final two-source physical evidence")
    row["view"] = public_views(h, root)
    row["clientDelivery"] = h.menu_count("PlayerA", destination, STICK)
    row["finishedSave"] = h.save_checkpoint()
    h.screenshot("PlayerA", MIXED + "-outcome", chat=True)
    h.screenshot("PlayerB", MIXED + "-independent-player", chat=True)


def exercise(h, case, row):
    from .walkthrough import DESTINATION, SOURCE, public_views
    row["start"], row["initialSave"] = fixture(h, case)
    row["actor"] = h.entities["PlayerA"]
    row["principal"] = h.players["PlayerA"]
    row["methodPins"] = h.artifacts
    if case == MIXED:
        mixed_sources(h, row, SOURCE, DESTINATION)
        return
    if case in recipe_challenges.CASES:
        recipe_challenges.exercise(h, row, SOURCE, DESTINATION)
        return
    if case == storage_pressure.CASE:
        storage_pressure.exercise(h, row, SOURCE, DESTINATION)
        return
    h.clock("tick rate 4")
    is_axe = case != AMBIGUOUS
    row["request"] = ask(h, h.citizens["PlayerA"], AXE if is_axe else BIRCH, 1 if is_axe else 3, SOURCE, DESTINATION)
    root = row["request"]["root"]
    require(root and row["request"]["accepted"], "Original ordinary authorized activity was refused")
    if case == IDENTICAL:
        prefix = ["WITHDRAW", "PLACE"]
    elif case in (AMBIGUOUS, REPLACED):
        prefix = ["WITHDRAW"]
    elif case == CRASH:
        prefix = ["WITHDRAW", "CRAFT"]
    else:
        prefix = ["WITHDRAW", "CRAFT", "DEPOSIT"]
    row["cut"] = freeze(h, root, lambda job, _: phases(job) == prefix and job["fulfilled"] == 0,
                        "required original native phase prefix")
    h.screenshot("PlayerA", case + "-cut", chat=True)
    if case == CHILD:
        row["external"] = remove_from_chest(h, "PlayerB", SOURCE, BIRCH, 3)
    elif case == AMBIGUOUS:
        row["external"] = remove_from_chest(h, "PlayerB", SOURCE, OTHER, 2)
    elif case in (IDENTICAL, REPLACED):
        row["external"] = replace_table(h)
    if case in (CRASH, AMBIGUOUS, IDENTICAL):
        row["stop"] = stop_world(h)
    unfreeze(h)
    if case == CRASH:
        complete = terminal_job(h, "PlayerA", root, 150)
        require(complete["state"] == "SUCCEEDED", "Original resumed parent failed")
        row["clientDelivery"] = h.menu_count("PlayerA", DESTINATION, AXE)
    elif case == CHILD:
        row["blocked"] = live_frame(h, root, lambda job, _: job["state"] == "WAITING"
                                    and len(job["activity"]["checkpoints"]) == 1 and phases(job) == prefix
                                    and job["activity"]["production"]["blocker"] != "",
                                    "Missing child stock invalidates the current parent")
    else:
        row["blocked"] = live_frame(h, root, lambda job, _: job["state"] == "INTERRUPTED" and phases(job) == prefix,
                                    "Original conflicting custody stays interrupted")
    if case == AMBIGUOUS:
        row["blockedActor"] = ask(h, h.citizens["PlayerA"], BIRCH, 3, C_SOURCE, C_DESTINATION)
        row["blockedSource"] = ask(h, h.subjects[-1][0], BIRCH, 3, SOURCE, C_DESTINATION)
        row["unrelated"] = ask(h, h.subjects[-1][0], BIRCH, 3, C_SOURCE, C_DESTINATION)
        require(row["unrelated"]["accepted"], "Independent actor could not use its unrelated source")
        row["unrelatedJob"] = terminal_job(h, "PlayerA", row["unrelated"]["root"], 140)
        row["clientDelivery"] = h.menu_count("PlayerA", C_DESTINATION, BIRCH)
    h.quiet(20)
    row["after"] = live_frame(h, root, lambda job, _: bool(job), "Original decision remains visible after the final check")
    row["view"] = public_views(h, root)
    h.screenshot("PlayerA", case + "-outcome", chat=True)
    h.screenshot("PlayerB", case + "-independent-player", chat=True)


def run(root, seed):
    from .walkthrough import boot_trial
    for case in REQUIRED:
        folder = root / case
        folder.mkdir(parents=True, exist_ok=False)
        h = None
        journal_volume = storage_pressure.JournalVolume(folder) if case == storage_pressure.CASE else None
        row = {"id": case, "status": "FAIL_NATIVE"}
        try:
            h = boot_trial(folder, seed, profile=PROFILE, journal_volume=journal_volume)
            h.family = case
            exercise(h, case, row)
            row["status"] = "COLLECTED"
        except Exception as failure:
            row.update(reason=str(failure), trace=traceback.format_exc())
        finally:
            try:
                if h is not None:
                    row["screenshots"] = h.screenshots
                    h.finish()
            finally:
                if journal_volume is not None:
                    journal_volume.close()
                atomic(folder / "result.json", row)


def source_frames(folder, row):
    frames = [row["start"], row["cut"]["state"], row["after"]["state"]]
    if "blocked" in row:
        frames.append(row["blocked"]["state"])
    if "removed" in row.get("external", {}):
        frames.extend(row["external"][kind] for kind in ("removed", "replacement"))
    wanted = {(f["pid"], f["tick"], f["time"]): f for f in frames}
    for frame in jsonl(folder / "server/observations.jsonl"):
        key = frame["pid"], frame["tick"], frame["time"]
        if key in wanted:
            require(wanted.pop(key) == frame, "Reported state differs from the actual native observer frame")
    require(not wanted, "Missing native observations for an original cut or interference")


def confirmed_stop(folder, row, jar):
    from .walkthrough_review import world_hashes
    stop = row["stop"]
    cold, warm = stop["cold"], stop["warm"]
    require(cold["releaseSha256"] == warm["releaseSha256"] == jar and cold["start"] < warm["start"]
            and row["cut"]["state"]["pid"] == cold["pid"] and row["after"]["state"]["pid"] == warm["pid"],
            "Native before and after evidence belongs to different binary or process")
    events = list(jsonl(folder / "supervisor.jsonl"))
    killed, = [event for event in events if event["kind"] == "process-stop-witness"
               and event["data"]["process"] == cold]
    require(killed["data"]["hard"] and stopped_process(killed["data"]["state"], hard=True)
            and killed["data"]["state"]["OOMKilled"] is False
            and any(event["kind"] == "acknowledged-world-checkpoint" and event["data"] == stop["saved"]
                    and event["time"] < killed["time"] for event in events)
            and any(event["kind"] == "crafting-backend-unavailable" and event["time"] > killed["time"] for event in events),
            "Original native save, hard kill or unavailable model witness missing")
    world = folder / "stopped-world"
    require(world_hashes(world) == stop["hashes"] and stop["hashes"], "Original stopped world bytes differ")
    return world


def player_interference(folder, event, kind):
    commands = [row["data"] for row in jsonl(folder / "clients/PlayerB/events.jsonl")
                if row["kind"] == "action"]
    if kind == "stock":
        require(any(a["action"] == "menu-click" and a.get("quick") is True and a.get("slot") == event["slot"]
                    for a in commands), "No real connected PlayerB transfer produced the stock change")
        def totals(menu, held):
            return sum(s["count"] for s in menu["slots"] if s["item"] == event["item"] and s["playerInventory"] == held)
        before, after = event["beforeMenu"], event["afterMenu"]
        require(before["play"] and after["play"] and before["uuid"] == after["uuid"]
                and totals(before, False) == event["quantity"] and totals(after, False) == 0
                and totals(after, True) - totals(before, True) == event["quantity"],
                "Public client transfer did not move the original native units")
    else:
        require(any(a["action"] == "break" and a.get("pos") == SITE for a in commands)
                and any(a["action"] == "place" and a.get("pos") == [SITE[0], SITE[1]-1, SITE[2]] for a in commands)
                and "minecraft:air" in event["removed"]["blocks"][position_key(SITE)]
                and TABLE in event["replacement"]["blocks"][position_key(SITE)]
                and event["removed"]["tick"] < event["replacement"]["tick"],
                "Identical table replacement was not an actual connected player intervention")


def child_proof(folder, row, jar):
    from .walkthrough import DESTINATION, SOURCE
    from .integrated_controls import chest, chest_row, entities, original
    case, actor = row["id"], row["actor"]
    initial, final = row["cut"]["job"], row["after"]["job"]
    before, boundary, after = row["start"], row["cut"]["state"], row["after"]["state"]
    child = final["activity"]["steps"][0]
    old, new = initial["attempts"][0], final["attempts"][0]
    scope = final["request"]["context"]["scope"]["worldId"]
    require(child["artifact"]["capability"]["name"] == CHILD_CAPABILITY
            and child["artifact"]["sha256"] == row["methodPins"][BIRCH]
            and child["quantities"] == EXPECTED_CHILD and child["goalCredit"] == 0
            and new["resourceReceipts"][:len(old["resourceReceipts"])] == old["resourceReceipts"]
            and new["id"] == old["id"] == child["attempt"] and new["generation"] == old["generation"]
            and new["worker"] == old["worker"] and new["bound"] == old["bound"] and new["allowance"] == old["allowance"]
            and new["executions"] == old["executions"]
            and all(new["usage"].get(k, 0) >= v for k, v in old["usage"].items())
            and [r["stage"] for r in new["resourceReceipts"]] == ["WITHDRAW", "CRAFT", "DEPOSIT"]
            and [r["quantity"] for r in new["resourceReceipts"]] == [1, 4, 3]
            and exact_withdrawals(final, new["resourceReceipts"]) == {BIRCH_LOG: 1}
            and final["activity"]["checkpoints"][0]["quantity"] == 3 and new["credited"] == 3
            and item_at(before, SOURCE, BIRCH_LOG) == 1 and item_at(after, SOURCE, BIRCH_LOG) == 0
            and item_at(before, SOURCE, OTHER) == item_at(after, SOURCE, OTHER) == 2
            and stock_at(before, actor) == {} and stock_at(after, actor) == {BIRCH: 1},
            "Original exact child delivery, allowance or genuine residual custody differs")
    if case == CHILD:
        player_interference(folder, row["external"], "stock")
        require(final["state"] == "WAITING" and final["fulfilled"] == 0 and len(final["attempts"]) == 1
                and item_at(boundary, SOURCE, BIRCH) == 3 and item_at(after, SOURCE, BIRCH) == 0
                and item_at(after, DESTINATION, AXE) == 0
                and final["activity"]["checkpoints"] == row["blocked"]["job"]["activity"]["checkpoints"]
                and row["blocked"]["job"]["activity"]["production"]["blocker"] != "",
                "Child stock loss permitted a parent mutation or erased the verified child delivery")
    else:
        world = confirmed_stop(folder, row, jar)
        saved = original(world, initial["id"])
        require(saved["attempts"][0]["id"] == old["id"] and len(saved["activity"]["checkpoints"]) == 0
                and final["state"] == "SUCCEEDED" and final["fulfilled"] == 1 and len(final["attempts"]) == 2
                and child["savedPrefixEffects"] == 2 and item_at(after, DESTINATION, AXE) == row["clientDelivery"] == 1
                and stock_at(boundary, actor) == {BIRCH: 4}
                and item_at(boundary, SOURCE, BIRCH) == 0 and chest(world, SOURCE) == {STICK: 2, OTHER: 2}
                and after["brokerStats"]["calls"] == after["brokerStats"]["dispatched"] == after["needleCalls"] == 0,
                "Native process restart failed original child custody, allocation or parent delivery")
        wal = recipe_journal.phases(recipe_journal.read(world, expected_world=scope), initial["id"])
        require([e["prepare"]["phase"] for e in wal] == ["WITHDRAW", "CRAFT"] and all(map(recipe_journal.positive, wal)),
                "The original world was stopped outside the saved craft-before-allocation boundary")
        recipe_journal.saved_marker(entities(world)[actor], wal[-1], wal[-1]["result"]["after"][0])
        recipe_journal.saved_marker(chest_row(world, SOURCE), wal[0], wal[0]["result"]["after"][1])
        require(all(e["prepare"]["attempt"] == old["id"] and e["prepare"]["generation"] == old["generation"]
                    and e["prepare"]["method"] == child["artifact"]["sha256"] for e in wal),
                "Saved native prefix changed the original child attempt")
    return {"attempt": old["id"], "deliveredToParent": 3, "actorSurplus": 1, "parentCredit": final["fulfilled"]}


def conflicting_proof(folder, row, jar):
    from .walkthrough import DESTINATION, SOURCE
    case, actor = row["id"], row["actor"]
    cut, after = row["cut"]["job"], row["after"]["job"]
    state = row["after"]["state"]
    prefix = ("WITHDRAW", "PLACE") if case == IDENTICAL else ("WITHDRAW",)
    require(tuple(phases(cut)) == tuple(phases(after)) == prefix and receipts(cut) == receipts(after)
            and cut["generation"] == after["generation"] and cut["attempts"][0]["id"] == after["attempts"][0]["id"]
            and after["fulfilled"] == 0 and after["state"] == "INTERRUPTED"
            and item_at(state, DESTINATION, AXE if case != AMBIGUOUS else BIRCH) == 0
            and "next=RECOVER_OR_SETTLE" in row["view"]["owner"],
            "Conflicting original native state yielded new custody, credit or a misleading public view")
    if case == AMBIGUOUS:
        from .integrated_controls import chest, entities
        player_interference(folder, row["external"], "stock")
        world = confirmed_stop(folder, row, jar)
        wal = recipe_journal.phases(recipe_journal.read(world), cut["id"])
        require(len(wal) == 1 and recipe_journal.positive(wal[0]) and wal[0]["prepare"]["phase"] == "WITHDRAW"
                and chest(world, SOURCE) == {BIRCH_LOG: 1} and stock_at(state, actor) == {BIRCH_LOG: 1},
                "Saved ambiguous withdrawal did not retain the actual source conflict and original custody")
        recipe_journal.saved_marker(entities(world)[actor], wal[0], wal[0]["result"]["after"][0])
        for issue in ("blockedActor", "blockedSource", "unrelated"):
            request = row[issue]
            require_command(folder / "clients/PlayerA/events.jsonl", request["command"], request["feedback"], "PlayerA")
        require(all(not row[issue]["accepted"] and "INTERRUPTED" in row[issue]["feedback"]
                    for issue in ("blockedActor", "blockedSource"))
                and row["unrelated"]["accepted"] and row["unrelatedJob"]["state"] == "SUCCEEDED",
                "Affected scope did not refuse independent work or unrelated work did not proceed")
        reply = row["unrelatedJob"]
        require(reply["id"] == row["unrelated"]["root"] and reply["attempts"][0]["worker"]["entityId"] != actor
                and reply["fulfilled"] == 3 and item_at(state, C_DESTINATION, BIRCH) == row["clientDelivery"] == 3,
                "Unrelated actor failed independent actual delivery")
    else:
        player_interference(folder, row["external"], "table")
        require(TABLE in state["blocks"][position_key(SITE)]
                and point(cut["request"]["request"]["arguments"]["table"]["value"]) == SITE,
                "The physically replaced site differs from the original approved table")
        if case == IDENTICAL:
            world = confirmed_stop(folder, row, jar)
            wal = recipe_journal.phases(recipe_journal.read(world), cut["id"])
            require([e["prepare"]["phase"] for e in wal] == list(prefix) and all(map(recipe_journal.positive, wal))
                    and wal[-1]["result"]["placement"]["blockState"] == TABLE,
                    "Original saved native placement was missing before the identical external substitute")
        else:
            require(stock_at(state, actor) == {OAK: 3, STICK: 2}
                    and after["activity"]["steps"][0]["artifact"]["capability"]["name"] == AXE_CAPABILITY,
                    "Removed or replaced table consumed any original withdrawn ingredient")
    return {"attempt": cut["attempts"][0]["id"], "goalCredit": 0, "originalEffects": len(prefix)}


def review_mixed_sources(folder, row):
    from .walkthrough import DESTINATION, SOURCE
    from review_lifecycle_connected import chest, chest_row, original

    start, cut, end = row["start"], row["cut"], row["after"]
    blocked, final = cut["job"], end["job"]
    require(blocked["id"] == row["unlisted"]["root"] != final["id"] == row["request"]["root"]
            and not blocked["attempts"] and "cancellation=true" in row["cancelled"]
            and not blocked["activity"]["production"].get("additionalSource")
            and final["request"]["request"] == blocked["request"]["request"]
            and final["request"]["context"] == blocked["request"]["context"]
            and point(final["activity"]["production"]["additionalSource"]) == SECOND_SOURCE,
            "Original primary binding or separately authorized secondary source changed")
    state = end["state"]
    require(final["state"] == "SUCCEEDED" and final["fulfilled"] == 3 and row["clientDelivery"] == 3
            and final["id"] not in row["view"]["foreign"]
            and final["request"]["context"]["principal"]["id"] == row["principal"]
            and all(item_at(frame, SOURCE, ACACIA) == item_at(frame, SECOND_SOURCE, BAMBOO) == 1
                    and not item_at(frame, DESTINATION, STICK) for frame in (start, cut["state"]))
            and not item_at(state, SOURCE, ACACIA) and not item_at(state, SECOND_SOURCE, BAMBOO)
            and item_at(state, SOURCE, OTHER) == 2 and item_at(state, SECOND_SOURCE, OTHER) == 3
            and item_at(state, DESTINATION, STICK) == 3 and stock_at(state, row["actor"]) == {STICK: 1}
            and all(frame["generationCalls"] == start["generationCalls"] for frame in (cut["state"], state)),
            "Physical two-source withdrawal, surplus, privacy or authorization failed")
    step, = final["activity"]["steps"]
    choice = base64.b64decode(step["choice"]["base64"], validate=True)
    bindings = json.loads(choice)["batches"][0]["bindings"]
    installed = read(folder / "oracle-recipes.json")
    recipe = installed["recipes"][STICK]
    require(installed["worldEffects"] == 0 and installed["serverPid"] == state["pid"] and recipe["count"] == 4
            and recipe["remainderCount"] == 0 and len(recipe["ingredients"]) == 2
            and all({BAMBOO, ACACIA} <= set(options) for options in recipe["ingredients"])
            and hashlib.sha256(choice).hexdigest() == step["choice"]["sha256"]
            and step["artifact"]["sha256"] == row["methodPins"][STICK]
            and {(item["item"]["id"], tuple(point(item["source"])), item["sourceSlot"], item["gridSlot"]) for item in bindings}
                == {(ACACIA, tuple(SOURCE), 0, 0), (BAMBOO, tuple(SECOND_SOURCE), 26, 2)}
            and exact_withdrawals(final, receipts(final)) == {ACACIA: 1, BAMBOO: 1},
            "Exact mixed native slots or admitted method differ")
    world = folder / "server/game/world"
    world_id = final["request"]["context"]["scope"]["worldId"]
    journal = recipe_journal.phases(recipe_journal.read(world, expected_world=world_id), final["id"])
    require([entry["prepare"]["phase"] for entry in journal] == ["WITHDRAW", "CRAFT", "DEPOSIT"]
            and all(recipe_journal.positive(entry) and entry["prepare"]["attempt"] == step["attempt"] for entry in journal)
            and original(world, final["id"])["fulfilled"] == 3 and chest(world, DESTINATION) == {STICK: 3},
            "Native saved world did not retain one original mixed recipe attempt")
    by_address = {participant["address"]: participant for participant in journal[0]["result"]["after"]}
    for point_value in (SOURCE, SECOND_SOURCE):
        address = "chest:minecraft:overworld/" + position_key(point_value)
        recipe_journal.saved_marker(chest_row(world, point_value), journal[0], by_address[address])
    events = folder / "clients/PlayerA/events.jsonl"
    require_command(events, row["unlisted"]["command"], row["unlisted"]["feedback"], "PlayerA")
    require_command(events, row["request"]["command"], row["request"]["feedback"], "PlayerA")
    source_frames(folder, row)
    return {"id": MIXED, "root": final["id"], "status": "PASS", "scope": "I00_NATIVE_MIXED_GRID",
            "separatelyAuthorizedSources": 2, "exactMixedSlots": 2, "unlistedSourceEffects": 0,
            "delivered": 3, "retained": 1, "evidenceSha256": digest(folder / "result.json")}


def review(root, source, jar):
    from .campaign import admissions
    from .identity import candidate
    result = {"schema": 1, "stage": "I00", **candidate(source, jar), "status": "FAIL_REQUIRED_JOINT_CONTROLS",
              "requiredCases": list(REQUIRED), "cases": []}
    for case in REQUIRED:
        folder = root / case
        try:
            from .walkthrough import DESTINATION, SOURCE

            row = read(folder / "result.json")
            require(row and row["status"] == "COLLECTED", (row or {}).get("reason", "Required case was not executed"))
            meta = read(folder / "metadata.json")
            result.update(witness_identity(meta, source, jar, PROFILE))
            require(row["methodPins"] == admissions(root.parent / "seed"), "The exact admitted method pins differ")
            verify_installation(folder, meta, ("PlayerA", "PlayerB"))
            verify_frames(folder, row["screenshots"], minimum=2)
            if case == MIXED:
                result["cases"].append(review_mixed_sources(folder, row))
                continue
            if case in recipe_challenges.CASES:
                result["cases"].append(recipe_challenges.review(folder, row, SOURCE, DESTINATION))
                continue
            if case == storage_pressure.CASE:
                result["cases"].append(storage_pressure.review(folder, row, SOURCE, DESTINATION, jar))
                continue
            state = row["after"]["state"]
            old, final = row["cut"]["job"], row["after"]["job"]
            require(old["id"] == final["id"] == row["request"]["root"] and final == job_at(state, "PlayerA", final["id"])
                    and old["request"] == final["request"] and old["allowance"] == final["allowance"]
                    and final["request"]["context"]["principal"]["id"] == row["principal"]
                    and final["request"]["request"]["arguments"]["actor"]["value"]["entityId"] == row["actor"]
                    and final["id"] not in row["view"]["foreign"], "Original activity owner, entitlement or private view changed")
            require_command(folder / "clients/PlayerA/events.jsonl", row["request"]["command"], row["request"]["feedback"], "PlayerA")
            source_frames(folder, row)
            proof = child_proof(folder, row, jar) if case in (CHILD, CRASH) else conflicting_proof(folder, row, jar)
            result["cases"].append({"id": case, "status": "PASS", "root": final["id"], **proof,
                                    "evidenceSha256": digest(folder / "result.json")})
        except Exception as failure:
            result["cases"].append({"id": case, "status": "FAIL", "reason": str(failure)})
    if all(row["status"] == "PASS" for row in result["cases"]):
        result["status"] = "PASS_REQUIRED_JOINT_CONTROLS"
    atomic(root / "joint-review.json", result)
    print("I00_JOINT_REVIEW " + json.dumps(result), flush=True)
    return result["status"] == "PASS_REQUIRED_JOINT_CONTROLS"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--jar-sha256", required=True)
    args = parser.parse_args()
    if not review(args.root.resolve(), args.source, args.jar_sha256):
        raise SystemExit(1)
