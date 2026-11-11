"""A08 real connected clients, native custody and reserved original Kernel Jobs. No model method fixture."""
import argparse,hashlib,json,shutil,socket,subprocess,time,traceback,uuid
from pathlib import Path
from lifecycle_connected import LifecycleHarness,checkpoint,starting_checkpoint
from recovery_seed import start as retained_seed
from suite import ROOT,atomic,read,require,reconnect_players
from generalization_support import LOG,PLANK,STICK,coord,Inconclusive
from baseline import native_process
from deposits import manifest
from claims_cases import CASES,service

SRC=[74,208,69];DA=[73,208,68];DB=[75,208,68];TABLE=[77,208,73]

def active_claims(state,job=None):
    """Retained released history is never a live scheduling claim."""
    return [r for r in state['leases']['leases'] if r['state']=='ACTIVE' and (job is None or r['owner']['job']==job)]

class ClaimsHarness(LifecycleHarness):
    def compose(self,*args):
        files=['e2e/compose.yml','e2e/parallel_a/compose-deposits.yml','e2e/parallel_a/compose-lifecycle.yml','e2e/parallel_a/compose-claims.yml']
        cmd=['docker','compose','-p',self.project]
        for file in files:cmd+=['-f',str(ROOT/file)]
        if args and args[0]=='build':
            resolved={}
            for host in ('archive.ubuntu.com','security.ubuntu.com'):
                try:resolved[host]={'addresses':sorted({r[4][0] for r in socket.getaddrinfo(host,80,type=socket.SOCK_STREAM)})}
                except socket.gaierror as unavailable:resolved[host]={'error':str(unavailable)}
            print('A08_BUILD_HOST_DNS '+json.dumps(resolved),flush=True)
            for image in ('eclipse-temurin:25-jdk-noble','mcr.microsoft.com/playwright/python:v1.56.0-noble'):
                self.docker_build_input('pull',image)
        value=subprocess.run(cmd+list(args),cwd=ROOT,env=self.env,text=True,capture_output=True)
        with (self.root/'docker.log').open('a') as out:out.write(value.stdout+value.stderr)
        if value.returncode:raise RuntimeError('A08 Compose '+value.stderr[-1800:])
        if args and args[0]=='build':
            images=json.loads(self.docker_build_input('image','inspect','cognitivecraft-e2e:java25','eclipse-temurin:25-jdk-noble','mcr.microsoft.com/playwright/python:v1.56.0-noble').stdout)
            identities=[{k:image[k] for k in ('Id','RepoTags','RepoDigests','Architecture','Os')} for image in images]
            atomic(self.root/'a08-build-images.json',identities);print('A08_BUILD_IMAGES '+json.dumps(identities),flush=True)
            paths=['e2e/Dockerfile','e2e/compose.yml','e2e/parallel_a/compose-deposits.yml','e2e/parallel_a/compose-lifecycle.yml','e2e/parallel_a/compose-claims.yml','e2e/parallel_a/Dockerfile.claims','e2e/parallel_a/dependency_probe.py']
            inputs={'composeCommand':cmd+list(args),'actualDockerfile':'e2e/parallel_a/Dockerfile.claims','actualBuildContext':'e2e/parallel_a','files':{p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in paths},'networkDuringBuildRun':'none','runtimeNetwork':'host'}
            atomic(self.root/'a08-build-inputs.json',inputs);print('A08_BUILD_INPUTS '+json.dumps(inputs),flush=True)
            user=self.env.get('E2E_UID','1000')+':'+self.env.get('E2E_GID','1000')
            proof=self.docker_build_input('run','--rm','--network','none','--user',user,'--entrypoint','python3','cognitivecraft-e2e:java25','/opt/cognitivecraft/dependency_probe.py')
            dependency=json.loads(proof.stdout);atomic(self.root/'a08-image-dependencies.json',dependency)
            print('A08_IMAGE_DEPENDENCIES '+json.dumps(dependency),flush=True)
        return value
    def docker_build_input(self,*args):
        value=subprocess.run(['docker',*args],cwd=ROOT,env=self.env,text=True,capture_output=True,timeout=300)
        with (self.root/'docker.log').open('a') as out:out.write(value.stdout+value.stderr)
        if value.returncode:raise RuntimeError('A08 dependency image '+str(args[:2])+': '+value.stderr[-2400:])
        return value
    def state(self):
        value=read(self.root/'server/a08-snapshot.json')
        return value if value and value['time']>=self.activated else None
    def ready(self):
        def fresh():
            v=self.state();last=getattr(self,'rpc_sequence',None)
            return v if v and v['ready'] and (last is None or last[0]!=v['process'] or v['sequence']>last[1]) else None
        return self.wait(fresh,'Fresh acknowledged A08 Jobs and leases',30)
    def claim_rpc(self,op,player='PlayerA',expected='PASS',**values):
        current=self.wait(self.state,'Actual A08 native driver',45)
        request={'id':str(uuid.uuid4()),'process':current['process'],'principal':self.players[player],'op':op,**values}
        atomic(self.root/'server/a08-request.json',request)
        reply=self.wait(lambda:(v if (v:=read(self.root/'server/a08-response.json')) and v['id']==request['id'] else None),'A08 '+op+' native reply',25)
        atomic(self.root/'a08-rpc'/(request['id']+'.json'),{'request':request,'response':reply})
        self.rpc_sequence=(reply['process'],reply['value']['sequence'])
        require(reply['value']['status']==expected,'A08 '+op+': '+json.dumps(reply));return reply['value']
    def job(self,id):return next(j for j in self.ready()['jobs']['jobs'] if j['id']==id)
    def create_stage(self,quantity=3,player='PlayerA',source=SRC,destination=None,units=None,axe=False):
        self.ready();id=str(uuid.uuid4());destination=destination or (DA if player=='PlayerA' else DB)
        self.claim_rpc('create',player,job=id,citizen=self.citizens[player],source=source,destination=destination,
                       units=units or [{'item':LOG,'quantity':quantity}],axe=axe,table=TABLE)
        self.ready();self.claim_rpc('assign',player,job=id);self.ready();view=self.claim_rpc('open',player,job=id)['view']
        if view['phase']=='WAITING':
            # Deterministic initial fixture pose after the real runtime has acquired actor control.
            # NoAI remains false; ordinary engine physics and fresh reach checks stay active.
            pose=[73.5 if player=='PlayerA' else 75.5,208,69.5] if source==SRC else [source[0]+1.5,source[1],source[2]+.5]
            self.console(['tp '+self.entities[player]+' '+coord(pose)]);self.quiet(3)
        return id
    def stage(self,id,operation='WITHDRAW',player='PlayerA',until=None,polls=None):
        def action():
            state=self.ready();value=self.claim_rpc('step',player,job=id,operation=operation)['view']
            if polls is not None:
                tick=value['firstTick']+value['ticks']
                if polls and tick-polls[-1]>state['fairLimits']['idleTicks']:
                    raise Inconclusive('Required owner polling window missed: '+str(tick-polls[-1])+' ticks')
                polls.append(tick)
            if value['phase']=='BLOCKED' and until not in (None,'BLOCKED'):raise AssertionError('Unexpected native staging blocker '+json.dumps(value))
            return value if until is None or value['phase']==until else None
        return self.wait(action,'Native staging '+operation+' '+str(until),25)
    def physical(self):
        native=self.wait(self.a07,'A08 independent native custody observer',25)
        return {'time':native['time'],'tick':native['tick'],'targets':native['targets'],'entities':native['entities']}
    def observed_stock(self,p,stock):
        return self.wait(lambda:(v if (v:=self.target(p)) and v.get('stock',{})==stock else None),'Independent native stock '+coord(p),25)
    def setup_claims(self,seed,name,stock=5):
        self.env.update(A06_HARVEST_ENABLED='true',A06_PICKUP_ENABLED='true',A05_DEPOSIT_ENABLED='true',A06_RECEIPT_PREFIX_ENABLED='true')
        self.restore(seed,'a08-'+name);self.ready();self.reset({},source=SRC,destination=DA,table=False)
        self.console(['tp '+self.entities['PlayerA']+' 73.5 208 69.5','tp '+self.entities['PlayerB']+' 75.5 208 69.5',
                      'tp PlayerA 70.5 208 69.5','tp PlayerB 78.5 208 69.5',
                      'setblock '+coord(DB)+' minecraft:chest','setblock '+coord(TABLE)+' minecraft:crafting_table']+
                     ['setblock '+coord(p)+' minecraft:stone' for p in ([72,208,69],[73,208,70],[76,208,69],[75,208,70])])
        self.watch([SRC,DA,DB,TABLE,[74,208,70]],[self.entities['PlayerA'],self.entities['PlayerB']])
        if stock:self.console(['item replace block '+coord(SRC)+' container.0 with '+LOG+' '+str(stock)])
        self.observed_stock(SRC,{LOG:stock} if stock else {})
        self.wait(lambda:all(not a.get('inventory') for a in self.physical()['entities'].values()),'Both actual private inventories empty',20)
        actors={a['uuid']:a for a in self.snapshot()['actors']}
        require(all(actors[self.entities[p]]['alive'] and not actors[self.entities[p]]['noAI'] and not actors[self.entities[p]]['baby'] for p in self.players),'Two real normal-AI adult villagers required')
        self.quiet(5)
    def full(self,p,keep_logs=None):
        lines=['item replace block '+coord(p)+' container.'+str(i)+' with minecraft:cobblestone 64' for i in range(27)]
        if keep_logs is not None:lines[0]='item replace block '+coord(p)+' container.0 with '+LOG+' '+str(keep_logs)
        self.console(lines);self.observed_stock(p,{'minecraft:cobblestone':64*(26 if keep_logs is not None else 27),**({LOG:keep_logs} if keep_logs is not None else {})})

def finish_case(h,folder,result):
    h.ready();result['after']=h.physical();result['runtime']=h.state();result['fairA']=h.claim_rpc('status')['fair'];result['fairB']=h.claim_rpc('status','PlayerB')['fair']
    result['modelCallsAfter']=h.model_calls();require(result['modelCallsAfter']==result['modelCallsBefore'],'Unexpected inference in deterministic staging')
    result['terminalWorldHashes']=checkpoint(h,folder);result['status']='PASS_NATIVE_CANDIDATE';atomic(folder/'result.json',result);return result

def one(h,seed,name):
    folder=h.root/'cases'/name;folder.mkdir(parents=True,exist_ok=True)
    h.setup_claims(seed,name,2 if name in ('older-feasible-small-arrivals','impossible-large-useful-small') else 5)
    result={'id':name,'status':'NOT_EXECUTED','modelCallsBefore':h.model_calls(),'fixture':{'source':SRC,'aDestination':DA,'bDestination':DB,'table':TABLE,
                'actors':dict(h.entities),'citizens':dict(h.citizens),'principals':dict(h.players)},'expectedLogs':2 if name in ('older-feasible-small-arrivals','impossible-large-useful-small') else 5,'external':[]}
    if name=='initial-full-zero-effect':h.full(DA)
    if name=='source-destination-alias':a=h.create_stage(destination=SRC)
    elif name=='complementary-private-custody-residence-bound':
        h.console(['item replace block '+coord(SRC)+' container.0 with minecraft:air',
                   'data merge entity '+h.entities['PlayerA']+' {Inventory:[{id:"'+PLANK+'",count:3}]}',
                   'data merge entity '+h.entities['PlayerB']+' {Inventory:[{id:"'+STICK+'",count:2}]}'])
        h.observed_stock(SRC,{})
        h.wait(lambda:h.physical()['entities'][h.entities['PlayerA']].get('inventory')=={PLANK:3} and h.physical()['entities'][h.entities['PlayerB']].get('inventory')=={STICK:2},'Actual complementary private custody',20)
        units=[{'item':PLANK,'quantity':3},{'item':STICK,'quantity':2}];a=h.create_stage(units=units,axe=True);b=h.create_stage(player='PlayerB',units=units,axe=True);result['b']=b;result['expectedLogs']=0
    elif name=='complementary-inputs-atomic':
        h.console(['item replace block '+coord(SRC)+' container.0 with '+PLANK+' 3','item replace block '+coord(SRC)+' container.1 with '+STICK+' 1'])
        h.observed_stock(SRC,{PLANK:3,STICK:1});result['expectedLogs']=0
        a=h.create_stage(units=[{'item':PLANK,'quantity':3},{'item':STICK,'quantity':2}],axe=True)
    elif name in ('older-feasible-small-arrivals','impossible-large-useful-small'):
        b=h.create_stage(4,'PlayerB');result['b']=b;a=h.create_stage(1)
    else:a=h.create_stage()
    result['a']=a;result['before']=h.physical();result['startingWorldHashes']=starting_checkpoint(h,folder)
    if name in ('older-feasible-small-arrivals','impossible-large-useful-small'):
        # Saving the initial world is outside the declared <=40-tick owner-poll window.
        # Request the first stable ticket afterwards, while the actual stock is still two.
        polls=[] if name=='older-feasible-small-arrivals' else None
        waiting=h.stage(b,player='PlayerB',polls=polls);result['olderWaiting']=waiting
        if polls is not None:result['ownerPollTicks']=polls
        require(waiting['phase']=='WAITING' and not waiting['moves'] and not active_claims(h.ready()),'Infeasible older demand held a claim')
    if name=='five-logs-three-and-four-external-two':
        b=h.create_stage(4,'PlayerB');result['b']=b
        result['aHeld']=h.stage(a,until='HELD');h.observed_stock(SRC,{LOG:2})
        result['bObservedTwo']=h.stage(b,player='PlayerB');require(result['bObservedTwo']['phase']=='WAITING' and not result['bObservedTwo']['moves'],'B did not independently wait on two actual logs')
        result['twoLogNative']=h.physical();h.console(['item replace block '+coord(SRC)+' container.0 with '+LOG+' 4']);h.observed_stock(SRC,{LOG:4})
        result['external'].append({'kind':'actual-addition','item':LOG,'delta':2,'native':h.physical()});result['expectedLogs']=7
        result['bHeld']=h.stage(b,player='PlayerB',until='HELD');h.observed_stock(SRC,{})
        result['aReturn']=h.stage(a,'RETURN',until='RETURNED');result['bReturn']=h.stage(b,'RETURN','PlayerB',until='RETURNED')
        h.observed_stock(SRC,{LOG:7});h.observed_stock(DA,{});h.observed_stock(DB,{})
    elif name=='older-feasible-small-arrivals':
        h.console(['item replace block '+coord(SRC)+' container.0 with '+LOG+' 4']);h.observed_stock(SRC,{LOG:4});result['expectedLogs']=4
        result['external'].append({'kind':'actual-addition','item':LOG,'delta':2,'native':h.physical()})
        result['arrivals']=[h.claim_rpc('arrival',job=a) for _ in range(6)]
        require(all(r['claim'].get('group') is None for r in result['arrivals']),'New small demand bypassed older feasible demand')
        result['bHeld']=h.stage(b,player='PlayerB',until='HELD',polls=result['ownerPollTicks']);h.observed_stock(SRC,{})
        result['service']=service(h.claim_rpc('status','PlayerB')['fair'])
    elif name=='impossible-large-useful-small':
        result['aHeld']=h.stage(a,until='HELD');h.observed_stock(SRC,{LOG:1});require(not h.state()['frames'][b]['moves'],'Impossible four moved physical stock')
    elif name=='complementary-inputs-atomic':
        result['missingSecondary']=h.stage(a);require(not result['missingSecondary']['moves'] and not active_claims(h.ready()),'Partial complementary ingredient claim')
        h.console(['item replace block '+coord(SRC)+' container.1 with '+STICK+' 2']);h.observed_stock(SRC,{PLANK:3,STICK:2})
        result['aHeld']=h.stage(a,until='HELD');h.observed_stock(SRC,{})
        result['return']=h.stage(a,'RETURN',until='RETURNED');h.observed_stock(SRC,{PLANK:3,STICK:2})
    elif name=='complementary-private-custody-residence-bound':
        result['waitingA']=h.stage(a);result['waitingB']=h.stage(b,player='PlayerB');require(not active_claims(h.ready()),'Complementary private custody held storage claims')
        h.quiet(610);result['blockedA']=h.stage(a,until='BLOCKED');result['blockedB']=h.stage(b,player='PlayerB',until='BLOCKED')
        require(all(v['blocker']=='ACTION_TIMEOUT' and not v['moves'] and not v['acquired'] for v in (result['blockedA'],result['blockedB'])),'Residence bound was reset or foreign custody was spent')
    elif name in ('initial-full-zero-effect','source-destination-alias'):
        result['blocked']=h.state()['frames'].get(a) or h.claim_rpc('private-view',job=a)['view'];require(result['blocked']['phase']=='BLOCKED' and not result['blocked']['moves'],'Stable preflight was not zero-effect')
    elif name=='dynamic-fill-before-withdraw':
        first=h.stage(a);result['grantWindow']=h.ready()
        if not active_claims(result['grantWindow'],a):raise Inconclusive('Required actual pre-effect claim window missed')
        require(not first['moves'],'Already moved before fill');h.full(DA);result['blocked']=h.stage(a,until='BLOCKED');require(not result['blocked']['moves'],'Dynamic full destination withdrew stock')
    elif name=='slot-stack-swap-after-claim':
        first=h.stage(a);result['grantWindow']=h.ready()
        if not active_claims(result['grantWindow'],a) or first['moves']:raise Inconclusive('Required actual slot-swap pre-effect window missed')
        h.console(['item replace block '+coord(SRC)+' container.0 with '+LOG+' 2','item replace block '+coord(SRC)+' container.1 with '+LOG+' 3']);h.observed_stock(SRC,{LOG:5})
        result['blocked']=h.stage(a,until='BLOCKED');require(not result['blocked']['moves'],'Captured slot swap moved stock')
        if result['blocked']['usage'].get('ATTEMPTED_EFFECTS',0)!=1:raise Inconclusive('Slot swap missed the live native mutation recheck window')
    elif name=='navigation-no-storage-claim':
        h.console(['tp '+h.entities['PlayerA']+' 80.5 208 72.5']);result['unreachable']=h.stage(a)
        require(result['unreachable']['phase']=='WAITING' and not active_claims(h.ready()),'Storage held during navigation')
        h.console(['tp '+h.entities['PlayerA']+' 73.5 208 69.5']);h.quiet(3);result['aHeld']=h.stage(a,until='HELD')
    elif name=='chest-replacement':
        h.console(['setblock '+coord(SRC)+' minecraft:air','setblock '+coord(SRC)+' minecraft:chest','item replace block '+coord(SRC)+' container.0 with '+LOG+' 5'])
        result['blocked']=h.stage(a,until='BLOCKED');require(not result['blocked']['moves'],'Replaced chest was rebound')
    elif name=='double-chest-split':
        h.console(['setblock 74 208 70 minecraft:chest[facing=east,type=right]','setblock '+coord(SRC)+' minecraft:chest[facing=east,type=left]'])
        h.wait(lambda:'type=left' in h.block(SRC) or 'type=right' in h.block(SRC),'Actual double chest state',20);result['doubleChest']=h.physical()
        result['blocked']=h.stage(a,until='BLOCKED');require(not result['blocked']['moves'],'Unsupported chest alias mutated')
        h.console(['setblock 74 208 70 minecraft:air']);h.wait(lambda:'type=single' in h.block(SRC),'Actual split chest state',20);result['splitChest']=h.physical()
        require(not h.stage(a)['moves'],'Split rebound a retired staging frame')
    elif name=='component-stack-swap':
        h.console(['data modify block '+coord(SRC)+' Items[0].components set value {"minecraft:custom_name":"A08 external component"}'])
        h.wait(lambda:'components' in h.command('PlayerB','/data get block '+coord(SRC),'has the following block data'),'Actual changed native components',20)
        result['blocked']=h.stage(a,until='BLOCKED');require(not result['blocked']['moves'],'Changed components were accepted')
    elif name=='cross-principal-denial':
        result['denied']=h.claim_rpc('private-view','PlayerB',expected='DENIED',job=a);result['aHeld']=h.stage(a,until='HELD')
    elif name=='external-mutation-replan-bound':
        # Each independent native revision is observed before the next external change; no inferred replan count.
        h.console(['item replace block '+coord(SRC)+' container.0 with '+LOG+' 2']);h.observed_stock(SRC,{LOG:2})
        result['changes']=[]
        for i in range(10):
            h.console(['item replace block '+coord(SRC)+' container.0 with '+LOG+' '+str(1+i%2)])
            h.observed_stock(SRC,{LOG:1+i%2});v=h.stage(a);result['changes'].append(v)
            if v['phase']=='BLOCKED':break
        require(result['changes'][-1]['phase']=='BLOCKED' and not result['changes'][-1]['moves'],'Replan limit was not enforced')
        result['expectedLogs']=h.container(SRC,LOG)
    elif name=='stale-epoch-release':
        h.stage(a);claim=active_claims(h.ready(),a)
        if not claim:raise Inconclusive('Native grant window missed')
        old=claim[0]
        h.save_checkpoint();before=native_process(h);result['restart']=h.restart(hard=True)
        result['oldReference']=h.claim_rpc('old-reference',ref={k:old[k] for k in ('id','epoch','generation')});require(result['oldReference']['validate']['code']=='STALE' and result['oldReference']['release']['code']=='STALE','Old epoch was usable')
        result['beforeJvm']=before
    else:
        result['aHeld']=h.stage(a,until='HELD');h.observed_stock(SRC,{LOG:2})
        if name=='lawful-return-new-action':
            result['return']=h.stage(a,'RETURN',until='RETURNED');h.observed_stock(SRC,{LOG:5});h.ready();h.claim_rpc('cancel',job=a)
            result['cancelled']=h.wait(lambda:(j if (j:=h.job(a))['state']=='CANCELLED' else None),'Original returned staging Job cancelled without credit',30)
        elif name=='dynamic-fill-after-withdraw-custody':
            h.full(DA);h.ready();result['waiting']=h.claim_rpc('inspect',job=a)['view'];require(result['waiting']['acquired']=={LOG:3} and len(result['waiting']['moves'])==1 and result['waiting']['blocker']=='TARGET_UNAVAILABLE','Dynamic fill lost custody')
            result['return']=h.stage(a,'RETURN',until='RETURNED');h.observed_stock(SRC,{LOG:5})
        elif name=='full-return-retains-custody':
            h.full(SRC,64);result['expectedLogs']=67;result['external'].append({'kind':'actual-addition','item':LOG,'delta':62,'native':h.physical()})
            result['waiting']=h.stage(a,'RETURN');require(result['waiting']['acquired']=={LOG:3} and len(result['waiting']['moves'])==1,'Full return lost private custody')
        elif name=='revoked-return-retains-custody':
            h.vanilla('PlayerB','/gamerule minecraft:mob_griefing false');result['blocked']=h.stage(a,'RETURN',until='BLOCKED')
            require(result['blocked']['acquired']=={LOG:3} and len(result['blocked']['moves'])==1,'Revoked return lost custody');h.vanilla('PlayerB','/gamerule minecraft:mob_griefing true')
        elif name=='crash-after-withdraw-scoped-fence-C-progress':
            h.ready();h.claim_rpc('report',job=a);original=h.job(a);result['originalBefore']=original
            if original['state']!='ACTIVE' or original['attempts'][-1]['effects']!=1:raise Inconclusive('Original active saved-withdrawal cut missed')
            h.save_checkpoint();before=native_process(h);clients={p:read(h.root/'clients'/p/'process.json') for p in h.players}
            h.stop('server',hard=True);killed=folder/'killed-world';shutil.copytree(h.root/'server/game/world',killed,ignore=shutil.ignore_patterns('session.lock'));result['killedWorldHashes']=manifest(killed)
            for p in h.players:h.wait(lambda:not h.action(p,'snapshot')['play'],'Actual A08 crash disconnect '+p,45)
            h.activated=int(time.time()*1000);h.start('server');reconnect_players(h,list(h.players));h.console(['tick unfreeze','tick rate 20'],lifecycle=True)
            after=native_process(h);require((before['hostJvmPid'],before['startTicks'])!=(after['hostJvmPid'],after['startTicks']),'A08 did not replace the JVM')
            require(all(read(h.root/'clients'/p/'process.json')==v for p,v in clients.items()),'A08 replaced clients at the crash')
            result['restart']={'before':before,'after':after,'sameClients':True}
            original=h.wait(lambda:(j if (j:=h.job(a))['state']=='INTERRUPTED' and j['attempts'][-1]['uncertain'] else None),'Original interrupted scoped staging owner',45)
            result['originalAfter']=original;result['fence']=h.claim_rpc('fence',job=a,source=SRC);require(result['fence']['fenced'],'Original effect fence disappeared')
            cs=[326,208,66];cd=[328,208,66];h.console(['gamemode spectator PlayerB','tp PlayerB 329.5 208 69.5'])
            h.quiet(20);h.console(['fill 324 207 64 332 207 70 minecraft:stone','tp '+h.entities['PlayerB']+' 327.5 208 66.5',
                'setblock '+coord(cs)+' minecraft:chest','setblock '+coord(cd)+' minecraft:chest','item replace block '+coord(cs)+' container.0 with '+LOG+' 1'])
            h.watch([SRC,DA,DB,cs,cd],[h.entities['PlayerA'],h.entities['PlayerB']]);h.observed_stock(cs,{LOG:1})
            c=h.create_stage(1,'PlayerB',cs,cd);result['c']=c;result['cHeld']=h.stage(c,player='PlayerB',until='HELD');result['cReturn']=h.stage(c,'RETURN','PlayerB',until='RETURNED')
            h.observed_stock(cs,{LOG:1});h.observed_stock(cd,{});require(h.job(a)==original,'Unrelated work changed the original Job');result['expectedLogs']=6
    return finish_case(h,folder,result)

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--seed-archive',type=Path,required=True);a=p.parse_args()
    root=a.root.resolve();root.mkdir(parents=True,exist_ok=True);h=None
    result={'schema':1,'stage':'A08','status':'NOT_EXECUTED','a08Qualified':False,'plannedCases':list(CASES),'requiredCases':len(CASES),'cases':[],'failures':[],'omittedTasks':0,'omittedTests':0}
    try:
        h=ClaimsHarness(root);h.activated=0;seed=retained_seed(h,a.seed_archive.resolve());h.primary=h.citizens['PlayerA'],h.entities['PlayerA']
        metadata=read(root/'metadata.json')
        print('A08_NATIVE_METADATA '+json.dumps({k:metadata[k] for k in ('sourceCommit','sourceTree','minecraft','loader','topology','dirty','releaseSha256','binaries')}),flush=True)
        for name in CASES:
            try:value=one(h,seed,name)
            except Exception as failure:
                value={'id':name,'status':'INCONCLUSIVE_REQUIRED_NATIVE_WINDOW' if isinstance(failure,(Inconclusive,TimeoutError)) else 'FAIL','reason':str(failure),'trace':traceback.format_exc()}
                target=root/'failures'/name;target.mkdir(parents=True,exist_ok=True)
                try:
                    value['runtime']=h.state();value['native']=h.physical();h.save_checkpoint();h.stop('server',hard=True)
                    world=target/'terminal-world';shutil.copytree(h.root/'server/game/world',world,ignore=shutil.ignore_patterns('session.lock'))
                    value['failureWorldHashes']=manifest(world)
                except Exception as evidence:value['evidenceError']=str(evidence)
                atomic(target/'result.json',value);result['failures'].append(name)
            result['cases'].append(value);atomic(root/'a08-results.json',result);print('A08_CASE '+json.dumps({'id':name,'status':value['status'],'reason':value.get('reason')}),flush=True)
        result['status']='PASS_NATIVE_CANDIDATE_PENDING_REVIEW' if not result['failures'] else 'FAIL_REQUIRED_A08_CASES'
    except Exception as failure:
        result.update(status='FAIL_A08_PREREQUISITE',reason=str(failure),trace=traceback.format_exc())
        print('A08_PREREQUISITE_FAILURE '+json.dumps({'reason':result['reason'],'trace':result['trace']}),flush=True)
    finally:
        attempted={r['id'] for r in result['cases']};result['cases'] += [{'id':n,'status':'NOT_EXECUTED_REQUIRED'} for n in CASES if n not in attempted]
        atomic(root/'a08-results.json',result)
        if h:h.finish()
    if result['status']!='PASS_NATIVE_CANDIDATE_PENDING_REVIEW':raise SystemExit(1)

if __name__=='__main__':main()
