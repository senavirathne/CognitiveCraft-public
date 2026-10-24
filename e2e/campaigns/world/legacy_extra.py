"""Legacy group and safety contracts through ordinary real-client commands/interactions."""
import math
import re

from generate import point
from oracle import custody,key
from suite import atomic,require
from generalization_support import Inconclusive


def coord(p): return ' '.join(map(str,p))


def providers(h):
    if not getattr(h,'legacy_provider_view',None):
        h.legacy_provider_view=h.command('PlayerA','/aivillage providers','No LLM providers enabled')
    return h.legacy_provider_view


def status(h,name):
    value=h.command('PlayerA','/aivillage status '+name,re.escape(name)+r' \|')
    h.event('legacy-controller-status',{'name':name,'status':value,'gameTick':h.snapshot()['gameTick']})
    return value


def subject(h,c,name,where,home,trader=False):
    existing={a['uuid'] for a in h.snapshot()['actors']}
    native=[where[0]+.5,208,where[2]+.5]
    data='{PersistenceRequired:1b,Tags:["campaign_world_subject"]'
    if trader: data+=',VillagerData:{level:2,profession:"minecraft:toolsmith",type:"minecraft:plains"}'
    data+='}'
    h.console(['tp PlayerA '+coord([native[0],208,native[2]+1.5]),
               'summon minecraft:villager '+coord(native)+' '+data])
    actor=h.wait(lambda:next((a for a in h.snapshot()['actors'] if a['uuid'] not in existing),None),
                 'Actual fresh legacy subject UUID',25)
    require(actor['alive'] and not actor['baby'] and not actor['noAI'],'Legacy subject must retain normal live adult native AI')
    h.command('PlayerA','/aivillage enroll '+name,'Enrolled '+re.escape(name))
    if not hasattr(h,'legacy_subjects'): h.legacy_subjects={}
    h.legacy_subjects[name]=actor['uuid']
    h.wait(lambda:any(a['uuid']==actor['uuid'] and a['name']==name for a in h.snapshot()['actors']),
           'Legacy name belongs to the actual intended native subject',25)
    h.command('PlayerA','/aivillage home '+name+' '+coord(home),'Work area set')
    return actor['uuid']


def ledger(c,before,after):
    harvested=sum('age=7' in before['blocks'].get(key(p),'') and 'age=7' not in after['blocks'].get(key(p),'')
                  for p in c['positions'])
    initial=custody(before,'minecraft:wheat'); final=custody(after,'minecraft:wheat')
    return harvested,initial,final


def legacy_group(h,c):
    h.reset_case(c)
    provider_view=providers(h)
    # These are idle fixture actors. Tested farmer effects have not been requested yet.
    h.console(['tp '+a['uuid']+' 65.5 208 76.5' for a in h.snapshot()['actors']])
    names=[f'Group_{c["seed"]}_{c["index"]}_{letter}' for letter in ('A','B')]
    h.stock_fixture({},c['secondaryDestination'],c['container'])
    entities=[]
    try:
        for name,where,home in zip(names,c['groupActors'],(c['destination'],c['secondaryDestination'])):
            entities.append(subject(h,c,name,where,home))
        h.console(['tp PlayerA 74.5 208 69.5']); h.quiet(3)
        before=h.snapshot(); calls=h.model_calls(); atomic(h.case_dir/'before.json',before)
        h.attempted_case=True
        answer=h.command('PlayerA','/aivillage village',r'Shared food goal assigned to \d+ villagers')
        assigned=int(re.search(r'assigned to (\d+)',answer).group(1)); h.actions_active=True
        def active():
            views=[status(h,n) for n in names]
            return views if all('working' in v or 'planning' in v for v in views) else None
        activated=h.wait(active,'Both real group workers active',45)
        h.executed_case=True
        def finished():
            views=[status(h,n) for n in names]
            return views if all(' | idle |' in v for v in views) else None
        terminal=h.wait(finished,'Bounded group controller termination',220)
        plans=[h.command('PlayerA','/aivillage memory '+name,'Started ') for name in names]
        h.quiet(20); after=h.snapshot(); atomic(h.case_dir/'after.json',after)
        harvested,initial,final=ledger(c,before,after)
        props={'groupAdmission':assigned==c['groupSize'],'nativeIdentity':all(any(a['uuid']==eid and a['name']==name
            for a in after['actors']) for eid,name in zip(entities,names)),
            'harvest':harvested==c['quantity'],'conservation':sum(final.values())==sum(initial.values())+harvested,
            'noInference':h.model_calls()==calls,'groupTerminal':all(' | idle |' in v for v in terminal)}
        h.screenshot('PlayerA','legacy-group-food',chat=True)
        return dict(status='PASS' if all(props.values()) else 'FAIL_GAMEPLAY',genuineGameplay=True,gameplayAttempted=True,
            properties=props,command='/aivillage village',feedback=answer,actors=entities,activated=activated,
            clientResult=terminal,controllerPlans=plans,providers=provider_view,fullFoodGoalCompletion=False,
            accounting={'harvestedPlants':harvested,'custodyBefore':initial,'custodyAfter':final},
            firstDivergence=next(({'property':k,'observed':False} for k,v in props.items() if not v),None),
            limitations=['Tests group admission, physical acquisition, legacy reservations and bounded termination; no bread crafting or full food-goal completion claim'])
    finally:
        h.cleanup_legacy(); h.actions_active=False


def native_health(h,eid):
    text=h.command('PlayerA','/data get entity '+eid+' Health',r'entity data:')
    match=re.search(r'entity data:\s*(-?\d+(?:\.\d+)?)f?',text)
    if not match: raise Inconclusive('Vanilla health observation not parseable: '+text)
    return float(match.group(1))


def legacy_safety(h,c):
    h.reset_case(c)
    provider_view=providers(h)
    h.console(['tp '+a['uuid']+' 65.5 208 76.5' for a in h.snapshot()['actors']])
    name=f'Safety_{c["seed"]}_{c["index"]}'; mode=c['safetyMode']
    try:
        eid=subject(h,c,name,c['actor'],c['destination'],trader=mode=='trade-active')
        first_health=native_health(h,eid); hazard=None; activated=None
        if mode=='low-health-admission':
            h.command('PlayerA','/damage '+eid+' 15 minecraft:generic',r'(?i)damage')
            h.quiet(15); hazard={'kind':'native-damage','health':native_health(h,eid)}
            require(0<hazard['health']<6,'Low-health admission fixture must be genuinely alive below 30 percent')
        before=h.snapshot(); atomic(h.case_dir/'before.json',before); calls=h.model_calls()
        h.attempted_case=True
        if mode=='low-health-admission':
            answer=h.command('PlayerA','/aivillage food '+name,r'unsafe|sleeping|trading')
            h.executed_case=True
        else:
            answer=h.command('PlayerA','/aivillage food '+name,'food: '+re.escape(name)); h.actions_active=True
            activated=h.wait(lambda:(v if 'working' in (v:=status(h,name)) else None),
                'Real active legacy plan before native safety disturbance',45)
            h.executed_case=True
            current=next(a for a in h.snapshot()['actors'] if a['uuid']==eid)
            if math.dist(h.client_snapshot('PlayerA')['pos'],current['pos'])>3:
                raise Inconclusive('Actual subject left ordinary interaction reach before hazard; no forced active teleport')
            if mode=='damage-active':
                h.action('PlayerA','attack',uuid=eid)
                health=h.wait(lambda:(v if (v:=native_health(h,eid))<first_health else None),
                              'Real connected-player attack changed native health',25)
                hazard={'kind':'native-client-attack','healthBefore':first_health,'healthAfter':health}
            else:
                h.action('PlayerA','interact-entity',uuid=eid)
                menu=h.wait(lambda:(v if (v:=h.client_snapshot('PlayerA'))['menu']>0 and
                    'merchant' in v['screen'].lower() else None),'Actual native trading menu opened',30)
                hazard={'kind':'actual-trade','clientMenu':menu}
                # Keep real trading open across several ordinary controller ticks.
                h.quiet(20); h.action('PlayerA','close-menu')
        terminal=h.wait(lambda:(v if ' | idle |' in (v:=status(h,name)) else None),
                        'Legacy work safely idle after verified hazard',45)
        memory=None
        if mode!='low-health-admission':
            memory=h.command('PlayerA','/aivillage memory '+name,'safety_interrupt')
        fence=h.snapshot(); h.quiet(30); after=h.snapshot(); atomic(h.case_dir/'after.json',after)
        props={'hazardObserved':bool(hazard),'safetyInterruption':' | idle |' in terminal,
               'noLaterEffects':fence['containers']==after['containers'] and
                   all(fence['blocks'].get(key(p))==after['blocks'].get(key(p)) for p in c['positions']),
               'noInference':h.model_calls()==calls}
        harvested,initial,final=ledger(c,before,after)
        props['conservation']=sum(final.values())==sum(initial.values())+harvested
        if mode!='low-health-admission': props['safetyReason']='safety_interrupt' in memory
        h.screenshot('PlayerA','legacy-safety-'+mode,chat=True)
        good=all(props.values())
        return dict(status=('EXPECTED_SAFE_REFUSAL' if mode=='low-health-admission' else 'PASS') if good else 'FAIL_SAFETY',
            genuineGameplay=True,gameplayAttempted=True,properties=props,command='/aivillage food '+name,
            feedback=answer,actor=eid,activated=activated,hazard=hazard,clientResult=terminal,memory=memory,
            providers=provider_view,
            accounting={'harvestedPlants':harvested,'custodyBefore':initial,'custodyAfter':final},
            firstDivergence=next(({'property':k,'observed':False} for k,v in props.items() if not v),None))
    finally:
        h.cleanup_legacy(); h.actions_active=False
