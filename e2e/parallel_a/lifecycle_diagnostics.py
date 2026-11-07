"""A07 first native diagnostic gate; this cannot qualify the complete A07 stage."""
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]


def inspect(root=ROOT):
    cases=list(ET.parse(root/'core/build/test-results/test/TEST-dev.aivillages.core.kernel.LifecycleTraceTest.xml').getroot().iter('testcase'))
    assert len(cases)==12 and all(c.find(t) is None for c in cases for t in ('failure','error','skipped'))
    folders=sorted((root/'build/lifecycle-evidence').iterdir(),key=lambda p:p.name)
    assert folders,'Actual native lifecycle evidence missing'
    directory=folders[-1];native=json.loads((directory/'acceptance.json').read_text())
    assert native['phases']=={'cold':7,'warm':7} and native['modelsConfigured'] is False
    state=root/'build/lifecycle-run'/directory.name/'lifecycle-state'
    cold=json.loads((state/'lifecycle-trace-cold.json').read_text())
    warm=json.loads((state/'lifecycle-trace-warm.json').read_text())
    assert cold['enabled'] and warm['enabled'] and cold['process']!=warm['process']
    assert cold['actor']==warm['actor']
    for trace in (cold,warm):
        assert len(trace['rows'])<=trace['capacity']==128
        assert all(a['sequence']<b['sequence'] for a,b in zip(trace['rows'],trace['rows'][1:]))
    assert any(r.get('fact',{}).get('event')=='DEATH' for r in cold['rows'])
    assert not any(r.get('fact',{}).get('event')=='DEATH' for r in warm['rows'])
    masks=[r['marker']['acknowledged'] for r in cold['rows'] if r['phase']=='PUBLISHED' and r['marker']['event']=='DEATH']
    assert masks==[0,1,3,7],masks
    result={'schema':1,'stage':'A07','status':'PASS_NATIVE_EVENT_DIAGNOSTICS_ONLY','a07Qualified':False,
            'source':native['source'],'tree':native['sourceTree'],'releaseSha256':native['releaseSha256'],
            'traceContractCases':12,'nativeColdCases':7,'nativeWarmCases':7,
            'cold':cold,'warm':warm,'w04':'OPEN_NINE_CONTEXT_REPRODUCTION_REQUIRED',
            'pending':['Connected-client actor/source/facility/destination unload at preflight/effect/publication',
                       'Native surviving/lost/unknown death custody and authorized physical handoff',
                       'Nine historical W-04 timeout contexts with event-order distribution',
                       'Impact-based connected-client and broader A07 qualification']}
    out=root/'build/parallel-a/source-audit/a07-lifecycle-diagnostics.json'
    out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(result,indent=2)+'\n')
    print('A07_NATIVE_EVENT_DIAGNOSTICS_PASS contracts=12 native=7+7 W04=OPEN',flush=True)


if __name__=='__main__':
    env={**os.environ,'COGNITIVECRAFT_LIFECYCLE_TRACE':'true'}
    subprocess.run(['python3','scripts/verify-lifecycle.py'],cwd=ROOT,env=env,check=True)
    inspect()
