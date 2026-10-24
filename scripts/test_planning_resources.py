"""Integrity unit fixtures only; no fixture is a genuine model record."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('resources', Path(__file__).with_name('planning-resources.py'))
RESOURCES = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RESOURCES)


class ResourceIntegrityTest(unittest.TestCase):
    def context(self):
        c = {'availableInventory': {'minecraft:oak_log': 2}, 'requiredDeliveries': {'minecraft:crafting_table': 2},
             'tableAvailable': False, 'placementAllowed': False,
             'requiredWork': {'status': 'plan', 'batches': {'minecraft:oak_planks': 2, 'minecraft:crafting_table': 2}}}
        c['requiredWork']['resourceGrounding'] = {
            'initialStock': {item: c['availableInventory'].get(item, 0) for item in RESOURCES.ITEMS},
            'transactions': {'minecraft:oak_planks': {'consumes': {'minecraft:oak_log': 2}, 'produces': {'minecraft:oak_planks': 8}},
                             'minecraft:crafting_table': {'consumes': {'minecraft:oak_planks': 8}, 'produces': {'minecraft:crafting_table': 2}}}}
        return c

    def candidate(self, recipes):
        return json.dumps({'schema': 1, 'status': 'plan', 'deliveries': [{'item': 'minecraft:crafting_table', 'amount': 2}],
                           'steps': [{'recipe': 'minecraft:' + r, 'batches': 2} for r in recipes]})

    def test_future_outputs_cannot_be_promoted_to_initial_stock(self):
        c = self.context()
        RESOURCES.check_grounding(c)
        c['requiredWork']['resourceGrounding']['initialStock']['minecraft:oak_planks'] = 8
        with self.assertRaisesRegex(ValueError, 'inventory changed'):
            RESOURCES.check_grounding(c)

    def test_future_transactions_keep_exact_trusted_consumption(self):
        c = self.context()
        c['requiredWork']['resourceGrounding']['transactions']['minecraft:crafting_table']['consumes']['minecraft:oak_planks'] = 4
        with self.assertRaisesRegex(ValueError, 'quantities differ'):
            RESOURCES.check_grounding(c)

    def test_stock_replay_denies_premature_consumption_and_double_spending(self):
        c = self.context()
        self.assertTrue(RESOURCES.resource_valid(self.candidate(['oak_planks', 'crafting_table']), c))
        self.assertFalse(RESOURCES.resource_valid(self.candidate(['crafting_table', 'oak_planks']), c))
        self.assertFalse(RESOURCES.resource_valid(self.candidate(['oak_planks', 'oak_planks']), c))

    def test_refusals_cannot_hide_work_or_delivery_effects(self):
        c = self.context()
        c['requiredWork'] = {'status': 'insufficient_resources', 'batches': {}}
        refusal = {'schema': 1, 'status': 'insufficient_resources', 'steps': [], 'deliveries': []}
        self.assertTrue(RESOURCES.resource_valid(json.dumps(refusal), c))
        for field in ('steps', 'deliveries'):
            bad = copy.deepcopy(refusal)
            bad[field] = [1]
            self.assertFalse(RESOURCES.resource_valid(json.dumps(bad), c))


if __name__ == '__main__':
    unittest.main()
