"""Regression controls for evidence transport and exact candidate identity."""
import json
from pathlib import Path
import tempfile
import unittest

from e2e.parallel_i00.campaign import PROFILE
from e2e.parallel_i00.campaign_review import HELD_OUT_PROFILE, aggregate, verify_review
from e2e.parallel_i00.frozen_campaign import controls_for, held_out, material_controls, original, shard
from e2e.parallel_i00.gameplay import ready_kernel, startup_failure


class NativeGameplayReviewTests(unittest.TestCase):
    def setUp(self):
        work = tempfile.TemporaryDirectory()
        self.addCleanup(work.cleanup)
        self.directory = Path(work.name)
        self.identity = {"source": "1" * 40, "tree": "2" * 40, "productionSha256": "3" * 64,
                         "run": "4", "attempt": "1"}

    def report(self, path, profile, definitions, **fields):
        path.parent.mkdir(parents=True, exist_ok=True)
        rows = [{"id": case["id"], "status": "PASS", "resultSha256": "5" * 64} for case in definitions]
        path.write_text(json.dumps({**self.identity, **fields, "profile": profile,
          "status": "PASS_INDEPENDENT_NATIVE_REVIEW", "requiredCases": len(rows), "cases": rows}))

    def test_missing_generation_counter_does_not_hide_failed_native_kernel(self):
        self.assertFalse(ready_kernel({"needleCalls": 0, "kernelLoading": True}))
        self.assertTrue(ready_kernel({"needleCalls": 0, "generationCalls": 0}))
        self.assertFalse(ready_kernel({"needleCalls": 0, "generationCalls": True}))
        log = ("[15:30:03] [Server thread/ERROR]: CognitiveCraft kernel disabled\n"
               "Caused by: java.lang.IllegalArgumentException: Owner registration\n"
               "  at knot//dev.aivillages.fabric.KernelSession.lambda$new$2(KernelSession.java:344)\n"
               "[15:30:03] [Server thread/INFO]: Done\n")
        failure = startup_failure(log)
        self.assertIn("Owner registration", failure)
        self.assertIn("KernelSession.java:344", failure)
        self.assertNotIn("Done", failure)

    def test_frozen_denominators_reject_duplicate_later_cases_and_other_attempts(self):
        frozen, later = original(), held_out()["cases"]
        campaign, extra = self.directory / "campaign", self.directory / "later/review.json"
        shards = [shard(frozen, index, 6) for index in range(6)]
        self.assertEqual([len(rows) for rows in shards], [62, 61, 57, 30, 62, 62])
        controls = material_controls()
        for index, cases in enumerate(shards):
            witnesses = [{"id": c["id"], "status": "PASS", "cohort": c["cohort"], "originalId": c["originalId"],
                          "nativeQuantities": c["expected"], "resultSha256": "6" * 64} for c in controls_for(cases, controls)]
            self.report(campaign / str(index) / "review.json", PROFILE, cases, shardIndex=index, shardCount=6,
                        materialControls=witnesses)
        self.report(extra, HELD_OUT_PROFILE, later)
        value = aggregate(campaign, 6, extra, self.identity)
        self.assertEqual(value["status"], "PASS_INDEPENDENT_CAMPAIGN_REVIEW")
        self.assertEqual(value["materialControls"]["verifiedCases"], 12)
        self.assertEqual([value["original"][key] for key in ("verifiedDevelopment", "verifiedHeldOut", "verifiedHistoricalGaps")], [316, 18, 42])
        self.report(extra, HELD_OUT_PROFILE, later[:-1] + later[:1])
        self.assertEqual(aggregate(campaign, 6, extra, self.identity)["laterHeldOut"]["status"], "FAIL_REVIEW")
        self.report(extra, HELD_OUT_PROFILE, later, attempt="2")
        self.assertEqual(aggregate(campaign, 6, extra, self.identity)["status"], "FAIL_REQUIRED_CAMPAIGN")
        self.report(extra, HELD_OUT_PROFILE, later)
        path = campaign / "0/review.json"
        incomplete = json.loads(path.read_text())
        incomplete["materialControls"].pop()
        path.write_text(json.dumps(incomplete))
        self.assertEqual(aggregate(campaign, 6, extra, self.identity)["status"], "FAIL_REQUIRED_CAMPAIGN")

    def test_an_original_positive_recipe_refusal_cannot_pass(self):
        case = next(case for case in original() if case["mode"] == "recipe" and not case.get("dynamic"))
        path = self.directory / "review.json"
        self.report(path, PROFILE, [case])
        value = json.loads(path.read_text())
        value["cases"][0]["status"] = "PASS_EXPECTED_REFUSAL"
        path.write_text(json.dumps(value))
        with self.assertRaises(AssertionError):
            verify_review(path, PROFILE, [case], self.identity)
