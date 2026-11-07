"""A07 Docker clients: native lifecycle matrix, explicit handoff and nine frozen W-04 contexts."""
import argparse,copy,json,math,shutil,subprocess,time,traceback,uuid
from pathlib import Path
from recovery_native import RecoveryHarness
from recovery_seed import start as retained_seed
from recovery_controls import original_control,classification,job,retain
from recovery_cases import originals
from recovery_fixture import native_saved
from lifecycle_cases import contexts,LIVE_CASES,DEATH_CASES,HANDOFF_CASES,disposition,handoff
from suite import ROOT,atomic,read,require,reconnect_players,DENIED_PATTERN
from generalization_support import LOG,BIRCH,AXE,PLANK,STICK,coord,Inconclusive
from generalization import receipts
from baseline import native_process
from deposits import manifest


class LifecycleHarness(RecoveryHarness):
    move_companion=False
    def command(self,player,text,expected,timeout=30):
        if not text.startswith('/aivillage kernel name '):return super().command(player,text,expected,timeout)
        # A busy identity publication rejects before changing a name. Retry only that exact zero-work refusal.
        pattern='(?:'+expected+')|Name: BUDGET_EXHAUSTED'
        def named():
            answer=super(LifecycleHarness,self).command(player,text,pattern,timeout)
            return None if answer=='Name: BUDGET_EXHAUSTED' else answer
        return self.wait(named,'Original W04 name publication accepted once after bounded zero-work refusals',timeout)
    def compose(self,*args):
        command=['docker','compose','-p',self.project,'-f',str(ROOT/'e2e/compose.yml'),
                 '-f',str(ROOT/'e2e/parallel_a/compose-deposits.yml'),'-f',str(ROOT/'e2e/parallel_a/compose-lifecycle.yml'),*args]
        result=subprocess.run(command,cwd=ROOT,env=self.env,text=True,capture_output=True)
        with (self.root/'docker.log').open('a') as output:output.write(result.stdout+result.stderr)
        if result.returncode:raise RuntimeError('A07 Compose: '+result.stderr[-1800:])
        return result
    def a07(self):
        error=read(self.root/'server/a07-error.json')
        if error and (self.root/'server/a07-error.json').stat().st_mtime*1000>=self.activated:raise RuntimeError(error)
        value=read(self.root/'server/a07-snapshot.json')
        return value if value and value['time']>=self.activated else None
    def observation(self,op,**values):
        ready=self.wait(self.a07,'Actual A07 native observer',45)
        request={'id':str(uuid.uuid4()),'process':ready['process'],'op':op,**values};atomic(self.root/'server/a07-request.json',request)
        reply=self.wait(lambda:(v if (v:=read(self.root/'server/a07-response.json')) and v['id']==request['id'] else None),'A07 '+op,25)
        require(reply['status']=='PASS','A07 observer request failed');return reply
    def watch(self,points,entities):
        self.observation('watch',positions=points,entities=entities)
        keys={','.join(map(str,p)) for p in points}
        return self.wait(lambda:(v if (v:=self.a07()) and set(map(str,entities))==set(v['entities']) and keys==set(v['targets']) else None),'Exact A07 native watch set',25)
    def life(self,citizen=None,player='PlayerA'):
        value=read(self.root/'server/lifecycle-snapshot.json')
        if not value or value['time']<self.activated:return None
        return value['players'].get(player,{}).get(citizen or self.citizens['PlayerA'])
    def target(self,point):return (self.a07() or {}).get('targets',{}).get(','.join(map(str,point)))
    def block(self,point):
        value=self.target(point)
        return value.get('block','UNKNOWN') if value else super().block(point)
    def container(self,point,item='minecraft:wheat'):
        value=self.target(point)
        if value and value.get('loaded'):return value.get('stock',{}).get(item,0)
        return super().container(point,item)
    def vanilla(self,player,command):
        result=self.command(player,command,'.+')
        require(not any(s in result for s in ('Unknown or incomplete command','Incorrect argument','No entity was found')),command+' failed: '+result)
        return result
    def relocate_companion(self):
        self.vanilla('PlayerA','/gamemode spectator');self.vanilla('PlayerA','/tp 329.5 208 69.5')
        self.wait(lambda:(v if (v:=self.life(self.companion[0])) and v['identity']['availability']=='LOADED' else None),'Actual companion loaded before fixture relocation',60)
        self.vanilla('PlayerA','/tp '+self.companion[1]+' 68.5 208 68.5')
        self.vanilla('PlayerA','/tp 66.5 208 69.5');self.vanilla('PlayerA','/gamemode survival')
        self.wait(lambda:any(a['uuid']==self.companion[1] for a in self.snapshot()['actors']),'Actual same companion reaches the main arena',40)
    def reset(self,*args,**kwargs):
        value=super().reset(*args,**kwargs)
        if self.move_companion:self.relocate_companion()
        return value
    def activate(self,seed,name):
        self.citizens['PlayerA'],self.entities['PlayerA']=self.primary
        self.env.update(A06_HARVEST_ENABLED='true',A06_PICKUP_ENABLED='true',A05_DEPOSIT_ENABLED='true',A06_RECEIPT_PREFIX_ENABLED='true')
        self.restore(seed,name);self.observation('clear')
    def restore(self,seed,name):
        if hasattr(self,'primary'):self.citizens['PlayerA'],self.entities['PlayerA']=self.primary
        result=super().restore(seed,name);self.observation('clear');return result
    def unrelated(self):
        primary=self.citizens['PlayerA'],self.entities['PlayerA'];self.actions_active=False
        # The live-unload caller has already made both clients spectators; an idempotent gamemode command has no feedback.
        self.vanilla('PlayerA','/tp 329.5 208 69.5');self.vanilla('PlayerA','/gamemode survival')
        self.wait(lambda:(v if (v:=self.life(self.companion[0])) and v['identity']['availability']=='LOADED' else None),'Independent pre-existing worker loads normally',60)
        try:
            self.citizens['PlayerA'],self.entities['PlayerA']=self.companion
            run=self.submit(LOG,1,minimum=self.other_source,maximum=self.other_source,destination=self.other_destination)
            view=self.finish_run(run);require(all(receipts(view,s,LOG)==1 for s in ('HARVEST','PICKUP','DEPOSIT')),'Unrelated known work lacks actual receipts')
            require(self.container(self.other_destination,LOG)==1,'Unrelated native destination differs')
            return {'run':run,'view':view,'destination':self.other_destination,'stock':1}
        finally:self.citizens['PlayerA'],self.entities['PlayerA']=primary


def checkpoint(h,folder,name='terminal-world'):
    h.actions_active=False;h.vanilla('PlayerB','/tick unfreeze');h.save_checkpoint();h.stop('server',hard=True)
    target=folder/name;shutil.copytree(h.root/'server/game/world',target,ignore=shutil.ignore_patterns('session.lock'))
    return manifest(target)

def starting_checkpoint(h,folder):
    require(not h.actions_active,'Starting world must precede active gameplay')
    h.save_checkpoint();target=folder/'starting-world'
    shutil.copytree(h.root/'server/game/world',target,ignore=shutil.ignore_patterns('session.lock'))
    return manifest(target)


def freeze_seed(h,archive):
    h.activated=0;seed=retained_seed(h,archive)
    h.primary=h.citizens['PlayerA'],h.entities['PlayerA']
    h.env.update(A06_HARVEST_ENABLED='true',A06_PICKUP_ENABLED='true',A05_DEPOSIT_ENABLED='true',A06_RECEIPT_PREFIX_ENABLED='true')
    h.restore(seed,'a07-baseline');h.watch([[74,208,69],[77,208,72],[76,208,74]],[h.entities['PlayerA']])
    # One ordinary baseline axe admission supplies a real required-facility producer.
    h.reset({PLANK:3,STICK:2});h.env['COGNITIVECRAFT_OLLAMA_MODEL']='qwen3:4b-instruct-2507-q4_K_M'
    # The configured model is selected at process startup, so restart the unchanged, clean seed first.
    h.save_checkpoint();h.stop('server');h.activated=int(time.time()*1000);h.start('server');reconnect_players(h,list(h.players))
    calls=h.model_calls();admission={'before':calls}
    try:
        h.compose('up','-d','ollama')
        for attempt in range(3):
            try:h.compose('exec','-T','ollama','ollama','pull',h.env['COGNITIVECRAFT_OLLAMA_MODEL']);break
            except RuntimeError:
                if attempt==2:raise
                time.sleep(2)
        h.command('PlayerA','/aivillage kernel inference true','admission=true')
        run=h.submit(AXE,1,acquire=True);view=h.finish_run(run,admitted=True)
        require(h.model_calls()['generationCalls']>calls['generationCalls'],'Required-facility method was not genuinely admitted')
        h.hashes['cognitivecraft:produce_wooden_axes']=view['marker']['artifactSha256']
        admission.update(status='PASS',run=run,view=view,after=h.model_calls())
    except Exception as failure:
        admission.update(status='FAIL_REQUIRED_FACILITY_ADMISSION',reason=str(failure),trace=traceback.format_exc())
        atomic(h.root/'a07-facility-prerequisite-failure.json',admission)
        # Preserve the failed admission world; independent log/lifecycle cases still use the exact retained seed.
        h.stop('server');h.restore(seed,'after-failed-facility-admission')
    h.command('PlayerA','/aivillage kernel inference false','admission=false');h.compose('stop','ollama')
    h.reset({},table=False);h.save_checkpoint();h.stop('server')
    ready=h.root/'a07-baseline-game';shutil.copytree(h.root/'server/game',ready,ignore=shutil.ignore_patterns('session.lock'))
    atomic(h.root/'a07-baseline-admissions.json',{'seed':read(h.root/'seed-continuation.json'),'axe':admission,
            'artifacts':h.hashes,'worldHashes':manifest(ready/'world'),'admissionsCountedAsA07Cases':0})
    return ready


def role_fixture(h,role):
    actor=[67,208,68];source=[73,208,73];destination=[77,208,72]
    facility=[76,208,74];inputs=[74,208,69]
    # Ticket setup is explicit fixture authority. Production never adds tickets.
    points=[actor,source,destination]+([facility,inputs] if role=='FACILITY' else [])
    for p in points:h.vanilla('PlayerB','/forceload add '+str(p[0])+' '+str(p[2]))
    h.watch(points,[h.entities['PlayerA']])
    h.wait(lambda:all((h.target(p) or {}).get('loaded') for p in points),'All native fixture chunks genuinely loaded',50)
    h.console(['fill 63 207 63 84 207 78 minecraft:stone','fill 63 208 63 84 212 78 minecraft:air',
               'tp '+h.entities['PlayerA']+' '+coord([actor[0]+.5,actor[1],actor[2]+.5]),
               'setblock '+coord(destination)+' minecraft:chest'])
    if role=='FACILITY':
        h.stock_fixture({PLANK:3,STICK:2},source=inputs);h.console(['setblock '+coord(facility)+' minecraft:crafting_table'])
    else:h.logs([source])
    target=actor if role=='ACTOR' else source if role=='SOURCE' else facility if role=='FACILITY' else destination
    return {'actor':actor,'source':source,'destination':destination,'facility':facility,'inputs':inputs,'target':target,'watched':points}


def live_case(h,seed,role,phase,restart_unloaded=False):
    name=role+'-'+phase;directory=h.root/'connected'/name;directory.mkdir(parents=True,exist_ok=True)
    h.activate(seed,name);fixture=role_fixture(h,role);before=h.wait(h.life,'Verified original identity',35);calls=h.model_calls()
    starting=starting_checkpoint(h,directory);initial_native=h.a07()
    token=str(uuid.uuid4());h.observation('arm',mode=phase,actor=h.entities['PlayerA'],token=token)
    if role=='FACILITY':run=h.submit(AXE,1,source=fixture['inputs'],destination=fixture['destination'],table=fixture['facility'])
    else:run=h.submit(LOG,1,minimum=fixture['source'],maximum=fixture['source'],destination=fixture['destination'])
    cut=h.wait(lambda:(v if (v:=read(h.root/'server/a07-cut.json')) and v['token']==token else None),'Actual A07 '+name+' boundary',100)
    require(cut['freezeResult']==1 and cut['mode']==phase,'A07 selected phase was not witnessed')
    original=next(j for j in cut['jobs']['jobs'] if j.get('submissionId')==run or j.get('submission')==run)
    require(original['state']=='ACTIVE','A07 cut owner was not live')
    current=h.a07();actor_row=current['entities'][h.entities['PlayerA']]
    atomic(directory/'actual-cut.json',{'cut':cut,'native':current,'originalJob':original,'identity':before})
    if role=='ACTOR':fixture['target']=[math.floor(v) for v in actor_row['pos']]
    target=fixture['target'];target_chunk=(target[0]//16,target[2]//16)
    h.watch(fixture['watched']+[target] if target not in fixture['watched'] else fixture['watched'],[h.entities['PlayerA']])
    # Remove every fixture ticket that can keep the selected chunk full, including a moving actor's chunk.
    removed=[]
    for p in fixture['watched']:
        if max(abs(p[0]//16-target_chunk[0]),abs(p[2]//16-target_chunk[1]))<=2:
            removed.append(h.vanilla('PlayerB','/forceload remove '+str(p[0])+' '+str(p[2])))
    for player in h.players:
        h.vanilla(player,'/gamemode spectator');h.vanilla(player,'/tp 10000.5 220 10000.5')
    # Native stale-ticket aging pauses under vanilla freeze. Release only bounded UNKNOWN/full-chunk
    # tickets retained by fixture reads after both real clients left; native ChunkMap alone performs unload.
    # This explicit controlled matrix is separate from the nine ordinary, unmodified W04 timeout contexts.
    h.observation('release-fixture-tickets',token=token)
    ticket_release=read(h.root/'server/a07-fixture-ticket-release.json');require(ticket_release['token']==token,'Fixture residency release cut differs')
    atomic(directory/'ticket-release.json',ticket_release)
    start=time.monotonic()
    absent=h.wait(lambda:(v if (v:=h.a07()) and not v['targets'][','.join(map(str,target))]['loaded']
                         and not v['entities'][h.entities['PlayerA']]['loaded'] else None),
                  'Actual native '+role+' chunk unload at '+phase,110)
    at_loss=job(h,run);loss_life=h.life();require(at_loss is not None,'Original Job disappeared at native unload')
    if at_loss['state']=='SUCCEEDED' and phase!='PUBLICATION':raise Inconclusive('Original work completed before the actual required native unload window')
    unload_seconds=time.monotonic()-start
    require(at_loss['id']==original['id'] and at_loss['allowance']==original['allowance'],'Unload replaced responsibility or extended its original allowance')
    require(any(e['event']=='ENTITY_UNLOAD' and e['entity']==h.entities['PlayerA'] for e in absent['nativeEvents']),
            'Native entity-unload callback was not independently observed')
    atomic(directory/'native-loss.json',{'native':absent,'identity':loss_life,'originalJob':at_loss,'unloadSeconds':unload_seconds})
    h.vanilla('PlayerB','/tick unfreeze');before_other=job(h,run);other=h.unrelated();after_other=job(h,run)
    require(sum(a['credited'] for a in after_other['attempts'])==sum(a['credited'] for a in before_other['attempts']),
            'Unavailable original work gained delivery credit while unrelated work ran')
    # Unrelated physical work restored survival mode. Switch once before spectator reload, including the unloaded restart.
    h.vanilla('PlayerA','/gamemode spectator')
    unloaded_restart=None
    if restart_unloaded:
        h.save_checkpoint();unloaded_restart=h.restart(hard=True);h.watch(fixture['watched']+[target] if target not in fixture['watched'] else fixture['watched'],[h.entities['PlayerA']])
        still=h.wait(h.a07,'Native unloaded restart observations',40)
        require(not still['targets'][','.join(map(str,target))]['loaded'],'Restart forced the unavailable target chunk')
        require(job(h,run)['id']==original['id'],'Restart replaced original responsibility')
    h.vanilla('PlayerA','/tp '+coord([target[0]+.5,target[1]+2,target[2]+.5]))
    loaded=h.wait(lambda:(v if (v:=h.target(target)) and v['loaded'] else None),'Real player reloads original target chunk',60)
    h.vanilla('PlayerA','/tp 66.5 208 69.5');h.vanilla('PlayerA','/gamemode survival')
    after=h.wait(lambda:(v if (v:=h.life()) and v['identity']['availability']=='LOADED' else None),'Original citizen returns with exact identity',65)
    require(after['identity']['actor']==before['identity']['actor'] and after['identity']['owner']==before['identity']['owner'],
            'Native unload/reload changed immutable actor or owner')
    require(after['identity']['lifecycle']['generation']==before['identity']['lifecycle']['generation']+1,
            'Repeated native events duplicated or failed the ordinary reload generation')
    h.command('PlayerB','/aivillage kernel job-status '+original['id'],DENIED_PATTERN)
    require(h.model_calls()==calls,'Lifecycle matrix or unrelated reuse called a model')
    h.actions_active=False;h.screenshot('PlayerA',name+'-same-identity',chat=True)
    value={'id':name,'status':'PASS_CONNECTED_NATIVE_LIFECYCLE','fixture':fixture,'before':before,'startingWorldHashes':starting,
           'initialNative':initial_native,'nativeAtCut':current,'run':run,'cut':cut,
           'removedFixtureTickets':removed,'nativeTransientTicketRelease':ticket_release,'unloadSeconds':unload_seconds,'absent':absent,'lifeAtLoss':loss_life,'originalAtLoss':at_loss,
           'unrelated':other,'originalAfterUnrelated':after_other,'unloadedRestart':unloaded_restart,'loaded':loaded,'after':after,
           'modelCallsBefore':calls,'modelCallsAfter':h.model_calls(),'claim':'Selected required target unloads after the actual execution/publication cut. Adjacent actor/source/facility/destination may unload together, recorded without a single-cause claim; original wall/tick budgets are unchanged. No resumed-goal completion is claimed.'}
    value['terminalWorldHashes']=checkpoint(h,directory);atomic(directory/'result.json',value);return value


def w04_case(h,seed,frozen):
    c=copy.deepcopy(frozen['case']);name=c['id'];directory=h.root/'w04'/name;directory.mkdir(parents=True,exist_ok=True)
    h.activate(seed,'w04-'+name);h.actions_active=False;h.disconnect('PlayerB')
    # Recreate the original one-client subject context; unrelated enrolled actors are removed in disposable setup.
    h.console(['kill '+h.entities['PlayerB']]);h.vanilla('PlayerA','/gamemode spectator');h.vanilla('PlayerA','/tp 329.5 208 69.5')
    h.wait(lambda:(v if (v:=h.life(h.companion[0])) and v['identity']['availability']=='LOADED' else None),'Disposable non-subject companion loaded',55)
    h.console(['kill '+h.companion[1]]);h.vanilla('PlayerA','/tp 66.5 208 69.5');h.vanilla('PlayerA','/gamemode survival')
    # Exact frozen source positions, orientation, destination contents, name and actor position.
    lines=['fill 63 208 63 85 213 79 minecraft:air','fill 63 207 63 85 207 79 minecraft:stone',
           'fill 63 208 63 85 213 63 minecraft:glass','fill 63 208 79 85 213 79 minecraft:glass',
           'fill 63 208 63 63 213 79 minecraft:glass','fill 85 208 63 85 213 79 minecraft:glass',
           'fill 63 214 63 85 214 79 minecraft:glass',
           'data merge entity '+h.entities['PlayerA']+' {Inventory:'+json.dumps([{'id':'minecraft:cobblestone','count':64} for _ in range(c.get('actorOccupiedSlots',0))],separators=(',',':'))+'}',
           'tp '+h.entities['PlayerA']+' '+coord([c['actor'][0]+.5,c['actor'][1],c['actor'][2]+.5])]
    for p in c['positions']:
        if c['item']=='minecraft:wheat':lines.extend(['setblock '+coord([p[0],p[1]-1,p[2]])+' minecraft:farmland[moisture=7]','setblock '+coord(p)+' minecraft:wheat[age=7]'])
        else:lines.append('setblock '+coord(p)+' '+c['item']+'[axis='+c['logAxis']+']')
    for p in c.get('immature',[]):lines.extend(['setblock '+coord([p[0],p[1]-1,p[2]])+' minecraft:farmland[moisture=7]','setblock '+coord(p)+' minecraft:wheat[age=3]'])
    for p in c.get('standingSupports',[]):lines.append('setblock '+coord(p)+' minecraft:stone')
    if c['terrain']=='corridor':
        lines.extend(['fill 69 208 65 69 209 70 minecraft:stone','fill 69 208 72 69 209 77 minecraft:stone'])
    elif c['terrain']=='detour':lines.append('fill 69 208 67 69 209 74 minecraft:stone')
    lines.append('setblock '+coord(c['destination'])+' minecraft:'+c['container'])
    for slot,item,count in c['destinationSlots']:lines.append('item replace block '+coord(c['destination'])+' container.'+str(slot)+' with '+item+' '+str(count))
    for p in c['unrelated']:lines.append('setblock '+coord(p)+' '+c['nearbyUnrelated'])
    h.console(lines)
    primary=h.citizens['PlayerA'],h.entities['PlayerA']
    if c['index']%3==0:
        existing={a['uuid'] for a in h.snapshot()['actors']}
        h.console(['tp '+primary[1]+' 83.5 208 66.5','summon minecraft:villager '+coord([c['actor'][0]+.5,208,c['actor'][2]+.5])+' {PersistenceRequired:1b}'])
        fresh=h.wait(lambda:next((a for a in h.snapshot()['actors'] if a['uuid'] not in existing and a['alive'] and not a['controlled']),None),
                     'Actual new normal-AI villager observed before fresh W04 enrollment',30)
        # Fixture proximity moves the actual player, never the normal-AI subject; the public native enrollment still checks range/identity.
        h.vanilla('PlayerA','/tp '+coord(fresh['pos']))
        h.enroll(name=c['citizenName'])
        h.vanilla('PlayerA','/tp 66.5 208 69.5')
    else:h.command('PlayerA','/aivillage kernel name '+primary[0]+' '+c['citizenName'],'name='+c['citizenName'])
    subject,entity=h.citizens['PlayerA'],h.entities['PlayerA'];h.watch(c['positions']+[c['destination'],[c['actor'][0],208,c['actor'][2]]],[entity])
    before=h.wait(lambda:(v if (v:=h.life(subject)) and v['identity']['displayName']==c['citizenName'] and v['identity']['availability']=='LOADED' else None),'Frozen W04 named subject',40)
    starting=starting_checkpoint(h,directory);initial_native=h.a07()
    calls=h.model_calls();h.console(['forceload add 10000 10000'])
    h.wait(lambda:'Test passed' in h.command('PlayerA','/execute if loaded 10000 208 10000','Test passed|Test failed'),'Remote original W04 landing chunk loaded',45)
    h.console(['fill 10000 207 10000 10008 207 10008 minecraft:stone','setworldspawn 10004 208 10004'])
    h.command('PlayerA','/execute if block 10004 207 10004 minecraft:stone','Test passed')
    h.vanilla('PlayerA','/tp 10004.5 208 10004.5');started=time.monotonic()
    boundary=h.wait(lambda:(v if (v:=h.life(subject)) and v['identity']['availability']=='UNLOADED'
                 and v['identity']['lifecycle']['event']=='UNLOAD' else None),'Actual same-citizen native chunk unload',110)
    duration=time.monotonic()-started;native=h.a07()
    require(not native['entities'][entity]['loaded'],'Lifecycle UNLOAD disagrees with native entity lookup')
    require(any(e['event']=='ENTITY_UNLOAD' and e['entity']==entity for e in native['nativeEvents']),'Native unload event was not recorded')
    h.vanilla('PlayerA','/tp 66.5 208 69.5');h.console(['forceload remove 10000 10000'])
    after=h.wait(lambda:(v if (v:=h.life(subject)) and v['identity']['availability']=='LOADED' and v['identity']['lifecycle']['event']=='LOAD' else None),'Original W04 subject reloads',80)
    require(after['identity']['actor']==before['identity']['actor'] and after['identity']['owner']==before['identity']['owner']
            and after['identity']['displayName']==c['citizenName'] and after['identity']['lifecycle']['generation']==before['identity']['lifecycle']['generation']+1,
            'W04 native identity or generation changed incorrectly')
    require(h.model_calls()==calls,'W04 identity reproduction invoked a model');h.screenshot('PlayerA','w04-'+name,chat=True)
    reconnect_players(h,['PlayerB'])
    value={'id':name,'status':'PASS_REPRODUCED_NATIVE_UNLOAD_RELOAD','originalCase':c,'before':before,'startingWorldHashes':starting,
           'initialNative':initial_native,'boundary':boundary,'nativeAtUnload':native,
           'after':after,'unloadSeconds':duration,'originalTimeoutSeconds':110,'clock':'ordinary 20 TPS; no freeze or accelerated ticks',
           'modelCallsBefore':calls,'modelCallsAfter':h.model_calls(),'historicalFailure':frozen['reason'],
           'causeClaim':'Historical timeout is retained. Candidate success does not establish a unique historical cause.'}
    value['terminalWorldHashes']=checkpoint(h,directory);atomic(directory/'result.json',value)
    h.citizens['PlayerA'],h.entities['PlayerA']=primary
    return value


def death_case(h,seed,template,kind):
    directory,case,value=original_control(h,seed,template,kind,'RESULT_ACKNOWLEDGED')
    proof=native_saved(directory/'killed-world','HARVEST',case);h.watch([[73,208,73]],[h.entities['PlayerA'],proof['harvest']['result']['drops'][0]['entity']])
    drop=proof['harvest']['result']['drops'][0]['entity'];before=h.wait(lambda:(v if (v:=h.a07()) and v['entities'][drop]['loaded'] else None),'Actual original surviving native drop',40)
    h.command('PlayerA','/kill '+h.entities['PlayerA'],'Killed')
    death=h.wait(lambda:(v if (v:=h.life()) and v['identity']['lifecycle']['event']=='DEATH'
              and v['identity']['lifecycle']['acknowledged']==7 else None),'Confirmed native death and owner acknowledgements',65)
    if kind=='death-burned':
        pos=[math.floor(n) for n in before['entities'][drop]['pos']]
        h.vanilla('PlayerB','/setblock '+coord(pos)+' minecraft:lava');h.vanilla('PlayerB','/tick unfreeze')
        final=h.wait(lambda:(v if (v:=h.a07()) and disposition(v['entities'][drop],v['damage'])=='OBSERVED_NATIVE_DESTRUCTION' else None),'Actual native burning of the attributed drop',35)
    elif kind=='death-unavailable':
        for player in h.players:h.vanilla(player,'/gamemode spectator');h.vanilla(player,'/tp 10000.5 220 10000.5')
        final=h.wait(lambda:(v if (v:=h.a07()) and not v['entities'][drop]['loaded'] else None),'Unavailable original drop after actual chunk unload',110)
    else:final=h.wait(lambda:(v if (v:=h.a07()) and disposition(v['entities'][drop],v['damage'])=='OBSERVED_SURVIVING_DROP' else None),'Observed attributed surviving drop after death',35)
    expected={'death-surviving':'OBSERVED_SURVIVING_DROP','death-burned':'OBSERVED_NATIVE_DESTRUCTION','death-unavailable':'UNAVAILABLE_CUSTODY_UNKNOWN'}[kind]
    require(disposition(final['entities'][drop],final['damage'])==expected,'Death custody classes were collapsed')
    original=job(h,value['run']);require(sum(a['credited'] for a in original['attempts'])==0,'Death invented a delivery credit')
    require(original['id']==value['key']['step'] and original['generation']==value['key']['generation'],'Death replaced original Job identity')
    require(h.model_calls()==value['modelCallsBefore'],'Death custody observation called a model')
    value.update(status='PASS_NATIVE_DEATH_CUSTODY',nativeSavedProof=proof,beforeDeath=before,death=death,finalNative=final,
                 custody=expected,originalJob=original,modelCallsAfter=h.model_calls(),claim='Observed surviving/destroyed/unknown custody; no authority to inherit, replay or invent delivery.')
    value['terminalWorldHashes']=checkpoint(h,directory);atomic(directory/'result.json',value);return value


def handoff_case(h,seed,template,kind):
    h.move_companion=True
    try:directory,case,value=original_control(h,seed,template,kind,'RESULT_ACKNOWLEDGED')
    finally:h.move_companion=False
    value['nativeSavedProof']=native_saved(directory/'killed-world','DEPOSIT',case)
    classification(h,{'SAVED_COMPLETE'},'Original delivered-unit evidence before explicit reassignment')
    current=job(h,value['run']);successor=h.wait(lambda:(v if (v:=h.life(h.companion[0])) and v['identity']['availability']=='LOADED' else None),'Eligible explicit successor',40)['identity']['actor']
    command='/aivillage kernel job-handoff '+current['id']+' '+str(current['revision'])+' '+str(current['generation'])+' '+h.companion[0]
    denied={'foreign':h.command('PlayerB',command,DENIED_PATTERN),
            'staleRevision':h.command('PlayerA',command.replace(' '+str(current['revision'])+' '+str(current['generation'])+' ',
                       ' '+str(current['revision']-1)+' '+str(current['generation'])+' '),'handoff=false'),
            'staleGeneration':h.command('PlayerA',command.replace(' '+str(current['revision'])+' '+str(current['generation'])+' ',
                       ' '+str(current['revision'])+' '+str(current['generation']+1)+' '),'handoff=false')}
    if kind=='handoff-stale-successor':
        h.command('PlayerB','/kill '+h.companion[1],'Killed')
        h.wait(lambda:(v if (v:=h.life(h.companion[0])) and v['identity']['availability']=='UNAVAILABLE' else None),'Confirmed successor death',40)
        denied['deadSuccessor']=h.command('PlayerA',command,'handoff=false')
        h.vanilla('PlayerB','/summon minecraft:villager 68.5 208 68.5 {PersistenceRequired:1b,CustomName:"Hana"}')
        extra=h.wait(lambda:next((a for a in h.snapshot()['actors'] if a['uuid'] not in h.entities.values() and a['uuid']!=h.companion[1] and not a['controlled']),None),'Actual same-name unbound replacement',30)
        denied['sameName']=h.command('PlayerA',command.rsplit(' ',1)[0]+' '+extra['uuid'],'Citizen unavailable in your control scope')
        h.command('PlayerA','/aivillage kernel job-cancel '+current['id'],'cancellation=true')
        final=h.wait(lambda:(j if (j:=job(h,value['run'])) and j['state']=='CANCELLED' and not j['attempts'][-1]['uncertain'] else None),'Original settled without replacement',60)
        require(len(final['attempts'])==1 and sum(a['credited'] for a in final['attempts'])==1,'Denied successor gained an assignment or duplicate delivery')
        status='PASS_DENIED_STALE_AND_SAME_NAME_SUCCESSOR'
    else:
        if kind=='handoff-revoked':h.vanilla('PlayerB','/gamerule minecraft:mob_griefing false')
        value['authorization']=h.command('PlayerA',command,'handoff=true')
        if kind=='handoff-revoked':
            ready=h.wait(lambda:(j if (j:=job(h,value['run'])) and not j['attempts'][-1]['uncertain'] else None),'Historical delivery accounted under revoked effect authority',60)
            h.quiet(80);blocked=job(h,value['run']);require(sum(a['credited'] for a in blocked['attempts'])==1 and all(a['effects']==0 for a in blocked['attempts'][1:]),'Revocation allowed new physical work')
            require(blocked['attempts'][-1].get('terminal') is not None and not blocked['attempts'][-1]['uncertain'],
                    'Closed-original denial requires an actually settled original attempt')
            value['closedOriginalBeforeDenied']=blocked
            closed_command='/aivillage kernel job-handoff '+blocked['id']+' '+str(blocked['revision'])+' '+str(blocked['generation'])+' '+h.companion[0]
            denied['closedOriginal']=h.command('PlayerA',closed_command,'handoff=false')
            h.command('PlayerA','/aivillage kernel job-cancel '+current['id'],'cancellation=true')
            final=h.wait(lambda:(j if (j:=job(h,value['run'])) and j['state']=='CANCELLED' else None),'Revoked handoff cancellation settles',45)
            h.vanilla('PlayerB','/gamerule minecraft:mob_griefing true');status='PASS_REVOKED_NEW_EFFECTS_HISTORICAL_ACCOUNTING'
        else:
            assigned=h.wait(lambda:(j if (j:=job(h,value['run'])) and j['generation']==current['generation']+1 else None),'Jobs publishes the exact authorized successor generation',90)
            require(assigned['attempts'][-1]['worker']==successor,'Dispatcher selected a different worker')
            h.command('PlayerB','/kill '+h.entities['PlayerA'],'Killed')
            final=h.wait(lambda:(j if (j:=job(h,value['run'])) and j['state']=='SUCCEEDED' else None),'Authorized successor physically acquires and delivers only the remaining unit',210)
            value['handoffReview']=handoff(current,final,successor)
            require(h.container([case['destination'][k] for k in ('x','y','z')],LOG)==2,'Handoff actual chest does not contain exactly two units')
            status='PASS_EXPLICIT_PHYSICAL_HANDOFF_SAME_JOB'
            h.save_checkpoint();value['restart']=h.restart(hard=True);h.quiet(60)
            require(job(h,value['run'])==final,'Restart replayed or changed the completed handoff')
            denied['obsoleteGeneration']=h.command('PlayerA',command,'handoff=false')
    require(h.model_calls()==value['modelCallsBefore'],'Handoff required a model or inherited knowledge without existing eligibility')
    value.update(status=status,denied=denied,beforeHandoff=current,successor=successor,originalJob=final,modelCallsAfter=h.model_calls(),
                 envelope='Saved proved-deposit handoff with empty old custody. Remaining known source goods are physically acquired; no unproved held inventory is transferred.')
    value['terminalWorldHashes']=checkpoint(h,directory);atomic(directory/'result.json',value);return value


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,required=True);p.add_argument('--seed-archive',type=Path,required=True);args=p.parse_args()
    root=args.root.resolve();root.mkdir(parents=True,exist_ok=True);h=None
    planned=[r['case']['id'] for r in contexts(ROOT/'e2e/parallel_a/a07_w04_contexts.json')]+list(LIVE_CASES)+list(DEATH_CASES)+list(HANDOFF_CASES)
    result={'schema':1,'stage':'A07','status':'NOT_EXECUTED','a07Qualified':False,'cases':[],'failures':[],'plannedCases':planned,'requiredCases':len(planned),'omittedTasks':0,'omittedTests':0}
    def execute(name,action):
        try:value=action()
        except Exception as failure:
            value={'id':name,'status':'INCONCLUSIVE_REQUIRED_NATIVE_WINDOW' if isinstance(failure,(Inconclusive,TimeoutError)) else 'FAIL',
                   'reason':str(failure),'trace':traceback.format_exc()}
            for key,observe in (('native',h.a07),('snapshot',h.snapshot),('life',h.life)):
                try:value[key]=observe()
                except Exception as unavailable:value[key]={'unavailable':str(unavailable)}
            folder=root/'failures'/name;folder.mkdir(parents=True,exist_ok=True);atomic(folder/'result.json',value)
            result['failures'].append(name)
        result['cases'].append(value);atomic(root/'a07-results.json',result)
        print('A07_CONNECTED_CASE '+json.dumps({'id':name,'status':value['status'],'reason':value.get('reason')}),flush=True)
    try:
        h=LifecycleHarness(root);h.activated=0;seed=freeze_seed(h,args.seed_archive.resolve())
        frozen=contexts(ROOT/'e2e/parallel_a/a07_w04_contexts.json')
        # All nine historical contexts execute even if another case fails; no successful result substitutes for a missing context.
        for row in frozen:execute(row['case']['id'],lambda row=row:w04_case(h,seed,row))
        for name in LIVE_CASES:
            role,phase=name.split('-');execute(name,lambda role=role,phase=phase:live_case(h,seed,role,phase,role=='ACTOR' and phase=='PUBLICATION'))
        old=originals(ROOT/'e2e/parallel_a/a06_original_cases.json')
        harvest=next(c for c in old if c['checkpoint']=='HARVEST' and c['item']==LOG)
        deposit=next(c for c in old if c['checkpoint']=='DEPOSIT');deposit=copy.deepcopy(deposit);deposit['item']=LOG;deposit['capability']['name']='cognitivecraft:deliver_oak_logs'
        for name in DEATH_CASES:execute(name,lambda name=name:death_case(h,seed,harvest,name))
        for name in HANDOFF_CASES:execute(name,lambda name=name:handoff_case(h,seed,deposit,name))
        result['status']='PASS_CONNECTED_CANDIDATE_PENDING_INDEPENDENT_REVIEW' if not result['failures'] else 'FAIL_REQUIRED_A07_CASES'
        result['requiredCases']=9+len(LIVE_CASES)+len(DEATH_CASES)+len(HANDOFF_CASES)
        require(len(result['cases'])==result['requiredCases'],'A07 scenario omission')
    except Exception as failure:
        result.update(status='FAIL_A07_PREREQUISITE',reason=str(failure),trace=traceback.format_exc())
    finally:
        attempted={row['id'] for row in result['cases']}
        result['cases'].extend({'id':name,'status':'NOT_EXECUTED_REQUIRED','reason':result.get('reason','Prerequisite interrupted this case')} for name in planned if name not in attempted)
        result['omittedTests']=len([row for row in result['cases'] if row['status']=='NOT_EXECUTED_REQUIRED'])
        atomic(root/'a07-results.json',result)
        if h is not None:h.finish()
    print('A07_CONNECTED_COMPLETE '+json.dumps({k:v for k,v in result.items() if k not in ('cases','trace')}),flush=True)
    if result['status']!='PASS_CONNECTED_CANDIDATE_PENDING_INDEPENDENT_REVIEW':raise SystemExit(1)

if __name__=='__main__':main()
