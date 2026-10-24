"""Additional reusable physical gameplay adapters; all tested effects enter through real clients."""
import contextlib
import json
import math
import re
import time

from generate import point
from oracle import custody,key
from suite import UUID_PATTERN,DENIED_PATTERN,require,atomic
from generalization_support import Inconclusive


def coord(p): return ' '.join(map(str,p))


def legacy(h,c):
    """Exercise legacy FarmPort through its unchanged deterministic public food controller."""
    h.reset_case(c)
    name=f'Farmer{c["seed"]}_{c["index"]}'
    base=h.entities['PlayerA']; subject=None
    existing={a['uuid'] for a in h.snapshot()['actors']}
    h.console(['tp '+base+' 83.5 208 66.5',
        'summon minecraft:villager '+coord([c['actor'][0]+.5,208,c['actor'][2]+.5])+
        ' {PersistenceRequired:1b,Tags:["campaign_world_subject"]}'])
    actor=h.wait(lambda:next((a for a in h.snapshot()['actors'] if a['uuid'] not in existing),None),'Fresh native legacy subject',20)
    subject=actor['uuid']; mode=c['legacyMode']; q=c['quantity']
    h.attempted_case=True
    h.command('PlayerA','/aivillage enroll '+name,'Enrolled '+name)
    h.command('PlayerA','/aivillage home '+name+' '+coord(c['destination']),'Work area set')
    # Fixture construction is still before the food job; it never supplies a tested active effect.
    lines=[]
    for p in c['positions']:
        if mode not in ('harvest-replant','cancel'):
            lines+=['setblock '+coord(p)+' minecraft:air']
            if mode!='plant': lines+=['setblock '+coord([p[0],207,p[2]])+' minecraft:stone']
    inventory=[]
    if mode=='harvest-replant': inventory=[{'id':'minecraft:wheat_seeds','count':q}]
    if mode=='plant': inventory=[{'id':'minecraft:wheat_seeds','count':q}]
    if mode=='delivery': inventory=[{'id':'minecraft:bread','count':q}]
    if mode=='pickup':
        for i in range(q):
            target=[c['actor'][0]+2.6,208.1,c['actor'][2]+.5+i*.15]
            lines.append('summon minecraft:item '+coord(target)+' {PickupDelay:0s,Item:{id:"minecraft:wheat",count:1}}')
    lines+=['data merge entity '+subject+' {Inventory:'+json.dumps(inventory,separators=(',',':'))+'}']
    h.console(lines); h.quiet(2)
    before=h.snapshot(); atomic(h.case_dir/'before.json',before)
    def native(): return next(a for a in h.snapshot()['actors'] if a['uuid']==subject)
    if mode=='pickup':
        require(sum(d['count'] for d in before['drops'] if d['item']=='minecraft:wheat')==q,
                'Loose pickup fixture was consumed before gameplay admission')
    try:
        if mode=='auto-release':
            h.command('PlayerA','/aivillage auto '+name+' true','auto=true')
        else: h.command('PlayerA','/aivillage food '+name,'food: '+name)
        h.actions_active=True; h.executed_case=True
        def status():
            value=h.command('PlayerA','/aivillage status '+name,re.escape(name)+r' \|')
            h.event('legacy-controller-status',{'name':name,'status':value,'gameTick':h.snapshot()['gameTick']})
            return value
        boundary=None
        if mode=='legacy-death':
            boundary=h.wait(lambda:(v if 'working' in (v:=status()) or 'planning' in v else None),
                            'Actual legacy work admitted before native death',50)
            h.command('PlayerA','/kill '+subject,'Killed')
            terminal=h.wait(lambda:(v if ' | dead |' in (v:=status()) else None),'Legacy controller observed real death',40)
            h.quiet(30); after=h.snapshot(); atomic(h.case_dir/'after.json',after)
            props={'legacyDeath':not any(a['uuid']==subject and a['alive'] for a in after['actors']),
                   'truthfulLifecycle':' | dead |' in terminal}
            h.screenshot('PlayerA','legacy-native-death',chat=True)
            return dict(status='PASS' if all(props.values()) else 'FAIL_GAMEPLAY',genuineGameplay=True,
                        gameplayAttempted=True,properties=props,clientResult=terminal,boundary=boundary)
        if mode in ('cancel','auto-release'):
            boundary=h.wait(lambda:(v if 'working' in (v:=status()) or 'planning' in v else None),
                            'Actual legacy controller became active',50)
            h.command('PlayerA','/aivillage stop '+name,'stop: '+name)
        terminal=h.wait(lambda:(v if ' | idle |' in (v:=status()) else None),'Bounded real legacy food outcome',210)
        h.actions_active=False; h.quiet(12); after=h.snapshot(); atomic(h.case_dir/'after.json',after)
        props={'legacyIdentity':native()['name']==name,'legacyTerminal':' | idle |' in terminal}
        harvested=sum('age=7' in before['blocks'].get(key(p),'') and 'age=7' not in after['blocks'].get(key(p),'') for p in c['positions'])
        wheat_before=sum(custody(before,'minecraft:wheat').values()); wheat_after=sum(custody(after,'minecraft:wheat').values())
        props['conservation']=wheat_after==wheat_before+harvested
        if mode=='harvest-replant':
            props['harvest']=harvested==q
            props['replant']=all('minecraft:wheat' in after['blocks'].get(key(p),'') and 'age=0' in after['blocks'].get(key(p),'') for p in c['positions'])
        if mode=='plant':
            planted=sum('age=0' in after['blocks'].get(key(p),'') for p in c['positions'])
            props['plant']=planted==q and native()['inventory'].get('minecraft:wheat_seeds',0)==0
        if mode=='pickup': props['pickup']=native()['inventory'].get('minecraft:wheat',0)==q
        if mode=='delivery':
            props['delivery']=h.container(c['destination'],'minecraft:bread')==q and native()['inventory'].get('minecraft:bread',0)==0
            props['breadConservation']=sum(custody(before,'minecraft:bread').values())==sum(custody(after,'minecraft:bread').values())
        if mode in ('cancel','auto-release'):
            fixed=native()['inventory'].copy(); boxes=after['containers']; h.quiet(30)
            props['cancellation']=native()['inventory']==fixed and h.snapshot()['containers']==boxes
        h.screenshot('PlayerA','legacy-'+mode,chat=True)
        status=('PASS' if mode in ('cancel','auto-release') else 'EXPECTED_SAFE_REFUSAL') if all(props.values()) else 'FAIL_GAMEPLAY'
        return dict(status=status,genuineGameplay=True,gameplayAttempted=True,
            properties=props,legacyMode=mode,controllerBoundary=boundary,clientResult=terminal,
            fullFoodGoalCompletion=False,
            accounting={'harvestedPlants':harvested,'wheatBefore':wheat_before,'wheatAfter':wheat_after},
            firstDivergence=next(({'property':k,'observed':False} for k,v in props.items() if not v),None),
            limitations=['No bread crafting completion is claimed; these cases test the selected physical effect and truthful bounded controller termination'])
    finally:
        # A normal player stop/release is also required cleanup; no active fixture reset.
        with contextlib.suppress(Exception): h.command('PlayerA','/aivillage stop '+name,'stop: '+name)
        with contextlib.suppress(Exception): h.command('PlayerA','/aivillage release '+name,'release: '+name)
        h.actions_active=False


def death(h,c):
    """Fresh ordinary citizens die in the real engine; a terminal identity never transfers by name."""
    from suite import reconnect_players
    # Twelve fresh identities plus the base stay within the public roster's sixteen-result page.
    # A new cohort gets an isolated native save, while each restart stays within its current save.
    if getattr(h,'death_cohort',None)!=c['seed']:
        h.cleanup_world(); h.case=None
        h.competition_citizen=None; h.competition_entity=None; h.foreign_citizen=None; h.foreign_entity=None
        h.actor_pool=None
        h.fresh(two=False); h.enroll(); h.hashes={}
        h.command('PlayerA','/aivillage kernel inference false','admission=false')
        h.death_cohort=c['seed']; h.case=c; h.samples=[]; h.last_sample=None
    h.reset_case(c)
    base_citizen,base_entity=h.citizens['PlayerA'],h.entities['PlayerA']
    existing={a['uuid'] for a in h.snapshot()['actors']}
    h.console(['tp '+base_entity+' 83.5 208 66.5',
        'summon minecraft:villager '+coord([c['actor'][0]+.5,208,c['actor'][2]+.5])+
        ' {PersistenceRequired:1b,Tags:["campaign_world_subject"]}'])
    h.wait(lambda:any(a['uuid'] not in existing for a in h.snapshot()['actors']),'Fresh live lifecycle subject',25)
    try:
        h.attempted_case=True; h.enroll(name=c['citizenName'])
        citizen,entity=h.citizens['PlayerA'],h.entities['PlayerA']
        def address(): return h.snapshot().get('knownPlayers',{}).get('PlayerA',{}).get('lifecycle',{}).get(citizen)
        before=h.wait(lambda:(v if (v:=address()) and v['availability']=='LOADED' else None),'Verified original citizen binding',30)
        atomic(h.case_dir/'citizen-before.json',before)
        h.command('PlayerA','/kill '+entity,'Killed'); h.executed_case=True
        dead=h.wait(lambda:(v if (v:=address()) and v['availability']=='UNAVAILABLE' and v['lifecycle']['event']=='DEATH'
                          and v['lifecycle']['acknowledged']==7 else None),'Native DEATH and completed owner fencing',60)
        probe='/aivillage kernel harvest '+citizen+' 1 '+coord(c['positions'][0]+c['positions'][0]+c['destination'])
        refusal=h.command('PlayerA',probe,'accepted=false|unavailable|UNAVAILABLE|AUTHORITY_DENIED')
        checkpoint=None; successor=None
        if c['deathMode']=='death-restart':
            checkpoint=h.save_checkpoint(); h.stop('server',hard=True)
            h.wait(lambda:not h.action('PlayerA','snapshot')['play'],'Actual death-save crash disconnect',40)
            h.start('server'); reconnect_players(h,['PlayerA'])
            dead=h.wait(lambda:(v if (v:=address()) and v['lifecycle']['event']=='DEATH' else None),'Same terminal identity recovered',70)
        if c['deathMode']=='same-name-successor':
            old={a['uuid'] for a in h.snapshot()['actors']}
            h.command('PlayerA','/summon minecraft:villager '+coord([c['actor'][0]+.5,208,c['actor'][2]+.5])+
                ' {PersistenceRequired:1b,Tags:["campaign_world_subject"],CustomName:"'+c['citizenName']+'"}','Summoned')
            successor=h.wait(lambda:next((a for a in h.snapshot()['actors'] if a['uuid'] not in old and a['alive']),None),
                             'Actual unrelated same-name native successor',25)
        h.quiet(25); final=address(); atomic(h.case_dir/'citizen-after.json',final)
        props={'identity':final['actor']==before['actor'] and final['owner']==before['owner'],
               'terminalFencing':final['availability']=='UNAVAILABLE' and final['lifecycle']['event']=='DEATH',
               'noRevival':not any(a['uuid']==entity and a['alive'] for a in h.snapshot()['actors']),
               'scopeRefusal':bool(refusal)}
        if successor: props['successorIsolation']=successor['uuid']!=entity and final['actor']['entityId']==entity
        if checkpoint: props['recovery']=bool(checkpoint['files']) and props['terminalFencing']
        h.screenshot('PlayerA','terminal-citizen-'+c['deathMode'],chat=True)
        return dict(status='PASS' if all(props.values()) else 'FAIL_SAFETY',genuineGameplay=True,gameplayAttempted=True,
            properties=props,citizen=citizen,entity=entity,dead=dead,successor=successor,checkpoint=checkpoint,feedback=refusal)
    finally:
        h.citizens['PlayerA']=base_citizen; h.entities['PlayerA']=base_entity


def foreign_worker(h,c):
    """Admit B's own wheat method once through real gameplay; never copy A's private knowledge."""
    if getattr(h,'foreign_admission_attempted',False) and not getattr(h,'foreign_method',None):
        raise Inconclusive('Original guest method admission failed; no silent model retry')
    h.start('PlayerB')
    if getattr(h,'foreign_method',None): return
    h.foreign_admission_attempted=True
    h.console(['op PlayerB','tp PlayerB 82.5 208 69.5',
        'summon minecraft:villager 82.5 208 66.5 {PersistenceRequired:1b,Tags:["campaign_world_foreign"]}'])
    h.enroll('PlayerB','Guest')
    h.foreign_citizen=h.citizens['PlayerB']; h.foreign_entity=h.entities['PlayerB']
    h.stock_fixture({},c['secondaryDestination'],c['container'])
    previous=(h.case,h.case_dir,h.family); h.case=None
    h.case_dir=h.root/'admissions/foreign-wheat'; h.case_dir.mkdir(parents=True,exist_ok=True)
    h.family='ADMISSION-FOREIGN-WHEAT'
    record={'scope':'PlayerB','status':'INCONCLUSIVE'}
    try:
        h.prepare_backend()
        h.command('PlayerB','/aivillage kernel inference true','admission=true')
        lo=[min(p[k] for p in c['positions']) for k in range(3)]
        hi=[max(p[k] for p in c['positions']) for k in range(3)]
        before=h.snapshot(); calls=h.model_calls(); atomic(h.case_dir/'before.json',before)
        command='/aivillage kernel harvest '+h.foreign_citizen+' 1 '+coord(lo+hi+c['secondaryDestination'])
        answer=h.command('PlayerB',command,'run='+UUID_PATTERN)
        require('accepted=true' in answer,'Guest admission intake refused: '+answer)
        rid=re.search('run=('+UUID_PATTERN+')',answer).group(1); h.actions_active=True
        view=h.finish_run(rid,'PlayerB',admitted=True)
        require(h.model_calls()['generationCalls']>calls['generationCalls'],'Guest method was not genuinely generated')
        require(h.container(c['secondaryDestination'],'minecraft:wheat')==1,'Guest admission lacks physical output')
        h.foreign_method=view['marker']['artifactSha256']
        record.update(status='PASS',command=command,view=view,artifact=h.foreign_method,callsBefore=calls,callsAfter=h.model_calls())
    except Exception as error:
        import traceback
        record.update(status='FAIL_ARCHITECTURAL_GAP',reason=str(error),trace=traceback.format_exc())
        raise Inconclusive('Guest genuine method admission failed: '+str(error)) from error
    finally:
        with contextlib.suppress(Exception): h.command('PlayerB','/aivillage kernel inference false','admission=false')
        with contextlib.suppress(Exception): h.cleanup_world()
        with contextlib.suppress(Exception): h.compose('stop','ollama')
        record['after']=h.snapshot(); atomic(h.case_dir/'result.json',record)
        h.case,h.case_dir,h.family=previous; h.samples=[]; h.last_sample=None
        h.executed_case=False; h.attempted_case=False
    h.reset_case(c)


def authority(h,c):
    """Real competing lease owners and real simultaneous foreign-scope clients."""
    h.reset_case(c); mode=c['authorityMode']
    if mode=='foreign-control':
        h.start('PlayerB'); h.console(['op PlayerB','tp PlayerB 81.5 208 76.5']); h.ensure_client('PlayerB')
        before=h.snapshot(); atomic(h.case_dir/'before.json',before)
        req=h.request_case(c); require(req['accepted'],'A request was not admitted before foreign access test')
        h.active_run=req['run']; h.actions_active=True
        try:
            boundary=h.wait(lambda:(v if (v:=h.run_view('PlayerA',req['run'])) and v.get('phase')=='EXECUTING' else None),
                            'A actually executing while B is simultaneously connected',100)
            h.executed_case=True; denied=[]
            for command in ('name '+h.citizens['PlayerA']+' Intruder','status '+req['run'],'cancel '+req['run']):
                denied.append(h.command('PlayerB','/aivillage kernel '+command,DENIED_PATTERN))
            own=h.snapshot()['knownPlayers']['PlayerA']['lifecycle'][h.citizens['PlayerA']]
            props={'authority':own.get('displayName')!='Intruder','twoPrincipals':h.players['PlayerA']!=h.players['PlayerB'],
                   'privacy':h.citizens['PlayerA'] not in h.suggestions('PlayerB','/aivillage kernel name ')}
            terminal=h.finish_run(req['run'],success=False); h.quiet(10); after=h.snapshot()
            atomic(h.case_dir/'after.json',after); atomic(h.case_dir/'run.json',terminal)
            props['noForeignCancellation']=terminal.get('marker',{}).get('outcome')!='CANCELLED'
            h.screenshot('PlayerB','foreign-authority-refusal',chat=True)
            return dict(status='EXPECTED_SAFE_REFUSAL' if all(props.values()) else 'FAIL_SAFETY',properties=props,
                genuineGameplay=True,gameplayAttempted=True,request=req,foreignFeedback=denied,boundary=boundary)
        finally:
            h.stop('PlayerB')
    base=h.citizens['PlayerA']; entity=h.entities['PlayerA']; cross=mode=='cross-principal'
    try:
        if cross:
            foreign_worker(h,c)
            h.console(['tp '+h.foreign_entity+' 82.5 208 66.5','tp PlayerB 82.5 208 69.5',
                       'data merge entity '+h.foreign_entity+' {Inventory:[]}'])
            h.ensure_client('PlayerB')
            participants=[('PlayerA',base,c['destination']),('PlayerB',h.foreign_citizen,c['secondaryDestination'])]
        else:
            if getattr(h,'actor_pool',None) and len(h.actor_pool)>1:
                h.competition_citizen,h.competition_entity=next((citizen,eid) for citizen,eid in h.actor_pool if citizen!=base)
            if not getattr(h,'competition_citizen',None):
                h.console(['tp '+entity+' 66.5 208 66.5','tp PlayerA 82.5 208 69.5',
                    'summon minecraft:villager 82.5 208 66.5 {PersistenceRequired:1b,Tags:["campaign_world_competitor"]}'])
                h.enroll(name='Competitor')
                h.competition_citizen=h.citizens['PlayerA']; h.competition_entity=h.entities['PlayerA']
                h.citizens['PlayerA']=base; h.entities['PlayerA']=entity
            h.console(['tp '+h.competition_entity+' 82.5 208 66.5','data merge entity '+h.competition_entity+' {Inventory:[]}'])
            participants=[('PlayerA',base,c['destination']),('PlayerA',h.competition_citizen,c['secondaryDestination'])]
        # Queue actor IDs are responsibility anchors; make every eligible owned worker physically accessible.
        for index,(citizen,eid) in enumerate(getattr(h,'actor_pool',[])):
            h.console(['tp '+eid+' '+coord([66.5,208,66.5+index]),'data merge entity '+eid+' {Inventory:[]}'])
        h.console(['tp PlayerA 66.5 208 69.5']); h.stock_fixture({},c['secondaryDestination'],c['container'])
        before=h.snapshot(); atomic(h.case_dir/'before.json',before)
        lo=[min(p[k] for p in c['positions']) for k in range(3)]; hi=[max(p[k] for p in c['positions']) for k in range(3)]
        demand=c['quantity']//2+1; jobs=[]; instructions=[]
        for player,citizen,dst in participants:
            command='/aivillage kernel queue '+citizen+' '+str(demand)+' '+coord(lo+hi+dst)
            h.attempted_case=True; answer=h.command(player,command,'job='+UUID_PATTERN)
            require('accepted=true' in answer,'Real competing job intake refused: '+answer)
            jid=re.search('job=('+UUID_PATTERN+')',answer).group(1)
            jobs.append((player,jid)); instructions.append({'player':player,'command':command,'feedback':answer}); h.actions_active=True
            if len(jobs)==1:
                h.wait(lambda:(v if (v:=h.job_view(player,jid)) and v['state']=='ACTIVE' else None),
                       'First real resource owner active before contention',90)
        overlap=h.snapshot(); atomic(h.case_dir/'overlap.json',overlap); h.executed_case=True
        require(h.job_view(*jobs[0])['state']=='ACTIVE','Second request arrived after first execution ended')
        require(any(l['state']=='ACTIVE' for d in overlap['knownPlayers'].values() for l in d.get('leases',[])),
                'No actual active resource lease observed during contention')
        boundary=h.cancel_job(*jobs[0]) if c['index']%4==0 else None
        def finished():
            values=[h.job_view(player,jid) for player,jid in jobs]
            return values if all(v and v['state'] in ('SUCCEEDED','FAILED','CANCELLED','INTERRUPTED','WAITING') for v in values) else None
        h.wait(finished,'Bounded competing owners reached completed or explicit waiting states',180)
        h.quiet(12); after=h.snapshot(); views=[h.job_view(player,jid) for player,jid in jobs]
        if any(v['state']=='ACTIVE' for v in views): raise Inconclusive('A waiting contender resumed during final accounting')
        atomic(h.case_dir/'after.json',after); atomic(h.case_dir/'jobs.json',views)
        total=sum(custody(after,'minecraft:wheat').values()); original=sum(custody(before,'minecraft:wheat').values())
        harvested=sum('age=7' in before['blocks'].get(key(p),'') and 'minecraft:air' in after['blocks'].get(key(p),'') for p in c['positions'])
        delivered=sum(after['containers'].get(key(p),{}).get('minecraft:wheat',0) for p in (c['destination'],c['secondaryDestination']))
        props={'conservation':total==original+harvested,'leaseContention':sum(v['fulfilled'] for v in views)<=harvested,
               'noOversubscription':not all(v['fulfilled']==demand for v in views),
               'truthfulCompletion':sum(v['fulfilled'] for v in views)<=delivered<=harvested}
        expected={}; unique=set(); receipt_authority=True
        for (player,_),job in zip(jobs,views):
            allowed={a['actor']['citizenId'] for a in after['knownPlayers'][player]['citizens']['citizens']}
            for attempt in job.get('attempts',[]):
                for receipt in attempt.get('receipts',[]):
                    rid=receipt['receiptId']; actor=receipt['actor']; eid=actor['entityId']
                    receipt_authority &= rid not in unique and actor['citizenId'] in allowed and actor==attempt['worker']
                    unique.add(rid)
                    expected[eid]=expected.get(eid,0)+(receipt['wheat'] if receipt['stage']=='PICKUP' else -receipt['wheat'] if receipt['stage']=='DEPOSIT' else 0)
        props['receiptAuthority']=receipt_authority
        start={a['uuid']:a['inventory'].get('minecraft:wheat',0) for a in before['actors']}
        props['receiptCustody']=all(a['inventory'].get('minecraft:wheat',0)-start.get(a['uuid'],0)==expected.get(a['uuid'],0) for a in after['actors'])
        if boundary: props['cancellation']=h.job_view(*jobs[0])['state']=='CANCELLED'
        if cross: props['twoPrincipals']=h.players['PlayerA']!=h.players['PlayerB']
        h.screenshot('PlayerA','competing-resource-owners',chat=True)
        return dict(status='PASS' if all(props.values()) else 'FAIL_SAFETY',genuineGameplay=True,gameplayAttempted=True,
            properties=props,jobs=jobs,jobViews=views,instructions=instructions,cancellation=boundary,
            accounting={'harvested':harvested,'delivered':delivered,'custodyBefore':original,'custodyAfter':total,'expectedActorCustody':expected})
    finally:
        if cross and 'PlayerB' in h.booted:
            with contextlib.suppress(Exception): h.cleanup_world()
            h.stop('PlayerB')
