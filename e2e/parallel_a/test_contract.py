import copy
import unittest
from contract import MAX_I64, batch, canonical, validate_proposal


class BoundaryFixtures(unittest.TestCase):
    def setUp(self):
        self.snapshot = {'root': 'fixture:root-a', 'revision': 7, 'deadline': 10000,
                         'remaining': {'effects': 5}}
        self.proposal = {'version': 1, 'proposalId': 'fixture:proposal-a', 'root': 'fixture:root-a',
                         'revision': 7, 'stepToken': 'fixture:step-a', 'capability': 'fixture:craft',
                         'capabilityVersion': 1, 'allowance': {'effects': 1}, 'deadline': 10000,
                         'quantities': batch(3, 4)}
        self.supported = {('fixture:craft', 1)}

    def valid(self, **kwargs):
        return validate_proposal(self.snapshot, self.proposal, self.supported, **kwargs)

    def test_q3_y4_keeps_surplus_separate(self):
        self.assertEqual(batch(3, 4), {'demand': 3, 'batches': 1, 'physicalOutput': 4, 'goalAllocation': 3, 'surplus': 1})
        self.assertEqual(len(self.valid()), 64)

    def test_fixtures_never_native(self):
        with self.assertRaises(ValueError): self.valid(native=True)

    def test_forged_proof_or_terminal_is_rejected(self):
        for field in ('receipt', 'proof', 'terminal'):
            p = copy.deepcopy(self.proposal); p[field] = 'forged'
            with self.assertRaises(ValueError): validate_proposal(self.snapshot, p, self.supported)

    def test_stale_revision(self):
        self.proposal['revision'] = 6
        with self.assertRaises(ValueError): self.valid()

    def test_foreign_root(self):
        self.proposal['root'] = 'fixture:other-root'
        with self.assertRaises(ValueError): self.valid()

    def test_budget_widening_and_unknown_dimension(self):
        for allowance in ({'effects': 6}, {'new': 1}):
            self.proposal['allowance'] = allowance
            with self.assertRaises(ValueError): self.valid()

    def test_deadline_cannot_extend_or_reset(self):
        self.proposal['deadline'] = 10001
        with self.assertRaises(ValueError): self.valid()

    def test_over_allocation(self):
        self.proposal['quantities']['surplus'] = 2
        with self.assertRaises(ValueError): self.valid()

    def test_capability_version(self):
        self.proposal['capabilityVersion'] = 2
        with self.assertRaises(ValueError): self.valid()

    def test_checked_arithmetic_and_wrong_types(self):
        for d, y in ((MAX_I64, 2), (0, 4), (1, 0), (-1, 1), (True, 4), (3.0, 4)):
            with self.assertRaises(ValueError): batch(d, y)
        self.assertEqual(batch(MAX_I64, 1)['physicalOutput'], MAX_I64)

    def test_canonical_order_preserves_arrays(self):
        self.assertEqual(canonical({'z': [2, 1], 'a': 3}), b'{"a":3,"z":[2,1]}')
        self.assertNotEqual(canonical([1, 2]), canonical([2, 1]))
        with self.assertRaises(ValueError): canonical({'n': 3.0})


if __name__ == '__main__': unittest.main()
