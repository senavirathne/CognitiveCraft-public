"""A03 connected-world lease-specific closure; unchanged 64 active /128 hot limits."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import time
import traceback
import uuid

sys.path.insert(0,str(Path(__file__).resolve().parent))
from activity import ActivityHarness
from baseline import RuntimeAHarness,interruption,native_process,same_native_alive
from suite import ROOT,atomic,read,require,reconnect_players,DENIED_PATTERN
from generalization_support import LOG,DST,at,coord,Inconclusive
from generalization import acquisition,receipts

class LeaseHarness(ActivityHarness):
    def compose(self,*args):
        result=subprocess.run(['docker','compose','-p',self.project,'-f',str(ROOT/'e2e/compose.yml'),
                    '-f',str(ROOT/'e2e/parallel_a/compose-activity.yml'),'-f',str(ROOT/'e2e/parallel_a/compose-leases.yml'),*args],
                    cwd=ROOT,env=self.env,text=True,capture_output=True)
        with (self.root/'docker.log').open('a') as output:output.write(result.stdout+result.stderr)
        if result.returncode:raise RuntimeError('A03 Compose failed: '+result.stderr[-2000:])
        return result
    # A01's full view/unload campaign remains at its qualified source. A03 needs
    # one real interrupted original and another ordinary pre-existing actor.
    def observe_live(self,run):return RuntimeAHarness.observe_live(self,run)
    def observe_recovery(self,run,case):return RuntimeAHarness.observe_recovery(self,run,case)
    def probe(self,op,**values):
        ready=self.wait(lambda:read(self.root/'server/lease-ready.json'),'Actual A03 native observer ready',60)
        identifier=str(uuid.uuid4());atomic(self.root/'server/lease-request.json',{'id':identifier,'process':ready['process'],'op':op,**values})
        result=self.wait(lambda:(v if (v:=read(self.root/'server/lease-response.json')) and v['id']==identifier else None),'Actual lease RPC '+op,55)
        require(result['process']==ready['process'],'A03 RPC changed native process')
        if result['status']=='BUSY':return None
        require(result['status'] in ('PASS','DENIED'), 'Native lease RPC failed: '+json.dumps(result))
        return result
    def stable(self):
        def status():
            value=self.probe('status');return value if value and value['ready'] and value['metrics']['ready'] and not value['metrics']['pending'] else None
        return self.wait(status,'Acknowledged hot/history state at unchanged limits',55)
    def inventory(self):return self.wait(lambda:self.probe('inventory'),'Bounded actual hot/archive inventory',45)
    def restart(self):
        old=native_process(self);process=read(self.root/'server/lease-ready.json')['process'];clients={p:read(self.root/'clients'/p/'process.json') for p in self.players}
        self.stop('server');self.start('server');reconnect_players(self,list(self.players))
        self.wait(lambda:(v if (v:=read(self.root/'server/lease-ready.json')) and v['process']!=process else None),'New A03 real process',70)
        new=native_process(self);require((old['hostJvmPid'],old['startTicks'])!=(new['hostJvmPid'],new['startTicks']),'JVM was not replaced')
        require(all(read(self.root/'clients'/p/'process.json')==v for p,v in clients.items()),'Clients changed across same-world restart')
        return {'before':old,'after':new,'sameClients':True}
    def companion_region(self):
        self.command('PlayerA','/gamemode spectator','Spectator|spectator');self.command('PlayerA','/tp 329.5 208 69.5','Teleported')
        self.command('PlayerA','/gamemode survival','Survival|survival')
        self.citizens['PlayerA'],self.entities['PlayerA']=self.companion
        self.wait(lambda:any(a['uuid']==self.companion[1] for a in self.snapshot()['actors']),'Same normal-AI companion loaded',50)
    def one(self):
        self.actions_active=False;self.console(['setblock '+coord(self.other_source)+' minecraft:oak_log'])
        self.wait(lambda:LOG in self.block(self.other_source),'Explicit fixture resupply before the next legitimate native run')
        calls=self.model_calls();before=self.container(self.other_destination,LOG);self.actions_active=True
        run=self.submit(LOG,1,minimum=self.other_source,maximum=self.other_source,destination=self.other_destination)
        view=self.finish_run(run)
        require(all(receipts(view,s,LOG)==1 for s in ('HARVEST','PICKUP','DEPOSIT')),'Actual repeated lease lifecycle lacks native conservation')
        require(self.container(self.other_destination,LOG)==before+1 and self.inventory().get(LOG,0)==0 and self.model_calls()==calls,
                'Repeated known native delivery changed custody/model contract')
        return {'run':run,'view':view,'beforeDestination':before,'afterDestination':before+1,'modelCallsUnchanged':True}

def union_inventory(value):
    rows={l['id']:l for l in value['hot']['leases']}
    for archived in value['archived']:
        raw=archived['payload'].encode();require(hashlib.sha256(raw).hexdigest()==archived['sha256'],'Archived lease payload hash mismatch')
        row=json.loads(archived['payload']);identifier=row['id'];require(identifier==archived['id'],'Archived ID mismatch')
        # Codec and record views use different field names; exact native reference
        # comparison below goes through the actual authorized domain reader.
        rows.setdefault(identifier,row)
    return rows

def archive_hashes(world):
    root=world/'data/cognitivecraft/runtime-history/v1'
    return {p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in root.iterdir() if p.is_file()}

def restart_cut(h,backup,point,original,old_reference,pinned_rows):
    case=h.root/('cut-'+point.lower());case.mkdir()
    token=str(uuid.uuid4());h.stable();armed=h.wait(lambda:h.probe('arm',point=point,token=token),'Actual publication cut armed',40)
    def marker():
        value=read(h.root/'server/lease-cut-reached.json')
        return value if value and value['token']==token else None
    produced=[]
    for n in range(10):
        if marker():break
        h.actions_active=False;h.console(['setblock '+coord(h.other_source)+' minecraft:oak_log']);h.wait(lambda:LOG in h.block(h.other_source),'Cut-cohort explicit source resupply')
        before=h.container(h.other_destination,LOG);h.actions_active=True
        run=h.submit(LOG,1,minimum=h.other_source,maximum=h.other_source,destination=h.other_destination)
        outcome=h.wait(lambda:marker() or (v if (v:=h.run_view('PlayerA',run)) and v.get('phase')=='TERMINAL' else None),'Legitimate native terminal or actually witnessed storage cut',100)
        if not marker():
            view=h.finish_run(run);require(all(receipts(view,s,LOG)==1 for s in ('HARVEST','PICKUP','DEPOSIT')),'Cut trigger failed native operation')
            produced.append({'run':run,'view':view,'beforeDestination':before})
        else:
            produced.append({'run':run,'beforeDestination':before,'lastObserver':h.run_view('PlayerA',run)});break
    reached=h.wait(marker,'Intended publication boundary must actually be reached',30)
    # Worker barriers leave the real server able to save native participants.
    # Hot-thread cuts first hold AFTER_DIRECTORY_ACK, save, then release to the
    # requested main-thread boundary. No asynchronous/native save atomicity claim.
    save=h.save_checkpoint()
    physical=h.snapshot();require(h.container(h.other_destination,LOG)==produced[-1]['beforeDestination']+1,'Cut cohort physical native deposit missing')
    if reached['point']=='PREPARE_SAVE':
        atomic(h.root/'server/lease-cut-release.json',{'token':token,'process':reached['process']})
        reached=h.wait(lambda:(v if (v:=marker()) and v['point']==point else None),'Exact hot cleanup boundary after native save',35)
    require(reached['point']==point,'Wrong cut was witnessed')
    before_process=native_process(h);clients={p:read(h.root/'clients'/p/'process.json') for p in h.players}
    atomic(case/'before-kill.json',{'armed':armed,'reached':reached,'process':before_process,'save':save,'native':physical,'produced':produced})
    h.stop('server',hard=True);h.wait(lambda:not same_native_alive(before_process),'Owned cut JVM actually exited',20)
    world=h.root/'server/game/world';shutil.copytree(world,case/'killed-world',ignore=shutil.ignore_patterns('session.lock'))
    before_archives=archive_hashes(world)
    h.actions_active=False;h.start('server');reconnect_players(h,list(h.players))
    h.wait(lambda:(v if (v:=read(h.root/'server/lease-ready.json')) and v['process']!=reached['process'] else None),'Cut restart has a different real JVM',70)
    require(all(read(h.root/'clients'/p/'process.json')==v for p,v in clients.items()),'Cut restart replaced clients')
    status=h.probe('status');after_process=native_process(h)
    require((before_process['hostJvmPid'],before_process['startTicks'])!=(after_process['hostJvmPid'],after_process['startTicks']),'Cut did not restart JVM')
    job=h.run_view('PlayerA',original['run'])['responsibility']
    require(job['id']==original['job'] and job['state']=='INTERRUPTED' and any(a['uncertain'] or not a.get('terminal') for a in job['attempts']),
            'Lease archival erased the pinned original effect')
    if point=='AFTER_SEGMENT_ACK':
        require(status['storage']['readOnly'] and not status['ready'],'Unacknowledged orphan did not fence restart')
        h.console(['setblock '+coord(h.other_source)+' minecraft:oak_log']);before=h.snapshot();stock=h.container(h.other_destination,LOG)
        request=h.request_run(LOG,1,minimum=h.other_source,maximum=h.other_source,destination=h.other_destination)
        if request['accepted']:
            h.actions_active=True;denied=h.finish_run(request['run'],success=False)
            require(all(receipts(denied,s,LOG)==0 for s in ('HARVEST','PICKUP','DEPOSIT')),'Fenced orphan replayed an effect')
        else:
            denied=request;require(any('reason='+r in request['feedback'] for r in ('INTERRUPTED','STORAGE_UNAVAILABLE')),'Unrelated negative refusal')
        require(h.container(h.other_destination,LOG)==stock and LOG in h.block(h.other_source),'Orphan archive allowed physical replay')
        require(archive_hashes(world)==before_archives,'Fenced restart altered archived originals')
        outcome={'storage':'FENCED_PRESERVED_ORPHAN','negative':denied,'before':before,'after':h.snapshot()}
    else:
        h.stable();lookup=h.probe('lookup',lease=old_reference,staleControls=True);require(lookup['lease']==old_reference and lookup['hotUnchanged'],'Old reference/stale owner changed after native cut')
        require(all(lookup[k]['code'] not in ('GRANTED','VALID') for k in ('validate','renew','release')),'Old process reference became authority')
        inventory=h.inventory();actual=union_inventory(inventory)
        require(set(pinned_rows)<=set(l['id'] for l in inventory['hot']['leases']),'Pinned unresolved rows were evicted')
        require(old_reference['id'] in actual,'Required old lease is unresolved after cut')
        outcome={'storage':'RESOLVABLE','lookup':lookup,'inventory':inventory}
    evidence={'status':'PASS','point':point,'beforeProcess':before_process,'afterProcess':after_process,'sameClients':True,
              'actuallyWitnessed':reached,'saveBeforeCrash':save,'outcome':outcome,'original':job,'automaticContinuation':False}
    atomic(case/'result.json',evidence)
    h.stop('server');world.rename(case/'restarted-world');shutil.copytree(backup,world,ignore=shutil.ignore_patterns('session.lock'))
    old=read(h.root/'server/lease-ready.json')['process'];h.start('server');reconnect_players(h,list(h.players))
    h.wait(lambda:(v if (v:=read(h.root/'server/lease-ready.json')) and v['process']!=old else None),'Coherent whole-world restoration for independent cut cohort',70)
    h.stable();return evidence

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--root',required=True,type=Path);args=parser.parse_args();args.root.mkdir(parents=True,exist_ok=True)
    result={'stage':'A03','status':'NOT_EXECUTED','claim':'lease-specific native lifecycle closure; no Job archival or physical recovery claim','cases':[]};h=None
    try:
        h=LeaseHarness(args.root);h.bootstrap([LOG]);h.family='A03-native-positive';positive=acquisition(h,2,[at(6,2),at(9,2)])
        result['cases'].append({'id':'legacy-native-positive','status':'PASS','evidence':positive})
        h.family='A03-pinned-native-original';case=args.root/'pinned-original';case.mkdir();original=interruption(h,True,case)
        pin=h.stable();pinned=[l for l in pin['hot']['leases'] if l['owner']['job']==original['job']]
        require(len(pinned)==3 and all(l['state']=='INTERRUPTED' for l in pinned),'Real interrupted owner must pin its original three leases')
        h.companion_region();calls=h.model_calls();cycles=[];old_reference=None
        for n in range(43):
            h.family='A03-continuous-lease-cycle-'+str(n+1);record=h.one();cycles.append(record)
            if old_reference is None:
                status=h.stable();job=record['view']['responsibility']['id'];old_reference=next(l for l in status['hot']['leases'] if l['owner']['job']==job)
            print('A03_NATIVE_LEASE_CYCLE '+json.dumps({'number':n+1,'run':record['run'],'job':record['view']['responsibility']['id']}),flush=True)
        status=h.stable();inventory=h.inventory();all_rows=union_inventory(inventory);cycle_jobs={c['view']['responsibility']['id'] for c in cycles}
        actual=[l for l in all_rows.values() if (l.get('owner',{}).get('job') or l.get('job')) in cycle_jobs]
        require(len(actual)==129 and len(cycle_jobs)==43 and all(l['state']!='ACTIVE' for l in actual),'Continuous native history did not exceed 128 legitimate retired leases in 43 Jobs')
        require(status['jobs']<64 and status['metrics']['active']<=64 and status['metrics']['hot']<=128 and status['metrics']['evicted']>0,'Native wall was crossed by raising quotas or exceeding Job wall')
        require(set(l['id'] for l in pinned)<=set(l['id'] for l in status['hot']['leases']),'Unresolved native references did not remain hot')
        lookup=h.probe('lookup',lease=old_reference);require(lookup['lease']==old_reference and not lookup['hot'],'Exact old native lease did not resolve from archive')
        denied=h.probe('lookup',lease=old_reference,principal=h.snapshot()['knownPlayers']['PlayerB']['uuid'])
        require(denied['status']=='DENIED','Private old lease disclosed to another principal')
        require(h.model_calls()==calls and h.menu_count('PlayerA',h.other_destination,LOG)==43 and h.inventory().get(LOG,0)==0,'Continuous known operations used model or lost native custody')
        h.save_checkpoint();restart=h.restart();h.stable();cold=h.probe('lookup',lease=old_reference,staleControls=True)
        require(cold['lease']==old_reference and cold['hotUnchanged'] and all(cold[k]['code'] not in ('GRANTED','VALID') for k in ('validate','renew','release')),'Cold historical lease became live authority')
        result['cases'].append({'id':'continuous-129-retired-native-leases','status':'PASS','cycles':cycles,'actualRetiredLeases':129,'boundedJobs':43,
                                'pinned':pinned,'status':status,'inventory':inventory,'archiveLookup':lookup,'foreignDenied':denied,'restart':restart,'coldLookup':cold})
        h.save_checkpoint();h.stop('server');world=args.root/'server/game/world';backup=args.root/'qualified-continuous-history-world';shutil.copytree(world,backup,ignore=shutil.ignore_patterns('session.lock'))
        h.start('server');reconnect_players(h,list(h.players));h.stable()
        # Each failure/cut cohort starts from this exact complete world, after
        # the continuous 129-lease denominator has already been established.
        for point in ('AFTER_SEGMENT_ACK','AFTER_DIRECTORY_ACK','BEFORE_HOT_PUBLICATION','AFTER_HOT_ACKNOWLEDGEMENT'):
            result['cases'].append({'id':'native-cut-'+point.lower(),'status':'PASS','evidence':restart_cut(h,backup,point,original,old_reference,{l['id']:l for l in pinned})})
        pressure=h.wait(lambda:h.probe('pressure'),'Native host ordinary archive quota fixture',50);require(pressure['nativeEffects']==0,'Quota fixture is not a physical effect')
        pressured=[];refusal=None
        for n in range(20):
            h.actions_active=False;h.console(['setblock '+coord(h.other_source)+' minecraft:oak_log']);h.wait(lambda:LOG in h.block(h.other_source),'Quota cohort fixture source')
            before=h.container(h.other_destination,LOG);run=h.submit(LOG,1,minimum=h.other_source,maximum=h.other_source,destination=h.other_destination);h.actions_active=True
            view=h.finish_run(run,success=False)
            if view.get('marker',{}).get('outcome')!='SUCCEEDED':
                require(all(receipts(view,s,LOG)==0 for s in ('HARVEST','PICKUP','DEPOSIT')) and LOG in h.block(h.other_source) and h.container(h.other_destination,LOG)==before,'Quota refusal produced a native effect')
                require('STORAGE_LIMIT_REACHED' in json.dumps(view),'Refusal was not the unchanged lease storage limit')
                refusal={'run':run,'view':view,'beforeDestination':before,'afterDestination':h.container(h.other_destination,LOG)};break
            require(all(receipts(view,s,LOG)==1 for s in ('HARVEST','PICKUP','DEPOSIT')),'Quota cohort positive is not native delivery');pressured.append({'run':run,'view':view})
        require(refusal is not None,'Archive quota refusal not reached inside bounded native Job history')
        final=h.probe('status');require(final['jobs']<64 and final['metrics']['hot']<=128,'Lease-specific quota test hit the Job limit or raised hot limits')
        result['cases'].append({'id':'native-archive-pressure-no-effect-refusal','status':'PASS','fixture':pressure,'nativeBeforeRefusal':pressured,'refusal':refusal,'status':final})
        result['status']='PASS'
    except Exception as error:
        result.update(status='INCONCLUSIVE' if isinstance(error,Inconclusive) else 'FAIL',reason=str(error),trace=traceback.format_exc())
        if h is not None:result['lastNativeSnapshot']=h.snapshot()
        raise
    finally:
        if h is not None:h.finish()
        atomic(args.root/'a03-results.json',result)
        print('PARALLEL_A_A03_RESULT '+json.dumps({'status':result['status'],'reason':result.get('reason'),'cases':[{'id':c['id'],'status':c['status']} for c in result['cases']]}),flush=True)

if __name__=='__main__':main()
