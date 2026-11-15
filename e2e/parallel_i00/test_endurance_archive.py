from hashlib import sha256
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from uuid import uuid4

from e2e.parallel_i00.endurance_archive import (ARCHIVE_PATH, ROW_HEADER, SEGMENT_HEADER, read_segment, verify_archive)


def encoded_segment(world, identifier, data, directory):
    raw = (SEGMENT_HEADER.pack(b"CCAHIST1", 1, world.bytes, 0, 1, 1, 1)
           + ROW_HEADER.pack(0, identifier.bytes, len(data)) + data)
    encoded = raw + sha256(raw).digest()
    digest = sha256(encoded).hexdigest()
    (directory / (digest + ".seg")).write_bytes(encoded)
    return {"digest": digest, "owner": "JOBS", "bytes": len(encoded), "records": 1, "createdEpochMillis": 1}


def encoded_manifest(path, world, revision, segments):
    body = json.dumps({"segments": segments, "slots": [], "migrations": []}).encode()
    header = {"world": str(world), "schema": 1, "revision": revision, "owners": {"JOBS": 1, "LEASES": 0},
              "retentionGeneration": 1, "records": len(segments), "sha256": sha256(body).hexdigest()}
    path.write_bytes(json.dumps(header).encode() + b"\n" + body)


class EnduranceArchiveTests(unittest.TestCase):
    def setUp(self):
        temp = TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.directory = self.root / ARCHIVE_PATH
        self.directory.mkdir(parents=True)
        self.world, self.identifier = uuid4(), uuid4()
        self.data = b'{"original":"native-byte-fixture"}'
        self.segment = encoded_segment(self.world, self.identifier, self.data, self.directory)
        self.current = self.directory / "manifest.jsonl"
        self.previous = self.directory / "manifest.prev.jsonl"
        encoded_manifest(self.current, self.world, 2, [self.segment])
        encoded_manifest(self.previous, self.world, 1, [self.segment])

    def test_reader_checks_both_reference_sets_and_exact_payload(self):
        records, metrics = verify_archive(self.root, str(self.world))
        self.assertEqual(records, {("JOBS", "HISTORY", str(self.identifier)): self.data})
        self.assertEqual(metrics["recordsByOwner"], {"JOBS": 1})
        self.assertEqual(metrics["current"]["revision"], 2)

    def test_digest_and_world_must_match_native_segment(self):
        segment = self.directory / (self.segment["digest"] + ".seg")
        with self.assertRaisesRegex(ValueError, "Wrong segment schema or world"):
            read_segment(segment, str(uuid4()))
        segment.write_bytes(segment.read_bytes() + b"unexpected")
        with self.assertRaisesRegex(ValueError, "filename/digest"):
            verify_archive(self.root, str(self.world))

    def test_replaced_uuid_payload_cannot_satisfy_previous_reference(self):
        replacement = encoded_segment(self.world, self.identifier, b"different", self.directory)
        encoded_manifest(self.current, self.world, 2, [replacement])
        with self.assertRaisesRegex(ValueError, "Previous permitted UUID reference lost or changed"):
            verify_archive(self.root, str(self.world))

    def test_unacknowledged_segment_cannot_supply_archive_progress(self):
        encoded_segment(self.world, uuid4(), self.data, self.directory)
        with self.assertRaisesRegex(ValueError, "Unacknowledged or conflicting"):
            verify_archive(self.root, str(self.world))
