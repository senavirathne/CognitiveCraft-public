#!/usr/bin/env python3
"""D11 readiness after the independent XML/native/client oracles, never a pass flag."""
from common import *

HOST_WORLD='d0200000-0000-4000-8000-000000000001'
BOOT='Loading Minecraft 26.3 with Fabric Loader 0.19.5'
TARGETS=('net.minecraft.world.level.block.entity.BlockEntity','net.minecraft.world.level.chunk.storage.EntityStorage',
         'net.minecraft.world.SimpleContainer','net.minecraft.core.NonNullList','net.minecraft.world.entity.npc.villager.AbstractVillager')

def collect_native_targets(root):
    import subprocess
    from zipfile import ZipFile
    server=REPO/'build/server-sdk/minecraft-server.jar';box=root/'qualification';box.mkdir(parents=True,exist_ok=True);rows=[]
    with ZipFile(server)as archive:
        for target in TARGETS:
            name=target.replace('.','/')+'.class';path=box/(target.rsplit('.',1)[1]+'.javap.txt')
            process=subprocess.run(['javap','-classpath',str(server),'-p','-s',target],capture_output=True,text=True)
            path.write_text(process.stdout+process.stderr)
            rows.append({'class':target,'native_class_sha256':__import__('hashlib').sha256(archive.read(name)).hexdigest(),
                         'javap':path.name,'javap_sha256':sha(path),'exit':process.returncode})
    write(box/'native-targets.json',{'server_sha256':sha(server),'classes':rows,'production_marker_registration':None,'production_activation':None})

def native_targets(root):
    from zipfile import ZipFile
    box=root/'qualification';observed=read(box/'native-targets.json');server=REPO/'build/server-sdk/minecraft-server.jar'
    require(observed['server_sha256']==sha(server)and [r['class']for r in observed['classes']]==list(TARGETS),'native mixin target runtime changed')
    with ZipFile(server)as archive:
        for row in observed['classes']:
            require(row['exit']==0 and sha(box/row['javap'])==row['javap_sha256'],'native target javap unavailable/changed')
            require(__import__('hashlib').sha256(archive.read(row['class'].replace('.','/')+'.class')).hexdigest()==row['native_class_sha256'],'native target class changed')
    text=(box/'BlockEntity.javap.txt').read_text()
    signatures={'loadAdditional':'(Lnet/minecraft/world/level/storage/ValueInput;)V','saveAdditional':'(Lnet/minecraft/world/level/storage/ValueOutput;)V',
                'preRemoveSideEffects':'(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V','setRemoved':'()V',
                'setBlockState':'(Lnet/minecraft/world/level/block/state/BlockState;)V','setLevel':'(Lnet/minecraft/world/level/Level;)V','clearRemoved':'()V'}
    for method,descriptor in signatures.items():
        lines=text.splitlines();positions=[i for i,s in enumerate(lines)if method+'('in s]
        require(len(positions)==1 and descriptor in lines[positions[0]+1],'native marker injection signature changed: '+method)
    entity=(box/'EntityStorage.javap.txt').read_text()
    require('private final net.minecraft.server.level.ServerLevel level;'in entity and 'storeEntities(net.minecraft.world.level.entity.ChunkEntities<net.minecraft.world.entity.Entity>)'in entity,'test-only entity save-cut target changed')
    require(observed['production_marker_registration']is None and observed['production_activation']is None,'native target proof falsely activates production')
    return ['qualification/native-targets.json']+['qualification/'+r['javap']for r in observed['classes']]

def native_context(observed,identity,fixture_sha):
    require(observed['identity']==identity,'native producer candidate changed')
    require(observed['stage']=='D11' and observed['world']==HOST_WORLD and observed['seed']==20261027,'wrong native fixture/world')
    require(observed['fixture_sha256']==fixture_sha and observed['release_sha256']==identity['jar_sha256'],'wrong native fixture/JAR')
    require(observed['connected_clients']==0,'native host fabricated connected clients')

def native_producer(root,row):
    phase=row['phase'];box=root/phase
    required=['stdout.log','selected-fixture.jar']+([]if phase in ('courier-crash-write','observed-restart-write')else['native-results.xml'])
    require(all((box/p).is_file()for p in required),'missing native producer: '+phase)
    require(sha(box/'selected-fixture.jar')==row['selected_fixture_sha256'],'wrong native fixture producer')
    command=row['command']
    require('net.fabricmc.loader.impl.launch.knot.KnotServer'in command and '-Dfabric-api.gametest'in command,'missing native Minecraft producer')
    require('-Dcognitivecraft.parallelD.fixtureWorld='+HOST_WORLD in command,'wrong native producer world')
    require(BOOT in (box/'stdout.log').read_text(),'missing native loader producer')
    # The complete native oracle has already checked every XML method, selected class,
    # original save byte and witnessed SIGKILL boundary before this final guard runs.
    return phase

def component_context(observed,identity,fixture_sha):
    from component_oracle import checked_identity
    checked_identity(observed,identity)
    require(observed['fixture_sha256']==fixture_sha,'wrong client fixture')

def no_settlement(observed):
    for name in ('integration_root','effect_id','save_proof','participant_save_proof','original_owner_settlement'):
        require(observed.get(name)is None,'fabricated save settlement: '+name)
    require(observed.get('tier')not in ('PARTICIPANT_SAVES_CONFIRMED','ORIGINAL_OWNER_SETTLED'),'fabricated participant settlement tier')

def readiness_status(result):
    require(result['stage']=='D11' and result['owner']=='D' and result['base_sha']==BASE,'wrong readiness owner/stage/base')
    spec=registry('D11')
    for tier,planned in [('unit',spec['unit_planned']),('python',len(spec['python_cases'])),('game_tests',len(spec['game_tests'])),('client_e2e',len(spec['client_e2e']))]:
        row=result[tier]
        require(row['status']=='PASS' and row['planned']==row['executed']==planned and row['passed']+row['refused_expected']==planned and row['failed']==row['inconclusive']==0,'incomplete mandatory tier: '+tier)
    require(not result['regressions'],'readiness regression')
    require(all(result[k]for k in ('source_sha','tree_sha','jar_sha256','docs_sha')),'readiness pin missing')
    require(all(any(owner in reason for reason in result['blocked'])for owner in ('J01','J02','J05')),'deferred J boundary missing')
    local=result['workflow_run']is None and result['attempt']is None
    require(local or all(isinstance(result[k],int)and not isinstance(result[k],bool)and result[k]>0 for k in ('workflow_run','attempt')),'incomplete CI run identity')
    return 'OWNED_GATES_PASS'if local else'BRANCH_READY'

def finalize(root,result,fixture):
    contract_check();identity={k:result[k]for k in ('source_sha','tree_sha','jar_sha256','workflow_run','attempt')}
    metadata=read(root/'metadata.json');native=read(root/'native/identity.json');fixture_sha=sha(fixture)
    native_context(native,identity,fixture_sha)
    require([r['phase']for r in native['phases']]==registry('D11')['native_schedule'],'missing native producer schedule')
    phases=[native_producer(root/'native',r)for r in native['phases']]
    for stage in range(5,11):
        meta=read(root/('clients-d'+str(stage).zfill(2))/'identity.json')
        require(meta['identity']==identity and meta['phase']=='COLLECTED_RAW','missing/stale compatibility client producer')
        require(meta['fixture_sha256']==fixture_sha,'compatibility fixture changed');no_settlement(meta)
    component_context(read(root/'clients-d10/identity.json'),identity,fixture_sha)
    require(metadata['contract_sha256']==CONTRACT and metadata['docs_sha']==result['docs_sha'],'stale shared contract/docs pin')
    require(all(v is None for k,v in metadata['integration'].items()if k!='blocker'),'fabricated integrated save settlement')
    for phase in ('observed-restart-write','observed-restart-read'):
        for line in (root/'native'/phase/'evidence/restart.jsonl').read_text().splitlines():no_settlement(json.loads(line,object_pairs_hook=no_duplicates))
    runtime=metadata['runtime'];require(runtime['executor']=='docker-compose'and runtime['workflow_run']==result['workflow_run']and runtime['attempt']==result['attempt'],'readiness runtime/CI mismatch')
    target_evidence=native_targets(root);status=readiness_status(result)
    return status,{'native_aggregate':{'id':'D11-GT01','name':'DCompleteNativeSuite','members':registry('D11')['game_tests'],'native_jvms':len(phases),'extra_case_credit':0},
                   'client_aggregate':{'id':'D11-CL01','name':'DFinalClientReplay','members':registry('D11')['client_e2e'],'component_cases':48,'compatibility_controls':5,'extra_case_credit':0},
                   'decision':status,'native_target_evidence':target_evidence,'production_activation':None,'participant_save_proof':None,'original_owner_settlement':None,'next_gate':'J00 requires C08, D11, A10 and qualified I00; not executed here'}

if __name__=='__main__':
    import argparse
    from verify import verify
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();result,details=verify('D11',a.root.resolve(),git('rev-parse','HEAD'),git('rev-parse','HEAD^{tree}'),RELEASE)
    write(a.output,result);write(a.output.parent/'oracle-details.json',details);print(json.dumps(result,sort_keys=True))
