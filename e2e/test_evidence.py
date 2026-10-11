"""Concurrent evidence-file regressions; these fixtures are never gameplay proof."""
import json
from pathlib import Path
import tempfile
import unittest

from suite import completed_jsonl


class EvidenceReadTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "observations.jsonl"

    def test_defer_98304_byte_concurrent_append_then_read_completed_record(self):
        first = {"time": 1, "actors": [{"uuid": "actor", "pos": [0, 201, 0]}]}
        following = json.dumps({"payload": "x" * 100000}).encode()
        partial = following[:98304]
        self.path.write_bytes(json.dumps(first).encode() + b"\n" + partial)
        with self.assertRaises(json.JSONDecodeError):
            json.loads(partial)  # The former full-file split/parse reproduces the observed failure.
        self.assertEqual([first], completed_jsonl(self.path))
        with self.path.open("ab") as stream:
            stream.write(following[len(partial):] + b"\n")
        self.assertEqual([first, {"payload": "x" * 100000}], completed_jsonl(self.path))

    def test_completed_corrupt_record_is_not_silently_accepted(self):
        self.path.write_bytes(b'{"time":1}\n{"actors":\n')
        with self.assertRaises(json.JSONDecodeError):
            completed_jsonl(self.path)

    def test_partial_utf8_tail_is_deferred(self):
        first = {"name": "Ada"}
        self.path.write_bytes(json.dumps(first).encode() + b"\n" + '{"name":"\u96ea"}'.encode()[:-3])
        self.assertEqual([first], completed_jsonl(self.path))

    def test_every_newline_terminated_record_preserves_order_and_values(self):
        records = [{"time": 1}, {"time": 2, "actors": []}, {"time": 3, "quantity": 6}]
        self.path.write_bytes(b"".join(json.dumps(row).encode() + b"\n" for row in records))
        self.assertEqual(records, completed_jsonl(self.path))

    def test_missing_empty_and_unterminated_record_have_no_committed_evidence(self):
        self.assertEqual([], completed_jsonl(self.path))
        for data in (b"", b'{"time":1}'):
            self.path.write_bytes(data)
            self.assertEqual([], completed_jsonl(self.path))


if __name__ == "__main__":
    unittest.main()
