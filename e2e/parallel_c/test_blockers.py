"""C02-P01: actual compiled Java planner/projection, row permutations and private-data controls."""
import copy
import importlib.util
import itertools
import json
import os
from pathlib import Path
import subprocess
import unittest

from common import HERE, ROOT
from blocker_runtime import dependency


class BlockerTests(unittest.TestCase):
    def probe(self, **snapshot):
        java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
        classpath = os.pathsep.join(str(ROOT / p) for p in ("core/build/classes/java/main", "core/build/classes/java/test"))
        # Use the exact Gson dependency already used by the frozen module build; no new test framework.
        classpath += os.pathsep + dependency()["path"]
        result = subprocess.run([java, "-cp", classpath, "dev.aivillages.core.kernel.interaction.BlockerProbe",
                                 json.dumps(snapshot, ensure_ascii=False, separators=(",", ":"))],
                                capture_output=True, text=True, check=True, timeout=20)
        frame = json.loads(result.stdout); self.assertEqual("SYNTHETIC_BLOCKER", frame["tier"])
        spec = importlib.util.spec_from_file_location("frozen_blocker_contract", HERE / "contracts/verify_contract.py")
        verifier = importlib.util.module_from_spec(spec); spec.loader.exec_module(verifier)
        schema = json.loads((HERE / "contracts/contract.schema.json").read_text())
        for field, kind in (("blocker", "BlockerEvidence"), ("request", "AssistanceRequest")):
            if frame[field] is not None:
                verifier.shape({"schema": "cc.cd/1.0.0", "kind": kind, "body": frame[field]}, schema, schema)
                verifier.semantics(kind, frame[field])
        return frame

    def test_row_and_stock_permutations_preserve_the_exact_public_result(self):
        rows = [dict(id=51, relevant=False, authorized=False, fresh=False, code="UNAUTHORIZED", label="Private Pantry", item="minecraft:diamond", count=913),
                dict(id=52, relevant=True, authorized=True, fresh=True, code=None),
                dict(id=53, relevant=False, authorized=True, fresh=True, code="TARGET_UNAVAILABLE")]
        expected = self.probe(rows=rows)
        self.assertEqual(2, expected["blocker"]["requirements"][0]["missing"])
        for permutation in itertools.permutations(rows):
            for reverse in (False, True):
                self.assertEqual(expected, self.probe(rows=permutation, reverse_stock=reverse))

    def test_redacting_or_changing_private_item_name_and_count_cannot_leak(self):
        row = dict(id=61, relevant=False, authorized=False, fresh=False, code="UNAUTHORIZED", label="Hidden Cache 913", item="minecraft:diamond", count=913)
        expected = self.probe(rows=[row]); serialized = json.dumps(expected, sort_keys=True)
        self.assertNotIn("Hidden Cache", serialized); self.assertNotIn("diamond", serialized)
        changed = copy.deepcopy(row); changed.update(label="Never disclose", item="minecraft:netherite_ingot", count=777777)
        redacted = {k: row[k] for k in ("id", "relevant", "authorized", "fresh", "code")}
        self.assertEqual(expected, self.probe(rows=[changed])); self.assertEqual(expected, self.probe(rows=[redacted]))

    def test_private_relevant_row_wins_and_removes_root_storage_and_requirements(self):
        rows = [dict(id=71, relevant=True, authorized=False, fresh=True, code="UNAUTHORIZED", label="Hidden Pantry", item="minecraft:diamond", count=913),
                dict(id=72, relevant=True, authorized=True, fresh=True, code="UNSUPPORTED_EFFECT"),
                dict(id=73, relevant=True, authorized=True, fresh=True, code="DESTINATION_FULL")]
        for permutation in itertools.permutations(rows):
            frame = self.probe(rows=permutation); self.assertIsNone(frame["request"])
            self.assertEqual("UNAUTHORIZED", frame["blocker"]["code"]); self.assertEqual("REDACTED", frame["blocker"]["visibility"])
            self.assertEqual([], frame["blocker"]["requirements"]); self.assertIsNone(frame["blocker"]["storage_id"]); self.assertIsNone(frame["blocker"]["activity"])

    def test_unknown_stale_unsupported_and_full_are_distinct_without_workarounds(self):
        cases = [(dict(complete=False), "UNKNOWN_WORLD_STATE"), (dict(current_tick=103), "STALE_REFERENCE"),
                 (dict(primitive_supported=False), "UNSUPPORTED_EFFECT"), (dict(target_capacity=1), "DESTINATION_FULL"),
                 (dict(recipe_version_two=True), "DEPENDENCY_NOT_QUALIFIED")]
        for snapshot, expected in cases:
            frame = self.probe(**snapshot); self.assertEqual(expected, frame["blocker"]["code"])
            self.assertIsNone(frame["request"]); self.assertEqual([], frame["blocker"]["requirements"])

    def test_multiple_material_counts_and_request_root_come_from_actual_planner(self):
        frame = self.probe(planks=1, sticks=0)
        self.assertEqual([("minecraft:oak_planks", 2), ("minecraft:stick", 2)], [(r["item"]["registry_id"], r["missing"]) for r in frame["blocker"]["requirements"]])
        self.assertEqual(frame["request"]["activity"], frame["request"]["blocker"]["activity"])
        self.assertLessEqual(frame["request"]["expires_ms"], frame["request"]["activity"]["absolute_deadline_ms"])

    def test_original_budget_deadline_and_returned_root_cannot_be_replaced(self):
        cases = [(dict(used_effects=999), "BUDGET_OR_DEADLINE_EXCEEDED"), (dict(clock_ms=100000), "BUDGET_OR_DEADLINE_EXCEEDED"),
                 (dict(prior_plan=True, used_replans=5), "BUDGET_OR_DEADLINE_EXCEEDED"),
                 (dict(returned_root="00000000-0000-0000-0000-000000009999"), "UNAUTHORIZED")]
        for snapshot, expected in cases:
            frame = self.probe(**snapshot); self.assertEqual(expected, frame["blocker"]["code"]); self.assertIsNone(frame["request"])
