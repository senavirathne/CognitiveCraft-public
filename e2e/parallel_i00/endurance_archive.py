"""Read the preserved native history independently of the Java archive readers."""

from collections import Counter
from hashlib import md5, sha256
import json
import re
from struct import Struct
from uuid import UUID
import zlib


ARCHIVE_PATH = "data/cognitivecraft/runtime-history/v1"
OWNERS = ("JOBS", "LEASES")
KINDS = ("HISTORY", "INTENT", "RESULT", "CANCELLATION", "SETTLEMENT", "PACKED_HISTORY")
MAX_BYTES = 8 * 1024 * 1024
MAX_SEGMENTS = 128
MAX_SEGMENT_BYTES = 131_072
MAX_SEGMENT_ROWS = 64
MAX_RECORD_BYTES = 65_536
MAX_MANIFEST_BYTES = 65_536
SEGMENT_HEADER = Struct(">8sI16sBQQI")
ROW_HEADER = Struct(">B16sI")
PACK_HEADER = Struct(">4sI")
PACK_ROW_HEADER = Struct(">16sI")
SEGMENT_NAME = re.compile(r"[a-f0-9]{64}\.seg")


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def unique_object(pairs):
    value = dict(pairs)
    require(len(value) == len(pairs), "Duplicate encoded JSON field")
    return value


def object_from_bytes(data):
    value = json.loads(data.decode("utf-8"), object_pairs_hook=unique_object)
    require(isinstance(value, dict), "Native evidence JSON must be an object")
    return value


def bounded_bytes(path, limit):
    require(not path.is_symlink() and path.is_file(), f"Missing/nonregular evidence: {path}")
    with path.open("rb") as source:
        data = source.read(limit + 1)
    require(len(data) <= limit, f"Evidence byte bound exceeded: {path}")
    return data


def read_manifest(path, world):
    data = bounded_bytes(path, MAX_MANIFEST_BYTES)
    header_bytes, body_bytes = data.split(b"\n", 1)
    header, body = object_from_bytes(header_bytes), object_from_bytes(body_bytes)
    require(set(header) == {"schema", "world", "revision", "owners", "retentionGeneration", "records", "sha256"},
            f"Unexpected archive manifest header: {path.name}")
    require(type(header["schema"]) is int and header["schema"] == 1 and header["world"] == world,
            f"Wrong manifest schema or world: {path.name}")
    require(sha256(body_bytes).hexdigest() == header["sha256"], f"Manifest body digest differs: {path.name}")
    require(set(header["owners"]) == set(OWNERS) and set(body) == {"segments", "slots", "migrations"},
            f"Unexpected archive manifest fields: {path.name}")
    counters = [header["revision"], header["retentionGeneration"], header["records"], *header["owners"].values()]
    require(all(type(counter) is int and counter >= 0 for counter in counters), f"Invalid archive counters: {path.name}")
    require(isinstance(body["segments"], list) and len(body["segments"]) == header["records"] <= MAX_SEGMENTS,
            f"Manifest segment count differs: {path.name}")
    require(all(isinstance(body[key], list) and len(body[key]) <= 8 for key in ("slots", "migrations")),
            f"Manifest reservation/migration bound exceeded: {path.name}")
    names = set()
    for entry in body["segments"]:
        require(set(entry) == {"digest", "owner", "bytes", "records", "createdEpochMillis"}, "Unexpected segment reference")
        name = entry["digest"] + ".seg"
        require(SEGMENT_NAME.fullmatch(name) and name not in names and entry["owner"] in OWNERS,
                "Invalid or duplicate segment reference")
        require(all(type(entry[k]) is int for k in ("bytes", "records", "createdEpochMillis"))
                and 90 <= entry["bytes"] <= MAX_SEGMENT_BYTES and 1 <= entry["records"] <= MAX_SEGMENT_ROWS
                and entry["createdEpochMillis"] >= 0, "Invalid segment reference bounds")
        names.add(name)
    return header, body, sha256(data).hexdigest()


def read_segment(path, world):
    data = bounded_bytes(path, MAX_SEGMENT_BYTES)
    require(len(data) >= 90 and sha256(data).hexdigest() + ".seg" == path.name,
            f"Archive segment filename/digest differs: {path.name}")
    payload, checksum = data[:-32], data[-32:]
    require(sha256(payload).digest() == checksum, f"Archive segment checksum differs: {path.name}")
    magic, schema, segment_world, owner, owner_revision, generation, count = SEGMENT_HEADER.unpack_from(payload)
    require(magic == b"CCAHIST1" and schema == 1 and str(UUID(bytes=segment_world)) == world,
            f"Wrong segment schema or world: {path.name}")
    require(owner < len(OWNERS) and 1 <= count <= MAX_SEGMENT_ROWS, "Invalid segment owner or row count")
    offset = SEGMENT_HEADER.size
    records = {}
    for _ in range(count):
        require(offset + ROW_HEADER.size <= len(payload), "Truncated archive row header")
        kind, identifier, size = ROW_HEADER.unpack_from(payload, offset)
        offset += ROW_HEADER.size
        require(kind < len(KINDS) and 1 <= size <= MAX_RECORD_BYTES and offset + size <= len(payload),
                "Invalid archive row framing")
        key = (OWNERS[owner], KINDS[kind], str(UUID(bytes=identifier)))
        require(key not in records, f"Duplicate archive row: {key}")
        records[key] = payload[offset:offset + size]
        offset += size
    require(offset == len(payload), "Trailing archive segment bytes")
    return {"owner": OWNERS[owner], "ownerRevision": owner_revision, "retentionGeneration": generation,
            "bytes": len(data), "rows": records}


def expand_history(stored):
    records = {}
    bytes_read = 0
    for key, body in stored.items():
        owner, kind, identifier = key
        if kind != "PACKED_HISTORY":
            require(records.setdefault(key, body) == body, "Conflicting original history record")
            bytes_read += len(body)
            continue
        require(len(body) > PACK_HEADER.size, "Truncated packed history header")
        magic, size = PACK_HEADER.unpack_from(body)
        require(magic == b"CCP1" and 4 + PACK_ROW_HEADER.size < size <= MAX_SEGMENT_BYTES,
                "Unknown packed history or unbounded decompression")
        stream = zlib.decompressobj()
        payload = stream.decompress(body[PACK_HEADER.size:], size + 1)
        require(len(payload) == size and stream.eof and not stream.unused_data and not stream.unconsumed_tail
                and str(UUID(bytes=md5(payload, usedforsecurity=False).digest(), version=3)) == identifier,
                "Packed history does not match its original encoded identity")
        count = int.from_bytes(payload[:4], "big")
        require(1 <= count <= MAX_SEGMENT_ROWS, "Packed history row bound")
        offset = 4
        seen = set()
        for _ in range(count):
            require(offset + PACK_ROW_HEADER.size <= len(payload), "Truncated original history row")
            row_id, length = PACK_ROW_HEADER.unpack_from(payload, offset)
            offset += PACK_ROW_HEADER.size
            row_key = (owner, "HISTORY", str(UUID(bytes=row_id)))
            require(row_key not in seen and 1 <= length <= MAX_RECORD_BYTES and offset + length <= len(payload),
                    "Invalid original packed row contents")
            seen.add(row_key)
            data = payload[offset:offset + length]
            offset += length
            require(records.setdefault(row_key, data) == data, "Conflicting original packed history record")
            bytes_read += length
        require(offset == len(payload) and len(records) <= 4096 and bytes_read <= 24 * 1024 * 1024,
                "Trailing data or unbounded expanded history")
    if any(kind == "PACKED_HISTORY" for _, kind, _ in stored):
        require(len(records) <= 4096 and bytes_read <= 24 * 1024 * 1024, "Unbounded expanded history")
    return records


def verify_archive(world_path, world):
    """Return verified raw history and both manifests' accounting, without a game JVM."""
    directory = world_path / ARCHIVE_PATH
    require(directory.resolve().is_relative_to(world_path.resolve()) and not directory.is_symlink(),
            "Archive path leaves the preserved world")
    files = list(directory.iterdir())
    require(len(files) <= MAX_SEGMENTS + 4 and all(p.is_file() and not p.is_symlink() for p in files),
            "Unexpected or unbounded archive file inventory")
    sizes = {p.name: p.stat().st_size for p in files}
    require(sum(sizes.values()) <= MAX_BYTES, "Original native archive byte quota exceeded")
    manifest_names = {"manifest.jsonl", "manifest.prev.jsonl"}
    require(manifest_names <= sizes.keys(), "Current and previous archive publications required")
    segment_names = {name for name in sizes if SEGMENT_NAME.fullmatch(name)}
    require(len(segment_names) <= MAX_SEGMENTS and sizes.keys() <= segment_names | manifest_names | {"writer.lock"},
            "Unknown, pending, or unbounded archive files")
    decoded = {name: read_segment(directory / name, world) for name in sorted(segment_names)}
    publications = {}
    for name in sorted(manifest_names):
        header, body, digest = read_manifest(directory / name, world)
        records = {}
        for ref in body["segments"]:
            segment = decoded[ref["digest"] + ".seg"]
            require(segment["owner"] == ref["owner"] and segment["bytes"] == ref["bytes"]
                    and len(segment["rows"]) == ref["records"], "Archive reference changed native segment identity")
            require(segment["ownerRevision"] <= header["owners"][ref["owner"]]
                    and segment["retentionGeneration"] <= header["retentionGeneration"], "Archive owner fence differs")
            for key, data in segment["rows"].items():
                require(records.setdefault(key, data) == data, f"Conflicting immutable UUID history: {key}")
        publications[name] = (header, body, digest, records)
    current, body, digest, history = publications["manifest.jsonl"]
    previous, previous_body, previous_digest, previous_history = publications["manifest.prev.jsonl"]
    require(previous["revision"] < current["revision"] and previous["retentionGeneration"] <= current["retentionGeneration"]
            and all(previous["owners"][owner] <= current["owners"][owner] for owner in OWNERS),
            "Archive publication fence regressed")
    require(all(history.get(key) == data for key, data in previous_history.items()),
            "Previous permitted UUID reference lost or changed in current archive")
    for segment in decoded.values():
        require(all(history.get(key) == data for key, data in segment["rows"].items()),
                "Unacknowledged or conflicting native archive segment")
    expanded = expand_history(history)
    return expanded, {"bytes": sum(sizes.values()), "segmentFiles": len(segment_names),
                     "storedRecords": len(history), "recordsByOwner": dict(Counter(key[0] for key in expanded)),
                     "current": {"revision": current["revision"], "sha256": digest, "segments": len(body["segments"])},
                     "previous": {"revision": previous["revision"], "sha256": previous_digest,
                                  "segments": len(previous_body["segments"])}}
