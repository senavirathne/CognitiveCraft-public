"""One actual Java campaign, with independent counter/identity/privacy tamper controls."""
import copy,json,os,subprocess,tempfile,unittest
from pathlib import Path
from common import *
from acl_oracle import campaign,observations,verify

class AclSequenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary=tempfile.TemporaryDirectory(prefix='cc-d03-acl-')
        cls.root=Path(os.getenv('CC_D_ACL_EVIDENCE',Path(cls.temporary.name)/'evidence'))
        require(not cls.root.exists(),'refuse overwrite of ACL sequence evidence');cls.root.mkdir(parents=True)
        override=os.getenv('CC_D_ACL_TEST_CP')
        if override:cp=override
        else:
            gson=sorted((REPO/'build/server-sdk/libs').glob('gson-*.jar'));require(len(gson)==1,'ACL runtime Gson ambiguous')
            cp=os.pathsep.join(map(str,[REPO/'core/build/classes/java/test',RELEASE,gson[0]]))
        command=[os.getenv('CC_D_ACL_TEST_JAVA','java'),'-cp',cp,'dev.aivillages.core.kernel.storage.StorageAclSequenceProbe',str(HERE/'campaigns/D03-acl-100.json'),str(cls.root)]
        result=subprocess.run(command,capture_output=True,timeout=120)
        (cls.root/'probe.stdout.log').write_bytes(result.stdout);(cls.root/'probe.stderr.log').write_bytes(result.stderr)
        require(result.returncode==0,'Java ACL probe failed: '+result.stderr.decode(errors='replace')[-2000:])
        cls.rows=[json.loads(line)for line in (cls.root/'observations.jsonl').read_text().splitlines()]
    @classmethod
    def tearDownClass(cls):cls.temporary.cleanup()
    def test_100_seeded_grant_revoke_transfer_membership_transitions(self):
        result=verify(self.root);self.assertEqual(result['transitions'],100);self.assertEqual(result['native_executions'],0)
    def test_missing_duplicate_or_wrong_principal_transition_refused(self):
        for bad in (self.rows[:-1],self.rows+[self.rows[0]]):
            with self.assertRaises(ValueError):observations(bad)
        bad=copy.deepcopy(self.rows);bad[12]['after']['scope']['principal_id']='00000000-0000-4000-8000-000000000000'
        with self.assertRaisesRegex(ValueError,'principal'):observations(bad)
    def test_claimed_allow_or_surviving_old_decision_is_not_pass(self):
        bad=copy.deepcopy(self.rows);bad[1]['after']['decision']='ALLOW'
        with self.assertRaises(ValueError):observations(bad)
        bad=copy.deepcopy(self.rows);bad[1]['old_current']=True
        with self.assertRaisesRegex(ValueError,'survived'):observations(bad)
    def test_rehashed_json_cannot_hide_acl_revision_or_transfer_inheritance(self):
        for action in ('GRANT','REVOKE','TRANSFER','MEMBERSHIP'):
            bad=copy.deepcopy(self.rows);i=next(i for i,s in enumerate(campaign())if s['action']==action);bad[i]['after']['acl_revision']+=1
            with self.assertRaisesRegex(ValueError,'revision'):observations(bad)
