import copy
from pathlib import Path
import unittest

from common import EvidenceError, ROOT, read
from predecessor import validate_records, verify_predecessor


class PredecessorTests(unittest.TestCase):
    def records(self):
        root = ROOT.parent / "CognitiveCraft-Documentation-C/docs/implementation/parallel-c"
        # Read the immutable Git objects so later stage reports cannot replace C00 evidence.
        import json, subprocess
        def pinned(path):
            return json.loads(subprocess.check_output(["git", "-C", str(root), "show",
                "d2ed91d0bcea7c2864e3cdc0511e9ecfdaeff3ab:docs/implementation/parallel-c/" + path]))
        return pinned("stage-manifest.json"), pinned("evidence/C00-20261030/qualified.json")

    def test_actual_qualified_predecessor_has_exact_ancestry_and_digests(self):
        result = verify_predecessor(ROOT.parent / "CognitiveCraft-Documentation-C")
        self.assertEqual("PASS", result["status"])

    def test_changed_predecessor_source_jar_or_failed_gate_is_rejected(self):
        manifest, oracle = self.records()
        for field, value in (("source_sha", "0" * 40), ("jar_sha256", "0" * 64), ("gate_status", "FAIL")):
            changed = copy.deepcopy(oracle); changed[field] = value
            with self.assertRaises(EvidenceError): validate_records(manifest, changed)

    def test_missing_predecessor_case_or_invented_native_credit_is_rejected(self):
        manifest, oracle = self.records()
        changed = copy.deepcopy(oracle); changed["counts"]["unit"]["executed"] -= 1
        with self.assertRaises(EvidenceError): validate_records(manifest, changed)
        changed = copy.deepcopy(oracle); changed["native_executed"] = 1
        with self.assertRaises(EvidenceError): validate_records(manifest, changed)
