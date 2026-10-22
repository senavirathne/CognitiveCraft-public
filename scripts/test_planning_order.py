"""Measured wire-contract integrity fixtures; these are not live-model results."""
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

    def canonical(self):
        return json.loads(json.dumps(ORDER.expected_shape(self.context()), sort_keys=True))

    def test_canonical_order_preserves_choices_batches_and_cardinality(self):
        shape = self.canonical()
        ORDER.check_schema_order(shape)
        steps = shape['properties']['steps']
        self.assertEqual(2, steps['minItems'])
        self.assertEqual(2, steps['maxItems'])
        self.assertEqual([['minecraft:oak_planks'], ['minecraft:stick']],
                         [s['properties']['recipe']['enum'] for s in steps['items']['anyOf']])
        self.assertEqual([[1], [2]], [s['properties']['batches']['enum'] for s in steps['items']['anyOf']])

    def test_rejected_experiment_is_distinct_despite_equal_schema_values(self):
        canonical = self.canonical()
        contract = ORDER.expected_shape(self.context())
        self.assertEqual(canonical, contract)
        ORDER.check_schema_order(contract, 'contract')
        with self.assertRaisesRegex(ValueError, 'measured wire order'):
            ORDER.check_schema_order(contract)
        with self.assertRaisesRegex(ValueError, 'measured wire order'):
            ORDER.check_schema_order(canonical, 'contract')

    def test_closed_objects_and_original_empty_arrays_are_preserved(self):
        c = self.context()
        c['requiredWork'] = {'status': 'insufficient_resources', 'batches': {}}
        shape = ORDER.expected_shape(c)
        self.assertFalse(shape['additionalProperties'])
        for field in ('steps', 'deliveries'):
            self.assertEqual({'type': 'array', 'maxItems': 0, 'items': {'type': 'object'}}, shape['properties'][field])

    def test_raw_candidate_sequence_is_checked_without_reordering_it(self):
        p = {'deliveries': [{'amount': 5, 'item': 'minecraft:stick'}], 'schema': 1,
             'status': 'plan', 'steps': [{'batches': 2, 'recipe': 'minecraft:stick'}]}
        ORDER.check_candidate_order(json.dumps(p))
        bad = copy.deepcopy(p)
        bad['steps'][0] = {'recipe': 'minecraft:stick', 'batches': 2}
        with self.assertRaisesRegex(ValueError, 'Candidate step'):
            ORDER.check_candidate_order(json.dumps(bad))

    def test_delivery_and_root_order_cannot_silently_change(self):
        p = {'deliveries': [{'item': 'minecraft:stick', 'amount': 5}], 'schema': 1, 'status': 'plan', 'steps': []}
        with self.assertRaisesRegex(ValueError, 'Candidate delivery'):
            ORDER.check_candidate_order(json.dumps(p))
        p = {'schema': 1, 'status': 'plan', 'steps': [], 'deliveries': []}
        with self.assertRaisesRegex(ValueError, 'Candidate root'):
            ORDER.check_candidate_order(json.dumps(p))
        ORDER.check_candidate_order(json.dumps(p), 'contract')


if __name__ == '__main__':
    unittest.main()
