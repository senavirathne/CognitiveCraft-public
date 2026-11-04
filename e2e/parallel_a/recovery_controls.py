"""Native cancellation, ambiguous envelopes, remaining demand and representative cost controls."""
import copy,json,shutil,time,traceback,uuid
from pathlib import Path
from suite import atomic,read,require,DENIED_PATTERN
from generalization_support import LOG,BIRCH,coord
from generalization import receipts
from baseline import native_process,same_native_alive,percentiles
from deposits import manifest
from recovery_cases import positions
from recovery_fixture import native_saved,prepared_deposit,source_row
from review_receipt_prefix import journal

REQUIRED=('source-replacement-HARVEST','source-replacement-PICKUP','source-replacement-DEPOSIT',
          'unrelated-native-progress','unsaved-pickup-containment','two-unit-prepared-no-effect',
          'two-unit-delivered-remaining-one','expired-original-accounting','representative-active-cost')

def physical(h,case):
    from recovery_native import physical as observe
    return observe(h,case)

def restart(h,before,clients):
    from recovery_native import restart_after_cut
    return restart_after_cut(h,before,clients)

def job(h,run):
    value=h.run_view('PlayerA',run);return value.get('responsibility') if value else None

def classification(h,allowed,description):
    return h.wait(lambda:(v if (v:=h.stable_entry()) and len(v['reconciliation'])==1 and next(iter(v['reconciliation'].values())) in allowed else None),description,65)

def retain(h,directory,name='terminal-world'):
    h.save_checkpoint();h.stop('server',hard=True);world=directory/name
    shutil.copytree(h.root/'server/game/world',world,ignore=shutil.ignore_patterns('session.lock'))
    return manifest(world)

def original_control(h,seed,template,name,point,save=True):
    case=copy.deepcopy(template);case.update(id=name,requested=2);directory=h.root/'controls'/name;directory.mkdir(parents=True,exist_ok=True)
    h.case_stage=case['checkpoint'];h.restore(seed,name);dst=[case['destination'][k] for k in ('x','y','z')]
    h.reset({},destination=dst,table=False);h.logs(positions(case),item=case['item']);h.save_checkpoint()
    token=str(uuid.uuid4());arm=h.rpc('arm',stage=case['checkpoint'],point=point,token=token,save=save);calls=h.model_calls();src=case['source']
    run=h.submit(case['item'],2,minimum=[src[k] for k in ('minX','minY','minZ')],maximum=[src[k] for k in ('maxX','maxY','maxZ')],destination=dst)
    cut=h.wait(lambda:(v if (v:=read(h.root/'server/harvest-cut-reached.json')) and v['token']==token else None),'Actual control boundary '+name,190)
    require(cut['stage']==case['checkpoint'] and cut['point']==point and cut['commands']==[{'command':'tick freeze','result':1}]+([{'command':'save-all flush','result':1}] if save else []),'Control missed its actual native boundary')
    entry=cut['stages'][case['checkpoint']]['journal']['entries'][0];key=entry['intent']['key'];original=next(j for j in cut['runtime']['jobs']['jobs'] if j['id']==key['step'])
    require(original['state']=='ACTIVE' and original['attempts'][-1].get('terminal') is None,'Control owner was not active at actual kill')
    before=native_process(h);clients={p:read(h.root/'clients'/p/'process.json') for p in h.players};h.stop('server',hard=True)
    h.wait(lambda:not same_native_alive(before),'Actual control JVM killed',25)
    shutil.copytree(h.root/'server/game/world',directory/'killed-world',ignore=shutil.ignore_patterns('session.lock'))
    value={'id':name,'originalCase':case,'arm':arm,'reached':cut,'key':key,'run':run,'originalAtCut':original,'modelCallsBefore':calls,
           'killedWorldHashes':manifest(directory/'killed-world'),'restart':restart(h,before,clients)}
    atomic(directory/'started.json',value);return directory,case,value

def torn(h,case,unrelated=False):
    name='source-replacement-'+case['checkpoint'];directory=h.root/'controls'/name;directory.mkdir(parents=True,exist_ok=True)
    original=read(h.root/'original-cases'/case['id']/'result.json');key=original['key'];run=original['run']
    h.case_stage=case['checkpoint'];h.restore(h.root/'original-cases'/case['id']/'cut-game',name)
    source=original['nativeSavedProof']['harvest']['result']['source'];point=[source[k] for k in ('x','y','z')]
    h.console(['setblock '+coord(point)+' '+case['item'],'setblock '+coord(point)+' minecraft:air'])
    h.save_checkpoint();restarted=h.restart(hard=True);h.save_checkpoint()
    refused=classification(h,{'UNPROVEN','TORN_SAVED_COHORT','TORN_PARTICIPANTS'},'Equal-state source replacement stays unproved')
    calls=h.model_calls();h.command('PlayerA','/aivillage kernel job-cancel '+key['step'],'job='+key['step']+' cancellation=true')
    h.quiet(80);blocked=job(h,run);require(blocked['state']!='CANCELLED' and blocked['attempts'][-1]['uncertain'] and blocked['attempts'][-1]['id']==key['attempt'],'Torn source settled the original owner')
    require(not refused['journal']['entries'][0].get('verification'),'Torn source received positive verification')
    current=physical(h,case);h.quiet(40);require(physical(h,case)==current,'Torn original replayed effects')
    value={'id':name,'status':'PASS_REFUSED_TORN_ORIGINAL','originalCase':case,'originalKey':key,'originalJob':blocked,'reconciliation':refused,
           'restart':restarted,'expectedSource':source,'custody':current,'modelCallsBefore':calls}
    if unrelated:
        h.actions_active=False;h.command('PlayerA','/gamemode spectator','Spectator|spectator');h.command('PlayerA','/tp 329.5 208 69.5','Teleported')
        h.command('PlayerA','/gamemode survival','Survival|survival')
        h.wait(lambda:any(c['availability']=='LOADED' and c['actor']['citizenId']==h.companion[0] for c in h.snapshot()['knownPlayers']['PlayerA']['citizens']['citizens']),'Pre-existing independent actor actually loaded',60)
        primary=h.citizens['PlayerA'],h.entities['PlayerA']
        try:
            h.citizens['PlayerA'],h.entities['PlayerA']=h.companion
            other_run=h.submit(LOG,1,minimum=h.other_source,maximum=h.other_source,destination=h.other_destination)
            other=h.finish_run(other_run);require(all(receipts(other,s,LOG)==1 for s in ('HARVEST','PICKUP','DEPOSIT')),'Unrelated work lacks genuine native H/P/D')
            require(h.container(h.other_destination,LOG)==1 and not h.inventory(),'Unrelated native custody differs')
        finally:h.citizens['PlayerA'],h.entities['PlayerA']=primary
        require(job(h,run)['attempts'][-1]['uncertain'] and job(h,run)['attempts'][-1]['credited']==blocked['attempts'][-1]['credited'],'Unrelated work altered the original accounting')
        value['unrelated']={'id':'unrelated-native-progress','status':'PASS_NATIVE_WHILE_ORIGINAL_AMBIGUOUS','run':other_run,'view':other,'originalBefore':blocked,'originalAfter':job(h,run),'destination':h.other_destination}
    require(h.model_calls()==calls,'Torn original or unrelated work called the model');value['modelCallsAfter']=h.model_calls()
    value['terminalWorldHashes']=retain(h,directory);actual=source_row(directory/'terminal-world',source)
    require((actual['incarnation'],actual['revision'])!=(source['incarnation'],source['revision']),'Raw source incarnation did not actually change')
    atomic(directory/'result.json',value);return value

def unsaved(h,seed,template):
    directory,case,value=original_control(h,seed,template,'unsaved-pickup-containment','AFTER_MUTATION',False)
    _,rows=journal(directory/'killed-world/data/cognitivecraft/pickup-evidence/v1/state.jsonl')
    require(len(rows)==1 and rows[0].get('result') is None,'Unflushed pickup unexpectedly acquired a durable result')
    key=value['key'];h.command('PlayerA','/aivillage kernel job-cancel '+key['step'],'job='+key['step']+' cancellation=true');h.quiet(100)
    current=job(h,value['run']);require(current['attempts'][-1]['uncertain'] and current['attempts'][-1]['id']==key['attempt'] and current['state']!='CANCELLED','Unsaved effect was falsely settled')
    custody=physical(h,case);h.quiet(80);require(physical(h,case)==custody and h.model_calls()==value['modelCallsBefore'],'Unsaved envelope replayed effects or called the model')
    require('resume=false' in h.command('PlayerA','/aivillage kernel job-resume '+key['step'],'resume='),'Unsaved pickup authorized replacement harvesting')
    value.update(status='PASS_UNSAVED_CONTAINMENT_ONLY',originalJob=current,custody=custody,modelCallsAfter=h.model_calls(),terminalWorldHashes=retain(h,directory),settlementClaim=False,resumedCompletion=False)
    atomic(directory/'result.json',value);return value

def no_effect(h,seed,template):
    directory,case,value=original_control(h,seed,template,'two-unit-prepared-no-effect','PREPARED_MARKED')
    value['independentNative']=prepared_deposit(directory/'killed-world',case)
    value['restored']=classification(h,{'PREPARED_NO_EFFECT'},'Saved D preparation proves no D mutation')
    key=value['key'];require('resume=false' in h.command('PlayerA','/aivillage kernel job-resume '+key['step'],'resume='),'Undelivered actor custody authorized replacement harvest')
    h.command('PlayerA','/aivillage kernel job-cancel '+key['step'],'job='+key['step']+' cancellation=true')
    current=h.wait(lambda:(v if (v:=job(h,value['run'])) and v['state']=='CANCELLED' and not v['attempts'][-1]['uncertain'] else None),'Original HP settles with D proved absent',55)
    a=current['attempts'][-1];custody=physical(h,case)
    require(a['id']==key['attempt'] and current['generation']==key['generation'] and a['effects']==2 and a['credited']==current['fulfilled']==0,'Prepared D accounting differs from original HP')
    require(current['allowance']==value['originalAtCut']['allowance'] and sum(custody['inventory'].values())==1 and custody['destination']==0 and not custody['drops'],'Prepared D lost its existing actor custody')
    require(sum('minecraft:air' in b for b in custody['blocks'].values())==1,'No-effect recovery harvested replacement goods')
    h.quiet(80);require(physical(h,case)==custody and h.model_calls()==value['modelCallsBefore'],'No-effect settlement replayed work')
    value.update(status='PASS_PROVED_D_NO_EFFECT_EXISTING_HP_CUSTODY',originalJob=current,custody=custody,modelCallsAfter=h.model_calls(),terminalWorldHashes=retain(h,directory))
    atomic(directory/'result.json',value);return value

def remaining(h,seed,template):
    directory,case,value=original_control(h,seed,template,'two-unit-delivered-remaining-one','RESULT_ACKNOWLEDGED')
    value['independentNative']=native_saved(directory/'killed-world','DEPOSIT',case)
    value['restored']=classification(h,{'SAVED_COMPLETE'},'Saved original HPD is complete before explicit resume');key=value['key']
    value['foreignDenied']=h.command('PlayerB','/aivillage kernel job-resume '+key['step'],DENIED_PATTERN)
    value['resume']=h.command('PlayerA','/aivillage kernel job-resume '+key['step'],'resume=true')
    current=h.wait(lambda:(v if (v:=job(h,value['run'])) and v['state']=='SUCCEEDED' else None),'Same original Job delivers only the one remaining unit',210)
    require(current['id']==key['step'] and current['fulfilled']==2 and len(current['attempts'])==2 and current['generation']==key['generation']+1,'Remaining demand replaced the original Job or repeated its delivered unit')
    first,last=current['attempts'];require(first['id']==key['attempt'] and first['credited']==last['credited']==1 and first['effects']==last['effects']==3 and not first['uncertain'] and not last['uncertain'],'Both actual units require their own certain original accounting')
    require(last['bound']['request']['arguments']['amount']['value']==1 and current['allowance']==value['originalAtCut']['allowance'],'New attempt refreshed total demand or original allowance')
    require(all(n<=current['allowance']['maxima'][kind] for kind,n in current['usage'].items()),'Remaining attempt exceeded original budget')
    custody=physical(h,case);require(custody['destination']==2 and not custody['inventory'] and not custody['drops'] and all('minecraft:air' in b for b in custody['blocks'].values()),'Remaining native custody differs')
    h.actions_active=False;h.quiet(80);h.save_checkpoint();value['terminalRestart']=h.restart(hard=True);h.quiet(80)
    require(job(h,value['run'])==current and physical(h,case)==custody and h.model_calls()==value['modelCallsBefore'],'Completed remaining unit replayed after another kill')
    value.update(status='PASS_ORIGINAL_JOB_RESUMED_ONLY_REMAINING_ONE',originalJob=current,custody=custody,modelCallsAfter=h.model_calls(),terminalWorldHashes=retain(h,directory),resumedCompletion=True)
    atomic(directory/'result.json',value);return value

def expired(h,cases):
    options=[(read(h.root/'original-cases'/c['id']/'result.json'),c) for c in cases]
    original,case=min(options,key=lambda pair:pair[0]['originalAtCut']['allowance']['deadlineEpochMillis'])
    deadline=original['originalAtCut']['allowance']['deadlineEpochMillis'];require(int(time.time()*1000)>deadline,'No original deadline has actually expired; expiry control remains required')
    name='expired-original-accounting';directory=h.root/'controls'/name;directory.mkdir(parents=True,exist_ok=True)
    h.case_stage=case['checkpoint'];h.restore(h.root/'original-cases'/case['id']/'cut-game',name);key=original['key'];calls=h.model_calls()
    h.save_checkpoint();classification(h,{'SAVED_COMPLETE'},'Expired original still has actual saved accounting proof')
    h.command('PlayerA','/aivillage kernel job-cancel '+key['step'],'job='+key['step']+' cancellation=true')
    current=h.wait(lambda:(v if (v:=job(h,original['run'])) and v['state']=='CANCELLED' and not v['attempts'][-1]['uncertain'] else None),'Expired original accounting closes without new work',60)
    require(current['id']==key['step'] and current['generation']==key['generation'] and current['allowance']==original['originalAtCut']['allowance'],'Expired original received a new allowance')
    custody=physical(h,case);h.quiet(80);require(physical(h,case)==custody and h.model_calls()==calls,'Expired original continued physical work')
    value={'id':name,'status':'PASS_NATIVE_ACCOUNTING_AFTER_ACTUAL_DEADLINE','originalCase':case,'key':key,'originalJob':current,'deadline':deadline,'observedAt':int(time.time()*1000),
           'modelCallsBefore':calls,'modelCallsAfter':h.model_calls(),'custody':custody,'terminalWorldHashes':retain(h,directory)}
    atomic(directory/'result.json',value);return value

def cost(h,seed,templates):
    directory=h.root/'controls/representative-active-cost';directory.mkdir(parents=True,exist_ok=True);windows=[];captures={s:[] for s in ('HARVEST','PICKUP','DEPOSIT')};ack=[]
    for n,template in enumerate(templates):
        case=copy.deepcopy(template);case.update(id='cost-'+str(n),requested=1);h.case_stage='DEPOSIT';h.restore(seed,case['id'])
        dst=[case['destination'][k] for k in ('x','y','z')];h.reset({},destination=dst,table=False);h.logs(positions(case),item=case['item'])
        start=int(time.time()*1000);calls=h.model_calls();src=case['source']
        run=h.submit(case['item'],1,minimum=[src[k] for k in ('minX','minY','minZ')],maximum=[src[k] for k in ('maxX','maxY','maxZ')],destination=dst);view=h.finish_run(run)
        require(all(receipts(view,s,case['item'])==1 for s in ('HARVEST','PICKUP','DEPOSIT')) and physical(h,case)['destination']==1,'Cost interval lacks actual successful uninterrupted H/P/D')
        end=int(time.time()*1000);h.quiet(400);raw=read(h.root/'server/runtime-a-ticks.json');active=[r for r in raw['samples'] if start<=r['time']<=end];require(len(active)>=40,'Cost interval lacks active tick coverage')
        evidence=h.evidence();metrics={s:v['metrics'] for s,v in evidence['stages'].items()};captures['HARVEST']+=metrics['HARVEST']['preparationNanos'];captures['PICKUP']+=metrics['PICKUP']['captureNanos'];captures['DEPOSIT']+=metrics['DEPOSIT']['beginNanos']
        for v in metrics.values():ack+=v['acknowledgementNanos']
        require(h.model_calls()==calls,'Active known-work cost interval called a model')
        window={'id':case['id'],'item':case['item'],'run':run,'view':view,'start':start,'end':end,'activeSamples':active,'raw':raw,'metrics':metrics,'modelCallsUnchanged':True}
        target=directory/case['id'];target.mkdir(parents=True,exist_ok=True)
        window['worldHashes']=retain(h,target);window['nativeSaved']=native_saved(target/'terminal-world','DEPOSIT',case);window['originalCase']=case
        atomic(directory/(case['id']+'.json'),window);windows.append(window)
    wheat=next(c for c in read(h.root/'a06-original-results.json')['cases'] if c['originalCase']['item']=='minecraft:wheat')
    captures['HARVEST']+=wheat['reached']['stages']['HARVEST']['metrics']['preparationNanos']
    ticks=percentiles([s['nanos'] for w in windows for s in w['activeSamples']]);capture={s:percentiles(v) for s,v in captures.items()};ack_value=percentiles(ack)
    value={'id':'representative-active-cost','status':'PASS_REPRESENTATIVE_ACTIVE_COST' if ticks['p95']<=20 and ticks['p99']<=40 and all(v['p99']<=2 for v in capture.values()) and ack_value['max']<=30000 else 'FAIL_COST_TARGET',
           'windows':len(windows),'nativeEffects':3*len(windows),'tick':ticks,'capture':capture,'acknowledgement':ack_value,'limits':{'tickP95Ms':20,'tickP99Ms':40,'captureP99Ms':2,'ackMaxMs':30000},'warmupExcluded':False}
    atomic(directory/'result.json',value);return value

def campaign(h,seed,cases):
    result={'schema':1,'status':'FAIL_REQUIRED_CONTROLS','required':list(REQUIRED),'cases':[]};root=h.root/'a06-controls-results.json'
    def attempt(name,call):
        try:value=call()
        except Exception as error:value={'id':name,'status':'FAIL','reason':str(error),'trace':traceback.format_exc()}
        result['cases'].append(value);atomic(root,result);return value
    by_stage={s:next(c for c in cases if c['checkpoint']==s and c['item']!= 'minecraft:wheat') for s in ('HARVEST','PICKUP','DEPOSIT')}
    for stage,case in by_stage.items():
        value=attempt('source-replacement-'+stage,lambda c=case:torn(h,c,unrelated=c['checkpoint']=='PICKUP'))
        if stage=='PICKUP':result['cases'].append(value.get('unrelated',{'id':'unrelated-native-progress','status':'FAIL','reason':'Unrelated native progress was not reached'}))
    attempt('unsaved-pickup-containment',lambda:unsaved(h,seed,by_stage['PICKUP']))
    attempt('two-unit-prepared-no-effect',lambda:no_effect(h,seed,by_stage['DEPOSIT']))
    attempt('two-unit-delivered-remaining-one',lambda:remaining(h,seed,by_stage['DEPOSIT']))
    attempt('representative-active-cost',lambda:cost(h,seed,[c for c in cases if c['item']!='minecraft:wheat']))
    attempt('expired-original-accounting',lambda:expired(h,cases))
    require({v['id'] for v in result['cases']}==set(REQUIRED) and len(result['cases'])==len(REQUIRED),'A mandatory control was omitted')
    if all(v['status'].startswith('PASS_') for v in result['cases']):result['status']='PASS_ALL_REQUIRED_NATIVE_CONTROLS'
    atomic(root,result);require(result['status']=='PASS_ALL_REQUIRED_NATIVE_CONTROLS','One or more mandatory A06 native controls failed');return result
