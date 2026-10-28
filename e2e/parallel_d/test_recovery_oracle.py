import tempfile,unittest
from pathlib import Path
from common import sha,write,read
from test_registry_files import envelope,payload
from recovery_oracle import check_case,verify

class RecoveryOracleTest(unittest.TestCase):
    def fixture(self,root):
        case='error-BEFORE_PENDING';d=root/case;d.mkdir();f=d/'metadata.current.json';f.write_bytes(envelope(payload(0)));digest=sha(f)
        frame={'id':case,'tier':'METADATA_FILESYSTEM_ONLY','cut':'BEFORE_PENDING','before_reopen':{'metadata.current.json':digest},'after_reopen':{'metadata.current.json':digest},'reopened_read_only':False,'ack_status':'IO_ERROR','process_exit':None,'files':[{'path':'metadata.current.json','sha256':digest,'bytes':f.stat().st_size}]}
        write(d/'observation.json',frame);return case,d
    def test_missing_recovery_frame_is_not_a_pass(self):
        with tempfile.TemporaryDirectory()as d:
            with self.assertRaisesRegex(ValueError,'missing'):verify(Path(d),['error-BEFORE_PENDING'])
    def test_mutated_recovery_file_hash_is_rejected(self):
        with tempfile.TemporaryDirectory()as d:
            root=Path(d);case,folder=self.fixture(root);check_case(root,case)
            (folder/'metadata.current.json').write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError,'hash'):check_case(root,case)
    def test_rehashed_corrupt_payload_still_fails_its_checksum(self):
        with tempfile.TemporaryDirectory()as d:
            root=Path(d);case,folder=self.fixture(root);file=folder/'metadata.current.json';value=read(file);value['checksum_sha256']='0'*64;write(file,value)
            frame=read(folder/'observation.json');digest=sha(file);frame['before_reopen']['metadata.current.json']=digest;frame['after_reopen']['metadata.current.json']=digest;frame['files'][0].update(sha256=digest,bytes=file.stat().st_size);write(folder/'observation.json',frame)
            with self.assertRaisesRegex(ValueError,'checksum'):check_case(root,case)
