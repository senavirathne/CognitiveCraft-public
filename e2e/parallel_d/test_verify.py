import json, tempfile, unittest
from pathlib import Path
from common import HERE,read,sha,write,contract_check
from verify import validate_manifest,fixture_observations,xml_cases,metric,unique

class EvidenceVerifierTest(unittest.TestCase):
    def manifest_fixture(self,root):
        (root/'production.jar').write_bytes(b'synthetic:manifest-negative-control-only')
        write(root/'manifest.json',{'files':[{'path':'production.jar','sha256':sha(root/'production.jar'),'bytes':(root/'production.jar').stat().st_size}]})
    def test_mutated_jar_hash_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);self.manifest_fixture(root);validate_manifest(root)
            (root/'production.jar').write_bytes(b'swapped')
            with self.assertRaisesRegex(ValueError,'hash'):validate_manifest(root)
    def test_missing_raw_snapshot_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);self.manifest_fixture(root);(root/'production.jar').unlink()
            with self.assertRaisesRegex(ValueError,'missing'):validate_manifest(root)
    def test_duplicate_manifest_path_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);self.manifest_fixture(root);m=read(root/'manifest.json');m['files']*=2;write(root/'manifest.json',m)
            with self.assertRaisesRegex(ValueError,'duplicate'):validate_manifest(root)
    def test_artifact_path_escape_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);write(root/'manifest.json',{'files':[{'path':'../foreign'}]})
            with self.assertRaisesRegex(ValueError,'escapes'):validate_manifest(root)
    def test_unmanifested_frame_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);self.manifest_fixture(root);(root/'invented-snapshot.json').write_text('{}')
            with self.assertRaisesRegex(ValueError,'unmanifested'):validate_manifest(root)
    def fixture_lines(self):
        return ['CC_D00_FIXTURE '+json.dumps({'id':f['id'],'actual':f['expected']}) for f in read(HERE/'contracts/fixtures-index.json')['fixtures']]
    def test_missing_or_duplicate_fixture_id_rejected(self):
        lines=self.fixture_lines();self.assertEqual(len(fixture_observations('\n'.join(lines))),57)
        for rows in (lines[:-1],lines+[lines[0]]):
            with self.assertRaises(ValueError):fixture_observations('\n'.join(rows))
    def test_claimed_pass_cannot_replace_frozen_disposition(self):
        lines=self.fixture_lines();lines[0]=lines[0].replace('VALID','PASS')
        with self.assertRaisesRegex(ValueError,'disagrees'):fixture_observations('\n'.join(lines))
    def test_xml_failures_and_skips_are_not_passes(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'results.xml';p.write_text('<testsuite><testcase name="a"><failure/></testcase><testcase name="b"><skipped/></testcase></testsuite>')
            rows,_=xml_cases(p);counts=metric(2,rows,['results.xml'])
            self.assertEqual((0,1,1),(counts['passed'],counts['failed'],counts['inconclusive']))
    def test_duplicate_case_ids_and_inflated_counts_rejected(self):
        with self.assertRaisesRegex(ValueError,'duplicate'):unique([{'id':'same'},{'id':'same'}])
        with self.assertRaisesRegex(ValueError,'inflated'):metric(0,[{'status':'PASS'}],[])
    def test_frozen_contract_and_manifest_hash(self):
        contract_check()
