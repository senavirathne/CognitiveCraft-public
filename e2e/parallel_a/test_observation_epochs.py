"""A stopped observer tail stays unknown; no concatenation, JSON repair, or invented frame."""
import hashlib,json,tempfile,unittest
from pathlib import Path
from freeze_storage_review import frames

class ObservationEpochs(unittest.TestCase):
    def epoch(self,root,stamp,pid):
        return root/('observations.epoch-'+str(stamp).zfill(19)+'-'+str(pid)+'-12345678-1234-1234-1234-123456789abc.jsonl')
    def test_incomplete_old_epoch_does_not_consume_a_complete_cold_frame(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);first={'time':10,'pid':7};second={'time':20,'pid':8};tail=b'{"time":11,"nativeProof":'
            old=self.epoch(root,1,7);old.write_bytes(json.dumps(first).encode()+b'\n'+tail)
            self.epoch(root,2,8).write_text(json.dumps(second)+'\n');before=old.read_bytes();tails=[]
            self.assertEqual([first,second],list(frames(root,'observations',tails=tails)))
            self.assertEqual(before,old.read_bytes());self.assertEqual(hashlib.sha256(tail).hexdigest(),tails[0]['sha256'])
            self.assertEqual('UNKNOWN_INCOMPLETE_FRAME_NOT_NATIVE_PROOF',tails[0]['classification'])
            with self.assertRaises(AssertionError):list(frames(root,'observations'))
    def test_a_completed_corrupt_line_and_ambiguous_format_remain_failures(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);p=self.epoch(root,1,7);p.write_bytes(b'{"broken":\n')
            with self.assertRaises(json.JSONDecodeError):list(frames(root,'observations',tails=[]))
            p.write_text('{"time":1,"pid":7}\n');(root/'observations.jsonl').write_text('{"time":1,"pid":7}\n')
            with self.assertRaises(AssertionError):list(frames(root,'observations',tails=[]))
    def test_epoch_pid_must_match_the_exact_complete_frame(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);self.epoch(root,1,7).write_text('{"time":1,"pid":8}\n')
            with self.assertRaises(AssertionError):list(frames(root,'observations',tails=[]))

if __name__=='__main__':unittest.main()
