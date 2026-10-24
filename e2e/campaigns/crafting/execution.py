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
from .invariants import totals, recipe_balance, composition_balance, delta, physical_custody, interruption_verification
from .oracle import requirements, rotated_cells
from .recipe_model import rules, demands


def shortage(case, state, source, recipes, *, facility=None, blueprint_origin=None):
    """Use actual source stock, observed acquisition cells and exported recipe ratios."""
    source_stock = state["containers"].get(",".join(map(str, source)), {}).copy()
    acquisition = Counter()
    for key, block in state.get("blocks", {}).items():
        position = list(map(int, key.split(",")))
        if all(LO[i] <= position[i] <= HI[i] for i in range(3)):
            item = re.search(r"minecraft:[a-z0-9_]+", block)
            if item and item[0] in {LOG, "minecraft:birch_log"}:
                acquisition[item[0]] += 1
    stock = Counter(source_stock); stock.update(acquisition)
    output, quantity = case.get("output", AXE), case.get("quantity", 1)
    goals, projection = [], {}
    if case["mode"] == "blueprint":
        cells = rotated_cells(case["body"], blueprint_origin, case["rotation"])
        matching = 0
        for cell in cells:
            block = state["blocks"].get(",".join(map(str, cell[:3])), "UNKNOWN")
            wanted = case["body"]["palette"][cell[3]]
            actual = re.search(r"minecraft:[a-z0-9_]+", block)
            if actual and actual[0] == wanted["block"] and all(
                    re.search(r"(?:\[|,)" + re.escape(k) + "=" + re.escape(v) + r"(?:,|\])", block)
                    for k, v in wanted["properties"].items()):
                matching += 1
        quantity = len(cells) - matching
        goals.append({"item": OAK, "quantity": quantity, "consumeExisting": True})
        projection = {"fullBill": len(cells), "existingBlockContributions": matching, "outstanding": quantity,
                      "initialStaged": source_stock.get(OAK, 0),
                      "newOutputRequired": max(0, quantity-source_stock.get(OAK, 0))}
    elif case["mode"] == "legacy":
        return {"feasible": True, "scope": "Bread demand is evaluated after its separate actor fixture"}
    elif (case["mode"] == "facility" and output == TABLE or
          case["mode"] == "recovery" and case["boundary"] == "after-placement"):
        goals.append({"item": TABLE, "quantity": 1, "consumeExisting": True, "allowMake": False})
    else:
        if case["mode"] == "facility":
            present = TABLE in state["blocks"].get(",".join(map(str, facility)), "")
            if not present:
                goals.append({"item": TABLE, "quantity": 1, "consumeExisting": True})
            projection["placementRequired"] = not present
        goals.append({"item": output, "quantity": quantity, "consumeExisting": False})
    return {**demands(rules(recipes), goals, stock), **projection,
            "sourceStock": source_stock, "observedAcquisitionStock": dict(acquisition),
            "stockScope": "Explicit input container plus the approved loaded acquisition area"}


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


def refusal_reason(request, result):
    """Use this request's reported terminal reason, not the fact it used another wood."""
    feedback = (request or {}).get("feedback") or ""
    direct = re.search(r"\breason=([A-Z_]+)", feedback)
    if direct and not (request or {}).get("accepted"):
        return direct[1]
    rows = result.get("views", [])
    if rows:
        root = rows[0]
        return root.get("reason") or (root.get("marker") or {}).get("reason")
    return None


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
        def allocation():
            answer = h.command("PlayerA", "/aivillage kernel blueprint-status " + request["identity"], "blueprint-job=", 40)
            match = re.search(r"\ballocated=(\d+)\b", answer)
            if match and (not result["completed"] or "currentlyAllocated=true" in answer):
                return {"response": answer, "quantity": int(match[1]), "currentlyAllocated": "currentlyAllocated=true" in answer}
            return None
        result["allocation"] = h.wait(allocation, "Acknowledged current physical blueprint allocation", 20)
        result["allocationStatus"] = result["allocation"]["response"]
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
    conserved, balance = composition_balance(totals(before, client), totals(after, final_client), h.recipes)
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
    initial_physical = h.snapshot()
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
        result["durablePhysicalState"] = h.snapshot() if "checkpoint" in result else initial_physical
        if not boundary_matches(case, request, h):
            raise BoundaryMissed("The requested unfinished boundary was gone before the actual server kill")
        result["killWitness"] = {"views": h.views(request), "state": h.snapshot(), "time": time.time()}
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
    result["interruptionVerified"], result["interruptionVerification"] = interruption_verification(request, result, case["interruption"])
    if "durablePhysicalState" in result:
        result["durableCustodyVerified"] = physical_custody(result["durablePhysicalState"]) == physical_custody(fence)
    h.quiet(20)
    require(physical_custody(fence) == physical_custody(h.snapshot()),
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
                                                totals(reuse_after, h.client_snapshot("PlayerA")), h.recipes)
        result["offlineReuse"] = {"request": reuse_request, "result": reuse, "before": reuse_before,
                                  "after": reuse_after, "conservation": conservation, "conserved": ok,
                                  "delivered": h.container(h.destination, AXE), "calls": calls}
        require(ok and reuse["completed"] and h.container(h.destination, AXE) == 1,
                "Changed-binding offline reuse did not produce one conserved physical axe")
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


def fixture_observation(h, expected_tables):
    """Wait for both real observers to see setup before measuring any activity."""
    def ready():
        state = h.snapshot()
        tables = {key for key, block in state.get("blocks", {}).items() if TABLE in block}
        if tables != expected_tables:
            return None
        client = h.client_snapshot("PlayerA")
        tables = {key for key, block in client.get("blocks", {}).items() if TABLE in block}
        return (state, client) if tables == expected_tables else None
    return h.wait(ready, "Server and client observed the complete legacy fixture", 20)


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
    wanted_tables = {",".join(map(str, h.facility))} if case["facility"] == "existing" else set()
    before, client = fixture_observation(h, wanted_tables)
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
