"""Guard the original denominator and reject relabelled, repeated or unsettled native claims."""
import copy,json,unittest
from pathlib import Path
from harvest_cases import LOG_H_CASE_IDS,select_case,fixture_positions,summarize

class OriginalHarvestCasesTest(unittest.TestCase):
    def setUp(self):
        self.manifest=json.loads((Path(__file__).parent/'a06_original_cases.json').read_text())
    def reviews(self):
        rows=[]
        for case_id in LOG_H_CASE_IDS:
            case=select_case(self.manifest,case_id);key={'world':'world','root':'root-'+case_id,'step':'root-'+case_id,'attempt':'attempt-'+case_id,'generation':1,'ordinal':1}
            job={'id':key['step'],'generation':1,'state':'CANCELLED','attempts':[{'id':key['attempt'],'uncertain':False,'effects':1,'credited':0}],
                 'request':{'request':{'arguments':{'amount':{'value':case['requested']}}}}}
            receipt={'stage':'HARVEST','item':case['item'],'source':[case['source'][k] for k in ('minX','minY','minZ','maxX','maxY','maxZ')],
                     'destination':[case['destination'][k] for k in ('x','y','z')]}
            rows.append({'originalCase':copy.deepcopy(case),'status':'PASS_FIRST_H_REVIEWED_ONLY','originalOwnerSettlements':1,'a06Qualified':False,'key':key,'originalJob':job,'nativeSavedProof':{'entry':{'result':{'receipt':receipt}}}})
        return rows
    def test_three_distinct_selected_cases_keep_all_twelve_originals(self):
        self.assertEqual(12,len(self.manifest['cases']));self.assertEqual(3,len(set(LOG_H_CASE_IDS)))
        selected=[select_case(self.manifest,c) for c in LOG_H_CASE_IDS]
        self.assertEqual({('minecraft:oak_log',2),('minecraft:oak_log',3),('minecraft:birch_log',2)},{(c['item'],c['requested']) for c in selected})
    def test_every_fixture_uses_original_quantity_and_fits_unchanged_geometry(self):
        before=copy.deepcopy(self.manifest)
        for case_id in LOG_H_CASE_IDS:
            case=select_case(self.manifest,case_id);points=fixture_positions(case)
            self.assertEqual(case['requested'],len(points));self.assertEqual(len(points),len({tuple(p) for p in points}))
        self.assertEqual(before,self.manifest)
    def test_pickup_deposit_wheat_and_unknown_cases_cannot_be_counted_as_log_h(self):
        for case in self.manifest['cases']:
            if case['id'] not in LOG_H_CASE_IDS:
                with self.assertRaises(ValueError):select_case(self.manifest,case['id'])
        with self.assertRaises(ValueError):select_case(self.manifest,'invented')
    def test_missing_or_duplicate_original_definition_is_rejected(self):
        for values in ([],[self.manifest['cases'][0],self.manifest['cases'][0]]):
            with self.assertRaises(ValueError):select_case({'cases':values},'W6-002')
    def test_changed_checkpoint_material_and_quantity_are_rejected(self):
        for field,value in (('checkpoint','PICKUP'),('item','minecraft:wheat'),('requested',1)):
            m=copy.deepcopy(self.manifest);select_case(m,'W6-002')[field]=value
            with self.assertRaises(ValueError):select_case(m,'W6-002')
    def test_fixture_cannot_move_logs_outside_original_source_bounds(self):
        case=copy.deepcopy(select_case(self.manifest,'heldout-W6-008'));case['source']['maxX']=76
        with self.assertRaises(ValueError):fixture_positions(case)
    def test_complete_review_counts_three_originals_and_leaves_nine_required(self):
        result=summarize(self.reviews(),self.manifest);self.assertEqual(3,result['originalOwnerSettlements'])
        self.assertEqual(9,len(result['remainingRequiredCases']));self.assertFalse(result['a06Qualified'])
    def test_repeating_one_case_cannot_replace_an_unqualified_original(self):
        rows=self.reviews();rows[-1]=copy.deepcopy(rows[0])
        with self.assertRaises(ValueError):summarize(rows,self.manifest)
    def test_missing_case_cannot_be_filled_by_a_historical_or_reported_counter(self):
        rows=self.reviews()[:-1]
        for row in rows:row['originalOwnerSettlements']=100
        with self.assertRaises(ValueError):summarize(rows,self.manifest)
    def test_failed_ambiguous_and_full_a06_claims_are_rejected(self):
        for field,value in (('status','FAIL'),('originalOwnerSettlements',0),('a06Qualified',True)):
            rows=self.reviews();rows[0][field]=value
            with self.assertRaises(ValueError):summarize(rows,self.manifest)
    def test_material_source_and_destination_must_match_each_original(self):
        for field,value in (('stage','PICKUP'),('item','minecraft:stone'),('source',[0]*6),('destination',[0]*3)):
            rows=self.reviews();rows[0]['nativeSavedProof']['entry']['result']['receipt'][field]=value
            with self.assertRaises(ValueError):summarize(rows,self.manifest)
    def test_uncertain_owner_changed_attempt_wrong_debit_and_credit_are_rejected(self):
        for field,value in (('uncertain',True),('id','replacement'),('effects',2),('credited',1)):
            rows=self.reviews();rows[0]['originalJob']['attempts'][-1][field]=value
            with self.assertRaises(ValueError):summarize(rows,self.manifest)
    def test_pickup_ordinal_and_changed_original_quantity_are_not_h_passes(self):
        rows=self.reviews();rows[0]['key']['ordinal']=2
        with self.assertRaises(ValueError):summarize(rows,self.manifest)
        rows=self.reviews();rows[1]['originalJob']['request']['request']['arguments']['amount']['value']=2
        with self.assertRaises(ValueError):summarize(rows,self.manifest)
