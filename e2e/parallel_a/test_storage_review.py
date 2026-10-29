"""Reject fabricated and incomplete native mechanics review claims."""
import copy,json,unittest
from review_storage_mechanics import validate,TAG

class StorageReviewTest(unittest.TestCase):
    def fixture(self):
        marker={'effect':'a'*64,'intent':'b'*64,'phase':'RESULT','result':'c'*64}
        row={'entity':'first-uuid','incarnation':'incarnation','revision':1,'marker':marker};encoded={**row,'marker':json.dumps({'schema':1,**marker})}
        proof={'status':'PASS_NATIVE_STORAGE_MECHANICS_ONLY','nativePickupProducer':'INACTIVE','originalOwnerSettlements':0,
               'emptyRemoval':'PASS_NATIVE_CLOSE_REOPEN_AND_TWO_EMPTY_SAVES','opaqueMetadata':'PASS_EXACT_INT_77_PRESERVED',
               'contradiction':'PASS_PERMANENT_NATIVE_QUARANTINE','removal':row,'initialEntity':row['entity'],'conflictingEntity':'conflict-uuid',
               'movement':{'chunks':[{'dimension':'minecraft:overworld','x':x,'z':4} for x in (4,5,6)],
                           'current':{'dimension':'minecraft:overworld','x':4,'z':4}},'changedUuid':'PASS_GAP'}
        roots=[{'Position':[x,4],'Entities':[],'DataVersion':1} for x in (4,5,6)]
        roots[0][TAG]=json.dumps({'schema':1,'dimension':'minecraft:overworld','chunkX':4,'chunkZ':4,'removals':[encoded]})
        roots[1][TAG]=77;roots[2][TAG]=json.dumps({'quarantined':True,'schema':0});roots[2][TAG+'_original']={TAG:json.dumps({'schema':1,'removals':[{'entity':'conflict-uuid'}]})}
        return roots,proof
    def test_mechanics_claim_keeps_original_owner_count_zero(self):
        roots,proof=self.fixture();self.assertEqual(0,validate(roots,proof)['originalOwnerSettlements'])
    def test_bare_absence_and_live_uuid_cannot_replace_saved_empty_frame(self):
        roots,proof=self.fixture()
        for value in (None,{**roots[0],'Entities':[{'UUID':'live'}]}):
            changed=copy.deepcopy(roots);changed[0]=value
            with self.assertRaises(AssertionError):validate(changed,proof)
    def test_changed_identity_and_marker_are_rejected(self):
        roots,proof=self.fixture()
        for key in ('entity','incarnation','revision','marker'):
            changed=copy.deepcopy(roots);metadata=json.loads(changed[0][TAG]);metadata['removals'][0][key]=0 if key=='revision' else 'changed';changed[0][TAG]=json.dumps(metadata)
            with self.assertRaises((AssertionError,ValueError,TypeError)):validate(changed,proof)
    def test_opaque_value_and_quarantine_cannot_be_replaced_by_absence(self):
        roots,proof=self.fixture()
        for index in (1,2):
            changed=copy.deepcopy(roots);changed[index][TAG]='{}'
            with self.assertRaises(AssertionError):validate(changed,proof)
    def test_sampled_movement_and_false_pickup_completion_are_rejected(self):
        roots,proof=self.fixture()
        for key,value in (('originalOwnerSettlements',1),('nativePickupProducer','ACTIVE'),('changedUuid','UNCHANGED')):
            changed=copy.deepcopy(proof);changed[key]=value
            with self.assertRaises(AssertionError):validate(roots,changed)
        changed=copy.deepcopy(proof);changed['movement']['chunks'].pop()
        with self.assertRaises(AssertionError):validate(roots,changed)
