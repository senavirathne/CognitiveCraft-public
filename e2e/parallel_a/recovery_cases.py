"""Immutable twelve-case selection and exact original-owner accounting checks."""
import hashlib,json
from pathlib import Path

MANIFEST_BLOB='e71f635050c6cc85c30cb7282cbe1ed596d67cb3'
STAGES={'HARVEST':(1,0),'PICKUP':(2,0),'DEPOSIT':(3,1)}

def originals(path):
    raw=Path(path).read_bytes()
    if hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()!=MANIFEST_BLOB:
        raise ValueError('Frozen original A06 manifest changed')
    value=json.loads(raw);cases=value['cases']
    if len(cases)!=12 or len({c['id'] for c in cases})!=12:raise ValueError('Original denominator changed')
    if {s:sum(c['checkpoint']==s for c in cases) for s in STAGES}!={s:4 for s in STAGES}:raise ValueError('Original stage coverage changed')
    if {item:sum(c['item']==item for c in cases) for item in ('minecraft:oak_log','minecraft:birch_log','minecraft:wheat')}!={'minecraft:oak_log':5,'minecraft:birch_log':6,'minecraft:wheat':1}:raise ValueError('Original material coverage changed')
    return cases

def positions(case):
    result=[[73,208,73],[75,208,74],[77,208,75],[79,208,76]][:case['requested']]
    a=case['source']
    if len(result)!=case['requested'] or any(not all(a['min'+axis]<=p[n]<=a['max'+axis] for n,axis in enumerate(('X','Y','Z'))) for p in result):
        raise ValueError('Native fixture does not preserve the original geometry and quantity')
    return result

def settled(case,original,job,key):
    effects,credit=STAGES[case['checkpoint']];a=job['attempts'][-1];old=original['attempts'][-1]
    assert job['id']==original['id']==key['root']==key['step'] and a['id']==old['id']==key['attempt']
    assert job['generation']==original['generation']==a['generation']==key['generation']
    assert job['state']=='CANCELLED' and a['terminal']['status']=='CANCELLED' and not a['uncertain']
    assert a['effects']==effects and a['credited']==credit and sum(row['credited'] for row in job['attempts'])==credit
    assert job['allowance']==original['allowance'] and a['allowance']==old['allowance']
    assert a['worker']==old['worker'] and a['executions']==old['executions'] and job['children']==original['children']==[]
    assert job['request']==original['request'] and job['request']['request']['arguments']['amount']['value']==case['requested']
    assert job['request']['request']['capability']==case['capability']
    assert all(count<=a['allowance']['maxima'][kind] for kind,count in a['usage'].items())
    assert all(a['usage'].get(kind,0)>=count for kind,count in old['usage'].items())
    return {'id':case['id'],'stage':case['checkpoint'],'item':case['item'],'requested':case['requested'],'key':key,
            'originalJob':job['id'],'originalAttempt':a['id'],'effects':effects,'credited':credit,'status':'PASS_ORIGINAL_OWNER'}

def complete(cases,reviews):
    required={c['id']:c for c in cases};seen=set();keys=set();rows=[]
    for review in reviews:
        case=review['originalCase'];identifier=case['id'];assert identifier in required and case==required[identifier] and identifier not in seen
        assert review['status']=='PASS_INDEPENDENT_ORIGINAL_REVIEW' and review['nativeCustodyNoReplay'] and review['originalOwnerSettled']
        seen.add(identifier);key=review['key'];identity=tuple(key[k] for k in ('world','root','step','attempt','generation','ordinal'))
        assert identity not in keys;keys.add(identity);rows.append(review['owner'])
    assert seen==set(required) and len(rows)==12
    return {'schema':1,'status':'PASS_ALL_TWELVE_ORIGINAL_SAVED_CASES','originalOwnerSettlements':12,'requiredOriginalCases':12,
            'cases':rows,'stageCounts':{s:sum(row['stage']==s for row in rows) for s in STAGES}}
