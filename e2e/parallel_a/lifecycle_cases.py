"""A07 native obligations. Historical inputs and narrow claims stay separate."""
import hashlib,json
from pathlib import Path

ROLES=('ACTOR','SOURCE','FACILITY','DESTINATION')
PHASES=('PREFLIGHT','EFFECT','PUBLICATION')
LIVE_CASES=tuple(f'{role}-{phase}' for role in ROLES for phase in PHASES)
DEATH_CASES=('death-surviving','death-burned','death-unavailable')
HANDOFF_CASES=('handoff-positive','handoff-revoked','handoff-stale-successor')


def contexts(path):
    value=json.loads(Path(path).read_text());rows=value['cases']
    assert len(rows)==9 and len({r['case']['id'] for r in rows})==9
    assert value['historicalRun']==40085523505 and value['sourceBlob']=='2ce892ba6fa33cfbda605805382e6277b40eb134'
    assert all(r['case']['lifecycle']=='unload' and r['reason']=='Actual same-citizen native chunk unload; last=None' for r in rows)
    return rows


def handoff(before,after,successor):
    assert before['id']==after['id'] and after['generation']==before['generation']+1
    assert len(before['attempts'])==1 and len(after['attempts'])==2
    first,last=after['attempts'];old=before['attempts'][0]
    assert first['id']==old['id'] and first['worker']==old['worker'] and first['credited']==last['credited']==1
    assert first['effects']==last['effects']==3 and not first['uncertain'] and not last['uncertain']
    assert last['worker']==successor and last['worker']!=first['worker']
    assert last['bound']['request']['arguments']['amount']['value']==1
    assert after['allowance']==before['allowance'] and after['state']=='SUCCEEDED'
    assert sum(a['credited'] for a in after['attempts'])==2
    assert all(n<=after['allowance']['maxima'][kind] for kind,n in after['usage'].items())
    return {'job':after['id'],'originalAttempt':first['id'],'successorAttempt':last['id'],
            'generation':after['generation'],'credited':2,'status':'PASS_SAME_JOB_EXPLICIT_PHYSICAL_REASSIGNMENT'}


def disposition(entity,damage):
    """Observed engine loss differs from an unavailable native lookup; counts alone never prove destruction."""
    if entity.get('loaded') and entity.get('alive') and not entity.get('removed'):
        return 'OBSERVED_SURVIVING_DROP'
    burned=[d for d in damage if d.get('accepted') and d.get('removed')
            and any(t in d.get('source','').lower() for t in ('fire','lava','burn'))]
    if burned:return 'OBSERVED_NATIVE_DESTRUCTION'
    if not entity.get('loaded') and entity.get('removal') in ('UNLOADED_TO_CHUNK','UNLOADED_WITH_PLAYER','None','null',None):
        return 'UNAVAILABLE_CUSTODY_UNKNOWN'
    return 'UNPROVEN_CUSTODY'
