#!/usr/bin/env python3
"""B06 controlled connected-client diagnostics; outcomes never imply recovery closure."""
from __future__ import annotations
import argparse
from collections import Counter
import json
import os
from pathlib import Path
import sys
import traceback
import urllib.request
import uuid
from zipfile import ZipFile

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from suite import ROOT,atomic,read,require
from runtime import SDK,digest
from generalization_support import AssessmentHarness,LOG,PLANK,CAPS,SRC,DST,at,coord
from parallel_b.guards import clean_owners,instruments,observer_inputs
from campaigns.crafting.oracle import candidates

MATRIX=Path(__file__).with_name('diagnostic-matrix.json')
POSITIONS=[at(x,2) for x in range(6,13)]
FAR=[10004,208,10004]

def lines(path):
    return [json.loads(x) for x in path.read_text().splitlines()] if path.exists() else []

def settled(h):
    h.wait(lambda:all(clean_owners(d) for d in h.snapshot().get('knownPlayers',{}).values()),
           'Actual terminal settled owners before any diagnostic fixture mutation',45)

def probe(h,positions):
    request={'id':str(uuid.uuid4()),'positions':positions};atomic(h.root/'server/b-probe.json',request)
    return h.wait(lambda:(v if (v:=read(h.root/'server/b-probe-response.json')) and v['id']==request['id'] else None),
                  'Independent native loaded/identity query without chunk loading',20)

def relevant(h,positions):
    s=h.snapshot()
    return {'containers':s['containers'],'inventory':h.inventory(),
            'drops':s['drops'],'sourceBlocks':{','.join(map(str,p)):s['blocks'].get(','.join(map(str,p))) for p in positions}}

def classify(row):
    """Do not promote a diagnostic outcome to an automatic-retry or completion claim."""
    if row['callsBefore']!=row['callsAfter']:return 'FAIL_MODEL_REGENERATION'
    if not row['request']['accepted']:return 'REFUSED_INTAKE_NO_ACCEPTED_RUN'
    if row.get('view',{}).get('marker',{}).get('outcome')=='SUCCEEDED':return 'SUCCEEDED'
    if (row['delivered'] or 0)>0:return 'PARTIAL_PRESERVED_NO_REPLAY'
    return 'FAILED_OR_INTERRUPTED_PRESERVED_NO_REPLAY'

def admit(h,item):
    settled(h);h.reset({} if item==LOG else {LOG:1},table=False)
    if item==LOG:h.logs(POSITIONS[:1])
    calls=h.model_calls();before=h.snapshot()
    req=h.request_run(item,1 if item==LOG else 4,minimum=POSITIONS[0],maximum=POSITIONS[0],acquire=True)
    require(req['accepted'],'Genuine diagnostic method admission refused: '+req['feedback'])
    h.actions_active=True;view=h.finish_run(req['run'],admitted=True)
    require(view.get('marker',{}).get('artifactSha256') and h.model_calls()['generationCalls']>calls['generationCalls'],
            'Diagnostic method was not genuinely generated/admitted')
    require(h.container(DST,item)==(1 if item==LOG else 4),'Admission lacks native physical output')
    h.hashes['cognitivecraft:'+CAPS[item]]=view['marker']['artifactSha256']
    row={'item':item,'request':req,'view':view,'before':before,'after':h.snapshot(),'callsBefore':calls,'callsAfter':h.model_calls()}
    atomic(h.root/('admission-'+item.split(':')[1]+'.json'),row);settled(h)

def trial(h,schedule_seed,variant,index):
    settled(h);family=variant['family'];item=LOG if family=='seven-log' else PLANK;q=7 if item==LOG else 4
    h.family='B06-'+variant['id'];h.reset({} if item==LOG else {LOG:1},table=False)
    h.console(['data merge entity '+h.entities['PlayerA']+' {Inventory:[]}'])
    if item==LOG:h.logs(POSITIONS)
    # This is a fixture schedule seed, not a claim to change the Minecraft world seed.
    destination=DST[:];kind=variant['id']
    if kind.startswith('pose-'):h.console(['tp '+h.entities['PlayerA']+' 71.5 208 68.5 90 0'])
    if kind in ('missing-target','moved-target'):
        h.console(['setblock '+coord(DST)+' minecraft:air']+(['setblock '+coord(at(16,8))+' minecraft:chest'] if kind=='moved-target' else []))
        h.wait(lambda:'minecraft:air' in h.block(DST),'Original requested target independently absent',20)
    if kind=='replaced-target':h.stock_fixture({},DST,'barrel')
    if kind=='full-target':h.stock_fixture({},DST,slots=[(i,'minecraft:cobblestone',64) for i in range(27)])
    if kind=='delayed-method':h.quiet(40)
    if kind=='loaded-target':
        h.console(['forceload add 10000 10000'])
        h.wait(lambda:probe(h,[FAR])['positions'][0]['loaded'],'Same far target actually loaded',45)
    if kind in ('unloaded-target','loaded-target'):destination=FAR[:]
    # A real client must acknowledge the source/destination fixture before the measured request.
    if item==PLANK:require(h.menu_count('PlayerA',SRC,LOG)==1,'Client source acknowledgement differs')
    if kind not in ('missing-target','moved-target','unloaded-target','loaded-target'):
        require(h.menu_count('PlayerA',DST,'minecraft:cobblestone')==(27*64 if kind=='full-target' else 0),
                'Client destination acknowledgement differs')
    target=probe(h,[destination]);require(target['worldEffects']==0,'Read-only probe performed world effects')
    if kind in ('unloaded-target','loaded-target'):require(target['positions'][0]['loaded']==(kind=='loaded-target'),'Loading counterfactual not witnessed')
    before=h.snapshot();physical_before=relevant(h,POSITIONS);calls=h.model_calls();offset=len(lines(h.root/'server/b-guards.jsonl'))
    req=h.request_run(item,q,minimum=POSITIONS[0],maximum=POSITIONS[-1],destination=destination)
    h.actions_active=req['accepted'];view=h.finish_run(req['run'],success=False) if req['accepted'] else None
    h.actions_active=False;after=h.snapshot();physical_after=relevant(h,POSITIONS)
    target_after=probe(h,[destination]);observed=target_after['positions'][0]
    actual=observed.get('itemCounts',{}).get(item,0) if observed['loaded'] else None
    row={'index':index,'scheduleSeed':schedule_seed,'variant':variant,'family':family,'item':item,'quantity':q,
         'request':req,'view':view,'before':before,'after':after,'physicalBefore':physical_before,'physicalAfter':physical_after,
         'callsBefore':calls,'callsAfter':h.model_calls(),'methodPin':h.hashes['cognitivecraft:'+CAPS[item]],
         'delivered':actual,'actorHeld':h.inventory().get(item,0),'targetObservation':target,'targetAfter':target_after,'guards':lines(h.root/'server/b-guards.jsonl')[offset:],
         'retryOf':None,'automaticReplay':False,'remainingDemandForJointAdapter':None if actual is None else q-actual}
    row['classification']=classify(row)
    require(row['callsBefore']==row['callsAfter'],'Known diagnostic method invoked generation')
    if row['classification']=='SUCCEEDED':
        require(actual==q and view['marker']['artifactSha256']==row['methodPin'],'Success lacks exact native delivery or pinned method identity')
    if kind in ('missing-target','moved-target','full-target','unloaded-target','loaded-target'):
        require(physical_before==physical_after,'Required refusal changed native participants: '+kind)
        require(not view or not view.get('resourceReceipts'),'Required refusal has resource effects: '+kind)
    atomic(h.root/'cases'/f'{index:02d}-{schedule_seed}-{kind}.json',row)
    h.screenshot('PlayerA','diagnostic-'+kind,chat=True)
    settled(h)
    if kind=='loaded-target':
        h.console(['forceload remove 10000 10000'])
        h.wait(lambda:not probe(h,[FAR])['positions'][0]['loaded'],'Same target actually unloaded after load control',110)
    return {k:v for k,v in row.items() if k not in ('before','after','physicalBefore','physicalAfter','guards','view')}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,required=True);a=p.parse_args()
    matrix=read(MATRIX);h=AssessmentHarness(a.root,'parallel-b-diagnostics')
    h.project='cc-production-b-'+read(h.root/'metadata.json')['fixtureInstance'][:8];h.family='B06-STARTUP'
    (h.root/'b-diagnostics-enabled').touch();atomic(h.root/'matrix.json',matrix)
    result={'status':'NOT_EXECUTED','issueClosure':{'W-05':'UNKNOWN','W-06':'UNKNOWN'},'rows':[]}
    try:
        instruments(ROOT/'fabric/build/libs/ai-villages-0.1.0.jar',ROOT/'build/e2e-driver/observer.jar')
        atomic(h.root/'oracle-candidates.json',candidates(SDK/'minecraft-client.jar'));observer_inputs(h.root)
        h.env['COGNITIVECRAFT_OLLAMA_MODEL']='qwen3:4b-instruct-2507-q4_K_M'
        h.compose('build','minecraft');h.compose('up','-d','ollama');h.compose('exec','-T','ollama','ollama','pull',h.env['COGNITIVECRAFT_OLLAMA_MODEL'])
        with urllib.request.urlopen('http://127.0.0.1:11434/api/tags',timeout=20) as r:atomic(h.root/'models.json',json.load(r))
        h.fresh(two=True);h.enroll();h.enroll('PlayerB','Mira')
        h.command('PlayerA','/aivillage kernel inference true','admission=true')
        for item in (LOG,PLANK):admit(h,item)
        h.command('PlayerA','/aivillage kernel inference false','admission=false');h.compose('stop','ollama')
        h.console(['forceload add 10000 10000'])
        h.wait(lambda:probe(h,[FAR])['positions'][0]['loaded'],'Far control fixture initially loaded',45)
        h.console(['setblock '+coord(FAR)+' minecraft:chest']);atomic(h.root/'far-fixture.json',probe(h,[FAR]))
        h.console(['forceload remove 10000 10000'])
        h.wait(lambda:not probe(h,[FAR])['positions'][0]['loaded'],'Actual far chunk unload before controlled pair',110)
        with ZipFile(SDK/'minecraft-client.jar') as z:atomic(h.root/'recipe-files.json',{n:__import__('hashlib').sha256(z.read(n)).hexdigest() for n in z.namelist() if n.startswith('data/minecraft/recipe/') and n.endswith('.json')})
        for seed in matrix['seeds']:
            for variant in matrix['variants']:
                result['rows'].append(trial(h,seed,variant,len(result['rows'])))
                atomic(h.root/'result.json',result)
        for family in matrix['families']:
            require(sum(r['classification']=='SUCCEEDED' and r['family']==family for r in result['rows'])>=2,
                    'Required stable native environment positives not established: '+family)
        guards=lines(h.root/'server/b-guards.jsonl');require(guards,'Required native B guards were never observed')
        result.update(status='PASS',meaning='Controlled native diagnostic matrix complete; historical intermittent issues remain unresolved',
                      distribution=dict(Counter(r['classification'] for r in result['rows'])),nativeGameplayAttempts=len(result['rows']),
                      connectedClients=2,automaticRecovery=False,guardRows=len(guards),methods=h.hashes)
    except Exception as e:
        result.update(status='FAIL',reason=str(e),trace=traceback.format_exc());atomic(h.root/'failure-snapshot.json',h.snapshot());raise
    finally:
        h.finish();atomic(h.root/'result.json',result)
        m=read(h.root/'metadata.json');m.update(profile='B06',matrixSha256=digest(MATRIX),diagnosticInstrument=read(ROOT/'build/b-diagnostics/integrity.json'));atomic(h.root/'metadata.json',m)
        atomic(h.root/'manifest.json',{'source':m,'files':[{'path':str(f.relative_to(h.root)),'bytes':f.stat().st_size,'sha256':digest(f)} for f in sorted(h.root.rglob('*')) if f.is_file() and f.name!='manifest.json']})
    print(json.dumps(result),flush=True)

if __name__=='__main__':main()
