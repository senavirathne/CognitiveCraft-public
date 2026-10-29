"""Synthetic consistency/tamper checks. They execute zero native or client cases."""
import copy,math,unittest
from common import HERE,read
from handoff_oracle import trace

def existing():return copy.deepcopy(read(HERE/'fixtures/D09-handoff-model.json')['existing'])

def storage():
    source=copy.deepcopy(read(HERE/'fixtures/D09-handoff-model.json')['storage']);before,pickup,accepted,terminal=source;tick=accepted['tick'];accepted['route']['acceptedTick']=tick;accepted['route']['deadline']=tick+600;route=accepted['route'];rows=[before,pickup,accepted];pos=accepted['sender_pos'][:];target=accepted['recipient_pos'];distance=0
    def point(p):return {'dimension':route['endpoint']['dimension'],'x':p[0],'y':p[1],'z':p[2]}
    detour=accepted['source_binding']['members'][0]['z']-7+11.6
    for endpoint in ([pos[0],pos[1],detour],[target[0]-1,target[1],detour],[target[0]-1,target[1],target[2]]):
        for axis in (0,2):
            while abs(endpoint[axis]-pos[axis])>.000001:
                old=pos[:];pos=pos[:];pos[axis]+=math.copysign(min(.1,abs(endpoint[axis]-pos[axis])),endpoint[axis]-pos[axis]);distance+=math.dist(old,pos);tick+=1;row=copy.deepcopy(accepted);row.update(event='MOVE',tick=tick,sender_pos=pos[:],path_nodes=32,path_length=24,checked_chunks=25,operation_ns=1000);row['recipient_projection']['tick']=tick;row['detail']={'status':'MOVING','blocker':'NONE','tick':tick,'feet':point(pos),'endpoint':route['endpoint'],'travelBlocks':distance,'elapsedTicks':tick-route['acceptedTick'],'lastProgressTick':tick,'lastProgress':point(pos),'repathProposals':1,'standingCandidates':1};rows.append(row)
    boundary=copy.deepcopy(rows[-1]);boundary.update(event='BEFORE_HANDOFF',detail=None);rows.append(boundary);terminal.update(tick=tick,sender_pos=pos,route=route,operation_ns=1000);terminal['recipient_projection']['tick']=tick;rows.append(terminal);return rows

class HandoffOracleTest(unittest.TestCase):
    def check(self,rows,name=None):return trace(rows,'SYNTHETIC_D09',rows[0]['world'],[name or rows[0]['campaign']])
    def test_synthetic_consistency_has_zero_physical_case_credit(self):
        for rows in (existing(),storage()):self.assertEqual(self.check(rows)['scenario_credit'],0)
    def test_swapped_recipient_or_principal_cannot_reroute(self):
        for kind in ('principal','entity','generation'):
            rows=existing()
            if kind=='principal':rows[-2]['principal']='foreign'
            elif kind=='entity':rows[-2]['recipient_entity']='namesake'
            else:rows[-2]['recipient_projection']['generation']+=1
            with self.assertRaises(ValueError):self.check(rows)
    def test_wrong_delta_old_stock_and_ungranted_source_are_rejected(self):
        for kind in ('delta','grant','permission'):
            rows=existing()
            if kind=='delta':rows[-2]['sender'][0]['count']+=4
            elif kind=='grant':rows[1]['selected_source']['slots'][0]['quantity']=3
            else:rows[-2]['control']['existingSourceAuthorized']=False
            with self.assertRaises(ValueError):self.check(rows)
    def test_item_entity_substitution_cannot_count_as_handoff(self):
        rows=existing();rows[-2]['item_entities'].append({'entity':'fabricated-drop','count':4,'item':rows[0]['goal']['item']})
        with self.assertRaisesRegex(ValueError,'item entity'):self.check(rows)
    def test_missing_tick_teleport_and_reset_allowance_are_rejected(self):
        for kind in ('missing','teleport','clock'):
            rows=storage()
            if kind=='missing':del rows[12]
            elif kind=='teleport':rows[12]['sender_pos'][0]+=4
            else:rows[12]['route']['acceptedTick']+=1
            with self.assertRaises(ValueError):self.check(rows)
    def test_reach_protection_and_atomic_profile_remain_mandatory(self):
        for kind in ('reach','profile','protection'):
            rows=existing()
            if kind=='reach':rows[-2]['recipient_pos'][0]+=5
            elif kind=='profile':rows[-2]['recipient_inventory_class']='UnknownContainer'
            else:rows[-2]['control']['protectionAllowed']=False
            with self.assertRaises(ValueError):self.check(rows)
    def test_refusal_cannot_mutate_or_disclose_private_counts(self):
        rows=existing()[:-1]
        for row in rows:row['campaign']='RIVAL_COURIER'
        terminal=rows[-1];terminal.update(sender=copy.deepcopy(rows[-2]['sender']),recipient=copy.deepcopy(rows[-2]['recipient']),phase='BLOCKED',delivered=0,in_flight=4);terminal['control']['exclusiveRecipient']=False;terminal['detail']={'status':'REFUSED','reason':'UNAUTHORIZED','quantity':0,'before':None,'after':None,'location':'handoff unavailable; owner revalidation required'};self.check(rows)
        for kind in ('leak','mutation'):
            bad=copy.deepcopy(rows)
            if kind=='leak':bad[-1]['detail']['after']={'source':0,'carrier':9,'destination':1}
            else:bad[-1]['sender'][0]['count']=8
            with self.assertRaises(ValueError):self.check(bad)
    def test_replay_duplicate_request_and_fake_save_proof_are_rejected(self):
        for kind in ('replay','duplicate','save'):
            rows=existing()
            if kind=='replay':rows[-1]['sender'][0]['count']-=1
            elif kind=='duplicate':rows.insert(2,copy.deepcopy(rows[0]))
            else:rows[-2]['save_proof']='fabricated settlement'
            with self.assertRaises(ValueError):self.check(rows)
