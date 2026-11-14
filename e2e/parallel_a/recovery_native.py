"""All twelve frozen saved-checkpoint originals on one built source and connected-client track."""
import argparse,json,re,shutil,time,traceback,uuid
from pathlib import Path
from harvest_native import HarvestHarness
from deposits import manifest
from baseline import native_process,same_native_alive,percentiles
from suite import ROOT,atomic,read,require,reconnect_players,Harness,UUID_PATTERN
from generalization_support import LOG,BIRCH,DST,coord
from recovery_cases import originals,positions,settled,STAGES
from recovery_fixture import native_saved,source_row

WHEAT='minecraft:wheat'

class RecoveryHarness(HarvestHarness):
    case_stage='HARVEST'
    def __init__(self,root):
        super().__init__(root)
        # Capture includes wall-clock pauses. Select a low-pause collector
        # within the same two-core server and heap cap.
        self.env['A06_NATIVE_JAVA_TOOL_OPTIONS']=(
            '-XX:+UseZGC -XX:ConcGCThreads=1 '
            '-Xlog:gc*,safepoint:file=/evidence/server/a06-gc-%t-%p.log:time,uptime,level,tags:filecount=4,filesize=8M')
    def start(self,component):
        result=super().start(component)
        if component=='server' and self.env.get('A06_HARVEST_ENABLED')=='true':
            def ready():
                current=(self.root/'server/game/logs/latest.log').read_text()
                if 'CognitiveCraft kernel disabled' in current:
                    raise RuntimeError('Experimental kernel startup failed: '+current[current.index('CognitiveCraft kernel disabled'):][-4500:])
                value=self.evidence()
                runtime=read(self.root/'server/harvest-ready.json')
                return runtime if value and runtime and runtime['process']==value['process'] else None
            runtime=self.wait(ready,'Current experimental kernel and native evidence owners ready',60)
            require(runtime['availableProcessors']==2 and
                    {'-Xms256M','-Xmx1536M','-XX:+UseZGC','-XX:ConcGCThreads=1'}<=set(runtime['inputArguments']),
                    'Native server differs from the declared A06 CPU and JVM budget')
            self.event('a06-native-jvm',runtime)
        return result
    def quiet(self,tick_count=40):
        start=self.snapshot()['tick'];self.wait(lambda:self.snapshot()['tick']>=start+tick_count,'Post-boundary physical fence',max(20,tick_count/20+20))
    def evidence(self):
        value=super().evidence()
        if not value:return None
        selected=value.get('stages',{}).get(self.case_stage)
        return dict(value,**selected) if selected else value
    def submit(self,item,quantity=1,**kwargs):
        if item!=WHEAT:return super().submit(item,quantity,**kwargs)
        acquire=kwargs.pop('acquire',False);player=kwargs.pop('player','PlayerA')
        if not acquire:require('cognitivecraft:deliver_wheat' in self.hashes,'Genuine wheat admission missing')
        source=kwargs.pop('minimum',[73,208,73])+kwargs.pop('maximum',[73,208,73]);destination=kwargs.pop('destination',DST)
        require(not kwargs,'Unsupported wheat fixture arguments')
        run=Harness.submit(self,player=player,amount=quantity,source=source,destination=destination);self.actions_active=True;return run
    def crops(self,points):
        self.console([command for p in points for command in ('setblock '+coord([p[0],p[1]-1,p[2]])+' minecraft:farmland[moisture=7]',
                                                               'setblock '+coord(p)+' minecraft:wheat[age=7]')])
        self.wait(lambda:all('minecraft:wheat' in self.block(p) and 'age=7' in self.block(p) for p in points),'Actual mature crop fixture',25)

def physical(h,case):
    value=h.snapshot();actor=next(a for a in value['actors'] if a['uuid']==h.entities['PlayerA']);items={case['item']}
    if case['item']==WHEAT:items.add('minecraft:wheat_seeds')
    return {'inventory':actor['inventory'],'destination':h.container([case['destination'][k] for k in ('x','y','z')],case['item']),
            'drops':sorted((d['uuid'],d['item'],d['count']) for d in value['drops'] if d['item'] in items),
            'blocks':{coord(p):h.block(p) for p in positions(case)}}

def restart_after_cut(h,before,clients,hold_world=False):
    for player in h.players:h.wait(lambda:not h.action(player,'snapshot')['play'],'Actual cut disconnected '+player,45)
    h.actions_active=False;h.activated=int(time.time()*1000);h.start('server');reconnect_players(h,list(h.players))
    h.console(['tick rate 20'] if hold_world else ['tick unfreeze','tick rate 20'],lifecycle=True)
    value=h.wait(h.evidence,'Current native recovery reader',60);h.save_checkpoint();after=native_process(h)
    require((before['hostJvmPid'],before['startTicks'])!=(after['hostJvmPid'],after['startTicks']),'Actual native JVM was not replaced')
    require(all(read(h.root/'clients'/p/'process.json')==old for p,old in clients.items()),'Compatible connected clients were replaced')
    return {'before':before,'after':after,'sameClients':True,'reader':value}

def learned_seed(h):
    h.env.update(A06_HARVEST_ENABLED='false',A06_PICKUP_ENABLED='false',A05_DEPOSIT_ENABLED='false',A06_RECEIPT_PREFIX_ENABLED='false')
    h.bootstrap([LOG,BIRCH])
    require(all(any(a.get('item')==item and a.get('status')=='PASS' for a in h.admissions) for item in (LOG,BIRCH)),'Both genuine log admissions are required')
    h.compose('up','-d','ollama');h.command('PlayerA','/aivillage kernel inference true','admission=true')
    h.reset({},destination=DST,table=False);h.crops([[73,208,73]])
    calls=h.model_calls();run=h.submit(WHEAT,1,minimum=[73,208,73],maximum=[73,208,73],destination=DST,acquire=True)
    view=h.finish_run(run,admitted=True)
    require(h.model_calls()['generationCalls']>calls['generationCalls'],'Wheat method was not genuinely generated')
    require(all(sum(r['wheat'] for r in view.get('receipts',[]) if r['stage']==stage)==1 for stage in STAGES),'Genuine wheat admission lacks actual legacy H/P/D receipts')
    h.hashes['cognitivecraft:deliver_wheat']=view['marker']['artifactSha256'];h.admissions.append({'item':WHEAT,'run':run,'view':view,'status':'PASS','callsBefore':calls,'callsAfter':h.model_calls()})
    h.command('PlayerA','/aivillage kernel inference false','admission=false');h.compose('stop','ollama')
    h.reset({},table=False)
    if h.snapshot()['drops']:h.console(['kill @e[type=minecraft:item]'])
    require(not h.inventory(),'The admission seed must have empty actual actor custody')
    h.prepare_unrelated();h.save_checkpoint();h.stop('server')
    seed=h.root/'genuinely-admitted-seed-game';shutil.copytree(h.root/'server/game',seed,ignore=shutil.ignore_patterns('session.lock'))
    atomic(h.root/'independent-admissions.json',{'admissions':h.admissions,'artifacts':h.hashes,'nativeAdmissionsCountedAsA06':0,'worldHashes':manifest(seed/'world'),'unrelatedCompanion':h.companion})
    return seed

def one(h,seed,case):
    directory=h.root/'original-cases'/case['id'];directory.mkdir(parents=True,exist_ok=True)
    result={'originalCase':case,'status':'NOT_EXECUTED','nativeOriginalOwnerSettlements':0};h.case_stage=case['checkpoint']
    try:
        h.env.update(A06_HARVEST_ENABLED='true',A06_PICKUP_ENABLED='true',A05_DEPOSIT_ENABLED='true',A06_RECEIPT_PREFIX_ENABLED='true')
        h.restore(seed,'original-'+case['id']);dst=[case['destination'][k] for k in ('x','y','z')]
        if not (h.root/'a06-storage-mechanics.json').exists():
            mechanics=h.rpc('storage-contracts');require(mechanics['status']=='PASS_NATIVE_STORAGE_MECHANICS_ONLY','Native storage controls failed')
            atomic(h.root/'a06-storage-mechanics.json',mechanics)
        h.reset({},destination=dst,table=False);points=positions(case)
        if getattr(h,'a10_profile',None) in ('A06','A09'):
            from nonparticipant_fixture import protects
            protects(case);result['nonparticipantFixture']=h.current_nonparticipant_fixture
        h.crops(points) if case['item']==WHEAT else h.logs(points,item=case['item'])
        initial=physical(h,case);require(not initial['inventory'] and initial['destination']==0 and not initial['drops'],'Original fixture has foreign custody')
        h.save_checkpoint();token=str(uuid.uuid4());result['arm']=h.rpc('arm',stage=case['checkpoint'],point='RESULT_ACKNOWLEDGED',token=token,save=True)
        calls=h.model_calls();start=int(time.time()*1000);src=case['source']
        run=h.submit(case['item'],case['requested'],minimum=[src[k] for k in ('minX','minY','minZ')],maximum=[src[k] for k in ('maxX','maxY','maxZ')],destination=dst)
        result.update(run=run,initialCustody=initial)
        cut=h.wait(lambda:(v if (v:=read(h.root/'server/harvest-cut-reached.json')) and v['token']==token else None),'Actual original '+case['id']+' '+case['checkpoint']+' boundary',190)
        require(cut['stage']==case['checkpoint'] and cut['point']=='RESULT_ACKNOWLEDGED' and cut['commands']==[{'command':'tick freeze','result':1},{'command':'save-all flush','result':1}],'Original saved cut was not actually reached')
        stage=cut['stages'][case['checkpoint']];entry=stage['journal']['entries'][0];key=entry['intent']['key'];effects,credit=STAGES[case['checkpoint']]
        atomic(directory/'active-tick-samples.json',read(h.root/'server/runtime-a-ticks.json'))
        require(len(stage['journal']['entries'])==1 and key['ordinal']==effects,'Original effect ordinal differs')
        original=next(j for j in cut['runtime']['jobs']['jobs'] if j['id']==key['step']);attempt=original['attempts'][-1]
        require(original['state']=='ACTIVE' and attempt.get('terminal') is None,'Original owner was not live at the cut')
        require(original['request']['request']['arguments']['amount']['value']==case['requested'] and original['request']['request']['capability']==case['capability'],'Original request changed')
        require(attempt['executions'][0]['artifact']['sha256']==h.hashes[case['capability']['name']],'Original used a different admitted method')
        result.update(reached=cut,key=key,originalAtCut=original)
        before=native_process(h);clients={p:read(h.root/'clients'/p/'process.json') for p in h.players}
        h.stop('server',hard=True);h.wait(lambda:not same_native_alive(before),'Actual original JVM killed',25)
        killed=directory/'killed-world';shutil.copytree(h.root/'server/game/world',killed,ignore=shutil.ignore_patterns('session.lock'))
        saved_game=directory/'cut-game';shutil.copytree(h.root/'server/game',saved_game,ignore=shutil.ignore_patterns('session.lock'))
        proof=native_saved(killed,case['checkpoint'],case)
        clock_request=None
        if case['id']=='W6-003':
            from adoption_clock import request as request_clock
            clock_request=request_clock(h.root,case,proof)
        result.update(killedWorldHashes=manifest(killed),nativeSavedProof=proof,
                      firstRestart=restart_after_cut(h,before,clients,hold_world=clock_request is not None))
        h.wait(lambda:(v if (v:=h.stable_entry()) and list(v['reconciliation'].values())==['SAVED_COMPLETE'] else None),'Complete independently saved native cohort',65)
        current=h.wait(lambda:(j if (v:=h.run_view('PlayerA',run)) and (j:=v.get('responsibility')) and j['state']=='INTERRUPTED' and j['attempts'][-1]['uncertain'] else None),'Original interrupted owner before cancellation',45)
        custody=physical(h,case);require(custody['destination']==credit,'Original native delivery quantity differs')
        require(sum(custody['inventory'].values())==(1 if case['checkpoint']=='PICKUP' else 0),'Original actor custody differs')
        result['beforeCancellation']=current
        if case['id']=='W6-003':
            from adoption_clock import witnessed,release
            first=witnessed(h,clock_request,result['firstRestart']['after'])
            h.save_checkpoint();shutil.copytree(h.root/'server/game/world',directory/'adoption-first-world',ignore=shutil.ignore_patterns('session.lock'))
            result['adoptionClock']={'request':clock_request,'first':first,'firstWorldHashes':manifest(directory/'adoption-first-world')}
            result['adoptionRestart']=h.restart(hard=True,hold_world=True)
            h.wait(lambda:(v if (v:=h.stable_entry()) and list(v['reconciliation'].values())==['SAVED_COMPLETE'] else None),'Original P survives adoption re-kill',60)
            second=witnessed(h,clock_request,result['adoptionRestart']['after'])
            h.save_checkpoint();shutil.copytree(h.root/'server/game/world',directory/'adoption-second-world',ignore=shutil.ignore_patterns('session.lock'))
            result['adoptionClock'].update(second=second,secondWorldHashes=manifest(directory/'adoption-second-world'))
            result['adoptionClock']['released']=release(h)
            verification_token=h.rpc('arm',stage='PICKUP',point='VERIFICATION_ACKNOWLEDGED',token=str(uuid.uuid4()),save=True)['token']
        else:verification_token=None
        result['cancellation']=h.command('PlayerA','/aivillage kernel job-cancel '+key['step'],'job='+key['step']+' cancellation=true')
        if verification_token:
            vcut=h.wait(lambda:(v if (v:=read(h.root/'server/harvest-cut-reached.json')) and v['token']==verification_token else None),'Actual saved verification before original Job settlement',45)
            prior=native_process(h);h.stop('server',hard=True);h.wait(lambda:not same_native_alive(prior),'Actual settlement JVM killed',25)
            shutil.copytree(h.root/'server/game/world',directory/'verification-killed-world',ignore=shutil.ignore_patterns('session.lock'))
            result['verificationCut']=vcut;result['settlementCutRestart']=restart_after_cut(h,prior,clients)
        job=h.wait(lambda:(j if (v:=h.run_view('PlayerA',run)) and (j:=v.get('responsibility')) and j['state']=='CANCELLED' and not j['attempts'][-1]['uncertain'] else None),'Terminal certain original owner',50)
        owner=settled(case,original,job,key);verified=h.wait(lambda:(v if (v:=h.stable_entry()) and v['journal']['entries'][0].get('verification') else None),'Acknowledged native verification',35)
        closed=h.wait(lambda:(v if (v:=h.evidence()) and not any(l['owner']['job']==key['step'] and l['state']=='ACTIVE' for l in v['runtime']['leases']['leases']) else None),'Original claims settled',40)
        result['cancelAgain']=h.command('PlayerA','/aivillage kernel job-cancel '+key['step'],'job='+key['step']+' cancellation=true')
        h.quiet(40);require(physical(h,case)==custody,'Cancellation changed native custody')
        perf_start=int(time.time()*1000);h.quiet(400)
        tick_data=h.wait(lambda:(v if (v:=read(h.root/'server/runtime-a-ticks.json')) and len([s for s in v['samples'] if s['time']>=perf_start])>=390 else None),'Representative recovered-world tick samples',15)
        atomic(directory/'tick-samples.json',tick_data)
        h.save_checkpoint();result['terminalRestart']=h.restart(hard=True)
        again=h.wait(lambda:(j if (v:=h.run_view('PlayerA',run)) and (j:=v.get('responsibility')) and j['state']=='CANCELLED' else None),'Original terminal owner survives another kill',45)
        h.quiet(40);require(again==job and physical(h,case)==custody and h.model_calls()==calls,'Restart changed the original owner, replayed effects or called the model')
        h.save_checkpoint();h.stop('server',hard=True)
        final=directory/'terminal-world';shutil.copytree(h.root/'server/game/world',final,ignore=shutil.ignore_patterns('session.lock'))
        final_proof=native_saved(final,case['checkpoint'],case);require({k:v for k,v in final_proof['native'].items() if k!='memberships'}=={k:v for k,v in proof['native'].items() if k!='memberships'},'Independent native custody changed after terminal restart')
        result.update(status='PASS_ORIGINAL_PENDING_INDEPENDENT_REVIEW',nativeOriginalOwnerSettlements=1,originalAfterSettlement=job,afterTerminalRestart=again,
                      owner=owner,verified=verified,claimsAfterSettlement=closed,finalCustody=custody,terminalWorldHashes=manifest(final),tickWindowStart=perf_start,
                      executionStartTime=start,modelCallsBefore=calls,modelCallsAfter=h.model_calls())
        atomic(directory/'result.json',result);return result
    except Exception as error:
        result.update(status='FAIL',reason=str(error) or type(error).__name__,trace=traceback.format_exc())
        for name,observe in (('lastEvidence',h.evidence),('lastSnapshot',h.snapshot)):
            try:result[name]=observe()
            except Exception as observation_error:result[name]={'unavailable':str(observation_error)}
        if 'run' in result:
            try:result['lastRun']=h.run_view('PlayerA',result['run'])
            except Exception as observation_error:result['lastRun']={'unavailable':str(observation_error)}
        atomic(directory/'result.json',result);raise
    finally:
        if case['id']=='W6-003':
            from adoption_clock import cleanup
            cleanup(h.root)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,required=True);p.add_argument('--seed-archive',type=Path);args=p.parse_args()
    root=args.root.resolve();root.mkdir(parents=True,exist_ok=True);h=None
    result={'schema':1,'status':'NOT_EXECUTED','requiredOriginalCases':12,'originalOwnerSettlements':0,'a06Qualified':False,'cases':[]}
    try:
        cases=originals(ROOT/'e2e/parallel_a/a06_original_cases.json');result['plannedCases']=[c['id'] for c in cases];h=RecoveryHarness(root);h.activated=0
        if args.seed_archive:
            from recovery_seed import start as retained_seed
            seed=retained_seed(h,args.seed_archive.resolve())
        else:seed=learned_seed(h)
        for case in cases:
            try:value=one(h,seed,case);result['cases'].append(value)
            except Exception as error:result['cases'].append(read(root/'original-cases'/case['id']/'result.json') or {'originalCase':case,'status':'FAIL','reason':str(error)})
            atomic(root/'a06-original-results.json',result)
            current=result['cases'][-1]
            print('A06_ORIGINAL_PROGRESS '+json.dumps({'id':case['id'],'status':current['status'],'reason':current.get('reason'),
                  'failureSite':current.get('trace','').splitlines()[-4:],'actualCutReached':bool(current.get('reached')),
                  'completed':len(result['cases']),'required':12}),flush=True)
        originals_pass=len(result['cases'])==12 and all(c['status']=='PASS_ORIGINAL_PENDING_INDEPENDENT_REVIEW' for c in result['cases'])
        result.update(status='PASS_ALL_TWELVE_PENDING_INDEPENDENT_REVIEW' if originals_pass else 'FAIL_REQUIRED_ORIGINALS',
                      originalStatus='PASS_ALL_TWELVE_PENDING_INDEPENDENT_REVIEW' if originals_pass else 'FAIL_REQUIRED_ORIGINALS',
                      originalOwnerSettlements=sum(c.get('nativeOriginalOwnerSettlements',0) for c in result['cases']))
        from recovery_controls import campaign
        result['controls']=campaign(h,seed,cases)
        require(originals_pass,'One or more original cases did not pass; inspect every retained case result')
    except Exception as error:
        result.update(status='FAIL',reason=str(error),trace=traceback.format_exc());raise
    finally:
        if h is not None:h.finish()
        atomic(root/'a06-original-results.json',result)
        print('A06_ORIGINAL_TWELVE_NATIVE '+json.dumps({'status':result['status'],'passed':sum(v.get('nativeOriginalOwnerSettlements',0) for v in result['cases']),
              'failures':[{'id':v['originalCase']['id'],'reason':v.get('reason')} for v in result['cases'] if v.get('status')=='FAIL']}),flush=True)

if __name__=='__main__':main()
