"""Independent on-disk fixtures exercise the actual Java reader/registry, not JSON pass flags."""
import copy, hashlib, json, os, subprocess, tempfile, unittest, uuid
from pathlib import Path
from common import HERE, REPO, RELEASE, read, require

WORLD='7b2d1a66-d01d-4000-8000-000000000001'
MAIN='dev.aivillages.core.kernel.storage.StorageMetadataProbe'

def canonical(value):
    return (json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'))+'\n').encode()

def envelope(payload):
    return canonical({'checksum_sha256':hashlib.sha256(canonical(payload)).hexdigest(),'payload':payload})

def payload(count=1):
    original=read(HERE/'contracts/fixtures/positive-StorageRecord.json')['message']['body']
    records=[]
    for index in range(count):
        row=copy.deepcopy(original);row.update(world_id=WORLD,storage_id=str(uuid.UUID(int=index+1)),state='PENDING_BIND',acl=[],roles=[],acl_revision=0)
        row['stamp']={'schema_version':1,'revision':0,'generation':1};records.append(row)
    return {'schema':'cc.storage-metadata/1','world_id':WORLD,'revision':0,'records':records,'assignments':[],'bindings':[]}

def probe(action,path,world=WORLD):
    override=os.getenv('CC_D_METADATA_TEST_CP')
    if override:classpath=override
    else:
        tests=REPO/'core/build/classes/java/test'
        require((tests/Path(MAIN.replace('.','/')+'.class')).is_file(),'compiled metadata probe missing')
        jars=sorted((REPO/'build/server-sdk/libs').glob('gson-*.jar'));require(len(jars)==1,'installed runtime Gson identity missing/ambiguous')
        require(RELEASE.is_file(),'built release missing')
        classpath=os.pathsep.join(map(str,[tests,RELEASE,jars[0]]))
    result=subprocess.run([os.getenv('CC_D_METADATA_TEST_JAVA','java'),'-cp',classpath,MAIN,action,world,str(path)],capture_output=True,text=True,timeout=45)
    lines=[line for line in result.stdout.splitlines()if line.startswith('{')]
    require(lines,'Java probe produced no observation: '+result.stderr[-500:])
    return result.returncode,json.loads(lines[-1])

class RegistryFilesTest(unittest.TestCase):
    def fixture(self,directory,value):
        root=Path(directory)/'metadata';root.mkdir();file=root/'metadata.current.json';file.write_bytes(envelope(value));return root,file
    def assert_read_only_unchanged(self,root,file):
        before=file.read_bytes();code,result=probe('open',root)
        self.assertEqual(code,0);self.assertTrue(result['read_only'],result);self.assertEqual(file.read_bytes(),before)
        self.assertFalse((root/'metadata.pending.json').exists());self.assertFalse((root/'metadata.previous.json').exists())
    def test_valid_private_snapshot_is_readable_without_any_inventory_records(self):
        with tempfile.TemporaryDirectory()as d:
            root,file=self.fixture(d,payload());code,result=probe('validate',file)
            self.assertEqual((code,result['records']),(0,1));self.assertEqual(result['status'],'VALID')
            code,result=probe('open',root);self.assertEqual(code,0);self.assertFalse(result['read_only'])
            body=json.loads(file.read_bytes())['payload'];self.assertEqual(set(body),{'schema','world_id','revision','records','assignments','bindings'})
            self.assertEqual(body['records'][0]['acl'],[])
    def test_unknown_schema_with_valid_checksum_opens_read_only(self):
        with tempfile.TemporaryDirectory()as d:
            value=payload();value['schema']='cc.storage-metadata/2';root,file=self.fixture(d,value)
            self.assertEqual(probe('validate',file)[0],2);self.assert_read_only_unchanged(root,file)
    def test_truncated_payload_preserves_originals_read_only(self):
        with tempfile.TemporaryDirectory()as d:
            root,file=self.fixture(d,payload());file.write_bytes(file.read_bytes()[:-17])
            self.assertEqual(probe('validate',file)[0],2);self.assert_read_only_unchanged(root,file)
    def test_4097th_record_refused_without_evicting_any_of_4096_identities(self):
        with tempfile.TemporaryDirectory()as d:
            root,file=self.fixture(d,payload(4096));before=file.read_bytes();code,seen=probe('validate',file)
            self.assertEqual((code,seen['records']),(0,4096));code,result=probe('register',root)
            self.assertEqual((code,result['status'],result['records']),(0,'LIMIT',4096));self.assertEqual(file.read_bytes(),before)
            oversized=payload(4097);file.write_bytes(envelope(oversized));self.assertEqual(probe('validate',file)[0],2)
    def test_missing_acl_is_not_imported_as_public_or_permissive(self):
        with tempfile.TemporaryDirectory()as d:
            value=payload();del value['records'][0]['acl'];root,file=self.fixture(d,value)
            self.assertEqual(probe('validate',file)[0],2);self.assert_read_only_unchanged(root,file)
    def test_inventory_or_credit_fields_are_rejected(self):
        for field in ('inventory_counts','jobs','credits'):
            with self.subTest(field=field),tempfile.TemporaryDirectory()as d:
                value=payload();value[field]={'minecraft:oak_log':64};root,file=self.fixture(d,value)
                self.assertEqual(probe('validate',file)[0],2);self.assert_read_only_unchanged(root,file)
    def test_forged_checksum_cannot_authorize_file_repair(self):
        with tempfile.TemporaryDirectory()as d:
            root,file=self.fixture(d,payload());value=json.loads(file.read_bytes());value['checksum_sha256']='0'*64;file.write_bytes(canonical(value))
            self.assertEqual(probe('validate',file)[0],2);self.assert_read_only_unchanged(root,file)
    def test_foreign_world_cannot_be_opened_for_mutation(self):
        with tempfile.TemporaryDirectory()as d:
            root,file=self.fixture(d,payload());before=file.read_bytes();code,result=probe('open',root,str(uuid.UUID(int=999)))
            self.assertEqual(code,0);self.assertTrue(result['read_only']);self.assertEqual(file.read_bytes(),before)
