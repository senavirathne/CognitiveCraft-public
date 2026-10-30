"""Independent D10 observer. Derives outcomes from native slots, routes, saved bytes and client packets."""
import copy,hashlib,json,math,struct,os
from collections import Counter
from zipfile import ZipFile
from common import *
from delivery_oracle import slots
from client_oracle import lines,offline,PLAYERS
from native_nbt import decode
from native_oracle import topology
from save_observer import inventory,uuid

WORLD='d1000000-0000-4000-8000-000000000001'

def no_settlement(value):
    if isinstance(value,dict):
        for k,v in value.items():
            if k in ('integration_root','effect_id','save_proof','participant_save_proof','original_owner_settlement'):require(v is None,'fabricated component root/effect/save flag')
            no_settlement(v)
    elif isinstance(value,list):
        for v in value:no_settlement(v)

def containers(path):
    rows=lines(path);return rows[0]if len(rows)==1 and isinstance(rows[0],list)else rows

def checked_identity(meta,identity):
    require(meta['identity']==identity and meta['identity']['jar_sha256']==identity['jar_sha256'],'wrong D10 source/tree/run/JAR')
    require(meta['world']==WORLD and meta['case']=='D10-CL01' and meta['phase']=='COLLECTED_RAW','incomplete D10 client collection')
    no_settlement(meta)

def count(row,role,item=None):
    c=row['inventories'][role];require(c['inventory_known'],'unknown inventory cannot be interpreted as empty')
    return sum(s['count'] for s in c['slots'] if item is None or s['native_item_id']==item)

def vector(row,item,roles=('source','carrier','destination')):return [count(row,r,item)for r in roles]

def ledger(row):
    result=Counter();seen=set()
    for role,c in row['inventories'].items():
        if not c['inventory_known']:return None
        key=('block',*c['pos']) if 'pos'in c else ('entity',row['entities'].get(role,{}).get('entity',role))
        if key in seen:continue
        seen.add(key)
        for slot in c['slots']:
            if slot['item']is not None:result[(slot['item']['registryId'],slot['item']['fingerprint'])]+=slot['count']
    for e in row['item_entities']:result[(e['item']['registryId'],e['item']['fingerprint'])]+=e['count']
    return +result

def images(row):return row['inventories'],row['item_entities']

def performance(values):
    if not values:return {'samples':0}
    values=sorted(values);require(all(0<v<=250000000 for v in values),'D10 native max operation cost');p=lambda f:values[math.ceil(f*len(values))-1];require(p(.99)<=50000000,'D10 native p99 regression');return {'samples':len(values),'p50_ns':p(.5),'p95_ns':p(.95),'p99_ns':p(.99),'max_ns':values[-1]}

def case(spec,rows,diagnostic=False):
    require(rows and rows[0]['event']=='BEFORE','D10 case missing native before image');first=rows[0];full=[r for r in rows if 'inventories'in r];last=full[-1];labels=set();previous=first;move=[];transfers=[];request=first['request'];n=int(spec['id'][-2:]);group=spec['group'];item=spec['item']
    require(rows[-1]['event']=='TERMINAL' or n==12 and group in ('courier','handoff') and rows[-1]['phase']=='AWAIT_NATIVE_SAVE','D10 missing native terminal or witnessed save pause')
    require(first['entities']['carrier']['entity']==first['actor'],'swapped native carrier')
    for r in rows:
        no_settlement(r);require(r['case']==spec['id']and r['group']==group and r['request']==request and r['actor']==first['actor']and r['principal']==first['principal']and r['world']==first['world']and r['case_seed']==spec['case_seed'],'D10 identity/request/principal/seed changed')
        require(r['operation_label'] not in labels,'duplicated effect label (diagnostic observation label)');labels.add(r['operation_label']);require(r['forced_chunks']==first['forced_chunks']and r['tick']>=previous['tick'],'forced load or clock reset')
        require(0<r['heap_used_bytes']<1100*1024*1024,'D10 native heap regression')
        if not diagnostic:require(not r['facts'].get('diagnostic_mock_players',False),'mock players cannot establish connected-client qualification')
        if 'inventories'in r:
            for c in r['inventories'].values():
                if c['inventory_known']:slots(c['slots'],len(c['slots']))
                else:require(c['slots']is None and group=='storage'and n==11,'hidden inventory converted to empty')
        if r['event']=='MOVE':move.append(r['step_ns'])
        if r['event']in ('WITHDRAW','DEPOSIT','HANDOFF','RIVAL_WITHDRAW','COMPETING_HANDOFF'):transfers.append(r['step_ns']) if r['step_ns'] else None
        elapsed=r['tick']-previous['tick'];require(math.dist(r['native_feet'],previous['native_feet'])<=.6*elapsed+1e-6,'actor teleported between actual native observations')
        previous=r
    outcome='PASS';details={}
    if group=='storage':
        events={r['event']:r for r in rows};checks=[r for r in rows if r['event']in ('REGISTERED','DOUBLE','BARREL','RENAMED','EXPLICIT_REBIND','EXPLICIT_PAIR_REBIND')]
        for r in checks:
            d=r['detail'];topology(d['binding']);require(d['inspect']['eligible'] if 'eligible'in d['inspect']else d['inspect']['refusal']=='NONE','native authorized read refused');require(isinstance(d['snapshot'],dict),'authorized read lacks actual slots')
        if n==1:require(events['NATIVE_SERVER_SELECTION']['detail']['hit']=='BLOCK'and events['NATIVE_SERVER_SELECTION']['detail']['selected']==[spec['origin'][0]+5,spec['origin'][1],spec['origin'][2]+7]and events['NATIVE_SERVER_SELECTION']['detail']['marker_before']and checks,'registration bypassed actual fresh server selection')
        if n==2:require(len(events['DOUBLE']['detail']['snapshot']['slots'])==54 and count(last,'primary',item)==13 and count(last,'right',item)==7,'double native halves missing/double counted')
        if n==3:require(len([r for r in rows if r['event']=='BARREL'])==6 and {r['detail']['binding']['facing']for r in rows if r['event']=='BARREL'}=={'NORTH','SOUTH','EAST','WEST','UP','DOWN'},'barrel six native facing states absent')
        if n==4:
            a=last['facts']['metadata_before'];b=last['facts']['metadata_after'];require(a['id']==b['id']and a['generation']==b['generation']and b['revision']>a['revision']and a['label']!=b['label'],'rename lost stable identity')
        for event in ('REPLACED_OLD_BINDING','PAIRED_OLD_BINDING','SPLIT_OLD_BINDING','CLONE_ORIGINAL','CLONE_COPIED','LOCKED','UNRESOLVED_LOOT'):
            if event in events:require(events[event]['detail']['snapshot']=='INSTANCE'and all(events[event]['detail'][op]['refusal']!='NONE'for op in ('inspect','withdraw','deposit')),'unsafe storage inherited old permission or empty snapshot')
        if n==5:require(events['EXPLICIT_REBIND']['detail']['binding']['members'][0]['incarnation_id']!=first['facts']['original_binding']['members'][0]['incarnation_id'],'replacement inherited incarnation')
        if n==6:require(len(events['EXPLICIT_PAIR_REBIND']['detail']['binding']['members'])==2 and events['EXPLICIT_PAIR_REBIND']['detail']['binding']['binding_generation']>first['facts']['original_binding']['binding_generation'],'pair did not require explicit new generation')
        if n==7:require(count(last,'primary',item)==13 and count(last,'right',item)==7,'split lost physical native halves');outcome='REFUSED_EXPECTED'
        if n==8:require(events['CLONE_ORIGINAL']['detail']['binding']['storage_id']!=events['CLONE_COPIED']['detail']['binding']['storage_id'],'clone did not use separate bindings');outcome='REFUSED_EXPECTED'
        if n==9:
            require(events['PRIVATE_P2']['detail']['snapshot']=='INSTANCE'and events['DEPOSIT_ONLY_VIEW']['detail']['snapshot']=='INSTANCE'and events['DEPOSIT_ONLY_VIEW']['detail']['withdraw']['refusal']!='NONE','private/deposit-only disclosure')
            require(vector(first,item)==[5,2,7]and vector(last,item)==[4,2,8]and last['detail']['delivered']==1,'valid explicit deposit-only use broken');require(last['detail']['caller']!=first['principal'],'deposit-only operation did not use second native principal');require(ledger(first)==ledger(last),'deposit-only hidden residual or loss')
        if n==10:require(events['UNREGISTERED_PUBLIC_LOOKING']['detail']['snapshot']=='INSTANCE'and events['UNREGISTERED_PUBLIC_LOOKING']['detail']['check']['refusal']!='NONE','public appearance granted automation');outcome='REFUSED_EXPECTED'
        if n==11:
            d=events['UNLOADED']['detail'];require(not d['loaded_before']and not d['loaded_after']and d['chest']==d['barrel']=='UNLOADED'and last['facts']['loot_remains_unresolved'],'unloaded/loot control forced load or unwrapped unknown');outcome='REFUSED_EXPECTED'
        if n==12:
            controls=[r['detail']for r in rows if r['event']=='UNSUPPORTED'];require(len(controls)==7 and all(d['chest']==d['barrel']=='UNSUPPORTED'and not d['eligible_incarnation']for d in controls),'unsupported native storage gained incarnation/automation access');outcome='REFUSED_EXPECTED'
        if n not in (6,8,11):require(ledger(first)==ledger(last),'storage observation/rename/rebind changed physical distribution')
        if n in (6,8):
            a=ledger(first);b=ledger(last);key=next(k for k in b if k[0]==item);a[key]+=7;require(a==b,'unclassified topology/clone external residual')
    elif group=='courier':
        if n!=4:require(all(r['facts']['goal']==first['facts']['goal']for r in rows),'courier original goal changed')
        for b in ('source_binding','destination_binding'):
            for r in rows:topology(r['facts'][b])
        previous=first;withdrawn=deposited=batches=0;old=first['inventories']['carrier']['slots'];cargo=None
        for r in full[1:]:
            d=r['detail'];event=r['event']
            if event in ('WITHDRAW','DEPOSIT'):
                require(previous['event']=='BEFORE_'+event and previous['tick']==r['tick'],'courier lacks an immediate same-tick native before image')
                a=vector(previous,item);b=vector(r,item);require(ledger(previous)==ledger(r),'courier operation lost/minted hidden residual')
                if d['status'].startswith('LIVE_'):
                    q=d['quantity'];require([d['before'][k]for k in ('source','carrier','destination')]==a and [d['after'][k]for k in ('source','carrier','destination')]==b,'courier claimed counts differ from native slots')
                    if event=='WITHDRAW':require(cargo is None and b==[a[0]-q,a[1]+q,a[2]],'courier guarded debit mismatch');cargo=q;withdrawn+=q;batches+=1
                    else:require(cargo==q and b==[a[0],a[1]-q,a[2]+q],'old/foreign carrier stock discharged cargo');cargo=None;deposited+=q
                else:require(d['status']=='REFUSED'and d['quantity']==0 and images(previous)==images(r),'refused courier mutated native participants')
            if event=='RIVAL_WITHDRAW':require(previous['event']=='BEFORE_RIVAL_WITHDRAW'and previous['tick']==r['tick']and images(previous)==images(r)and d['status']=='REFUSED'and d['reason']=='SHORTAGE'and count(r,'rival',item)==0,'rival debited same last stack twice')
            previous=r
        require(batches<=8 and deposited<=spec['quantity'],'courier original allowance reset');require(last['inventories']['carrier']['slots'][0]['count']==old[0]['count']and last['inventories']['carrier']['slots'][0]['item']==old[0]['item'],'old carrier stock credited or replaced')
        if n in (1,2,3,11):
            q=spec['quantity'];require(deposited==q and withdrawn==q and vector(last,item)==([0,3,64]if n==3 else[0,3,6]if n==11 else[10-q,3,2+q]),'required courier exact quantity/delta absent')
            if n==3:require(batches==4,'q64 did not retain one original four-batch request')
        elif n==12:require(withdrawn==4 and deposited==0 and vector(last,item)==[6,7,2],'restart did not cut actual in-flight native cargo')
        else:
            outcome='REFUSED_EXPECTED';require(deposited==0,'unsafe courier deposited cargo')
            if n in (7,8,9):require(withdrawn==4 and count(last,'carrier',item)==7,'unsafe destination lost attributed cargo')
            else:require(withdrawn==0,'unsafe courier withdrew before admission')
            if n==9:
                requested=next(r for r in rows if r['event']=='EXTERNAL_GRANT_REVOCATION_REQUESTED');ack=next(r for r in rows if r['event']=='EXTERNAL_GRANT_REVOKED');require(requested['phase']=='MOVING_TO_DESTINATION'and ack['facts']['destination_record']['acl']==[]and ack['facts']['destination_record']['aclRevision']>first['facts']['destination_record']['aclRevision']and next(r for r in rows if r['event']=='DEPOSIT')['detail']['reason']=='UNAUTHORIZED','grant revocation did not commit during original travel before final effect guard')
            if n==4:require(last['detail']['typed_refusal']=='QUANTITY_OUT_OF_RANGE'and images(first)==images(last),'q65 had physical effect')
            if n==10:
                selected=next(r for r in rows if r['event']=='SELECTED_SOURCE');effect=next(r for r in rows if r['event']=='WITHDRAW');require(0<=effect['tick']-selected['detail']['tick']<=40 and selected['detail']['count']==10,'stolen-source control did not recheck a still-fresh actual selection');require(vector(last,item)==[6,3,2]and count(last,'external_custody',item)==4,'stolen source falsely authorized or unclassified')
        if n not in (7,8):require(ledger(first)==ledger(last),'courier final unclassified external residual')
        if n==7:
            expected=ledger(first).copy();oak=next(k for k in expected if k[0]==item);expected[oak]-=2;stone=next(k for k in ledger(last)if k[0]=='minecraft:stone');expected[stone]+=1728;require(+expected==ledger(last),'full-during-travel external replacement not conserved')
        if n==8:require(ledger(first)==ledger(last),'replaced destination lost native break drops')
        details={'withdrawn':withdrawn,'deposited':deposited,'batches':batches,'live_native_vector':vector(last,item),**delivery_movement(rows)}
    elif group=='handoff':
        g=first['facts']['goal'];require(g['request']==request and g['carrier']['entity']==first['actor']and g['carrier']['principal']==first['principal']and g['recipient']['entity']==first['entities']['recipient']['entity'],'swapped recipient/carrier/principal lineage')
        require(all(r['facts']['goal']==g for r in rows),'handoff rebound original recipient/request')
        previous=first;admitted=None;deposited=0;controls={}
        for r in full[1:]:
            event=r['event'];d=r['detail']
            if event=='ADMITTED':
                grant=r['facts']['selected_source'];require(grant and grant['request']==g['request']and grant['principal']==first['principal']and grant['sender']==g['carrier']['citizen']and 0<=r['tick']-grant['observed_tick']<=40,'handoff missing explicit fresh source grant');admitted=r
                require(images(previous)==images(r),'existing source admission changed actual inventory')
                for selected in grant['slots']:
                    slot=r['inventories']['carrier']['slots'][selected['slot']];require(slot['count']==selected['native_count']and slot['item']==selected['item']and slot['native_object']==selected['native_object'],'source grant not actual same native slots')
            if event in ('HANDOFF','COMPETING_HANDOFF','REFUSED_PREPARATION'):
                require(ledger(previous)==ledger(r),'handoff operation changed conservation or hidden residual')
                if d['status']=='LIVE_DEPOSITED':
                    require(event=='HANDOFF'and admitted is not None,'handoff discharged ungranted/replayed stock');q=d['quantity'];a=vector(previous,item,('carrier','recipient'));b=vector(r,item,('carrier','recipient'));require(a==[9,1]and b==[5,5]and q==4 and r['item_entities']==previous['item_entities'],'handoff failed exact native 9/1 to 5/5 or spawned items')
                    projection=r['facts']['recipient_projection'];control=r['facts']['control'];target=g['recipient'];require(projection['entity']==target['entity']==r['entities']['recipient']['entity']and projection['id']==target['citizen']and all(projection[k]==target[k]for k in ('generation','policyRevision','epoch','dimension')),'handoff swapped native recipient generation')
                    require(all(control[k]for k in ('controlled','exclusiveSender','exclusiveRecipient','existingSourceAuthorized','protectionAllowed'))and projection['loaded']and projection['visible']and 'DEPOSIT'in projection['permitted']and projection['tick']==r['tick'],'handoff ignored current scope/permission/custody')
                    require(math.dist(r['entities']['carrier']['pos'],r['entities']['recipient']['pos'])<=2.5+1e-8 and r['entities']['recipient']['loaded'],'handoff outside actual native reach/current entity');deposited+=q
                else:require(d['status']=='REFUSED'and d['quantity']==0 and d['before']is None and d['after']is None and images(previous)==images(r),'refused handoff mutated/disclosed inventories')
            previous=r
        if n in (1,3,11,12):require(deposited==4,'required live handoff absent')
        else:require(deposited==0 and count(last,'carrier',item)==9,'unsafe handoff debited sender');outcome='REFUSED_EXPECTED'
        if n==3:require(count(last,'namesake',item)==17 and last['facts']['recipient_projection']['entity']==g['recipient']['entity'],'rename rerouted stable recipient')
        if n==4:require(next(r for r in rows if r['event']=='FRESH_NAME_RESOLUTION')['detail']['status']=='CLARIFY'and first['entities']['namesake']['entity']!=g['recipient']['entity']and first['entities']['namesake']['native_name']==first['entities']['recipient']['native_name']=='Receiver','namesake ambiguity lacks distinct actual native participants')
        if n==5:require(last['entities']['recipient']['entity']!=g['recipient']['entity']and not last['entities']['retired_recipient']['loaded'],'entity replacement did not invalidate old generation')
        if n in (6,7):require(not last['entities']['recipient']['loaded'],'recipient disappearance was fabricated')
        if n==7:require(not last['entities']['recipient']['alive'],'recipient death has no actual native disappearance')
        if n==8:require('DEPOSIT'not in last['facts']['recipient_projection']['permitted']and last['facts']['recipient_projection']['policyRevision']>g['recipient']['policyRevision'],'revocation not at final native check')
        if n==9:require(math.dist(last['entities']['carrier']['pos'],last['entities']['recipient']['pos'])>2.5,'out-of-reach control not actually distant')
        if n==10:require(g['sourceCitizen']!=g['carrier']['citizen']and count(last,'donor',item)==24,'third-party donor rejection absent')
        if n==11:require(any(r['event']=='COMPETING_HANDOFF'and r['detail']['status']=='REFUSED'and r['detail']['reason']=='INTERFERENCE'for r in rows),'competing source revision debited twice')
        if n not in (3,7):require(ledger(first)==ledger(last),'handoff final hidden residual/loss')
        elif n==7:
            before=ledger(first);after=ledger(last);drop_count=sum(d['count']for d in last['item_entities']if d['item']['registryId']==item);require(0<=drop_count<=1,'native death fabricated compensation');key=next(k for k in before if k[0]==item);before[key]+=drop_count;require(before==after,'unclassified native death residual');require(first['inventories']['carrier']['slots']==last['inventories']['carrier']['slots'],'native death changed sender stock')
        else:
            expected=ledger(first).copy();key=next(k for k in expected if k[0]==item);expected[key]+=17;require(expected==ledger(last),'rename/new-namesake external stock mismatch')
        details={'deposited':deposited,'live_native_vector':vector(last,item,('carrier','recipient'))}
    else:
        details=movement(spec,rows);outcome='REFUSED_EXPECTED'if n in (3,4,8,9,10,11,12)else'PASS'
    return {'case':spec['id'],'disposition':outcome,'frames':len(rows),'movement_performance':performance(move),'transfer_performance':performance(transfers),**details}

def delivery_movement(rows):
    route=None;previous=None;last_tick=None;total=0;route_distance=0;count=0;last_observation=None;ownership_interruptions=0
    for row in rows:
        if row['event']in ('TRAVEL_ACCEPTED','RETURN_ACCEPTED'):
            route=row['facts']['route'];previous=row['native_feet'];last_tick=None;route_distance=0;last_observation=None
            require(route['mode']=='GO_NAMED_STORAGE'and route['actor']['entity']==row['actor']and route['actor']['principal']==row['principal'],'courier route belongs to another native actor/principal')
        if row['event']!='MOVE':continue
        count+=1;observation=row['detail'];require(route is not None and row['facts']['route']==route,'courier movement has no immutable accepted route')
        if last_tick is not None:require(row['tick']==last_tick+1,'courier skipped a native control tick')
        delta=math.dist(previous,row['native_feet']);require(delta<=.6,'courier teleported while carrying cargo');total+=delta;route_distance+=delta;previous=row['native_feet'];last_tick=row['tick']
        require(row['facts']['path_nodes']<=256 and row['facts']['path_length']<=64 and row['facts']['checked_chunks']<=121 and route['deadline']<=route['acceptedTick']+600 and len(route['standing'])<=8,'courier widened native route limits')
        require(row['tick']-route['acceptedTick']<=600 and route_distance<=64.6,'courier exceeded original route time/travel')
        if observation['status']=='BLOCKED'and observation['blocker']in ('STALE_STORAGE','NAVIGATION_CHANGED'):
            require(last_observation is not None and all(observation[k]==last_observation[k]for k in observation if k not in ('status','blocker')),'native guard did not retain its exact last accepted observation')
            if observation['blocker']=='STALE_STORAGE':require(row['native_navigation_done'],'stale storage guard kept owned native navigation')
            else:ownership_interruptions+=1
        else:require([observation['feet'][k]for k in ('x','y','z')]==row['native_feet']and abs(observation['travelBlocks']-route_distance)<1e-6,'courier movement observation differs from actual native feet/travel')
        last_observation=observation
    require(total<=64.6,'courier reset its cumulative component travel allowance');return {'native_control_ticks':count,'native_travel_blocks':total,'native_path_ownership_interruptions':ownership_interruptions}

def movement(spec,rows):
    n=int(spec['id'][-2:]);moves=[r for r in rows if r['event']=='MOVE'];require(moves,'missing actual native movement/control ticks');last_tick=None;session=None;last_feet=None;distance=0;observed={};pauses=0;routes={};last_repath={}
    for r in moves:
        d=r['detail'];o=d['observation'];goal=r['facts']['goal'];sid=goal.get('session',str(goal.get('acceptedTick')))
        if sid!=session:
            session=sid;last_tick=None;last_feet=None;distance=0;observed[sid]=[];last_repath[sid]=0
        if last_tick is not None:require(r['tick']==last_tick+1,'missing native control tick or reset original follow session');delta=math.dist(last_feet,r['native_feet']);require(delta<=.6,'actor teleport or unbounded per-tick movement');distance+=delta
        last_tick=r['tick'];last_feet=r['native_feet'];observed[sid].append(r)
        require(r['facts']['path_nodes']<= (32 if n>=6 else 256)and r['facts']['path_length']<=(8 if n>=6 else 64),'unbounded native route/lookahead')
        if n>=6:
            require(o['session']==goal['session']and o['acceptedTick']==goal['acceptedTick']and o['deadline']<=goal['deadline']<=goal['acceptedTick']+1200 and o['traveled']<=128.6 and o['pausedTicks']<=100,'follow reset/widened original allowances')
            require(r['tick']-goal['acceptedTick']<=1201,'follow exceeded original deadline');pauses=max(pauses,o['pausedTicks'])
            if o['action']=='MOVE_PROPOSAL':
                proposal=o['proposal'];feet=o['feet'];require(math.dist([proposal[k]for k in ('x','y','z')],[feet[k]for k in ('x','y','z')])<=8+1e-8,'follow proposal exceeds eight loaded blocks')
            if o['repaths']>last_repath[sid]:
                if sid in routes:require(r['tick']-routes[sid]>=10,'follow repath faster than ten ticks')
                routes[sid]=r['tick'];last_repath[sid]=o['repaths']
            if not d['connected']:require(r['native_navigation_done'],'disconnect retained owned native path')
        else:
            require(o['elapsedTicks']<=600 and o['travelBlocks']<=64.6 and o['standingCandidates']<=8 and r['facts']['checked_chunks']<=121,'native movement exceeded frozen bounds')
            require(o['feet']==dict(zip(('dimension','x','y','z'),[goal['actor']['dimension'],*r['native_feet']])),'native movement claimed another position')
    last=rows[-1]['detail'];goal=rows[-1]['facts']['goal'];details={'control_ticks':len(moves),'sessions':len(observed),'pause_ticks':pauses}
    if n in (1,2,5):
        require(last['status']=='ARRIVED'and math.dist(rows[-1]['native_feet'],[last['endpoint'][k]for k in ('x','y','z')])<=1.5+1e-8,'required captured/native named arrival absent')
        if n==1:require(any(math.dist(r['detail']['native_player'],[goal['endpoint'][k]for k in ('x','y','z')])>2 for r in moves),'COME did not retain old target after actual player motion')
        if n==5:require(rows[-1]['facts']['unsupported_landmark']=='CLARIFY_UNSUPPORTED_LANDMARK'and rows[-1]['facts']['named_storage_id']==rows[0]['facts']['destination_binding']['storage_id'],'unsupported place guessed endpoint')
    if n in (3,4):require(last['status']=='BLOCKED'and last['blocker']in ({'UNREACHABLE','COLLISION','NO_PROGRESS'}if n==3 else {'UNLOADED','UNREACHABLE','TRAVEL_LIMIT'}),'missing actual finite no-path/unloaded refusal')
    if n==4:require(not rows[-1]['facts']['bridge_loaded_before']and not rows[-1]['facts']['bridge_loaded_after']and all(math.dist(r['native_feet'],rows[0]['native_feet'])<.01 for r in moves),'unloaded bridge was forced or actor entered unknown route')
    if n>=6:
        in_band=[r for r in moves if r['detail']['connected']and r['detail']['player_dimension']==goal['actor']['dimension']and 2<=math.dist(r['native_feet'],r['detail']['native_player'])<=4.1]
        require(in_band,'follow lacks feasible native 2–4 block band')
        if n in (6,7):require(max(math.dist(in_band[0]['detail']['native_player'],r['detail']['native_player'])for r in in_band)>2,'follow did not observe ordinary moving player')
        if n in (6,7,8,12):require(last['reason']=='CANCELLED'and rows[-1]['native_navigation_done'],'STOP failed to remove owned path')
        if n==9:require(last['reason']=='PAUSE_LIMIT'and pauses==100,'actual disconnect did not exhaust original finite pause')
        if n==10:require(last['reason']=='DIMENSION_CHANGED'and any(r['detail']['player_dimension']!='minecraft:overworld'for r in moves),'dimension change guard lacks native target transition')
        if n==8:
            stop=next(r for r in rows if r['event']=='STOP_REQUESTED');require(stop['detail']['prior_path_present']and stop['detail']['navigation_done']and rows[-1]['tick']-stop['tick']<=1,'STOP was not witnessed on the owned native path by next control tick')
        if n==11:
            require(len(observed)==2 and pauses>=30 and last['reason']=='TRAVEL_LIMIT','expiry/resume reset time/travel allowances');expiry=next(r for r in rows if r['event']=='TIME_EXPIRY');require(expiry['detail']['reason']=='DEADLINE'and expiry['tick']-expiry['detail']['acceptedTick']==1200,'original time expiry not observed')
        if n==12:require(any(r['event']=='COMPETING_CONTROLLER_REFUSED'and r['detail']['refused']and r['detail']['same_navigation']and r['detail']['primary_path_active']for r in rows),'competing owner acquired native controller')
    return details

def saved(distribution,descriptor):
    value=read(distribution/'distributions.json');no_settlement(value);require(value['classification']=='UNKNOWN_NATIVE_PARTICIPANT_PERSISTENCE','native participant observation falsely settled');actual={}
    for name,spec in descriptor['participants'].items():
        values=[]
        for role in ('source','carrier','destination')if name=='courier'else('sender','recipient'):
            witness=value['participants'][name]['roles'][role];path=distribution/witness['witness'];require(sha(path)==witness['witness_sha256'],'saved witness changed');nbt=decode(path);p=spec[role];raw=read(path);pos=p['pos'];require([raw['chunk_x'],raw['chunk_z']]==[int(pos[0])>>4,int(pos[2])>>4],'saved participant wrong chunk')
            if 'entity'in p:found=[r for r in nbt['Entities']if uuid(r['UUID'])==p['entity']]
            else:found=[r for r in nbt['block_entities']if [r[k]for k in ('x','y','z')]==p['pos']]
            require(len(found)==1,'saved native participant UUID/position swapped');require(found[0].get('cognitivecraft_storage_v1')==witness.get('native_marker'),'saved actual marker altered');quantity=inventory(found[0],spec['item']);require(quantity==witness['count'],'saved count fabricated');values.append(quantity)
        require(values==value['participants'][name]['vector'],'saved native vector fabricated');actual[name]=values
    return actual

def native_restart(root):
    write_root=root/'observed-restart-write';read_root=root/'observed-restart-read';d=read(write_root/'evidence/participants.json');before=lines(write_root/'evidence/restart.jsonl');after=lines(read_root/'evidence/restart.jsonl');cuts=lines(write_root/'evidence/save-cuts.jsonl')
    require([r['event']for r in before]==['SAVED_BASELINE','LIVE_AFTER_NATIVE_SAVE_CUT']and [r['event']for r in after]==['FRESH_RESTORED_UNKNOWN','NO_AUTOMATIC_COMPONENT_REPLAY'],'missing native save/reload observations');require(before[0]['pid']!=after[0]['pid']and before[0]['jvm_start_ms']<after[0]['jvm_start_ms']and after[-1]['tick']-after[0]['tick']==100,'native restart reused process or skipped replay window')
    for phase,rows in [(write_root,before),(read_root,after)]:
        stdout=(phase/'stdout.log').read_text();require([json.loads(s.split('CC_D10_RESTART ',1)[1])for s in stdout.splitlines()if 'CC_D10_RESTART 'in s]==rows,'native restart stdout/raw mismatch')
        for r in rows:no_settlement(r);require(r['classification']=='UNKNOWN_NATIVE_PARTICIPANT_PERSISTENCE','native restart minted save settlement')
    require(cuts and all(c['event']=='NATIVE_ENTITY_CHUNK_WRITE_WITHHELD'and c['recipient']==d['participants']['handoff']['recipient']['entity']for c in cuts),'native recipient partial-write fault not witnessed')
    bindings=read(read_root/'evidence/binding-reload.json');require(len(bindings)==4,'missing native marker reloads')
    for b in bindings:
        topology(b['before']);topology(b['fresh']);require(all(b['before'][k]==b['fresh'][k]for k in ('storage_id','binding_generation','topology_sha256','members'))and b['before']['lifecycle_epoch']!=b['fresh']['lifecycle_epoch'],'native marker identity/epoch reload mismatch')
    expected={'courier':[6,7,2],'handoff':[5,1]}
    for p in (write_root/'prekill-saved',write_root/'saved',read_root/'input-saved'):require(saved(p,d)==expected,'native actual saved distributions differ from frozen cut')
    require(before[0]['participants']['courier']['vector']==[10,3,2]and before[0]['participants']['handoff']['vector']==[9,1]and before[-1]['participants']['courier']['vector']==[6,7,2]and before[-1]['participants']['handoff']['vector']==[5,5],'native live save boundaries missing actual inventory effect')
    for r in after:require({k:v['vector']for k,v in r['participants'].items()}==expected,'restored native vector automatically replayed/credited')
    return {'case':'D10-GT01','restored':expected,'classification':'UNKNOWN_NATIVE_PARTICIPANT_PERSISTENCE','original_owner_settlement':None,'automatic_replay':False,'native_write_callbacks':len(cuts),'read_only_ticks':100}

def client(root,identity,fixture):
    meta=read(root/'identity.json');checked_identity(meta,identity);require(meta['scenario_sha256']==sha(HERE/'scenarios.json')==registry('D10')['component_suite']['scenario_sha256'],'frozen D10 scenario bytes changed');require(meta['driver_sha256']==sha(REPO/'build/e2e-driver/client.jar')and meta['observer_sha256']==sha(REPO/'build/e2e-driver/observer.jar'),'established actual client driver bytes changed');selected=root/'selected-client-fixture.jar';require(sha(selected)==meta['selected_fixture_sha256']and sha(fixture)==meta['fixture_sha256'],'wrong D10 actual fixture JAR')
    with ZipFile(fixture)as original,ZipFile(selected)as actual:
        require(original.namelist()==actual.namelist(),'D10 fixture members changed')
        for name in original.namelist():
            if name!='fabric.mod.json':require(original.read(name)==actual.read(name),'D10 fixture runtime bytes changed')
        d=json.loads(original.read('fabric.mod.json'));d['id']='cognitivecraft_parallel_d_clients';d['entrypoints']={'main':['dev.aivillages.e2e.paralleld.DComponentCampaignServer']};require(json.loads(actual.read('fabric.mod.json'))==d,'wrong D10 native fixture entrypoint')
    specs=read(HERE/'scenarios.json')['cases'];frozen=read(HERE/'campaigns/D10-native-48.json');require(len(specs)==48 and [s['id']for s in specs]==[s['id']for s in frozen['cases']]and all(all(s[k]==f[k]for k in f)for s,f in zip(specs,frozen['cases'])),'frozen 48 case expectations changed')
    stdout=(root/'server/stdout.log').read_text();native_rows=[json.loads(s.split('CC_D10_COMPONENT ',1)[1])for s in stdout.splitlines()if 'CC_D10_COMPONENT 'in s];require({r['case']for r in native_rows}=={s['id']for s in specs},'missing/unknown actual D10 native cases')
    preparations=[json.loads(s.split('CC_D10_PREPARATION ',1)[1])for s in stdout.splitlines()if 'CC_D10_PREPARATION 'in s];require(len(preparations)==49,'missing native observer platform preparations')
    for spec in specs:
        cid=spec['id'];x,y,z=spec['origin'];expected=[('area-preparation.json',x,y-1,z,20,25)]
        if cid=='D10-MOBILITY-04':expected.append(('distant-area-preparation.json',x+99998,y-1,z+100005,5,5))
        for filename,xx,yy,zz,width,depth in expected:
            prepared=read(root/'server/d10'/cid/filename);require(prepared in preparations and prepared['case']==cid and prepared['world']==WORLD and prepared['players']==[offline(p)for p in PLAYERS]and prepared['forced_before']==prepared['forced_after']==0,'observer floor identity/forced chunk mismatch')
            expected_blocks={(xx+a,yy,zz+b)for a in range(width)for b in range(depth)};require(len(prepared['blocks'])==len(expected_blocks)and {tuple(b['pos'])for b in prepared['blocks']}==expected_blocks and all(b['block']=='minecraft:stone'for b in prepared['blocks']),'actual native observer floor missing or outside frozen arena')
            expected_chunks={(cx,cz)for cx in range(xx>>4,((xx+width-1)>>4)+1)for cz in range(zz>>4,((zz+depth-1)>>4)+1)};require(len(prepared['chunks'])==len(expected_chunks)and {(c['x'],c['z'])for c in prepared['chunks']}==expected_chunks and all(c['loaded']for c in prepared['chunks']),'observer floor bypassed actual loaded chunk observation')
            before=lines(root/'server/d10'/cid/'frames-final.jsonl');require(prepared['tick']<=before[0]['tick']if filename=='area-preparation.json'else prepared['tick']<=next(r['tick']for r in before if r['event']=='ACCEPTED'),'observer floor was prepared after component admission')
    observations=read(root/'component-clients.json');menus=read(root/'component-menus.json');summaries=[];actors=set()
    for spec in specs:
        cid=spec['id'];box=root/'server/d10'/cid;rows=lines(box/'frames-final.jsonl');commit=read(box/'frames-commit.json');require(commit['case']==cid and commit['sha256']==sha(box/'frames-final.jsonl')and commit['frames']==len(rows)and commit['bytes']==(box/'frames-final.jsonl').stat().st_size,'native final frame file commitment changed');progress=lines(box/'frames.jsonl');require(progress==rows[:len(progress)],'progress stream diverges from committed native observations');require(rows==[r for r in native_rows if r['case']==cid],'D10 raw/native stdout frame mismatch');require(all(r['world']==WORLD and r['world_seed']==20261027 and r['principal']==offline('PlayerD_A')and r['forced_chunks']==0 for r in rows),'wrong D10 actual world/principal/seed/forced chunks');actors.add(rows[0]['actor']);summaries.append(case(spec,rows));require(summaries[-1]['disposition']==spec['expected_disposition'],'frozen component expected disposition not met')
        for player in PLAYERS:
            for phase in ('BEFORE','AFTER'):
                samples=[r for r in observations if r['case']==cid and r['player']==player and r['phase']==phase];require(len(samples)==1,'missing/duplicate two-player case snapshot');state=samples[0]['state'];require(state['play']and not state['singleplayer']and state['uuid']==offline(player),'swapped real connected player snapshot')
                require(any(e['uuid']==rows[0]['actor']for e in state['entities']),'D10 client did not actually witness native actor')
                current=rows[0]if phase=='BEFORE'else rows[-1];actual={e['uuid']:e for e in state['entities']}
                for role,e in current['entities'].items():
                    if e['loaded']and e['alive']:require(e['entity']in actual and actual[e['entity']]['name']==e['native_name'],'D10 client did not witness actual target/namesake identity/name')
                    if not e['loaded']:require(e['entity']not in actual,'D10 client did not witness actual entity disappearance')
        full=[r for r in rows if 'inventories'in r]
        if spec['group']!='mobility':
            for phase,native in [('BEFORE',full[0]),('AFTER',full[-1])]:
                if int(cid[-2:])==12 and spec['group']in ('courier','handoff')and phase=='AFTER':continue
                for role,c in native['inventories'].items():
                    if not c['inventory_known']or role in native['entities']and not native['entities'][role]['loaded']:continue
                    for player in PLAYERS:
                        if cid=='D10-STORAGE-09'and player=='PlayerD_B':require(not any(m['case']==cid and m['player']==player for m in menus),'private/deposit-only stock disclosed to P2');continue
                        matching=[m for m in menus if m['case']==cid and m['phase']==phase and m['role']==role and m['player']==player];require(len(matching)==1,'missing real native menu witness');m=matching[0];actual=[s for s in m['state']['slots']if not s['playerInventory']];require([(s['item']if s['count']else None,s['count'])for s in actual[:len(c['slots'])]]==[(s['native_item_id'],s['count'])for s in c['slots']]and all(s['count']==0 for s in actual[len(c['slots']):]),'actual native GUI does not match all slots')
        if spec['group']in ('courier','handoff')and cid.endswith('-12'):
            d=read(box/'participants.json');expected={spec['group']:[6,7,2]if spec['group']=='courier'else[5,1]};require(saved(box/'prekill-saved',d)==saved(box/'killed-saved',d)==expected,'client actual save-cut distribution absent');boundary=read(root/'boots'/cid/'boundary.json');require(boundary['kill_kind']=='SIGKILL'and boundary['disk_sha256']==sha(box/'prekill-saved/distributions.json'),'unwitnessed real client kill window');no_settlement(boundary)
            killed=containers(root/'boots'/cid/'containers-after-kill.jsonl');restarted=containers(root/'boots'/cid/'containers-after-restart.jsonl');require(len(killed)==len(restarted)==3 and {c['Service']for c in killed}=={c['Service']for c in restarted}=={'minecraft','client-a','client-b'},'actual three-process restart missing');require(next(c for c in killed if c['Service']=='minecraft')['ExitCode']==137,'actual native server was not killed by SIGKILL');require(all(next(c for c in killed if c['Service']==n)['ID']!=next(c for c in restarted if c['Service']==n)['ID']for n in ('minecraft','client-a','client-b')),'client/server container namespace reused')
            for kind,process in boundary['before'].items():require(boundary['after'][kind]['start']>process['start']and process['releaseSha256']==boundary['after'][kind]['releaseSha256']==identity['jar_sha256'],'client/server JVM was not restarted on exact JAR')
            restored=lines(box/'restart.jsonl');require([r['event']for r in restored]==['LIVE_AFTER_NATIVE_SAVE','FRESH_RESTORED_UNKNOWN','NO_AUTOMATIC_COMPONENT_REPLAY']and restored[-1]['tick']-restored[1]['tick']==100,'fresh client native no-replay window missing')
            require(restored==[json.loads(s.split('CC_D10_RESTART ',1)[1])for s in stdout.splitlines()if 'CC_D10_RESTART 'in s and json.loads(s.split('CC_D10_RESTART ',1)[1])['case']==cid],'native client restart stdout/raw mismatch')
            for r in restored[1:]:no_settlement(r);require({k:v['vector']for k,v in r['participants'].items()}==expected,'client restored UNKNOWN vector silently replayed')
            for player in PLAYERS:
                require(any(m['case']==cid and m['player']==player and m['phase']=='AFTER_RELOAD'for m in menus),'restored native inventory not actually visible to both clients')
    require(all(c['Image']==os.environ.get('CC_D_RUNTIME_IMAGE','cognitivecraft-e2e:java25')for c in containers(root/'containers.jsonl')),'D10 runtime container image changed');require(len(actors)==48,'native case identity reused');require(read(root/'campaign-progress.json')['completed']==[s['id']for s in specs],'incomplete 48-case schedule')
    server=read(root/'server/process.json');require(server['releaseSha256']==identity['jar_sha256']and any(m['sha256']==meta['selected_fixture_sha256']for m in server['mods']),'actual native server used wrong release/fixture')
    for player in PLAYERS:
        process=read(root/'clients'/player/'process.json');require(process['releaseSha256']==identity['jar_sha256']and process['kind']=='client'and '25.'in process['javaVersion'],'wrong actual D10 client process/JAR/runtime')
        require(process['renderEnvironment'].get('SDL_VIDEO_DRIVER')=='offscreen'and process['renderEnvironment'].get('LIBGL_ALWAYS_SOFTWARE')=='1','actual native rendering environment changed')
        observed=lines(root/'clients'/player/'observations.jsonl')
        for row in [r for r in observations+menus if r['player']==player]:require(any(o['state']==row['state']for o in observed),'D10 client snapshot was fabricated')
        events=lines(root/'clients'/player/'events.jsonl');require(any(e['kind']=='play'for e in events),'no actual multiplayer events')
    shots=read(root/'screenshots.json');require(len(shots)==24 and len({(s['case'],s['player'])for s in shots})==24,'missing actual D10 native screenshot schedule')
    for shot in shots:
        png=(root/'clients'/shot['player']/'screenshots'/(shot['case'].lower()+'-'+shot['player']+'.png')).read_bytes();require(png[:8]==b'\x89PNG\r\n\x1a\n'and struct.unpack('>II',png[16:24])==(800,600),'actual native rendered screenshot bytes missing')
    return {'kind':'NATIVE_COMPONENT_WITH_REAL_CLIENT_WITNESS','cases':summaries,'planned':48,'executed':48,'passed':sum(s['disposition']=='PASS'for s in summaries),'refused_expected':sum(s['disposition']=='REFUSED_EXPECTED'for s in summaries),'failed':0,'inconclusive':0,'players':2,'native_frames':len(native_rows),'client_snapshots':len(observations),'native_menus':len(menus),'screenshots':len(shots),'production_activation':None,'participant_save_proof':None}
