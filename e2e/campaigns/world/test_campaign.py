"""Supporting checks of corpus validity and reporting; these are not gameplay tests."""
import unittest
import tempfile
from pathlib import Path
from generate import generate, FAMILIES

class CorpusTests(unittest.TestCase):
    def test_reproduction_and_independent_seed(self):
        self.assertEqual(generate(23),generate(23))
        self.assertNotEqual(generate(23),generate(29))

    def test_supported_domain_and_observation_bounds(self):
        corpus=generate()
        self.assertEqual(len({c['id'] for c in corpus}),72)
        for family in FAMILIES:
            self.assertEqual(sum(c['family']==family for c in corpus),12)
        for c in corpus:
            self.assertEqual(c['quantity'],len(set(tuple(p) for p in c['positions'])))
            self.assertNotIn(c['destination'],c['positions'])
            for x,y,z in c['positions']:
                self.assertTrue(-1<=x<=20 and 201<=y<=205 and -1<=z<=14)
            self.assertLess(c['occupiedSlots'],27)
            if c['item']=='minecraft:wheat':
                self.assertTrue(all(p[1]==201 for p in c['positions']))

    def test_variations_change_multiple_parameters(self):
        corpus=generate()
        for family in FAMILIES:
            rows=[c for c in corpus if c['family']==family]
            keys=('item','quantity','positions','actor','destination','container','occupiedSlots','terrain')
            self.assertTrue(all(sum(a[k]!=b[k] for k in keys)>=3 for a,b in zip(rows,rows[1:])))

if __name__=='__main__': unittest.main()
