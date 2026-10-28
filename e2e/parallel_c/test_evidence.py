from pathlib import Path
import tempfile
import unittest

from common import EvidenceError, artifact, digest
from verify import integration_claims, metric, require_frames, verify_jar, xml_cases


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.root = Path(self.temp.name)
    def tearDown(self): self.temp.cleanup()
    def xml(self, name, body, tests=1, failures=0, errors=0, skipped=0):
        p = self.root / name
        p.write_text(f'<testsuite tests="{tests}" failures="{failures}" errors="{errors}" skipped="{skipped}">{body}</testsuite>')
        return p
    def test_changed_jar_is_rejected(self):
        p = self.root / "production.jar"; p.write_bytes(b"original binary")
        sha = digest(p); verify_jar(p, sha); p.write_bytes(b"changed binary")
        with self.assertRaisesRegex(EvidenceError, "changed production JAR"):
            verify_jar(p, sha)
    def test_duplicate_xml_case_is_rejected(self):
        body = '<testcase classname="dev.Test" name="caseA"/>'
        a = self.xml("one.xml", body); b = self.xml("two.xml", body)
        with self.assertRaisesRegex(EvidenceError, "duplicate XML case"):
            xml_cases([a, b])
    def test_nonexistent_native_frame_is_rejected(self):
        with self.assertRaisesRegex(EvidenceError, "missing artifact"):
            require_frames(self.root, [{"id": "C-test-native", "frames": ["server/snapshot.json"]}], {"server/snapshot.json": "a" * 64})
    def test_existing_frame_and_pass_flag_do_not_qualify_native(self):
        p = self.root / "frame.json"; p.write_text('{"pass":true}')
        with self.assertRaisesRegex(EvidenceError, "native oracle not implemented"):
            require_frames(self.root, [{"id": "C-test-native", "frames": ["frame.json"]}], {"frame.json": digest(p)})
    def test_xml_counters_are_recomputed(self):
        p = self.xml("counts.xml", '<testcase classname="C" name="x"/>', tests=2)
        with self.assertRaisesRegex(EvidenceError, "count mismatch"): xml_cases([p])
    def test_failure_and_skip_are_not_passes(self):
        p = self.xml("outcomes.xml", '<testcase classname="C" name="failed"><failure/></testcase><testcase classname="C" name="skipped"><skipped/></testcase>', tests=2, failures=1, skipped=1)
        m = metric(xml_cases([p]), 2)
        self.assertEqual((m["executed"], m["passed"], m["failed"], m["inconclusive"]), (2, 0, 1, 1))
    def test_artifact_path_escape_is_rejected(self):
        with self.assertRaisesRegex(EvidenceError, "unsafe artifact"):
            artifact(self.root, "../outside.json")
    def test_xml_entities_are_rejected(self):
        p = self.root / "entity.xml"; p.write_text('<!DOCTYPE test [<!ENTITY ex SYSTEM "file:///etc/hostname">]><testsuite/>')
        with self.assertRaisesRegex(EvidenceError, "entities"): xml_cases([p])
    def test_fabricated_settlement_is_rejected(self):
        with self.assertRaisesRegex(EvidenceError, "fabricate integrated A"):
            integration_claims({"original_root_id": None, "original_owner_id": None, "a_settlement": "SETTLED"})
    def test_injected_root_or_owner_is_rejected(self):
        for field in ("original_root_id", "original_owner_id"):
            with self.assertRaisesRegex(EvidenceError, "fabricate integrated A"):
                integration_claims({field: "00000000-0000-0000-0000-000000000001"})
        integration_claims({"original_root_id": None, "original_owner_id": None, "a_settlement": None})
