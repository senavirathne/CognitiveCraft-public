import base64,copy,hashlib,json,struct,tempfile,unittest,zlib
from pathlib import Path
from common import HERE,read,write
from native_oracle import WORLD,PRINCIPAL,coherent,topology,xml,physical,performance,PREFIX,METHODS
from native_nbt import saved_entities

class NativeOracleTest(unittest.TestCase):
    def test_swapped_native_world_or_principal_refused(self):
        good={'world':WORLD,'principal':PRINCIPAL,'event':'UNMARKED','binding':None,'resolved':None};coherent(good)
        for key in ('world','principal'):
            bad=dict(good);bad[key]='00000000-0000-4000-8000-000000000000'
            with self.assertRaisesRegex(ValueError,'swapped'):coherent(bad)
    def test_actual_owned_xml_missing_duplicate_failure_refused(self):
        text='<testcase classname="native" name="'+PREFIX+METHODS['incarnation']+'"/>'
        with tempfile.TemporaryDirectory()as d:
            path=Path(d)/'native.xml'
            path.write_text('<testsuite><testcase name="minecraft:always_pass"/>'+text+'</testsuite>');xml(path,'incarnation')
            for rows in ('',text+text,text.replace('/>','><failure/></testcase>')):
                path.write_text('<testsuite><testcase name="minecraft:always_pass"/>'+rows+'</testsuite>')
                with self.assertRaises(ValueError):xml(path,'incarnation')
    def test_changed_member_or_digest_cannot_be_claimed_live(self):
        good=read(HERE/'contracts/fixtures/positive-PhysicalStorageBinding.json')['message']['body'];topology(good)
        bad=copy.deepcopy(good);bad['members'][0]['marker_generation']+=1
        with self.assertRaisesRegex(ValueError,'digest'):topology(bad)
    def test_missing_and_quarantined_native_member_refused(self):
        binding=copy.deepcopy(read(HERE/'contracts/fixtures/positive-PhysicalStorageBinding.json')['message']['body']);binding['world_id']=WORLD
        shape={k:binding[k]for k in ('adapter_id','world_id','dimension','members','facing')};binding['topology_sha256']=hashlib.sha256((json.dumps(shape,ensure_ascii=False,sort_keys=True,separators=(',',':'))+'\n').encode()).hexdigest()
        rows=[]
        for m in binding['members']:
            rows.append(dict(x=m['x'],y=m['y'],z=m['z'],incarnation=m['incarnation_id'],marker_generation=m['marker_generation'],quarantined=False,facing=binding['facing'],type='SINGLE',saved_nbt=m['incarnation_id']))
        good={'event':'BOUND','world':WORLD,'principal':PRINCIPAL,'storage':binding['storage_id'],'binding_generation':binding['binding_generation'],'record_state':'ACTIVE','binding':binding,'resolved':binding,'native':rows};coherent(good)
        for key,value in [('incarnation',PRINCIPAL),('marker_generation',999),('quarantined',True),('type','LEFT')]:
            bad=copy.deepcopy(good);bad['native'][0][key]=value
            with self.assertRaises(ValueError):coherent(bad)
    def test_missing_physical_history_is_not_native_pass(self):
        with self.assertRaisesRegex(ValueError,'missing'):physical({})
    def test_fabricated_percentiles_and_regression_refused(self):
        good={'samples':list(range(2000)),'warmup':200,'p50_ns':999,'p95_ns':1899,'p99_ns':1979,'max_ns':1999,'scheduled_tick_tasks':0,'heap_used_bytes':1000};performance(good,good)
        bad=copy.deepcopy(good);bad['p99_ns']=0
        with self.assertRaisesRegex(ValueError,'percentile'):performance(good,bad)
        bad=copy.deepcopy(good);bad['samples']=[x+3000000 for x in bad['samples']]
        for key in ('p50_ns','p95_ns','p99_ns','max_ns'):bad[key]+=3000000
        with self.assertRaisesRegex(ValueError,'regression'):performance(good,bad)
    def test_actual_compressed_nbt_hash_and_bounds_required(self):
        def name(text):return struct.pack('>H',len(text))+text.encode()
        nbt=b'\x0a\x00\x00'+b'\x03'+name('xPos')+struct.pack('>i',8)+b'\x03'+name('zPos')+struct.pack('>i',8)+b'\x09'+name('block_entities')+b'\x0a\x00\x00\x00\x00'+b'\x00'
        compressed=zlib.compress(nbt);chunk=struct.pack('>i',len(compressed)+1)+b'\x02'+compressed
        witness={'native_chunk_base64':base64.b64encode(chunk).decode(),'native_chunk_sha256':hashlib.sha256(chunk).hexdigest()}
        with tempfile.TemporaryDirectory()as d:
            p=Path(d)/'chunk.json';write(p,witness);self.assertEqual(saved_entities(p),[])
            witness['native_chunk_sha256']='0'*64;write(p,witness)
            with self.assertRaisesRegex(ValueError,'bytes changed'):saved_entities(p)
