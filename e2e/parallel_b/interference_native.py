#!/usr/bin/env python3
"""B07 two real clients; ordinary source-menu removal in a naturally yielding window."""
from __future__ import annotations
import argparse
import copy
import json
from pathlib import Path
import sys
import traceback
import urllib.request

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from suite import ROOT,atomic,read,require,DENIED_PATTERN
from runtime import SDK,digest
from generalization_support import AssessmentHarness,LOG,PLANK,CAPS,SRC,DST,at,coord
from parallel_b.diagnostics import enroll_subjects,settled,probe,lines,admit
from parallel_b.guards import instruments,observer_inputs
from parallel_b.interference_oracle import evaluate
from campaigns.crafting.oracle import candidates

MOVED='minecraft:cobblestone'

def actor(row):return row['details']['request']['request']['arguments']['actor']['value']['entityId']
def b_count(snapshot):return snapshot['knownPlayers']['PlayerB']['assessmentInventory'].get(MOVED,0)
def source_key():return ','.join(map(str,SRC))


def observed_preparation(root,run):
    """Wait for the actual successful runtime observation, not an earlier intake preflight."""
    path=root/'server/stdout.log'
    if not path.exists():return None
    active=False;handles=set()
    for line in path.read_text(errors='replace').splitlines():
        if 'CRAFTING_PRIMITIVE_TRACE ' not in line:continue
        try:r=json.loads(line.split('CRAFTING_PRIMITIVE_TRACE ',1)[1]);v=r['value']
        except (ValueError,KeyError):continue
        if r['kind']=='primitive-start' and v['correlation']['runId']==run:
            active=v['primitive']['id']=='cognitivecraft:observe_plank_inputs'
        elif r['kind']=='primitive-handle' and v['handle']['runId']==run and active:
            handles.add(v['handle']['id']);active=False
        elif r['kind']=='primitive-terminal' and v['handle']['id'] in handles:
            receipt=v['receipt']
            if receipt.get('terminal') and receipt.get('result',{}).get('value') is True and not receipt.get('reason'):
                return r
    return None


def open_source(h,stock):
    h.walk('PlayerB',SRC);h.action('PlayerB','open',pos=SRC)
    return h.wait(lambda:(s if (s:=h.client_snapshot('PlayerB')) and s['menu']>0 and
        sum(x['count'] for x in s['slots'] if not x['playerInventory'] and x['item']==LOG)==stock and
        sum(x['count'] for x in s['slots'] if not x['playerInventory'] and x['item']==MOVED)==1 else None),
        'PlayerB synchronized real source menu before request',30)


def remove(h,menu,before_b):
    slot=next(x['slot'] for x in menu['slots'] if not x['playerInventory'] and x['item']==MOVED)
    scheduled={'beforeNativeTick':h.snapshot()['gameTick'],'clientResult':h.action('PlayerB','menu-click',slot=slot,quick=True)}
    def acknowledged():
        s=h.snapshot();c=h.client_snapshot('PlayerB')
        native=h.container(SRC,MOVED)==0 and b_count(s)==before_b+1
        synchronized=sum(x['count'] for x in c['slots'] if x['playerInventory'] and x['item']==MOVED)==before_b+1
        return {'snapshot':s,'client':c,'nativeMutation':native,'clientSynchronized':synchronized} if native and synchronized else None
    ack=h.wait(acknowledged,'Independent server source decrement, real PlayerB inventory and client synchronization',30)
    ack['probe']=probe(h,[SRC]);ack['scheduled']=scheduled
    return ack


def trial(h,index,*,missed=False):
    settled(h);h.family='B07-'+('deliberately-missed' if missed else 'window-'+str(index))
    # Preserve all stock covered by the original input lease. Removing the unrelated
    # stack changes the full native source fingerprint without bypassing that lease.
    h.reset({LOG:2 if missed else 1,MOVED:1},table=False,actor=at(1,4))
    h.console(['data merge entity '+h.entities['PlayerA']+' {Inventory:[]}',
               'data merge entity '+h.entities['PlayerB']+' {Inventory:[]}'])
    menu=open_source(h,2 if missed else 1)
    # Clean-fixture pose acknowledgement immediately before intake preserves a real
    # travel window; no clock/AI/effect/authority change occurs during the request.
    pose=[65.5,208,68.5]
    h.console(['tp '+h.entities['PlayerA']+' '+coord(pose)])
    h.wait(lambda:any(a['uuid']==h.entities['PlayerA'] and __import__('math').dist(a['pos'],pose)<1
                     for a in h.snapshot()['actors']),'Native pre-request actor pose acknowledgement',20)
    before=h.snapshot();calls=h.model_calls();offset=len(lines(h.root/'server/b-guards.jsonl'))
    path=h.root/'cases'/f'{index:02d}.json'
    row={'index':index,'deliberatelyMissed':missed,'before':before,'callsBefore':calls,'guardsOffset':offset,
         'window':'captured preflight to final withdraw return','administrativeMutationsDuringRun':0,
         'automaticReplay':False,'twoRealClients':True}
    atomic(path,row);h.actions_active=True
    req=h.request_run(PLANK,4);row['request']=req;atomic(path,row)
    require(req['accepted'],'Baseline interference request was not admitted: '+req['feedback'])
    h.wait(lambda:any(x['phase']=='preflight' and x['actualReturned'].get('availableInputs',0)>0 and actor(x)==h.entities['PlayerA']
               for x in lines(h.root/'server/b-guards.jsonl')[offset:]),'Actual native preflight with available source stock',35)
    row['runtimePreflightTerminal']=h.wait(lambda:observed_preparation(h.root,req['run']),
        'Original run successful observe_plank_inputs terminal before ordinary interference',40)
    if missed:
        row['view']=h.finish_run(req['run']);h.actions_active=True
        require(h.container(DST,PLANK)==4 and h.container(SRC,LOG)==1,'Missed control must complete before interference')
        menu=h.client_snapshot('PlayerB')
    ack=remove(h,menu,b_count(before));row['mutationAcknowledgement']=ack;atomic(path,row)
    if not missed:row['view']=h.finish_run(req['run'],success=False)
    h.actions_active=False;row['after']=h.snapshot();row['callsAfter']=h.model_calls()
    guards=lines(h.root/'server/b-guards.jsonl')[offset:];row['guards']=guards
    withdrawals=[x for x in guards if x['phase']=='withdraw-return' and x['details']['actor']['entityId']==h.entities['PlayerA']]
    if withdrawals:
        w=withdrawals[-1];captured=w['details']['captured']
        pre=[x for x in guards if x['phase']=='preflight' and x['actualReturned']==captured and actor(x)==h.entities['PlayerA']]
        if pre:
            p=pre[-1];q=ack['probe'];native=q['positions'][0]
            proof={'run':req['run'],'actor':h.entities['PlayerA'],'inputItem':LOG,'movedItem':MOVED,
                'principal':before['knownPlayers']['PlayerA']['uuid'],'interferer':before['knownPlayers']['PlayerB']['uuid'],
                'twoRealClients':True,'callsBefore':calls,'callsAfter':row['callsAfter'],
                'preflight':{'actor':actor(p),'tick':p['gameTick'],'available':captured['availableInputs'],
                    'captured':captured,'sourceIdentity':p['details']['source']['blockEntityIdentity'],
                    'sourceFingerprint':captured['sourceFingerprint']},
                'ack':{'tick':q['gameTick'],'nativeMutation':ack['nativeMutation'],'clientSynchronized':ack['clientSynchronized'],
                    'sourceIdentity':native['identity'],'sourceFingerprint':native['fingerprint']},
                'withdraw':{'actor':w['details']['actor']['entityId'],'tick':w['gameTick'],'captured':captured,
                    'sourceIdentity':w['details']['source']['blockEntityIdentity'],'reason':w['actualReturned'].get('reason'),
                    'inputCount':w['actualReturned']['moved']},
                'quantities':{'sourceDelta':h.container(SRC,MOVED)-before['containers'][source_key()].get(MOVED,0),
                    'playerBDelta':b_count(row['after'])-b_count(before),'actorInputDelta':h.inventory().get(LOG,0),
                    'actorOutputDelta':h.inventory().get(PLANK,0),'destinationDelta':h.container(DST,PLANK),
                    'creditedOutput':sum(r['quantity'] for r in row['view'].get('resourceReceipts',[]) if r['stage']=='DEPOSIT')}}
            row['proof']=proof;row['oracle']=evaluate(proof)
        else:row['oracle']={'status':'INCONCLUSIVE','reason':'No identical captured preflight independently observed'}
    else:row['oracle']={'status':'INCONCLUSIVE','reason':'Actual native final withdrawal was not reached'}
    row['methodPin']=h.hashes['cognitivecraft:'+CAPS[PLANK]]
    require(row['view']['marker']['artifactSha256']==row['methodPin'],'Interference changed immutable method identity')
    require(calls==row['callsAfter'],'Interference invoked model generation')
    atomic(path,row);h.action('PlayerB','close-menu');h.screenshot('PlayerB','independent-transfer',chat=True)
    row['settlement']=settled(h,fence=True);atomic(path,row)
    if missed:require(row['oracle']['status']=='INCONCLUSIVE','Late action incorrectly counted as witnessed interference')
    else:require(row['oracle']['status'] not in ('FAIL_GAMEPLAY','INVALID_EVIDENCE'),'Interference failed independent oracle: '+json.dumps(row['oracle']))
    return row


def shared_reuse(h):
    settled(h);h.family='B07-shared-method';h.reset({LOG:1},table=False)
    h.console(['data merge entity '+h.entities['PlayerB']+' {Inventory:[]}'])
    before=h.snapshot();calls=h.model_calls();req=h.request_run(PLANK,4,player='PlayerB');require(req['accepted'],'Shared legacy method reuse refused')
    h.actions_active=True;view=h.finish_run(req['run'],'PlayerB')
    pin=h.hashes['cognitivecraft:'+CAPS[PLANK]]
    require(view['marker']['artifactSha256']==pin and h.container(DST,PLANK)==4,'Shared method changed identity or lacked native delivery')
    require(h.model_calls()==calls,'Shared reuse invoked generation')
    row={'request':req,'view':view,'before':before,'after':h.snapshot(),'callsBefore':calls,'callsAfter':h.model_calls(),
         'sameMethodSha256':pin,'assertionStatus':'PENDING_FOREIGN_STATUS','privateMode':'INTEGRATION_PENDING: A grant/private fixture port'}
    atomic(h.root/'shared-reuse.json',row)
    foreign=h.command('PlayerA','/aivillage kernel status '+req['run'],DENIED_PATTERN)
    row.update(foreignStatus=foreign,assertionStatus='PASS')
    row['settlement']=settled(h,fence=True);atomic(h.root/'shared-reuse.json',row);return row


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,required=True);a=p.parse_args()
    h=AssessmentHarness(a.root,'parallel-b-interference');h.project='cc-production-b-'+read(h.root/'metadata.json')['fixtureInstance'][:8]
    h.family='B07-STARTUP';(h.root/'b-diagnostics-enabled').touch();result={'status':'NOT_EXECUTED','rows':[]}
    try:
        instruments(ROOT/'fabric/build/libs/ai-villages-0.1.0.jar',ROOT/'build/e2e-driver/observer.jar')
        atomic(h.root/'oracle-candidates.json',candidates(SDK/'minecraft-client.jar'))
        observer_inputs(h.root)
        h.env['COGNITIVECRAFT_OLLAMA_MODEL']='qwen3:4b-instruct-2507-q4_K_M'
        h.compose('build','minecraft');h.compose('up','-d','ollama');h.compose('exec','-T','ollama','ollama','pull',h.env['COGNITIVECRAFT_OLLAMA_MODEL'])
        with urllib.request.urlopen('http://127.0.0.1:11434/api/tags',timeout=20) as r:atomic(h.root/'models.json',json.load(r))
        h.fresh(two=True);enroll_subjects(h);h.command('PlayerA','/aivillage kernel inference true','admission=true')
        admit(h,PLANK);h.command('PlayerA','/aivillage kernel inference false','admission=false');h.compose('stop','ollama')
        for i in range(3):
            row=trial(h,i,missed=i==2);result['rows'].append({'index':i,'oracle':row['oracle'],'run':row['request']['run']})
            atomic(h.root/'result.json',result)
        shared_reuse(h)
        witnessed=sum(r['oracle']['status']=='WITNESSED_SAFE_REFUSAL' for r in result['rows'])
        require(witnessed>=1,'No genuinely witnessed native interference window; scheduled/missed actions are inconclusive')
        result.update(status='PASS',witnessedWindows=witnessed,deliberatelyMissed=1,connectedClients=2,
                      sharedReuse=True,I05Attribution='UNKNOWN',methods=h.hashes)
    except Exception as e:
        result.update(status='FAIL',reason=str(e),trace=traceback.format_exc());atomic(h.root/'failure-snapshot.json',h.snapshot());raise
    finally:
        h.finish();atomic(h.root/'result.json',result)
        m=read(h.root/'metadata.json');m.update(profile='B07',diagnosticInstrument=read(ROOT/'build/b-diagnostics/integrity.json'),
            specSha256=digest(Path(__file__).with_name('qualification-specs.json')));atomic(h.root/'metadata.json',m)
        atomic(h.root/'manifest.json',{'source':m,'files':[{'path':str(f.relative_to(h.root)),'bytes':f.stat().st_size,'sha256':digest(f)}
            for f in sorted(h.root.rglob('*')) if f.is_file() and f.name!='manifest.json']})
    print(json.dumps(result),flush=True)


if __name__=='__main__':main()
