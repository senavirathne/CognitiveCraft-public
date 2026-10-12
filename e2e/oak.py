"""GP-RB-01 through the existing real-client harness; no mock players or canned candidates."""
from __future__ import annotations
import json
import re
import shutil
import urllib.request
from pathlib import Path

ITEM = "minecraft:oak_log"
SOURCE = [6, 201, 14, 13, 201, 14]
DESTINATION = [7, 201, 9]


def scenario(h):
    # Imported lazily so suite keeps its established owner and all old profile inventories.
    from suite import ROOT, UUID_PATTERN, DENIED_PATTERN, require, read, reconnect_players, completed_jsonl
    if h.topology != "compose":
        raise RuntimeError("RB genuine backend/network gate requires the existing compose profile")
    assets = ROOT / "build/needle"
    require((assets / "manifest.json").exists(), "Pinned Needle assets unavailable")
    private = h.root / "needle"
    shutil.copytree(assets, private, dirs_exist_ok=True)
    h.env["COGNITIVECRAFT_OLLAMA_MODEL"] = "qwen3:4b-instruct-2507-q4_K_M"
    h.env["COGNITIVECRAFT_NEEDLE_DIR"] = "/evidence/needle"
    h.compose("up", "-d", "ollama")
    h.compose("exec", "-T", "ollama", "ollama", "pull", h.env["COGNITIVECRAFT_OLLAMA_MODEL"])

    def tags():
        try:
            return json.load(urllib.request.urlopen("http://127.0.0.1:11434/api/tags", timeout=5))
        except OSError:
            return None
    model = h.wait(tags, "RB configured generation endpoint", 60)
    require(any(x["name"] == h.env["COGNITIVECRAFT_OLLAMA_MODEL"] for x in model["models"]), "Wrong RB model")
    request = urllib.request.Request("http://127.0.0.1:11434/api/generate", data=json.dumps({
        "model": h.env["COGNITIVECRAFT_OLLAMA_MODEL"], "stream": False, "keep_alive": "30m"}).encode(),
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=120) as response:
        require(json.load(response)["done"], "RB model prewarm failed")
    h.fresh(two=True)
    h.enroll(); h.enroll("PlayerB", "Mira"); h.release_gate()
    h.console(["gamerule minecraft:block_drops true",
               'data merge block 7 201 9 {Items:[{Slot:0b,id:"minecraft:oak_log",count:11}]}',
               "fill 10 201 12 10 202 13 minecraft:stone",
               'summon minecraft:item 6.5 201.25 14.5 {Item:{id:"minecraft:oak_log",count:17}}'])
    h.wait(lambda: h.container(DESTINATION, ITEM) == 11, "RB contaminated container setup")
    unrelated = {d["uuid"] for d in h.snapshot()["drops"] if d["item"] == ITEM}
    require(unrelated, "RB actual unrelated drop witness missing")
    h.command("PlayerA", "/aivillage kernel inference true", "admission=true")

    def prepare(quantity, z=14):
        h.actions_active = False
        commands = [f"setblock {x} 201 {z} minecraft:air" for x in range(6, 14)]
        commands += [f"setblock {x} 201 {z} minecraft:oak_log" for x in range(6, 6+quantity)]
        h.console(commands)
        source = [6, 201, z, 13, 201, z]
        h.wait(lambda: all("minecraft:oak_log" in h.snapshot()["blocks"].get(f"{x},201,{z}", "")
                           for x in range(6, 6+quantity)), "Loaded exact oak setup")
        return source

    def submit(quantity, source, player="PlayerA"):
        text = f"/aivillage kernel gather-oak {h.citizens['PlayerA']} {quantity} " + " ".join(map(str, source+DESTINATION))
        answer = h.command(player, text, "run="+UUID_PATTERN if player=="PlayerA" else DENIED_PATTERN)
        if player != "PlayerA":
            return answer
        if "accepted=false reason=BUDGET_EXHAUSTED" in answer:
            def available():
                nonlocal answer
                answer = h.command(player, text, "run="+UUID_PATTERN)
                return answer if "accepted=false reason=BUDGET_EXHAUSTED" not in answer else None
            h.wait(available, "RB durable owner publication", 30)
        require("accepted=true" in answer, "RB canonical request rejected: "+answer)
        return re.search("run=("+UUID_PATTERN+")", answer).group(1)

    def verify(run, quantity, before, source, acquired=False):
        view = h.terminal("PlayerA", run, 650 if acquired else 180)
        h.event("rb-oak-terminal", {"run": run, "view": view})
        require(("ADMITTED" if acquired else "SUCCEEDED") in view["description"], "RB physical completion failed: "+view["description"])
        records = view.get("resourceReceipts", [])
        sums = {stage: sum(r["quantity"] for r in records if r["stage"] == stage)
                for stage in ("HARVEST", "PICKUP", "DEPOSIT")}
        require(sums == {stage:quantity for stage in sums}, "RB physical receipt conservation mismatch")
        require(not view.get("receipts"), "Oak was relabeled as crop evidence")
        require(len({r["receiptId"] for r in records}) == len(records), "RB duplicated evidence IDs")
        require(h.container(DESTINATION, ITEM) == before+quantity, "RB actual container delta mismatch")
        for r in records:
            require(r["item"] == {"id": ITEM, "components":"default"}, "Wrong resource/components")
            require(r["actor"]["entityId"] == h.entities["PlayerA"] and r["origin"]["principal"]["id"] == h.snapshot()["players"]["PlayerA"]["uuid"], "RB cross-principal evidence")
            require(not unrelated.intersection(r["dropIds"]), "Unrelated drops entered custody")
            require([r["destination"][k] for k in ("x","y","z")] == DESTINATION, "RB changed destination")
        require(view["responsibility"]["state"] == "SUCCEEDED", "Jobs did not own RB completion")
        trace=[row for row in completed_jsonl(h.root/"server/observations.jsonl")
               if run in row.get("players",{}).get("PlayerA",{}).get("runs",{})]
        positions=[tuple(actor["pos"]) for row in trace for actor in row.get("actors",[]) if actor["uuid"]==h.entities["PlayerA"]]
        require(len(set(positions))>1,"RB exact-run trace has no actual worker travel")
        require(run not in h.snapshot()["players"]["PlayerB"]["runs"], "RB private result leaked")
        h.command("PlayerB", "/aivillage kernel status "+run, DENIED_PATTERN)
        h.command("PlayerB", "/aivillage kernel cancel "+run, DENIED_PATTERN)
        h.command("PlayerA", "/aivillage kernel status "+run, "phase=TERMINAL")
        require(h.menu_count("PlayerA", DESTINATION, ITEM) == before+quantity, "RB real client menu did not converge")
        h.actions_active = False
        return {"run":run,"artifact":view["marker"]["artifactSha256"],"quantities":sums,
                "source":source,"destination":DESTINATION,"responsibility":view["responsibility"],"receipts":records,
                "workerPositions":positions}

    source = prepare(4)
    denial = submit(4, source, "PlayerB")
    before = h.container(DESTINATION, ITEM); baseline = h.model_calls(); h.actions_active = True
    acquisition = verify(submit(4, source), 4, before, source, acquired=True)
    require(h.model_calls()["generationCalls"] > baseline["generationCalls"], "No genuine RB generation occurred")
    source = prepare(4)
    before = h.container(DESTINATION, ITEM); calls = h.model_calls(); h.actions_active = True
    answer = h.command("PlayerA", "/aivillage kernel ask Ada, collect 4 oak logs and deliver them to the nearest suitable chest.", "interpretation="+UUID_PATTERN)
    ticket = re.search("interpretation=("+UUID_PATTERN+")", answer).group(1)
    interpreted = h.wait(lambda: (v if (v:=h.run_view("PlayerA",ticket)) and v["phase"] not in ("INTERPRETING","RESOLVING") else None), "Actual RB language interpretation", 40)
    require(interpreted["phase"] == "SUBMITTED", "Real RB interpreter failed: "+str(interpreted))
    language = verify(interpreted["runId"], 4, before, source)
    require(h.model_calls()["needleCalls"] > calls["needleCalls"], "Controlled extraction was substituted for real RB language")
    require(language["artifact"] == acquisition["artifact"], "Language did not reuse admitted method")
    source=prepare(1);before=h.container(DESTINATION,ITEM);known_calls=h.model_calls();h.actions_active=True
    known_one=verify(submit(1,source),1,before,source)
    require(h.model_calls()==known_calls and known_one["artifact"]==acquisition["artifact"],"Fresh quantity one called inference")
    h.compose("stop", "ollama")
    def unavailable():
        try:
            urllib.request.urlopen("http://127.0.0.1:11434/api/tags",timeout=2).close();return False
        except OSError:
            return True
    h.wait(unavailable, "Stopped RB generation backend", 20)
    h.env["COGNITIVECRAFT_OLLAMA_MODEL"] = ""; h.env["COGNITIVECRAFT_NEEDLE_DIR"] = ""
    h.stop("server"); (private/"needle").rename(private/"needle.offline")
    h.start("server"); reconnect_players(h,["PlayerA","PlayerB"])
    h.command("PlayerA", "/aivillage kernel inference false", "admission=false")
    source = prepare(7, 10); before = h.container(DESTINATION, ITEM); offline = h.model_calls(); h.actions_active = True
    reuse = verify(submit(7, source), 7, before, source)
    require(reuse["artifact"] == acquisition["artifact"] and h.model_calls() == offline, "Warm offline RB reuse called inference or changed artifact")
    source = prepare(1)
    h.console(["fill 5 201 13 7 203 15 minecraft:glass", "setblock 6 201 14 minecraft:oak_log"])
    before = h.container(DESTINATION, ITEM); h.actions_active = True
    unreachable_run = submit(1, source); unreachable = h.terminal("PlayerA", unreachable_run, 180)
    require("BLOCKED" in unreachable["description"] and not unreachable.get("resourceReceipts"), "Unreachable solid log did not fail closed")
    require(h.container(DESTINATION, ITEM) == before and h.model_calls() == offline, "Unreachable target caused stock credit or inference")
    h.actions_active = False; h.console(["fill 5 201 13 7 203 15 minecraft:air"])
    # The same save is reopened after a real abrupt server JVM termination. The owner
    # must interrupt the in-flight acquisition; stock is never converted into success.
    source = prepare(4); checkpoint = h.save_checkpoint(); h.actions_active = True
    interrupted_run = submit(4, source)
    boundary = h.wait(lambda: (v if (v:=h.run_view("PlayerA",interrupted_run)) and v["phase"] == "EXECUTING" else None), "RB actual in-flight kill boundary", 30)
    h.stop("server",hard=True); killed = h.world_hashes(); h.actions_active = False
    h.start("server"); reconnect_players(h,["PlayerA","PlayerB"])
    recovered = h.wait(lambda: h.run_view("PlayerA",interrupted_run), "RB preserved interruption record", 30)
    require("INTERRUPTED" in recovered["description"], "RB uncertain execution resumed")
    stable = h.snapshot()["containers"]
    h.quiet(80)
    require(h.snapshot()["containers"] == stable, "RB restart replayed a deposit")
    h.screenshot("PlayerA", "rb001-offline-and-interrupted", chat=True)
    return {"canonicalAcquisition":acquisition,"actualLanguage":language,"knownOne":known_one,"offlineWarmReuse":reuse,"foreignDenied":denial,
            "models":model,"needle":read(private/"manifest.json"),"killBoundary":boundary,"checkpoint":checkpoint,
            "unreachable":unreachable,"killedWorldHashes":killed,"recovered":recovered,"serverAuthenticationEnvelope":"two real server-issued connection principals"}
