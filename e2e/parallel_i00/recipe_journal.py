"""Independent bounded reader of the actual exact-choice journal and saved markers."""

import hashlib
import json
from uuid import UUID


MAX_JOURNAL_BYTES = 8 * 1024 * 1024
PARTICIPANT = "cognitivecraft_deposit_evidence"
PHASES = ("WITHDRAW", "CRAFT", "DEPOSIT")
USAGE_KINDS = {"CALLS", "REPAIRS", "INPUT_BYTES", "OUTPUT_BYTES", "CANDIDATES", "TRIALS", "INSTRUCTIONS",
               "OBSERVATIONS", "TRAVEL_BLOCKS", "ATTEMPTED_EFFECTS", "COMMITTED_EFFECTS", "ELAPSED_TICKS"}


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def bounded(path, size):
    if path.is_symlink():
        raise ValueError("Indirect native recipe evidence")
    with path.open("rb") as file:
        raw = file.read(size + 1)
    if len(raw) > size:
        raise ValueError("Oversized recipe evidence")
    return raw


def read(world, *, expected_world=None):
    base = world / "data/cognitivecraft"
    rows = bounded(base / "recipe-evidence/phases.jsonl", MAX_JOURNAL_BYTES)
    commit = bounded(base / "recipe-evidence-required.json", 1024)
    manifest = json.loads(commit)
    world_id = str(UUID(manifest["world"]))
    if expected_world is not None and world_id != expected_world:
        raise ValueError("Exact recipe journal is from a different saved world")
    head = digest(world_id.encode())
    evidence = {}
    operations = set()
    for line in rows.splitlines(keepends=True):
        value = json.loads(line)
        if len(line) > 2049 or canonical(value) + b"\n" != line or value.pop("previous") != head:
            raise ValueError("Invalid recipe journal chain at " + head)
        head = digest(line)
        token = str(UUID(value["token"]))
        if value["kind"] == "prepare":
            fields = {"kind", "token", "root", "step", "attempt", "generation", "operation",
                      "method", "choice", "batch", "phase", "deadline", "before"}
            if (set(value) != fields or token in evidence or value["operation"] in operations
                    or value["phase"] not in PHASES or not value["before"] or len(value["before"]) > 4):
                raise ValueError("Duplicate or malformed recipe preparation")
            for field in ("root", "step", "attempt", "operation"):
                UUID(value[field])
            operations.add(value["operation"])
            evidence[token] = {"prepare": value, "result": None}
        elif value["kind"] == "result":
            existing = evidence.get(token)
            if (set(value) != {"kind", "token", "intent", "status", "after", "usage"}
                    or not existing or existing["result"] is not None or value["intent"] != digest(canonical(existing["prepare"]))
                    or value["status"] not in ("APPLIED", "NO_EFFECT", "UNCERTAIN")):
                raise ValueError("Recipe result lacks the original exact preparation")
            usage = value["usage"]
            if (not isinstance(usage, dict) or not set(usage) <= USAGE_KINDS
                    or any(type(quantity) is not int or quantity < 0 for quantity in usage.values())):
                raise ValueError("Invalid exact recipe phase cumulative usage")
            before = existing["prepare"]["before"]
            after = value["after"]
            if value["status"] != "UNCERTAIN" and (len(before) != len(after) or any(
                    (a["address"], a["incarnation"]) != (b["address"], b["incarnation"]) or a["revision"] > b["revision"]
                    for a, b in zip(before, after))):
                raise ValueError("Saved native participants changed under the original mutation")
            if value["status"] == "NO_EFFECT" and before != after:
                raise ValueError("A no-effect result changed custody")
            existing["result"] = value
        else:
            raise ValueError("Unknown native recipe journal kind")
    expected = {"schema": 1, "world": world_id, "bytes": len(rows), "head": head}
    if commit != canonical(expected) or (base / "recipe-evidence-required.json.next").exists():
        raise ValueError("No acknowledged exact journal commitment")
    return evidence


def phases(evidence, root):
    return [phase for phase in evidence.values() if phase["prepare"]["root"] == root]


def positive(phase):
    return phase["result"] is not None and phase["result"]["status"] == "APPLIED"


def prepared_craft(evidence, root):
    prefix = phases(evidence, root)
    if [row["prepare"]["phase"] for row in prefix] != ["WITHDRAW", "CRAFT"] or not all(map(positive, prefix)):
        return None
    old, new = [row["prepare"] for row in prefix]
    if any(old[key] != new[key] for key in ("root", "step", "attempt", "generation", "method", "choice", "deadline", "batch")):
        raise ValueError("Craft cut changed the original immutable authority")
    return prefix[-1]


def saved_marker(entity, phase, participant):
    """Read the actual native NBT marker; a result journal alone never suffices."""
    marker = json.loads(entity[PARTICIPANT])
    prepare, result = phase["prepare"], phase["result"]
    expected_effect = {"schema": 1, "effect": digest(prepare["token"].encode()),
                       "intent": digest(canonical(prepare)), "phase": "RESULT",
                       "result": digest(canonical(result))}
    if (marker.get("schema"), marker.get("incarnation"), marker.get("revision"), json.loads(marker["marker"])) != (
            1, participant["incarnation"], participant["revision"], expected_effect):
        raise ValueError("Actual saved participant does not acknowledge the exact craft result")
    if PARTICIPANT + "_original" in entity:
        raise ValueError("Quarantined participant marker cannot prove a supported save")
    return expected_effect
