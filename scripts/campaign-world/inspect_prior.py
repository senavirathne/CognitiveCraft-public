#!/usr/bin/env python3
"""Bounded inspection of an exact prior campaign artifact; original files remain immutable."""
import json
from pathlib import Path
import re
import sys
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'e2e'))
sys.path.insert(0,str(ROOT/'e2e/campaigns/world'))
from seed import find_runtime
from suite import read,completed_jsonl

source=find_runtime(ROOT/'build/e2e/previous-world')
results=read(source/'results.json'); meta=read(source/'metadata.json')
print('WORLD_PRIOR_SUMMARY '+json.dumps({k:v for k,v in results.items() if k!='records'},separators=(',',':')))
print('WORLD_PRIOR_ADMISSIONS '+json.dumps([{k:v for k,v in a.items() if k!='after'} for a in read(source/'admissions.json')],separators=(',',':')))
print('WORLD_PRIOR_PROVENANCE '+json.dumps(meta,separators=(',',':')))
seen=set(); chosen=[r for r in results['records'] if r['status'].startswith('FAIL_') or
    r['case']['id'] in ('W5-002','W5-003','W5-006','W5-007','W5-008','W6-000')]
for record in results['records']:
    c=record['case']; signature=(c['family'],record['status'],(record.get('firstDivergence') or {}).get('property'))
    if signature not in seen:
        seen.add(signature)
        if record not in chosen: chosen.append(record)
for record in results['records']:
    print('WORLD_PRIOR_CASE '+json.dumps(record,separators=(',',':')))
for record in chosen[:24]:
    c=record['case']; directory=source/'cases'/c['id']
    def state(filename):
        value=read(directory/filename)
        if not value:return None
        points=c['positions']+[c['destination']]+c.get('immature',[])+c.get('unrelated',[])
        return {**{k:value.get(k) for k in ('time','gameTick','actors','containers','drops','generationCalls','needleCalls','brokerStats')},
            'blocks':{','.join(map(str,p)):value.get('blocks',{}).get(','.join(map(str,p))) for p in points}}
    log=directory/'server.log'
    context=[line for line in log.read_text(errors='replace').splitlines() if re.search('CognitiveCraft|IMP-007|Exception|ERROR|stale',line)] if log.exists() else []
    def client(filename):
        value=read(directory/filename) or {}
        return {k:value.get(k) for k in ('play','screen','pos','uuid','gameLoaded','singleplayer')}
    value=dict(case=c['id'],before=state('before.json'),after=state('after.json'),failure=state('failure-state.json'),
        clientBefore=client('client-before.json'),clientAfter=client('client-after.json'),
        request=read(directory/'request.json'),run=read(directory/'run.json'),
        logFirst=context[:14],logLast=context[-14:],citizen=read(directory/'citizen.json'))
    print('WORLD_PRIOR_EVIDENCE '+json.dumps(value,separators=(',',':')))
for filename in ('server/stdout.log','clients/PlayerA/stdout.log','clients/PlayerA/game/logs/latest.log'):
    path=source/filename
    if path.exists():
        lines=path.read_text(errors='replace').splitlines()
        selected=[line for line in lines if re.search('fell|died|lost connection|Exception|ERROR|OutOfMemory|SIGSEGV|fatal error|logged out',line,re.I)]
        print('WORLD_PRIOR_RUNTIME_LOG '+json.dumps({'path':filename,'lines':selected[-50:]},separators=(',',':')))
