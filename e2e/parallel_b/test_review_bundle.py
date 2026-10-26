import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from parallel_b.review_bundle import STAGE,selected,validate_native

class ReviewTransportTests(unittest.TestCase):
    def fixture(self,root):
        base=root/STAGE;base.mkdir(parents=True);jar=root/'fabric/build/libs/ai-villages-0.1.0.jar';jar.parent.mkdir(parents=True);jar.write_bytes(b'fixture:not-a-native-binary')
        metadata={'sourceCommit':'fixture:head','sourceTree':'fixture:tree','dirty':False,'profile':'B06','ci':{'GITHUB_RUN_ID':'7','GITHUB_RUN_ATTEMPT':'1'},'releaseSha256':hashlib.sha256(jar.read_bytes()).hexdigest()}
        (base/'metadata.json').write_text(json.dumps(metadata));(base/'result.json').write_text(json.dumps({'status':'PASS','rows':[{}]*22}))
        return base,jar,metadata

    def test_transport_is_separate_from_native_identity_and_required_outcome(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);base,jar,metadata=self.fixture(root)
            validate_native(root,'fixture:head','fixture:tree',7)
            for head,tree,run in [('foreign','fixture:tree',7),('fixture:head','foreign',7),('fixture:head','fixture:tree',8)]:
                with self.assertRaises(ValueError):validate_native(root,head,tree,run)
            (base/'result.json').write_text(json.dumps({'status':'FAIL','rows':[{}]*22}))
            with self.assertRaises(ValueError):validate_native(root,'fixture:head','fixture:tree',7)
            (base/'result.json').write_text(json.dumps({'status':'PASS','rows':[{}]*21}))
            with self.assertRaises(ValueError):validate_native(root,'fixture:head','fixture:tree',7)

    def test_wrong_binary_and_live_world_inputs_cannot_be_substituted(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);_,jar,_=self.fixture(root);jar.write_bytes(b'fixture:wrong-binary')
            with self.assertRaises(ValueError):validate_native(root,'fixture:head','fixture:tree',7)
        self.assertFalse(selected(Path('server/observations.jsonl')))
        self.assertFalse(selected(Path('server/game/world/level.dat')))
        self.assertTrue(selected(Path('cases/00-260026-stable-log.json')))
        self.assertTrue(selected(Path('server/b-guards.jsonl')))

if __name__=='__main__':unittest.main()
