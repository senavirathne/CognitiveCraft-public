"""A04: isolate the actual old Job wall, then qualify bounded archival in the same connected world."""
from __future__ import annotations
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import time
import traceback
import uuid
from pathlib import Path

sys.path.insert(0,str(Path(__file__).resolve().parent))
from leases import LeaseHarness
from baseline import interruption,native_process,same_native_alive,percentiles
from persistence import LIMITS
from performance_window import reached as window_reached
from suite import ROOT,atomic,read,require,reconnect_players
from generalization_support import LOG,DST,at,coord,Inconclusive
from generalization import acquisition,receipts

class JobHarness(LeaseHarness):
    writes=False
    def compose(self,*args):
        command=['docker','compose','-p',self.project]
        for file in ('e2e/compose.yml','e2e/parallel_a/compose-activity.yml','e2e/parallel_a/compose-leases.yml','e2e/parallel_a/compose-jobs.yml'):
            command+=['-f',str(ROOT/file)]
        result=subprocess.run(command+list(args),cwd=ROOT,env=self.env,text=True,capture_output=True)
        with (self.root/'docker.log').open('a') as output:output.write(result.stdout+result.stderr)
        if result.returncode:raise RuntimeError('A04 Compose failed: '+result.stderr[-2000:])
        return result
    def flags(self,jobs,runtime=True,leases=True):
        self.writes=jobs
        for name,value in [('JOB',jobs),('RUNTIME',runtime),('LEASE',leases)]:self.env['COGNITIVECRAFT_A04_'+name+'_HISTORY']=str(value).lower()
    def probe(self,op,**values):
        ready=self.wait(lambda:read(self.root/'server/job-ready.json'),'Actual A04 observer ready',60)
        identifier=str(uuid.uuid4());request={'id':identifier,'process':ready['process'],'op':op,**values}
        atomic(self.root/'server/job-request.json',request)
        result=self.wait(lambda:(v if (v:=read(self.root/'server/job-response.json')) and v['id']==identifier else None),'Actual Job RPC '+op,55)
        require(result['process']==ready['process'],'A04 response changed native process')
        if op!='status':
            (self.root/'job-rpc').mkdir(exist_ok=True);atomic(self.root/'job-rpc'/ (identifier+'.json'),{'request':request,'response':result})
        if result['status']=='BUSY':return None
        require(result['status'] in ('PASS','DENIED','UNAVAILABLE'),'Actual Job RPC failed: '+json.dumps(result))
        return result
    def stable(self):
        def status():
            value=self.probe('status')
            return value if value and value['ready'] and value['jobs']['ready'] and not value['jobs']['pending'] and not value['leases']['pending'] \
                and value['leases']['hot']<96 and (not self.writes or value['jobs']['hot']<48) else None
        return self.wait(status,'Acknowledged native Job/lease history under the original limits',70)
    def one(self):
        self.stable();return super().one()
    def lookup(self,job,foreign=False,**values):
        principal=self.snapshot()['knownPlayers']['PlayerB' if foreign else 'PlayerA']['uuid']
        return self.probe('lookup',job=job,principal=principal,**values)
    def restart(self,expect_ready=True):
        before=native_process(self);old=read(self.root/'server/job-ready.json')['process']
        clients={p:read(self.root/'clients'/p/'process.json') for p in self.players}
        self.stop('server');self.start('server');reconnect_players(self,list(self.players))
        self.wait(lambda:(v if (v:=read(self.root/'server/job-ready.json')) and v['process']!=old else None),'Real A04 JVM replacement',75)
        after=native_process(self);require((before['hostJvmPid'],before['startTicks'])!=(after['hostJvmPid'],after['startTicks']),'Native JVM did not change')
        require(all(read(self.root/'clients'/p/'process.json')==v for p,v in clients.items()),'A04 replaced compatible clients')
        if expect_ready:self.stable()
        return {'before':before,'after':after,'sameClients':True}
    def window(self,mode):
        self.quiet(100);begin=self.probe('begin-window');io=self.probe(mode+'-benchmark')
        self.wait(lambda:window_reached(self.snapshot(),begin,LIMITS['ticks']),'400 actual A04 cost-window game ticks after the marker',65)
        captures=self.probe('end-window')
        raw=self.wait(lambda:(v if (v:=read(self.root/'server/runtime-a-ticks.json')) and len([s for s in v['samples'] if s['time']>=begin['startMillis']])>=400 else None),
                      '400 retained event-bracketed samples',15)
        ticks=[s for s in raw['samples'] if s['time']>=begin['startMillis']][:400]
        cap=[s for s in captures['captures'] if s['time']>=begin['startMillis']][:400]
        process=native_process(self);raw_file=self.root/(mode+'-raw-window.json')
        atomic(raw_file,{'tickSamples':ticks,'captures':cap,'ackNanos':io['samplesNanos'],'begin':begin,'process':process})
        if len(ticks)!=400 or len(cap)!=400 or len(io['samplesNanos'])!=64:raise Inconclusive('Fixed A04 performance window missing: '+json.dumps({'ticks':len(ticks),'captures':len(cap),'acks':len(io['samplesNanos'])}))
        result={'configuration':mode,'tick':percentiles([s['nanos'] for s in ticks]),'capture':percentiles([s['nanos'] for s in cap]),
                'ack':percentiles(io['samplesNanos']),'io':io,'begin':begin,'nativeProcess':process,'limits':LIMITS,
                'metric':raw['metric'],'workload':'same frozen actual 64-Job metadata; candidate additionally verifies archive and acknowledges 16-row hot removal; fixture setup excluded; zero fixture native effects'}
        result['rawSha256']=hashlib.sha256(raw_file.read_bytes()).hexdigest();atomic(self.root/(mode+'-performance.json'),result)
        for field,percentile,limit in [('tick','p95','tickP95Ms'),('tick','p99','tickP99Ms'),('capture','p99','captureP99Ms'),('ack','p95','ackP95Ms'),('ack','p99','ackP99Ms'),('ack','max','ackMaxMs')]:
            require(result[field][percentile]<=LIMITS[limit],f'Frozen A04 {mode} {field}/{percentile} exceeded {LIMITS[limit]} ms: '+json.dumps(result[field]))
        return result

def archive_hashes(world):
    root=world/'data/cognitivecraft/runtime-history'
    return {p.relative_to(root).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in root.rglob('*') if p.is_file()}

def pinned_unchanged(status,pinned):
    hot={j['id']:j for j in status['hot']['jobs']}
    require(all(hot.get(identifier)==job for identifier,job in pinned.items()),'Original uncertain identity/budget/deadline/receipts changed or left the hot owner')

def no_effect_refusal(h,label,expected):
    h.actions_active=False;h.console(['setblock '+coord(h.other_source)+' minecraft:oak_log'])
    h.wait(lambda:LOG in h.block(h.other_source),'Fixture source acknowledged before negative observation')
    before=h.snapshot();stock=h.container(h.other_destination,LOG);calls=h.model_calls()
    request=h.request_run(LOG,1,minimum=h.other_source,maximum=h.other_source,destination=h.other_destination)
    if request['accepted']:
        h.actions_active=True;view=h.finish_run(request['run'],success=False)
        require(all(receipts(view,s,LOG)==0 for s in ('HARVEST','PICKUP','DEPOSIT')),'Negative produced a native effect');text=json.dumps(view)
    else:view=request;text=request['feedback']
    require(any(reason in text for reason in expected),label+' has the wrong actual refusal: '+text)
    require(h.container(h.other_destination,LOG)==stock and LOG in h.block(h.other_source) and h.inventory().get(LOG,0)==0 and h.model_calls()==calls,
            label+' changed native custody or inference')
    return {'label':label,'request':request,'result':view,'before':before,'after':h.snapshot(),'stock':stock,'calls':calls}

def restore(h,world,backup,case):
    h.stop('server');world.rename(case/'restarted-world');shutil.copytree(backup,world,ignore=shutil.ignore_patterns('session.lock'))
    old=read(h.root/'server/job-ready.json')['process'];h.flags(True);h.start('server');reconnect_players(h,list(h.players))
    h.wait(lambda:(v if (v:=read(h.root/'server/job-ready.json')) and v['process']!=old else None),'Whole matching world restored into a new JVM',75);h.stable()

def crash_cut(h,backup,point,old_job,pinned):
    case=h.root/('cut-'+point.lower());case.mkdir();token=str(uuid.uuid4());h.stable()
    armed=h.wait(lambda:h.probe('arm',point=point,token=token),'Actual Job-only publication observer armed',40)
    def marker():
        value=read(h.root/'server/job-cut-reached.json');return value if value and value['token']==token else None
    produced=[]
    for n in range(20):
        if marker():break
        h.actions_active=False;h.console(['setblock '+coord(h.other_source)+' minecraft:oak_log']);h.wait(lambda:LOG in h.block(h.other_source),'Explicit cut-cohort source fixture')
        stock=h.container(h.other_destination,LOG);h.actions_active=True
        run=h.submit(LOG,1,minimum=h.other_source,maximum=h.other_source,destination=h.other_destination)
        h.wait(lambda:marker() or (v if (v:=h.run_view('PlayerA',run)) and v.get('phase')=='TERMINAL' else None),'Actual native terminal or witnessed Job cut',100)
        if marker():produced.append({'run':run,'beforeDestination':stock,'lastView':h.run_view('PlayerA',run)});break
        view=h.finish_run(run);require(all(receipts(view,s,LOG)==1 for s in ('HARVEST','PICKUP','DEPOSIT')),'Cut trigger lacks actual native conservation')
        produced.append({'run':run,'view':view,'beforeDestination':stock})
        def cut_or_idle():
            if marker():return marker()
            status=h.probe('status');return status if status and status['ready'] and not status['jobs']['pending'] and not status['leases']['pending'] else None
        h.wait(cut_or_idle,'Cut or acknowledged idle metadata before another legitimate trigger',40)
    reached=h.wait(marker,'Intended Job cut must actually be reached',35);save=h.save_checkpoint();physical=h.snapshot()
    require(produced and h.container(h.other_destination,LOG)==produced[-1]['beforeDestination']+1,'Cut lacks the last native deposit')
    if reached['point']=='PREPARE_SAVE':
        atomic(h.root/'server/job-cut-release.json',{'token':token,'process':reached['process']})
        reached=h.wait(lambda:(v if (v:=marker()) and v['point']==point else None),'Exact main-thread Job cut after native save',35)
    require(reached['point']==point,'Wrong boundary was witnessed')
    process=native_process(h);clients={p:read(h.root/'clients'/p/'process.json') for p in h.players};stock=h.container(h.other_destination,LOG)
    atomic(case/'before-kill.json',{'armed':armed,'reached':reached,'process':process,'save':save,'physical':physical,'produced':produced})
    h.stop('server',hard=True);h.wait(lambda:not same_native_alive(process),'Actual cut JVM has exited',20)
    world=h.root/'server/game/world';shutil.copytree(world,case/'killed-world',ignore=shutil.ignore_patterns('session.lock'));hashes=archive_hashes(world)
    h.actions_active=False;h.start('server');reconnect_players(h,list(h.players))
    h.wait(lambda:(v if (v:=read(h.root/'server/job-ready.json')) and v['process']!=reached['process'] else None),'Actual cut restart process',75)
    after=native_process(h);require((process['hostJvmPid'],process['startTicks'])!=(after['hostJvmPid'],after['startTicks']),'Cut did not replace native JVM')
    require(all(read(h.root/'clients'/p/'process.json')==value for p,value in clients.items()),'Cut replaced connected clients')
    status=h.probe('status');pinned_unchanged(status,pinned)
    if point=='AFTER_SEGMENT_ACK':
        require(status['storage']['readOnly'] and not status['ready'],'Unacknowledged Job orphan failed to fence')
        denied=no_effect_refusal(h,'unacknowledged Job segment',('INTERRUPTED','STORAGE_UNAVAILABLE'))
        require(archive_hashes(world)==hashes,'Fenced orphan changed original archive bytes');outcome={'storage':'FENCED','negative':denied}
    else:
        h.stable();lookup=h.lookup(old_job,replay=True,accomplishments=True);foreign=h.lookup(old_job,foreign=True)
        require(lookup['status']=='PASS' and not lookup['hot'] and lookup['hotUnchanged'] and foreign['status']=='DENIED','Old private evidence or replay changed after cut')
        require(lookup['sameReceipt']['code']=='DUPLICATE' and lookup['changedReceipt']['code']=='REJECTED' and lookup['staleSubmission']['code']=='REJECTED','Cold stale evidence became authority')
        outcome={'storage':'RESOLVABLE','lookup':lookup,'foreign':foreign,'inventory':h.wait(lambda:h.probe('inventory'),'Cold complete Job component inventory',45)}
    require(h.container(h.other_destination,LOG)==stock,'Cut recovery duplicated a native delivery')
    evidence={'status':'PASS','point':point,'actuallyWitnessed':reached,'beforeProcess':process,'afterProcess':after,'sameClients':True,
              'save':save,'produced':produced,'outcome':outcome,'pinned':pinned,'automaticContinuation':False}
    atomic(case/'result.json',evidence);restore(h,world,backup,case);return evidence

def corrupt_required(h,backup,kind,old_job,pinned):
    case=h.root/('required-'+kind);case.mkdir();h.stable();h.save_checkpoint();h.stop('server');world=h.root/'server/game/world'
    history=world/'data/cognitivecraft/runtime-history';version=history/'v1'
    manifest=json.loads((version/'manifest.jsonl').read_text().splitlines()[1]);ref=next(r for r in manifest['segments'] if r['owner']=='JOBS')
    segment=version/(ref['digest']+'.seg');original=segment.read_bytes();require(hashlib.sha256(original).hexdigest()==ref['digest'],'Required source segment is not exact')
    if kind=='version-missing':version.rename(case/'preserved-version')
    elif kind=='segment-missing':segment.rename(case/'preserved-segment.seg')
    else:
        (case/'preserved-segment.seg').write_bytes(original);changed=bytearray(original);changed[64]^=1;segment.write_bytes(changed)
    hashes=archive_hashes(world);h.flags(False,False,False);old=read(h.root/'server/job-ready.json')['process'];h.start('server');reconnect_players(h,list(h.players))
    h.wait(lambda:(v if (v:=read(h.root/'server/job-ready.json')) and v['process']!=old else None),'Sticky required reader starts with every writer flag off',75)
    status=h.probe('status');require(status['storage']['enabled'] and status['storage']['readOnly'] and not status['ready'],'Disabled flags forgot required history')
    pinned_unchanged(status,pinned);lookup=h.lookup(old_job);require(lookup['status']=='UNAVAILABLE','Missing/corrupt history proved false absence or success')
    negative=no_effect_refusal(h,kind,('INTERRUPTED','STORAGE_UNAVAILABLE'));after=archive_hashes(world)
    require(all(after.get(name)==digest for name,digest in hashes.items()) and set(after)-set(hashes)<= {'v1/writer.lock'},'Fenced startup changed preserved archive bytes')
    evidence={'status':'PASS','kind':kind,'sourceDigest':ref['digest'],'beforeHashes':hashes,'afterHashes':after,'storage':status,'lookup':lookup,
              'allWriterFlagsOff':True,'negative':negative,'nativeProcess':native_process(h),'pinned':pinned}
    atomic(case/'result.json',evidence);restore(h,world,backup,case);return evidence

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--root',required=True,type=Path);args=parser.parse_args();args.root.mkdir(parents=True,exist_ok=True)
    result={'stage':'A04','status':'NOT_EXECUTED','cases':[]};h=None
    try:
        h=JobHarness(args.root);h.flags(False);h.bootstrap([LOG]);h.family='A04-native-positive';control=acquisition(h,2,[at(6,2),at(9,2)])
        result['cases'].append({'id':'native-positive-control','status':'PASS','evidence':control})
        case=args.root/'pinned-original';case.mkdir();h.family='A04-native-original';original=interruption(h,True,case);h.stable();h.companion_region()
        cycles=[]
        for n in range(64):
            status=h.stable()
            if status['jobs']['hot']==64:break
            h.family='A04-old-wall-'+str(n+1);cycles.append(h.one())
            print('A04_NATIVE_JOB '+json.dumps({'number':len(cycles),'run':cycles[-1]['run'],'job':cycles[-1]['view']['responsibility']['id'],'writer':False}),flush=True)
        status=h.stable();require(status['jobs']['hot']==64 and status['jobs']['active']<32 and status['leases']['hot']<96 and status['jobs']['archived']==0,'Actual old Job limit was not isolated from live/lease pressure')
        before=status['hot'];pinned={j['id']:j for j in before['jobs'] if j['state'] not in ('SUCCEEDED','FAILED','CANCELLED')}
        known={c['view']['responsibility']['id'] for c in cycles}
        old_job=next(j['id'] for j in before['jobs'] if j['id'] in known and j['state']=='SUCCEEDED')
        old_before=h.lookup(old_job,accomplishments=True);foreign_before=h.lookup(old_job,foreign=True)
        require(old_before['hot'] and old_before['knowledge']['allowed'] and foreign_before['status']=='DENIED','Actual old authorized/private proofs missing before archival')
        require(original['job'] in pinned,'Original uncertain native owner is absent')
        refusal=no_effect_refusal(h,'actual old 65th Job admission',('STORAGE_LIMIT_REACHED',));require(h.probe('status')['jobs']['hot']==64,'Old refusal created a Job')
        h.save_checkpoint();world=args.root/'server/game/world';shutil.copyfile(world/'data/cognitivecraft/jobs/v1/state.jsonl',args.root/'old-wall-native-jobs.jsonl')
        frozen=h.wait(lambda:h.probe('freeze-benchmark'),'Actual old-wall metadata and archive frozen for cost fixtures',45)
        old_wall={'id':'actual-old-job-wall','status':'PASS','hot':before,'cycles':cycles,'refusal':refusal,'pinned':pinned,'frozen':frozen,
                  'oldAuthorized':old_before,'foreignDenied':foreign_before}
        result['cases'].append(old_wall);atomic(args.root/'actual-old-job-wall.json',old_wall)
        baseline=h.window('baseline');old_wall['performance']=baseline
        h.flags(True);restart=h.restart();enabled=h.stable();pinned_unchanged(enabled,pinned);candidate=h.window('candidate')
        require(baseline['io']['sourceSha256']==candidate['io']['sourceSha256'],'A04 metadata cost inputs changed')
        inventory=h.wait(lambda:h.probe('inventory'),'Acknowledged native Job archive',45)
        hot_ids={j['id'] for j in enabled['hot']['jobs']};require(old_job not in hot_ids,'The pre-observed old known Job did not archive')
        prior=next(j for j in before['jobs'] if j['id']==old_job);lookup=h.lookup(old_job,replay=True,accomplishments=True);foreign=h.lookup(old_job,foreign=True)
        require(lookup['job']==prior and lookup['knowledge']==old_before['knowledge'] and lookup['accomplishments']==old_before['accomplishments']
                and not lookup['hot'] and lookup['hotUnchanged'] and lookup['knowledge']['allowed'] and foreign['status']=='DENIED','Old authorized outcome/knowledge or denied foreign view changed at compaction')
        require(lookup['sameReceipt']['code']=='DUPLICATE' and lookup['changedReceipt']['code']=='REJECTED' and lookup['staleSubmission']['code']=='REJECTED','Stale original evidence changed a Job')
        more=[]
        for n in range(8):h.family='A04-after-wall-'+str(n+1);more.append(h.one())
        final=h.stable();pinned_unchanged(final,pinned);all_inventory=h.wait(lambda:h.probe('inventory'),'Continuous full Job provenance',45)
        terminal=sum(j['state'] in ('SUCCEEDED','FAILED','CANCELLED') for j in final['hot']['jobs'])
        archived={}
        for row in all_inventory['archived']:
            require(hashlib.sha256(row['payload'].encode()).hexdigest()==row['sha256'],'Native component checksum mismatch')
            for job in json.loads(row['payload'])['jobs']:
                old=archived.setdefault(job['id'],job);require(old==job,'Conflicting actual archived Job body')
        terminal+=sum(identifier not in {j['id'] for j in final['hot']['jobs']} for identifier in archived)
        require(terminal>64 and len(cycles)+len(more)>64 and final['jobs']['hot']<=64 and final['jobs']['active']<=32,'Continuous native terminal denominator did not exceed the actual old limit')
        require(h.menu_count('PlayerA',h.other_destination,LOG)==len(cycles)+8 and h.inventory().get(LOG,0)==0,'Native cumulative delivery balance differs from original requests')
        h.screenshot('PlayerA','a04-native-over-64-menu')
        h.save_checkpoint();cold=[]
        for n in range(2):
            cold.append(h.restart());value=h.lookup(old_job,replay=True,accomplishments=True);denied=h.lookup(old_job,foreign=True)
            require(value['job']==prior and value['knowledge']==lookup['knowledge'] and value['accomplishments']==lookup['accomplishments'] and value['hotUnchanged'] and denied['status']=='DENIED','Cold original outcome/access/accomplishment proof changed')
            pinned_unchanged(h.stable(),pinned)
        result['cases'].append({'id':'continuous-native-over-64-terminal-jobs','status':'PASS','initialRestart':restart,'additional':more,'terminalJobs':terminal,
                                'nativeCompanionDeliveries':len(cycles)+8,'original':original,'pinned':pinned,'oldLookup':lookup,'foreignDenied':foreign,
                                'inventory':all_inventory,'before':enabled,'after':final,'coldRestarts':cold,'performance':candidate})
        h.save_checkpoint();h.stop('server');backup=args.root/'qualified-continuous-world';shutil.copytree(world,backup,ignore=shutil.ignore_patterns('session.lock'))
        h.start('server');reconnect_players(h,list(h.players));h.stable()
        for point in ('AFTER_SEGMENT_ACK','AFTER_DIRECTORY_ACK','BEFORE_HOT_PUBLICATION','AFTER_HOT_ACKNOWLEDGEMENT'):
            result['cases'].append({'id':'native-job-cut-'+point.lower(),'status':'PASS','evidence':crash_cut(h,backup,point,old_job,pinned)})
        for kind in ('segment-missing','segment-corrupt','version-missing'):
            result['cases'].append({'id':'native-required-'+kind,'status':'PASS','evidence':corrupt_required(h,backup,kind,old_job,pinned)})
        result['status']='PASS'
    except Exception as error:
        result.update(status='INCONCLUSIVE' if isinstance(error,Inconclusive) else 'FAIL',reason=str(error),trace=traceback.format_exc())
        if h is not None:result['lastSnapshot']=h.snapshot()
        raise
    finally:
        if h is not None:h.finish()
        atomic(args.root/'a04-results.json',result)
        print('PARALLEL_A_A04_RESULT '+json.dumps({'status':result['status'],'reason':result.get('reason'),'cases':[{'id':c['id'],'status':c['status']} for c in result['cases']]}),flush=True)

if __name__=='__main__':main()
