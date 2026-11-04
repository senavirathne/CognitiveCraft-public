"""Transport integrity controls; these synthetic bytes qualify no native case."""
import hashlib,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
from recovery_archive import split

class RecoveryArchiveTest(unittest.TestCase):
    def test_every_original_byte_is_retained_in_order_with_independent_part_hashes(self):
        with tempfile.TemporaryDirectory() as folder,patch('recovery_archive.PART_BYTES',16):
            root=Path(folder);body=bytes(range(53));source=root/'input.zip';source.write_bytes(body)
            value=split(source,root/'parts',hashlib.sha256(body).hexdigest())
            restored=b''
            for part in value['parts']:
                data=(root/'parts'/part['name']).read_bytes()
                self.assertEqual(len(data),part['bytes']);self.assertEqual(hashlib.sha256(data).hexdigest(),part['sha256']);restored+=data
            self.assertEqual(restored,body);self.assertEqual(value,json.loads((root/'parts/parts-manifest.json').read_text()))
    def test_wrong_archive_identity_and_untransportable_extra_part_are_refused(self):
        with tempfile.TemporaryDirectory() as folder,patch('recovery_archive.PART_BYTES',10):
            root=Path(folder);source=root/'input.zip';source.write_bytes(b'x'*41)
            with self.assertRaises(AssertionError):split(source,root/'wrong','0'*64)
            with self.assertRaises(AssertionError):split(source,root/'too-many',hashlib.sha256(source.read_bytes()).hexdigest())
            self.assertEqual(list((root/'too-many').iterdir()),[])

if __name__=='__main__':unittest.main()
