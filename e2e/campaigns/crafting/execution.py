"""Ordinary connected-client operations and independently measured physical outcomes."""
from __future__ import annotations

from collections import Counter
import json
import math
import re
import time

from suite import UUID_PATTERN, atomic, read, require, reconnect_players
from .generator import LOG, OAK, BIRCH, STICK, AXE, TABLE
from .harness import AdmissionBlocked, BoundaryMissed, coord, LO, HI, POSITIONS
from .invariants import totals, recipe_balance, composition_balance, delta
from .oracle import requirements


def shortage(case, state, source):
    """Vanilla arithmetic over measured resources; never reads an execution graph."""
    stock = state["containers"].get(",".join(map(str, source)), {})
    q, output = case.get("quantity", 1), case.get("output", AXE)
    if case["mode"] == "blueprint":
        q = len(case["body"]["cells"]) - min(case["completed"], len(case["body"]["cells"]))
        planks = max(0, q - stock.get(OAK, 0)); sticks = 0
    elif output == AXE:
        sticks = max(0, 2*q - stock.get(STICK, 0))
        planks = max(0, 3*q + 2*((sticks+3)//4) - stock.get(OAK, 0))
    elif output == TABLE:
        planks, sticks = max(0, 4*q - stock.get(OAK, 0)), 0
    else:
        count = (q+3)//4
        available = stock.get("minecraft:birch_log", 0) + case.get("worldLogs", 0)
        return {"requiredLogs": count, "availableLogs": available, "feasible": available >= count}
    needed = (planks + 3) // 4
    available = stock.get(LOG, 0) + case.get("worldLogs", 0)
    return {"missingSticks": sticks, "missingPlanks": planks, "requiredLogs": needed,
            "availableLogs": available, "feasible": available >= needed}


def check_artifacts(h, result):
    capability_keys = {"cognitivecraft:produce_oak_planks": OAK, "cognitivecraft:produce_birch_planks": BIRCH,
                       "cognitivecraft:produce_sticks": STICK, "cognitivecraft:produce_wooden_axes": AXE,
                       "cognitivecraft:produce_crafting_tables": TABLE, "cognitivecraft:place_crafting_table": "placement",
                       "cognitivecraft:deliver_oak_logs": "oak-acquisition", "cognitivecraft:deliver_birch_logs": "birch-acquisition"}
    hashes = set(h.artifacts.values())
    used = []
    valid = True
    for view in result.get("views", []):
        method = (view.get("preparation") or {}).get("method")
        marker = view.get("marker") or {}
        value = method.get("sha256") if method else marker.get("artifactSha256")
        if value:
            used.append(value)
            capability = view.get("request", {}).get("request", {}).get("capability", {}).get("name")
            if capability in capability_keys:
                valid = valid and value == h.artifacts.get(capability_keys[capability])
    return valid and all(x in hashes for x in used), used


def execute_blueprint(h, case):
    encoded = json.dumps(case["body"], separators=(",", ":"))
    answer = h.command("PlayerA", "/aivillage kernel blueprint-store " + encoded,
                       "blueprint=[0-9a-f]{64} status=STORED", 40)
    ref = re.search(r"blueprint=([0-9a-f]{64})", answer)[1]
    minimum = [min(c[i] for c in h.blueprint_cells) for i in range(3)]
    maximum = [max(c[i] for c in h.blueprint_cells) for i in range(3)]
    tail = h.citizens["PlayerA"] + " " + ref + " " + str(case["rotation"]) + " " + coord(
        h.blueprint_origin + minimum + maximum + h.source + h.destination + LO + HI)
    before = h.snapshot()
    preflight = h.command("PlayerA", "/aivillage kernel blueprint-materials preflight " + tail,
                          "mode=PREFLIGHT job=", 40)
    after = h.snapshot()
    require(before["containers"] == after["containers"] and before["blocks"] == after["blocks"],
            "Read-only blueprint preflight changed physical resources")
    require(set(before["knownPlayers"]["PlayerA"]["jobs"]) == set(after["knownPlayers"]["PlayerA"]["jobs"]),
            "Read-only preflight created a Job")
    request = h.submit_command("/aivillage kernel blueprint-materials stage " + tail)
    result = h.finish_request(request, 260)
    result.update(preflight=preflight, blueprint=ref, transformedCells=h.blueprint_cells)
    if request["accepted"]:
        result["allocationStatus"] = h.command("PlayerA", "/aivillage kernel blueprint-status " + request["identity"],
                                               "blueprint-job=", 40)
    return request, result


def execute_facility(h, case):
    request = h.submit_command(h.command_text(case))
    result = h.finish_request(request, 260)
    if case["facility"] != "reuse" or not result["completed"]:
        return request, result
    result["rootMenuCount"] = h.menu_count("PlayerA", h.destination, AXE)
    result["physicalAfter"], result["clientAfter"] = h.snapshot(), h.client_snapshot("PlayerA")
    h.stock(h.source, {OAK: 3, STICK: 2}, case["container"])
    before, client = h.snapshot(), h.client_snapshot("PlayerA")
    reuse_case = {"mode": "recipe", "output": AXE, "quantity": 1}
    second = h.submit_command(h.command_text(reuse_case))
    final = h.finish_request(second, 180)
    after, final_client = h.snapshot(), h.client_snapshot("PlayerA")
    conserved, balance = composition_balance(totals(before, client), totals(after, final_client))
    new_placements = any(r["stage"] == "PLACE" or r["stage"] == "CRAFT" and r["item"]["id"] == TABLE
                         for r in final.get("receipts", []))
    result["facilityReuse"] = {"request": second, "execution": final, "before": before, "after": after,
                               "client": final_client, "conserved": conserved, "conservation": balance,
                               "extraPlacementOrTableProduction": new_placements,
                               "clientMenuCount": h.menu_count("PlayerA", h.destination, AXE)}
    result["reuseVerified"] = conserved and final["completed"] and not new_placements and h.container(h.destination, AXE) == 2
    return request, result


def boundary_matches(case, request, h):
    values = h.views(request)
    if not values:
        return False
    rows = h.receipts_for(request)
    stages = Counter()
    for row in rows:
        stages[row["stage"]] += row["quantity"]
    active = values[0].get("phase") == "EXECUTING" or values[0].get("state") in {"RUNNING", "READY", "WAITING"}
    stage = case["boundary"]
    if stage == "before-withdrawal":
        return active and not stages["WITHDRAW"]
    if stage == "after-withdrawal":
        return active and stages["WITHDRAW"] > 0 and not stages["CRAFT"]
    if stage == "after-intermediate":
        return active and any(r["stage"] == "CRAFT" and r["item"]["id"] != case["output"] for r in rows)
    if stage == "after-placement":
        return active and stages["PLACE"] == 1
    return active and any(r["stage"] == "CRAFT" and r["item"]["id"] == case["output"] for r in rows) and not any(
        r["stage"] == "DEPOSIT" and r["item"]["id"] == case["output"] for r in rows)


def execute_recovery(h, case):
    # Persist the initial fixture. A before-checkpoint crash is assessed against
    # that durable state, not an unjustified cross-file atomic-save assumption.
    initial_checkpoint = h.save_checkpoint()
    h.clock("tick rate 2")
    request = h.submit_command(h.command_text(case))
    if not request["accepted"]:
        h.clock("tick rate 20")
        return request, h.finish_request(request)
    try:
        h.wait(lambda: boundary_matches(case, request, h), "Requested physical interruption boundary", 240)
    except TimeoutError as error:
        raise BoundaryMissed(str(error)) from error
    h.clock("tick freeze")
    if not boundary_matches(case, request, h):
        raise BoundaryMissed("Execution advanced beyond the requested boundary before the vanilla freeze")
    before = h.snapshot()
    client = h.client_snapshot("PlayerA")
    h.screenshot("PlayerA", "witnessed-" + case["boundary"])
    result = {"boundary": {"requested": case["boundary"], "snapshot": before,
                           "client": client, "views": h.views(request), "receipts": h.receipts_for(request)},
              "initialCheckpoint": initial_checkpoint}
    if case["interruption"] == "cancel":
        command = "job-cancel" if request["kind"] == "job" else "cancel"
        result["cancellation"] = h.command("PlayerA", "/aivillage kernel " + command + " " + request["identity"],
                                            "cancel|CANCEL|denied|unavailable", 40)
        h.clock("tick unfreeze"); h.clock("tick rate 20")
        result.update(h.finish_request(request, 80))
    else:
        if case["boundary"] != "before-checkpoint":
            result["checkpoint"] = h.save_checkpoint()
        cold = read(h.root / "server/process.json")
        h.stop("server", hard=True)
        h.actions_active = False
        result["stoppedWorldFiles"] = h.world_hashes()
        h.start("server")
        h.clock("tick unfreeze"); h.clock("tick rate 20")
        reconnect_players(h, ["PlayerA"])
        warm = read(h.root / "server/process.json")
        require(warm["start"] > cold["start"] and warm["releaseSha256"] == cold["releaseSha256"],
                "Recovery did not use a new server JVM and identical release")
        result["processes"] = {"cold": cold, "warm": warm}
        result.update(h.finish_request(request, 80))
    fence = h.snapshot(); fence_client = h.client_snapshot("PlayerA")
    h.quiet(20)
    require(totals(fence, fence_client) == totals(h.snapshot(), h.client_snapshot("PlayerA")),
            "Interrupted task performed late physical effects")
    result["postInterruption"] = {"state": fence, "client": fence_client}
    if case["interruption"] == "restart":
        h.assert_backend_stopped()
        # New explicit request, changed bindings, preserved old custody. No automatic replay.
        h.source, h.destination, h.facility = [67, 208, 76], [71, 208, 76], [73, 208, 76]
        h.stock(h.source, {OAK: 3, STICK: 2}, "barrel")
        h.stock(h.destination, {}, "chest")
        h.console(["setblock " + coord(h.facility) + " minecraft:crafting_table"])
        reuse_case = {"mode": "recipe", "output": AXE, "quantity": 1}
        reuse_before, reuse_client = h.snapshot(), h.client_snapshot("PlayerA")
        calls = h.model_calls()
        reuse_request = h.submit_command(h.command_text(reuse_case))
        reuse = h.finish_request(reuse_request, 200)
        require(h.model_calls() == calls, "Offline known reuse invoked inference")
        reuse_after = h.snapshot()
        ok, conservation = composition_balance(totals(reuse_before, reuse_client),
                                                totals(reuse_after, h.client_snapshot("PlayerA")))
        result["offlineReuse"] = {"request": reuse_request, "result": reuse, "before": reuse_before,
                                  "after": reuse_after, "conservation": conservation, "conserved": ok,
                                  "delivered": h.container(h.destination, AXE), "calls": calls}
        require(ok and reuse["completed"] and h.container(h.destination, AXE) == 1,
                "Changed-binding offline reuse did not produce one conserved physical axe")
    result["completed"] = False
    return request, result


def execute_dynamic(h, case):
    event = case["dynamic"]
    if event == "competing-actors":
        request = h.submit_command(h.command_text(case))
        h.use_actor(1)
        competitor = h.submit_command(h.command_text(case))
        first = h.finish_request(request)
        other = h.finish_request(competitor)
        first["competitor"] = {"request": competitor, "result": other}
        first["competitionWitnessed"] = request["accepted"] and competitor["accepted"]
        return request, first
    target = h.destination if event == "fill-destination" else h.source
    h.walk("PlayerA", target)
    if event != "break-facility":
        h.action("PlayerA", "open", pos=target)
        h.wait(lambda: h.client_snapshot("PlayerA")["menu"] > 0, "Connected mutation menu")
    h.clock("tick rate 2")
    request = h.submit_command(h.command_text(case))
    if not request["accepted"]:
        h.clock("tick rate 20")
        return request, h.finish_request(request)
    def first_batch():
        rows = h.receipts_for(request)
        withdrawals = sum(r["quantity"] for r in rows if r["stage"] == "WITHDRAW")
        return h.views(request) if 0 < withdrawals < (10 if case["output"] == AXE else 2) else None
    try:
        witness = h.wait(first_batch, "First withdrawal before second batch", 160)
    except TimeoutError as error:
        raise BoundaryMissed(str(error)) from error
    if event == "break-facility":
        h.walk("PlayerA", h.facility)
        h.action("PlayerA", "break", pos=h.facility)
        h.wait(lambda: TABLE not in h.snapshot()["blocks"].get(",".join(map(str, h.facility)), ""),
               "Ordinary player destroyed the real facility", 60)
    else:
        slots = h.client_snapshot("PlayerA")["slots"]
        eligible = [s for s in slots if s["count"] and s["playerInventory"] == (event == "fill-destination") and
                    s["item"] == (case["output"] if event == "fill-destination" else LOG)]
        if not eligible:
            raise BoundaryMissed("No physical stack remained for the connected-player mutation")
        for slot in eligible:
            h.action("PlayerA", "menu-click", slot=slot["slot"], quick=True)
        h.wait(lambda: h.container(target, case["output"]) == 64 if event == "fill-destination" else
               h.container(target, LOG) == 0, "Actual connected inventory mutation", 30)
        h.action("PlayerA", "close-menu")
    mutation = h.snapshot()
    live = h.views(request)
    if not live or live[0].get("phase") == "TERMINAL" or live[0].get("state") in {"SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED"}:
        raise BoundaryMissed("The connected mutation was not witnessed while the requested work was active")
    if event == "remove-input":
        moved = sum(s["count"] for s in h.client_snapshot("PlayerA")["slots"]
                    if s["playerInventory"] and s["item"] == LOG)
        if moved < 1:
            raise BoundaryMissed("No remaining ingredient was physically transferred to the player")
    h.clock("tick rate 20")
    result = h.finish_request(request, 200)
    result["dynamic"] = {"event": event, "boundary": witness, "mutation": mutation,
                         "client": h.client_snapshot("PlayerA"), "witnessed": True}
    return request, result


def execute_legacy(h, case, expected):
    if not h.legacy_entity:
        old = {a["uuid"] for a in h.snapshot()["actors"]}
        h.console(["summon minecraft:villager 67.5 208 75.5 {PersistenceRequired:1b}",
                   "tp PlayerA 67.5 208 77.5 180 0"])
        h.legacy_entity = h.wait(lambda: next((a["uuid"] for a in h.snapshot()["actors"] if a["uuid"] not in old), None),
                                 "Separate legacy food actor")
        h.command("PlayerA", "/aivillage enroll BreadWorker", "Enrolled BreadWorker")
    h.console(["tp " + h.legacy_entity + " " + coord([h.source[0]+.5, 208, h.source[2]+1.5])])
    h.actor_stock({"minecraft:wheat": case["wheat"]}, h.legacy_entity)
    h.stock(h.source, {}, case["container"])
    h.command("PlayerA", "/aivillage home BreadWorker " + coord(h.source), "Work area set")
    # The legacy facility scan has a declared radius of three around home.
    h.facility = [h.source[0]+2, 208, h.source[2]]
    h.console(["setblock " + coord(list(x[2])) + " minecraft:air" for x in POSITIONS])
    if case["facility"] == "existing":
        h.console(["setblock " + coord(h.facility) + " minecraft:crafting_table"])
    before, client = h.snapshot(), h.client_snapshot("PlayerA")
    h.actions_active = True
    h.case_attempted = True
    pending = {"command": "/aivillage food BreadWorker", "kind": "legacy", "identity": h.legacy_entity,
               "accepted": True, "feedback": None}
    h.last_request = pending; h.case_requests.append(pending)
    feedback = h.command("PlayerA", "/aivillage food BreadWorker", "food: BreadWorker")
    pending["feedback"] = feedback
    h.wait(lambda: "working" in h.command("PlayerA", "/aivillage status BreadWorker", "BreadWorker") or
           sum(a["inventory"].get("minecraft:bread", 0) for a in h.snapshot()["actors"]) > 0,
           "Legacy activity actually started", 40)
    h.wait(lambda: " | idle | " in h.command("PlayerA", "/aivillage status BreadWorker", "BreadWorker"),
           "Legacy bounded food task stopped", 160)
    h.actions_active = False
    after, after_client = h.snapshot(), h.client_snapshot("PlayerA")
    produced = totals(after, after_client)["minecraft:bread"] - totals(before, client)["minecraft:bread"]
    ok, balance = recipe_balance(totals(before, client), totals(after, after_client), expected)
    return {"command": "/aivillage food BreadWorker", "feedback": feedback, "accepted": True,
            "kind": "legacy", "identity": h.legacy_entity}, {
                "completed": produced > 0, "terminal": True, "before": before, "clientBefore": client,
                "conserved": ok, "conservation": balance, "legacyProduced": produced,
                "requested": expected["produced"], "expected": expected}
