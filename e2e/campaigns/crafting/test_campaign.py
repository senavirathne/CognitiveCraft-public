"""Evaluator unit checks only. None of these are Minecraft gameplay evidence."""
from __future__ import annotations

from collections import Counter
import importlib.util
from itertools import combinations, product
import json
from pathlib import Path
import random
import sys
import tempfile
import unittest
from zipfile import ZipFile

E2E = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(E2E))
from campaigns.crafting.generator import generate, pairwise, select, LOG, OAK, BIRCH, STICK, AXE, TABLE
from campaigns.crafting.invariants import recipe_balance, composition_balance, classify, totals
from campaigns.crafting.oracle import candidates, requirements, rotated_cells


def recipe_fixture():
    """Synthetic data deliberately includes an unfamiliar plank identifier."""
    woods = ["oak", "birch", "spruce", "jungle", "acacia", "dark_oak", "mangrove", "cherry",
             "bamboo", "crimson", "warped", "pale_oak", "unknown_future_wood"]
    planks = ["minecraft:" + w + "_planks" for w in woods]
    rows = {
        STICK: {"ingredients": [planks], "slots": [0, 0], "width": 1, "height": 2, "count": 4, "output": STICK},
        TABLE: {"ingredients": [planks], "slots": [0, 0, 0, 0], "width": 2, "height": 2, "count": 1, "output": TABLE},
        AXE: {"ingredients": [planks, [STICK]], "slots": [0, 0, 0, 1, -1, 1], "width": 2, "height": 3, "count": 1, "output": AXE},
    }
    for w in woods:
        output = "minecraft:" + w + "_planks"
        rows[output] = {"ingredients": [["minecraft:" + w + "_log", "minecraft:stripped_" + w + "_log"]],
                        "slots": [0], "width": 1, "height": 1, "count": 4, "output": output}
    return rows


class GeneratorTests(unittest.TestCase):
    def setUp(self):
        self.recipes = recipe_fixture()
        self.cases = generate(self.recipes, 20261024)

    def test_seed_replays_every_parameter(self):
        self.assertEqual(self.cases, generate(self.recipes, 20261024))
        self.assertNotEqual(self.cases, generate(self.recipes, 12))

    def test_all_families_have_twelve_distinct_variations(self):
        counts = Counter(c["family"] for c in self.cases)
        self.assertEqual(set(counts), {"P" + str(i) for i in range(1, 9)})
        self.assertTrue(all(n >= 12 for n in counts.values()))
        self.assertEqual(len(self.cases), len({c["id"] for c in self.cases}))

    def test_exhaustive_material_substitution_is_not_a_fixed_wood_list(self):
        for key in [AXE, TABLE, STICK]:
            rows = [c for c in self.cases if c.get("recipe") == key]
            for material in self.recipes[key]["ingredients"][0]:
                self.assertTrue(any(material in c["grid"] and not c.get("mixed") for c in rows))
                self.assertTrue(any(material in c["grid"] and c.get("mixed") for c in rows))

    def test_all_log_tag_alternatives_are_generated(self):
        for key, recipe in self.recipes.items():
            if not key.endswith("_planks"):
                continue
            for material in recipe["ingredients"][0]:
                self.assertTrue(any(c.get("recipe") == key and material in c["grid"] for c in self.cases))

    def test_pairwise_covers_every_parameter_pair(self):
        dimensions = {"quantity": [1, 7, 13], "actor": [0, 1], "location": [0, 1, 2], "layout": ["a", "b"]}
        rows = pairwise(dimensions, random.Random(13))
        for a, b in combinations(dimensions, 2):
            self.assertEqual({(r[a], r[b]) for r in rows}, set(product(dimensions[a], dimensions[b])))

    def test_held_out_ids_are_distinct_and_replayable(self):
        held = generate(self.recipes, 123, held_out=True)
        self.assertTrue(all(c["heldOut"] for c in held))
        self.assertFalse({c["id"] for c in held} & {c["id"] for c in self.cases})

    def test_smoke_includes_all_families(self):
        smoke = select(self.cases, "smoke")
        self.assertEqual({c["family"] for c in smoke}, {c["family"] for c in self.cases})
        self.assertLess(len(smoke), len(self.cases))


class InvariantTests(unittest.TestCase):
    def setUp(self):
        self.expected = {"batches": 2, "consumed": {LOG: 2}, "output": OAK, "yield": 4}

    def test_non_multiple_quantity_preserves_surplus(self):
        expected = requirements({"matches": True, "remainderCount": 0, "count": 4,
                                 "grid": [LOG], "output": OAK}, 7)
        self.assertEqual((expected["batches"], expected["produced"], expected["surplus"]), (2, 8, 1))

    def test_correct_real_recipe_balance(self):
        self.assertTrue(recipe_balance(Counter({LOG: 3}), Counter({LOG: 1, OAK: 8}), self.expected)[0])

    def test_partial_execution_is_conserved(self):
        self.assertTrue(recipe_balance(Counter({LOG: 3}), Counter({LOG: 2, OAK: 4}), self.expected)[0])

    def test_fabricated_output_and_wrong_material_are_rejected(self):
        self.assertFalse(recipe_balance(Counter({LOG: 3}), Counter({LOG: 2, OAK: 8}), self.expected)[0])
        self.assertFalse(recipe_balance(Counter({LOG: 3, "minecraft:birch_log": 1}),
                                       Counter({LOG: 3, OAK: 4}), self.expected)[0])

    def test_lost_surplus_and_unrelated_stock_loss_are_rejected(self):
        self.assertFalse(recipe_balance(Counter({LOG: 2}), Counter({OAK: 7}), self.expected)[0])
        self.assertFalse(recipe_balance(Counter({LOG: 2, "minecraft:diamond": 1}), Counter({OAK: 8}), self.expected)[0])

    def test_composition_is_order_independent(self):
        before = Counter({LOG: 3})
        after = Counter({OAK: 3, STICK: 2, AXE: 1, TABLE: 1})
        self.assertTrue(composition_balance(before, after)[0])
        after[AXE] += 1
        self.assertFalse(composition_balance(before, after)[0])

    def test_generated_composition_balances(self):
        rng = random.Random(17)
        for _ in range(100):
            logs, sticks, axes, tables = [rng.randrange(4) for _ in range(4)]
            d = Counter({LOG: -logs, OAK: 4*logs-2*sticks-3*axes-4*tables,
                         STICK: 4*sticks-2*axes, AXE: axes, TABLE: tables})
            before = Counter({key: max(0, -n) for key, n in d.items()})
            after = Counter({key: max(0, n) for key, n in d.items()})
            self.assertTrue(composition_balance(before, after)[0])

    def test_player_custody_drops_and_placed_blocks_are_counted(self):
        state = {"containers": {"x": {TABLE: 1}}, "actors": [{"inventory": {TABLE: 2}}],
                 "drops": [{"item": TABLE, "count": 3}], "blocks": {"x": "Block{minecraft:crafting_table}"}}
        client = {"slots": [{"item": TABLE, "count": 4, "playerInventory": True}]}
        self.assertEqual(totals(state, client)[TABLE], 11)

    def test_equivalence_refusal_never_counts_as_success(self):
        self.assertEqual(classify(feasible=True, completed=False, equivalent=True, conservation=True,
                                  delivered=0, requested=1), "GAP_RECIPE_EQUIVALENCE")

    def test_completion_marker_without_output_is_safety_failure(self):
        self.assertEqual(classify(feasible=True, completed=True, equivalent=False, conservation=True,
                                  delivered=0, requested=1), "FAIL_SAFETY")

    def test_unwitnessed_boundary_is_inconclusive(self):
        self.assertEqual(classify(feasible=True, completed=True, equivalent=False, conservation=True,
                                  delivered=1, requested=1, boundary=False), "INCONCLUSIVE_BOUNDARY")


class OracleTests(unittest.TestCase):
    def test_official_data_discovery_uses_bytes_not_material_names(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "unit-fixture.jar"
            with ZipFile(jar, "w") as archive:
                for key in ["stick", "wooden_axe", "crafting_table", "oak_planks", "birch_planks", "bread", "future_planks"]:
                    archive.writestr("data/minecraft/recipe/" + key + ".json", json.dumps({
                        "type": "minecraft:crafting_shapeless", "ingredients": ["minecraft:unit_fixture"],
                        "result": {"id": "minecraft:" + key, "count": 4}}))
            rows = candidates(jar)
            self.assertIn("minecraft:future_planks", {r["id"] for r in rows})
            self.assertTrue(all(len(r["officialDataSha256"]) == 64 for r in rows))

    def test_invalid_live_probe_is_rejected(self):
        with self.assertRaises(ValueError):
            requirements({"matches": False}, 7)

    def test_rotation_keeps_quantity_and_changes_coordinates(self):
        body = {"dimensions": {"x": 3, "y": 1, "z": 2}, "cells": [{"x": 2, "y": 0, "z": 1, "palette": 0}]}
        self.assertEqual(rotated_cells(body, [10, 20, 30], 1), [[10, 20, 32, 0]])
        self.assertEqual(rotated_cells(body, [10, 20, 30], 4), [[12, 20, 31, 0]])


if __name__ == "__main__":
    unittest.main()
