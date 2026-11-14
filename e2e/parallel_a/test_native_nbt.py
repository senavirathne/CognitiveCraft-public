import tempfile,unittest
from pathlib import Path
from native_nbt import read_nbt,chunk

class NativeNbtOracleTest(unittest.TestCase):
    def test_known_named_compound_string_and_integer_vector(self):
        raw=bytes.fromhex('0a00000800036b6579000568656c6c6f030005636f756e740000000100')
        self.assertEqual({'key':'hello','count':1},read_nbt(raw))
    def test_truncated_and_trailing_native_bytes_refuse_evidence(self):
        raw=bytes.fromhex('0a000000')
        with self.assertRaises(ValueError):read_nbt(raw[:-1])
        with self.assertRaises(ValueError):read_nbt(raw+b'\x00')
    def test_duplicate_names_cannot_replace_an_original_native_value(self):
        with self.assertRaises(ValueError):read_nbt(bytes.fromhex('0a00000100017801010001780200'))
    def test_region_cannot_reference_an_unallocated_sector(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'r.0.0.mca';data=bytearray(8192);data[:4]=bytes.fromhex('00000301');p.write_bytes(data)
            with self.assertRaises(ValueError):chunk(p,0,0)
    def test_final_native_sector_requires_payload_but_not_unused_padding(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'r.0.0.mca';data=bytearray(8192);data[:4]=bytes.fromhex('00000201')
            data+=bytes.fromhex('00000005030a000000');p.write_bytes(data)
            self.assertEqual({},chunk(p,0,0))
            p.write_bytes(data[:-1])
            with self.assertRaises(ValueError):chunk(p,0,0)

if __name__=='__main__':unittest.main()
