#!/usr/bin/env python3
"""Validated launch request and always-run evidence reconciliation for the branch-only campaign."""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import traceback

ROOT=Path(__file__).resolve().parents[2]
HERE=ROOT/'e2e/campaigns/world'
sys.path.insert(0,str(HERE))
from runner import selection,reproduction
from generate import BASELINE,TREE,FAMILIES

OUT=ROOT/'build/e2e/generalization-world'

def write(path,body):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(body,indent=2)+'\n')

def configuration():
    raw=json.loads((HERE/'launch.json').read_text())
    if os.environ.get('GITHUB_EVENT_NAME')=='workflow_dispatch':
        for key in ('mode','seed','families','budget'):
            val=os.environ.get('CAMPAIGN_'+key.upper())
            if val: raw[key]=val
    raw['seed']=int(raw['seed']); raw['budget']=int(raw['budget'])
    if isinstance(raw['families'],str): raw['families']=raw['families'].split(',')
    if raw['mode'] not in ('full','smoke') or not 0<=raw['budget']<=300 or not set(raw['families'])<=set(FAMILIES):
        raise ValueError('Invalid bounded campaign configuration')
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,80}',raw['launchId']): raise ValueError('Invalid launch identity')
    if os.environ.get('GITHUB_EVENT_NAME')=='push':
        if os.environ.get('GITHUB_REF')!='refs/heads/test/generalization-world': raise ValueError('Unexpected launch branch')
        event=json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
        if '[world-campaign]' not in event.get('head_commit',{}).get('message',''): raise ValueError('Explicit launch marker missing')
    return raw

def plan():
    config=configuration(); OUT.mkdir(parents=True,exist_ok=True)
    _,cases=selection(config['seed'],config['mode'],config['families'],config['budget'])
    files={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()
           for p in sorted(HERE.glob('*.py'))}
    write(OUT/'plan.json',dict(configuration=config,requested=len(cases),cases=cases,baseline=BASELINE,productionTree=TREE,
        generatorFreeze=files,ci={k:v for k,v in os.environ.items() if k in ('GITHUB_SHA','GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT','GITHUB_REF','GITHUB_EVENT_NAME')}))
    print('WORLD_PLAN '+json.dumps(dict(requested=len(cases),**config)),flush=True)


def run():
    value=json.loads((OUT/'plan.json').read_text()); c=value['configuration']
    args=[sys.executable,str(HERE/'runner.py'),'--root',str(OUT),'--mode',c['mode'],'--seed',str(c['seed']),
          '--families',','.join(c['families']),'--budget',str(c['budget'])]
    raise SystemExit(subprocess.call(args,cwd=ROOT))


def report():
    plan_path=OUT/'plan.json'
    if not plan_path.exists():
        write(OUT/'setup-failure.json',dict(status='INFRASTRUCTURE_BLOCKED',reason='Plan/setup did not complete'))
        return
    planned=json.loads(plan_path.read_text()); result_path=OUT/'results.json'
    if result_path.exists(): result=json.loads(result_path.read_text())
    else:
        reason='Actions build, asset preparation or driver compilation failed before gameplay; see job logs'
        records=[dict(case=c,status='INFRASTRUCTURE_BLOCKED',gameplayAttempted=False,genuineGameplay=False,reason=reason)
                 for c in planned['cases']]
        result=dict(schema=2,baseline=BASELINE,productionTree=TREE,requested=len(records),records=records,complete=False,
                    infrastructureFailure={'reason':reason},counts={'INFRASTRUCTURE_BLOCKED':len(records)},
                    gameplayAttempts=0,genuineGameplayExecutions=0,rates={})
        write(result_path,result)
    for rec in result['records']:
        c=rec['case']; directory=OUT/'cases'/c['id']; directory.mkdir(parents=True,exist_ok=True)
        write(directory/'parameters.json',c); write(directory/'result.json',rec)
        if not (directory/'provenance.json').exists():
            write(directory/'provenance.json',dict(baseline=BASELINE,productionTree=TREE,ci=planned['ci'],releaseSha256=None))
        (directory/'reproduce.txt').write_text(reproduction(c,planned['configuration']['seed']))
    families={f:dict(requested=sum(r['case']['family']==f for r in result['records']),
        attempted=sum(r['case']['family']==f and r.get('gameplayAttempted',False) for r in result['records']),
        gameplay=sum(r['case']['family']==f and r.get('genuineGameplay',False) for r in result['records']),
        counts=dict(Counter(r['status'] for r in result['records'] if r['case']['family']==f))) for f in planned['configuration']['families']}
    summary={k:v for k,v in result.items() if k!='records'}; summary.update(families=families,ci=planned['ci'],
        launch=planned['configuration'],records=[{k:v for k,v in r.items() if k not in ('trace','disturbance')}
             for r in result['records']])
    write(OUT/'summary.json',summary)
    inventory=json.loads((HERE/'inventory.json').read_text())
    for row in inventory['capabilities']:
        row['generatedCandidateVariations']=families.get(row['family'],{}).get('requested',0)
        row['generatedVariations']=0; row['genuineExecutions']=0
        row['coverageClaim']='UNVERIFIED_PRIMITIVE_PATH' if row.get('primitive') else 'REQUIRES_CASE_WITNESS'
        row['familyResults']=families.get(row['family'],{})
        row['limitation']=row.get('limitation','')+'; aggregate family results do not prove this particular operation was invoked'
    inventory['executionSummary']='summary.json'; inventory['coverageClaim']='NO_AUTOMATIC_PRIMITIVE_CREDIT'
    write(OUT/'coverage.json',inventory)
    failures=[]
    for r in result['records']:
        if r['status']=='PASS': continue
        c=r['case']; failures.append(dict(case=c['id'],status=r['status'],seed=c['seed'],parameters=c,
            expected=f'Physically acquire and deliver {c["quantity"]} {c["item"]}; preserve custody and authority',
            actual=r.get('accounting',r.get('reason','No physical accounting available')),
            firstDivergence=r.get('firstDivergence') or {'decision':r.get('reason','Not yet identified')},
            logs='cases/'+c['id']+'/server.log',custody=r.get('accounting'),
            reproduction=reproduction(c,planned['configuration']['seed']).strip(),reproductionConsistency='SINGLE_ATTEMPT',
            smallestCounterexample='NOT_MINIMIZED; original parameters preserved',
            boundary='UNCONFIRMED; inspect native route/custody, admission and durable owner evidence',
            cause='EVIDENCE_BOUNDED: no production cause inferred from a final exception',
            failureFamily=(r.get('firstDivergence') or {}).get('property',r['status']),
            recommendedCorrection='Diagnose first native divergence and reproduce before selecting a production correction',
            regressions='Retain this seed and independent physical assertions; production remains frozen'))
    write(OUT/'failures.json',failures)
    lines=['# Physical world campaign execution','',f'Frozen source: `{BASELINE}`; production tree: `{TREE}`.',
           '',f'Requested **{result["requested"]}**; gameplay attempts **{result.get("gameplayAttempts",0)}**; genuine gameplay executions **{result.get("genuineGameplayExecutions",0)}**.',
           '', '**Mission remains incomplete** until the capability inventory limitations and unverified primitive paths are resolved.',
           '', '| Family | Requested | Attempted | Genuine gameplay | Outcomes |','|---|---:|---:|---:|---|']
    for f,v in families.items(): lines.append(f'| {f} | {v["requested"]} | {v["attempted"]} | {v["gameplay"]} | {json.dumps(v["counts"])} |')
    lines+=['','Rates have separate evaluated denominators. A zero denominator is untested. See `summary.json`, `coverage.json`, `failures.json`, and each case directory.']
    (OUT/'report.md').write_text('\n'.join(lines)+'\n')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a') as stream: stream.write('\n'.join(lines)+'\n')
    print('WORLD_SUMMARY '+json.dumps({k:v for k,v in summary.items() if k!='records'},separators=(',',':')),flush=True)
    for r in summary['records']: print('WORLD_CASE '+json.dumps(r,separators=(',',':')),flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('action',choices=('plan','run','report'))
    {'plan':plan,'run':run,'report':report}[parser.parse_args().action]()
