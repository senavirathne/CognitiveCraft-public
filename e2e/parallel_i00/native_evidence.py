"""Independent readers for synchronized native client and server witnesses."""
from __future__ import annotations

import json
import re

from .gameplay import ROOT
from campaigns.crafting.evidence import evidence_path, log_facts
from campaigns.crafting.run import offsets
from runtime import digest
from suite import UUID_PATTERN, require

TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED"}


def uuid_field(text, field):
    match = re.search(rf"\b{field}=({UUID_PATTERN})\b", text)
    return match[1] if match else None


def trace_offsets(root):
    result = offsets(root)
    for name in ("events.jsonl", "stdout.log"):
        key = "clients/PlayerB/" + name
        path = root / key
        result[key] = path.stat().st_size if path.exists() else 0
    return result


def request_job(h, player, command):
    h.actions_active = True
    response = h.command(player, command, r"job=|accepted=false|[Dd]enied|unavailable", 50)
    return {"command": command, "feedback": response, "player": player, "root": uuid_field(response, "job"),
            "accepted": "accepted=true" in response}


def job_at(state, player, identity):
    return state.get("knownPlayers", {}).get(player, {}).get("jobs", {}).get(identity)


def terminal_job(h, player, identity, timeout=240):
    def terminal():
        job = h.job_view(player, identity)
        return job if job and job["state"] in TERMINAL else None
    return h.wait(terminal, "Original Job physically terminal", timeout)


def closed_job(job):
    return job["state"] in {"SUCCEEDED", "FAILED", "CANCELLED"} and all(
        attempt.get("terminal") and attempt["uncertain"] is False for attempt in job["attempts"])


def item_at(state, position, item):
    return state["containers"].get(",".join(map(str, position)), {}).get(item, 0)


def stock_at(state, actor):
    return next(row["inventory"] for row in state["actors"] if row["uuid"] == actor)


def raw_slices(root, row, names):
    paths = {}
    for record in row["rawLogs"]:
        if not record["available"]:
            continue
        key = record["path"]
        require(key not in paths, "Duplicate native evidence path: " + key)
        path = evidence_path(root, record["slice"])
        require(path.is_file() and path.stat().st_size == record["endByte"] - record["startByte"]
                and 0 <= record["startByte"] <= record["endByte"] and digest(path) == record["sha256"],
                "Native trace slice differs from its recorded byte range: " + key)
        paths[key] = path
    require(set(names) <= paths.keys(), "Required native trace slices missing: " + str(set(names) - paths.keys()))
    return paths


def jsonl(path):
    with path.open() as stream:
        for index, line in enumerate(stream):
            if not line.endswith("\n"):
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError:
                # A slice can start within the producer's current append.
                if index != 0:
                    raise


def require_command(path, command, feedback, player):
    saw_command = False
    for event in jsonl(path):
        if event.get("player") != player:
            continue
        data = event.get("data", {})
        if event.get("kind") == "action" and data.get("action") == "command" and data.get("text") == command:
            saw_command = True
        if saw_command and event.get("kind") == "feedback" and data.get("text") == feedback:
            return
    raise ValueError("No original connected-client command and matching acknowledgement")


def require_native_primitive(path):
    facts = log_facts(path)
    require(facts["primitiveStarts"] and not facts["incompletePrimitiveTrace"], "No complete original native primitive trace")


def verify_frames(root, frames, minimum=1):
    require(len(frames) >= minimum, "Missing original connected-client frames")
    paths = set()
    for frame in frames:
        p = evidence_path(root, frame["path"])
        require(p not in paths and p.is_file() and digest(p) == frame["sha256"], "Native client frame missing or changed")
        paths.add(p)
        with p.open("rb") as stream:
            header = stream.read(24)
        require(frame["nativeClientFrame"] is True and len(header) == 24 and header[:8] == b"\x89PNG\r\n\x1a\n"
                and int.from_bytes(header[16:20], "big") >= 800 and int.from_bytes(header[20:24], "big") >= 600,
                "Native frame identity or dimensions changed")
