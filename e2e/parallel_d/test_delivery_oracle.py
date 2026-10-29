"""Synthetic courier oracle tamper checks; these never count as native or client cases."""
import copy,hashlib,json,math,unittest
from common import HERE,read
from native_oracle import canonical
from delivery_oracle import trace

def synthetic():
    base=read(HERE/'contracts/fixtures/positive-PhysicalStorageBinding.json')['message']['body'];src=copy.deepcopy(base);dst=copy.deepcopy(base);world=base['world_id'];dst['storage_id']='d0800000-0000-4000-8000-000000000099';dst['members'][0]['incarnation_id']='d0800000-0000-4000-8000-000000000098';dst['members'][0]['x']+=15
    for b in (src,dst):b['topology_sha256']=hashlib.sha256(canonical({k:b[k]for k in ('adapter_id','world_id','dimension','members','facing')})).hexdigest()
    codec='{"id":"minecraft:oak_planks","count":1}';item=dict(registryId='minecraft:oak_planks',fingerprint=hashlib.sha256(codec.encode()).hexdigest(),maxStack=64,defaultComponents=True,containerItem=False)
    def stacks(n,size,offset=0):
        return [dict(slot=i,count=n if i==offset else 0,item=item.copy()if i==offset and n else None,native_item_id='minecraft:oak_planks'if i==offset and n else None,native_codec=codec if i==offset and n else None,native_default=True,native_max_stack=64)for i in range(size)]
    def point(x,z):return dict(dimension='minecraft:overworld',x=x,y=201,z=z)
    row=dict(case='synthetic',campaign='SUCCESS',event='BEFORE',tick=10,world=world,principal='synthetic-principal',actor='synthetic-actor',request='synthetic-request',goal={'item':item,'quantity':4},source_binding=src,destination_binding=dst,source=stacks(10,27),carrier=stacks(3,8),destination=stacks(2,27),native_feet=[3.5,201,7.5],source_pos=[2,201,7],destination_pos=[17,201,7],detail=None,phase='READY',delivered=0,in_flight=0,custody_revision=0,integration_root=None,save_proof=None,forced_chunks=0,heap_used_bytes=1000000,transfer_ns=1000,route=None,path_nodes=20,path_length=24,checked_chunks=25)
    rows=[copy.deepcopy(row)];row=copy.deepcopy(row);row['source']=stacks(6,27);row['carrier'][1]=stacks(4,8,1)[1];row['event']='WITHDRAW';row['phase']='IN_FLIGHT';row['in_flight']=4;row['detail']=dict(status='LIVE_WITHDRAWN',reason='NONE',quantity=4,before=dict(source=10,carrier=3,destination=2),after=dict(source=6,carrier=7,destination=2));rows.append(copy.deepcopy(row))
    row['event']='TRAVEL_ACCEPTED';row['detail']=None;row['route']={'acceptedTick':10,'endpoint':point(18.5,7.5)};rows.append(copy.deepcopy(row));pos=[3.5,201,7.5];travel=0;tick=10
    for endpoint in ([3.5,201,11.6],[18.5,201,11.6],[18.5,201,7.5]):
        for axis in (0,2):
            while abs(endpoint[axis]-pos[axis])>.00001:
                old=pos[:];pos=pos[:];pos[axis]+=math.copysign(min(.1,abs(endpoint[axis]-pos[axis])),endpoint[axis]-pos[axis]);travel+=math.dist(old,pos);tick+=1;r=copy.deepcopy(row);r.update(event='MOVE',tick=tick,native_feet=pos);r['detail']={'step_ns':1000,'observation':dict(feet=point(pos[0],pos[2]),endpoint=point(18.5,7.5),elapsedTicks=tick-10,travelBlocks=travel,lastProgressTick=tick,lastProgress=point(pos[0],pos[2]),standingCandidates=1,repathProposals=1)};rows.append(r)
    row=copy.deepcopy(rows[-1]);row.update(event='DEPOSIT',phase='DELIVERED_LIVE',delivered=4,in_flight=0);row['carrier']=stacks(3,8);row['destination']=stacks(6,27);row['detail']=dict(status='LIVE_DEPOSITED',reason='NONE',quantity=4,before=dict(source=6,carrier=7,destination=2),after=dict(source=6,carrier=3,destination=6));rows.append(row);return rows,world
class DeliveryOracleTest(unittest.TestCase):
    def check(self,rows,world):return trace(rows,'synthetic',world,['SUCCESS'])
    def test_conservation_and_old_stock_provenance(self):
        rows,w=synthetic();self.check(rows,w)
        for field in ('source','carrier','destination'):
            bad=copy.deepcopy(rows);bad[-1][field][0]['count']+=1
            with self.assertRaises(ValueError):self.check(bad,w)
    def test_old_stock_cannot_substitute_for_attributed_slot(self):
        rows,w=synthetic();bad=copy.deepcopy(rows);bad[1]['carrier'][0]['count']=7;bad[1]['carrier'][1].update(count=0,item=None,native_item_id=None,native_codec=None)
        with self.assertRaises(ValueError):self.check(bad,w)
    def test_component_codec_or_fingerprint_swap_refused(self):
        rows,w=synthetic()
        for key,value in [('native_codec','{}'),('native_default',False),('native_item_id','minecraft:diamond')]:
            bad=copy.deepcopy(rows);bad[1]['carrier'][1][key]=value
            with self.assertRaises(ValueError):self.check(bad,w)
    def test_missing_movement_teleport_and_clock_reset_refused(self):
        rows,w=synthetic()
        for kind in ('missing','teleport','clock'):
            bad=copy.deepcopy(rows)
            if kind=='missing':del bad[10]
            elif kind=='teleport':bad[10]['native_feet'][0]+=4
            else:bad[10]['route']['acceptedTick']=11
            with self.assertRaises(ValueError):self.check(bad,w)
    def test_courier_principal_endpoint_and_fake_save_receipt_refused(self):
        rows,w=synthetic()
        for kind in ('principal','endpoint','receipt'):
            bad=copy.deepcopy(rows)
            if kind=='principal':bad[5]['principal']='foreign'
            elif kind=='endpoint':bad[5]['destination_binding']['storage_id']='foreign'
            else:bad[5]['save_proof']='fabricated'
            with self.assertRaises(ValueError):self.check(bad,w)
    def test_unearned_completion_and_empty_observations_refused(self):
        rows,w=synthetic();bad=copy.deepcopy(rows);bad[-1]['detail']['status']='REFUSED';bad[-1]['detail']['quantity']=0
        with self.assertRaises(ValueError):self.check(bad,w)
        with self.assertRaises(ValueError):self.check([],w)

    def test_live_crash_counts_cannot_be_save_or_recovery_proof(self):
        from delivery_oracle import crash_states
        def frame(event,counts):return dict(event=event,source=counts[0],carrier=counts[1],destination=counts[2],actor='synthetic-actor',world='d0200000-0000-4000-8000-000000000001',case='D08-GT01',save_proof=None,integration_root=None)
        before=[frame('SAVED_BEFORE',[10,3,2]),frame('LIVE_IN_FLIGHT',[6,7,2])];after=[frame('RESTORED_UNKNOWN_CUSTODY',[10,3,2])];crash_states(before,after)
        for kind in ('recovery','receipt','actor','world'):
            bad=copy.deepcopy(after)
            if kind=='recovery':bad[0].update(source=6,carrier=7)
            elif kind=='receipt':bad[0]['save_proof']='fabricated'
            elif kind=='actor':bad[0]['actor']='foreign'
            else:bad[0]['world']='foreign'
            with self.assertRaises(ValueError):crash_states(before,bad)
