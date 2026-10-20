#!/usr/bin/env python3
"""Execute the frozen 45-case assessment without changing production behavior."""
from __future__ import annotations

import argparse
import json
import re
import time
import traceback
from pathlib import Path

from suite import ROOT, UUID_PATTERN, InfrastructureBlocked, atomic, read, require, reconnect_players
from generalization_support import (AssessmentHarness, AssessmentBlocked, Inconclusive, at, coord,
                                    SRC, DST, FACILITY, SITE, LO, HI, LOG, PLANK, STICK,
                                    AXE, TABLE, BIRCH, BIRCH_PLANK, CAPS)


def receipts(view, stage, item=None):
    return sum(r["quantity"] for r in view.get("resourceReceipts", [])
               if r["stage"] == stage and (item is None or r["item"]["id"] == item))


def graph_receipts(graph, stage, item=None):
    return sum(receipts(a, stage, item) for n in graph for a in n.get("attempts", []))


def physical_item_total(h, item):
    state = h.snapshot()
    total = sum(box.get(item, 0) for box in state["containers"].values())
    total += sum(a["inventory"].get(item, 0) for a in state["actors"])
    total += sum(d["count"] for d in state["drops"] if d["item"] == item)
    for player in ("PlayerA", "PlayerB"):
        client = h.client_snapshot(player)
        total += sum(s["count"] for s in client["slots"] if s["playerInventory"] and s["item"] == item)
    return total


def acquisition(h, q, positions, *, item=LOG, destination=DST, container="chest", player="PlayerA",
                extra_oak=None, reset=True):
    if reset:
        h.reset({}, destination=destination, destination_kind=container, table=False)
    h.logs(positions, item)
    if extra_oak:
        h.logs(extra_oak, LOG)
    minimum = [min(p[i] for p in positions+(extra_oak or [])) for i in range(3)]
    maximum = [max(p[i] for p in positions+(extra_oak or [])) for i in range(3)]
    before = h.snapshot(); inv = h.inventory(player); calls = h.model_calls()
    rid = h.submit(item, q, minimum=minimum, maximum=maximum, destination=destination, player=player)
    view = h.finish_run(rid, player)
    require(all(receipts(view, stage, item) == q for stage in ("HARVEST", "PICKUP", "DEPOSIT")),
            "Actual harvest/pickup/deposit lineage does not equal the requested quantity")
    require(h.container(destination, item) == q and h.inventory(player).get(item, 0) == inv.get(item, 0),
            "Actual acquisition conservation mismatch")
    require(all("minecraft:air" in h.block(p) for p in positions), "Selected physical logs remain")
    require(all(LOG in h.block(p) for p in (extra_oak or [])), "Unrequested oak logs were removed")
    require(view["marker"]["artifactSha256"] == h.hashes["cognitivecraft:"+CAPS[item]],
            "New bindings changed the previously admitted executable method")
    require(h.model_calls() == calls, "Known acquisition invoked a model")
    actual = h.menu_count(player, destination, item)
    require(actual == q, "Connected destination menu mismatch")
    return {"run": rid, "before": before, "view": view, "positions": positions,
            "minimum": minimum, "maximum": maximum, "destination": destination,
            "clientMenuCount": actual, "inventoryBefore": inv, "inventoryAfter": h.inventory(player),
            "modelCallsBefore": calls, "modelCallsAfter": h.model_calls()}


def verify_axes_graph(h, q, stock, remaining):
    data = h.compose_axes(q, stock)
    require((h.container(SRC, LOG), h.container(SRC, PLANK), h.container(SRC, STICK)) == remaining,
            "Stock-dependent graph failed its exact surplus calculation")
    require(graph_receipts(data["graph"], "HARVEST") == 0, "Unnecessary additional resource acquisition")
    require(graph_receipts(data["graph"], "CRAFT", AXE) == q and graph_receipts(data["graph"], "DEPOSIT", AXE) == q,
            "Composition did not physically produce and deliver all requested axes")
    require(h.menu_count("PlayerA", DST, AXE) == q, "Connected composed output mismatch")
    data["physicalRemaining"] = {item: h.container(SRC, item) for item in (LOG, PLANK, STICK)}
    return data


def place_table(h):
    source, site = at(6, 12), at(17, 12)
    h.reset({TABLE: 1}, source=source, table=False, source_kind="barrel")
    before = h.snapshot(); inv = h.inventory(); calls = h.model_calls()
    rid = h.submit("place", source=source, target=site)
    view = h.finish_run(rid)
    require(receipts(view, "WITHDRAW", TABLE) == 1 and receipts(view, "PLACE", TABLE) == 1,
            "Placement lacks exactly one attributable withdrawal and placement")
    require(h.container(source, TABLE) == 0 and TABLE in h.block(site) and
            h.inventory().get(TABLE, 0) == inv.get(TABLE, 0), "Actual item-to-block conservation mismatch")
    require(view["marker"]["artifactSha256"] == h.hashes["cognitivecraft:"+CAPS["place"]] and
            h.model_calls() == calls, "Fresh placement changed its method or invoked inference")
    return {"run": rid, "before": before, "view": view, "source": source, "site": site,
            "inventoryBefore": inv, "inventoryAfter": h.inventory(), "modelCallsAfter": h.model_calls()}


def provision(h):
    site = at(17, 10)
    h.reset({PLANK: 3, STICK: 2, TABLE: 1}, table=False)
    before = h.snapshot(); calls = h.model_calls()
    answer = h.command("PlayerA", "/aivillage kernel provision-axe "+h.citizens["PlayerA"]+" "+coord(SRC+DST+site+LO+HI), "job="+UUID_PATTERN)
    require("accepted=true" in answer, "Supplied-facility provisioning rejected: "+answer)
    h.actions_active = True; jid = re.search("job=("+UUID_PATTERN+")", answer).group(1)
    graph = h.complete_job(jid)
    caps = [n["request"]["request"]["capability"]["name"] for n in graph]
    require(caps.count("cognitivecraft:place_crafting_table") == 1 and
            "cognitivecraft:produce_crafting_tables" not in caps, "Supplied table was unnecessarily manufactured")
    require(TABLE in h.block(site) and h.container(SRC, TABLE) == 0 and h.container(DST, AXE) == 1,
            "Facility provisioning did not leave the real facility and axe")
    require(graph_receipts(graph, "PLACE", TABLE) == 1 and graph_receipts(graph, "CRAFT", AXE) == 1 and
            graph_receipts(graph, "HARVEST") == 0 and h.model_calls() == calls,
            "Provisioning duplicated a facility, acquired unnecessary stock or inferred")
    require(h.menu_count("PlayerA", DST, AXE) == 1, "Actual provisioned axe menu mismatch")
    return {"job": jid, "before": before, "graph": graph, "site": site,
            "modelCallsBefore": calls, "modelCallsAfter": h.model_calls()}


def store_blueprint(h, body):
    answer = h.command("PlayerA", "/aivillage kernel blueprint-store "+json.dumps(body, separators=(",", ":")),
                       "blueprint=[0-9a-f]{64} status=STORED")
    return re.search("blueprint=([0-9a-f]{64})", answer).group(1)


def blueprint(h, q, stocked, already=0, rotation=0):
    from blueprints import body
    origin = at(14, 5) if rotation else at(14, 11)
    end = [origin[0], origin[1], origin[2]+q-1] if rotation else [origin[0]+q-1, origin[1], origin[2]]
    h.reset({PLANK: stocked}, destination=SRC, table=False)
    h.logs([at(x, 2) for x in range(6, 8 if q == 7 else 7)])
    correct = [at(14+i, 11) for i in range(already)]
    if correct:
        h.console(["setblock "+coord(p)+" "+PLANK for p in correct])
    ref = store_blueprint(h, body(q)); before = h.snapshot(); inv = h.inventory(); calls = h.model_calls()
    tail = ref+" "+str(rotation)+" "+coord(origin+origin+end+SRC+SRC+LO+HI)
    preflight = h.command("PlayerA", "/aivillage kernel blueprint-materials preflight "+h.citizens["PlayerA"]+" "+tail,
                          "mode=PREFLIGHT job=")
    require("accepted=true" in preflight and "job=null" in preflight, "Read-only supported assessment refused: "+preflight)
    before_jobs = set(before["knownPlayers"]["PlayerA"]["jobs"])
    require(set(h.snapshot()["knownPlayers"]["PlayerA"]["jobs"]) == before_jobs and
            h.snapshot()["containers"] == before["containers"], "Preflight created work or moved materials")
    answer = h.command("PlayerA", "/aivillage kernel blueprint-materials stage "+h.citizens["PlayerA"]+" "+tail,
                       "mode=STAGE_MATERIALS job=")
    require("accepted=true" in answer, "Supported staging refused: "+answer)
    jid = re.search("job=("+UUID_PATTERN+")", answer).group(1); h.actions_active = True
    graph = h.complete_job(jid); outstanding = q-already; new = outstanding-stocked
    status = h.command("PlayerA", "/aivillage kernel blueprint-status "+jid, "blueprint-job="+jid)
    if "currentlyAllocated=true" not in status:
        status = h.wait(lambda: (s if "currentlyAllocated=true" in (s := h.command("PlayerA", "/aivillage kernel blueprint-status "+jid, "blueprint-job="+jid)) else None), "Acknowledged current material allocation", 20)
    require(f"allocated={outstanding}" in status and h.container(SRC, PLANK) == outstanding,
            "Stage did not reserve exactly the outstanding physical materials")
    produced = graph_receipts(graph, "CRAFT", PLANK); deposited = graph_receipts(graph, "DEPOSIT", PLANK)
    require(deposited == new and produced == ((new+3)//4)*4,
            "Stock-first shortage or installed batch calculation mismatch")
    require(h.inventory().get(PLANK, 0) == inv.get(PLANK, 0)+produced-deposited,
            "Blueprint material surplus lost")
    require(all(PLANK in h.block(p) for p in correct), "Already correct blocks changed")
    require(sum(PLANK in h.block([x, 208, z]) for x in range(origin[0], end[0]+1)
                for z in range(origin[2], end[2]+1)) == already, "Material staging placed a building block")
    require(h.model_calls() == calls and not graph[0]["attempts"], "Staging inferred or fabricated executable root effects")
    actual = h.menu_count("PlayerA", SRC, PLANK); require(actual == outstanding, "Staged physical menu mismatch")
    h.cancel_job("PlayerA", jid); h.actions_active = False
    return {"job": jid, "before": before, "blueprint": ref, "rotation": rotation, "origin": origin,
            "end": end, "preflight": preflight, "graph": graph, "status": status,
            "fullBill": q, "alreadyCorrect": already, "outstanding": outstanding,
            "stocked": stocked, "newlyDeposited": deposited, "crafted": produced,
            "clientMenuCount": actual, "modelCallsBefore": calls, "modelCallsAfter": h.model_calls()}


def current_effect_boundary(h, rid, stage=None, *, require_no_craft=False):
    def boundary():
        v = h.run_view("PlayerA", rid)
        if not v:
            return None
        if v.get("phase") == "TERMINAL":
            raise Inconclusive("Task ended before the required dynamic boundary: "+json.dumps(v))
        if v.get("phase") != "EXECUTING" or stage and not receipts(v, stage):
            return None
        if require_no_craft and receipts(v, "CRAFT"):
            raise Inconclusive("Craft committed before the required competing-player boundary")
        return v
    return h.wait(boundary, "Witness the original live execution boundary", 80)


def external_break(h, *, table=False, gate=False):
    if table:
        h.reset({PLANK: 3, STICK: 2}, actor=at(3, 4))
        target = FACILITY
        h.console(["tp PlayerB "+coord([target[0]+1.5, 208, target[2]+1.5]),
                   "item replace entity PlayerB weapon.mainhand with minecraft:iron_axe"])
        rid = h.submit(AXE, 1); boundary = current_effect_boundary(h, rid, "WITHDRAW", require_no_craft=True)
    else:
        target = at(10, 3)
        h.reset({}, table=False, actor=at(3, 3)); h.logs([target])
        if gate:
            h.console(["fill 72 208 63 72 210 78 minecraft:oak_fence",
                       "setblock 72 208 67 minecraft:oak_fence_gate[facing=east,open=true]",
                       "setblock 72 209 67 minecraft:air", "setblock 72 210 67 minecraft:air"])
            target_gate = at(8, 3)
            h.console(["tp PlayerB 72.5 208 68.5"])
        else:
            h.console(["tp PlayerB "+coord([target[0]+1.5, 208, target[2]+1.5]),
                       "item replace entity PlayerB weapon.mainhand with minecraft:iron_axe"])
        rid = h.submit(LOG, 1, minimum=target, maximum=target)
        boundary = current_effect_boundary(h, rid)
    if gate:
        h.action("PlayerB", "use", pos=target_gate)
        h.wait(lambda: "open=false" in h.block(target_gate), "Ordinary player closes the actual route gate", 20)
    else:
        h.action("PlayerB", "break", pos=target)
        h.wait(lambda: "minecraft:air" in h.block(target), "Ordinary player physically breaks the selected block", 25)
    mutation = h.snapshot(); final = h.finish_run(rid, success=False)
    if table:
        require(receipts(final, "CRAFT", AXE) == 0 and receipts(final, "DEPOSIT", AXE) == 0 and
                final.get("marker", {}).get("outcome") != "SUCCEEDED", "Crafting used a destroyed facility")
    elif not gate:
        require(receipts(final, "HARVEST", LOG) == 0 and receipts(final, "DEPOSIT", LOG) == 0 and
                final.get("marker", {}).get("outcome") != "SUCCEEDED", "Another player's break was credited to the villager")
    else:
        succeeded = final.get("marker", {}).get("outcome") == "SUCCEEDED"
        require((receipts(final, "DEPOSIT", LOG) == 1 and h.container(DST, LOG) == 1) if succeeded
                else receipts(final, "DEPOSIT", LOG) < 1, "Closed gate produced fictitious completion")
    return {"run": rid, "boundaryBeforePlayerAction": boundary, "mutationObserved": mutation,
            "view": final, "playerMutation": "gate-close" if gate else "ordinary-block-break",
            "target": target, "actualOutcome": final.get("marker", {}).get("outcome")}


def external_inventory(h, fill=False):
    h.reset({LOG: 2}, actor=at(3, 4), table=False)
    target = DST if fill else SRC
    if fill:
        h.stock_fixture({"minecraft:cobblestone": 64*26}, DST,
               slots=[(i, "minecraft:cobblestone", 64) for i in range(26)])
        h.console(["give PlayerB minecraft:oak_planks 64"])
    h.console(["tp PlayerB "+coord([target[0]+1.5, 208, target[2]+1.5])])
    h.action("PlayerB", "open", pos=target)
    menu = h.wait(lambda: (s if (s := h.client_snapshot("PlayerB"))["menu"] > 0 else None), "Competing player's real inventory menu", 20)
    physical_before = {item: physical_item_total(h, item) for item in (LOG, PLANK)}
    rid = h.submit(PLANK, 7)
    boundary = current_effect_boundary(h, rid, "WITHDRAW")
    require(receipts(boundary, "WITHDRAW", LOG) >= 1, "No witnessed first batch after input inspection")
    candidates = [s for s in menu["slots"] if s["playerInventory"] == fill and s["item"] == (PLANK if fill else LOG)]
    require(candidates, "Competing player's expected movable stack was not observed")
    for s in candidates:
        h.action("PlayerB", "menu-click", slot=s["slot"], quick=True)
    h.wait(lambda: h.container(target, PLANK) == 64 if fill else h.container(target, LOG) == 0,
           "Actual connected-player inventory mutation", 20)
    if not fill:
        moved = sum(s["count"] for s in h.client_snapshot("PlayerB")["slots"]
                    if s["playerInventory"] and s["item"] == LOG)
        if moved < 1:
            raise Inconclusive("The remaining input was withdrawn before PlayerB actually removed it")
    h.action("PlayerB", "close-menu"); mutation = h.snapshot()
    final = h.finish_run(rid, success=False)
    require(final.get("marker", {}).get("outcome") != "SUCCEEDED" and receipts(final, "DEPOSIT", PLANK) < 7,
            "Stale or full stock produced fictitious full completion")
    produced = receipts(final, "CRAFT", PLANK)
    actual = h.container(DST, PLANK)+h.inventory().get(PLANK, 0)
    h.quiet(5)
    physical_after = {item: physical_item_total(h, item) for item in (LOG, PLANK)}
    require(physical_after[LOG] == physical_before[LOG]-produced//4 and
            physical_after[PLANK] == physical_before[PLANK]+produced,
            "Competing-player mutation lost or duplicated actual resource units")
    data = {"run": rid, "boundary": boundary, "mutationObserved": mutation, "view": final,
            "actualContainers": h.snapshot()["containers"], "actors": h.snapshot()["actors"],
            "produced": produced, "destinationAndCustody": actual,
            "inspectionBoundaryWitnessed": True, "physicalTotalsBefore": physical_before,
            "physicalTotalsAfter": physical_after, "mutation": "destination-filled" if fill else "source-removed"}
    return data


def irrigation(h):
    h.reset({}, table=False, actor=at(5, 7))
    target = at(12, 7); h.logs([target])
    h.console(["fill 72 207 68 72 207 74 minecraft:water"])
    data = acquisition(h, 1, [target], reset=False)
    data["channel"] = {"x": 72, "zMin": 68, "zMax": 74, "alternativeSupportedEnds": [67, 75]}
    return data


def compete(h):
    h.reset({LOG: 3}, table=False); before = h.snapshot(); calls = h.model_calls()
    runs = [h.submit(PLANK, 7, player=p) for p in ("PlayerA", "PlayerB")]
    views = [h.finish_run(rid, p, success=False) for p, rid in zip(("PlayerA", "PlayerB"), runs)]
    produced = sum(receipts(v, "CRAFT", PLANK) for v in views)
    withdrawn = sum(receipts(v, "WITHDRAW", LOG) for v in views)
    completed = sum(v.get("marker", {}).get("outcome") == "SUCCEEDED" for v in views)
    initial_custody = sum(a["inventory"].get(PLANK, 0) for a in before["actors"])
    final_custody = sum(a["inventory"].get(PLANK, 0) for a in h.snapshot()["actors"])
    require(completed <= 1 and withdrawn <= 3 and produced == 4*withdrawn,
            "Insufficient competing stock was double allocated or fabricated")
    require(h.container(SRC, LOG) == 3-withdrawn and h.container(DST, PLANK)+final_custody-initial_custody == produced,
            "Competing owners lost or duplicated physical resources")
    require(h.model_calls() == calls, "Known competing execution invoked a model")
    return {"before": before, "runs": runs, "views": views, "physicalAfter": h.snapshot(),
            "produced": produced, "withdrawn": withdrawn, "fullyCompletedRequests": completed}


def offline_reuse(h):
    cold = read(h.root / "server/process.json")
    h.env["COGNITIVECRAFT_NEEDLE_DIR"] = ""; h.env["COGNITIVECRAFT_OLLAMA_MODEL"] = ""
    h.stop("server"); h.start("server"); reconnect_players(h, ["PlayerA", "PlayerB"])
    warm = read(h.root / "server/process.json")
    require(warm["start"] > cold["start"] and warm["releaseSha256"] == cold["releaseSha256"],
            "Offline test did not reopen the unchanged release in a new JVM")
    data = acquisition(h, 3, [at(x, 10) for x in (13, 15, 17)], destination=at(18, 7), player="PlayerB")
    data["processProof"] = {"cold": cold, "warm": warm, "generatorStopped": True, "interpreterConfigured": False}
    h.env["COGNITIVECRAFT_NEEDLE_DIR"] = "/evidence/needle"
    h.stop("server"); h.start("server"); reconnect_players(h, ["PlayerA", "PlayerB"])
    return data


def crash(h):
    h.reset({LOG: 2}, destination=at(18, 12), table=False, actor=at(9, 5))
    rid = h.submit(PLANK, 7, destination=at(18, 12))
    boundary = current_effect_boundary(h, rid, "CRAFT")
    if receipts(boundary, "DEPOSIT", PLANK):
        raise Inconclusive("The requested crash boundary already includes a delivery")
    h.console(["tick freeze"], lifecycle=True)
    saved = h.snapshot(); checkpoint = h.save_checkpoint()
    h.stop("server", hard=True); h.actions_active = False
    killed = h.world_hashes(); h.start("server")
    h.console(["tick unfreeze", "tick rate 20"], lifecycle=True); reconnect_players(h, ["PlayerA", "PlayerB"])
    recovered = h.wait(lambda: h.run_view("PlayerA", rid), "Actual interrupted saved craft after SIGKILL", 30)
    require("INTERRUPTED" in recovered["description"], "Uncertain craft resumed after crash")
    require(h.snapshot()["containers"] == saved["containers"] and
            {a["uuid"]: a["inventory"] for a in h.snapshot()["actors"]} == {a["uuid"]: a["inventory"] for a in saved["actors"]},
            "Acknowledged saved custody/stock changed at recovery")
    h.quiet(80)
    require(h.snapshot()["containers"] == saved["containers"] and
            {a["uuid"]: a["inventory"] for a in h.snapshot()["actors"]} == {a["uuid"]: a["inventory"] for a in saved["actors"]},
            "Restart duplicated crafting or delivery")
    return {"run": rid, "killBoundary": boundary, "savedPhysicalState": saved,
            "checkpoint": checkpoint, "killedWorldHashes": killed, "recovered": recovered,
            "afterQuiet": h.snapshot()}


def moved_containers(h):
    first = h.verify_recipe(PLANK, 7, {LOG: 2}, source=at(4, 11), destination=at(17, 3))
    second = h.verify_recipe(PLANK, 11, {LOG: 3}, source=at(16, 11), destination=at(5, 8), source_kind="barrel")
    require(first["view"]["marker"]["artifactSha256"] == second["view"]["marker"]["artifactSha256"],
            "Container relocation changed the same admitted method")
    return {"firstRequest": first, "secondRequest": second}


def language(h, text, expected_quantity=None, clarify=False, boundary=False, boundary_id=None):
    if expected_quantity == 11:
        h.reset({BIRCH: 3, LOG: 2})
    else:
        stock = {LOG: 2, PLANK: 12, STICK: 8, TABLE: 1, BIRCH_PLANK: 4}
        stock.update({"minecraft:spruce_log": 3} if boundary_id == "B02" else
                     {"minecraft:chest": 1} if boundary_id == "B07" else {})
        h.reset(stock, source_kind="barrel" if "barrel" in text.lower() else "chest")
    if boundary_id == "B01":
        h.logs([at(x, 3) for x in (6, 8, 10)], "minecraft:spruce_log")
    if boundary_id == "B09":
        h.console([command for x in (6, 7, 8) for command in
                   ("setblock "+coord(at(x, 3, -1))+" minecraft:farmland[moisture=7]",
                    "setblock "+coord(at(x, 3))+" minecraft:carrots[age=7]")])
    wall_ref = None
    if boundary_id == "B11":
        wall_ref = store_blueprint(h, {"schema": 1, "dimensions": {"x": 3, "y": 3, "z": 1},
                                     "constraints": {"supportedGround": True, "nonHazardous": True, "perimeter": 0},
                                     "palette": [{"block": PLANK, "properties": {}}],
                                     "cells": [{"x": x, "y": y, "z": 0, "palette": 0} for x in range(3) for y in range(3)]})
        text = "Ada, construct a three by three oak-plank wall from blueprint "+wall_ref+" at 79 208 75."
    before = h.snapshot(); calls = h.model_calls()
    h.command("PlayerA", "/aivillage kernel inference true", "admission=true")
    answer = h.command("PlayerA", "/aivillage kernel ask "+text, "interpretation="+UUID_PATTERN)
    ticket = re.search("interpretation=("+UUID_PATTERN+")", answer).group(1)
    interpreted = h.wait(lambda: (v if (v := h.run_view("PlayerA", ticket)) and
                                 v.get("phase") not in ("INTERPRETING", "RESOLVING") else None),
                         "Unmodified language interpreter on exact held-out wording", 130)
    submitted = interpreted.get("phase") == "SUBMITTED"
    data = {"text": text, "ticket": ticket, "interpreted": interpreted, "before": before,
            "modelCallsBefore": calls, "modelCallsAfter": h.model_calls(),
            "interpretationCorrect": False, "physicalGameplaySuccess": False}
    if clarify or boundary:
        require(not submitted, "Ambiguous or unsupported wording silently submitted gameplay: "+json.dumps(interpreted))
        h.quiet(20)
        require(h.snapshot()["containers"] == before["containers"] and
                h.snapshot()["blocks"] == before["blocks"], "Refused wording produced physical effects")
        data["interpretationCorrect"] = True
        if boundary:
            vanilla = h.snapshot().get("assessmentVanillaRecipes", {}).get(boundary_id)
            if boundary_id in ("B02", "B05", "B08"):
                require(vanilla and vanilla["installedVanillaMatch"], "Installed vanilla support was not demonstrated")
            data["vanillaRecipe"] = vanilla
            data["blueprint"] = wall_ref
            data.update(assessmentClassification="UNSUPPORTED", boundaryCheck="PASS", gameplayExecution="NOT_EXECUTED")
    else:
        require(submitted, "Semantically supported wording was not interpreted: "+json.dumps(interpreted))
        rid = interpreted["runId"]; job = h.job_view("PlayerA", rid)
        if job:
            amount = job["request"]["request"]["arguments"]["amount"]["value"]
            require(amount == expected_quantity, "Interpretation silently changed requested output quantity")
            graph = h.complete_job(rid); data["graph"] = graph
            item = AXE if expected_quantity == 3 else BIRCH_PLANK
            require(graph_receipts(graph, "DEPOSIT", item) == expected_quantity, "Interpreted Job lacks exact physical output")
        else:
            view = h.finish_run(rid); data["view"] = view
            item = BIRCH_PLANK if expected_quantity == 11 else PLANK
            require(receipts(view, "DEPOSIT", item) == expected_quantity, "Interpreted execution lacks exact requested output")
        data.update(interpretationCorrect=True, physicalGameplaySuccess=True)
    h.command("PlayerA", "/aivillage kernel inference false", "admission=false")
    data["physicalAfter"] = h.snapshot()
    return data


def recipe_boundary(h, item, stock, scenario):
    h.reset(stock, table=item == AXE); before = h.snapshot(); calls = h.model_calls()
    rid = h.submit(item, 1 if item in (TABLE, AXE) else 4)
    view = h.finish_run(rid, success=False)
    require(view.get("marker", {}).get("outcome") != "SUCCEEDED" and not view.get("resourceReceipts"),
            "Unsupported recipe variant consumed or credited resources")
    require(h.snapshot()["containers"] == before["containers"] and
            {a["uuid"]: a["inventory"] for a in h.snapshot()["actors"]} == {a["uuid"]: a["inventory"] for a in before["actors"]},
            "Unsupported resource restriction produced physical mutations")
    vanilla = h.snapshot().get("assessmentVanillaRecipes", {}).get(scenario)
    require(vanilla and vanilla["installedVanillaMatch"], "Installed vanilla recipe support was not established")
    require(h.model_calls() == calls, "Unsupported stock triggered hidden inference")
    return {"assessmentClassification": "UNSUPPORTED", "boundaryCheck": "PASS", "gameplayExecution": "NOT_EXECUTED",
            "classification": "Recipe or resource restriction", "before": before, "run": rid, "view": view,
            "vanillaRecipe": vanilla, "physicalAfter": h.snapshot()}


def mixed_blueprint(h):
    h.reset({PLANK: 8}, table=False)
    body = {"schema": 1, "dimensions": {"x": 3, "y": 2, "z": 1},
            "constraints": {"supportedGround": True, "nonHazardous": True, "perimeter": 0},
            "palette": [{"block": PLANK, "properties": {}},
                        {"block": "minecraft:oak_slab", "properties": {"type": "bottom"}},
                        {"block": "minecraft:oak_door", "properties": {"half": "lower", "facing": "north", "hinge": "left"}},
                        {"block": "minecraft:oak_door", "properties": {"half": "upper", "facing": "north", "hinge": "left"}}],
            "cells": [{"x": 0, "y": 0, "z": 0, "palette": 0}, {"x": 1, "y": 0, "z": 0, "palette": 1},
                      {"x": 2, "y": 0, "z": 0, "palette": 2}, {"x": 2, "y": 1, "z": 0, "palette": 3}]}
    ref = store_blueprint(h, body); before = h.snapshot(); origin = at(15, 11); end = at(17, 11, 1)
    answer = h.command("PlayerA", "/aivillage kernel blueprint-materials stage "+h.citizens["PlayerA"]+" "+ref+" 0 "+coord(origin+origin+end+SRC+SRC+LO+HI), "mode=STAGE_MATERIALS job=")
    require("accepted=false" in answer and "missing-producer-and-staging-support" in answer,
            "Unsupported material bill was accepted or misclassified: "+answer)
    require(h.snapshot()["containers"] == before["containers"] and
            set(h.snapshot()["knownPlayers"]["PlayerA"]["jobs"]) == set(before["knownPlayers"]["PlayerA"]["jobs"]),
            "Unsupported staging created work or consumed stock")
    return {"assessmentClassification": "UNSUPPORTED", "boundaryCheck": "PASS", "gameplayExecution": "NOT_EXECUTED",
            "classification": "Missing capability", "blueprint": ref, "body": body, "answer": answer, "before": before,
            "physicalAfter": h.snapshot()}


def cases(h, ident, row):
    if ident == "A01": return acquisition(h, 3, [at(9, 3, dy) for dy in range(3)])
    if ident == "A02": return acquisition(h, 7, [at(x, z) for x, z in [(6, 2), (8, 2), (10, 2), (6, 4), (8, 4), (10, 4), (9, 5)]], container="barrel")
    if ident == "A03": return h.verify_recipe(PLANK, 7, {LOG: 2})
    if ident == "A04": return h.verify_recipe(PLANK, 12, {}, slots=[(1, LOG, 1), (13, LOG, 1), (26, LOG, 1), (4, BIRCH, 2), (19, BIRCH_PLANK, 5)])
    if ident == "A05": return h.verify_recipe(STICK, 5, {PLANK: 4})
    if ident == "A06": return h.verify_recipe(STICK, 12, {PLANK: 6}, source_kind="barrel")
    if ident == "A07": return h.verify_recipe(AXE, 2, {PLANK: 6, STICK: 4})
    if ident == "A08": return h.verify_recipe(AXE, 4, {PLANK: 12, STICK: 8}, source=at(5, 11), destination=at(17, 3))
    if ident == "A09": return verify_axes_graph(h, 2, {PLANK: 4, STICK: 2, LOG: 1}, (0, 0, 2))
    if ident == "A10": return verify_axes_graph(h, 3, {PLANK: 7, LOG: 2}, (0, 2, 2))
    if ident == "A11": return h.verify_recipe(TABLE, 3, {PLANK: 12})
    if ident == "A12": return h.verify_recipe(TABLE, 1, {PLANK: 4}, source_kind="barrel")
    if ident == "A13": return place_table(h)
    if ident == "A14": return provision(h)
    if ident == "A15": return acquisition(h, 5, [at(x, 3) for x in (6, 7, 9, 11, 13)], item=BIRCH, extra_oak=[at(8, 3), at(12, 3)])
    if ident == "A16": return h.verify_recipe(BIRCH_PLANK, 11, {BIRCH: 3, LOG: 2})
    if ident == "A17": return blueprint(h, 7, 2, rotation=1)
    if ident == "A18": return blueprint(h, 5, 1, already=2)
    if ident == "C01": return external_break(h)
    if ident == "C02": return external_inventory(h)
    if ident == "C03": return external_inventory(h, fill=True)
    if ident == "C04": return external_break(h, table=True)
    if ident == "C05": return external_break(h, gate=True)
    if ident == "C06": return irrigation(h)
    if ident == "C07": return compete(h)
    if ident == "C08": return offline_reuse(h)
    if ident == "C09": return crash(h)
    if ident == "C10": return moved_containers(h)
    if ident.startswith("D"):
        return language(h, row["requirement"], expected_quantity={"D03": 3, "D04": 11}.get(ident), clarify=ident in ("D01", "D02", "D05"))
    if ident == "B03": return recipe_boundary(h, STICK, {BIRCH_PLANK: 4}, ident)
    if ident == "B04": return recipe_boundary(h, AXE, {BIRCH_PLANK: 3, STICK: 2}, ident)
    if ident == "B06": return recipe_boundary(h, TABLE, {PLANK: 2, BIRCH_PLANK: 2}, ident)
    if ident == "B12": return recipe_boundary(h, PLANK, {"minecraft:stripped_oak_log": 1}, ident)
    if ident == "B10": return mixed_blueprint(h)
    unsupported = {"B01": "Ada, gather three spruce logs and deliver them to the nearest chest.",
                   "B02": "Ada, make four spruce planks from spruce logs and store them in the nearest chest.",
                   "B05": "Ada, craft one wooden pickaxe using oak planks and sticks.",
                   "B07": "Ada, place the chest item from the nearest barrel at 81 208 75.",
                   "B08": "Ada, make three oak fences from planks and sticks.",
                   "B09": "Ada, harvest three carrots and deliver them to the nearest chest.",
                   "B11": "Ada, construct a three by three oak-plank wall from the blueprint at 79 208 75."}
    if ident in unsupported: return language(h, unsupported[ident], boundary=True, boundary_id=ident)
    raise AssertionError("Frozen scenario has no assessment driver: "+ident)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--group", choices=("oak", "craft", "facility", "birch", "blueprint"), required=True)
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args(); corpus = read(ROOT / "e2e/generalization/cases.json")
    selected = [r for r in corpus["scenarios"] if r["group"] == args.group]
    result = {"schema": 1, "group": args.group, "productionBaseline": corpus["productionBaseline"],
              "measurementStatus": "INCOMPLETE", "gameplayAcceptance": "NOT_INFERRED_FROM_RECORD_COMPLETENESS",
              "scenarios": [{**r, "status": "NOT_EXECUTED", "reason": "Not reached"} for r in selected]}
    args.root.mkdir(parents=True, exist_ok=True); h = None
    atomic(args.root / "generalization-results.json", result)
    items = {"oak": [LOG], "craft": [LOG, PLANK, STICK, AXE],
             "facility": [TABLE, "place", AXE], "birch": [BIRCH, BIRCH_PLANK], "blueprint": [LOG, PLANK]}
    try:
        h = AssessmentHarness(args.root, args.group); h.bootstrap(items[args.group])
        for index, row in enumerate(selected):
            ident = row["id"]; h.family = ident; started = time.time(); captures = len(h.screenshots)
            record = {**row, "started": started, "status": "NOT_EXECUTED"}
            h.event("assessment-scenario-start", record)
            try:
                data = cases(h, ident, row)
                record.update(status=data.pop("assessmentClassification", "PASS"), evidence=data)
                if record["status"] == "INCONCLUSIVE": record["reason"] = data.get("reason")
                h.screenshot("PlayerA", "observed-result", chat=True)
            except Exception as error:
                status = "BLOCKED" if isinstance(error, (AssessmentBlocked, InfrastructureBlocked, TypeError, AttributeError)) else "INCONCLUSIVE" if isinstance(error, Inconclusive) else "FAIL"
                reason = str(error)
                classification = "Test infrastructure failure" if isinstance(error, (InfrastructureBlocked, TypeError, AttributeError)) else "Environmental limitation" if status == "BLOCKED" else "Dynamic boundary not witnessed" if status == "INCONCLUSIVE" else "Unclassified pending independent artifact review"
                record.update(status=status, reason=reason, classification=classification, trace=traceback.format_exc())
                try:
                    record["physicalFailureState"] = h.snapshot(); h.screenshot("PlayerA", "observed-failure", chat=True)
                except Exception as capture_error:
                    record["captureError"] = str(capture_error)
            record.update(durationSeconds=time.time()-started, screenshots=h.screenshots[captures:])
            result["scenarios"][index] = record
            atomic(args.root / "generalization-results.json", result)
            h.event("assessment-scenario-result", record)
            print(ident, record["status"], record.get("reason", ""), flush=True)
            try:
                h.cleanup()
            except Exception as error:
                result.setdefault("cleanupFailures", []).append({"after": ident, "reason": str(error)})
                raise
        result["measurementStatus"] = "COMPLETE"
    except Exception as error:
        result["infrastructureFailure"] = {"reason": str(error), "trace": traceback.format_exc()}
        print("ASSESSMENT_INFRASTRUCTURE_FAILURE", str(error), flush=True)
    finally:
        if h:
            result["independentAdmissions"] = h.admissions; result["admittedArtifacts"] = h.hashes
            result["metadata"] = read(args.root / "metadata.json")
            h.finish()
        result["counts"] = {s: sum(r["status"] == s for r in result["scenarios"])
                            for s in ("PASS", "FAIL", "UNSUPPORTED", "BLOCKED", "INCONCLUSIVE", "NOT_EXECUTED")}
        atomic(args.root / "generalization-results.json", result)
    # This job certifies original measurement completeness, never a gameplay pass rate.
    raise SystemExit(0 if result["measurementStatus"] == "COMPLETE" else 1)


if __name__ == "__main__":
    main()
