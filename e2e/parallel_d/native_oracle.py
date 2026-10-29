"""Independent physical oracle: exact native XML, live snapshots, saved NBT and predecessor baseline."""
import base64,hashlib,json,xml.etree.ElementTree as ET
from zipfile import ZipFile
from common import *
from native_nbt import saved_entities

WORLD='d0200000-0000-4000-8000-000000000001'
PRINCIPAL='d0200000-0000-4000-8000-000000000002'
PREFIX='cognitivecraft_parallel_d_tests:'
METHODS={
 'baseline':'dstorage_baseline_game_tests_unchanged_baseline_lifecycle_cost',
 'incarnation':'dstorage_incarnation_game_tests_break_rebuild_refuses_old_binding_and_rename_preserves_identity',
 'topology':'dstorage_topology_game_tests_pairing_splitting_repairing_and_cloned_markers_refuse_stale_bindings',
 'reload-write':'dstorage_topology_reload_game_tests_witnessed_cold_reload_preserves_matching_marker_and_refuses_pending_or_mismatch',
 'reload-read':'dstorage_topology_reload_game_tests_witnessed_cold_reload_preserves_matching_marker_and_refuses_pending_or_mismatch',
 'performance':'dstorage_lifecycle_cost_game_tests_bounded_lifecycle_cost_preserves_binding'}
EVENTS={
 'incarnation':['UNMARKED','BOUND','RENAMED','REPLACED_UNMARKED','REBOUND'],
 'topology':['SINGLE','PAIRED_STALE','PAIRED_REBOUND','SPLIT_STALE','REPAIRED','CLONE_BEFORE','CLONE_ORIGINAL_REFUSED','CLONE_COPY_REFUSED','QUARANTINE_RETAINED'],
 'reload-write':['SAVE_VALID','SAVE_PENDING','SAVE_MISMATCH'],
 'reload-read':['RELOAD_VALID','RELOAD_PENDING_REFUSED','RELOAD_MISMATCH_REFUSED'],
 'performance':['PERFORMANCE_VALID']}
LIVE={'BOUND','RENAMED','REBOUND','SINGLE','PAIRED_REBOUND','REPAIRED','CLONE_BEFORE','SAVE_VALID','RELOAD_VALID','PERFORMANCE_VALID'}

def canonical(value):return (json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'))+'\n').encode()

def topology(binding):
    keys=('adapter_id','world_id','dimension','members','facing');shape={k:binding[k]for k in keys}
    require(hashlib.sha256(canonical(shape)).hexdigest()==binding['topology_sha256'],'native topology digest mismatch')
    members=binding['members'];require(members==sorted(members,key=lambda x:(x['x'],x['y'],x['z'],x['incarnation_id'])),'native member ordering')
    require(len(members)==(2 if binding['adapter_id']=='minecraft:chest_double/v1' else 1),'native member count')
    require(binding['storage_id'] not in [m['incarnation_id']for m in members],'storage UUID used as incarnation')
    return shape

def coherent(frame):
    require(frame['world']==WORLD and frame['principal']==PRINCIPAL,'swapped native world/principal')
    for name in ('binding','resolved'):
        b=frame.get(name)
        if b is not None:
            topology(b);require(b['world_id']==WORLD and b['storage_id']==frame['storage'] and b['binding_generation']==frame['binding_generation'],'native binding identity mismatch')
    live=frame.get('resolved')
    require((live is not None)==(frame['event'] in LIVE),'missing native refusal/live snapshot')
    if live is None:return
    require(frame['record_state']=='ACTIVE' and live['status']=='VERIFIED_LIVE','uncommitted metadata reported live')
    require(topology(live)==topology(frame['binding']),'resolved topology differs from metadata')
    rows={(n['x'],n['y'],n['z']):n for n in frame['native']}
    for member in live['members']:
        n=rows[(member['x'],member['y'],member['z'])]
        require(n['incarnation']==member['incarnation_id'] and n['marker_generation']==member['marker_generation'] and not n['quarantined'],'native marker differs from resolved binding')
        require(n['facing']==live['facing'] and member['incarnation_id'] in n['saved_nbt'],'native orientation/serialized marker mismatch')
    types=[rows[(m['x'],m['y'],m['z'])]['type']for m in live['members']]
    adapter=live['adapter_id']
    require(sorted(types)==(['LEFT','RIGHT'] if adapter=='minecraft:chest_double/v1' else ['BARREL'] if adapter=='minecraft:barrel/v1' else ['SINGLE']),'actual native chest types incompatible')

def xml(path,phase):
    rows=list(ET.parse(path).getroot().iter('testcase'));names=[r.get('name')for r in rows]
    require(len(names)==len(set(names)),'duplicate native XML case IDs')
    require(set(names)=={'minecraft:always_pass',PREFIX+({'observed-restart-read':'dobserved_restart_game_tests_observed_native_saves_remain_unknown_without_original_owner_settlement','handoff':'dvillager_handoff_game_tests_atomic_live_handoff_preserves_stable_recipient_and_refuses_unsafe_effects','courier':'dchest_courier_game_tests_exact_guarded_courier_preserves_stock_and_refuses_interference','courier-crash-read':'dchest_courier_crash_game_tests_killed_carrier_has_no_automatic_recovery_or_save_receipt','follow':'dfollow_game_tests_finite_follow_obstacle_pause_ownership_and_dimension','come':'dcome_game_tests_captured_location_around_obstacle_without_teleport_or_chunk_loads','adapters':'dstorage_adapters_game_tests_native_single_double_barrel_capacity_and_fail_closed_eligibility',**METHODS}[phase])},'missing/unregistered actual native XML test')
    require(all(not any(c.find(tag) is not None for tag in ('failure','error','skipped'))for c in rows),'native XML failed/skipped')

def performance(baseline,candidate):
    for value in (baseline,candidate):
        samples=value['samples'];require(len(samples)==2000 and samples==sorted(samples) and all(isinstance(n,int) and n>=0 for n in samples),'performance sample denominator')
        require(value['warmup']==200 and value['scheduled_tick_tasks']==0,'changed native performance schedule')
        for key,index in [('p50_ns',999),('p95_ns',1899),('p99_ns',1979),('max_ns',1999)]:require(value[key]==samples[index],'fabricated performance percentile')
        require(0<value['heap_used_bytes']<1100*1024*1024,'native heap bound')
    require(candidate['p99_ns']<=max(2000000,baseline['p99_ns']*10) and candidate['max_ns']<=250000000,'native lifecycle performance regression')
    return {'baseline':{k:v for k,v in baseline.items()if k!='samples'},'candidate':{k:v for k,v in candidate.items()if k!='samples'},'baseline_source':'ea1ff7ec9ab1fd16782c32130207aa7e5230442a','bounds':{'p99_floor_ns':2000000,'p99_ratio':10,'max_ns':250000000,'scheduled_tick_tasks':0}}

def physical(frames):
    require({k:list(v) for k,v in frames.items()}==EVENTS,'missing/duplicate native snapshots')
    for phase,values in frames.items():
        for event,f in values.items():require(f['phase']==phase and f['event']==event,'swapped native observation phase');coherent(f)
    inc=frames['incarnation'];a=inc['BOUND'];r=inc['RENAMED'];b=inc['REBOUND'];unmarked=inc['REPLACED_UNMARKED']
    require(a['storage']==r['storage']==b['storage'] and a['binding_generation']==r['binding_generation']<b['binding_generation'],'rename/rebind metadata identity')
    require(a['binding']['topology_sha256']==r['binding']['topology_sha256'] and a['label']!=r['label'] and a['record_revision']<r['record_revision'],'rename continuity missing')
    require(unmarked['native'][0]['incarnation'] is None and inc['UNMARKED']['native'][0]['incarnation'] is None,'replacement/selection inherited native identity')
    require(a['native'][0]['incarnation']!=b['native'][0]['incarnation'],'same-coordinate replacement inherited incarnation')
    t=frames['topology'];single=t['SINGLE'];pair=t['PAIRED_REBOUND'];repaired=t['REPAIRED']
    require(single['binding_generation']<pair['binding_generation']<repaired['binding_generation'],'topology rebind did not advance generation')
    require(pair['binding']['members'][0]==repaired['binding']['members'][0] and pair['binding']['members'][1]!=repaired['binding']['members'][1],'re-pair failed to preserve first/replace second incarnation')
    original=t['CLONE_ORIGINAL_REFUSED'];copy=t['CLONE_COPY_REFUSED'];require(original['storage']!=copy['storage'],'clone test needs independent storage identities')
    require(original['native'][0]['incarnation']==copy['native'][0]['incarnation'] and original['native'][0]['quarantined'] and copy['native'][0]['quarantined'],'all conflicting markers not quarantined')
    require(t['QUARANTINE_RETAINED']['native'][0]['quarantined'],'duplicate quarantine automatically cleared')
    write_frames=frames['reload-write'];read_frames=frames['reload-read']
    for before,after in [('SAVE_VALID','RELOAD_VALID'),('SAVE_PENDING','RELOAD_PENDING_REFUSED'),('SAVE_MISMATCH','RELOAD_MISMATCH_REFUSED')]:
        a=write_frames[before];b=read_frames[after]
        require(a['storage']==b['storage'] and a['binding']==b['binding'] and a['native'][0]['incarnation']==b['native'][0]['incarnation'] and a['native'][0]['marker_generation']==b['native'][0]['marker_generation'],'cold reload did not preserve actual saved identity')
    require(read_frames['RELOAD_VALID']['resolved']['lifecycle_epoch']!=write_frames['SAVE_VALID']['resolved']['lifecycle_epoch'],'cold reload reused lifecycle epoch')
    require(read_frames['RELOAD_PENDING_REFUSED']['record_state']=='PENDING_BIND' and read_frames['RELOAD_PENDING_REFUSED']['binding']['status']=='UNKNOWN','pending history was published')
    mismatch=read_frames['RELOAD_MISMATCH_REFUSED'];require(mismatch['native'][0]['marker_generation']!=mismatch['binding']['members'][0]['marker_generation'],'missing mismatched-marker negative control')

def verify(root,identity,fixture,stage="D02"):
    native=read(root/'identity.json');require(native['identity']==identity,'native source/JAR/run identity changed')
    require(native['release_sha256']==identity['jar_sha256'] and native['fixture_sha256']==sha(fixture),'native release/fixture changed')
    require(native['world']==WORLD and native['seed']==20261027 and native['connected_clients']==0,'native context/false client claim')
    phases=native['phases'];require([r['phase']for r in phases]==list(METHODS)+(['adapters']if stage in ('D05','D06','D07','D08','D09','D10')else[])+(['come']if stage in ('D06','D07','D08','D09','D10')else[])+(['follow']if stage in ('D07','D08','D09','D10')else[])+(['courier','courier-crash-write','courier-crash-read']if stage in ('D08','D09','D10')else[])+(['handoff']if stage in ('D09','D10')else[])+(['observed-restart-write','observed-restart-read']if stage=='D10'else[]),'missing/duplicate native JVM schedule')
    for dep in native['runtime']:require(sha(REPO/dep['path'])==dep['sha256'],'native dependency changed')
    frames={}
    for row in phases:
        phase=row['phase'];require(row['exit']==(-9 if phase in ('courier-crash-write','observed-restart-write')else 0),'actual native JVM failed')
        expected_entry={'baseline':'DStorageBaselineGameTests','incarnation':'DStorageIncarnationGameTests','topology':'DStorageTopologyGameTests','reload-write':'DStorageTopologyReloadGameTests','reload-read':'DStorageTopologyReloadGameTests','performance':'DStorageLifecycleCostGameTests','adapters':'DStorageAdaptersGameTests','come':'DComeGameTests','follow':'DFollowGameTests','courier':'DChestCourierGameTests','courier-crash-write':'DChestCourierCrashGameTests','courier-crash-read':'DChestCourierCrashGameTests','handoff':'DVillagerHandoffGameTests','observed-restart-write':'DObservedRestartGameTests','observed-restart-read':'DObservedRestartGameTests'}[phase]
        require(row['entrypoint']=='dev.aivillages.e2e.paralleld.'+expected_entry,'swapped native entrypoint')
        require(row['world_path']=='worlds/'+('observed-restart'if phase.startswith('observed-restart')else'courier-crash'if phase.startswith('courier-crash')else'reload' if phase.startswith('reload') else phase),'unowned/reset native world')
        expected_jar=read(HERE/'predecessors/D01-lock.json')['jar_sha256'] if phase=='baseline' else identity['jar_sha256']
        require(row['release_sha256']==expected_jar,'native baseline/candidate JAR swapped')
        if phase not in ('courier-crash-write','observed-restart-write'):xml(root/phase/'native-results.xml',phase)
        else:require(row.get('termination',{}).get('kind')=='SIGKILL'and row['termination']['sentinel_sha256']==sha(root/phase/'evidence/kill-ready.json'),'courier real process-kill boundary absent')
        selected=root/phase/'selected-fixture.jar';require(sha(selected)==row['selected_fixture_sha256'],'selected fixture changed')
        with ZipFile(fixture) as original,ZipFile(selected) as observed:
            require(original.namelist()==observed.namelist(),'selected fixture member set changed')
            for name in original.namelist():
                if name!='fabric.mod.json':require(original.read(name)==observed.read(name),'native fixture class/mixin changed')
            desc=json.loads(original.read('fabric.mod.json'));desc['entrypoints']['fabric-gametest']=[row['entrypoint']]
            if phase=='baseline':desc['mixins']=[]
            require(json.loads(observed.read('fabric.mod.json'))==desc,'unrequested selected fixture registration')
        if phase not in ('baseline','adapters','come','follow','courier','courier-crash-write','courier-crash-read','handoff','observed-restart-write','observed-restart-read'):
            rows=[json.loads(line,object_pairs_hook=no_duplicates)for line in (root/phase/'evidence/observations.jsonl').read_text().splitlines()]
            require([r['event']for r in rows]==EVENTS[phase],'missing/duplicate native observation events')
            require(all(r['case']==row['case'] for r in rows),'swapped native stable case ID')
            frames[phase]={r['event']:r for r in rows}
    physical(frames)
    for phase,prefix in [('reload-write','SAVE_'),('reload-read','RELOAD_')]:
        entities=saved_entities(root/phase/'saved-chunk.json');lookup={(b['x'],b['y'],b['z']):b for b in entities}
        for frame in frames[phase].values():
            n=frame['native'][0];saved=lookup[(n['x'],n['y'],n['z'])]['cognitivecraft_storage_v1']
            require(saved['version']==1 and saved['incarnation']==n['incarnation'] and saved['generation']==n['marker_generation'],'actual saved NBT differs from observed/reloaded marker')
    adapter_details={}
    if stage in ('D05','D06','D07','D08','D09','D10'):
        from adapters_oracle import verify as adapters_verify
        adapter_details=adapters_verify(root/'adapters')
    mobility_details={}
    if stage in ('D06','D07','D08','D09','D10'):
        from mobility_oracle import native as mobility_verify
        mobility_details=mobility_verify(root/'come')
    follow_details={}
    if stage in ('D07','D08','D09','D10'):
        from follow_oracle import native as follow_verify
        follow_details=follow_verify(root/'follow')
    delivery_details={}
    if stage in ('D08','D09','D10'):
        from delivery_oracle import native as delivery_verify
        delivery_details=delivery_verify(root)
    handoff_details={}
    if stage in ('D09','D10'):
        from handoff_oracle import native as handoff_verify
        handoff_details=handoff_verify(root/'handoff')
    restart_details={}
    if stage=='D10':
        from component_oracle import native_restart
        restart_details=native_restart(root)
    evidence=[str((root/p/'native-results.xml').relative_to(root.parent))for p in list(METHODS)+(['adapters']if stage in ('D05','D06','D07','D08','D09','D10')else[])+(['come']if stage in ('D06','D07','D08','D09','D10')else[])+(['follow']if stage in ('D07','D08','D09','D10')else[])+(['courier','courier-crash-read']if stage in ('D08','D09','D10')else[])+(['handoff']if stage in ('D09','D10')else[])]
    if stage=='D10':evidence+=['native/observed-restart-read/native-results.xml','native/observed-restart-read/evidence/restart.jsonl','native/observed-restart-read/evidence/binding-reload.json','native/observed-restart-write/evidence/restart.jsonl','native/observed-restart-write/prekill-saved/distributions.json']
    evidence+=follow_details.get('evidence',[])+delivery_details.get('evidence',[])+handoff_details.get('evidence',[])
    if stage in ('D06','D07','D08','D09','D10'):evidence += ['native/come/'+p for p in ('stdout.log','evidence/arena.json','evidence/movement.jsonl','evidence/storage-movement.jsonl','evidence/controls.json','evidence/storage-controls.json')]
    return [{'id':case,'status':'PASS'}for case in ['D02-GT01','D02-GT02-TOPOLOGY','D02-GT02-RELOAD','D02-PERF']+(['D05-GT01']if stage in ('D05','D06','D07','D08','D09','D10')else[])+(['D06-GT01']if stage in ('D06','D07','D08','D09','D10')else[])+(['D07-GT01']if stage in ('D07','D08','D09','D10')else[])+(['D08-GT01']if stage in ('D08','D09','D10')else[])+(['D09-GT01']if stage in ('D09','D10')else[])+(['D10-GT01']if stage=='D10'else[])],{
        'observed_restart_details':restart_details,'handoff_details':handoff_details,'delivery_details':delivery_details,'follow_details':follow_details,'native_jvms':len(phases),'owned_cases':4+(stage in ('D05','D06','D07','D08','D09','D10'))+(stage in ('D06','D07','D08','D09','D10'))+(stage in ('D07','D08','D09','D10'))+(stage in ('D08','D09','D10'))+(stage in ('D09','D10'))+(stage=='D10'),'xml_observations':5+(stage in ('D05','D06','D07','D08','D09','D10'))+(stage in ('D06','D07','D08','D09','D10'))+(stage in ('D07','D08','D09','D10'))+2*(stage in ('D08','D09','D10'))+(stage in ('D09','D10'))+(stage=='D10'),'baseline_cases':1,'cold_reload_jvms':2,'saved_marker_snapshots':6,
        'mobility_details':mobility_details,'adapter_details':adapter_details,'frames':sum(len(v)for v in frames.values())+adapter_details.get('events',0)+sum(d.get('frames',0)for d in mobility_details.values()if isinstance(d,dict)),'evidence':evidence,
        'performance':performance(read(root/'baseline/evidence/performance.json'),read(root/'performance/evidence/performance.json'))}
