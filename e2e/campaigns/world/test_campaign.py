"""Supporting checks of corpus validity and reporting; these are not gameplay tests."""
import unittest
import tempfile
import math
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
        self.assertEqual(len({c['id'] for c in corpus}),12*len(FAMILIES))
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
            selected=c['positions']+c.get('immature',[])
            self.assertLessEqual(math.prod(max(p[k] for p in selected)-min(p[k] for p in selected)+1 for k in range(3)),256)

    def test_variations_change_multiple_parameters(self):
        corpus=generate()
        for family in FAMILIES:
            rows=[c for c in corpus if c['family']==family]
            keys=('item','quantity','positions','actor','destination','container','occupiedSlots','terrain')
            self.assertTrue(all(sum(a[k]!=b[k] for k in keys)>=3 for a,b in zip(rows,rows[1:])))

    def test_followup_selection_preserves_originals_and_bounds_every_request(self):
        _,cases=selection(104729,'full',('W7','W8','W9','W10'),heldout_seed=15485863,
            extra_heldout=('W1','W2','W3','W4','W5','W6'),replay_original=True)
        self.assertEqual(len(cases),283)
        self.assertEqual(len({c['id'] for c in cases}),283)
        self.assertEqual(sum('minimization' in c for c in cases),9)
        self.assertEqual(sum(c['id'].startswith('replay-') for c in cases),106)
        self.assertEqual(sum(c['seed']==15485863 for c in cases),120)
        for c in cases:
            bounds=c['sourceBounds']
            self.assertLessEqual(math.prod(bounds[k+3]-bounds[k]+1 for k in range(3)),256)
            self.assertTrue(all(all(bounds[k]<=p[k]<=bounds[k+3] for k in range(3)) for p in c['positions']))
            self.assertIn('caseSha256',c)

    def test_tall_positive_log_fixtures_have_supported_native_reach(self):
        for c in generate():
            if c['item']!='minecraft:wheat' and c['geometry']=='vertical' and c['quantity']==4:
                support=c['standingSupports'][0]
                feet=[support[0]+.5,support[1]+1,support[2]+.5]
                top=c['positions'][-1]
                closest=[max(top[k]+.125,min(top[k]+.875,feet[k])) for k in range(3)]
                self.assertLessEqual(math.dist(feet,closest),2.5)
                self.assertNotIn(support,c['positions'])

    def test_heldout_replay_uses_campaign_seed_and_exact_id(self):
        _,full=selection(23,'full',FAMILIES)
        self.assertEqual(len(full),24*len(FAMILIES))
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

    def test_nearest_fixture_has_typed_capacity_distractors_and_supported_legacy_home(self):
        for c in generate():
            if c['family']=='W7':
                anchor=[66,208,69]
                distance=lambda p:sum((a-b)**2 for a,b in zip(anchor,p))
                self.assertLess(distance(c['distractorFull']),distance(c['destination']))
                self.assertLess(distance(c['distractorWrongType']),distance(c['destination']))
                self.assertLess(distance(c['destination']),distance(c['distractorFar']))
            if c['family']=='W8':
                self.assertLessEqual(max(abs(c['actor'][k]-c['destination'][k]) for k in (0,2)),12)
                self.assertTrue(all(max(abs(p[k]-c['destination'][k]) for k in (0,2))<=12 for p in c['positions']))

    def test_batch_and_actor_receipt_forgery_fail_independent_oracle(self):
        import copy
        c=next(c for c in generate() if c['item']=='minecraft:oak_log' and c['quantity']==1)
        p=c['positions'][0]; destination=','.join(map(str,c['destination']))
        before=dict(blocks={','.join(map(str,p)):'minecraft:oak_log'},containers={destination:{}},
            actors=[{'uuid':'entity','pos':[66,208,66],'inventory':{}}],drops=[],knownPlayers={})
        after=copy.deepcopy(before); after['blocks'][','.join(map(str,p))]='minecraft:air'; after['containers'][destination]={'minecraft:oak_log':1}
        rows=[dict(receiptId=str(i),batchId='batch',runId='run',actor={'citizenId':'citizen','entityId':'entity'},
            destination=dict(zip(('x','y','z'),c['destination'])),item={'id':c['item']},stage=stage,quantity=1)
            for i,stage in enumerate(('HARVEST','PICKUP','DEPOSIT'))]
        view={'id':'run','outcome':{'status':'SUCCEEDED'},'resourceReceipts':rows}
        props,_=evaluate(c,before,after,view,'citizen','entity',[])
        self.assertTrue(props['conservation'] and props['pickupLineage'] and props['receiptIdentity'])
        rows[-1]['batchId']='unharvested-batch'
        props,_=evaluate(c,before,after,view,'citizen','entity',[])
        self.assertFalse(props['pickupLineage'])
        rows[-1]['actor']['entityId']='foreign-entity'
        props,_=evaluate(c,before,after,view,'citizen','entity',[])
        self.assertFalse(props['receiptIdentity'])

if __name__=='__main__': unittest.main()
