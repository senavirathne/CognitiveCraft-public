#!/usr/bin/env python3
"""Read original native traces/participants, including admission trials; never runtime inputs."""
from __future__ import annotations
import argparse
from collections import Counter,defaultdict
import json
from pathlib import Path
import re
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from suite import ROOT,atomic,read
from runtime import digest


def totals(s):
    out=Counter()
    for box in s.get('containers',{}).values():out.update(box)
    for a in s.get('actors',[]):out.update(a['inventory'])
    for d in s.get('drops',[]):out[d['item']]+=d['count']
    for p in s.get('knownPlayers',{}).values():out.update(p.get('assessmentInventory',{}))
    for block in s.get('blocks',{}).values():
        m=re.search(r'minecraft:[a-z_]+',block)
        if m and m[0].endswith(('_log','_wood')):out[m[0]]+=1
    return out


def audit(root):
    starts={};handles={};witnesses=defaultdict(list);effects={};corrupt=0
    for raw in (root/'server/stdout.log').read_text(errors='replace').splitlines():
        if 'CRAFTING_PRIMITIVE_TRACE ' not in raw:continue
        try:r=json.loads(raw.split('CRAFTING_PRIMITIVE_TRACE ',1)[1]);v=r['value']
        except (ValueError,KeyError):corrupt+=1;continue
        if r['kind']=='primitive-start':starts[v['correlation']['runId']]=v
        elif r['kind']=='primitive-handle':
            key=v['handle']['id'];s=starts.get(v['handle']['runId'])
            if s and s['primitive']==v['primitive']:handles[key]=s
        elif r['kind']=='primitive-terminal':
            s=handles.get(v['handle']['id'])
            if s:
                receipt=v['receipt'];op=s['primitive']['id'];cor=s['correlation']
                witnesses[op].append({'handle':v['handle'],'method':cor['artifact'],'researchId':cor.get('researchId'),
                    'committedEffects':receipt['committedEffects'],'reason':receipt.get('reason'),
                    'receiptSha256':__import__('hashlib').sha256(json.dumps(receipt,sort_keys=True).encode()).hexdigest()})
                for effect in receipt.get('resourceReceipts',[]):
                    identity=json.dumps(effect,sort_keys=True);effects[identity]=effect
    java=(ROOT/'core/src/main/java/dev/aivillages/core/kernel/GatewayPrimitives.java').read_text()
    ops=re.findall(r'\b[A-Z_]+\("([a-z_]+)",',java)
    coverage=[{'primitive':'cognitivecraft:'+op,'status':'NATIVE_TERMINAL_WITNESSED' if 'cognitivecraft:'+op in witnesses else 'UNTESTED_IN_THIS_RUN',
               'nativeWitnesses':witnesses.get('cognitivecraft:'+op,[])} for op in ops]
    admissions=[]
    paths=sorted(root.glob('admission-*.json'))
    canonical=root/'canonical.json'
    if canonical.exists() and read(canonical).get('view',{}).get('marker',{}).get('outcome')=='ADMITTED':paths.append(canonical)
    for p in paths:
        r=read(p);before=totals(r['before']);after=totals(r['after'])
        admissions.append({'path':p.name,'sha256':digest(p),'request':r.get('request',{'command':r.get('command'),'run':r.get('run'),'feedback':r.get('feedback')}),'method':r['view']['marker'].get('artifactSha256'),
            'nativeBefore':dict(before),'nativeAfter':dict(after),'delta':{k:after[k]-before[k] for k in before.keys()|after.keys() if after[k]!=before[k]},
            'receiptQuantities':dict(Counter({stage:sum(x['quantity'] for x in r['view'].get('resourceReceipts',[]) if x['stage']==stage)
                for stage in ('HARVEST','PICKUP','WITHDRAW','CRAFT','DEPOSIT')})),
            'modelBefore':r['callsBefore'],'modelAfter':r['callsAfter']})
    return {'schema':1,'source':read(root/'metadata.json'),'admissions':admissions,'coverage':coverage,
        'deduplicatedResourceReceipts':len(effects),'corruptTraceLines':corrupt,
        'I05Attribution':'UNKNOWN','limitation':'Native before/after quantities and correlation are measured. This does not causally attribute historical missing or duplicate drops, nor establish durable save cuts.'}


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,required=True);a=p.parse_args()
    v=audit(a.root);atomic(a.root/'resource-acquisition-audit.json',v)
    print(json.dumps({'admissionTrialsAudited':len(v['admissions']),
        'witnessedPrimitives':sum(x['status']=='NATIVE_TERMINAL_WITNESSED' for x in v['coverage']),
        'primitiveDenominator':len(v['coverage']),'corruptTraceLines':v['corruptTraceLines'],'I05Attribution':'UNKNOWN'}))


if __name__=='__main__':main()
