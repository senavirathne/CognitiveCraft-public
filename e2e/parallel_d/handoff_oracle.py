"""Independent D09 native slot deltas, source grants, current recipients, movement and real clients."""
import hashlib,json,math,re,struct
from zipfile import ZipFile
from common import *
from delivery_oracle import slots,reconcile
from native_oracle import topology
from client_oracle import lines,offline,PLAYERS
from mobility_oracle import xyz

NATIVE_CAMPAIGNS=['EXISTING_SUCCESS','RENAMED_NAMESAKE','FULL_RECIPIENT','OUT_OF_REACH','NATIVE_REACH_LIMIT','MOVED_RECIPIENT','REVOKED_DEPOSIT','DEAD_RECIPIENT','UNLOADED_RECIPIENT','RIVAL_COURIER','CONTROL_LOSS','EFFECT_BOUNDARY_INTERFERENCE','UNAUTHORIZED_OLD_STOCK','UNSUPPORTED_INVENTORY','CUSTOM_COMPONENTS','STALE_GENERATION','EXISTING_Q1','EXISTING_Q64','STORAGE_SUCCESS','STORAGE_BATCH64','STORAGE_REVOKED','STORAGE_TARGET_MOVED','OFF_THREAD','NATIVE_PROTECTION','EXPIRED_SELECTION','FOREIGN_SELECTION','SWAPPED_SLOT']
CLIENT_MODES=['EXISTING','STORAGE','DEATH','UNLOAD','RIVAL','MOVED','FULL']
SUCCESS={'EXISTING_SUCCESS','RENAMED_NAMESAKE','EXISTING_Q1','EXISTING_Q64','STORAGE_SUCCESS','STORAGE_BATCH64','OFF_THREAD','EXISTING','STORAGE'}

def totals(row):
    item=row['goal']['item']
    def count(field):return sum(s['count']for s in row[field]if s['item']==item)
    return [count('source')if row['goal']['source']=='STORAGE'else 0,count('sender'),count('recipient')]

def images(row):return [row[k]for k in ('source','sender','recipient','item_entities')]

def granted(row):
    goal=row['goal'];grant=row['selected_source'];require(grant is not None and grant['request']==goal['request']and grant['sender']==goal['carrier']['citizen']and grant['principal']==row['principal']and 0<=row['tick']-grant['observed_tick']<=40,'handoff lacks explicit current sender source authorization')
    amounts={}
    for selected in grant['slots']:
        i=selected['slot'];require(i not in amounts and 0<=i<8,'handoff source slot duplication/bound');native=row['sender'][i]
        require(selected['item']==goal['item']==native['item']and selected['native_object']==native['native_object']and selected['native_count']==native['count']and 1<=selected['quantity']<=native['count'],'handoff source grant does not match actual native slot')
        amounts[i]=selected['quantity']
    require(sum(amounts.values())==goal['quantity'],'handoff implicit or partial existing source quantity');return amounts

def eligible(row):
    goal=row['goal'];target=goal['recipient'];projection=row['recipient_projection'];control=row['control'];scope=projection['scope']
    require(control['carrier']==goal['carrier']and control['controlled']and control['exclusiveSender']and control['exclusiveRecipient']and control['protectionAllowed'],'handoff lacked current native authority/custody/protection')
    require(row['sender_loaded']and row['recipient_loaded']and row['recipient_alive']and not row['recipient_removed'],'handoff to unavailable native participant')
    require(scope['world']==row['world']==goal['carrier']['world']==target['world']and scope['principal']==row['principal']==goal['carrier']['principal']and scope['actorCitizen']==goal['carrier']['citizen'],'handoff swapped principal or world scope')
    for field in ('entity','epoch','generation','policyRevision','dimension'):require(projection[field]==target[field],'handoff recipient lifecycle/policy changed')
    require(projection['id']==target['citizen']and projection['tick']==row['tick']and projection['loaded']and projection['visible']and 'DEPOSIT'in projection['permitted']and row['recipient_entity']==target['entity'],'handoff name/address used as recipient permission')
    require(row['native_sender_class']==row['native_recipient_class']=='net.minecraft.world.entity.npc.villager.Villager'and row['sender_inventory_class']==row['recipient_inventory_class']=='net.minecraft.world.SimpleContainer','handoff unsupported local atomic inventory primitive')
    require(math.dist(row['sender_pos'],row['recipient_pos'])<=min(2.5,control['interactionLimit'])+1e-8,'handoff outside actual native interaction reach')
    if goal['source']=='EXISTING_CARRIER':require(control['existingSourceAuthorized']and goal['sourceCitizen']==goal['carrier']['citizen'],'handoff used older/uncontrolled/third-villager stock')
    else:
        binding=row['source_binding'];topology(binding);source=goal['storageSource'];require(source['world']==row['world']and source['storage']==binding['storage_id']and set(source['participants'])=={m['incarnation_id']for m in binding['members']},'handoff storage source retargeted')
        marker=row['source_native']['marker'];require(marker is not None and marker['incarnation']in source['participants'],'handoff source native incarnation missing')

def trace(rows,case,world,expected):
    require(rows and rows[0]['event']=='BEFORE','handoff admission boundary missing');groups={};requests=set();movement=[];transfers=[]
    for row in rows:
        require(row['case']==case and row['world']==world and row['integration_root']is None and row['save_proof']is None,'handoff fabricated root/save or swapped native case/world')
        require(0<row['heap_used_bytes']<1100*1024*1024,'handoff heap bound')
        for field,n in [('source',27),('sender',8),('recipient',8)]:slots(row[field],n)
        if row['event']=='BEFORE':require(row['goal']['request']not in requests and row['campaign']not in groups,'handoff duplicated campaign/reset original request');requests.add(row['goal']['request']);groups[row['campaign']]=[]
        require(row['campaign']in groups,'unregistered handoff campaign');groups[row['campaign']].append(row)
    require(list(groups)==list(expected),'missing/duplicate handoff campaign')
    summaries=[]
    for name,frames in groups.items():
        first=frames[0];goal=first['goal'];principal=first['principal'];delivered=withdrawn=batches=0;cargo=None;accepted_sender=None;accepted_custody=None;previous=first;route=None;last_move=None;route_travel=0;route_feet=None;last_progress=None;progress_tick=None;travel=0
        require(1<=goal['quantity']<=64 and goal['item']['defaultComponents']and not goal['item']['containerItem'],'unsupported handoff goal')
        for row in frames:
            require(row['goal']==goal and row['principal']==principal and row['forced_chunks']==first['forced_chunks']and row['tick']>=previous['tick'],'handoff retargeted goal/principal, forced a chunk or reset its clock')
            if row['event']=='ADMITTED':
                require(cargo is None and goal['source']=='EXISTING_CARRIER'and images(previous)==images(row),'existing cargo admission mutated native inventories');cargo=granted(row);accepted_sender=row['sender'];accepted_custody=row['control']['custodyRevision'];batches+=1
            if row['event']=='WITHDRAWN':
                o=row['detail'];q=o['quantity'];a=totals(previous);b=totals(row)
                require(cargo is None and goal['source']=='STORAGE'and o['status']=='LIVE_WITHDRAWN'and [a[0]-q,a[1]+q,a[2]]==b and sum(a)==sum(b),'handoff storage pickup minted/lost/misplaced units')
                require([o['before'][k]for k in ('source','carrier','destination')]==a and [o['after'][k]for k in ('source','carrier','destination')]==b,'handoff storage counts not native slots')
                changed=[i for i in range(8)if previous['sender'][i]['count']==0 and row['sender'][i]['count']>0];require(len(changed)==1 and row['sender'][changed[0]]['count']==q and row['sender'][changed[0]]['item']==goal['item'],'handoff storage batch merged with old stock');cargo={changed[0]:q};accepted_sender=row['sender'];accepted_custody=row['control']['custodyRevision'];batches+=1;withdrawn+=q;transfers.append(row['operation_ns'])
            if row['event']=='TRAVEL_ACCEPTED':route=row['route'];require(route is not None and route['mode']=='GO_RECIPIENT','handoff did not use guarded native recipient navigation');last_move=None;route_feet=row['sender_pos'];route_travel=0;progress_tick=route['acceptedTick'];last_progress=route_feet
            if row['event']=='MOVE':
                observation=row['detail'];require(route is not None and row['route']==route and (last_move is None or row['tick']==last_move+1),'handoff missing/reset native movement tick');last_move=row['tick'];delta=math.dist(route_feet,row['sender_pos']);require(delta<=.6,'handoff teleported the carrier');route_travel+=delta;travel+=delta;route_feet=row['sender_pos']
                stale_stop=observation['status']=='BLOCKED'and observation['blocker']=='STALE_RECIPIENT'
                if stale_stop:
                    require(previous['event']=='MOVE'and row['sender_navigation_done']and observation['tick']==previous['tick']and all(observation[k]==previous['detail'][k]for k in observation if k not in ('status','blocker')),'recipient guard must retain the exact last accepted observation and stop the actual native path')
                    require(math.dist(row['recipient_pos'],xyz(route['endpoint']))>.25,'recipient stop without actual target motion')
                else:
                    require(xyz(observation['feet'])==row['sender_pos']and xyz(observation['endpoint'])==xyz(route['endpoint'])and observation['elapsedTicks']==row['tick']-route['acceptedTick']and observation['elapsedTicks']<600 and abs(observation['travelBlocks']-route_travel)<1e-6,'handoff invented movement allowance/position')
                    if delta>=.01:progress_tick=row['tick'];last_progress=row['sender_pos']
                require(route_travel<=64,'handoff exceeded actual native travel cap')
                origin=[row['source_binding']['members'][0]['x']-2,row['source_binding']['members'][0]['z']-7]
                if abs(row['sender_pos'][0]-(origin[0]+8.5))<.8:require(row['sender_pos'][2]<origin[1]+3.71 or row['sender_pos'][2]>origin[1]+11.29,'handoff walked through native arena obstacle')
                require(observation['lastProgressTick']==progress_tick and xyz(observation['lastProgress'])==last_progress and row['path_nodes']<=256 and row['path_length']<=64 and row['checked_chunks']<=121,'handoff unbounded route or fabricated progress');movement.append(row['operation_ns'])
            if row['event']=='HANDOFF':
                o=row['detail'];require(row['item_entities']==previous['item_entities'],'handoff threw/spawned an item entity instead of native inventory transfer')
                if o['status']=='LIVE_DEPOSITED':
                    eligible(row);q=o['quantity'];require(cargo is not None and sum(cargo.values())==q and row['control']['custodyRevision']==accepted_custody,'handoff used foreign/replayed custody');a=totals(previous);b=totals(row)
                    require([a[0],a[1]-q,a[2]+q]==b and sum(a)==sum(b)and [o['before'][k]for k in ('source','carrier','destination')]==a and [o['after'][k]for k in ('source','carrier','destination')]==b,'handoff native 9/1 to 5/5 conservation or observation mismatch')
                    for i,old in enumerate(accepted_sender):
                        require(previous['sender'][i]==old,'handoff changed admitted source cargo before effect');amount=cargo.get(i,0);require(row['sender'][i]['count']==old['count']-amount,'handoff consumed older/unattributed sender stock')
                        if amount==0:require(row['sender'][i]==old,'handoff replaced unrelated carrier slot')
                    for old,new in zip(previous['recipient'],row['recipient']):
                        if old['item']is not None and old['item']!=goal['item']:require(old==new,'handoff changed unrelated recipient item/components')
                    delivered+=q;require(delivered<=goal['quantity'],'handoff duplicated completion units');cargo=None;transfers.append(row['operation_ns'])
                else:
                    require(o['status']=='REFUSED'and o['quantity']==0 and o['before']is None and o['after']is None and o['location']=='handoff unavailable; owner revalidation required','handoff denial leaked metadata or claimed effect')
                    require(images(row)==images(previous),'refused handoff changed actual native units')
            if row['event']=='REFUSED_PREPARATION':
                o=row['detail'];require(o['status']=='REFUSED'and o['quantity']==0 and o['before']is None and o['after']is None and images(row)==images(previous),'refused source admission mutated stock or disclosed private data')
            if row['event']=='REPLAY_REFUSED':require(row['detail']['status']=='REFUSED'and row['detail']['reason']=='UNKNOWN_CUSTODY'and row['detail']['quantity']==0 and images(row)==images(previous),'one-use cargo replay affected native stock')
            require(row['delivered']==delivered and row['batches']==batches and batches<=8,'handoff false delivery or batch counter');previous=row
        terminal=frames[-1];require((delivered==goal['quantity'])if name in SUCCESS else delivered==0,'handoff required success/refusal absent')
        if name in SUCCESS:require(terminal['phase']=='DELIVERED_LIVE'and terminal['in_flight']==0,'handoff live terminal absent')
        else:require(terminal['phase']=='BLOCKED','unsafe handoff did not stop')
        if name in ('STORAGE_SUCCESS','STORAGE_REVOKED','STORAGE'):require(travel>=10,'storage-sourced handoff lacks actual native travel')
        if name in ('MOVED_RECIPIENT','MOVED'):require(math.dist(first['recipient_pos'],terminal['recipient_pos'])>.5 and math.dist(terminal['sender_pos'],terminal['recipient_pos'])>2.5,'recipient motion guard lacks real native movement')
        if name in ('RENAMED_NAMESAKE','EXISTING'):require(any(r['event']=='RENAMED_AND_NAMESAKE'and r['namesake']is not None and r['namesake']['entity']!=goal['recipient']['entity']and sum(s['count']for s in r['namesake']['slots'])==17 for r in frames),'renamed stable recipient/namesake positive control absent')
        summaries.append({'campaign':name,'request':goal['request'],'withdrawn':withdrawn,'deposited':delivered,'batches':batches,'travel_blocks':travel,'final_native':totals(terminal),'phase':terminal['phase']})
    def performance(values):
        if not values:return {'samples':0}
        require(all(v>0 for v in values),'handoff timing sample absent');values.sort();p=lambda f:values[math.ceil(len(values)*f)-1];require(p(.99)<=50000000 and max(values)<=250000000,'handoff native operation performance regression');return {'samples':len(values),'p50_ns':p(.5),'p95_ns':p(.95),'p99_ns':p(.99),'max_ns':max(values)}
    return {'kind':'CONSISTENCY_OBSERVATIONS','scenario_credit':0,'campaigns':summaries,'frames':len(rows),'movement_performance':performance(movement),'transfer_performance':performance(transfers)}

def primitive(root):
    lock=read(HERE/'handoff-primitive-lock.json');server=REPO/'build/server-sdk/minecraft-server.jar';require(sha(server)==lock['minecraft_server_sha256'],'unqualified handoff native runtime primitive')
    with ZipFile(server)as z:require({name:hashlib.sha256(z.read(name)).hexdigest()for name in lock['classes']}==lock['classes'],'handoff inventory primitive class bytes changed')
    api=(root/'handoff-api-bytecode.txt').read_text()
    for text in ('public net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> getItems();','java/util/Arrays.asList','public E set(int, E);','Field inventory:Lnet/minecraft/world/SimpleContainer;'):
        require(text in api,'missing actual handoff primitive bytecode evidence')
    require(read(root/'primitive-classes.json')==lock,'handoff primitive class record changed');return {'server_sha256':sha(server),'classes':lock['classes'],'slots':8,'local_live_atomicity_only':True,'participant_persistence':None}

def native(root):
    rows=reconcile(root,root/'evidence','CC_D09_FRAME ','handoff.jsonl');result=trace(rows,'D09-GT01','d0200000-0000-4000-8000-000000000001',NATIVE_CAMPAIGNS);require(all(r['seed']==0 for r in rows),'actual native GameTest seed changed');result['primitive']=primitive(root);result['evidence']=['native/handoff/stdout.log','native/handoff/evidence/handoff.jsonl','native/handoff/handoff-api-bytecode.txt','native/handoff/primitive-classes.json'];return result

def client(root,identity,fixture):
    meta=read(root/'identity.json');require(meta['identity']==identity and meta['case']=='D09-CL01'and meta['world']=='d0900000-0000-4000-8000-000000000001'and meta['phase']=='COLLECTED_RAW'and meta['integration_root']is None and meta['save_proof']is None,'handoff client collection identity/authority absent')
    selected=root/'selected-client-fixture.jar';require(sha(selected)==meta['selected_fixture_sha256']and sha(fixture)==meta['fixture_sha256'],'handoff real fixture identity changed')
    with ZipFile(fixture)as original,ZipFile(selected)as observed:
        require(original.namelist()==observed.namelist(),'handoff client fixture members changed')
        for name in original.namelist():
            if name!='fabric.mod.json':require(original.read(name)==observed.read(name),'handoff client fixture implementation changed')
        descriptor=json.loads(original.read('fabric.mod.json'));descriptor['id']='cognitivecraft_parallel_d_clients';descriptor['entrypoints']={'main':['dev.aivillages.e2e.paralleld.DVillagerHandoffServer']};require(json.loads(observed.read('fabric.mod.json'))==descriptor,'unrequested handoff fixture entrypoint')
    stdout=(root/'server/stdout.log').read_text();actors=read(root/'handoff-clients.json');menus=read(root/'handoff-menus.json');summary={};native_by_mode={};sender_ids=set();recipient_ids=set()
    for mode in CLIENT_MODES:
        ready=read(root/'server/d09'/('ready-'+mode+'.json'));rows=lines(root/'server/d09'/mode/'handoff.jsonl');expected=[json.loads(s.split('CC_D09_FRAME ',1)[1])for s in stdout.splitlines()if 'CC_D09_FRAME 'in s and json.loads(s.split('CC_D09_FRAME ',1)[1])['case']=='D09-CL01-'+mode];require(rows==expected,'handoff client native stdout/raw frame mismatch');native_by_mode[mode]=rows
        require(rows and rows[0]['principal']==ready['principal']==offline('PlayerD_A')and rows[0]['goal']['carrier']['entity']==ready['sender']and rows[0]['goal']['recipient']['entity']==ready['recipient']and rows[0]['goal']['recipient']['citizen']==ready['recipient_citizen']and all(r['seed']==ready['seed']==20261027 for r in rows),'handoff native principal/entity/world seed swapped')
        summary[mode]=trace(rows,'D09-CL01-'+mode,meta['world'],[mode]);sender_ids.add(ready['sender']);recipient_ids.add(ready['recipient'])
        terminal=read(root/'server/d09'/('terminal-'+mode+'.json'));success=mode in ('EXISTING','STORAGE');require(terminal['request']==rows[0]['goal']['request']and terminal['sender']==ready['sender']and terminal['recipient']==ready['recipient']and terminal['delivered']==(4 if success else 0)and terminal['in_flight']==(0 if success else 4),'handoff false live terminal credit')
        for player in PLAYERS:
            frames=[r for r in actors if r['mode']==mode and r['player']==player];require([r['phase']for r in frames]==['BEFORE','ADMITTED','AFTER'],'handoff actual client observation boundaries missing/duplicated')
            for row in frames:
                state=row['state'];entities={e['uuid']:e for e in state['entities']};require(state['play']and not state['singleplayer']and state['uuid']==offline(player)and row['sender']==ready['sender']and row['recipient']==ready['recipient']and entities[ready['sender']]['type']=='minecraft:villager','handoff simulated/disconnected/retargeted client')
                if row['phase']=='AFTER'and mode in ('DEATH','UNLOAD'):require(ready['recipient']not in entities,'dead/unloaded recipient still claimed as available')
                else:require(ready['recipient']in entities and entities[ready['recipient']]['type']=='minecraft:villager','handoff stable native recipient absent')
            after={e['uuid']:e for e in frames[-1]['state']['entities']}
            if mode=='EXISTING':
                rename=next(r for r in rows if r['event']=='RENAMED_AND_NAMESAKE');require(after[ready['recipient']]['name']=='Renamed receiver'and after[rename['namesake']['entity']]['name']=='Receiver','two-client rename/namesake continuity absent')
            if mode=='STORAGE':require(math.dist(after[ready['sender']]['pos'],ready['sender_pos'])>=10 and math.dist(after[ready['sender']]['pos'],after[ready['recipient']]['pos'])<=3,'handoff real clients did not observe carrier travel/arrival')
            if mode=='MOVED':require(math.dist(after[ready['sender']]['pos'],after[ready['recipient']]['pos'])>2.5,'real client did not observe recipient moving out of reach')
        if mode in ('DEATH','UNLOAD'):require(any(r['event']==('EXTERNAL_RECIPIENT_DEAD'if mode=='DEATH'else'EXTERNAL_RECIPIENT_UNLOADED')and not r['recipient_loaded']for r in rows),'actual native recipient removal absent')
    require(len(sender_ids)==len(recipient_ids)==7 and not sender_ids.intersection(recipient_ids),'handoff reused blocked carrier/recipient between client campaigns')
    for player in PLAYERS:
        box=root/'clients'/player;process=read(box/'process.json');events=lines(box/'events.jsonl');observed=lines(box/'observations.jsonl')
        require(process['kind']=='client'and process['releaseSha256']==identity['jar_sha256']and 'net.fabricmc.loader.impl.launch.knot.KnotClient'in process['command']and any(e['kind']=='driver'and e['data']['pid']==process['pid']for e in events)and any(e['kind']=='play'and e['data']['uuid']==offline(player)for e in events),'handoff actual authenticated client JVM absent')
        for row in [r for r in actors+menus if r['player']==player]:require(any(o['state']==row['state']for o in observed),'handoff client snapshot fabricated')
        for mode in CLIENT_MODES:
            data=[m for m in menus if m['mode']==mode and m['player']==player]
            phases=(['SOURCE_BEFORE','BEFORE','SOURCE_WITHDRAWN','WITHDRAWN','AFTER']if player=='PlayerD_A'else['BEFORE','WITHDRAWN','AFTER'])if mode=='STORAGE'else['BEFORE','AFTER'];require([r['phase']for r in data]==phases,'handoff actual native menu snapshots missing/duplicated')
            native=native_by_mode[mode]
            for row in data:
                phase=row['phase'];frame=native[0]if phase in ('BEFORE','SOURCE_BEFORE')else next(r for r in native if r['event']=='WITHDRAWN')if phase in ('WITHDRAWN','SOURCE_WITHDRAWN')else next(r for r in native if r['event']=='HANDOFF')
                field='source'if phase.startswith('SOURCE_')else'sender'if player=='PlayerD_A'else'recipient';expected=frame[field];actual=[r for r in row['state']['slots']if not r['playerInventory']];require(len(actual)==(27 if field=='source'else 9),'handoff native menu is not actual villager eight slots plus empty display slot')
                for index,slot in enumerate(expected):require(actual[index]['slot']==index and actual[index]['count']==slot['count']and actual[index]['item']==(slot['native_item_id']or'minecraft:air'),'handoff actual native GUI/native slot delta mismatch')
                if field!='source':require(actual[8]['count']==0,'handoff fixture padding was counted as real cargo')
        overlay=read(box/'root-overlay.json');require(overlay['observation_sha256']==sha(box/'observation-area.json')and overlay['root']in process['command'][process['command'].index('--gameDir')+1],'handoff bounded observer config not used by actual native client')
        for mode in ('existing','storage'):
            png=(box/'screenshots'/('d09-'+mode+'-'+player+'.png')).read_bytes();require(png[:8]==b'\x89PNG\r\n\x1a\n'and struct.unpack('>II',png[16:24])==(800,600),'actual handoff native GUI screenshot absent')
    audit=read(root/'server/d09/final-custody.json');require(set(audit)==set(CLIENT_MODES),'handoff final custody campaign missing')
    for mode,row in audit.items():
        expected=3 if mode=='STORAGE'else 5 if mode=='EXISTING'else 9;require(row['sender_loaded']and sum(s['count']for s in row['sender_slots'])==expected and row['delivered']==(4 if mode in ('EXISTING','STORAGE')else 0)and row['in_flight']==(0 if mode in ('EXISTING','STORAGE')else 4),'handoff blocked material lost or false completion after later campaigns')
    require(meta['driver_sha256']==sha(REPO/'build/e2e-driver/client.jar')and meta['observer_sha256']==sha(REPO/'build/e2e-driver/observer.jar'),'handoff native SDK driver changed');process=read(root/'server/process.json');require(process['releaseSha256']==identity['jar_sha256']and any(m['sha256']==sha(selected)for m in process['mods']),'handoff native server release/fixture mismatch')
    properties=read(root/'server-properties.json');require(properties['view-distance']==properties['simulation-distance']=='6'and properties['level-seed']=='20261027','handoff isolated fixture tracking/seed changed')
    return [{'id':'D09-CL01','status':'PASS'}],{'scenarios':summary,'client_snapshots':len(actors),'menu_snapshots':len(menus),'blocked_custody_retained':audit,'evidence':['clients-d09/'+p for p in ('identity.json','handoff-clients.json','handoff-menus.json','server/stdout.log','server/d09/final-custody.json','clients/PlayerD_A/events.jsonl','clients/PlayerD_B/events.jsonl')]}
