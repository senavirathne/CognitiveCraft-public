"""Cold native archive reader challenge for a previous snapshot's unique segment."""

import hashlib
import json
import struct


def exercise(h, world, backup, old_job, pinned):
    from freeze_storage_review import segment
    from jobs import archive_hashes, no_effect_refusal, pinned_unchanged, restore
    from suite import atomic, read, reconnect_players, require

    folder = h.root / "previous-only-archive"
    folder.mkdir()
    version = world / "data/cognitivecraft/runtime-history/v1"
    h.save_checkpoint()
    h.stop("server")
    current_path = version / "manifest.jsonl"
    previous_path = version / "manifest.prev.jsonl"
    current_bytes, previous_bytes = current_path.read_bytes(), previous_path.read_bytes()
    head, current = (json.loads(line) for line in current_bytes.splitlines())
    previous_head, previous = (json.loads(line) for line in previous_bytes.splitlines())
    previous_refs = {r["digest"] for r in previous["segments"]}
    require(previous_head["world"] == head["world"] and previous_head["revision"] < head["revision"],
            "The actual previous archive manifest is missing or is not older")
    for ref in current["segments"]:
        if ref["owner"] != "JOBS" or ref["digest"] not in previous_refs:
            continue
        old_path = version / (ref["digest"] + ".seg")
        _, revision, _, rows = segment(old_path, head["world"])
        if revision > 0 and any(job["id"] == old_job for raw in rows.values() for job in json.loads(raw)["jobs"]):
            break
    else:
        raise ValueError("The native previous manifest has no exact segment for the archived original")

    # Reframe one acknowledged segment in this stopped fixture. Its exact
    # immutable rows are the native writer's bytes. No production repack or
    # physical effect is attributed to the fixture operation.
    original = ref["digest"]
    body = bytearray(old_path.read_bytes()[:-32])
    struct.pack_into(">q", body, 29, revision - 1)
    raw = bytes(body) + hashlib.sha256(body).digest()
    replacement = hashlib.sha256(raw).hexdigest()
    newer = version / (replacement + ".seg")
    require(not newer.exists(), "Reframed archive already exists")
    newer.write_bytes(raw)
    ref["digest"] = replacement
    payload = json.dumps(current, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    head["sha256"] = hashlib.sha256(payload).hexdigest()
    current_path.write_bytes(json.dumps(head, sort_keys=True, separators=(",", ":")).encode() + b"\n" + payload)
    for path in (current_path, previous_path, old_path, newer):
        (folder / path.name).write_bytes(path.read_bytes())

    def cold_start(label, expected_ready):
        prior = read(h.root / "server/job-ready.json")["process"]
        h.flags(False, False, False)
        h.start("server")
        reconnect_players(h, list(h.players))
        h.wait(lambda: (ready if (ready := read(h.root / "server/job-ready.json")) and ready["process"] != prior else None),
               label, 75)
        state = h.stable() if expected_ready else h.probe("status")
        pinned_unchanged(state, pinned)
        return {"state": state, "process": read(h.root / "server/process.json"), "lookup": h.lookup(old_job)}

    positive = cold_start("Previous-only archive segment remains readable in a new JVM", True)
    require(positive["lookup"]["status"] == "PASS" and positive["lookup"]["hot"] is False
            and previous_path.read_bytes() == previous_bytes, "Current and previous native snapshots were not both retained")
    h.stop("server")
    old_path.rename(folder / "withheld.seg")
    before = archive_hashes(world)
    negative = cold_start("Missing previous-only archive segment fences the current reader", False)
    require(negative["lookup"]["status"] == "UNAVAILABLE" and negative["state"]["storage"]["readOnly"],
            "Previous-only missing history accepted current mutation authority")
    refusal = no_effect_refusal(h, "previous-only required archive unavailable", ("INTERRUPTED", "STORAGE_UNAVAILABLE"))
    after = archive_hashes(world)
    require(before == after, "Fenced initialization rewrote remaining archive bytes")
    restore(h, world, backup, folder)
    recovered = h.lookup(old_job)
    require(recovered["status"] == "PASS" and recovered["hot"] is False,
            "Fully coordinated restore lost the original archived Job")
    atomic(folder / "result.json", {"oldDigest": original, "newDigest": replacement, "oldJob": old_job,
           "positive": positive, "negative": negative, "refusal": refusal, "beforeHashes": before,
           "afterHashes": after, "restored": recovered, "restoredProcess": read(h.root / "server/process.json")})


def review(root):
    from freeze_storage_review import segment
    from suite import completed_jsonl, read, require
    from e2e.parallel_i00.identity import sha256

    folder = root / "previous-only-archive"
    result = folder / "result.json"
    row = read(result)
    current, previous = (folder / name for name in ("manifest.jsonl", "manifest.prev.jsonl"))

    def read_manifest(path):
        head, payload = path.read_bytes().split(b"\n", 1)
        header = json.loads(head)
        require(header["schema"] == 1 and header["sha256"] == hashlib.sha256(payload).hexdigest(),
                "Invalid native archive manifest frame")
        return header, {ref["digest"] for ref in json.loads(payload)["segments"]}

    live_head, live_refs = read_manifest(current)
    old_head, old_refs = read_manifest(previous)
    old, new = row["oldDigest"], row["newDigest"]
    require(old in old_refs - live_refs and new in live_refs - old_refs and old_head["world"] == live_head["world"]
            and old_head["revision"] < live_head["revision"], "No exact previous-only reference")
    world = live_head["world"]
    previous_rows, current_rows = (segment(folder / (digest + ".seg"), world)[3] for digest in (old, new))
    require(previous_rows == current_rows and sha256(folder / "withheld.seg") == old
            and "v1/" + old + ".seg" not in row["beforeHashes"] and row["afterHashes"] == row["beforeHashes"],
            "Required previous history was not independently retained then withheld")
    exact = next(job for raw in previous_rows.values() for job in json.loads(raw)["jobs"] if job["id"] == row["oldJob"])
    positive, negative = row["positive"], row["negative"]
    require(positive["lookup"]["status"] == row["restored"]["status"] == "PASS"
            and positive["lookup"]["job"] == row["restored"]["job"]
            and positive["lookup"]["job"]["submissionId"] == exact["submission"]
            and all(positive["lookup"]["job"][key] == exact[key] for key in ("id", "generation", "state"))
            and positive["lookup"]["hot"] is row["restored"]["hot"] is False
            and negative["lookup"]["status"] == "UNAVAILABLE" and negative["state"]["storage"]["readOnly"],
            "Native cold archive classification changed original ownership")
    processes = (positive["process"], negative["process"], row["restoredProcess"])
    stops = completed_jsonl(root / "supervisor.jsonl")
    require(processes[0]["start"] < processes[1]["start"] < processes[2]["start"]
            and all(any(event["kind"] == "process-stop-witness" and event["data"]["process"] == p for event in stops) for p in processes[:2]),
            "Cold archive observations lack native JVM replacement")
    refusal = row["refusal"]
    require(refusal["before"]["containers"] == refusal["after"]["containers"]
            and not refusal["result"].get("resourceReceipts"), "Invalid previous history allowed native effects")
    return {"status": "PASS_INDEPENDENT_NATIVE_BOUNDARY", "scope": "NATIVE_PREVIOUS_ARCHIVE_REFERENCE",
            "previousOnlySegment": True, "immutableRowsPreserved": True, "missingReferenceFenced": True,
            "nativeEffects": 0, "resultSha256": sha256(result)}
