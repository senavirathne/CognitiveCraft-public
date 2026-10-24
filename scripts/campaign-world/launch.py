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

def expectation(c):
    if c['family']=='W5': return 'Authorized native enrollment/name and same-entity lifecycle continuity through '+str(c.get('lifecycle'))
    if c['family']=='W8': return 'Actual legacy '+str(c.get('legacyMode'))+' effect and truthful bounded controller termination'
    if c['family']=='W9': return 'Scoped '+str(c.get('authorityMode'))+' decisions, actual overlapping owners and conserved custody'
    if c['family']=='W10': return 'Native death, terminal owner fencing and no unsupported successor inheritance'
    if c['family']=='W11': return 'Two actual owned legacy workers admitted together; conserved native acquisition and bounded termination'
    if c['family']=='W12': return 'Verified native '+str(c.get('safetyMode'))+' hazard; correct safety refusal/interruption and no later task effects'
    return f'Physically acquire and deliver {c["quantity"]} {c["item"]}; preserve custody and authority'

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
    if raw.get('previousRun') not in (None,40076986301): raise ValueError('Only the explicitly identified original world artifact may be reused')
    if not isinstance(raw.get('includeCases',[]),list): raise ValueError('Explicit case selection must be a list')
    if os.environ.get('GITHUB_EVENT_NAME')=='push':
        if os.environ.get('GITHUB_REF')!='refs/heads/test/generalization-world': raise ValueError('Unexpected launch branch')
        event=json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
        if '[world-campaign]' not in event.get('head_commit',{}).get('message',''): raise ValueError('Explicit launch marker missing')
    return raw

def plan():
    config=configuration(); OUT.mkdir(parents=True,exist_ok=True)
    frozen_paths=[*sorted(HERE.glob('*.py')),*sorted(HERE.glob('*.json')),
                  *sorted((HERE/'client').rglob('*.java')),*sorted((ROOT/'scripts/campaign-world').glob('*.py'))]
    files={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in frozen_paths}
    write(OUT/'plan-freeze.json',dict(files=files,heldOutSeed=config.get('heldoutSeed',config['seed']+1)))
    _,cases=selection(config['seed'],config['mode'],config['families'],config['budget'],include_cases=config.get('includeCases',[]),
        heldout_seed=config.get('heldoutSeed'),extra_heldout=config.get('extraHeldoutFamilies',[]),replay_original=config.get('replayOriginal',False))
    write(OUT/'plan.json',dict(configuration=config,requested=len(cases),cases=cases,baseline=BASELINE,productionTree=TREE,
        generatorFreeze=files,ci={k:v for k,v in os.environ.items() if k in ('GITHUB_SHA','GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT','GITHUB_REF','GITHUB_EVENT_NAME')}))
    print('WORLD_PLAN '+json.dumps(dict(requested=len(cases),**config)),flush=True)
    if os.environ.get('GITHUB_OUTPUT'):
        with Path(os.environ['GITHUB_OUTPUT']).open('a') as f:
            f.write('interpreter='+str('W7' in config['families']).lower()+'\n')
            f.write('previous_run='+str(config.get('previousRun') or '')+'\n')
            f.write('review_prior='+str(config.get('reviewPrior',False)).lower()+'\n')


def run():
    value=json.loads((OUT/'plan.json').read_text()); c=value['configuration']
    args=[sys.executable,str(HERE/'runner.py'),'--root',str(OUT),'--mode',c['mode'],'--seed',str(c['seed']),
          '--families',','.join(c['families']),'--budget',str(c['budget'])]
    if c.get('includeCases'): args+=['--include-cases',','.join(c['includeCases'])]
    if c.get('heldoutSeed') is not None: args+=['--heldout-seed',str(c['heldoutSeed'])]
    if c.get('extraHeldoutFamilies'): args+=['--extra-heldout-families',','.join(c['extraHeldoutFamilies'])]
    if c.get('replayOriginal'): args+=['--replay-original']
    if c.get('previousRun'): args+=['--reuse-world',str(ROOT/'build/e2e/previous-world')]
    if c.get('isolateFamilies'): args+=['--isolate-families','--cohort-cases',str(c.get('cohortCases',6))]
    raise SystemExit(subprocess.call(args,cwd=ROOT))


def report():
    plan_path=OUT/'plan.json'
    if not plan_path.exists():
        write(OUT/'setup-failure.json',dict(status='INFRASTRUCTURE_BLOCKED',reason='Plan/setup did not complete'))
        return
    planned=json.loads(plan_path.read_text()); result_path=OUT/'results.json'
    if result_path.exists(): result=json.loads(result_path.read_text())
    else:
        failed=[stage.lower() for stage in ('BUILD','INTERPRETER','EXTRACT','INSPECT')
            if os.environ.get('CAMPAIGN_'+stage)=='failure']
        reason='Actions setup failed before gameplay at: '+(', '.join(failed) if failed else 'see actual failed job step')
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
        (directory/'reproduce.txt').write_text(reproduction(c,planned['configuration']['seed'],bool(planned['configuration'].get('previousRun')),planned['configuration'].get('heldoutSeed'),rec.get('nativeCertificate')))
    families={f:dict(requested=sum(r['case']['family']==f for r in result['records']),
        attempted=sum(r['case']['family']==f and r.get('gameplayAttempted',False) for r in result['records']),
        gameplay=sum(r['case']['family']==f and r.get('genuineGameplay',False) for r in result['records']),
        counts=dict(Counter(r['status'] for r in result['records'] if r['case']['family']==f)))
        for f in FAMILIES if any(c['family']==f for c in planned['cases'])}
    summary={k:v for k,v in result.items() if k!='records'}; summary.update(families=families,ci=planned['ci'],
        launch=planned['configuration'],records=[{k:v for k,v in r.items() if k not in ('trace','disturbance')}
             for r in result['records']])
    write(OUT/'summary.json',summary)
    inventory=json.loads((HERE/'inventory.json').read_text())
    items={'cognitivecraft:deliver_wheat':'minecraft:wheat','cognitivecraft:deliver_oak_logs':'minecraft:oak_log','cognitivecraft:deliver_birch_logs':'minecraft:birch_log'}
    legacy_modes={'legacy-harvest-replant':'harvest-replant','legacy-plant':'plant','legacy-loose-pickup':'pickup','legacy-bread-delivery':'delivery','legacy-enroll-release-auto-stop':'auto-release','legacy-death':'legacy-death'}
    for row in inventory['capabilities']:
        row['generatedCandidateVariations']=families.get(row['family'],{}).get('requested',0)
        row['generatedVariations']=0; row['genuineExecutions']=0
        row['coverageClaim']='UNVERIFIED_PRIMITIVE_PATH' if row.get('primitive') else 'REQUIRES_CASE_WITNESS'
        row['familyResults']=families.get(row['family'],{})
        row['limitation']=row.get('limitation','')+'; aggregate family results do not prove this particular operation was invoked'
        witnesses=[]
        if row['capability'] in items:
            witnesses=[r for r in result['records'] if r['case']['item']==items[row['capability']] and r['case']['family'] in ('W1','W2','W3','W4','W6','W7')]
        elif row['capability'] in legacy_modes:
            witnesses=[r for r in result['records'] if r['case']['family']=='W8' and r['case'].get('legacyMode')==legacy_modes[row['capability']]]
        elif row['capability']=='kernel-enroll-name':
            witnesses=[r for r in result['records'] if r['case']['family']=='W5']
        elif row['capability']=='citizen-lifecycle':
            witnesses=[r for r in result['records'] if r['case']['family'] in ('W5','W10')]
        elif row['capability']=='nearest-reference':
            witnesses=[r for r in result['records'] if r['case']['family']=='W7']
        elif row['capability']=='legacy-group-food':
            witnesses=[r for r in result['records'] if r['case']['family']=='W11']
        elif row['capability']=='legacy-safety-interruption':
            witnesses=[r for r in result['records'] if r['case']['family']=='W12']
        elif row['capability']=='leases-contention':
            witnesses=[r for r in result['records'] if r['case']['family']=='W9' and r['case'].get('authorityMode') in ('competition','cross-principal')]
        elif row['capability'] in ('cancellation','acquisition-delivery-recovery'):
            witnesses=[r for r in result['records'] if r['case']['family']=='W6' and
                (r['case'].get('disturbance')=='cancel' if row['capability']=='cancellation' else str(r['case'].get('disturbance')).startswith('restart-'))]
        if witnesses:
            row.update(generatedVariations=len(witnesses),genuineExecutions=sum(r.get('genuineGameplay',False) for r in witnesses),
                successful=sum(r.get('properties',{}).get('delivery',r.get('status')=='PASS') is True for r in witnesses),
                failed=sum(r['status'].startswith('FAIL_') for r in witnesses),safeRefusals=sum(r['status']=='EXPECTED_SAFE_REFUSAL' for r in witnesses),
                blocked=sum(r['status']=='INFRASTRUCTURE_BLOCKED' for r in witnesses),inconclusive=sum(r['status']=='INCONCLUSIVE' for r in witnesses),
                caseWitnesses=[r['case']['id'] for r in witnesses],coverageClaim='ACTUAL_CASE_RESULTS' if any(r.get('genuineGameplay') for r in witnesses) else 'UNTESTED')
    inventory['executionSummary']='summary.json'; inventory['coverageClaim']='NO_AUTOMATIC_PRIMITIVE_CREDIT'
    write(OUT/'coverage.json',inventory)
    failures=[]
    for r in result['records']:
        if r['status']=='PASS': continue
        c=r['case']; failures.append(dict(case=c['id'],status=r['status'],seed=c['seed'],parameters=c,
            expected=expectation(c),
            actual=r.get('accounting',r.get('reason','No physical accounting available')),
            firstDivergence=r.get('firstDivergence') or {'decision':r.get('reason','Not yet identified')},
            logs='cases/'+c['id']+'/server.log',custody=r.get('accounting'),
            reproduction=reproduction(c,planned['configuration']['seed'],bool(planned['configuration'].get('previousRun')),planned['configuration'].get('heldoutSeed'),r.get('nativeCertificate')).strip(),reproductionConsistency='SINGLE_ATTEMPT',
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
