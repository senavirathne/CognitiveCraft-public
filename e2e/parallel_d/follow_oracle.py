"""Independent raw feet/tick/session oracle. No collector pass flag is accepted."""
import math,struct
from zipfile import ZipFile
from common import *
from client_oracle import lines,offline,PLAYERS
from mobility_oracle import arena,ACTOR,xyz

def reconcile(root,evidence):
    stdout=[]
    for line in (root/'stdout.log').read_text().splitlines():
        if 'CC_D07_FRAME ' in line:stdout.append(json.loads(line.split('CC_D07_FRAME ',1)[1]))
    rows=lines(evidence/'follow.jsonl');require(stdout and rows==stdout,'missing/mutated raw follow frames vs native stdout');return rows

def sessions(rows,case,world,principal=None):
    require(rows and rows[0]['event']=='ACCEPTED','follow missing native acceptance')
    groups=[];seen=set();timings=[]
    for r in rows:
        s=r['state']
        require(r['case']==case and r['world']==world and r['actor']==ACTOR and r['principal']==rows[0]['principal'] and (principal is None or r['principal']==principal),'follow swapped actor/principal/world')
        require(r['integration_root']is None and r['save_proof']is None,'fabricated follow A/save authority')
        require(r['forced_chunks']==rows[0]['forced_chunks'],'follow forced loaded a chunk')
        require(r['path_nodes']<=32 and r['path_length']<=8+1e-9,'unbounded native follow segment')
        if r['event']=='ACCEPTED':
            require(s['session']not in seen,'follow restarted same session');seen.add(s['session']);groups.append([])
        require(groups and s['session']==groups[-1][0]['state']['session'] if groups[-1:]and groups[-1] else bool(groups),'follow session changed without admission')
        groups[-1].append(r)
    details=[]
    for group in groups:
        first=group[0];initial=first['state'];previous=first['native_feet'];last_tick=None;travel=0;repaths=observations=paused=0;observe_tick=repath_tick=None;terminal=None;crossed=False
        for r in group:
            s=r['state'];require(s['session']==initial['session'] and s['acceptedTick']==initial['acceptedTick'] and s['deadline']<=initial['deadline']<=initial['acceptedTick']+1200,'follow clock or deadline renewed')
            require(s['traveled']<=128 and s['pausedTicks']<=100,'follow cumulative allowance exceeded')
            if terminal is not None:
                require(s==terminal and r['navigation_done'],'terminal follow resumed navigation');continue
            if r['event']not in ('STEP','STOP_EVENT'):continue
            if r['event']=='STEP':
                require(last_tick is None or r['tick']==last_tick+1,'follow missing/duplicate observed tick');last_tick=r['tick']
                if r['step_ns']>0:timings.append(r['step_ns'])
                delta=math.dist(previous,r['native_feet']);require(delta<=.6,'follow teleported actor');travel+=delta;previous=r['native_feet']
                # Immediate adapter refusals can terminate before the pure accounting call.
                if s['reason']not in ('RESOURCE_RESERVED','DIMENSION_CHANGED','IDENTITY_CHANGED'):
                    require(abs(s['traveled']-travel)<1e-6 and xyz(s['feet'])==r['native_feet'],'follow fabricated native travel/feet')
                if s['observations']!=observations:
                    require(s['observations']==observations+1 and (observe_tick is None or r['tick']-observe_tick>=10),'follow oversampled player coordinates');observations+=1;observe_tick=r['tick']
                if s['repaths']!=repaths:
                    require(s['repaths']==repaths+1 and (repath_tick is None or r['tick']-repath_tick>=10),'follow excessive repathing');repaths+=1;repath_tick=r['tick']
                if s['proposal']is not None:require(math.dist(xyz(s['feet']),xyz(s['proposal']))<=8+1e-9,'follow proposal beyond lookahead')
                if not r['connected']:
                    require(r['navigation_done'] and s['reason']in ('DISCONNECTED','PAUSE_LIMIT'),'disconnect not paused within one observed tick');paused+=1
                require(s['pausedTicks']==paused,'follow pause counters reset/forged')
                x,y,z=r['native_feet'];ox,oy,oz=r['origin']
                if abs(x-(ox+8.5))<.8:
                    require(z<oz+3.71 or z>oz+11.29,'follow passed through obstacle');crossed=True
            if s['action']in ('STOP','EXPIRED'):
                require(r['navigation_done'],'terminal follow path still active');terminal=s
        require(terminal is not None,'follow session has no measured terminal')
        details.append({'session':initial['session'],'frames':len(group),'travel_blocks':travel,'repaths':repaths,'observations':observations,'paused_ticks':paused,'reason':terminal['reason'],'crossed_obstacle':crossed,'accepted_tick':initial['acceptedTick'],'deadline':initial['deadline']})
    require(timings,'follow timings absent');timings.sort();n=len(timings);p=lambda q:timings[math.ceil(n*q)-1]
    require(p(.99)<=50000000 and max(timings)<=250000000 and all(0<r['heap_used_bytes']<1100*1024*1024 for r in rows),'follow tick/heap regression')
    return details,{'samples':n,'p50_ns':p(.5),'p95_ns':p(.95),'p99_ns':p(.99),'max_ns':max(timings)}

def tracked(frames):
    before=next((r['state']for r in frames if r['player']=='PlayerD_A'and r['phase']=='BEFORE'),None)
    stopped=next((r['state']for r in frames if r['player']=='PlayerD_A'and r['phase']=='STOPPED'),None)
    require(before is not None and stopped is not None and math.dist(before['pos'],stopped['pos'])>=3,'requester did not actually walk after follow admission')
    actor=next((e for e in stopped['entities']if e['uuid']==ACTOR),None)
    require(actor is not None and math.dist(actor['pos'],stopped['pos'])<=4.5,'villager did not track changed player position')
    return {'player_displacement':math.dist(before['pos'],stopped['pos']),'stopped_distance':math.dist(actor['pos'],stopped['pos'])}

def native(root):
    rows=reconcile(root,root/'evidence');arena(root/'evidence/arena.json');groups,performance=sessions(rows,'D07-GT01','d0200000-0000-4000-8000-000000000001')
    require([g['reason']for g in groups]==['PAUSE_LIMIT','RESOURCE_RESERVED','DIMENSION_CHANGED','UNAUTHORIZED','DEAD','UNLOADED','CANCELLED'],'native follow required refusal controls missing')
    require(groups[0]['travel_blocks']>12 and groups[0]['crossed_obstacle'] and groups[0]['paused_ticks']==100 and len(rows)>580,'native finite follow observation window incomplete')
    positive=[r for r in rows if r['state']['session']==rows[0]['state']['session']and r['connected']and r['event']=='STEP']
    require(len({tuple(r['native_player'])for r in positive})>=2 and math.dist(positive[-1]['native_feet'],positive[-1]['native_player'])<=4.2,'native villager did not follow changed target')
    c=read(root/'evidence/controls.json');require(c=={'competing_owner':'RESOURCE_RESERVED','dimension':'DIMENSION_CHANGED','revocation':'UNAUTHORIZED','stop':'CANCELLED','pause_ticks':100,'native_class':'net.minecraft.world.entity.npc.villager.Villager','no_ai':False,'death':'DEAD','unloaded':'UNLOADED'},'native follow controls changed')
    return {'sessions':groups,'performance':performance,'evidence':['native/follow/stdout.log','native/follow/evidence/follow.jsonl','native/follow/evidence/controls.json']}

def client(root,identity,fixture):
    m=read(root/'identity.json');require(m['identity']==identity and m['case']=='D07-CL01'and m['world']=='d0700000-0000-4000-8000-000000000001'and m['phase']=='COLLECTED_RAW' and m['integration_root']is None and m['save_proof']is None,'follow client identity incomplete')
    selected=root/'selected-client-fixture.jar';require(sha(fixture)==m['fixture_sha256']and sha(selected)==m['selected_fixture_sha256'],'follow fixture hash changed')
    with ZipFile(fixture)as a,ZipFile(selected)as b:
        require(a.namelist()==b.namelist(),'follow fixture members changed')
        for n in a.namelist():
            if n!='fabric.mod.json':require(a.read(n)==b.read(n),'follow fixture code changed')
        d=json.loads(a.read('fabric.mod.json'));d['id']='cognitivecraft_parallel_d_clients';d['entrypoints']={'main':['dev.aivillages.e2e.paralleld.DFollowServer']};require(json.loads(b.read('fabric.mod.json'))==d,'follow fixture entrypoint changed')
    rows=reconcile(root/'server',root/'server/d07');arena(root/'server/d07/arena.json');groups,perf=sessions(rows,'D07-CL01',m['world'],offline('PlayerD_A'))
    require(len(groups)==2 and [s['reason']for s in groups]==['CANCELLED','PAUSE_LIMIT']and groups[0]['travel_blocks']>10 and groups[0]['crossed_obstacle']and groups[1]['paused_ticks']==100,'missing real follow/STOP/disconnect session')
    frames=read(root/'follow-clients.json');require(set(r['phase']for r in frames)>={'BEFORE','FOLLOWING','PLAYER_WALKING','STOPPED','BEFORE_DISCONNECT','DISCONNECTED','PAUSE_TERMINAL'},'follow client phases absent')
    tracking=tracked(frames)
    for player in PLAYERS:
        box=root/'clients'/player;process=read(box/'process.json');events=lines(box/'events.jsonl');observed=lines(box/'observations.jsonl');require(process['kind']=='client' and process['releaseSha256']==identity['jar_sha256']and 'net.fabricmc.loader.impl.launch.knot.KnotClient'in process['command'],'follow real JVM missing')
        require(any(e['kind']=='driver'and e['data']['pid']==process['pid']for e in events) and any(e['kind']=='play'and e['data']['uuid']==offline(player)for e in events),'follow client authenticated play missing')
        for f in [f for f in frames if f['player']==player]:
            state=f['state'];require(state['play']and not state['singleplayer']and state['uuid']==offline(player)and any(e['uuid']==ACTOR and e['type']=='minecraft:villager'for e in state['entities'])and any(o['state']==state for o in observed),'follow forged/unsynchronized client snapshot')
        png=(box/'screenshots/d07-follow-stopped.png').read_bytes();require(png[:8]==b'\x89PNG\r\n\x1a\n'and struct.unpack('>II',png[16:24])==(800,600),'follow rendered screenshot missing')
        if player=='PlayerD_A':
            commands=[e['data']['text']for e in events if e['kind']=='action'and e['data'].get('action')=='command'and e['data'].get('text','').startswith('d-follow ')];require(commands==['d-follow start','d-follow stop','d-follow start'],'follow requests not explicit separate sessions')
            require(sum(e['kind']=='action'and e['data'].get('action')=='move'for e in events)==2,'follow ordinary real-player movement missing')
    process=read(root/'server/process.json');require(process['releaseSha256']==identity['jar_sha256']and any(x['sha256']==sha(selected)for x in process['mods']),'follow server process swapped')
    require(m['driver_sha256']==sha(REPO/'build/e2e-driver/client.jar')and m['observer_sha256']==sha(REPO/'build/e2e-driver/observer.jar'),'follow established drivers changed')
    return [{'id':'D07-CL01','status':'PASS'}],{'sessions':groups,'performance':perf,'tracking':tracking,'client_snapshots':len(frames),'evidence':['clients-d07/'+p for p in ('identity.json','follow-clients.json','server/d07/follow.jsonl','server/stdout.log','clients/PlayerD_A/events.jsonl','clients/PlayerD_B/events.jsonl')]}
