"""Evidence policy tests only; these do not count as Minecraft gameplay scenarios."""
import importlib.util
import ast
import inspect
from pathlib import Path
import textwrap
import unittest

from generalization_support import AssessmentHarness
from suite import Harness

spec = importlib.util.spec_from_file_location("generalization_verifier", Path(__file__).resolve().parents[1] / "scripts/verify-generalization.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class GeneralizationEvidenceTests(unittest.TestCase):
    def test_assessment_methods_do_not_collide_with_base_harness_instance_state(self):
        tree = ast.parse(textwrap.dedent(inspect.getsource(Harness.__init__)))
        assigned = {n.attr for n in ast.walk(tree) if isinstance(n, ast.Attribute)
                    and isinstance(n.ctx, ast.Store) and isinstance(n.value, ast.Name)
                    and n.value.id == "self"}
        methods = {name for name, value in AssessmentHarness.__dict__.items() if callable(value)}
        self.assertFalse(assigned & methods, "A base instance field would hide an assessment method")

    def test_safe_refusal_is_boundary_success_and_never_gameplay_success(self):
        rows = [{"id": "A01", "category": "parameter", "composition": False, "status": "BLOCKED"},
                {"id": "B01", "category": "boundary", "composition": False, "status": "UNSUPPORTED", "evidence": {"boundaryCheck": "PASS"}}]
        metrics = verifier.metric_report(rows)
        self.assertEqual(0, metrics["supportedGameplay"]["correct"])
        self.assertEqual(1, metrics["supportedGameplay"]["requestedDenominator"])
        self.assertEqual(1, metrics["capabilityBoundaryCorrectness"]["correct"])

    def test_blocked_and_unexecuted_cases_remain_in_requested_denominator(self):
        rows = [{"id": "A01", "category": "parameter", "composition": False, "status": "PASS"},
                {"id": "A02", "category": "parameter", "composition": False, "status": "NOT_EXECUTED"},
                {"id": "A03", "category": "parameter", "composition": False, "status": "BLOCKED"}]
        rate = verifier.metric_report(rows)["supportedGameplay"]
        self.assertEqual((1, 3, 1), (rate["correct"], rate["requestedDenominator"], rate["executed"]))

    def test_unknown_and_changed_case_labels_are_rejected(self):
        expected = {"id": "A01", "group": "oak", "category": "parameter", "requirement": "frozen", "expected": "effects", "composition": False}
        row = {**expected, "status": "UNKNOWN", "reason": "x"}
        with self.assertRaises(ValueError): verifier.case_check(row, expected)
        row.update(status="FAIL", requirement="easier replacement")
        with self.assertRaises(ValueError): verifier.case_check(row, expected)

    def test_unsupported_cannot_be_relabelled_as_gameplay_pass(self):
        expected = {"id": "B01", "group": "oak", "category": "boundary", "requirement": "frozen", "expected": "refusal", "composition": False}
        row = {**expected, "status": "PASS", "evidence": {"boundaryCheck": "PASS"}, "screenshots": [{"nativeClientFrame": True}]}
        with self.assertRaises(ValueError): verifier.case_check(row, expected)


if __name__ == "__main__": unittest.main()
