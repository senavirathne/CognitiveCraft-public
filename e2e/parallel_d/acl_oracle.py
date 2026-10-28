"""Independent 100-step ACL model. Reads actual Java decisions and metadata, never a probe pass flag."""
import hashlib,json,uuid
from common import *

SCHEDULE_SHA='05b44f59c7e4cd1b55b32562a81d6c3747dfa89021c2fda5a5096293a89ab3e5'
WORLD='d0300000-0000-4000-8000-000000000001';DOMAIN='d0300000-0000-4000-8000-000000000002';READER='d0300000-0000-4000-8000-000000000004'
ORG='d0300000-0000-4000-8000-000000000006'

def canonical(v):return (json.dumps(v,ensure_ascii=False,sort_keys=True,separators=(',',':'))+'\n').encode()
def campaign():
    p=HERE/'campaigns/D03-acl-100.json';require(sha(p)==SCHEDULE_SHA,'ACL frozen schedule changed');v=read(p)
    require(v['seed']==20261027 and len(v['transitions'])==100,'ACL campaign denominator')
    for i,r in enumerate(v['transitions']):require(r['id']==f'D03-ACL-{i:03d}' and r['ordinal']==i and r['seed']==20261027+i,'ACL ordinal/seed changed')
    return v['transitions']
def expected_decision(grants,member):return 'UNKNOWN'if member=='UNKNOWN'else 'ALLOW'if grants and member=='MEMBER'else 'DENY'
def observations(rows):
    schedule=campaign();require(len(rows)==100 and [r['id']for r in rows]==[s['id']for s in schedule],'missing/duplicate ACL transition IDs')
    storage=rows[0]['before']['storage_id'];require(str(uuid.UUID(storage))==storage,'sequence storage ID')
    grants=[];member='MEMBER';owner=ORG;registry=3;record=2;acl=0;generation=1;membership=0;previous=None;revoked_allows=0;times=[]
    def decision(value,i,expected):
        require(value['scope']=={'world_id':WORLD,'domain_id':DOMAIN,'principal_id':READER},'swapped ACL principal/domain/world')
        require(value['storage_id']==storage and value['operation']=='DEPOSIT','swapped ACL storage/operation')
        require(value['decision']==expected,'Java ACL decision disagrees with independent sequence model')
        require(value['registry_revision']==registry and value['acl_revision']==acl and value['binding_generation']==generation and value['membership_revision']==membership,'ACL revision/generation did not fence old decision')
        require(value['evaluated_tick']==1000+i and value['valid_through_tick']==1002+i,'ACL two-tick expiry')
        require(value['reason']==(None if expected=='ALLOW'else 'UNKNOWN_WORLD_STATE'if expected=='UNKNOWN'else 'UNAUTHORIZED'),'ACL refusal reason')
    for i,(row,step)in enumerate(zip(rows,schedule)):
        require(row['tier']=='PURE_ACL_SEQUENCE'and row['action']==step['action'],'false physical credit or swapped ACL action')
        require(row['integration_root']is None and row['participant_save']is None,'fabricated ACL settlement')
        before=expected_decision(grants,member);decision(row['before'],i,before)
        if step['action']=='GRANT':grants=step['permissions'];registry+=1;record+=1;acl+=1
        elif step['action']=='REVOKE':
            require(grants,'revoke without prior grant');grants=[];registry+=1;record+=1;acl+=1;revoked_allows+=before=='ALLOW'
        elif step['action']=='TRANSFER':owner=step['target'];grants=[];registry+=3;record+=3;acl+=1;generation+=1
        else:member=step['state'];membership+=1
        after=expected_decision(grants,member);decision(row['after'],i,after)
        require(row['owner']==owner and row['record_revision']==record,'ownership transition history changed')
        expected_acl=[]if not grants else [{'subject':READER,'permissions':grants}];require(row['acl']==expected_acl,'inherited/escalated grant after ACL edit/transfer')
        require(row['old_current']is False and row['old_effect_allowed']is False,'old ACL decision survived transition')
        require(row['before_current']is True and row['after_current']is True and row['after_effect_allowed']==(after=='ALLOW'),'fresh positive/negative ACL control missing')
        require(type(row['evaluation_ns'])is int and row['evaluation_ns']>=0,'invalid ACL timing');times.append(row['evaluation_ns'])
    require(revoked_allows>=5,'active allowed decision revocation control absent')
    times.sort();return {'transitions':100,'native_executions':0,'connected_clients':0,'revoked_previously_allowed_decisions':revoked_allows,'final':{'storage':storage,'owner':owner,'registry_revision':registry,'record_revision':record,'acl_revision':acl,'generation':generation,'membership_revision':membership},'evaluation_ns':{'p50':times[49],'p95':times[94],'p99':times[98],'max':times[99]},'scheduled_tick_tasks':0}

def verify(root):
    rows=[json.loads(s,object_pairs_hook=no_duplicates)for s in (root/'observations.jsonl').read_text().splitlines()];result=observations(rows);state=read(root/'metadata/metadata.current.json');p=state['payload']
    require(hashlib.sha256(canonical(p)).hexdigest()==state['checksum_sha256'],'ACL persisted metadata checksum')
    final=result['final'];require(p['world_id']==WORLD and p['revision']==final['registry_revision']and len(p['records'])==1,'ACL persisted world/history')
    record=p['records'][0];require(record['storage_id']==final['storage']and record['owner']=={'kind':'ORGANIZATION','id':final['owner']},'ACL persisted owner')
    require(record['stamp']=={'schema_version':1,'revision':final['record_revision'],'generation':final['generation']}and record['acl_revision']==final['acl_revision']and record['acl']==[],'ACL persisted transfer/revoke history')
    require(record['state']=='ACTIVE'and len(p['bindings'])==1 and p['bindings'][0]['binding_generation']==final['generation'],'ACL binding fence was not acknowledged')
    return result
