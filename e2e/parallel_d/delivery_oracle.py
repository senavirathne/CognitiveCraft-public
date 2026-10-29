"""Independent native courier deltas, slot provenance, movement, saved participants and real clients."""
import math,hashlib,re,struct,json
from zipfile import ZipFile
from common import *
from client_oracle import lines,offline,PLAYERS
from native_oracle import topology
from mobility_oracle import xyz
NATIVE_CAMPAIGNS=['SUCCESS_Q4','SOURCE_EFFECT_BOUNDARY','OLD_STOCK_INTERFERENCE','NO_DISJOINT_SLOT','FULL_DESTINATION','CUSTOM_COMPONENTS','SELECTED_SOURCE_SLOT','SUCCESS_Q1','SUCCESS_Q64','SUCCESS_BATCH4','FULL_IN_FLIGHT','REVOKED_IN_FLIGHT']

def reconcile(root,evidence,prefix='CC_D08_FRAME ',file='courier.jsonl'):
    observed=[]
    for line in (root/'stdout.log').read_text().splitlines():
        if prefix in line:observed.append(json.loads(line.split(prefix,1)[1]))
    raw=lines(evidence/file);require(raw and observed==raw,'missing/mutated courier frames vs actual native stdout');return raw

def slots(rows,expected):
    require(len(rows)==expected and [r['slot']for r in rows]==list(range(expected)),'courier missing/duplicate native slot')
    for r in rows:
        require(isinstance(r['count'],int)and 0<=r['count']<=99 and (r['item']is None)==(r['count']==0),'courier native slot shape')
        if r['item']is not None:
            i=r['item'];require(i['registryId']==r['native_item_id'] and i['fingerprint']==hashlib.sha256(r['native_codec'].encode()).hexdigest()and i['defaultComponents']==r['native_default']and i['maxStack']==r['native_max_stack']and r['count']<=i['maxStack'],'courier fingerprint/default/limit disagrees with actual native codec')
    return rows

def totals(row,probe=None):
    def count(name):return sum(s['count']for s in row[name]if s['item']is not None and (probe is None or s['item']['fingerprint']==probe['fingerprint']and s['item']['registryId']==probe['registryId']))
    return [count(k)for k in ('source','carrier','destination')]

def trace(rows,case,world,expected):
    require(rows and rows[0]['event']=='BEFORE','courier missing admission/setup boundary');groups={};identity=(rows[0]['actor'],rows[0]['principal']);seen_requests=set();move_samples=[];transfer_samples=[]
    for r in rows:
        require(r['case']==case and r['world']==world and (r['actor'],r['principal'])==identity,'courier swapped world/principal/actor')
        require(r['integration_root']is None and r['save_proof']is None,'courier fabricated root/save receipt')
        require(r['forced_chunks']==rows[0]['forced_chunks'],'courier forced chunk during effect/travel')
        for field,n in [('source',27),('carrier',8),('destination',27)]:slots(r[field],n)
        require(r['source_binding']['storage_id']!=r['destination_binding']['storage_id'],'courier same physical endpoint admitted')
        for b in (r['source_binding'],r['destination_binding']):topology(b);require(b['world_id']==world,'courier binding world swapped')
        if r['event']=='BEFORE':require(r['request']not in seen_requests,'courier reset/reused original request');seen_requests.add(r['request']);groups[r['campaign']]=[]
        require(r['campaign']in groups and r['request']==(groups[r['campaign']][0]['request']if groups[r['campaign']]else r['request']),'courier changed request inside campaign')
        groups[r['campaign']].append(r)
    require(list(groups)==list(expected),'courier missing/duplicated/unregistered campaign')
    result=[]
    for name,frames in groups.items():
        first=frames[0];probe=first['goal']['item'];initial=totals(first,probe);old=first['carrier'];inflight=None;withdrawn=deposited=batches=0;travel=0;previous=first['native_feet'];route=None;lasttick=None;route_travel=0;route_feet=previous;progress_tick=None;progress_feet=None;blocked=False;completed=False
        for r in frames:
            require(r['goal']==first['goal']and r['source_binding']==first['source_binding']and r['destination_binding']==first['destination_binding'],'courier goal/endpoint retargeted')
            require(0<r['heap_used_bytes']<1100*1024*1024,'courier native heap bound')
            if r['event']=='TRAVEL_ACCEPTED':route=r['route'];lasttick=None;route_travel=0;route_feet=r['native_feet'];progress_tick=route['acceptedTick']if route else None;progress_feet=route_feet;require(route is not None,'courier route evidence absent')
            if r['event']=='MOVE':
                o=r['detail']['observation'];require(route is not None and r['route']==route and (lasttick is None or r['tick']==lasttick+1),'courier missing/duplicate movement tick');lasttick=r['tick'];delta=math.dist(route_feet,r['native_feet']);require(delta<=.6,'courier teleported carrier');route_travel+=delta;travel+=delta;route_feet=r['native_feet']
                require(xyz(o['feet'])==r['native_feet']and xyz(o['endpoint'])==xyz(route['endpoint'])and o['elapsedTicks']==r['tick']-route['acceptedTick']and o['elapsedTicks']<600 and abs(o['travelBlocks']-route_travel)<1e-6 and route_travel<=64,'courier movement allowance or feet forged')
                if delta>=.01:progress_tick=r['tick'];progress_feet=r['native_feet']
                require(o['lastProgressTick']==progress_tick and xyz(o['lastProgress'])==progress_feet and 1<=o['standingCandidates']<=8,'courier fabricated actual last progress/candidates')
                origin=[r['source_pos'][0]-2,r['source_pos'][1],r['source_pos'][2]-7]
                if abs(r['native_feet'][0]-(origin[0]+8.5))<.8:require(r['native_feet'][2]<origin[2]+3.71 or r['native_feet'][2]>origin[2]+11.29,'courier crossed native obstacle')
                require(r['path_nodes']<=256 and r['path_length']<=64 and r['checked_chunks']<=121,'courier unbounded native route');move_samples.append(r['detail']['step_ns'])
            if r['event']in ('WITHDRAW','DEPOSIT','REFUSED','EXPIRED_SELECTION','SOURCE_CHANGED_BEFORE_EFFECT')and isinstance(r.get('detail'),dict)and 'status'in r['detail']:
                o=r['detail'];q=o['quantity'];actual=totals(r,probe)
                if o['after']is not None:require([o['after'][k]for k in ('source','carrier','destination')]==actual,'courier observed after counts differ from native slots')
                if o['status']=='LIVE_WITHDRAWN':
                    require(not blocked and not completed and inflight is None and 1<=q<=64,'courier withdrew outside original finite custody');a=[o['before'][k]for k in ('source','carrier','destination')];b=actual;require([a[0]-q,a[1]+q,a[2]]==b and sum(a)==sum(b),'courier withdrew/minted wrong native units');require(math.dist(r['native_feet'],[r['source_pos'][0]+.5,r['source_pos'][1]+.5,r['source_pos'][2]+.5])<=3,'courier withdrew out of native reach')
                    changes=[s for s in r['carrier']if s['item']is not None and old[s['slot']]['count']==0];require(len(changes)==1 and changes[0]['count']==q and changes[0]['item']==probe,'courier batch merged with old stock or changed components');inflight=(changes[0]['slot'],q);batches+=1;withdrawn+=q;require(batches<=8 and withdrawn<=first['goal']['quantity'],'courier carrying bound');transfer_samples.append(r['transfer_ns'])
                elif o['status']=='LIVE_DEPOSITED':
                    require(not blocked and inflight is not None and inflight[1]==q,'old/foreign carrier stock counted as deposit');a=[o['before'][k]for k in ('source','carrier','destination')];b=actual;require([a[0],a[1]-q,a[2]+q]==b and sum(a)==sum(b)and r['carrier'][inflight[0]]['count']==0,'courier deposited wrong native batch');require(math.dist(r['native_feet'],[r['destination_pos'][0]+.5,r['destination_pos'][1]+.5,r['destination_pos'][2]+.5])<=3,'courier deposit out of native reach');deposited+=q;inflight=None;completed=deposited==first['goal']['quantity'];transfer_samples.append(r['transfer_ns'])
                elif o['status']=='REFUSED':
                    require(q==0 and r['delivered']==deposited,'refusal falsely credited native delivery')
                    if r['phase']=='BLOCKED':blocked=True
                else:raise ValueError('unearned uncertain courier effect in qualification')
            if name not in ('OLD_STOCK_INTERFERENCE','NO_DISJOINT_SLOT'):
                for oldslot in old:
                    if oldslot['count']>0:require(r['carrier'][oldslot['slot']]==oldslot,'courier consumed/merged pre-existing carrier stock')
        if name in ('SUCCESS','SUCCESS_Q4','SUCCESS_Q1','SUCCESS_Q64','SUCCESS_BATCH4','SELECTED_SOURCE_SLOT'):
            require(completed and deposited==first['goal']['quantity']and inflight is None and travel>=10,'courier required delivery or actual native travel absent')
            require(totals(frames[-1],probe)==[initial[0]-deposited,initial[1],initial[2]+deposited],'courier final native conservation/old stock')
        if name in ('REVOKED','REVOKED_IN_FLIGHT','REPLACED','FULL_IN_FLIGHT'):
            terminal=next((r for r in reversed(frames)if r['event']=='DEPOSIT'),None);require(terminal and terminal['detail']['status']=='REFUSED'and terminal['delivered']==0 and terminal['in_flight']==4 and totals(terminal,probe)[1]==initial[1]+4,'courier blocker lost/credited in-flight stock')
            require(terminal['detail']['reason']==('CAPACITY'if name=='FULL_IN_FLIGHT'else'STALE_ENDPOINT'if name=='REPLACED'else'UNAUTHORIZED'),'courier wrong effect-time blocker')
        result.append({'campaign':name,'request':first['request'],'withdrawn':withdrawn,'deposited':deposited,'batches':batches,'travel_blocks':travel,'final_native':totals(frames[-1],probe),'phase':frames[-1]['phase']})
    def performance(values):
        require(values and all(n>0 for n in values),'courier actual timing samples absent');values.sort();p=lambda f:values[math.ceil(len(values)*f)-1];require(p(.99)<=50000000 and max(values)<=250000000,'courier native operation performance regression');return {'samples':len(values),'p50_ns':p(.5),'p95_ns':p(.95),'p99_ns':p(.99),'max_ns':max(values)}
    return {'campaigns':result,'movement_performance':performance(move_samples),'transfer_performance':performance(transfer_samples),'frames':len(rows)}

def crash_states(before,after):
    require([r['event']for r in before]==['SAVED_BEFORE','LIVE_IN_FLIGHT']and [r['event']for r in after]==['RESTORED_UNKNOWN_CUSTODY'],'missing courier actual crash snapshots')
    require([[r[k]for k in ('source','carrier','destination')]for r in before+after]==[[10,3,2],[6,7,2],[10,3,2]],'live/saved participant states conflated or auto-replayed')
    require(len({r['actor']for r in before+after})==1 and all(r['world']=='d0200000-0000-4000-8000-000000000001'and r['case']=='D08-GT01'and r['save_proof']is None and r['integration_root']is None for r in before+after),'crash actor/world/save lineage fabricated')

def crash(root):
    from native_nbt import decode
    write=root/'courier-crash-write';readroot=root/'courier-crash-read';before=reconcile(write,write/'evidence','CC_D08_CRASH ','crash.jsonl');after=reconcile(readroot,readroot/'evidence','CC_D08_CRASH ','crash.jsonl')
    crash_states(before,after)
    def item_count(items):return sum(i.get('count',i.get('Count',0))for i in items if i.get('id')=='minecraft:oak_planks')
    for phase in (write,readroot):
        a=decode(phase/'saved-source.json');b=decode(phase/'saved-destination.json');e=decode(phase/'saved-carrier.json');source=next(x for x in a['block_entities']if x.get('x')==130 and x.get('z')==135);dest=next(x for x in b['block_entities']if x.get('x')==145 and x.get('z')==135);villagers=[x for x in e['Entities']if x.get('id')=='minecraft:villager'];require(len(villagers)==1,'courier actual saved carrier ambiguous');require(str(__import__('uuid').UUID(bytes=villagers[0]['UUID']))==before[0]['actor'],'saved courier actor UUID swapped');require([item_count(source.get('Items',[])),item_count(villagers[0].get('Inventory',[])),item_count(dest.get('Items',[]))]==[10,3,2],'actual saved NBT does not match restored participants')
    log=(readroot/'class-init.log').read_text().replace('.','/');require('class,init'in log and 'dev/aivillages/fabric/logistics/MinecraftTransferAdapter'not in log and 'dev/aivillages/core/kernel/logistics/DeliveryPolicy$Session'not in log,'courier runtime/replay initialized after crash')
    return {'live':[6,7,2],'saved_and_restored':[10,3,2],'real_sigkill':True,'automatic_courier_replay':False,'native_vanilla_navigation_observed':after[0]['native_navigation_active'],'settlement':None}

def native(root):
    c=root/'courier';rows=reconcile(c,c/'evidence');result=trace(rows,'D08-GT01','d0200000-0000-4000-8000-000000000001',NATIVE_CAMPAIGNS);result['crash']=crash(root);result['evidence']=['native/courier/evidence/courier.jsonl','native/courier/stdout.log','native/courier-crash-write/evidence/crash.jsonl','native/courier-crash-read/evidence/crash.jsonl','native/courier-crash-read/class-init.log'];return result

def client(root,identity,fixture):
    m=read(root/'identity.json');require(m['identity']==identity and m['case']=='D08-CL01'and m['world']=='d0800000-0000-4000-8000-000000000001'and m['phase']=='COLLECTED_RAW'and m['integration_root']is None and m['save_proof']is None,'courier real-client identity/collection absent');selected=root/'selected-client-fixture.jar';require(sha(selected)==m['selected_fixture_sha256']and sha(fixture)==m['fixture_sha256'],'courier actual fixture changed')
    with ZipFile(fixture)as a,ZipFile(selected)as b:
        require(a.namelist()==b.namelist(),'courier fixture members changed')
        for n in a.namelist():
            if n!='fabric.mod.json':require(a.read(n)==b.read(n),'courier selected fixture implementation changed')
        d=json.loads(a.read('fabric.mod.json'));d['id']='cognitivecraft_parallel_d_clients';d['entrypoints']={'main':['dev.aivillages.e2e.paralleld.DChestCourierServer']};require(json.loads(b.read('fabric.mod.json'))==d,'courier fixture entrypoint changed')
    native_logs=(root/'server/stdout.log').read_text();summaries={};actors=read(root/'courier-clients.json');menus=read(root/'courier-menus.json')
    for mode in ('SUCCESS','REVOKED','REPLACED'):
        ready=read(root/'server/d08'/('ready-'+mode+'.json'));evidence=root/'server/d08'/mode;rows=lines(evidence/'courier.jsonl');require(rows==[json.loads(s.split('CC_D08_FRAME ',1)[1])for s in native_logs.splitlines()if 'CC_D08_FRAME 'in s and json.loads(s.split('CC_D08_FRAME ',1)[1])['case']=='D08-CL01-'+mode],'courier client raw frames missing/changed')
        require(ready['principal']==offline('PlayerD_A')and ready['seed']==20261027 and rows[0]['actor']==ready['actor'],'courier native principal/seed/actor changed');summaries[mode]=trace(rows,'D08-CL01-'+mode,m['world'],[mode]);terminal=read(root/'server/d08'/('terminal-'+mode+'.json'));require(terminal['delivered']==(4 if mode=='SUCCESS'else 0)and terminal['in_flight']==(0 if mode=='SUCCESS'else 4),'courier false terminal fulfillment')
        for p in PLAYERS:
            fs=[f for f in actors if f['mode']==mode and f['player']==p];require(fs and fs[0]['phase']=='BEFORE'and fs[-1]['phase']=='TERMINAL','courier client missing actual movement boundaries')
            for f in fs:require(f['state']['play']and not f['state']['singleplayer']and f['state']['uuid']==offline(p)and any(e['uuid']==ready['actor']and e['type']=='minecraft:villager'for e in f['state']['entities']),'courier simulated/disconnected/unsynchronized client')
        if mode=='SUCCESS':require(any(r['event']=='DESTINATION_RENAMED'for r in rows),'courier rename positive control absent')
        if mode=='REPLACED':require(any(r['event']=='DESTINATION_REPLACED'and r['destination_native']['marker']is None for r in rows),'courier native replacement not observed')
    require(len({read(root/'server/d08'/('ready-'+m+'.json'))['actor']for m in ('SUCCESS','REVOKED','REPLACED')})==3,'courier reused blocked actor/custody between control arenas')
    audit=read(root/'server/d08/final-custody.json');require(audit['SUCCESS']['delivered']==4 and all(audit[k]['delivered']==0 and audit[k]['in_flight']==4 and sum(s['count']for s in audit[k]['carrier'])==7 for k in ('REVOKED','REPLACED')),'courier lost blocked material after terminal')
    for p in PLAYERS:
        box=root/'clients'/p;process=read(box/'process.json');events=lines(box/'events.jsonl');observed=lines(box/'observations.jsonl');require(process['kind']=='client'and process['releaseSha256']==identity['jar_sha256']and 'net.fabricmc.loader.impl.launch.knot.KnotClient'in process['command'],'courier real client JVM missing');require(any(e['kind']=='driver'and e['data']['pid']==process['pid']for e in events)and any(e['kind']=='play'and e['data']['uuid']==offline(p)for e in events),'courier actual authenticated JVM play missing')
        for r in [x for x in actors+menus if x['player']==p]:require(any(o['state']==r['state']for o in observed),'courier client snapshot fabricated')
        for mode in ('SUCCESS','REVOKED','REPLACED'):
            fs=[x for x in menus if x['player']==p and x['mode']==mode];expected=[10,6,6]if p=='PlayerD_A'else[2,6 if mode=='SUCCESS'else 2 if mode=='REVOKED'else 0];require([sum(s['count']for s in x['state']['slots']if not s['playerInventory']and s['item']=='minecraft:oak_planks')for x in fs]==expected,'courier actual source/destination GUI delta mismatch')
        overlay=read(box/'root-overlay.json');require(overlay['observation_sha256']==sha(box/'observation-area.json')and overlay['root']in process['command'][process['command'].index('--gameDir')+1],'native courier client did not use actual bounded overlay')
        for f in [x for x in menus if x['player']==p]:require('minecraft:chest'in f['state']['blocks'].get(','.join(map(str,f['position'])),''),'courier client menu position outside actual observed native blocks')
        png=(box/'screenshots'/('d08-courier-'+p+'.png')).read_bytes();require(png[:8]==b'\x89PNG\r\n\x1a\n'and struct.unpack('>II',png[16:24])==(800,600),'courier actual native GUI screenshot absent')
    require(m['driver_sha256']==sha(REPO/'build/e2e-driver/client.jar')and m['observer_sha256']==sha(REPO/'build/e2e-driver/observer.jar'),'courier established driver changed');process=read(root/'server/process.json');require(process['releaseSha256']==identity['jar_sha256']and any(x['sha256']==sha(selected)for x in process['mods']),'courier server release/fixture swapped')
    return [{'id':'D08-CL01','status':'PASS'}],{'scenarios':summaries,'client_snapshots':len(actors),'menu_snapshots':len(menus),'blocked_custody_retained':audit,'evidence':['clients-d08/'+p for p in ('identity.json','courier-clients.json','courier-menus.json','server/stdout.log','server/d08/final-custody.json','clients/PlayerD_A/events.jsonl','clients/PlayerD_B/events.jsonl')]}
