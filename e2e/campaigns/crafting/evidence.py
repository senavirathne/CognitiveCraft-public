"""Independent validation of preserved case records, paths and original trace facts."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re

from .oracle import requirements

PASS_STATUSES = {"PASS", "PASS_EXPECTED_REFUSAL", "PASS_RECOVERY", "PASS_CONTENTION", "PASS_EXPECTED_INTERRUPTION"}
STATUSES = PASS_STATUSES | {"GAP_RECIPE_EQUIVALENCE", "GAP_NO_GAMEPLAY_ADAPTER", "GAP_RESOURCE_DISTRIBUTION",
                           "GAP_ACTOR_CUSTODY_BINDING", "BLOCKED_ADMISSION", "BLOCKED_CAPACITY", "INCONCLUSIVE_BOUNDARY",
                           "FAIL_SAFETY", "FAIL_GAMEPLAY", "FAIL_HARNESS", "FAIL_UNCLASSIFIED", "NOT_EXECUTED"}


def evidence_path(root, name):
    """An artifact reference cannot read another file through traversal or a symlink."""
    if not isinstance(name, str) or not name or "\\" in name:
        raise ValueError("Invalid evidence reference")
    relative = Path(name)
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError("Evidence reference leaves this campaign")
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()):
        raise ValueError("Evidence symlink leaves this campaign")
    return path


def file_hash(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def log_facts(path, *, partial_first=False, cutoff=None, strict_events=False):
    """Stream small trace records; do not load a long server log into memory."""
    commands, primitives, failures, incomplete = set(), 0, [], False
    starts, handles = {}, {}
    with path.open("r", errors="replace") as stream:
        for index, line in enumerate(stream):
            if not line.endswith("\n"):
                continue  # A concurrent last append is not a committed JSONL record.
            incomplete |= "CRAFTING_PRIMITIVE_TRACE_INCOMPLETE" in line
            marker = "CRAFTING_PRIMITIVE_TRACE "
            position = line.find(marker)
            if position >= 0:
                value = json.loads(line[position+len(marker):])
                if cutoff and value["time"] > cutoff * 1000:
                    continue
                primitives += value["kind"] in {"primitive-start", "legacy-primitive-start"}
                payload = value.get("value", {})
                if value["kind"] == "primitive-start":
                    starts[(payload["correlation"]["runId"], payload["primitive"]["id"])] = payload
                elif value["kind"] == "primitive-handle":
                    key = (payload["handle"]["runId"], payload["primitive"]["id"])
                    handles[payload["handle"]["id"]] = {"primitive": payload["primitive"],
                                                        "start": starts.get(key)}
                reason = payload.get("receipt", {}).get("reason")
                if reason and reason != "CANCELLED":
                    handle = payload.get("handle", {})
                    failures.append({"time": value["time"], "reason": reason, "trace": value,
                                     "operation": handles.get(handle.get("id")), "run": handle.get("runId")})
            elif line.startswith("{"):
                try:
                    value = json.loads(line)
                except json.JSONDecodeError:
                    if index == 0 and partial_first or not strict_events:
                        continue
                    raise
                if not isinstance(value, dict):
                    if strict_events:
                        raise ValueError("Original JSONL trace is not an object")
                    continue
                if value.get("kind") == "action" and value.get("data", {}).get("action") == "command":
                    commands.add(value["data"]["text"])
    return {"commands": sorted(commands), "primitiveStarts": primitives,
            "firstFailedPrimitive": failures[0] if failures else None, "incompletePrimitiveTrace": incomplete}


def record_problems(row):
    problems = []
    case = row.get("case") or {}
    identity = case.get("id", "")
    if not re.fullmatch(r"P[1-8]-[A-Za-z0-9_.-]{1,150}", identity) or not identity.startswith(case.get("family", "") + "-"):
        problems.append("Invalid or mismatched case identity")
    if row.get("status") not in STATUSES:
        problems.append("Unknown case classification")
    if type(row.get("attempted")) is not bool or type(row.get("terminalObserved")) is not bool:
        problems.append("Execution denominators must be explicit booleans")
    if row.get("status") not in PASS_STATUSES:
        return problems
    if row.get("cleanupFenced") is not True or row.get("terminalObserved") is not True:
        problems.append("Passing case lacks a terminal outcome and a case isolation fence")
    metadata = row.get("metadata") or {}
    for key, length in [("sourceCommit", 40), ("sourceTree", 40), ("releaseSha256", 64), ("productionBaseline", 40)]:
        if not re.fullmatch(r"[0-9a-f]{" + str(length) + r"}", metadata.get(key, "")):
            problems.append("Passing case lacks exact identity: " + key)
    if metadata.get("minecraft") != "26.3":
        problems.append("Wrong or missing Minecraft runtime identity")
    if metadata.get("dirty") is not False:
        problems.append("Passing case was not produced from a clean recorded source checkout")
    bindings = row.get("bindings") or {}
    for key in ["before", "after"]:
        state = row.get(key) or {}
        if not all(name in state for name in ("blocks", "containers", "actors", "drops", "knownPlayers")):
            problems.append("Missing independent physical snapshot: " + key)
        if bindings.get("actor") not in {actor.get("uuid") for actor in state.get("actors", [])}:
            problems.append("Bound actor is absent from physical snapshot: " + key)
        if state.get("knownPlayers", {}).get("PlayerA", {}).get("uuid") != bindings.get("player"):
            problems.append("Server principal differs from the bound player: " + key)
    for key in ["clientBefore", "clientAfter"]:
        client = row.get(key) or {}
        if client.get("play") is not True or client.get("singleplayer") is not False or client.get("uuid") != bindings.get("player"):
            problems.append("No matching dedicated-server client observation: " + key)
    if case.get("mode") in {"recipe", "legacy"}:
        probe = row.get("vanillaProbe") or {}
        if probe.get("caseId") != identity or probe.get("worldEffects") != 0 or probe.get("matches") is not True:
            problems.append("Vanilla probe is not bound to this case")
        if case.get("mode") == "recipe" and (probe.get("recipe") != case.get("recipe") or probe.get("grid") != case.get("grid")):
            problems.append("Vanilla probe describes different recipe inputs")
        try:
            wanted = requirements(probe, case["quantity"] if case.get("mode") == "recipe" else row["requested"])
            if wanted != row.get("independentExpectation"):
                problems.append("Recorded recipe arithmetic differs from the vanilla probe")
        except (KeyError, TypeError, ValueError):
            problems.append("Vanilla recipe expectation cannot be independently recomputed")
    return problems


def compact_result(row):
    """Keep report facts while full snapshots remain in immutable per-case files."""
    keys = ("case", "status", "attempted", "terminalObserved", "boundaryWitnessed", "reason", "reportedReason",
            "request", "independentExpectation", "independentShortage", "conservation", "rawLogs", "logFacts",
            "delivered", "requested", "physicalDelta", "cleanupFenced", "vanillaProbe")
    result = {key: row[key] for key in keys if key in row}
    result["physicalEffectObserved"] = bool(row.get("physicalDelta")) or (
        row.get("before", {}).get("blocks") != row.get("after", {}).get("blocks") and
        "before" in row and "after" in row)
    return result
