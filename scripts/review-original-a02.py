#!/usr/bin/env python3
"""Task 3 read-only review of the byte-pinned original A02 archive."""
import argparse
import datetime
import hashlib
import json
import math
import os
from pathlib import Path
import re
import urllib.error
import urllib.request
import zipfile

ARCHIVE_ID = 12272292483
ARCHIVE_SIZE = 106302942
ARCHIVE_SHA256 = "91de8917c93c4738eb3d6db2744ef0a502cd018edb7f4ab0c0b0efcb5e367050"
# The original production JAR has its own identity, independent of its enclosing ZIP.
JAR_SHA256 = "acc986c2b545735d8c2c6fc9c99ae59ad0291a35b7d134b3681436f5418316f0"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def download(path):
    request = urllib.request.Request(
        f"https://api.github.com/repos/senavirathne/CognitiveCraft-public/actions/artifacts/{ARCHIVE_ID}/zip",
        headers={"Authorization": "Bearer "+os.environ["GH_TOKEN"], "Accept": "application/vnd.github+json"})
    try:
        response = urllib.request.build_opener(NoRedirect).open(request, timeout=60)
    except urllib.error.HTTPError as error:
        if error.code != 302:
            raise
        # The signed storage redirect is never logged; credentials stay on GitHub.
        response = urllib.request.urlopen(error.headers["Location"], timeout=60)
    with response, path.open("wb") as output:
        while block := response.read(1024*1024):
            output.write(block)
    assert path.stat().st_size == ARCHIVE_SIZE
    assert hashlib.file_digest(path.open("rb"), "sha256").hexdigest() == ARCHIVE_SHA256


def compact(snapshot, actor, drop_ids):
    selected = next((a for a in snapshot.get("actors", []) if a.get("uuid", a.get("id")) == actor), None)
    drops = [d for d in snapshot.get("drops", []) if d.get("uuid", d.get("id")) in drop_ids]
    actor_view = {k: selected.get(k) for k in ("uuid", "pos", "inventory", "alive", "baby", "noAI")} if selected else None
    return {"gameTick": snapshot.get("gameTick", snapshot.get("tick")), "time": snapshot.get("time"),
            "actor": actor_view, "ownedDrops": drops,
            "containers": {k: v for k, v in snapshot.get("containers", {}).items() if k in ("74,208,69", "77,208,72")}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args(); args.root.mkdir(parents=True, exist_ok=True)
    archive = args.root / "original-oak.zip"
    download(archive)
    result = {"schema": 1, "task": "3/A02", "mode": "READ_ONLY_ORIGINAL_ARCHIVE",
              "archive": {"id": ARCHIVE_ID, "size": ARCHIVE_SIZE, "sha256": ARCHIVE_SHA256},
              "originalRun": 39434946667, "originalAttempt": 1}
    with zipfile.ZipFile(archive) as z:
        paths = z.namelist()
        record_path = next(p for p in paths if p.endswith("generalization-results.json"))
        raw = z.read(record_path); assessment = json.loads(raw)
        case = next(x for x in assessment["scenarios"] if x["id"] == "A02")
        view = json.loads(case["reason"][case["reason"].index("{"):])
        actor = view["responsibility"]["request"]["request"]["arguments"]["actor"]["value"]["entityId"]
        rows = view["resourceReceipts"]
        drop_ids = {d for row in rows for d in row["dropIds"]}
        final = case.get("physicalFinalState", case.get("physicalFailureState"))
        before_tick = 6138
        final_tick = final.get("gameTick", final.get("tick"))
        result.update(originalSource=assessment.get("metadata", {}).get("source"),
                      resultsPath=record_path, resultsSha256=hashlib.sha256(raw).hexdigest(),
                      originalCase={"id": "A02", "status": case["status"], "root": view["id"],
                                    "marker": view["marker"], "usage": view["responsibility"]["usage"],
                                    "receipts": rows, "actor": actor, "final": compact(final, actor, drop_ids)})
        jars = []
        for path in paths:
            if path.endswith("/ai-villages-0.1.0.jar"):
                data = z.read(path); digest = hashlib.sha256(data).hexdigest()
                assert digest == JAR_SHA256, (path, digest)
                jars.append({"path": path, "bytes": len(data), "sha256": digest})
        assert len(jars) >= 3
        result["actualProductionCopies"] = jars
        stdout_path = next(p for p in paths if p.endswith("server/stdout.log"))
        stdout = z.read(stdout_path).decode("utf-8", errors="replace")
        start = "14:14:04"; end = "14:15:07"
        navigation = []
        diagnostics = []
        for number, line in enumerate(stdout.splitlines(), 1):
            clock = re.search(r"\[(\d\d:\d\d:\d\d)", line)
            if not clock or not start <= clock[1] <= end:
                continue
            if "CognitiveCraft MOVE route actor="+actor in line:
                navigation.append({"line": number, "text": line})
            if view["id"] in line or any(d in line for d in drop_ids) or "custody stale" in line:
                diagnostics.append({"line": number, "text": line[:4000]})
        result["originalNavigation"] = navigation
        result["originalDiagnostics"] = diagnostics[:30]
        observer_path = next(p for p in paths if p.endswith("server/observations.jsonl"))
        witnesses = []; first = None; last = None; recent = []
        remaining = {d.get("uuid", d.get("id")) for d in final.get("drops", [])} & drop_ids
        with z.open(observer_path) as stream:
            for line_number, raw_line in enumerate(stream, 1):
                tick_match = re.search(rb'"gameTick"\s*:\s*(\d+)', raw_line[:300])
                if not tick_match:
                    continue
                tick = int(tick_match[1])
                if tick < before_tick or tick > final_tick+3:
                    continue
                snapshot = json.loads(raw_line)
                state = compact(snapshot, actor, remaining)
                state.update(line=line_number, rawLineSha256=hashlib.sha256(raw_line).hexdigest())
                if state["ownedDrops"]:
                    if first is None: first = state
                    last = state
                    if not witnesses or witnesses[-1]["ownedDrops"][0].get("pos") != state["ownedDrops"][0].get("pos"):
                        witnesses.append(state)
                if tick >= final_tick-70: recent.append(state)
        result["ownedDropFirst"] = first
        result["ownedDropLast"] = last
        result["ownedDropMovement"] = witnesses[-25:]
        result["lastApproachWitnesses"] = recent[::4]
        result["observerPath"] = observer_path
    output = args.root / "original-a02-review.json"
    output.write_text(json.dumps(result, indent=2)+"\n")
    # Bounded structured evidence is readable without downloading a new archive.
    print("TASK3_ORIGINAL_REVIEW "+json.dumps(result, separators=(",", ":")), flush=True)


if __name__ == "__main__":
    main()
