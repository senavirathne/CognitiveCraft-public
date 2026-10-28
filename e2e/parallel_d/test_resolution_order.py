"""Execute the real Java resolver; use an independent Python rank and limit model."""
import copy,json,os,subprocess,tempfile,unittest
from common import *
from resolution_oracle import observations,verify

class ResolutionOrderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary=tempfile.TemporaryDirectory(prefix='cc-d04-resolution-');cls.root=Path(os.getenv('CC_D_RESOLUTION_EVIDENCE',Path(cls.temporary.name)/'evidence'));require(not cls.root.exists(),'refuse overwrite of resolution evidence');cls.root.mkdir(parents=True)
        cp=os.getenv('CC_D_RESOLUTION_TEST_CP')
        if not cp:
            gson=sorted((REPO/'build/server-sdk/libs').glob('gson-*.jar'));require(len(gson)==1,'resolution Gson ambiguous');cp=os.pathsep.join(map(str,[REPO/'core/build/classes/java/test',RELEASE,gson[0]]))
        result=subprocess.run([os.getenv('CC_D_RESOLUTION_TEST_JAVA','java'),'-cp',cp,'dev.aivillages.core.kernel.storage.StorageResolutionProbe',str(HERE/'campaigns/D04-resolution-22.json'),str(cls.root)],capture_output=True,timeout=120)
        (cls.root/'probe.stdout.log').write_bytes(result.stdout);(cls.root/'probe.stderr.log').write_bytes(result.stderr);require(result.returncode==0,'Java resolution probe failed: '+result.stderr.decode(errors='replace')[-2000:]);cls.rows=[json.loads(s)for s in (cls.root/'observations.jsonl').read_text().splitlines()]
    @classmethod
    def tearDownClass(cls):cls.temporary.cleanup()
    def test_shuffled_rows_preserve_exact_nearest_tie_break(self):
        result=verify(self.root);self.assertEqual(result['shuffled_complete_queries'],20);self.assertEqual(result['unknown_limit_queries'],2);self.assertEqual(result['native_executions'],0)
    def test_incomplete_129_candidates_cannot_publish_partial_winner(self):
        bad=copy.deepcopy(self.rows);bad[20]['status']='RESOLVED';bad[20]['storage_id']=bad[20]['input'][0]['storage_id']
        with self.assertRaisesRegex(ValueError,'partial'):observations(bad)
    def test_missing_duplicate_swapped_principal_or_position_is_rejected(self):
        for bad in (self.rows[:-1],self.rows+[self.rows[0]]):
            with self.assertRaises(ValueError):observations(bad)
        for key in ('principal','position'):
            bad=copy.deepcopy(self.rows)
            if key=='principal':bad[0]['scope']['principal_id']='00000000-0000-4000-8000-000000000000'
            else:bad[0]['input'][0]['x']+=1
            with self.assertRaises(ValueError):observations(bad)
    def test_same_tick_overwork_and_fabricated_save_credit_are_rejected(self):
        for key in ('tick','native_observations','metadata_inspected','eligible_candidates','participant_save'):
            bad=copy.deepcopy(self.rows)
            if key=='participant_save':bad[0][key]='invented'
            else:bad[0]['slices'][0][key]+=1
            with self.assertRaises(ValueError):observations(bad)
