import copy,unittest
from review_harvest_births import validate
from review_receipt_prefix import sha,canonical

class HarvestBirthReviewTest(unittest.TestCase):
    def fixture(self):
        key={'world':'world','root':'root','step':'root','attempt':'attempt','generation':1,'ordinal':1}
        actor={'address':'actor','incarnation':'actor-version','dimension':'minecraft:overworld'}
        drop={'entity':'drop','incarnation':'drop-version'};chunk={'dimension':'minecraft:overworld','x':4,'z':4}
        def t(entity,inc):return {'entity':entity,'incarnation':inc,'chunks':[chunk.copy()],'current':chunk.copy()}
        entry={'intent':{'key':key,'source':{'dimension':'minecraft:overworld','x':73,'y':208,'z':73}},'result':{'actor':actor,'drops':[drop]}}
        birth={'key':key.copy(),'token':sha(canonical(key)),'complete':True,'pickupLineageAvailable':True,'actorBirth':chunk.copy(),
               'actorTrail':t('actor','actor-version'),'drops':[{'entity':'drop','incarnation':'drop-version','birth':chunk.copy(),'beforeNativeAdd':True,'trail':t('drop','drop-version')}]}
        return entry,[birth],[],{'actor':[73.5,208,73.5],'drop':[73.5,208.25,73.5]}
    def test_actual_birth_claim_still_counts_zero_pickup_settlements(self):
        self.assertEqual(0,validate(*self.fixture())['originalPickupSettlements'])
    def test_restored_rows_or_unfinished_h_cannot_mint_live_lineage(self):
        entry,births,_,positions=self.fixture()
        with self.assertRaises(AssertionError):validate(entry,births,births,positions)
        for field in ('complete','pickupLineageAvailable'):
            changed=copy.deepcopy(births);changed[0][field]=False
            with self.assertRaises(AssertionError):validate(entry,changed,[],positions)
    def test_drop_must_be_observed_before_actual_native_add(self):
        entry,births,restarted,positions=self.fixture();births[0]['drops'][0]['beforeNativeAdd']=False
        with self.assertRaises(AssertionError):validate(entry,births,restarted,positions)
    def test_changed_original_key_uuid_and_incarnation_are_rejected(self):
        entry,births,restarted,positions=self.fixture()
        for field in ('entity','incarnation'):
            changed=copy.deepcopy(births);changed[0]['drops'][0][field]='replacement'
            with self.assertRaises(AssertionError):validate(entry,changed,restarted,positions)
        changed=copy.deepcopy(births);changed[0]['token']='changed'
        with self.assertRaises(AssertionError):validate(entry,changed,restarted,positions)
    def test_saved_position_and_birth_source_must_match_the_complete_trail(self):
        entry,births,restarted,positions=self.fixture();changed=copy.deepcopy(positions);changed['drop'][0]=81
        with self.assertRaises(AssertionError):validate(entry,births,restarted,changed)
        changed=copy.deepcopy(births);changed[0]['drops'][0]['birth']['x']=5
        with self.assertRaises(AssertionError):validate(entry,changed,restarted,positions)
    def test_duplicate_missing_and_excessive_trail_chunks_refuse_completeness(self):
        entry,births,restarted,positions=self.fixture()
        for chunks in ([],[births[0]['actorBirth']]*2,[{'dimension':'minecraft:overworld','x':x,'z':4} for x in range(9)]):
            changed=copy.deepcopy(births);changed[0]['actorTrail']['chunks']=chunks
            with self.assertRaises(AssertionError):validate(entry,changed,restarted,positions)
