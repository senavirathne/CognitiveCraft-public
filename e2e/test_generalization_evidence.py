"""Evidence policy tests only; these do not count as Minecraft gameplay scenarios."""
import importlib.util
import ast
import inspect
from pathlib import Path
import textwrap
import unittest

from generalization_support import AssessmentHarness, PLANK
from suite import Harness

spec = importlib.util.spec_from_file_location("generalization_verifier", Path(__file__).resolve().parents[1] / "scripts/verify-generalization.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class GeneralizationEvidenceTests(unittest.TestCase):
    def test_intake_retry_never_repeats_accepted_work_or_material_refusal(self):
        for feedback in ("accepted=true", "accepted=false reason=RESOURCE_MISSING"):
            with self.subTest(feedback=feedback):
                h = AssessmentHarness.__new__(AssessmentHarness)
                h.hashes = {"cognitivecraft:produce_oak_planks": "known"}
                h.citizens = {"PlayerA": "citizen"}
                calls = []
                h.command = lambda *args: calls.append(args) or "run=00000000-0000-0000-0000-000000000001 "+feedback
                h.wait = lambda *args: self.fail("Accepted work or a material refusal must never be retried")
                response = h.request_run(PLANK, 7)
                self.assertEqual(1, len(calls))
                self.assertEqual([], response["publicationRefusals"])

    def test_only_zero_work_publication_refusal_has_a_bounded_intake_retry(self):
        h = AssessmentHarness.__new__(AssessmentHarness)
        h.hashes = {"cognitivecraft:produce_oak_planks": "known"}
        h.citizens = {"PlayerA": "citizen"}
        prefix = "run=00000000-0000-0000-0000-000000000001 "
        answers = iter([prefix+"accepted=false reason=BUDGET_EXHAUSTED", prefix+"accepted=true"])
        h.command = lambda *args: next(answers)
        events = []
        h.event = lambda *args: events.append(args)
        def bounded(predicate, description, timeout):
            self.assertEqual(30, timeout)
            return predicate()
        h.wait = bounded
        response = h.request_run(PLANK, 7)
        self.assertTrue(response["accepted"])
        self.assertEqual([prefix+"accepted=false reason=BUDGET_EXHAUSTED"], response["publicationRefusals"])
        self.assertEqual(1, len(events))

    def test_assessment_methods_do_not_collide_with_base_harness_instance_state(self):
        tree = ast.parse(textwrap.dedent(inspect.getsource(Harness.__init__)))
        assigned = {n.attr for n in ast.walk(tree) if isinstance(n, ast.Attribute)
                    and isinstance(n.ctx, ast.Store) and isinstance(n.value, ast.Name)
                    and n.value.id == "self"}
        methods = {name for name, value in AssessmentHarness.__dict__.items() if callable(value)}
        self.assertFalse(assigned & methods, "A base instance field would hide an assessment method")

    def test_safe_refusal_is_boundary_success_and_never_gameplay_success(self):
        rows = [{"id": "A01", "category": "parameter", "composition": False, "status": "BLOCKED"},
                {"id": "B01", "category": "boundary", "composition": False, "status": "UNSUPPORTED", "evidence": {"boundaryCheck": "PASS"}},
                {"id": "D01", "category": "language", "composition": False, "status": "PASS", "evidence": {"safeClarification": True, "interpretationCorrect": False}}]
        metrics = verifier.metric_report(rows)
        self.assertEqual(0, metrics["supportedGameplay"]["correct"])
        self.assertEqual(1, metrics["supportedGameplay"]["requestedDenominator"])
        self.assertEqual(1, metrics["capabilityBoundaryCorrectness"]["correct"])
        self.assertEqual(0, metrics["naturalLanguageInterpretation"]["correct"])

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

    def test_gameplay_pass_without_original_final_server_observation_is_rejected(self):
        expected = {"id": "A01", "group": "oak", "category": "parameter", "requirement": "frozen", "expected": "effects", "composition": False}
        row = {**expected, "status": "PASS", "evidence": {"run": "present"}, "screenshots": [{"nativeClientFrame": True}]}
        with self.assertRaisesRegex(ValueError, "actual final server observation"):
            verifier.case_check(row, expected)

    def test_gameplay_pass_without_both_actual_client_observations_is_rejected(self):
        expected = {"id": "A13", "group": "facility", "category": "parameter", "requirement": "frozen", "expected": "effects", "composition": False}
        row = {**expected, "status": "PASS", "evidence": {"run": "present"},
               "physicalFinalState": {"time": 1}, "screenshots": [{"nativeClientFrame": True}],
               "connectedClientObservations": {"PlayerA": {"play": True, "singleplayer": False}}}
        with self.assertRaisesRegex(ValueError, "actual connected client observations"):
            verifier.case_check(row, expected)


if __name__ == "__main__": unittest.main()
