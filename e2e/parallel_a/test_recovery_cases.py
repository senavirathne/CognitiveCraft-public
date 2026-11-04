"""Coverage and accounting guards; synthetic rows never qualify native recovery."""
import copy,json,tempfile,unittest
from pathlib import Path
from recovery_cases import originals,positions,settled,complete,STAGES

MANIFEST=Path(__file__).with_name('a06_original_cases.json')

class RecoveryCasesTest(unittest.TestCase):
    def setUp(self):self.cases=originals(MANIFEST)
    def test_all_original_quantities_materials_and_geometries_are_selected(self):
        self.assertEqual(12,len(self.cases))
        self.assertEqual({'HARVEST':4,'PICKUP':4,'DEPOSIT':4},{s:sum(c['checkpoint']==s for c in self.cases) for s in STAGES})
        for c in self.cases:self.assertEqual(c['requested'],len(positions(c)))
    def test_even_a_historical_field_change_or_whitespace_refuses_the_frozen_input(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'manifest.json';path.write_bytes(MANIFEST.read_bytes()+b'\n')
            with self.assertRaises(ValueError):originals(path)
            changed=json.loads(MANIFEST.read_text());changed['cases'][0]['historicalOriginalOwnerSettled']=True
            path.write_text(json.dumps(changed))
            with self.assertRaises(ValueError):originals(path)
    def reviews(self):
        return [{'originalCase':c,'status':'PASS_INDEPENDENT_ORIGINAL_REVIEW','nativeCustodyNoReplay':True,'originalOwnerSettled':True,
                 'key':dict(world='world',root=str(n),step=str(n),attempt=str(n),generation=1,ordinal=STAGES[c['checkpoint']][0]),
                 'owner':dict(id=c['id'],stage=c['checkpoint'])} for n,c in enumerate(self.cases)]
    def test_twelve_unique_independently_reviewed_originals_are_required(self):
        self.assertEqual(12,complete(self.cases,self.reviews())['originalOwnerSettlements'])
        for selected in (self.reviews()[:-1],self.reviews()+[self.reviews()[0]]):
            with self.assertRaises(AssertionError):complete(self.cases,selected)
    def test_a_failed_case_missing_custody_changed_quantity_and_reused_effect_identity_refuse(self):
        for field,value in (('status','FAIL'),('nativeCustodyNoReplay',False),('originalOwnerSettled',False)):
            rows=self.reviews();rows[0][field]=value
            with self.assertRaises(AssertionError):complete(self.cases,rows)
        rows=self.reviews();rows[0]['originalCase']=copy.deepcopy(rows[0]['originalCase']);rows[0]['originalCase']['requested']+=1
        with self.assertRaises(AssertionError):complete(self.cases,rows)
        rows=self.reviews();rows[1]['key']=rows[0]['key']
        with self.assertRaises(AssertionError):complete(self.cases,rows)

if __name__=='__main__':unittest.main()
