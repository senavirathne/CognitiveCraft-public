"""GP-RB-04 uses the established real network clients, backends and same-save lifecycle."""
from __future__ import annotations
import hashlib
import json
import re
import shutil
import urllib.request

LOG = "minecraft:oak_planks"
PLANK = "minecraft:wooden_axe"
SOURCE = [5, 201, 5]
SECOND_SOURCE = [15, 201, 9]
DESTINATION = [9, 201, 7]
SECOND_DESTINATION = [17, 201, 7]
STICK = "minecraft:stick"
TABLE = [7, 201, 9]
SECOND_TABLE = [15, 201, 11]


def scenario(h, *, d03_evidence=False):
    from suite import ROOT, UUID_PATTERN, DENIED_PATTERN, require, read, reconnect_players, completed_jsonl
    require(h.topology == "compose", "RB production requires the actual configured backend and network topology")
    private = h.root / "needle"
    shutil.copytree(ROOT / "build/needle", private, dirs_exist_ok=True)
    h.env["COGNITIVECRAFT_OLLAMA_MODEL"] = "qwen3:4b-instruct-2507-q4_K_M"
    h.env["COGNITIVECRAFT_NEEDLE_DIR"] = "/evidence/needle"
    h.compose("up", "-d", "ollama")
    h.compose("exec", "-T", "ollama", "ollama", "pull", h.env["COGNITIVECRAFT_OLLAMA_MODEL"])

    def tags():
        try:
            return json.load(urllib.request.urlopen("http://127.0.0.1:11434/api/tags", timeout=5))
        except OSError:
            return None
    models = h.wait(tags, "RB-004 genuine configured model", 60)
    require(any(x["name"] == h.env["COGNITIVECRAFT_OLLAMA_MODEL"] for x in models["models"]), "Wrong RB-004 model")
    request = urllib.request.Request("http://127.0.0.1:11434/api/generate", data=json.dumps({
        "model": h.env["COGNITIVECRAFT_OLLAMA_MODEL"], "stream": False, "keep_alive": "30m"}).encode(),
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=120) as response:
        require(json.load(response)["done"], "RB-004 model prewarm failed")
    h.fresh(two=True); h.enroll(); h.enroll("PlayerB", "Mira"); h.release_gate(); h.release_gate("PlayerB")
    require("axeCatalog=[]" in h.command("PlayerA", "/aivillage kernel catalog", "catalog="), "Production was seeded as known")
    require("oakCatalog=[]" in h.command("PlayerA", "/aivillage kernel catalog", "catalog="), "RB-001 was seeded as a dependency")
    require("plankCatalog=[]" in h.command("PlayerA", "/aivillage kernel catalog", "catalog="), "RB-002 was seeded as a dependency")
    if d03_evidence:
        h.console(["setblock 9 201 7 minecraft:barrel"])
    h.console(["setblock " + " ".join(map(str, SOURCE)) + " minecraft:chest",
               "setblock " + " ".join(map(str, SECOND_SOURCE)) + " minecraft:chest",
               "setblock " + " ".join(map(str, TABLE)) + " minecraft:crafting_table",
               "setblock " + " ".join(map(str, SECOND_TABLE)) + " minecraft:crafting_table",
               "setblock 8 200 7 minecraft:stone", "setblock 8 200 11 minecraft:stone",
               'data merge block 9 201 7 {Items:[{Slot:0b,id:"minecraft:wooden_axe",count:1}]}',
               'data merge block 17 201 7 {Items:[{Slot:0b,id:"minecraft:wooden_axe",count:1}]}',
               f'data merge entity {h.entities["PlayerA"]} {{Inventory:[{{id:"minecraft:oak_planks",count:3}},{{id:"minecraft:wooden_axe",count:1}}]}}'])
    h.wait(lambda: h.container(DESTINATION, PLANK) == 1 and actor_inventory("PlayerA", h).get(PLANK) == 1,
           "RB-004 old stock fixture")
    h.command("PlayerA", "/aivillage kernel inference true", "admission=true")

    def prepare(quantity, source=SOURCE):
        h.actions_active = False
        batches = quantity; inputs = batches*3; old = h.container(source, PLANK)
        slots = [{"Slot": 0, "id": LOG, "count": inputs}, {"Slot": 1, "id": STICK, "count": batches*2}]
        while old:
            n = min(old, 1); slots.append({"Slot": len(slots), "id": PLANK, "count": n}); old -= n
        nbt = "{Items:[" + ",".join('{Slot:'+str(s['Slot'])+'b,id:"'+s['id']+'",count:'+str(s['count'])+'}' for s in slots) + "]}"
        h.console(["data merge block " + " ".join(map(str, source)) + " " + nbt])
        h.wait(lambda: h.container(source, LOG) == inputs and h.container(source, STICK) == batches*2, "Real supplied input stock")
        return list(source)

    def submit(quantity, source, destination=DESTINATION, player="PlayerA", actor_player=None):
        table = SECOND_TABLE if player == "PlayerB" else TABLE
        citizen = h.citizens[actor_player or player]
        text = f"/aivillage kernel make-axes {citizen} {quantity} " + " ".join(map(str, source+destination+table))
        answer = h.command(player, text, "run="+UUID_PATTERN if actor_player is None else DENIED_PATTERN+"|unavailable in your control scope")
        if actor_player is not None:
            return answer
        if "accepted=false reason=BUDGET_EXHAUSTED" in answer:
            def available():
                nonlocal answer
                answer = h.command(player, text, "run="+UUID_PATTERN)
                return answer if "accepted=false reason=BUDGET_EXHAUSTED" not in answer else None
            h.wait(available, "Durable production owner publication", 30)
        require("accepted=true" in answer, "Canonical production rejected: "+answer)
        return re.search("run=("+UUID_PATTERN+")", answer).group(1)

    def verify(run, quantity, before, inventory, source, destination=DESTINATION, player="PlayerA", acquired=False):
        view = h.terminal(player, run, 650 if acquired else 180)
        h.event("rb-axes-terminal", {"run": run, "view": view})
        require(view.get("marker", {}).get("outcome") == ("ADMITTED" if acquired else "SUCCEEDED"),
                "Actual production failed: "+view["description"])
        rows = view.get("resourceReceipts", []); batches = quantity
        sums = {stage:sum(r["quantity"] for r in rows if r["stage"] == stage) for stage in ("WITHDRAW", "CRAFT", "DEPOSIT")}
        require(sums == {"WITHDRAW":batches*5, "CRAFT":batches, "DEPOSIT":quantity} and len(rows) == batches*4,
                "Typed transformation conservation mismatch")
        require(not view.get("receipts") and all(r.get("transformation") and not r["dropIds"] for r in rows), "Transformation became crop/drop evidence")
        require(h.container(source, LOG) == 0 and h.container(source, STICK) == 0 and h.container(destination, PLANK) == before+quantity, "Actual source/destination delta mismatch")
        now = actor_inventory(player, h)
        require(now.get(LOG, 0) == inventory.get(LOG, 0) and now.get(PLANK, 0) == inventory.get(PLANK, 0),
                "Actual old stock or retained surplus was lost")
        for r in rows:
            require(r["actor"]["entityId"] == h.entities[player] and r["origin"]["principal"]["id"] == h.snapshot()["players"][player]["uuid"], "Cross-principal output credit")
            require(r["item"]["components"] == "default" and (r["item"]["id"] in (LOG, STICK) if r["stage"] == "WITHDRAW" else r["item"]["id"] == PLANK), "Wrong exact components")
            t = r["transformation"]; require(t["recipe"]["key"] == "minecraft:wooden_axe" and t["recipe"]["inputs"] == 5 and t["recipe"]["outputs"] == 1 and t["remainders"] == 0, "Wrong installed recipe evidence")
        job = view["responsibility"]
        require(job["state"] == "SUCCEEDED" and not job["dependencies"] and not job["children"], "Independent production gained an acquisition/consumer Job")
        other = "PlayerB" if player == "PlayerA" else "PlayerA"
        require(run not in h.snapshot()["players"][other]["runs"], "Private production leaked")
        h.command(other, "/aivillage kernel status "+run, DENIED_PATTERN); h.command(other, "/aivillage kernel cancel "+run, DENIED_PATTERN)
        if acquired:
            # A citizen may push the idle viewer back into the open pen during
            # genuine inference. Leave through its actual gate using client input.
            position = h.client_snapshot(player)["pos"]
            if position[2] < 4.75 and 1 <= position[0] <= 4:
                h.walk(player, [2, 201, 2], reach=.5)
                h.walk(player, [2, 201, 5], reach=.5)
            h.walk(player, [2, 201, 7], reach=.7)
            h.walk(player, [7, 201, 7], reach=.7)
        if destination == SECOND_DESTINATION:
            h.walk(player, [7, 201, 5], reach=.7); h.walk(player, [17, 201, 5], reach=.7)
        elif destination == SECOND_SOURCE:
            h.walk(player, [17, 201, 5], reach=.7); h.walk(player, [15, 201, 5], reach=.7)
            h.walk(player, [15, 201, 7], reach=.7)
        elif player == "PlayerB" and destination == DESTINATION:
            h.walk(player, [13, 201, 5], reach=.7); h.walk(player, [9, 201, 5], reach=.7)
        require(h.menu_count(player, destination, PLANK) == before+quantity, "Real client menu did not converge")
        trace = [a["pos"] for row in completed_jsonl(h.root/"server/observations.jsonl")
                 if run in row.get("knownPlayers", {}).get(player, {}).get("runs", {}) for a in row.get("actors", []) if a["uuid"] == h.entities[player]]
        h.actions_active = False
        return {"run":run,"artifact":view["marker"]["artifactSha256"],"source":source,"destination":destination,
                "actor":h.entities[player],"quantities":sums,"surplus":0,"table":SECOND_TABLE if player == "PlayerB" else TABLE,"receipts":rows,"responsibility":job,"workerPositions":trace}

    # A complete known shortage reports exact missing input before candidate work or effects.
    source = prepare(1); calls = h.model_calls(); stock = h.snapshot()["containers"]
    offset = len(h.events("PlayerA"))
    shortage = h.command("PlayerA", f"/aivillage kernel make-axes {h.citizens['PlayerA']} 4 " + " ".join(map(str, source+DESTINATION+TABLE)), "accepted=false reason=RESOURCE_MISSING")
    require(any("required=12 available=3 missing=9" in e["data"].get("text", "") for e in h.events("PlayerA")[offset:] if e["kind"] == "feedback"), "Exact missing input was not disclosed privately")
    require(h.model_calls() == calls and h.snapshot()["containers"] == stock, "Initial shortage generated or mutated")
    source = prepare(1); denial = submit(1, source, player="PlayerB", actor_player="PlayerA")
    before = h.container(DESTINATION, PLANK); inv = actor_inventory("PlayerA", h); calls = h.model_calls(); h.actions_active = True
    acquisition = verify(submit(1, source), 1, before, inv, source, acquired=True)
    require(h.model_calls()["generationCalls"] > calls["generationCalls"], "Genuine production admission was not measured")
    path = h.root/"server/game/world/data/cognitivecraft/skills/v1/bodies"/(acquisition["artifact"]+".json")
    body = read(path); descriptor = body["descriptor"]
    require([(p["name"],p["type"]) for p in descriptor["parameters"]] == [("actor","ACTOR"),("amount","INT"),("source","CONTAINER"),("destination","CONTAINER"),("table","FACILITY")], "Runtime production interface changed")
    require(not descriptor["dependencies"] and not body["ir"]["dependencies"] and body["spec"]["completion"] == "ATTRIBUTABLE_TRANSFORMATION_DELIVERY", "Stocked production depended on another method")
    typed = {"descriptor":descriptor,"spec":body["spec"],"bodySha256":hashlib.sha256(path.read_bytes()).hexdigest()}

    if d03_evidence:
        from d03 import language_evidence
        return language_evidence(h,prepare,verify,acquisition,typed,models,read(private/"manifest.json"))

    source = prepare(1); before = h.container(source, PLANK); inv = actor_inventory("PlayerA", h); calls = h.model_calls(); h.actions_active = True
    answer = h.command("PlayerA", "/aivillage kernel ask Ada, make one wooden axe using available materials and store it.", "interpretation="+UUID_PATTERN)
    ticket = re.search("interpretation=("+UUID_PATTERN+")", answer).group(1)
    interpreted = h.wait(lambda:(v if (v:=h.run_view("PlayerA",ticket)) and v["phase"] not in ("INTERPRETING","RESOLVING") else None), "Actual production language", 40)
    require(interpreted["phase"] == "SUBMITTED", "Real production language failed: "+str(interpreted))
    language = verify(interpreted["runId"], 1, before, inv, source, source)
    require(h.model_calls()["needleCalls"] > calls["needleCalls"] and language["artifact"] == acquisition["artifact"], "Real language did not reuse production")
    known = []
    for quantity, src, dst in [(2,SOURCE,DESTINATION),(3,SECOND_SOURCE,SECOND_DESTINATION),(4,SECOND_SOURCE,SECOND_SOURCE)]:
        source = prepare(quantity, src); before = h.container(dst, PLANK); inv = actor_inventory("PlayerA", h); calls = h.model_calls(); h.actions_active = True
        result = verify(submit(quantity, source, dst), quantity, before, inv, source, dst)
        require(result["artifact"] == acquisition["artifact"] and h.model_calls() == calls, "Known parameter binding invoked generation")
        known.append(result)

    h.compose("stop", "ollama")
    def unavailable():
        try:
            urllib.request.urlopen("http://127.0.0.1:11434/api/tags", timeout=2).close(); return False
        except OSError:
            return True
    h.wait(unavailable, "Actually stopped production backend", 20)
    old_process = read(h.root/"server/process.json")
    h.env["COGNITIVECRAFT_OLLAMA_MODEL"] = ""; h.env["COGNITIVECRAFT_NEEDLE_DIR"] = ""
    h.stop("server"); (private/"needle").rename(private/"needle.offline"); h.start("server"); reconnect_players(h,["PlayerA","PlayerB"])
    h.command("PlayerB", "/aivillage kernel inference false", "admission=false")
    new_process = read(h.root/"server/process.json")
    require(new_process["start"] > old_process["start"] and (new_process["pid"],new_process["start"]) != (old_process["pid"],old_process["start"])
            and new_process["pid"] == h.snapshot()["pid"] and unavailable(), "Offline production did not use a new JVM with stopped backend")
    process_proof = {"cold":{"pid":old_process["pid"],"start":old_process["start"]},
                     "warm":{"pid":new_process["pid"],"start":new_process["start"]},
                     "sameRelease":old_process["releaseSha256"] == new_process["releaseSha256"],"backendUnavailable":True}
    require(process_proof["sameRelease"],"Offline production changed the production binary")
    h.event("rb004-independent-process-proof", process_proof)
    source = prepare(4, SECOND_SOURCE); before = h.container(SECOND_DESTINATION, PLANK); inv = actor_inventory("PlayerB", h); offline = h.model_calls(); h.actions_active = True
    reuse = verify(submit(4, source, SECOND_DESTINATION, "PlayerB"), 4, before, inv, source, SECOND_DESTINATION, "PlayerB")
    require(reuse["artifact"] == acquisition["artifact"] and h.model_calls() == offline and reuse["actor"] != acquisition["actor"], "Offline fresh citizen did not reuse exact immutable production")

    source = prepare(4); checkpoint = h.save_checkpoint(); h.actions_active = True
    interrupted_run = submit(4, source, SECOND_DESTINATION)
    boundary = h.wait(lambda:(v if (v:=h.run_view("PlayerA",interrupted_run)) and v["phase"] == "EXECUTING"
                     and any(r["stage"] == "CRAFT" for r in v.get("resourceReceipts", [])) else None), "Actual crafted-custody kill boundary", 40)
    h.stop("server", hard=True); killed = h.world_hashes(); h.actions_active = False
    h.start("server"); reconnect_players(h,["PlayerA","PlayerB"])
    recovered = h.wait(lambda:h.run_view("PlayerA",interrupted_run), "Preserved uncertain production", 30)
    require("INTERRUPTED" in recovered["description"], "Uncertain production resumed")
    stable = h.snapshot()["containers"]; inventories = {p:actor_inventory(p,h) for p in ("PlayerA","PlayerB")}
    h.quiet(80)
    require(h.snapshot()["containers"] == stable and {p:actor_inventory(p,h) for p in inventories} == inventories, "Restart replayed a withdrawal, craft or deposit")
    h.screenshot("PlayerA", "rb004-offline-and-interrupted", chat=True)
    return {"canonicalProduction":acquisition,"actualLanguage":language,"knownBindings":known,"offlineWarmReuse":reuse,
            "standaloneReuse":[acquisition,*known,reuse],"foreignDenied":denial,"typedInterface":typed,"initialShortage":shortage,
            "models":models,"needle":read(private/"manifest.json"),"killBoundary":boundary,"checkpoint":checkpoint,
            "killedWorldHashes":killed,"recovered":recovered,"offlineProcessProof":process_proof,
            "serverAuthenticationEnvelope":"two real server-issued connection principals"}


def actor_inventory(player, h):
    return next(a["inventory"] for a in h.snapshot()["actors"] if a["uuid"] == h.entities[player]).copy()
