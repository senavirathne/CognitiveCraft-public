"""Independent, network-free review of one A10 profile's full raw native evidence."""
import argparse,hashlib,json,traceback
from pathlib import Path
import xml.etree.ElementTree as ET
from freeze_plan import CASES,CONTROLS,MODULES,compose_files,JAVA_IMAGE,CLIENT_IMAGE,plan,ledger
from freeze_native import results
from freeze_early_review import REVIEW
from freeze_storage_review import digest,read,journal
from review_lifecycle_connected import runtime_proof,entities
from review_receipt_prefix import regions,entity_uuid
from recovery_fixture import MEMBERSHIP,marker

ROOT=Path(__file__).resolve().parents[2]

def identity(root,profile,source,jar):
    frozen=read(root/'a10-frozen-runtime.json');metadata=read(root/'metadata.json')
    assert frozen['schema']==1 and frozen['stage']=='A10' and frozen['source']==metadata['sourceCommit']==source
    assert frozen['tree']==metadata['sourceTree'] and metadata['dirty'] is False
    assert frozen['productionSha256']==metadata['releaseSha256']==jar and frozen['plan']==plan()
    assert metadata['minecraft']=='26.3' and metadata['loader']=='0.19.5'
    cpu=read(root/'a10-cpu-topology.json');assert cpu['profile']==profile
    assert cpu['isolationApplied'] and cpu['orchestratorAfter']==[2,3] and cpu['serverCores']==[0,1]
    assert cpu['clientCores']=={'PlayerA':[2],'PlayerB':[3]} and cpu['addedCores']==0 and not cpu['jvmHeapGcAndGateLimitsChanged']
    files={r['path']:r for r in frozen['files']}
    assert files['build/e2e-driver/observer.jar']['sha256']==metadata['binaries']['observer']['sha256']
    assert files['build/e2e-driver/client.jar']['sha256']==metadata['binaries']['clientDriver']['sha256']
    unit=modules()
    assert unit['source']==source and unit['tree']==frozen['tree'] and unit['productionSha256']==jar
    assert frozen['nativeUnits']=={'total':sum(MODULES.values()),'modules':MODULES}
    for row in frozen['files']:assert (ROOT/row['path']).stat().st_size==row['bytes'] and digest(ROOT/row['path'])==row['sha256']
    native=runtime_proof(root,metadata)
    stops=[json.loads(line) for line in (root/'supervisor.jsonl').read_text().splitlines()]
    stops=[r['data']['process'] for r in stops if r['kind'] in ('stop','sigkill') and r['data'].get('component')=='server']
    assert stops and all(p['kind']=='server' and p['releaseSha256']==jar for p in stops)
    from compiler_budget import review as compiler_review
    compiler=compiler_review(root,profile,jar,stops)
    for process in stops:
        actual={r['name']:r['sha256'] for r in process['mods']}
        assert actual['ai-villages-0.1.0.jar']==jar and actual['observer.jar']==metadata['binaries']['observer']['sha256']
    build=read(root/'a08-build-inputs.json');assert build['networkDuringBuildRun']=='none' and build['runtimeNetwork']=='host'
    assert build['actualDockerfile']=='e2e/parallel_a/Dockerfile.freeze' and build['immutableBaseImages']==[JAVA_IMAGE,CLIENT_IMAGE]
    expected={*compose_files(profile),'e2e/parallel_a/Dockerfile.freeze','e2e/parallel_a/dependency_probe.py'}
    assert set(build['files'])==expected and all(digest(ROOT/p)==h for p,h in build['files'].items())
    dependencies=read(root/'a08-image-dependencies.json');assert dependencies['status'].startswith('PASS')
    images=read(root/'a08-build-images.json');assert len(images)==3
    assert all(any(v.endswith(expected.split('@')[1]) for v in actual['RepoDigests']) for expected,actual in zip((JAVA_IMAGE,CLIENT_IMAGE),images[1:],strict=True))
    admission=read(root/'independent-admissions.json');assert len(admission['admissions'])==3
    continuation=read(root/'seed-continuation.json');assert continuation['currentSource']==source and continuation['currentReleaseSha256']==jar
    assert continuation['importedA06Settlements']==0 and continuation['allTwelveMustRunOnCurrentCandidate']
    from deposits import manifest
    assert manifest(root/'genuinely-admitted-seed-game/world')==continuation['worldHashes']==admission['worldHashes']
    assert {r['item'] for r in admission['admissions'] if r['status']=='PASS'}=={'minecraft:oak_log','minecraft:birch_log','minecraft:wheat'}
    for row in admission['admissions']:
        assert row['callsAfter']['generationCalls']>row['callsBefore']['generationCalls'] and row['view']['marker']['outcome']=='ADMITTED'
    from recovery_seed import ARCHIVE_SHA,SOURCE,ARTIFACT
    assert continuation['archiveSha256']==ARCHIVE_SHA and continuation['source']==SOURCE and continuation['artifact']==ARTIFACT
    return {'source':source,'tree':frozen['tree'],'productionSha256':jar,'observerSha256':metadata['binaries']['observer']['sha256'],
            'clientSha256':metadata['binaries']['clientDriver']['sha256'],'runtimeBindings':native,'immutableBuildImages':images,'compilerBudget':compiler,
            'seed':{'sha256':ARCHIVE_SHA,'source':SOURCE,'artifact':ARTIFACT,'importedCurrentPasses':0}}

def modules(root=ROOT):
    base=root/'build/parallel-a/a10-native-unit';result=read(base/'native-unit.json')
    assert result['status']=='PASS_ALL_MODULES_AGAINST_ONE_NATIVE_JAR' and result['modules']==MODULES and not result['productionClassDirectoriesOnClasspath']
    for module,expected in MODULES.items():
        rows=[c for path in (base/module).glob('TEST-*.xml') for c in ET.parse(path).getroot().iter('testcase')]
        assert len(rows)==expected and all(c.find(t) is None for c in rows for t in ('error','failure','skipped'))
    assert result['total']==sum(MODULES.values()) and result['failures']==result['errors']==result['skipped']==0
    return result

def handoff_membership(root):
    """Confirmed old-actor death must retain a supported absence row after the real handoff."""
    folder=root/'controls/handoff-positive';row=read(folder/'result.json');world=folder/'terminal-world'
    old=row['originalAtCut']['attempts'][0]['worker']['entityId'];new=row['successor']['entityId']
    native=entities(world);assert old not in native and new in native and old!=new
    _,entries=journal(world/'data/cognitivecraft/deposit-evidence/v1/state.jsonl')
    own=[r for r in entries if r['intent']['key']['step']==row['key']['step']]
    assert len(own)==2 and {r['intent']['actor']['address'] for r in own}=={old,new}
    checks={}
    for frame in regions(world,'entities'):
        if MEMBERSHIP not in frame:continue
        assert MEMBERSHIP+'_original' not in frame,'Positive handoff quarantined a membership frame'
        state=json.loads(frame[MEMBERSHIP]);assert state['schema']==1 and not state.get('quarantined')
        assert frame['Position']==[state['x'],state['z']] and len(state['rows'])<=64
        members={entity_uuid(e) for e in frame.get('Entities',[])}
        for actual in state['rows']:
            assert actual['present']==(actual['entity'] in members),'Saved native membership contradicts its actual entity frame'
            if actual['entity'] not in (old,new):continue
            entry=next(r for r in own if r['intent']['actor']['address']==actual['entity'])
            participant=entry['result']['actor']
            assert actual['role']=='ACTOR' and actual['incarnation']==participant['incarnation'] and actual['revision']==participant['revision']
            assert json.loads(actual['marker'])==marker(entry)
            assert actual['present']==(actual['entity']==new)
            checks.setdefault(actual['entity'],[]).append({'chunk':frame['Position'],'present':actual['present'],'marker':json.loads(actual['marker'])})
    assert set(checks)=={old,new} and all(checks.values())
    return {'status':'PASS_SAVED_NATIVE_HANDOFF_MEMBERSHIP','oldActor':old,'successor':new,'rows':checks,'creditMintedByDeparture':0}

def review(root,profile,source,jar):
    identity_result=identity(root,profile,source,jar);collection=read(root/'a10-collection.json')
    assert collection['status']=='COLLECTED_PENDING_INDEPENDENT_REVIEW' and collection['profile']==profile
    assert collection['source']==source and collection['tree']==identity_result['tree'] and collection['productionSha256']==jar
    actual=results(root,profile);complete=ledger(profile,actual)
    assert collection['cases']==complete and collection['executedCases']==collection['requiredCases']==len(CASES[profile])
    assert all(r['collectionStatus']!='NOT_EXECUTED_REQUIRED' for r in complete)
    if profile in REVIEW:
        result=read(root/(profile.lower()+'-results.json'));assert result['status'] in ('PASS','PASS_NATIVE_GATES_PENDING_INDEPENDENT_REVIEW')
        cases={r['id']:r for r in actual}
        assert all((r['status'] if isinstance(r['status'],str) else r['status']['status'])=='PASS' for r in cases.values())
        details=REVIEW[profile](root,cases)
    elif profile=='A06':
        from recovery_cases import originals,complete as all_originals
        from review_recovery_current import one,controls
        from review_storage_mechanics import review as storage
        originals_plan=originals(ROOT/'e2e/parallel_a/a06_original_cases.json')
        original_reviews=[one(root,c) for c in originals_plan];details=all_originals(originals_plan,original_reviews)
        details['storageMechanics']=storage(root);details['controls']=controls(root)
        assert details['controls']['status']=='PASS_INDEPENDENT_REQUIRED_CONTROLS'
        report=read(root/'a06-original-results.json');assert report['originalStatus']=='PASS_ALL_TWELVE_PENDING_INDEPENDENT_REVIEW' and report['originalOwnerSettlements']==12
    elif profile=='A07':
        from review_lifecycle_connected import review as original
        details=original(root,source);assert details['status']=='PASS_A07_CONNECTED_NATIVE_REVIEW'
        details['savedHandoffMembership']=handoff_membership(root)
        from handoff_fixture import review as fixture_review
        from lifecycle_cases import HANDOFF_CASES
        details['physicalHandoffFixtures']={}
        for name in HANDOFF_CASES:
            folder=root/'controls'/name;row=read(folder/'result.json');record=read(folder/'handoff-fixture.json')
            assert record==row['physicalFixture']
            details['physicalHandoffFixtures'][name]=fixture_review(folder/'killed-world',row['originalCase'],
                row['originalAtCut']['attempts'][0]['worker']['entityId'],row['successor']['entityId'],record)
    elif profile=='A08':
        from review_claims_connected import review as original
        details=original(root,source);assert details['status']=='PASS_BOUNDED_STAGING_NATIVE_REVIEW'
    else:
        from review_activity_connected import review as original
        details=original(root,source);assert details['status']=='PASS_ALL_REQUIRED_A09_NATIVE_REVIEW'
    return {'schema':1,'stage':'A10','profile':profile,'status':'PASS_INDEPENDENT_A10_PROFILE_REVIEW',**identity_result,
            'requiredCases':len(CASES[profile]),'cases':[{'id':name,'status':'PASS_ORIGINAL_CLASSIFICATION_RETAINED'} for name in CASES[profile]],
            'details':details,'omittedTasks':0,'omittedTests':0,'branchReady':False,
            'scope':'Original native E2E, native host and metadata-fault classifications retained. Not an I00 or broad gameplay claim.'}

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--profile',choices=CASES,required=True)
    p.add_argument('--source',required=True);p.add_argument('--jar-sha256',required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True)
    try:value=review(a.root,a.profile,a.source,a.jar_sha256)
    except Exception as error:value={'schema':1,'stage':'A10','profile':a.profile,'status':'FAIL_INDEPENDENT_A10_PROFILE_REVIEW','reason':str(error),'trace':traceback.format_exc(),'branchReady':False}
    (a.out/'review.json').write_text(json.dumps(value,indent=2)+'\n')
    print('A10_INDEPENDENT_REVIEW '+json.dumps({k:v for k,v in value.items() if k not in ('details','runtimeBindings','trace','cases','immutableBuildImages')}),flush=True)
    if value['status']!='PASS_INDEPENDENT_A10_PROFILE_REVIEW':raise SystemExit(1)

if __name__=='__main__':main()
