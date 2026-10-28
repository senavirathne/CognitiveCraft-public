#!/usr/bin/env python3
"""Collect raw D00 evidence from one already-built, clean candidate. No pass decisions."""
from __future__ import annotations
import argparse, os, shutil, subprocess, sys, uuid
from common import *
from packaging import inspect_fixture, inspect_release
from ownership import audit
from prerequisites import verify as predecessor

def run(stage, root, built):
    spec=registry(stage);require(built,'stage consumes an already-built release; --built required')
    root=Path(root).resolve();root.mkdir(parents=True,exist_ok=True)
    require(not (root/'metadata.json').exists(),'refuse to overwrite a previous candidate')
    contract_check();identity=source_identity();inspect_release(RELEASE)
    fixture=REPO/'build/parallel-d-native/parallel-d-fixtures.jar';inspect_fixture(fixture)
    runtime=read(root/'environment.json')
    require(runtime['executor']=='docker-compose' and runtime['image_id'].startswith('sha256:'),'Docker executor identity missing')
    require(runtime['workflow_run']==identity['workflow_run'] and runtime['attempt']==identity['attempt'],'Docker run/attempt mismatch')
    require(os.environ.get('CC_D_EXECUTOR')=='docker-compose','stage must execute inside the declared Docker service')
    sdk=Path(os.environ.get('COGNITIVECRAFT_E2E_CACHE',REPO/'build/e2e-sdk'))
    dependencies=[REPO/'build/server-sdk/minecraft-server.jar',REPO/'build/server-sdk/fabric-api.jar',sdk/'minecraft-client.jar',
                  REPO/'build/e2e-driver/client.jar',REPO/'build/e2e-driver/observer.jar',sdk/'integrity.json']
    dependencies+=sorted((REPO/'build/server-sdk/libs').glob('*.jar'))+sorted((sdk/'libraries').rglob('*.jar'))
    metadata={**identity,'stage':stage,'seed':spec['seed'],'registry_sha256':sha(HERE/'case-registry.json'),
              'contract_sha256':CONTRACT,'fixture_sha256':sha(fixture),'runtime':runtime,
              'dependencies':[{'path':str(p.relative_to(REPO)),'sha256':sha(p)} for p in dependencies],
              'java':subprocess.run(['java','-version'],capture_output=True,text=True,check=True).stderr,
              'integration':{'root_id':None,'owner_id':None,'effect_id':None,'save_proof':None,'blocker':'J01/J02: registration and A authority adapters deferred'},
              'docs_sha':read(HERE/'docs-snapshot.json')['candidate_sha'],'predecessor':predecessor(stage)}
    write(root/'metadata.json',metadata)
    write(root/'ownership.json',audit(require_built=True))
    for module in ('core','providers','fabric'):
        paths=sorted((REPO/module/'build/test-results/test').glob('TEST-*.xml'))
        require(paths,'missing measured JUnit XML for '+module)
        for path in paths:
            target=root/'junit'/module/path.name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,target)
    python_env=os.environ.copy()
    if spec.get('resolution_cases'):python_env['CC_D_RESOLUTION_EVIDENCE']=str(root/'resolution')
    if spec.get('acl_cases'):python_env['CC_D_ACL_EVIDENCE']=str(root/'acl')
    subprocess.run([sys.executable,str(HERE/'test_runner.py'),'--stage',stage,'--output',str(root/'python-results.xml')],env=python_env,check=False)
    if spec.get('metadata_cases'):
        shutil.copytree(REPO/'build/parallel-d-metadata',root/'metadata')
    write(root/'native-plan.json',{'game_tests':spec['game_tests'],'client_e2e':spec['client_e2e'],
              'status':'NOT_EXECUTED','reason':'Required native/client schedules have not completed' if spec['game_tests'] or spec['client_e2e'] else stage+' has no physical cases',
              'configured_players':[{'name':name,'anticipated_offline_uuid':str(uuid.UUID(bytes=__import__('hashlib').md5(('OfflinePlayer:'+name).encode()).digest(),version=3))} for name in ('PlayerD_A','PlayerD_B')]})
    if spec['game_tests']:
        from native_runner import run as native_run
        native_run(root/'native',RELEASE,fixture,identity,REPO/'build/d02-baseline/ai-villages-0.1.0.jar',stage)
        write(root/'native-plan.json',{'game_tests':spec['game_tests'],'client_e2e':spec['client_e2e'], 'status':'COLLECTED_RAW',
              'configured_players':[{'name':name,'anticipated_offline_uuid':str(uuid.UUID(bytes=__import__('hashlib').md5(('OfflinePlayer:'+name).encode()).digest(),version=3))} for name in ('PlayerD_A','PlayerD_B')]})
    if spec['client_e2e']:require(stage in ('D05','D06','D07') and (root/'clients-d05/identity.json').is_file() and (stage not in ('D06','D07') or (root/'clients-d06/identity.json').is_file()) and (stage!='D07' or (root/'clients-d07/identity.json').is_file()),'actual two-client campaign absent')
    files=[{'path':str(p.relative_to(root)),'sha256':sha(p),'bytes':p.stat().st_size} for p in sorted(root.rglob('*')) if p.is_file() and p.name not in ('manifest.json','qualified.json','oracle-details.json')]
    write(root/'manifest.json',{'identity':identity,'files':files})
    print(json.dumps({'stage':stage,'operation':'RAW_EVIDENCE_COLLECTED','files':len(files),'qualification_claims':0},sort_keys=True))

if __name__=='__main__':
    p=argparse.ArgumentParser();sub=p.add_subparsers(dest='command',required=True);r=sub.add_parser('run')
    r.add_argument('--stage',required=True);r.add_argument('--root',type=Path,required=True);r.add_argument('--built',action='store_true')
    a=p.parse_args();run(a.stage,a.root,a.built)
