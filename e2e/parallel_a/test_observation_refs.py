import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from observation_refs import restore,frames

class ExactObservationReferences(unittest.TestCase):
    def fixture(self,root):
        body=b'{"id":"original","quantity":1,"text":"\\u003c & exact"}';digest=hashlib.sha256(body).hexdigest()
        directory=root/'observation-bodies';directory.mkdir();(directory/(digest+'.json')).write_bytes(body)
        prefix=b'{"time":1,"pid":2,"native":{"stock":1},"job":';raw=prefix+body+b'}'
        token=('{"$a04Raw":"'+digest+'"}').encode();template=prefix+token+b'}'
        wrapper={'schema':1,'time':1,'pid':2,'template':template.decode(),'originalSha256':hashlib.sha256(raw).hexdigest()}
        return wrapper,raw,directory,digest
    def test_exact_bytes_and_native_observation_survive_reference_round_trip(self):
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as temp:
            root=Path(temp);wrapper,raw,bodies,_=self.fixture(root)
            self.assertEqual(raw,restore(wrapper,bodies))
            (root/'observations.refs-000.jsonl').write_text(json.dumps(wrapper)+'\n')
            self.assertEqual([json.loads(raw)],list(frames(root)))
    def test_corrupt_body_is_never_accepted_as_original_evidence(self):
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as temp:
            wrapper,_,bodies,digest=self.fixture(Path(temp));(bodies/(digest+'.json')).write_bytes(b'wrong')
            with self.assertRaises(ValueError):restore(wrapper,bodies)
    def test_missing_body_does_not_prove_an_observation(self):
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as temp:
            wrapper,_,bodies,digest=self.fixture(Path(temp));(bodies/(digest+'.json')).unlink()
            with self.assertRaises(ValueError):restore(wrapper,bodies)
    def test_wrong_original_frame_hash_is_rejected(self):
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as temp:
            wrapper,_,bodies,_=self.fixture(Path(temp));wrapper['originalSha256']='0'*64
            with self.assertRaises(ValueError):restore(wrapper,bodies)
    def test_intentional_kill_tail_is_retained_and_explicitly_unobserved(self):
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as temp:
            root=Path(temp);wrapper,raw,_,_=self.fixture(root);(root/'observations.refs-000.jsonl').write_text(json.dumps(wrapper)+'\n'+ '{"partial":')
            with self.assertRaises(ValueError):list(frames(root))
            tails=[];self.assertEqual([json.loads(raw)],list(frames(root,tails=tails)));self.assertEqual(1,len(tails));self.assertEqual(11,tails[0]['bytes'])

if __name__=='__main__':unittest.main()
