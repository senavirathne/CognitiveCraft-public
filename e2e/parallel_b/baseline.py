#!/usr/bin/env python3
"""B00: genuine admission, canonical craft and the original q=3/q=4 comparison.

Only fixture setup uses console writes. Work under measurement uses the real
connected player's public commands and the unchanged production runtime.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import time
import traceback
import urllib.request
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from suite import Harness, ROOT, UUID_PATTERN, atomic, require, read
from runtime import SDK, digest

LOG, PLANK, OTHER = "minecraft:birch_log", "minecraft:birch_planks", "minecraft:cobblestone"
SOURCE, DESTINATION = [5, 201, 5], [9, 201, 7]
LOW, HIGH = [6, 201, 14], [13, 201, 14]


def coord(p):
    return " ".join(map(str, p))


def inventory(h):
    return next(a["inventory"] for a in h.snapshot()["actors"] if a["uuid"] == h.entities["PlayerA"])


def physical(h):
    s = h.snapshot()
    return {"containers": s["containers"], "inventory": inventory(h),
            "blocks": {f"{x},201,14": s["blocks"].get(f"{x},201,14") for x in range(6, 14)}}


def seed(h):
    require(not h.actions_active, "No fixture writes while work is active")
    h.console(['data merge block ' + coord(SOURCE) +
               ' {Items:[{Slot:26b,id:"minecraft:birch_log",count:1},'
               '{Slot:0b,id:"minecraft:cobblestone",count:2}]}'])
    h.wait(lambda: h.container(SOURCE, LOG) == 1 and h.container(SOURCE, OTHER) == 2,
           "Server acknowledges exact ready stock")
    require(h.menu_count("PlayerA", SOURCE, LOG) == 1, "Client acknowledges exact ready stock")


def submit(h, command, kind):
    h.actions_active = True
    answer = h.command("PlayerA", command, kind + "=" + UUID_PATTERN)
    require("accepted=true" in answer, "Public request rejected: " + answer)
    return re.search(kind + "=(" + UUID_PATTERN + ")", answer).group(1), answer


def scenario(h):
    model = "qwen3:4b-instruct-2507-q4_K_M"
    h.env.update(COGNITIVECRAFT_OLLAMA_MODEL=model, COGNITIVECRAFT_E2E_SNAPSHOT_TICKS="1")
    h.compose("up", "-d", "ollama")
    h.compose("exec", "-T", "ollama", "ollama", "pull", model)
    req = urllib.request.Request("http://127.0.0.1:11434/api/generate",
            data=json.dumps({"model": model, "stream": False, "keep_alive": "30m"}).encode(),
            headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as response:
        require(json.load(response)["done"], "Configured generation backend prewarm")
    with urllib.request.urlopen("http://127.0.0.1:11434/api/tags", timeout=10) as response:
        atomic(h.root / "models.json", json.load(response))
    h.fresh(); h.enroll(); h.release_gate()
    # Neutral, acknowledged fixture. Ordinary villager AI is retained.
    cells = [(x,z) for x in (6,7) for z in (6,7,8,10,11,12)] + [(4,10)]
    h.console([c for x,z in cells for c in
               (f"setblock {x} 201 {z} minecraft:air", f"setblock {x} 200 {z} minecraft:stone")]
              + ["setblock 8 200 7 minecraft:stone", "setblock 8 200 11 minecraft:stone",
                 "setblock " + coord(SOURCE) + " minecraft:chest"]
              + [f"setblock {x} 201 14 minecraft:air" for x in range(6,14)])
    h.wait(lambda: all("minecraft:air" in h.snapshot()["blocks"].get(f"{x},201,{z}", "") for x,z in cells),
           "Neutral server fixture acknowledged")
    h.wait(lambda: all("minecraft:air" in h.client_snapshot("PlayerA")["blocks"].get(f"{x},201,{z}", "") for x,z in cells),
           "Neutral connected-client fixture acknowledged")
    # Pin actual recipe bytes from the verified installed binary as supplemental
    # identity. This is not a native RecipeManager match or durability certificate.
    with ZipFile(SDK / "minecraft-client.jar") as jar:
        recipes = {name: hashlib.sha256(jar.read(name)).hexdigest() for name in jar.namelist()
                   if name.startswith("data/minecraft/recipe/") and name.endswith(".json")}
    atomic(h.root / "installed-recipe-files.json", recipes)
    seed(h)
    h.command("PlayerA", "/aivillage kernel inference true", "admission=true")
    before = h.snapshot(); calls = h.model_calls(); stock_before = physical(h)
    command = "/aivillage kernel make-birch-planks " + h.citizens["PlayerA"] + " 4 " + coord(SOURCE+DESTINATION)
    rid, answer = submit(h, command, "run")
    view = h.terminal("PlayerA", rid, 650)
    require(view.get("marker", {}).get("outcome") == "ADMITTED", "Genuine canonical admission failed: " + view["description"])
    require(h.container(SOURCE, LOG) == 0 and h.container(SOURCE, OTHER) == 2
            and h.container(DESTINATION, PLANK) == stock_before["containers"].get(coord(DESTINATION).replace(" ", ","), {}).get(PLANK, 0) + 4,
            "Canonical admission physical input/output mismatch")
    artifact = view["marker"]["artifactSha256"]
    h.actions_active = False
    h.screenshot("PlayerA", "canonical-admitted-craft", chat=True)
    atomic(h.root / "canonical.json", {"command": command, "feedback": answer, "run": rid,
            "before": before, "after": h.snapshot(), "view": view, "callsBefore": calls,
            "callsAfter": h.model_calls(), "artifact": artifact})
    h.command("PlayerA", "/aivillage kernel inference false", "admission=false")
    rows = []
    for quantity in (3,4):
        seed(h)
        before = h.snapshot(); stock_before = physical(h); calls = h.model_calls()
        command = "/aivillage kernel compose-birch-planks " + h.citizens["PlayerA"] + " " + str(quantity) + " " + coord(SOURCE+DESTINATION+LOW+HIGH)
        jid, answer = submit(h, command, "job")
        def terminal():
            j = h.snapshot()["players"]["PlayerA"].get("jobs", {}).get(jid)
            return j if j and j["state"] in ("FAILED", "SUCCEEDED", "CANCELLED", "INTERRUPTED") else None
        root = h.wait(terminal, "Original composed birch Job " + str(quantity), 240)
        after = h.snapshot()
        require(h.model_calls() == calls, "Known composed work invoked inference")
        require(root["request"]["request"]["arguments"]["amount"]["value"] == quantity, "Original root demand changed")
        if quantity == 3:
            require(root["state"] == "FAILED" and root["reason"] == "REQUEST_INVALID" and not root["attempts"],
                    "Original q=3 admission failure not reproduced: " + json.dumps(root))
            require(physical(h) == stock_before, "Rejected q=3 changed physical stock")
        else:
            require(root["state"] == "SUCCEEDED" and root["fulfilled"] == 4, "q=4 native control failed: " + json.dumps(root))
            require(h.container(SOURCE, LOG) == 0 and h.container(SOURCE, OTHER) == 2, "q=4 changed unrelated stock")
            require(h.container(DESTINATION, PLANK) == stock_before["containers"][",".join(map(str,DESTINATION))].get(PLANK,0)+4,
                    "q=4 actual delivery mismatch")
            require(root["preparation"]["method"]["sha256"] == artifact, "q=4 used a different admitted method")
        h.actions_active = False
        require(h.menu_count("PlayerA", DESTINATION, PLANK) == h.container(DESTINATION, PLANK), "Client destination differs")
        h.screenshot("PlayerA", "baseline-q" + str(quantity), chat=True)
        row = {"quantity": quantity, "command": command, "feedback": answer, "job": jid,
               "before": before, "after": after, "root": root, "callsBefore": calls,
               "callsAfter": h.model_calls(), "diagnosticAssertion": "PASS", "gameplayOutcome": root["state"]}
        atomic(h.root / ("q" + str(quantity) + ".json"), row); rows.append(row)
    return {"status": "PASS", "meaning": "Baseline diagnostic reproduced; q3 remains a production failure",
            "artifact": artifact, "cases": [{"quantity": r["quantity"], "gameplayOutcome": r["gameplayOutcome"]} for r in rows]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    h = Harness(args.root, "compose", None)
    h.project = "cc-production-b-" + str(read(h.root / "metadata.json")["fixtureInstance"])[:8]
    h.family = "B00-BASELINE"
    result = {"status": "NOT_EXECUTED", "productionChanges": False}
    try:
        h.compose("build", "minecraft")
        result = scenario(h)
    except Exception as error:
        result.update(status="FAIL", reason=str(error), trace=traceback.format_exc())
        atomic(h.root / "failure-snapshot.json", h.snapshot())
        if "PlayerA" in h.booted:
            try: h.screenshot("PlayerA", "preserved-first-failure", chat=True)
            except Exception as capture: result["screenshotFailure"] = str(capture)
        raise
    finally:
        h.finish()
        atomic(h.root / "result.json", result)
        files = [{"path": str(p.relative_to(h.root)), "sha256": digest(p), "bytes": p.stat().st_size}
                 for p in sorted(h.root.rglob("*")) if p.is_file() and p.name != "manifest.json"]
        atomic(h.root / "manifest.json", {"schema": 1, "source": read(h.root / "metadata.json"),
               "result": result, "files": files})


if __name__ == "__main__":
    main()
