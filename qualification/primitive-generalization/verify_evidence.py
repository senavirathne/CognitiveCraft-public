"""Independently reconcile the two PG00 baselines from native producer files."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET
import zlib


CONNECTION = "E2E-CONN-001"
LEGACY = "E2E-LEGACY-001"
PROFILE = "pg00-baseline"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def records(path):
    with path.open() as stream:
        for line in stream:
            require(line.endswith("\n"), f"Incomplete evidence line in {path.name}")
            yield json.loads(line)


def single(rows, description):
    rows = list(rows)
    require(len(rows) == 1, f"Expected one {description}, found {len(rows)}")
    return rows[0]


def png_size(data):
    """Validate the native RGB(A) PNG stream, including CRCs and complete pixels."""
    require(data.startswith(b"\x89PNG\r\n\x1a\n") and 1024 < len(data) <= 64 * 1024**2,
            "Invalid PNG size or signature")
    offset, header, compressed = 8, None, bytearray()
    while offset + 12 <= len(data):
        length = int.from_bytes(data[offset:offset + 4], "big")
        tag = data[offset + 4:offset + 8]
        end = offset + length + 12
        require(end <= len(data), "Truncated PNG chunk")
        body = data[offset + 8:end - 4]
        require(zlib.crc32(tag + body) == int.from_bytes(data[end - 4:end], "big"), "Bad PNG CRC")
        if tag == b"IHDR":
            require(offset == 8 and length == 13, "Invalid PNG header")
            header = struct.unpack(">IIBBBBB", body)
        elif tag == b"IDAT":
            compressed.extend(body)
        elif tag == b"IEND":
            require(length == 0 and end == len(data) and header is not None, "Invalid PNG end")
            break
        offset = end
    else:
        raise ValueError("PNG end missing")
    width, height, depth, color, compression, filters, interlace = header
    require(800 <= width <= 4096 and 600 <= height <= 4096 and depth == 8 and color in (2, 6)
            and compression == filters == interlace == 0, "Unsupported client framebuffer format")
    stride = 1 + width * (3 if color == 2 else 4)
    inflater = zlib.decompressobj()
    pixels = inflater.decompress(compressed, stride * height + 1)
    require(len(pixels) == stride * height and inflater.eof and not inflater.unused_data
            and all(b <= 4 for b in pixels[::stride]), "Incomplete PNG pixels")
    return [width, height]


def frame(root, family, checkpoint, screen, captures, case, packets, window):
    item = single((v for v in captures if v["family"] == family and v["checkpoint"] == checkpoint
                   and v["player"] == "PlayerA"), checkpoint)
    require(item in case["screenshots"] and item["nativeClientFrame"] is True
            and item["screen"] == screen, "Frame is absent from its case or native UI differs")
    relative = Path(item["path"])
    require(relative.parent == Path("clients/PlayerA/screenshots") and
            re.fullmatch(re.escape(family) + r"-[0-9]{3}-PlayerA-" + re.escape(checkpoint) + r"\.png",
                         relative.name), "Frame escaped its checkpoint path")
    data = (root / relative).read_bytes()
    require(len(data) == item["bytes"] and png_size(data) == [item["width"], item["height"]] and
            hashlib.sha256(data).hexdigest() == item["sha256"], "Frame bytes differ from manifest")
    capture = single((e for e in packets if e["kind"] == "screenshot" and
                      e["data"]["name"] == relative.stem and e["data"]["tick"] == item["tick"] and
                      Path(e["data"]["path"]).parts[-4:] == relative.parts), "native render event")
    require(window[0] <= capture["time"] <= window[1] and
            window[0] <= item["time"] * 1000 <= window[1], "Frame belongs to another family window")
    return item, capture["time"]


def suggestion(packets, line, include):
    for index, request in enumerate(packets):
        if request["kind"] != "suggestion-request" or request["data"]["text"].lstrip("/") != line:
            continue
        transaction = request["data"]["transaction"]
        for event in packets[index + 1:]:
            if event["kind"] in ("driver", "disconnect") or (event["kind"] == "suggestion-request"
                    and event["data"]["transaction"] == transaction):
                break
            if event["kind"] == "suggestion-response" and event["data"]["transaction"] == transaction:
                if ("Baker" in event["data"]["values"]) == include:
                    return True
                break
    return False


def verify(root, source, jar, run_id, attempt):
    require(re.fullmatch(r"[0-9a-f]{40}", source), "Expected full source SHA")
    meta = json.loads((root / "metadata.json").read_text())
    with jar.open("rb") as stream:
        release = hashlib.file_digest(stream, "sha256").hexdigest()
    ci = meta["ci"]
    require(meta["sourceCommit"] == meta["reviewedHeadCommit"] == ci["GITHUB_SHA"] == source and
            meta["releaseSha256"] == meta["binaries"]["production"]["sha256"] == release and
            int(ci["GITHUB_RUN_ID"]) == run_id and int(ci["GITHUB_RUN_ATTEMPT"]) == attempt and
            meta["repository"] == "senavirathne/CognitiveCraft-public" and meta["topology"] == "compose",
            "Run, source, release or topology mismatch")
    for box, kind in (("server", "server"), ("clients/PlayerA", "client"), ("clients/PlayerB", "client")):
        process = json.loads((root / box / "process.json").read_text())
        require(process["kind"] == kind and process["releaseSha256"] == release and
                any(mod["sha256"] == release for mod in process["mods"]), f"Wrong binary in {box}")

    results = json.loads((root / "results.json").read_text())
    require(results["profile"] == PROFILE, "Wrong evidence profile")
    selected = {family: single((r for r in results["results"] if r["id"] == family), family)
                for family in (CONNECTION, LEGACY)}
    require(all(r["status"] == "PASS" for r in selected.values()), "Required baseline did not pass")
    xml = ET.parse(root / "junit.xml").getroot()
    require(sorted(c.get("name") for c in xml.findall("testcase")) == sorted(selected) and
            all(c.find(t) is None for c in xml.findall("testcase") for t in ("failure", "error", "skipped")),
            "Missing or failing E2E JUnit cases")
    supervisor = list(records(root / "supervisor.jsonl"))
    windows = {}
    for family in selected:
        start = single((e for e in supervisor if e["kind"] == "family-start" and
                        e["data"]["id"] == family), "family start")
        finish = single((e for e in supervisor if e["kind"] == "family-result" and
                         e["data"] == selected[family]), "family result")
        windows[family] = (math.floor(start["time"] * 1000), math.ceil(finish["time"] * 1000))
    require(windows[CONNECTION][0] < windows[CONNECTION][1] <= windows[LEGACY][0] + 1 <
            windows[LEGACY][1], "Unexpected family order")
    captures = json.loads((root / "screenshots.json").read_text())["captures"]
    require(all(c["sourceCommit"] == source and c["releaseSha256"] == release for c in captures),
            "Mixed source or release in screenshots")
    actors = {}
    for player in ("PlayerA", "PlayerB"):
        events = list(records(root / f"clients/{player}/events.jsonl"))
        require(all(e["player"] == player for e in events), "Mixed player packet streams")
        actors[player] = events
    a = actors["PlayerA"]
    smoke, smoke_time = frame(root, CONNECTION, "named-citizen-after-reconnect", "ChatScreen",
                             captures, selected[CONNECTION], a, windows[CONNECTION])
    session = [e for e in a if windows[CONNECTION][0] <= e["time"] <= smoke_time]
    login = selected[CONNECTION]["evidence"]["players"]["PlayerA"]
    require(re.fullmatch(r"[0-9a-f-]{36}", login) and any(e["kind"] == "disconnect" for e in session)
            and sum(e["kind"] == "play" and e["data"]["uuid"] == login for e in session) >= 2 and
            any(e["kind"] == "feedback" and "Ada" in e["data"]["text"] and
                "citizens=" in e["data"]["text"] for e in session), "Missing native reconnect identity")

    bread = selected[LEGACY]["evidence"]["bread"]
    require(type(bread) is int and bread >= 8, "Legacy physical bread threshold not met")
    menu, menu_time = frame(root, LEGACY, f"synchronized-container-{bread}", "ContainerScreen",
                            captures, selected[LEGACY], a, windows[LEGACY])
    require(smoke["sha256"] != menu["sha256"], "Different checkpoints reuse the same image")
    begin, end = windows[LEGACY]
    relevant = {name: [e for e in events if begin <= e["time"] <= end] for name, events in actors.items()}
    commands = [e for e in supervisor if e["kind"] == "command" and begin <= e["time"] * 1000 <= end]
    food = single((e for e in commands if e["data"]["player"] == "PlayerA" and
                   e["data"]["text"] == "/aivillage food Baker"), "Baker command feedback")
    native_food = single((e for e in relevant["PlayerA"] if e["kind"] == "feedback" and
                          e["data"]["text"] == food["data"]["feedback"]), "native food feedback")
    require(not any(e["kind"] == "fixture-setup" and native_food["time"] <= e["time"] * 1000 <= menu_time
                    for e in supervisor), "Administrative setup during measured bread production")
    require(any(e["kind"] == "feedback" and "Only the owner or an operator" in e["data"]["text"]
                for e in relevant["PlayerB"]) and
            suggestion(relevant["PlayerA"], "aivillage status ", True) and
            suggestion(relevant["PlayerB"], "aivillage status ", False),
            "Missing two-principal permission or suggestion evidence")
    speech = selected[LEGACY]["evidence"]["nearbySpeech"]
    require(speech.startswith("[Baker]") and sum(e["kind"] == "feedback" and e["data"]["text"] == speech
            for e in relevant["PlayerB"]) == 1 and selected[LEGACY]["evidence"]["outsideSpeechDistance"] > 32,
            "Unexpected nearby/distant speech evidence")

    # The last same-client snapshot before capture is collected with the menu still open.
    latest = None
    for value in records(root / "clients/PlayerA/observations.jsonl"):
        if native_food["time"] <= value["time"] <= menu_time:
            latest = value["state"]
    require(latest and latest["play"] and latest["singleplayer"] is False and latest["uuid"] == login and
            latest["menu"] > 0 and sum(s["count"] for s in latest["slots"] if s["playerInventory"] is False
            and s["item"] == "minecraft:bread") == bread, "No matching native client menu contents")
    container_slots = {s["slot"] for s in latest["slots"] if s["playerInventory"] is False}
    wire = {}
    for event in relevant["PlayerA"]:
        data = event["data"]
        if not native_food["time"] <= event["time"] <= menu_time or data.get("menu") != latest["menu"]:
            continue
        if event["kind"] == "menu-content":
            wire = dict(enumerate(data["slots"]))
        elif event["kind"] == "menu-slot" and wire:
            wire[data["slot"]] = data
    require(container_slots and container_slots <= wire.keys() and
            sum(wire[i]["count"] for i in container_slots if wire[i]["item"] == "minecraft:bread") == bread,
            "Native menu packets disagree with chest contents")
    player_b = next((v["state"]["uuid"] for v in records(root / "clients/PlayerB/observations.jsonl")
                     if begin <= v["time"] <= end and v["state"]["play"] and
                     v["state"]["singleplayer"] is False), None)
    require(player_b and player_b != login, "Missing distinct multiplayer principal")

    empty = live = same_players = False
    entity = selected[LEGACY]["evidence"]["entity"]
    for state in records(root / "server/observations.jsonl"):
        if not begin <= state["time"] <= end:
            continue
        chest = state["containers"].get("9,201,7")
        if chest is None:
            continue
        population = state["players"]
        if (population.get("PlayerA", {}).get("uuid") == login and
                population.get("PlayerB", {}).get("uuid") == player_b):
            same_players = True
        if state["time"] <= native_food["time"] and chest.get("minecraft:bread", 0) == 0:
            empty = True
        if native_food["time"] <= state["time"] <= menu_time and chest.get("minecraft:bread") == bread:
            live |= any(actor["uuid"] == entity and actor["alive"] and not actor["noAI"]
                        for actor in state["actors"])
    require(empty and live and same_players, "Server lacks a matched empty-to-delivered chest and live actor")
    return {"scope": "PG00 legacy baseline only", "source": source, "sourceTree": meta["sourceTree"],
            "releaseSha256": release, "run": run_id, "attempt": attempt, "profile": PROFILE,
            "verdict": "PASS", "bread": bread, "destination": "9,201,7",
            "familyVerdicts": {CONNECTION: "PASS", LEGACY: "PASS"},
            "screenshots": [{k: cap[k] for k in ("family", "path", "checkpoint", "screen", "tick",
                            "width", "height", "sha256")} for cap in (smoke, menu)]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--source", required=True)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--run-id", required=True, type=int)
    parser.add_argument("--attempt", required=True, type=int)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    result = verify(args.root, args.source, args.jar, args.run_id, args.attempt)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))
