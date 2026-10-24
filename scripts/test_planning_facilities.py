"""Integrity fixtures only; no synthetic row counts as actual-model evidence."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('facilities', Path(__file__).with_name('planning-facilities.py'))
FACILITIES = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FACILITIES)


class FacilityIntegrityTest(unittest.TestCase):
    def context(self):
        return {'availableInventory': {'minecraft:oak_planks': 7, 'minecraft:stick': 2},
                'requiredDeliveries': {'minecraft:wooden_axe': 1}, 'tableAvailable': False, 'placementAllowed': True,
                'requiredWork': {'status': 'plan', 'batches': {'minecraft:crafting_table': 1,
                                 FACILITIES.PLACE: 1, 'minecraft:wooden_axe': 1}}}

    def proposal(self, steps):
        return json.dumps({'schema': 1, 'status': 'plan', 'steps': [{'recipe': r, 'batches': n} for r, n in steps],
                           'deliveries': [{'item': 'minecraft:wooden_axe', 'amount': 1}]})

    def test_missing_item_is_not_an_available_facility(self):
        c = self.context()
        bad = self.proposal([(FACILITIES.PLACE, 1), (FACILITIES.TABLE, 1), (FACILITIES.AXE, 1)])
        witness = FACILITIES.facility_failure(bad, c)
        self.assertEqual('MISSING_TABLE_ITEM', witness['reason'])
        self.assertEqual(0, witness['tableItemsBeforeStep'])
        self.assertFalse(witness['tableAvailableBeforeStep'])
        self.assertFalse(FACILITIES.RESOURCES.resource_valid(bad, c))

    def test_supported_item_production_then_placement_conserves_stock(self):
        c = self.context()
        good = self.proposal([(FACILITIES.TABLE, 1), (FACILITIES.PLACE, 1), (FACILITIES.AXE, 1)])
        self.assertFalse(FACILITIES.facility_failure(good, c))
        self.assertTrue(FACILITIES.RESOURCES.resource_valid(good, c))

    def test_stored_item_and_placement_permission_are_independent(self):
        c = self.context()
        c['availableInventory'][FACILITIES.TABLE] = 1
        axe_first = self.proposal([(FACILITIES.AXE, 1), (FACILITIES.PLACE, 1)])
        self.assertEqual('TABLE_NOT_PLACED', FACILITIES.facility_failure(axe_first, c)['reason'])
        c['placementAllowed'] = False
        bad = self.proposal([(FACILITIES.PLACE, 1), (FACILITIES.AXE, 1)])
        self.assertEqual('PLACEMENT_NOT_AUTHORIZED', FACILITIES.facility_failure(bad, c)['reason'])
        c['placementAllowed'], c['tableAvailable'] = True, True
        self.assertEqual('TABLE_ALREADY_AVAILABLE', FACILITIES.facility_failure(bad, c)['reason'])

    def test_first_stock_error_cannot_be_hidden_by_a_later_facility_error(self):
        c = self.context()
        c['availableInventory'] = {'minecraft:oak_log': 3}
        bad = self.proposal([(FACILITIES.TABLE, 1), (FACILITIES.PLACE, 1), (FACILITIES.AXE, 1)])
        self.assertFalse(FACILITIES.facility_failure(bad, c))

    def test_repeated_placement_does_not_create_additional_authority(self):
        c = self.context()
        c['availableInventory'][FACILITIES.TABLE] = 2
        bad = self.proposal([(FACILITIES.PLACE, 1), (FACILITIES.PLACE, 1), (FACILITIES.AXE, 1)])
        witness = FACILITIES.facility_failure(bad, c)
        self.assertEqual(1, witness['step'])
        self.assertEqual('TABLE_ALREADY_AVAILABLE', witness['reason'])
        self.assertEqual(1, witness['tableItemsBeforeStep'])

    def test_unsupported_placement_is_not_registered_by_a_permission_flag(self):
        c = self.context()
        bad = self.proposal([('cognitivecraft:place_furnace', 1), (FACILITIES.AXE, 1)])
        self.assertFalse(FACILITIES.facility_failure(bad, c))
        self.assertFalse(FACILITIES.RESOURCES.resource_valid(bad, c))


if __name__ == '__main__':
    unittest.main()

