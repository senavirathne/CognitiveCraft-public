#!/usr/bin/env python3
"""Sequential connected-client campaign; never interpret generation as gameplay evidence."""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import traceback

E2E=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(E2E))
from suite import Harness, InfrastructureBlocked, ROOT, UUID_PATTERN, atomic, read, require, reconnect_players
from generalization_support import AssessmentHarness, AssessmentBlocked, Inconclusive, LOG, BIRCH
from generate import generate, BASELINE, TREE, FAMILIES

WHEAT='minecraft:wheat'

class WorldHarness(AssessmentHarness):
    """Reuse production command/client transport, with one client and campaign-owned fixtures."""
    def __init__(self,root):
        self.case_dir=None
        Harness.__init__(self,root,'compose',None)
        self.hashes={}; self.admissions=[]; self.last_sample=None; self.sample_count=0
        self.profile='generalization-world'
        atomic(self.root/'observation-area.json',dict(minX=-1,footY=201,minZ=-1,maxX=20,maxZ=14,maxObservationDy=4))
        corpus=read(E2E/'generalization/cases.json')
        atomic(self.root/'assessment-language.json',{r['id']:r['requirement'] for r in corpus['scenarios'] if r['category']=='language'})
        self.env['COGNITIVECRAFT_E2E_SNAPSHOT_TICKS']='1'

    def event(self,kind,data):
        Harness.event(self,kind,data)
        if self.case_dir:
            with (self.case_dir/'events.jsonl').open('a') as f:
                f.write(json.dumps(dict(time=time.time(),kind=kind,data=data))+'\n')

    def setup(self,two=False,legacy=False):
        require(not two and not legacy,'World campaign starts exactly one normal client')
        Harness.setup(self,False,False)

    def reset_case(self,c):
        require(not self.actions_active,'No fixtures during active gameplay')
        # Wait for native terminal state before the caller invokes this setup.
        self.console(['fill -1 201 -1 21 205 15 minecraft:air',
                      'fill -1 200 -1 21 200 15 minecraft:stone',
                      'kill @e[type=minecraft:item]',
                      'data merge entity '+self.entities['PlayerA']+' {Inventory:[]}',
                      'tp '+self.entities['PlayerA']+' '+' '.join(map(str,c['actor'])),
                      'tp PlayerA 2.5 201 5.5','clear PlayerA'])
        dst=c['destination']; item=c['item']
        slots=[(i,'minecraft:cobblestone',64) for i in range(c['occupiedSlots'])]
        slots.append((26,item,c['existingStock'])) if c['existingStock'] else None
        self.stock_fixture({},dst,c['container'],slots=slots)
        lines=[]
        for p in c['positions']:
            if item==WHEAT:
                lines.append('setblock '+' '.join(map(str,[p[0],200,p[2]]))+' minecraft:farmland[moisture=7]')
                block='minecraft:wheat[age=7]'
            else:
                block=item+'[axis='+c['logAxis']+']'
            lines.append('setblock '+' '.join(map(str,p))+' '+block)
        # Obstacle has an independently open route at either end.
        if c['terrain']=='detour':
            lines.append('fill 5 201 3 5 202 10 minecraft:stone')
        self.console(lines)
        self.wait(lambda: all(item in self.block(p) for p in c['positions']), 'Observed generated source',20)
        self.quiet(5)
        require(self.container(dst,item)==c['existingStock'],'Initial destination stock mismatch')

    def request_case(self,c,acquire=False):
        points=c['positions']
        lo=[min(p[k] for p in points) for k in range(3)]
        hi=[max(p[k] for p in points) for k in range(3)]
        if c['item']==WHEAT:
            command='/aivillage kernel harvest '+self.citizens['PlayerA']+' '+str(c['quantity'])+' '+' '.join(map(str,lo+hi+c['destination']))
            answer=self.command('PlayerA',command,'run='+UUID_PATTERN)
            return dict(command=command,feedback=answer,accepted='accepted=true' in answer,
                        run=re.search('run=('+UUID_PATTERN+')',answer).group(1))
        return self.request_run(c['item'],c['quantity'],minimum=lo,maximum=hi,destination=c['destination'],acquire=acquire)

    def bootstrap_world(self):
        self.env['COGNITIVECRAFT_OLLAMA_MODEL']='qwen3:4b-instruct-2507-q4_K_M'
        self.compose('build','minecraft')
        self.compose('--profile','ai','up','-d','ollama')
        self.compose('exec','-T','ollama','ollama','pull',self.env['COGNITIVECRAFT_OLLAMA_MODEL'])
        models=self.compose('exec','-T','ollama','ollama','list').stdout
        (self.root/'models.txt').write_text(models)
        self.fresh(two=False); self.enroll()
        self.command('PlayerA','/aivillage kernel inference true','admission=true')
        for item in (WHEAT,LOG,BIRCH):
            c=next(c for c in generate() if c['item']==item)
            c={**c,'quantity':1,'positions':[c['positions'][0]],'occupiedSlots':0,'existingStock':0,'terrain':'flat'}
            self.reset_case(c)
            before=self.model_calls(); req=self.request_case(c,True)
            require(req['accepted'],'Admission request refused: '+req['feedback'])
            self.actions_active=True
            view=self.finish_run(req['run'],admitted=True)
            require(self.model_calls()['generationCalls']>before['generationCalls'],'No genuine model admission')
            require(self.container(c['destination'],item)==1,'Admission lacks physical output')
            cap={WHEAT:'deliver_wheat',LOG:'deliver_oak_logs',BIRCH:'deliver_birch_logs'}[item]
            self.hashes['cognitivecraft:'+cap]=view['marker']['artifactSha256']
            self.admissions.append(dict(item=item,request=req,view=view))
            atomic(self.root/'admissions.json',self.admissions)
        self.command('PlayerA','/aivillage kernel inference false','admission=false')
        self.compose('stop','ollama')
        self.actions_active=False


def total(state,item):
    return (sum(box.get(item,0) for box in state['containers'].values())+
            sum(a['inventory'].get(item,0) for a in state['actors'])+
            sum(d['count'] for d in state['drops'] if d['item']==item))


def execute(h,c):
    h.reset_case(c)
    h.executed_case=False
    before=h.snapshot(); atomic(h.case_dir/'before.json',before)
    client_before=h.client_snapshot('PlayerA')
    require(client_before and client_before['play'] and not client_before['singleplayer'],'Real multiplayer client required')
    if c['family']=='W5':
        citizen=h.citizens['PlayerA']; entity=h.entities['PlayerA']
        h.executed_case=True
        h.command('PlayerA','/aivillage kernel name '+citizen+' '+c['citizenName'],'name='+c['citizenName'])
        listing=h.command('PlayerA','/aivillage kernel citizens','citizens=')
        require(citizen in listing and c['citizenName'] in listing,'Durable name/identity mismatch')
        return dict(status='PASS',genuineGameplay=True,properties={'identity':True},
                    citizen=citizen,entity=entity,listing=listing,
                    limitations=['Renaming existing citizen only; fresh enrollment/unload/reload not exercised by this case'])
    calls=h.model_calls(); request=h.request_case(c)
    atomic(h.case_dir/'request.json',request)
    if not request['accepted']:
        return dict(status='EXPECTED_SAFE_REFUSAL',genuineGameplay=False,request=request,properties={},refusalObserved=True)
    h.executed_case=True
    h.actions_active=True; run=request['run']; disturbance=None
    if c['family']=='W6':
        boundary=h.wait(lambda: (v if (v:=h.run_view('PlayerA',run)) and v.get('phase') in ('EXECUTING','TERMINAL') else None),'Witness active boundary',60)
        atomic(h.case_dir/'disturbance-boundary.json',boundary)
        if boundary.get('phase')=='TERMINAL':
            raise Inconclusive('Task ended before disturbance')
        if c['disturbance']=='cancel':
            disturbance=h.command('PlayerA','/aivillage kernel cancel '+run,'cancel|CANCEL|TERMINAL')
        elif c['disturbance']=='restart':
            checkpoint=h.save_checkpoint(); saved=h.snapshot()
            h.stop('server',hard=True); killed=h.world_hashes(); h.start('server'); reconnect_players(h,['PlayerA'])
            disturbance=dict(checkpoint=checkpoint,killedWorldHashes=killed,before=saved,after=h.snapshot())
        else:
            # Ordinary connected player action: no fixture writes during gameplay.
            target=c['positions'][0]
            h.walk('PlayerA',target)
            h.action('PlayerA','break',pos=target)
            h.wait(lambda: 'minecraft:air' in h.block(target),'Actual player block removal',25)
            disturbance=dict(target=target,observed=h.snapshot())
    view=h.finish_run(run,success=False)
    h.quiet(10); after=h.snapshot()
    atomic(h.case_dir/'after.json',after)
    atomic(h.case_dir/'run.json',view)
    client=h.client_snapshot('PlayerA')
    h.screenshot('PlayerA','world-campaign-result',chat=True)
    require(client and client['play'] and not client['singleplayer'],'Final multiplayer observation required')
    success=view.get('marker',{}).get('outcome')=='SUCCEEDED'
    delta=h.container(c['destination'],c['item'])-c['existingStock']
    remaining=sum(c['item'] in h.block(p) and (c['item']!=WHEAT or 'age=7' in h.block(p)) for p in c['positions'])
    harvested=c['quantity']-remaining
    actor_before=next(a['pos'] for a in before['actors'] if a['uuid']==h.entities['PlayerA'])
    actor_after=next(a['pos'] for a in after['actors'] if a['uuid']==h.entities['PlayerA'])
    conservation=total(after,c['item'])==total(before,c['item'])+harvested
    # Player interference may legitimately move harvest into player custody.
    if disturbance and c['disturbance']=='break':
        player_delta=sum(s['count'] for s in client['slots'] if s['playerInventory'] and s['item']==c['item'])-sum(s['count'] for s in client_before['slots'] if s['playerInventory'] and s['item']==c['item'])
        conservation=total(after,c['item'])+player_delta==total(before,c['item'])+harvested
    props=dict(conservation=conservation,truthfulCompletion=(not success or delta==c['quantity']),
               navigation=math.dist(actor_before,actor_after)>.5,noInference=h.model_calls()==calls,
               delivery=delta==c['quantity'],harvest=harvested==c['quantity'])
    receipts=view.get('receipts',[]) if c['item']==WHEAT else view.get('resourceReceipts',[])
    props['receiptIdentity']=all(r.get('actor',{}).get('citizenId')==h.citizens['PlayerA'] for r in receipts)
    props['uniqueReceipts']=len({r['receiptId'] for r in receipts})==len(receipts)
    props['pickupLineage']=sum(r.get('wheat',0) if c['item']==WHEAT else r.get('quantity',0) for r in receipts if r.get('stage')=='PICKUP')>=delta
    if not conservation or not props['truthfulCompletion'] or not props['receiptIdentity'] or not props['uniqueReceipts']:
        status='FAIL_SAFETY'
    elif c['family']=='W6':
        status='PASS' if disturbance and c['disturbance']!='restart' else 'INCONCLUSIVE'
    else:
        status='PASS' if success and all(props.values()) else 'FAIL_GENERALIZATION'
    return dict(status=status,genuineGameplay=True,request=request,properties=props,view=view,
                disturbance=disturbance,client=client,before=before,after=after,
                firstDivergence='UNRESOLVED: inspect ordered events and raw run before assigning cause' if status.startswith('FAIL') else None)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root',type=Path,required=True); p.add_argument('--seed',type=int,default=104729)
    p.add_argument('--mode',choices=('smoke','full'),default='smoke')
    p.add_argument('--families',default=','.join(FAMILIES)); p.add_argument('--budget',type=int,default=0)
    p.add_argument('--case'); p.add_argument('--generate-only',action='store_true')
    a=p.parse_args(); a.root.mkdir(parents=True,exist_ok=True)
    require(not (a.root/'results.json').exists(),'Use a new evidence root; original attempts are immutable')
    selected=a.families.split(','); require(set(selected)<=set(FAMILIES),'Unknown family')
    freeze={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in (Path(__file__),Path(__file__).with_name('generate.py'))}
    atomic(a.root/'generator-freeze.json',dict(files=freeze,heldOutSeed=a.seed+1))
    corpus=generate(a.seed)
    if a.mode=='full' and not a.case:
        heldout=generate(a.seed+1)
        for c in heldout: c['id']='heldout-'+c['id']
        corpus+=heldout
    cases=[c for c in corpus if c['family'] in selected and (not a.case or c['id']==a.case)]
    if a.mode=='smoke' and not a.case: cases=[next(c for c in cases if c['family']==f) for f in selected]
    if a.budget>0: cases=cases[:a.budget]
    require(bool(cases),'Selection contains no cases')
    atomic(a.root/'corpus.json',dict(baseline=BASELINE,productionTree=TREE,cases=corpus,selected=[c['id'] for c in cases]))
    records=[dict(case=c,status='INFRASTRUCTURE_BLOCKED',genuineGameplay=False,reason='Not reached') for c in cases]
    result=dict(schema=1,baseline=BASELINE,productionTree=TREE,requested=len(cases),records=records,complete=False)
    atomic(a.root/'results.json',result)
    if a.generate_only: return
    h=None
    try:
        require(shutil.which('docker') is not None,'Docker executable unavailable')
        subprocess.run(['docker','info'],check=True,capture_output=True)
        # Pin every production input, including untracked additions under source roots.
        changed=subprocess.check_output(['git','diff',BASELINE,'--','core','fabric','providers','build.gradle','settings.gradle','gradle.properties','gradle'],cwd=ROOT,text=True)
        require(not changed,'Production differs from frozen baseline')
        untracked=subprocess.check_output(['git','ls-files','--others','--exclude-standard','core','fabric','providers','gradle'],cwd=ROOT,text=True)
        require(not untracked,'Untracked production inputs')
        h=WorldHarness(a.root); h.bootstrap_world()
        for i,c in enumerate(cases):
            case_dir=a.root/'cases'/c['id']; case_dir.mkdir(parents=True)
            h.case_dir=case_dir; h.family=c['id']
            log_offset=len(h.server_log()); frame_offset=len(h.screenshots)
            rec=records[i]; rec['started']=time.time()
            atomic(case_dir/'parameters.json',c)
            atomic(case_dir/'provenance.json',read(a.root/'metadata.json'))
            (case_dir/'reproduce.txt').write_text(f'python3 e2e/campaigns/world/runner.py --root build/replay-{c["id"]} --seed {c['seed']} --mode full --case {c["id"]}\n')
            try:
                rec.update(execute(h,c))
            except Exception as error:
                rec['genuineGameplay']=getattr(h,'executed_case',False)
                rec.update(status='INCONCLUSIVE' if isinstance(error,Inconclusive) else 'INFRASTRUCTURE_BLOCKED' if isinstance(error,(InfrastructureBlocked,AssessmentBlocked,OSError)) else 'INCONCLUSIVE',reason=str(error),trace=traceback.format_exc())
                try: atomic(case_dir/'failure-state.json',h.snapshot())
                except Exception: pass
            finally:
                rec['finished']=time.time()
                (case_dir/'server.log').write_text(h.server_log()[log_offset:])
                atomic(case_dir/'client-events.json', [e for e in h.events('PlayerA') if e.get('time',0)>=rec['started']*1000])
                atomic(case_dir/'screenshots.json',h.screenshots[frame_offset:])
                atomic(case_dir/'result.json',rec); atomic(a.root/'results.json',result)
            # Stop on cleanup failure; never overwrite an active world to keep going.
            h.cleanup()
            for data in h.snapshot().get('players',{}).values():
                require(all(v.get('phase') not in ('EXECUTING','RESEARCHING','RESOLVING','INTERPRETING') for v in data.get('runs',{}).values()),'Cleanup left live work; halt before fixture reset')
            h.case_dir=None
        result['complete']=False # Additional inventory limitations must be resolved before completion.
    except Exception as error:
        result['infrastructureFailure']=dict(reason=str(error),trace=traceback.format_exc())
        for rec in records:
            if rec.get('reason')=='Not reached': rec['reason']=str(error)
    finally:
        if h: h.finish()
        for rec in records:
            case_dir=a.root/'cases'/rec['case']['id']; case_dir.mkdir(parents=True,exist_ok=True)
            atomic(case_dir/'result.json',rec)
            atomic(case_dir/'parameters.json',rec['case'])
            atomic(case_dir/'provenance.json',read(a.root/'metadata.json',dict(baseline=BASELINE,productionTree=TREE,releaseSha256=None,reason='Runtime never started')))
            c=rec['case']
            (case_dir/'reproduce.txt').write_text(f'python3 e2e/campaigns/world/runner.py --root build/replay-{c["id"]} --seed {c["seed"]} --mode full --case {c["id"].removeprefix("heldout-")}\n')
        result['counts']=dict(Counter(r['status'] for r in records))
        result['genuineGameplayExecutions']=sum(r['genuineGameplay'] for r in records)
        result['rates']={name:dict(passed=sum(r.get('properties',{}).get(name) is True for r in records),evaluated=sum(name in r.get('properties',{}) for r in records)) for name in ('conservation','truthfulCompletion','navigation','delivery','harvest','identity')}
        atomic(a.root/'results.json',result)
    raise SystemExit(1 if any(r['status'] not in ('PASS','EXPECTED_SAFE_REFUSAL') for r in records) else 0)

if __name__=='__main__': main()
