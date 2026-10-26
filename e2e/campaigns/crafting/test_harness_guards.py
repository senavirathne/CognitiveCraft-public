"""Adversarial evaluator checks. Synthetic fixtures here are never gameplay proof."""
from __future__ import annotations

from collections import Counter
from copy import deepcopy
import hashlib
import importlib.util
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

E2E = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(E2E))
from campaigns.crafting.test_campaign import recipe_fixture
from campaigns.crafting.generator import LOG, OAK, STICK, AXE, TABLE, blueprint
from campaigns.crafting.recipe_model import rules, demands
from campaigns.crafting.invariants import (composition_balance, classify, staging_verification,
                                          interruption_verification, physical_custody)
from campaigns.crafting.execution import shortage
from campaigns.crafting.evidence import evidence_path, record_problems, log_facts, compact_result
from campaigns.crafting.oracle import requirements
from campaigns.crafting.run import log_slices, require_case_fence, CampaignCapacityBlocked
from campaigns.crafting.scheduling import interleave

spec = importlib.util.spec_from_file_location("crafting_verifier_checks", E2E.parent / "scripts/campaign-crafting/verify.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class RecipeDataTests(unittest.TestCase):
    def test_live_yield_changes_are_not_fixed_ratio_expectations(self):
        recipes = recipe_fixture()
        recipes[OAK]["count"], recipes[STICK]["count"] = 5, 3
        before, after = Counter({LOG: 1}), Counter({AXE: 1, STICK: 1})
        self.assertTrue(composition_balance(before, after, recipes)[0])
        self.assertFalse(composition_balance(before, after, recipe_fixture())[0])

    def test_live_ingredient_counts_determine_the_resource_bill(self):
        recipes = recipe_fixture()
        recipes[TABLE]["slots"] = [0, 0]
        result = demands(rules(recipes), [{"item": TABLE, "quantity": 2}], {OAK: 4})
        self.assertTrue(result["feasible"])
        self.assertEqual(result["craftBatches"], {TABLE: 2})

    def test_make_never_credits_preexisting_target_items(self):
        result = demands(rules(recipe_fixture()), [{"item": AXE, "quantity": 1}], {AXE: 1})
        self.assertFalse(result["feasible"])
        self.assertEqual(result["craftBatches"][AXE], 1)

    def test_supply_can_use_stock_without_manufacturing(self):
        result = demands(rules(recipe_fixture()), [{"item": OAK, "quantity": 5, "consumeExisting": True}], {OAK: 8})
        self.assertTrue(result["feasible"])
        self.assertEqual(result["craftBatches"], {})
        self.assertEqual(result["mathematicalSurplus"], {OAK: 3})

    def test_supplied_item_placement_cannot_borrow_a_manufacturing_adapter(self):
        result = demands(rules(recipe_fixture()), [{"item": TABLE, "quantity": 1,
                         "consumeExisting": True, "allowMake": False}], {OAK: 4})
        self.assertEqual(result["missingRaw"], {TABLE: 1})
        self.assertFalse(result["feasible"])

    def test_cycle_cannot_be_used_to_prove_material_conservation(self):
        recipes = recipe_fixture(); recipes[OAK]["ingredients"] = [[STICK]]
        with self.assertRaisesRegex(ValueError, "Cyclic"):
            composition_balance(Counter(), Counter(), recipes)

    def test_quantity_and_yield_must_be_positive_integers(self):
        probe = {"matches": True, "remainderCount": 0, "count": 4, "grid": [LOG], "output": OAK}
        for value in (0, -1, True, 1.5):
            with self.subTest(value=value), self.assertRaises(ValueError):
                requirements(probe, value)
        probe["count"] = 0
        with self.assertRaises(ValueError):
            requirements(probe, 1)

    def test_resource_expectation_uses_observed_logs_not_generator_hint(self):
        case = {"mode": "compose", "output": AXE, "quantity": 1, "worldLogs": 8}
        state = {"containers": {"70,208,69": {}}, "blocks": {}}
        self.assertFalse(shortage(case, state, [70,208,69], recipe_fixture())["feasible"])
        state["blocks"] = {"70,208,65": "Block{minecraft:oak_log}", "71,208,65": "Block{minecraft:oak_log}"}
        observed = shortage(case, state, [70,208,69], recipe_fixture())
        self.assertTrue(observed["feasible"])
        self.assertEqual(observed["observedAcquisitionStock"], {LOG: 2})

    def test_blueprint_matching_is_measured_in_the_world(self):
        case = {"mode": "blueprint", "body": blueprint(5), "completed": 5, "rotation": 0}
        state = {"containers": {"70,208,69": {OAK: 8}}, "blocks": {}}
        measured = shortage(case, state, [70,208,69], recipe_fixture(), blueprint_origin=[76,208,75])
        self.assertEqual(measured["existingBlockContributions"], 0)
        self.assertEqual(measured["outstanding"], 5)
        state["blocks"]["76,208,75"] = "Block{minecraft:oak_planks}"
        measured = shortage(case, state, [70,208,69], recipe_fixture(), blueprint_origin=[76,208,75])
        self.assertEqual(measured["outstanding"], 4)


class OutcomeTests(unittest.TestCase):
    def test_valid_staging_surplus_is_not_a_false_completion(self):
        projection = {"outstanding": 5, "newOutputRequired": 0}
        self.assertTrue(staging_verification(projection, 8, {"quantity": 5, "currentlyAllocated": True}, [],
                                            {"attempts": [], "fulfilled": 0})[0])

    def test_staging_rejects_wrong_allocation_extra_production_or_make_credit(self):
        projection = {"outstanding": 5, "newOutputRequired": 0}
        base = {"quantity": 5, "currentlyAllocated": True}
        self.assertFalse(staging_verification(projection, 8, {**base, "quantity": 4}, [], {"fulfilled": 0})[0])
        self.assertFalse(staging_verification(projection, 8, base, [], {"fulfilled": 5})[0])
        self.assertFalse(staging_verification(projection, 8, base,
                         [{"stage": "DEPOSIT", "quantity": 1, "item": {"id": OAK}}], {"fulfilled": 0})[0])

    def test_navigation_timeout_on_equivalent_material_is_not_a_material_gap(self):
        self.assertEqual(classify(feasible=True, completed=False, equivalent=True, conservation=True,
                                  delivered=0, requested=1, refusal_reason="ACTION_TIMEOUT"), "FAIL_GAMEPLAY")

    def test_withdrawn_materials_do_not_count_as_a_zero_effect_refusal(self):
        self.assertEqual(classify(feasible=True, completed=False, equivalent=True, conservation=True,
                         delivered=0, requested=1, refusal_reason="RESOURCE_MISSING", physical_effects=True), "FAIL_GAMEPLAY")

    def test_successful_old_execution_cannot_pass_as_interrupted_recovery(self):
        result = {"completed": True, "views": [{"state": "SUCCEEDED", "reason": None}]}
        self.assertFalse(interruption_verification({"kind": "job"}, result, "restart")[0])

    def test_restart_requires_the_actual_interrupted_owner_outcome(self):
        interrupted = {"completed": False, "views": [{"marker": {"phase": "INTERRUPTED", "reason": "INTERRUPTED"}}]}
        self.assertTrue(interruption_verification({"kind": "run"}, interrupted, "restart")[0])
        interrupted["views"][0]["marker"]["reason"] = "RESOURCE_MISSING"
        self.assertFalse(interruption_verification({"kind": "run"}, interrupted, "restart")[0])

    def test_same_totals_cannot_hide_a_late_transfer_after_cancellation(self):
        state = {"containers": {"source": {OAK: 4}, "target": {}}, "actors": [], "blocks": {}, "drops": [], "knownPlayers": {}}
        moved = deepcopy(state); moved["containers"] = {"source": {}, "target": {OAK: 4}}
        self.assertNotEqual(physical_custody(state), physical_custody(moved))


class EvidenceTests(unittest.TestCase):
    def test_passing_marker_without_independent_runtime_facts_is_rejected(self):
        problems = record_problems({"case": {"id": "P1-fixture", "family": "P1", "mode": "recipe"},
                                   "status": "PASS", "attempted": True, "terminalObserved": True})
        self.assertTrue(any("exact identity" in problem for problem in problems))
        self.assertTrue(any("client observation" in problem for problem in problems))

    def test_unknown_pass_classification_is_not_a_success(self):
        problems = record_problems({"case": {"id": "P1-fixture", "family": "P1"}, "status": "PASS_FAKE",
                                   "attempted": True, "terminalObserved": True})
        self.assertIn("Unknown case classification", problems)

    def test_evidence_cannot_escape_through_paths_or_symlinks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "run"; root.mkdir()
            for name in ("../outside", "/absolute", "x\\y"):
                with self.subTest(name=name), self.assertRaises(ValueError):
                    evidence_path(root, name)
            (root / "link").symlink_to(Path(directory))
            with self.assertRaises(ValueError):
                evidence_path(root, "link/anything")

    def test_client_command_and_first_failed_primitive_are_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trace"
            records = [json.dumps({"kind": "action", "data": {"action": "command", "text": "/fixture-command"}}),
                       'CRAFTING_PRIMITIVE_TRACE ' + json.dumps({"time": 1000, "kind": "primitive-start",
                            "value": {"correlation": {"runId": "run"}, "primitive": {"id": "craft_sticks"}}}),
                       'CRAFTING_PRIMITIVE_TRACE ' + json.dumps({"time": 2000, "kind": "primitive-terminal",
                                                             "value": {"receipt": {"reason": "TARGET_UNAVAILABLE"}}})]
            path.write_text("\n".join(records) + "\n")
            facts = log_facts(path)
            self.assertEqual(facts["commands"], ["/fixture-command"])
            self.assertEqual(facts["firstFailedPrimitive"]["reason"], "TARGET_UNAVAILABLE")
            self.assertEqual(facts["primitiveStarts"], 1)

    def test_minecraft_stdout_prefix_does_not_hide_the_failed_primitive(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "server.log"
            trace = [
                {"time": 1, "kind": "primitive-start", "value": {"correlation": {"runId": "run"},
                    "primitive": {"id": "craft_sticks"}, "arguments": {"fixture": "input"}}},
                {"time": 2, "kind": "primitive-handle", "value": {"handle": {"id": "handle", "runId": "run"},
                    "primitive": {"id": "craft_sticks"}}},
                {"time": 3, "kind": "primitive-terminal", "value": {"handle": {"id": "handle", "runId": "run"},
                    "receipt": {"reason": "RESOURCE_MISSING"}}}]
            path.write_text("".join("[Server thread/INFO] [STDOUT]: CRAFTING_PRIMITIVE_TRACE " + json.dumps(row) + "\n" for row in trace))
            facts = log_facts(path)
            failure = facts["firstFailedPrimitive"]
            self.assertEqual(failure["operation"]["primitive"]["id"], "craft_sticks")
            self.assertEqual(failure["operation"]["start"]["arguments"], {"fixture": "input"})

    def test_corrupt_completed_client_event_is_not_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "events"
            path.write_text('{"kind":"action"}\n{"invalid":\n')
            with self.assertRaises(json.JSONDecodeError):
                log_facts(path, strict_events=True)

    def test_collector_keeps_snapshot_references_without_loading_them_into_the_report(self):
        row = {"case": {"id": "P1-fixture"}, "status": "FAIL_GAMEPLAY", "physicalDelta": {OAK: 4},
               "before": {"blocks": {}, "largeSnapshot": "x"*100000}, "after": {"blocks": {}}}
        compact = compact_result(row)
        self.assertNotIn("before", compact)
        self.assertNotIn("after", compact)
        self.assertTrue(compact["physicalEffectObserved"])

    def test_log_slices_capture_a_fixed_end_during_a_concurrent_append(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); folder = root / "cases"; folder.mkdir()
            source = root / "growing.log"; source.write_bytes(b"original\n")
            original_open = Path.open
            appended = False
            def append_on_open(path, mode="r", *args, **kwargs):
                nonlocal appended
                if path == source and mode == "rb" and not appended:
                    appended = True
                    with original_open(source, "ab") as stream:
                        stream.write(b"later\n")
                return original_open(path, mode, *args, **kwargs)
            with patch.object(Path, "open", append_on_open):
                entries = log_slices(root, folder, {"growing.log": 0})
            self.assertEqual((root / entries[0]["slice"]).read_bytes(), b"original\n")
            self.assertEqual(entries[0]["endByte"], len(b"original\n"))

    def test_untracked_production_addition_breaks_the_frozen_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); path = root / "core/src/main/java/Frozen.java"
            path.parent.mkdir(parents=True); path.write_text("baseline")
            name = str(path.relative_to(root))
            frozen = {"files": {name: hashlib.sha256(path.read_bytes()).hexdigest()}}
            self.assertEqual(verifier.production_mismatches(root, frozen, [name]), [])
            extra = path.with_name("Untracked.java"); extra.write_text("untracked")
            self.assertEqual(verifier.production_mismatches(root, frozen, [name]), [str(extra.relative_to(root))])

    def test_absent_runtime_recipe_catalogue_cannot_complete_a_campaign(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(SystemExit), contextlib.redirect_stdout(io.StringIO()):
                verifier.collect(root)
            report = json.loads((root / "assessment.json").read_text())
            self.assertFalse(report["executionComplete"])
            self.assertIn("No actual running-game recipe catalogue was preserved", report["evidenceProblems"])

    def test_malformed_case_still_produces_an_explicit_incomplete_report(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "campaign.json").write_text(json.dumps({"status": "RECORDED", "mode": "smoke", "results": [
                {"id": "P1-fixture", "status": "PASS", "path": "invalid.json"}]}))
            (root / "invalid.json").write_text('{"case":')
            with self.assertRaises(SystemExit), contextlib.redirect_stdout(io.StringIO()):
                verifier.collect(root)
            report = json.loads((root / "assessment.json").read_text())
            self.assertFalse(report["executionComplete"])
            self.assertTrue(any("cannot be verified for P1-fixture" in p for p in report["evidenceProblems"]))


class SchedulingTests(unittest.TestCase):
    def test_confirmed_capacity_block_stops_submission_instead_of_contaminating_recipe_results(self):
        with self.assertRaisesRegex(CampaignCapacityBlocked, "STORAGE_LIMIT_REACHED"):
            require_case_fence({"status": "BLOCKED_CAPACITY", "cleanupFenced": True}, "P2-case")

    def test_no_reproduction_may_follow_an_unfenced_case(self):
        with self.assertRaisesRegex(RuntimeError, "Case isolation"):
            require_case_fence({"status": "FAIL_GAMEPLAY", "cleanupFenced": False}, "P2-reproduction")

    def test_each_family_is_visited_before_a_second_case_in_any_family(self):
        cases = [{"id": f"P{i}-{j}", "family": f"P{i}"} for i in range(1, 9) for j in range(3)]
        result = interleave(cases)
        self.assertEqual({case["family"] for case in result[:8]}, {f"P{i}" for i in range(1, 9)})
        self.assertEqual(len(result), len(cases))

    def test_control_precedes_variant_without_duplicate_execution(self):
        variant = {"id": "P2-variant", "family": "P2", "metamorphicControlId": "P2-control"}
        control = {"id": "P2-control", "family": "P2"}
        original = [variant, control]
        result = interleave(original)
        self.assertEqual([case["id"] for case in result], ["P2-control", "P2-variant"])
        self.assertEqual(original, [variant, control])

    def test_unreachable_adapters_do_not_take_all_early_family_slots(self):
        missing = {"id": "P1-missing", "family": "P1", "missingAdapter": True}
        reachable = {"id": "P1-supported", "family": "P1"}
        self.assertEqual(interleave([missing, reachable])[0], reachable)

    def test_duplicate_or_cyclic_control_identities_are_rejected(self):
        row = {"id": "P2-case", "family": "P2"}
        with self.assertRaises(ValueError):
            interleave([row, row])
        row["metamorphicControlId"] = row["id"]
        with self.assertRaises(ValueError):
            interleave([row])


if __name__ == "__main__":
    unittest.main()
