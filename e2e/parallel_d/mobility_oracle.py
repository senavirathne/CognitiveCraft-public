"""Independent physical movement oracle: raw native feet/ticks/path and real client observations."""
import math,struct
from common import *
from client_oracle import lines,offline,PLAYERS
from zipfile import ZipFile

ACTOR='d0600000-0000-4000-8000-000000000003'
def xyz(p):return [p['x'],p['y'],p['z']]
def distance(a,b):return math.dist(a,b)
def routes(rows):
    groups=[]
    for r in rows:
        if r['event']=='ACCEPTED':groups.append([])
        require(groups,'native movement without acceptance');groups[-1].append(r)
    return groups
def movement(rows,case,world,expect='ARRIVED',principal=None,obstacle=True):
    require(rows and rows[0]['event']=='ACCEPTED'and rows[-1]['event']=='TERMINAL','missing physical route boundaries')
    start=rows[0];target=xyz(start['captured']);accepted=start['accepted_tick'];previous=start['native_feet'];last_tick=accepted;travel=0;last_progress=previous;progress_tick=accepted;repaths=0;repath_tick=None;samples=[];crossed=False
    require(start['actor']==ACTOR and start['captured']['dimension']=='minecraft:overworld','wrong physical actor/dimension')
    for r in rows:
        require(r['case']==case and r['world']==world and r['actor']==ACTOR and r['principal']==start['principal'] and (principal is None or r['principal']==principal),'swapped movement principal/actor/world')
        require(r['captured']==start['captured'] and r['accepted_tick']==accepted,'COME retargeted or reset acceptance clock')
        require(r['integration_root']is None and r['save_proof']is None and r['scheduled_tasks']==0,'fabricated A/save authority or new scheduler')
        require(r['forced_now']==r['forced_before']==start['forced_before'],'navigation forced a chunk')
        require(r['checked_chunks']<=121 and r['path_nodes']<=256 and r['path_length']<=64,'native search or route allowance unbounded')
        if r['event']not in ('STEP','TERMINAL'):continue
        require(r['tick']==last_tick+1 or (not samples and r['tick']>=accepted),'missing/duplicate tick observations')
        delta=distance(previous,r['native_feet']);require(delta<=.6,'actor teleport or nonordinary displacement');travel+=delta
        if delta>=.01:last_progress=r['native_feet'];progress_tick=r['tick']
        previous=r['native_feet'];last_tick=r['tick'];o=r['observation'];require(o is not None and xyz(o['feet'])==r['native_feet'] and xyz(o['endpoint'])==target,'exported feet differ from native entity')
        require(o['tick']==r['tick'] and o['elapsedTicks']==r['tick']-accepted and o['elapsedTicks']<600 and abs(o['travelBlocks']-travel)<1e-6 and travel<=64,'forged clock/cumulative allowance')
        require(o['lastProgressTick']==progress_tick and distance(xyz(o['lastProgress']),last_progress)<1e-6,'forged last physical progress')
        require(1<=o['standingCandidates']<=8,'standing scan bound')
        if o['repathProposals']!=repaths:
            require(o['repathProposals']==repaths+1 and (repath_tick is None or r['tick']-repath_tick>=10),'repath interval/count inflation');repath_tick=r['tick'];repaths+=1
        x,y,z=r['native_feet'];ox,oy,oz=r['origin']
        if obstacle and abs(x-(ox+8.5))<.8:
            require(z<oz+3.71 or z>oz+11.29,'actor passed through the three-high native obstacle');crossed=True
        require(r['step_ns']>0 and 0<r['heap_used_bytes']<1100*1024*1024,'missing native timing/heap');samples.append(r['step_ns'])
    terminal=rows[-1]['observation'];require(terminal['status']==expect,'required physical arrival missing')
    if expect=='ARRIVED':
        require(terminal['blocker']=='NONE'and distance(previous,target)<=1.5 and distance(rows[-1]['native_player'],target)>=5 and (crossed or not obstacle),'actor did not reach old location around obstacle after player moved')
        require(len(samples)>=(100 if obstacle else 1),'movement timing window incomplete')
    else:require(terminal['blocker']==('UNLOADED'if obstacle else'STALE_STORAGE'),'missing exact physical blocker')
    samples.sort();p99=samples[math.ceil(len(samples)*.99)-1];require(p99<=50000000 and max(samples)<=250000000,'native movement tick regression')
    return {'frames':len(rows),'ticks':terminal['elapsedTicks'],'travel_blocks':travel,'repath_proposals':repaths,'samples':len(samples),'p50_ns':samples[math.ceil(len(samples)*.5)-1],'p95_ns':samples[math.ceil(len(samples)*.95)-1],'p99_ns':p99,'max_ns':max(samples),'heap_peak_bytes':max(r['heap_used_bytes']for r in rows),'terminal':terminal['status'],'blocker':terminal['blocker'],'captured':target,'actual_native_seed':start['seed']}
def arena(path):
    a=read(path);require(a['native_class']=='net.minecraft.world.entity.npc.villager.Villager','not a real native villager')
    require([r['relative']for r in a['floor']]==[[x,-1,z]for x in range(20)for z in range(25)]and len(a['wall'])==21 and [r['relative']for r in a['wall']]==[[8,y,z]for z in range(4,11)for y in range(3)],'missing native arena/floor/obstacle snapshots')
    require(all(r['state']=='Block{minecraft:stone}'for r in a['floor']+a['wall']),'actual obstacle/floor is not stone');return a

def native(root):
    from mobility_frames import reconcile
    reconcile(root/'stdout.log',root/'evidence')
    arena(root/'evidence/arena.json')
    groups=routes(lines(root/'evidence/movement.jsonl'));require(len(groups)==2,'missing native positive/unloaded controls')
    positive=movement(groups[0],'D06-GT01','d0200000-0000-4000-8000-000000000001');negative=movement(groups[1],'D06-GT01','d0200000-0000-4000-8000-000000000001','BLOCKED')
    c=read(root/'evidence/controls.json');require(c['unloaded_before']is False and c['unloaded_after']is False and c['forced_before']==c['forced_after']and c['blocker']=='UNLOADED'and c['actor_no_ai']is False and c['native_class']=='net.minecraft.world.entity.npc.villager.Villager','native controls missing or disabled physics')
    storage=routes(lines(root/'evidence/storage-movement.jsonl'));require(len(storage)==2,'missing GO rename/replacement controls');go=movement(storage[0],'D06-GT01','d0200000-0000-4000-8000-000000000001',obstacle=False);stale=movement(storage[1],'D06-GT01','d0200000-0000-4000-8000-000000000001','BLOCKED',obstacle=False)
    sc=read(root/'evidence/storage-controls.json');require(sc['accepted_label']=='supplies'and sc['renamed_label']=='renamed'and sc['operation']=='DEPOSIT'and sc['inspect_denied']is True and sc['replacement_blocker']=='STALE_STORAGE','GO native binding/privacy controls missing')
    return {'come':positive,'unloaded':negative,'go_storage':go,'replacement':stale,'storage_controls':sc,'native_controls':c}
def client_frames(frames,server):
    require(len(frames)>=6 and [(r['phase'],r['player'])for r in frames[:2]]==[('BEFORE',p)for p in PLAYERS]and [(r['phase'],r['player'])for r in frames[-2:]]==[('TERMINAL',p)for p in PLAYERS],'missing actual client before/after')
    require(len(frames)%2==0 and all([(r['phase'],r['player'])for r in frames[i:i+2]]==[(frames[i]['phase'],p)for p in PLAYERS]for i in range(0,len(frames),2)),'missing/duplicate actual client frame pair')
    captured=xyz(server[0]['captured']);seen=set()
    for r in frames:
        s=r['state'];require(s['play']is True and s['singleplayer']is False and s['uuid']==offline(r['player']),'disconnected/simulated/swapped real client')
        actors=[e for e in s['entities']if e['uuid']==ACTOR];require(len(actors)==1 and actors[0]['type']=='minecraft:villager','native actor not synchronized to client');seen.add(r['player'])
        if r['phase']=='TERMINAL':
            require(distance(actors[0]['pos'],captured)<=1.8,'client did not observe arrival at old location')
            if r['player']=='PlayerD_A':require(distance(s['pos'],captured)>=5,'requesting player never moved after acceptance')
    require(seen==set(PLAYERS),'missing second actual client');return {'client_snapshots':len(frames),'connected_clients':2}
def client(root,identity,fixture):
    from mobility_frames import reconcile
    reconcile(root/'server/stdout.log',root/'server/d06')
    m=read(root/'identity.json');require(m['identity']==identity and m['case']=='D06-CL01'and m['world']=='d0600000-0000-4000-8000-000000000001'and m['phase']=='COLLECTED_RAW'and m['integration_root']is None and m['save_proof']is None,'client movement identity or collection incomplete')
    selected=root/'selected-client-fixture.jar';require(sha(selected)==m['selected_fixture_sha256']and sha(fixture)==m['fixture_sha256'],'movement fixture changed')
    with ZipFile(fixture)as original,ZipFile(selected)as observed:
        require(original.namelist()==observed.namelist(),'movement fixture members changed')
        for n in original.namelist():
            if n!='fabric.mod.json':require(original.read(n)==observed.read(n),'native movement fixture implementation swapped')
        d=json.loads(original.read('fabric.mod.json'));d['id']='cognitivecraft_parallel_d_clients';d['entrypoints']={'main':['dev.aivillages.e2e.paralleld.DComeServer']};require(json.loads(observed.read('fabric.mod.json'))==d,'movement fixture entrypoint changed')
    arena(root/'server/d06/arena.json');ready=read(root/'server/d06/ready.json');require(ready['actor']==ACTOR and ready['principal']==offline('PlayerD_A') and ready['seed']==m['seed']==20261027 and m['render_backend']=='ESTABLISHED_SDL_OFFSCREEN','native player/actor/seed mismatch')
    raw=lines(root/'server/d06/movement.jsonl');groups=routes(raw);require(len(groups)==1,'client COME reset/retried');details=movement(groups[0],'D06-CL01',m['world'],principal=offline('PlayerD_A'));frames=read(root/'movement-clients.json');details.update(client_frames(frames,raw));require(read(root/'server/d06/terminal.json')==raw[-1]['observation'],'actual native terminal state changed')
    require(m['driver_sha256']==sha(REPO/'build/e2e-driver/client.jar')and m['observer_sha256']==sha(REPO/'build/e2e-driver/observer.jar'),'established driver changed')
    containers=lines(root/'containers.jsonl');containers=containers[0]if len(containers)==1 and isinstance(containers[0],list)else containers;require({r['Service']for r in containers}=={'minecraft','client-a','client-b'}and len({r['ID']for r in containers})==3,'distinct real containers absent')
    process=read(root/'server/process.json');require(process['releaseSha256']==identity['jar_sha256']and any(r['sha256']==sha(selected)for r in process['mods']),'wrong native server release/fixture')
    for player in PLAYERS:
        box=root/'clients'/player;p=read(box/'process.json');events=lines(box/'events.jsonl');observed=lines(box/'observations.jsonl');require(p['kind']=='client'and p['releaseSha256']==identity['jar_sha256']and 'net.fabricmc.loader.impl.launch.knot.KnotClient'in p['command']and p['command'][p['command'].index('--username')+1]==player,'wrong real client process')
        require(any(e['kind']=='driver'and e['data']['pid']==p['pid']for e in events)and any(e['kind']=='play'and e['data']['uuid']==offline(player)for e in events),'no actual connected JVM play')
        require(p['renderEnvironment'].get('SDL_VIDEO_DRIVER')=='offscreen'and p['renderEnvironment'].get('LIBGL_ALWAYS_SOFTWARE')=='1','changed native renderer')
        for r in [r for r in frames if r['player']==player]:require(any(s['state']==r['state']for s in observed),'client movement snapshot fabricated')
        if player=='PlayerD_A':
            come=[e for e in events if e['kind']=='action'and e['data'].get('action')=='command'and e['data'].get('text')=='d-come come'];walk=[e for e in events if e['kind']=='action'and e['data'].get('action')=='move'];require(len(come)==len(walk)==1 and walk[0]['time']>=come[0]['time'],'request and subsequent ordinary player movement missing')
            require(any(e['kind']=='feedback'and e['data']['text'].startswith('D06_ACCEPTED ')for e in events),'no authenticated server capture feedback')
        data=(box/'screenshots/d06-captured-arrival.png').read_bytes();require(data[:8]==b'\x89PNG\r\n\x1a\n'and struct.unpack('>II',data[16:24])==(800,600),'actual rendered movement screenshot absent')
    props=read(root/'server-properties.json');require(props['level-seed']=='20261027'and props['server-port']=='25661','movement seed/port drift')
    evidence=[str((root/p).relative_to(root.parent))for p in ('identity.json','movement-clients.json','server/d06/movement.jsonl','server/d06/terminal.json','clients/PlayerD_A/events.jsonl','clients/PlayerD_B/events.jsonl')];return [{'id':'D06-CL01','status':'PASS'}],{**details,'evidence':evidence}
