"""A05 connected native deposit and independently witnessed participant-save cuts."""
from __future__ import annotations
import argparse,hashlib,json,shutil,subprocess,sys,time,traceback,uuid
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from activity import ActivityHarness
from baseline import native_process,same_native_alive,percentiles
from suite import ROOT,atomic,read,require,reconnect_players,DENIED_PATTERN
from generalization_support import LOG,DST,at,coord
from generalization import receipts

LIMITS={'tickP95Ms':20,'tickP99Ms':40,'captureP99Ms':2,'ackMaxMs':30000,'ticks':400,
        'declaration':'20/40ms tick and 2ms capture-p99 targets retained; a single real effect measures cost but cannot qualify the required representative cost window'}


class DepositHarness(ActivityHarness):
    def compose(self,*args):
        result=subprocess.run(['docker','compose','-p',self.project,'-f',str(ROOT/'e2e/compose.yml'),
            '-f',str(ROOT/'e2e/parallel_a/compose-deposits.yml'),*args],cwd=ROOT,env=self.env,text=True,capture_output=True)
        with (self.root/'docker.log').open('a') as out:out.write(result.stdout+result.stderr)
        if result.returncode:raise RuntimeError('A05 Compose: '+result.stderr[-2000:])
        return result

    def evidence(self):
        value=read(self.root/'server/deposit-snapshot.json')
        return value if value and value['time']>=self.activated else None

    def rpc(self,op,**values):
        ready=self.wait(lambda:(v if (v:=read(self.root/'server/deposit-ready.json')) and v['process']==self.evidence()['process'] else None),
            'Current native A05 observer',45)
        request={'id':str(uuid.uuid4()),'process':ready['process'],'op':op,**values}
        atomic(self.root/'server/deposit-request.json',request)
        response=self.wait(lambda:(v if (v:=read(self.root/'server/deposit-response.json')) and v['id']==request['id'] else None),'A05 '+op+' native reply',45)
        atomic(self.root/'deposit-rpc'/(request['id']+'.json'),{'request':request,'response':response})
        require(response['status']=='PASS','Native A05 observation failed: '+json.dumps(response))
        return response['value']

    def restart(self,hard=False):
        before=native_process(self);clients={p:read(self.root/'clients'/p/'process.json') for p in self.players}
        self.stop('server',hard=hard)
        self.wait(lambda:not same_native_alive(before),'Actual old A05 JVM exited',25)
        for p in self.players:self.wait(lambda:not self.action(p,'snapshot')['play'],'A05 actual disconnect '+p,45)
        self.activated=int(time.time()*1000);self.start('server');reconnect_players(self,list(self.players))
        self.console(['tick unfreeze','tick rate 20'],lifecycle=True)
        evidence=self.wait(self.evidence,'Current A05 durable evidence reader after JVM replacement',60)
        after=native_process(self)
        require((before['hostJvmPid'],before['startTicks'])!=(after['hostJvmPid'],after['startTicks']),'A05 did not replace native JVM')
        require(all(read(self.root/'clients'/p/'process.json')==old for p,old in clients.items()),'A05 replaced compatible connected clients')
        return {'before':before,'after':after,'sameClients':True,'reader':evidence}

    def restore(self,seed,name):
        self.actions_active=False;self.stop('server')
        for p in self.players:self.wait(lambda:not self.action(p,'snapshot')['play'],'Independent cohort disconnect '+p,45)
        game=self.root/'server/game';saved=self.root/('retained-before-'+name)
        game.rename(saved);shutil.copytree(seed,game,ignore=shutil.ignore_patterns('session.lock'))
        source=manifest(seed/'world');require(manifest(game/'world')==source,'A05 whole-world cohort restoration changed bytes')
        from continuation import bind_port
        port_override=bind_port(game/'server.properties',self.port)
        self.event('a05-independent-whole-world-cohort',{'id':name,'input':str(seed.relative_to(self.root)),'files':source,'configurationOverride':port_override})
        self.activated=int(time.time()*1000);self.start('server');reconnect_players(self,list(self.players))
        self.console(['tick unfreeze','tick rate 20'],lifecycle=True)
        self.wait(self.evidence,'A05 restored native cohort reader',60)


def manifest(world):
    return {p.relative_to(world).as_posix():hashlib.file_digest(p.open('rb'),'sha256').hexdigest()
            for p in sorted(world.rglob('*')) if p.is_file() and p.name!='session.lock'}


def actual_stock(h):
    state=h.snapshot();actor=next(a for a in state['actors'] if a['uuid']==h.entities['PlayerA'])
    return {'destination':h.container(DST,LOG),'actor':actor,'source':h.block(at(6,2)),
            'snapshotTime':state['time'],'gameTick':state['gameTick']}


def stable_entry(h):
    value=h.evidence()
    return value if value and not value['pending'] and len(value['journal']['entries'])==1 else None


def positive(h,result):
    h.family='A05-live-deposit';before=actual_stock(h);calls=h.model_calls()
    run=h.submit(LOG,1,minimum=at(6,2),maximum=at(6,2));view=h.finish_run(run)
    native=h.wait(lambda:(v if (v:=stable_entry(h)) and v['journal']['entries'][0].get('result') else None),'Exact actual deposit result acknowledged',45)
    entry=native['journal']['entries'][0];token=next(iter(native['reconciliation']))
    require(all(receipts(view,stage,LOG)==1 for stage in ('HARVEST','PICKUP','DEPOSIT')),'A05 lacks actual H/P/D chain')
    require(entry['result']['receipt'] in view['resourceReceipts'],'Protocol result does not retain the original gateway receipt')
    h.wait(lambda:actual_stock(h)['destination']==1 and not actual_stock(h)['actor']['inventory'],'Independent chest1 and empty actor custody',30)
    connected_count=h.menu_count('PlayerA',DST,LOG);require(connected_count==1,'Connected native menu lacks the actual log')
    after=actual_stock(h);require(h.model_calls()==calls,'A05 reuse called the model')
    require(native['reconciliation'][token]=='UNPROVEN','Live observation was treated as a native saved witness')
    original=view['responsibility'];key=entry['intent']['key']
    require(key['step']==original['id'] and key['attempt']==original['attempts'][-1]['id'] and key['generation']==original['generation'],'Native intent changed its original Jobs owner')
    saved=h.save_checkpoint();before_kill=native_process(h)
    h.stop('server',hard=True);h.wait(lambda:not same_native_alive(before_kill),'Positive saved actual JVM kill',25)
    saved_game=h.root/'positive-saved-game';shutil.copytree(h.root/'server/game',saved_game,ignore=shutil.ignore_patterns('session.lock'))
    for p in h.players:h.wait(lambda:not h.action(p,'snapshot')['play'],'Saved positive disconnect '+p,45)
    h.activated=int(time.time()*1000);h.start('server');reconnect_players(h,list(h.players));h.console(['tick unfreeze','tick rate 20'],lifecycle=True)
    restored=h.wait(lambda:(v if (v:=stable_entry(h)) and v['reconciliation'].get(token)=='SAVED_COMPLETE' else None),'Positive actual independently saved participants agree',60)
    require(restored['journal']['entries'][0]['intent']==entry['intent'] and restored['journal']['entries'][0]['result']==entry['result'],'Restart changed native original intent/result')
    verify=h.rpc('verify',token=token)
    verified=h.wait(lambda:(v if (v:=stable_entry(h)) and v['journal']['entries'][0].get('verification') else None),'Saved positive verification durably acknowledged',45)
    require(not verified['protectedJobs'] and verify['classification']=='SAVED_COMPLETE','Unacknowledged or ambiguous evidence released exclusion')
    restart=h.restart(hard=True);again=h.wait(stable_entry,'Repeated positive adoption',45)
    require(again['journal']['entries'][0]==verified['journal']['entries'][0] and not again['protectedJobs'],'Repeated adoption changed durable proof')
    h.quiet(40);require(actual_stock(h)['destination']==1 and not actual_stock(h)['actor']['inventory'],'Repeated adoption changed actual custody')
    repeated_count=h.menu_count('PlayerA',DST,LOG);require(repeated_count==1,'Repeated saved adoption changed connected client stock')
    metrics=native['metrics'];require(len(metrics['acknowledgementNanos'])==2,'Measure both real intent/result forced publications')
    measurement={'limits':LIMITS,'prepare':percentiles(metrics['beginNanos']),'poll':percentiles(metrics['pollNanos']),'ack':percentiles(metrics['acknowledgementNanos']),
                 'actualNativeDeposits':1,'warning':'Small actual sample; no 64-sample or universal latency claim'}
    atomic(h.root/'a05-live-cost.json',{'raw':metrics,'measurement':measurement})
    measurement['qualification']='NOT_QUALIFIED_SMALL_NATIVE_SAMPLE';measurement['captureTargetP99Ms']=LIMITS['captureP99Ms']
    require(measurement['ack']['max']<=LIMITS['ackMaxMs'],'A05 journal acknowledgement exceeds original bounded publication deadline')
    record={'id':'saved-positive-repeated-adoption','status':'PASS','run':run,'job':original,'before':before,'after':after,'live':native,'save':saved,'restored':restored,'verify':verify,'verified':verified,'repeatedRestart':restart,'cost':measurement,'connectedClientCounts':{'beforeSave':connected_count,'afterRepeatedRestart':repeated_count}}
    result['cases'].append(record);atomic(h.root/'a05-results.json',result)
    h.save_checkpoint();h.stop('server');verified_seed=h.root/'verified-positive-game'
    shutil.copytree(h.root/'server/game',verified_seed,ignore=shutil.ignore_patterns('session.lock'))
    return saved_game,verified_seed,entry,token


def cut(h,seed,result,name,point,expected,**options):
    h.restore(seed,name);h.family='A05-'+name;folder=h.root/name;folder.mkdir()
    arm_token=str(uuid.uuid4());h.rpc('arm',point=point,token=arm_token,**options)
    initial=actual_stock(h);run=h.submit(LOG,1,minimum=at(6,2),maximum=at(6,2))
    reached=h.wait(lambda:(v if (v:=read(h.root/'server/deposit-cut-reached.json')) and v['token']==arm_token else None),'Actually witnessed A05 '+point,150)
    require(reached['point']==point and reached['process']==h.evidence()['process'],'Wrong A05 cut or stale process marker')
    before=native_process(h);clients={p:read(h.root/'clients'/p/'process.json') for p in h.players}
    atomic(folder/'before-kill.json',{'marker':reached,'nativeProcess':before,'clients':clients,'initial':initial,'run':run,'lastIndependentSnapshot':h.snapshot()})
    h.stop('server',hard=True);h.wait(lambda:not same_native_alive(before),'Actual interrupted deposit JVM exited',25)
    shutil.copytree(h.root/'server/game/world',folder/'killed-world',ignore=shutil.ignore_patterns('session.lock'))
    killed=manifest(folder/'killed-world')
    for p in h.players:h.wait(lambda:not h.action(p,'snapshot')['play'],'Cut native disconnect '+p,45)
    h.actions_active=False;h.activated=int(time.time()*1000);h.start('server');reconnect_players(h,list(h.players));h.console(['tick unfreeze','tick rate 20'],lifecycle=True)
    value=h.wait(stable_entry,'Preserved native intent after actual cut',60);token=next(iter(value['reconciliation']))
    require(value['reconciliation'][token] in expected,'Unexpected native classification at '+name+': '+json.dumps(value))
    require(all(read(h.root/'clients'/p/'process.json')==old for p,old in clients.items()),'Crash cut replaced the observer clients')
    calls=h.model_calls();physical=actual_stock(h);h.quiet(40);later=actual_stock(h)
    require(physical['destination']==later['destination'] and physical['actor']['inventory']==later['actor']['inventory'] and calls==h.model_calls(),'A05 repeated adoption replayed a native effect')
    record={'id':name,'status':'PASS','run':run,'actuallyWitnessed':reached,'expectedClassification':sorted(expected),'restoredEvidence':value,
            'nativeBefore':before,'nativeAfter':native_process(h),'killedWorldFiles':killed,'physicalAfter':physical,'repeatedAdoption':later,
            'newPhysicalExecutionAfterRestart':False,'automaticOriginalJobSettlement':False}
    atomic(folder/'result.json',record);result['cases'].append(record);atomic(h.root/'a05-results.json',result)


def negative(h,saved_game,result,token,mode):
    h.restore(saved_game,'negative-'+mode);h.family='A05-negative-'+mode
    before=h.wait(stable_entry,'Saved positive evidence before adversarial control',45)
    require(before['reconciliation'][token]=='SAVED_COMPLETE' and not before['journal']['entries'][0].get('verification'),'Negative fixture must retain unverified saved evidence')
    if mode=='components':
        mutation=h.rpc('component-roundtrip');require(mutation['count']==1 and mutation['componentsRestored'],'Native component/count round trip did not happen')
    elif mode=='equal-count-swap':
        h.console(['item replace block '+coord(DST)+' container.0 with minecraft:oak_log 1'])
        mutation={'commands':'actual equal-count slot replacement','sameDestinationCount':1}
    elif mode=='replacement':
        h.console(['setblock '+coord(DST)+' minecraft:air','setblock '+coord(DST)+' minecraft:chest',
                   'item replace block '+coord(DST)+' container.0 with minecraft:oak_log 1'])
        mutation={'commands':'same-coordinate actual chest replacement and equal-count stock'}
    else:
        for p in h.players:
            h.command(p,'/gamemode spectator','Spectator|spectator');h.command(p,'/tp 10000.5 220 10000.5','Teleported')
        absent=h.wait(lambda:(v if (v:=stable_entry(h)) and v['reconciliation'][token]=='WAIT_WORLD' else None),'Actual actor/chest native unload',130)
        result['cases'].append({'id':'unloaded-participants','status':'PASS','before':before,'after':absent});atomic(h.root/'a05-results.json',result);return
    h.wait(lambda:(v if (v:=stable_entry(h)) and v['reconciliation'][token]=='UNPROVEN' else None),'Changed native participant refuses old proof',45)
    h.save_checkpoint();restart=h.restart(hard=True)
    after=h.wait(stable_entry,'Changed saved participant after restart',45)
    require(after['reconciliation'][token]=='UNPROVEN' and after['protectedJobs'],'Equal counts or changed incarnation regained old authority')
    refusal=h.rpc('verify',token=token);require(refusal['code']=='STALE','Invalid participant evidence closed an exclusion')
    result['cases'].append({'id':'negative-'+mode,'status':'PASS','before':before,'mutation':mutation,'restart':restart,'after':after,'verify':refusal})
    atomic(h.root/'a05-results.json',result)



def storage_controls(h,verified_seed,original_entry,result):
    from deposit_fixture import pressure_journal
    for mode in ('full-quota','corrupt-current'):
        h.env['A05_DEPOSIT_ENABLED']='true';h.restore(verified_seed,'storage-'+mode);h.family='A05-storage-'+mode
        original=h.wait(stable_entry,'Verified effect before isolated storage control',45)
        require(original['journal']['entries'][0].get('verification') is not None and not original['protectedJobs'],'Storage control is confounded by an old unresolved effect')
        h.logs([at(7,2)]);h.save_checkpoint();h.stop('server')
        for p in h.players:h.wait(lambda:not h.action(p,'snapshot')['play'],'Storage-control disconnect '+p,45)
        folder=h.root/('storage-'+mode);folder.mkdir();current=h.root/'server/game/world/data/cognitivecraft/deposit-evidence/v1/state.jsonl';previous=current.with_name('state.prev.jsonl')
        before=current.read_bytes();(folder/'original-current.jsonl').write_bytes(before)
        if mode=='full-quota':
            changed,declaration=pressure_journal(before,original_entry['intent']);previous.write_bytes(before)
        else:
            changed=bytearray(before);changed[-3]^=1;changed=bytes(changed);declaration={'kind':'CORRUPT_CURRENT_NATIVE_REFUSAL','allWriterActivationDisabled':True}
            h.env['A05_DEPOSIT_ENABLED']='false'
        current.write_bytes(changed);frozen_hash=hashlib.sha256(changed).hexdigest()
        atomic(folder/'fixture-declaration.json',{'mode':mode,'metadata':declaration,'originalSha256':hashlib.sha256(before).hexdigest(),'changedSha256':frozen_hash,
            'nativeSourceAddition':at(7,2),'syntheticMetadataIsNotNativeProvenance':True})
        h.activated=int(time.time()*1000);h.start('server');reconnect_players(h,list(h.players));h.console(['tick unfreeze','tick rate 20'],lifecycle=True)
        native=h.wait(h.evidence,'Required native journal reader under '+mode,60)
        require(native['fenced']==(mode=='corrupt-current'),'Journal pressure/corruption boundary not actually isolated')
        if mode=='full-quota':require(len(native['journal']['entries'])==64,'Native reader did not accept exact bounded synthetic pressure')
        before_stock=actual_stock(h);calls=h.model_calls();request=h.request_run(LOG,1,minimum=at(7,2),maximum=at(7,2));run=request['run']
        if mode=='full-quota':require(request['accepted'],'Quota control must reach the actual imminent deposit admission')
        if request['accepted']:
            h.actions_active=True;view=h.finish_run(run,success=False)
        else:view={'actualIntakeRefusal':request,'resourceReceipts':[],'physicalExecution':'NOT_STARTED'}
        h.quiet(20);after=actual_stock(h)
        require(receipts(view,'DEPOSIT',LOG)==0 and before_stock['destination']==after['destination']==1,'Storage refusal changed native chest custody')
        require(h.model_calls()==calls and hashlib.sha256(current.read_bytes()).hexdigest()==frozen_hash,'Storage refusal rewrote evidence or invoked a model')
        if mode=='full-quota':
            require(receipts(view,'HARVEST',LOG)==receipts(view,'PICKUP',LOG)==1 and after['actor']['inventory'].get(LOG,0)==1,'Quota refusal hid the pre-deposit actor custody')
        else:
            require(not after['actor']['inventory'] and LOG in h.block(at(7,2)) and not view['resourceReceipts'],'Corrupt required history allowed a physical primitive')
        record={'id':'native-storage-'+mode,'status':'PASS','fixture':declaration,'reader':native,'beforeStock':before_stock,'afterStock':after,'run':run,'view':view,
                'journalBytesUnchanged':True,'nativeProcess':native_process(h),'activationFlag':h.env['A05_DEPOSIT_ENABLED']}
        atomic(folder/'result.json',record);result['cases'].append(record);atomic(h.root/'a05-results.json',result)
    h.env['A05_DEPOSIT_ENABLED']='true'


def cost_windows(h,verified_seed,result):
    from deposit_fixture import tick_window
    windows=[]
    for mode,enabled in (('baseline',False),('candidate',True)):
        h.env['A05_DEPOSIT_ENABLED']='true' if enabled else 'false';h.restore(verified_seed,'cost-'+mode);h.family='A05-cost-'+mode
        h.quiet(100)
        initial=h.wait(lambda:(v if (v:=read(h.root/'server/a05-tick-samples.json')) and v['time']>=h.activated else None),'Current independent A05 raw timer',30)
        begin=h.rpc('window-marker');begin['observerProcess']=initial['process'];before=native_process(h)
        h.wait(lambda:(v if (v:=h.evidence()) and v['gameTick']>=begin['gameTick']+LIMITS['ticks'] else None),'Complete400-tick A05 game-clock window',45)
        def complete():
            raw=read(h.root/'server/a05-tick-samples.json');selected=tick_window(raw,begin,LIMITS['ticks'])
            return {'raw':raw,'selected':selected} if selected else None
        value=h.wait(complete,'Every raw native A05 interval sample retained',15)
        sample=percentiles([r['nanos'] for r in value['selected']]);record={'configuration':mode,'newEnvelopeActivation':enabled,'requiredReaderActive':True,'begin':begin,
            'nativeProcess':before,'metric':value['raw']['metric'],'tick':sample,'limits':LIMITS,'rawSamples':value['selected'],'nativeEffects':0}
        atomic(h.root/('a05-'+mode+'-raw-window.json'),record);windows.append(record)
        require(sample['p95']<=LIMITS['tickP95Ms'] and sample['p99']<=LIMITS['tickP99Ms'],'Frozen A05 '+mode+' tick window failed: '+json.dumps(sample))
    result['cases'].append({'id':'required-reader-baseline-and-envelope-candidate-400-tick-cost','status':'PASS','windows':windows,
                           'actualPublicationCost':result['cases'][0]['cost'],'captureDistribution':'Small actual primitive sample reported separately; no synthetic native-effect or large-sample claim'})
    atomic(h.root/'a05-results.json',result)


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--root',required=True,type=Path);parser.add_argument('--seed-archive',type=Path);args=parser.parse_args();args.root=args.root.resolve();args.root.mkdir(parents=True,exist_ok=True)
    h=None;result={'stage':'A05','status':'NOT_EXECUTED','cases':[],'limits':LIMITS,'scope':'one actual default-component log from actual bound actor to ordinary single chest; no physical replay or original-owner settlement'}
    try:
        h=DepositHarness(args.root);h.activated=0
        if args.seed_archive:
            from deposit_seed import start
            seed,result['seedContinuation']=start(h,args.seed_archive.resolve())
        else:
            h.env['A05_DEPOSIT_ENABLED']='false';h.bootstrap([LOG])
            require('cognitivecraft:deliver_oak_logs' in h.hashes,'Real baseline method admission required before activating the new envelope')
            h.family='A05-before-activation';h.reset({},table=False);h.logs([at(6,2)]);h.save_checkpoint();h.stop('server')
            for p in h.players:h.wait(lambda:not h.action(p,'snapshot')['play'],'Initial activation disconnect '+p,45)
            seed=args.root/'pre-effect-seed-game';shutil.copytree(h.root/'server/game',seed,ignore=shutil.ignore_patterns('session.lock'))
            h.env['A05_DEPOSIT_ENABLED']='true';h.activated=int(time.time()*1000);h.start('server');reconnect_players(h,list(h.players));h.console(['tick unfreeze','tick rate 20'],lifecycle=True)
            h.wait(h.evidence,'Opt-in native evidence reader activated',60)
        saved,verified_seed,entry,token=positive(h,result)
        for mode in ('equal-count-swap','components','replacement','unloaded'):
            negative(h,saved,result,token,mode)
        for name,point,expected,options in (
            ('unsaved-before-mutation','BEFORE_MUTATION',{'UNPROVEN','TORN_PARTICIPANTS'},{}),
            ('unsaved-after-mutation','AFTER_MUTATION',{'UNPROVEN','TORN_PARTICIPANTS'},{}),
            ('unsaved-after-journal','RESULT_ACKNOWLEDGED',{'UNPROVEN','JOURNAL_AHEAD_OF_PARTICIPANTS','TORN_PARTICIPANTS'},{}),
            ('saved-prepared-no-effect','PREPARED_MARKED',{'PREPARED_NO_EFFECT'},{'savePrepared':True}),
            ('journal-ahead-saved-prepared','RESULT_ACKNOWLEDGED',{'JOURNAL_AHEAD_OF_PARTICIPANTS'},{'saveBeforeMutation':True}),
            ('actor-saved-chest-prepared','RESULT_ACKNOWLEDGED',{'TORN_PARTICIPANTS'},{'saveBeforeMutation':True,'saveParticipant':'ACTOR'}),
            ('chest-saved-actor-prepared','RESULT_ACKNOWLEDGED',{'TORN_PARTICIPANTS'},{'saveBeforeMutation':True,'saveParticipant':'CHEST'}),
        ):cut(h,seed,result,name,point,expected,**options)
        storage_controls(h,verified_seed,entry,result)
        cost_windows(h,verified_seed,result)
        result['status']='PASS_NATIVE_GATES_PENDING_INDEPENDENT_REVIEW';result['remaining']=['independent full artifact, physical saved-participant and raw cost review']
    except Exception as error:
        result.update(status='FAIL',reason=str(error),trace=traceback.format_exc())
        if h is not None:result['lastEvidence']=h.evidence();result['lastSnapshot']=h.snapshot()
        raise
    finally:
        if h is not None:h.finish()
        atomic(args.root/'a05-results.json',result)
        print('PARALLEL_A_A05_RESULT '+json.dumps({'status':result['status'],'reason':result.get('reason'),'completed':[v['id'] for v in result['cases']]}),flush=True)


if __name__=='__main__':main()
