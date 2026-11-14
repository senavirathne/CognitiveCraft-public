import hashlib,json,stat,tempfile,unittest,zipfile,warnings
from pathlib import Path
from downloaded_replay import safe_zip,valid_manifest,same_review,display

class DownloadedReplayTests(unittest.TestCase):
    def test_full_outer_bytes_and_crc_are_required(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'data.zip'
            with zipfile.ZipFile(p,'w') as z:z.writestr('native/result.json','{"real":true}')
            size=p.stat().st_size;sha=hashlib.sha256(p.read_bytes()).hexdigest()
            self.assertEqual(safe_zip(p,size,sha),1)
            with self.assertRaises(AssertionError):safe_zip(p,size+1,sha)
            with self.assertRaises(AssertionError):safe_zip(p,size,'0'*64)
            body=bytearray(p.read_bytes());body[40]^=1;p.write_bytes(body)
            with self.assertRaises((AssertionError,zipfile.BadZipFile)):safe_zip(p)

    def test_paths_duplicates_and_symlinks_are_rejected_before_extraction(self):
        with tempfile.TemporaryDirectory() as d:
            for name in ('../escape','/absolute','folder\\escape'):
                p=Path(d)/'bad.zip'
                with zipfile.ZipFile(p,'w') as z:z.writestr(name,b'not-extracted')
                with self.subTest(name=name),self.assertRaises(AssertionError):safe_zip(p)
            p=Path(d)/'duplicates.zip'
            with warnings.catch_warnings():
                warnings.simplefilter('ignore')
                with zipfile.ZipFile(p,'w') as z:z.writestr('same',b'a');z.writestr('same',b'b')
            with self.assertRaises(AssertionError):safe_zip(p)
            p=Path(d)/'symlink.zip';entry=zipfile.ZipInfo('link');entry.create_system=3;entry.external_attr=(stat.S_IFLNK|0o777)<<16
            with zipfile.ZipFile(p,'w') as z:z.writestr(entry,'../outside')
            with self.assertRaises(AssertionError):safe_zip(p)
            self.assertFalse((Path(d).parent/'escape').exists())

    def test_every_manifest_part_and_whole_archive_size_must_agree(self):
        row={'schema':1,'archiveBytes':2,'archiveSha256':'a'*64,'parts':[{'name':'part-000.bin','bytes':2,'sha256':'b'*64}]}
        self.assertEqual(valid_manifest(row),row['parts'])
        for changed in [
            {**row,'archiveBytes':3},
            {**row,'parts':[{'name':'part-001.bin','bytes':2,'sha256':'b'*64}]},
            {**row,'parts':row['parts']*2},
            {**row,'archiveSha256':'not-a-digest'}]:
            with self.assertRaises(AssertionError):valid_manifest(changed)

    def test_semantically_equal_but_different_reader_bytes_are_not_accepted(self):
        body='{\n  "status": "PASS"\n}\n';same_review(body,body)
        with self.assertRaises(AssertionError):same_review(body,'{"status":"PASS"}\n')
        with self.assertRaises(AssertionError):same_review(body,'{"status":"FAIL"}\n')

    def test_only_map_display_order_is_normalized_for_the_same_execution_multiset(self):
        a='[16] args, {item={right={int=1}, left={bool=true}, operator=ADD}}, TYPE_MISMATCH'
        b='[16] args, {item={operator=ADD, left={bool=true}, right={int=1}}}, TYPE_MISMATCH'
        self.assertEqual(display(a),display(b))
        self.assertNotEqual(display(a),display(b.replace('int=1','int=2')))

if __name__=='__main__':unittest.main()
