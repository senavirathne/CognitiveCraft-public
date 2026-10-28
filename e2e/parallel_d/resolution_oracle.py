"""Independent distance/order/bound model over actual Java query observations; no native credit."""
import json,uuid
from common import *
SCHEDULE_SHA='e6fcd85c0d20a23d3e8be8afb6994a330b13cbf8a47e41ff47e86fa44c648d3e'

def campaign():
    p=HERE/'campaigns/D04-resolution-22.json';require(sha(p)==SCHEDULE_SHA,'resolution schedule changed');v=read(p)
    require(v['seed']==20261027 and len(v['cases'])==22,'resolution denominator');return v

def observations(rows):
    schedule=campaign();expected=schedule['cases'];require(len(rows)==22 and [r['id']for r in rows]==[r['id']for r in expected],'missing/duplicate resolution observations')
    times=[];winners=[];slices=0
    for r,s in zip(rows,expected):
        require(r['tier']=='PURE_RESOLUTION_PORTS'and r['seed']==s['seed'],'false native credit/swapped seed')
        require(r['scope']=={'world_id':'d0400000-0000-4000-8000-000000000001','domain_id':'d0400000-0000-4000-8000-000000000002','principal_id':'d0400000-0000-4000-8000-000000000003'},'swapped resolution principal')
        require(r['integration_root']is None and r['participant_save']is None,'fabricated resolution settlement')
        inputs=r['input'];require(len(inputs)==s['rows']and len({p['storage_id']for p in inputs})==len(inputs),'missing/duplicate candidates')
        # Independently reconstruct the frozen schedule, not a reported expected winner.
        actual={p['storage_id']:p for p in inputs}
        for i in range(s['rows']):
            p=actual.get(f'd0400000-0000-4000-8000-{100+i:012d}');require(p is not None,'swapped candidate UUID')
            if i<8:x=(-1 if i%2==0 else 1)if s['tie']=='x'else 0;y=(63 if i%2==0 else 65)if s['tie']=='y'else 64;z=(-1 if i%2==0 else 1)if s['tie']=='z'else 0
            else:x=2+i%8;y=64;z=(i//8)%5-2
            require((p['x'],p['y'],p['z'])==(x,y,z),'swapped candidate position')
        require(r['anchor']=={'x_milli':500,'y_milli':64500,'z_milli':500},'anchor changed')
        selector={'kind':'NAMED','label':'absent','container_type':'ANY_MVP'}if s['rows']==257 else {'kind':'NEAREST_PUBLIC','anchor':'CALLER','container_type':'ANY_MVP'}
        require(r['selector']==selector,'selector changed')
        def rank(p):return (p['x']**2+(p['y']-64)**2+p['z']**2,p['x'],p['y'],p['z'],p['storage_id'])
        winner=min(inputs,key=rank)['storage_id']if s['expected']=='RESOLVED'else None
        require(r['status']==s['expected']and r['storage_id']==winner,'partial scan or wrong nearest tie-break')
        if winner:winners.append(winner)
        expected_slices=(min(s['rows'],256)+15)//16
        require(len(r['slices'])==expected_slices,'slice bound/count changed');previous=0
        for i,step in enumerate(r['slices']):
            require(step['tick']==100+i,'same tick scanned more than once');status=s['expected']if i==expected_slices-1 else 'PENDING';require(step['status']==status,'early publication')
            observed=step['native_observations'];delta=observed-previous
            # Complete queries have one extra observation of the already-inspected winner, not a seventeenth record.
            final_extra=1 if status=='RESOLVED'else 0
            expected_delta=0 if s['rows']==257 or s['rows']==129 and i==expected_slices-1 else min(16,s['rows']-16*i)+final_extra
            require(delta==expected_delta,'per-tick native observation bound')
            require(step['metadata_inspected']==min((i+1)*16,s['rows'],256),'metadata slice/limit changed');require(step['eligible_candidates']==(0 if s['rows']==257 else min((i+1)*16,s['rows'])),'candidate bound changed')
            previous=observed;require(type(step['elapsed_ns'])is int and step['elapsed_ns']>=0,'timing missing');times.append(step['elapsed_ns']);slices+=1
    require(len(set(winners))==1,'shuffled candidate order changed winner');times.sort()
    return {'queries':22,'shuffled_complete_queries':20,'unknown_limit_queries':2,'slices':slices,'winner':winners[0],'step_ns':{k:times[min(len(times)-1,int(len(times)*p)-1)]for k,p in [('p50',.5),('p95',.95),('p99',.99),('max',1)]},'native_executions':0,'connected_clients':0,'scheduled_tick_tasks':0}

def verify(root):return observations([json.loads(s,object_pairs_hook=no_duplicates)for s in (root/'observations.jsonl').read_text().splitlines()])
