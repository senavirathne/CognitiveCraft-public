"""Supporting checks of corpus validity and reporting; these are not gameplay tests."""
import unittest
import tempfile
from pathlib import Path
from generate import generate, FAMILIES
from runner import selection, reproduction
from oracle import custody, evaluate

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
                self.assertTrue(63<=x<=85 and 208<=y<=212 and 63<=z<=79)
            self.assertLessEqual(c['occupiedSlots'],27)
            if c['item']=='minecraft:wheat':
                self.assertTrue(all(p[1]==208 for p in c['positions']))

    def test_variations_change_multiple_parameters(self):
        corpus=generate()
        for family in FAMILIES:
            rows=[c for c in corpus if c['family']==family]
            keys=('item','quantity','positions','actor','destination','container','occupiedSlots','terrain')
            self.assertTrue(all(sum(a[k]!=b[k] for k in keys)>=3 for a,b in zip(rows,rows[1:])))

    def test_heldout_replay_uses_campaign_seed_and_exact_id(self):
        _,full=selection(23,'full',FAMILIES)
        self.assertEqual(len(full),144)
        for row in full:
            _,replayed=selection(23,'full',FAMILIES,case=row['id'])
            self.assertEqual(replayed,[row])
            self.assertIn('--seed 23',reproduction(row,23))
            self.assertIn('--case '+row['id'],reproduction(row,23))

    def test_player_custody_is_counted_once(self):
        state=dict(containers={'x':{'item':2}},actors=[{'inventory':{'item':3}}],
                   drops=[{'item':'item','count':4}],knownPlayers={'A':{'assessmentInventory':{'item':5}}})
        self.assertEqual(sum(custody(state,'item').values()),14)

    def test_phantom_receipts_cannot_prove_physical_delivery(self):
        c=generate()[0]; item=c['item']; p=c['positions'][0]
        state=dict(blocks={','.join(map(str,p)):item},containers={},actors=[{'uuid':'entity','pos':[0,0,0],'inventory':{}}],drops=[],knownPlayers={})
        view={'id':'run','outcome':{'status':'SUCCEEDED'},'receipts':[],'resourceReceipts':[]}
        props,_=evaluate(c,state,state,view,'citizen','entity',[])
        self.assertFalse(props['truthfulCompletion'])
        self.assertFalse(props['delivery'])

if __name__=='__main__': unittest.main()
