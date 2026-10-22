"""Contract-order integrity fixtures; these are not live-model results."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('order', Path(__file__).with_name('planning-order.py'))
ORDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ORDER)


class ContractOrderIntegrityTest(unittest.TestCase):
    def context(self):
        return {'requiredDeliveries': {'minecraft:stick': 5}, 'requiredWork': {'status': 'plan',
                'batches': {'minecraft:oak_planks': 1, 'minecraft:stick': 2}}}

    def ordered(self):
        return ORDER.expected_shape(self.context())

    def test_contract_order_preserves_action_choices_batches_and_cardinality(self):
        shape = self.ordered()
        ORDER.check_schema_order(shape)
        steps = shape['properties']['steps']
        self.assertEqual(2, steps['minItems'])
        self.assertEqual(2, steps['maxItems'])
        self.assertEqual([['minecraft:oak_planks'], ['minecraft:stick']],
                         [s['properties']['recipe']['enum'] for s in steps['items']['anyOf']])
        self.assertEqual([[1], [2]], [s['properties']['batches']['enum'] for s in steps['items']['anyOf']])

    def test_alphabetical_order_has_equal_schema_values_but_wrong_wire_sequence(self):
        shape = self.ordered()
        alphabetic = json.loads(json.dumps(shape, sort_keys=True))
        self.assertEqual(shape, alphabetic)
        with self.assertRaisesRegex(ValueError, 'contract order'):
            ORDER.check_schema_order(alphabetic)

    def test_closed_objects_and_original_empty_arrays_are_preserved(self):
        c = self.context()
        c['requiredWork'] = {'status': 'insufficient_resources', 'batches': {}}
        shape = ORDER.expected_shape(c)
        self.assertFalse(shape['additionalProperties'])
        for field in ('steps', 'deliveries'):
            self.assertEqual({'type': 'array', 'maxItems': 0, 'items': {'type': 'object'}}, shape['properties'][field])

    def test_raw_candidate_must_expose_identity_before_quantity(self):
        p = {'schema': 1, 'status': 'plan', 'steps': [{'recipe': 'minecraft:stick', 'batches': 2}],
             'deliveries': [{'item': 'minecraft:stick', 'amount': 5}]}
        ORDER.check_candidate_order(json.dumps(p))
        bad = copy.deepcopy(p)
        bad['steps'][0] = {'batches': 2, 'recipe': 'minecraft:stick'}
        with self.assertRaisesRegex(ValueError, 'quantity before action'):
            ORDER.check_candidate_order(json.dumps(bad))

    def test_delivery_field_order_cannot_silently_revert(self):
        p = {'schema': 1, 'status': 'plan', 'steps': [], 'deliveries': [{'amount': 5, 'item': 'minecraft:stick'}]}
        with self.assertRaisesRegex(ValueError, 'quantity before delivery'):
            ORDER.check_candidate_order(json.dumps(p))


if __name__ == '__main__':
    unittest.main()
