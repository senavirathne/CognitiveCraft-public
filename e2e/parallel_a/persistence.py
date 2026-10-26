"""A02 real-world compatibility and frozen configuration-baseline/candidate storage gates."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time
import traceback
import uuid

sys.path.insert(0,str(Path(__file__).resolve().parent))
from activity import ActivityHarness
from baseline import native_process,percentiles
from suite import ROOT,atomic,read,require,reconnect_players
from generalization_support import LOG,DST,at
from generalization import acquisition,receipts

LIMITS={'tickP95Ms':20,'tickP99Ms':40,'captureP99Ms':2,'ackP95Ms':250,'ackP99Ms':1000,'ackMaxMs':30000,
        'ticks':400,'ackSamples':64,'baseline':'same exact source/JAR, new history disabled; legacy forced snapshot',
        'candidate':'same exact source/JAR and world/clients, history enabled; immutable segment plus manifest'}

class PersistenceHarness(ActivityHarness):
    def compose(self,*args):
        result=subprocess.run(['docker','compose','-p',self.project,'-f',str(ROOT/'e2e/compose.yml'),
                    '-f',str(ROOT/'e2e/parallel_a/compose-activity.yml'),'-f',str(ROOT/'e2e/parallel_a/compose-persistence.yml'),*args],
                    cwd=ROOT,env=self.env,text=True,capture_output=True)
        with (self.root/'docker.log').open('a') as output:output.write(result.stdout+result.stderr)
        if result.returncode:raise RuntimeError('A persistence Compose failed: '+result.stderr[-2000:])
        return result

    def probe(self,op,**values):
        ready=self.wait(lambda:read(self.root/'server/persistence-ready.json'),'Actual native A02 fixture ready',50)
        identifier=str(uuid.uuid4());request={'id':identifier,'process':ready['process'],'op':op,**values}
        atomic(self.root/'server/persistence-request.json',request)
        result=self.wait(lambda:(v if (v:=read(self.root/'server/persistence-response.json')) and v['id']==identifier else None),
                         'Actual native storage response '+op,180)
        atomic(self.root/'storage-rpc'/f'{identifier}.json',{'request':request,'response':result})
        require(result['process']==ready['process'] and result['status']=='PASS','Native storage '+op+' failed: '+json.dumps(result))
        return result

    def restart(self):
        before=native_process(self);old=read(self.root/'server/persistence-ready.json');clients={p:read(self.root/'clients'/p/'process.json') for p in self.players}
        self.stop('server')
        for p in self.players:self.wait(lambda:not self.action(p,'snapshot')['play'],'Native stop disconnect '+p,45)
        self.start('server');reconnect_players(self,list(self.players))
        self.wait(lambda:(v if (v:=read(self.root/'server/persistence-ready.json')) and v['process']!=old['process'] else None),'New native storage process authority',60)
        self.console(['tick unfreeze','tick rate 20'],lifecycle=True)
        after=native_process(self)
        require((before['hostJvmPid'],before['startTicks'])!=(after['hostJvmPid'],after['startTicks']),'Actual server JVM was not replaced')
        require(all(read(self.root/'clients'/p/'process.json')==v for p,v in clients.items()),'Compatible real clients were replaced')
        return {'before':before,'after':after,'sameClients':True,'oldProbeProcess':old['process'],'newProbeProcess':read(self.root/'server/persistence-ready.json')['process']}

    def window(self,mode):
        self.quiet(100)
        self.probe('begin-window');start_ms=int(time.time()*1000);start_tick=self.snapshot()['tick']
        io=self.probe(mode+'-benchmark')
        self.wait(lambda:self.snapshot()['tick']>=start_tick+LIMITS['ticks'],'400 actual stable-window ticks',60)
        captured=self.probe('end-window')
        measured=self.wait(lambda:(v if (v:=read(self.root/'server/runtime-a-ticks.json')) and len([s for s in v['samples'] if s['time']>=start_ms])>=LIMITS['ticks'] else None),
                           '400 event-bracketed tick samples flushed',15)
        ticks=[s['nanos'] for s in measured['samples'] if s['time']>=start_ms][:LIMITS['ticks']]
        captures=captured['captureNanos'][:LIMITS['ticks']]
        require(len(captures)==LIMITS['ticks'] and len(io['samplesNanos'])==LIMITS['ackSamples'],'Fixed capture/ack sample count missing')
        result={'configuration':mode,'tick':percentiles(ticks),'capture':percentiles(captures),'ack':percentiles(io['samplesNanos']),
                'io':io,'startTick':start_tick,'startMillis':start_ms,'metric':measured['metric'],
                'selectors':'first 400 ticks after begin acknowledgement; first 400 captures after begin; all 64 sequential forced publications'}
        for field,percentile,limit in [('tick','p95','tickP95Ms'),('tick','p99','tickP99Ms'),('capture','p99','captureP99Ms'),('ack','p95','ackP95Ms'),('ack','p99','ackP99Ms'),('ack','max','ackMaxMs')]:
            require(result[field][percentile]<=LIMITS[limit],f'Frozen {mode} {field}/{percentile} exceeded {LIMITS[limit]} ms: '+json.dumps(result[field]))
        atomic(self.root/f'{mode}-performance.json',result)
        return result

def job_identities(h):
    known=h.snapshot()['knownPlayers']['PlayerA'];jobs=[v['responsibility'] for v in known['runs'].values() if v.get('responsibility')]
    return {j['id']:{k:j[k] for k in ['id','submissionId','state','generation','allowance','usage','attempts']} for j in jobs}

def settled_identities(h):
    def settled():
        values=job_identities(h)
        return values if values and all(j['state'] in ('SUCCEEDED','FAILED','CANCELLED') and
                all(a.get('terminal') is not None and not a['uncertain'] for a in j['attempts']) for j in values.values()) else None
    return h.wait(settled,'Actual durable baseline owners settled before comparing configurations',45)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--root',required=True,type=Path);args=parser.parse_args();args.root.mkdir(parents=True,exist_ok=True)
    result={'stage':'A02','status':'NOT_EXECUTED','limits':LIMITS,'cases':[],
            'claim':'storage foundation/native host integration; no domain eviction, physical proof or automatic continuation'};h=None
    try:
        h=PersistenceHarness(args.root);h.env['COGNITIVECRAFT_RUNTIME_HISTORY']='false';h.bootstrap([LOG]);h.family='A02-baseline-control'
        control=acquisition(h,2,[at(6,2),at(9,2)]);baseline_status=h.probe('status');require(not baseline_status['storage']['enabled'],'Baseline new-history activation was not disabled')
        original=settled_identities(h);baseline=h.window('baseline');h.save_checkpoint()
        result['cases'].append({'id':'legacy-positive-and-baseline','status':'PASS','executionClass':'NATIVE_CONNECTED_CLIENT_AND_PERFORMANCE','control':control,'performance':baseline})
        h.env['COGNITIVECRAFT_RUNTIME_HISTORY']='true';restart=h.restart();status=h.probe('status')
        require(status['storage']['enabled'] and not status['storage']['readOnly'],'Production kernel history did not activate healthy')
        require(job_identities(h)==original and h.container(DST,LOG)==2,'Original native owner/budget/custody changed during opt-in same-world restart')
        candidate=h.window('candidate');require(candidate['io']['sourceSha256']==baseline['io']['sourceSha256'],'Benchmark source snapshots differ across the two configurations')
        compat=h.probe('compat');revision=compat['manifest']['revision'];again=h.probe('compat')
        require(again['manifest']['revision']==revision and again['migration']==compat['migration'],'Repeated migration duplicated publication or changed its target')
        h.save_checkpoint();restart2=h.restart();verified=h.probe('verify',key=compat['key'])
        require(verified['payloadSha256']==compat['payloadSha256'] and job_identities(h)==original and h.container(DST,LOG)==2,
                'Actual world/metadata identities or archived bytes changed during restart')
        after=h.probe('compat');require(after['manifest']['revision']==revision,'Cold repeated migration changed immutable original history')
        require(after['view']['retentionGeneration']>=compat['manifest']['retentionGeneration'],'Reference generation regressed across native process restart')
        result['cases'].append({'id':'real-world-compatibility-and-acknowledged-history','status':'PASS','executionClass':'NATIVE_HOST_STORAGE_REAL_METADATA',
                                'restart':restart,'secondRestart':restart2,'beforeOriginals':original,'compat':compat,'repeated':again,'coldVerified':verified,'coldRepeated':after,'performance':candidate})
        pressure=h.probe('headroom');require(pressure['reservedOriginalsSettled']==8 and pressure['nativeEffects']==0,'Native-host reserved capacity fixture incomplete')
        result['cases'].append({'id':'reserved-metadata-under-ordinary-pressure','status':'PASS','executionClass':'NATIVE_HOST_FILESYSTEM_FIXTURE','evidence':pressure})
        h.family='A02-enabled-control';enabled=acquisition(h,2,[at(6,2),at(9,2)])
        result['cases'].append({'id':'history-enabled-native-positive','status':'PASS','executionClass':'NATIVE_CONNECTED_CLIENT','evidence':enabled})
        # Corrupt only the new history header in a fully retained, quiescent world fixture.
        h.reset({},table=False);h.logs([at(6,2)]);h.save_checkpoint();h.stop('server')
        world=h.root/'server/game/world';backup=h.root/'coherent-before-corruption-world';shutil.copytree(world,backup,ignore=shutil.ignore_patterns('session.lock'))
        current=world/'data/cognitivecraft/runtime-history/v1/manifest.jsonl';original_bytes=current.read_bytes();header,body=original_bytes.split(b'\n',1)
        require(b'"schema":1' in header,'Known history schema header missing before explicit fault')
        damaged=header.replace(b'"schema":1',b'"schema":99',1)+b'\n'+body;current.write_bytes(damaged)
        archive_hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in current.parent.iterdir() if p.is_file()}
        old_process=read(h.root/'server/persistence-ready.json')['process'];h.start('server');reconnect_players(h,list(h.players))
        h.wait(lambda:(v if (v:=read(h.root/'server/persistence-ready.json')) and v['process']!=old_process else None),'Corrupt-history native process ready',60)
        fenced=h.probe('status');require(fenced['storage']['readOnly'],'Unknown current history was not fenced')
        h.family='A02-unknown-history-negative';before=h.snapshot();response=h.request_run(LOG,1)
        if response['accepted']:
            h.actions_active=True
            denied=h.finish_run(response['run'],success=False);require(denied.get('marker',{}).get('outcome')!='SUCCEEDED' and all(receipts(denied,s,LOG)==0 for s in ('HARVEST','PICKUP','DEPOSIT')),'Fenced history authorized native effects')
        else:denied=response
        require(LOG in h.block(at(6,2)) and h.container(DST,LOG)==0 and h.inventory().get(LOG,0)==0,'Unknown history changed actual participant custody')
        require(current.read_bytes()==damaged,'Unknown current archive header was overwritten')
        require({p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in current.parent.iterdir() if p.is_file()}==archive_hashes,
                'Fenced history changed an original archive file')
        atomic(h.root/'unknown-history-negative.json',{'status':'PASS','readOnlyStatus':fenced,'before':before,'after':h.snapshot(),'denied':denied,
                'currentSha256':hashlib.sha256(damaged).hexdigest(),'originalHeaderSha256':hashlib.sha256(original_bytes).hexdigest()})
        h.stop('server');world.rename(h.root/'retained-corrupt-history-world');shutil.copytree(backup,world,ignore=shutil.ignore_patterns('session.lock'))
        h.start('server');reconnect_players(h,list(h.players));h.wait(lambda:(v if (v:=read(h.root/'server/persistence-ready.json')) and v['process']!=fenced['process'] else None),'Coordinated whole-world rollback native process ready',60)
        restored=h.probe('status');require(not restored['storage']['readOnly'] and current.read_bytes()==original_bytes,'Whole-world plus metadata rollback did not restore the matching healthy state')
        result['cases'].append({'id':'unknown-history-fence-and-whole-world-rollback','status':'PASS','executionClass':'NATIVE_CONNECTED_CLIENT_AND_STORAGE_FAULT','fenced':fenced,'restored':restored,
                                'retainedWorld':'retained-corrupt-history-world','rollbackSource':'coherent-before-corruption-world'})
        result['status']='PASS'
    except Exception as error:
        result.update(status='FAIL',reason=str(error),trace=traceback.format_exc())
        if h is not None:result['lastNativeSnapshot']=h.snapshot()
        raise
    finally:
        if h is not None:h.finish()
        atomic(args.root/'a02-results.json',result)
        print('PARALLEL_A_A02_RESULT '+json.dumps({'status':result['status'],'reason':result.get('reason'),'cases':[{'id':c['id'],'status':c['status']} for c in result['cases']]}),flush=True)

if __name__=='__main__':main()
