#!/usr/bin/env python3
"""Independent oracle: actual XML, fixture observations, immutable inputs and hashes."""
from __future__ import annotations
import argparse, collections, re, xml.etree.ElementTree as ET
from common import *
from ownership import audit
from packaging import inspect_release,inspect_fixture
from prerequisites import verify as predecessor
from recovery_oracle import verify as recovery_verify

def xml_cases(path):
    tree=ET.parse(path).getroot();rows=[]
    for c in tree.iter('testcase'):
        status='PASS'
        if c.find('failure') is not None or c.find('error') is not None:status='FAIL'
        elif c.find('skipped') is not None:status='INCONCLUSIVE'
        rows.append({'id':c.get('classname','')+'/'+c.get('name',''),'name':c.get('name',''),'class':c.get('classname',''),'status':status})
    return rows,'\n'.join(n.text or '' for n in tree.iter('system-out'))

def metric(planned,rows,evidence):
    counts=collections.Counter(r['status'] for r in rows)
    require(len(rows)<=planned,'execution denominator inflated')
    return {'planned':planned,'executed':len(rows),'passed':counts['PASS'],'failed':counts['FAIL'],
            'refused_expected':counts['REFUSED_EXPECTED'],'inconclusive':counts['INCONCLUSIVE'],
            'status':'NOT_EXECUTED' if not planned else 'PASS' if len(rows)==planned and counts['PASS']==planned else 'FAIL' if counts['FAIL'] else 'INCONCLUSIVE','evidence':evidence}

def unique(rows):
    require(len({r['id'] for r in rows})==len(rows),'duplicate stable case IDs')

def fixture_observations(text):
    rows=[]
    for line in text.splitlines():
        if line.startswith('CC_D00_FIXTURE '):rows.append(json.loads(line[len('CC_D00_FIXTURE '):],object_pairs_hook=no_duplicates))
    unique(rows)
    expected={f['id']:f['expected'] for f in read(HERE/'contracts/fixtures-index.json')['fixtures']}
    require({r['id'] for r in rows}==set(expected),'missing/unregistered Java fixture observations')
    for row in rows:require(row['actual']==expected[row['id']],'Java codec disagrees with frozen fixture: '+row['id'])
    return rows

def validate_manifest(root):
    m=read(root/'manifest.json');seen=set()
    require(isinstance(m['files'],list) and m['files'],'raw artifact manifest missing')
    for f in m['files']:
        rel=Path(f['path']);require(not rel.is_absolute() and '..' not in rel.parts,'artifact path escapes root')
        require(f['path'] not in seen,'duplicate raw artifact path');seen.add(f['path'])
        p=root/rel;require(p.is_file() and not p.is_symlink(),'raw artifact missing/type changed')
        require(sha(p)==f['sha256'] and p.stat().st_size==f['bytes'],'raw artifact hash changed: '+f['path'])
    for p in root.rglob('*'):
        if p.is_file() and p.name not in ('manifest.json','qualified.json','oracle-details.json'):
            require(str(p.relative_to(root)) in seen,'unmanifested raw artifact')
    return m

def verify(stage,root,source,tree,jar):
    spec=registry(stage);contract_check();require(jar.resolve()==RELEASE.resolve(),'unexpected release path')
    raw=validate_manifest(root);m=read(root/'metadata.json');current=source_identity()
    require(source==current['source_sha'] and tree==current['tree_sha'],'requested source/tree differ from checkout')
    for key in ('source_sha','tree_sha','jar_sha256','workflow_run','attempt'):
        require(m[key]==current[key] and raw['identity'][key]==current[key],'candidate identity changed: '+key)
    require(m['stage']==stage and m['registry_sha256']==sha(HERE/'case-registry.json'),'stage/registry changed')
    require(m['contract_sha256']==CONTRACT,'wrong contract pin')
    require(m.get('predecessor')==predecessor(stage),'changed/missing predecessor qualification')
    require(all(v is None for k,v in m['integration'].items() if k!='blocker'),'fabricated integrated authority/settlement')
    require(m['docs_sha']==read(HERE/'docs-snapshot.json')['candidate_sha'],'swapped docs principal/pin')
    for row in m['dependencies']:require(sha(REPO/row['path'])==row['sha256'],'runtime/client dependency changed')
    require(m['runtime']==read(root/'environment.json') and m['runtime']['executor']=='docker-compose','Docker identity changed')
    inspect_release(jar);fixture=REPO/'build/parallel-d-native/parallel-d-fixtures.jar';inspect_fixture(fixture)
    require(m['fixture_sha256']==sha(fixture),'fixture mod changed')
    audit(require_built=True)
    regression=[];owned=[];output='';regression_ids=set()
    for module in ('core','providers','fabric'):
        paths=sorted((root/'junit'/module).glob('TEST-*.xml'));require(paths,'missing module JUnit XML: '+module)
        module_rows=[]
        for p in paths:
            rows,text=xml_cases(p);module_rows.extend(rows)
            if any(r['class'] in spec['unit_suites'] for r in rows):owned.extend(r for r in rows if r['class'] in spec['unit_suites'])
            if p.name=='TEST-dev.aivillages.core.kernel.storage.contract.StorageCodecTest.xml':output+='\n'+text
        unique(module_rows);require(module_rows and all(r['status']=='PASS' for r in module_rows),'module regression failed/skipped: '+module)
        regression.append({'module':module,'executed':len(module_rows),'passed':len(module_rows)})
    unique(owned);require(len(owned)==spec['unit_planned'],'owned JUnit denominator mismatch')
    expected_ids={name+'/'+case for name,cases in spec['unit_suites'].items() for case in cases}
    require({r['id']for r in owned}==expected_ids,'missing/unregistered owned JUnit case')
    for method in spec['unit_guard_methods']:require(any(method in r['name'] for r in owned),'missing Java negative control: '+method)
    observations=fixture_observations(output)
    recoveries=recovery_verify(root/'metadata',spec['metadata_cases']) if spec.get('metadata_cases') else []
    acl_details={}
    if spec.get('acl_cases'):
        from acl_oracle import verify as acl_verify
        acl_details=acl_verify(root/'acl');require(acl_details['transitions']==len(spec['acl_cases']),'ACL transition reconciliation')
    resolution_details={}
    if spec.get('resolution_cases'):
        from resolution_oracle import verify as resolution_verify
        resolution_details=resolution_verify(root/'resolution');require(resolution_details['queries']==len(spec['resolution_cases']),'resolution reconciliation')
    python_rows,_=xml_cases(root/'python-results.xml');unique(python_rows)
    require({r['name'] for r in python_rows}==set(spec['python_cases']),'missing/unregistered Python XML cases')
    require(all(r['status']=='PASS' for r in python_rows),'Python assertion failed/skipped')
    native=read(root/'native-plan.json')
    require(native['game_tests']==spec['game_tests'] and native['client_e2e']==[],'unearned/missing native/client case plan')
    native_rows=[];native_details={}
    if spec['game_tests']:
        from native_oracle import verify as native_verify
        require(native['status']=='COLLECTED_RAW','native collection absent')
        native_rows,native_details=native_verify(root/'native',current,fixture)
        require({r['id']for r in native_rows}==set(spec['game_tests']),'native case reconciliation')
    else:require(native['status']=='NOT_EXECUTED','unearned native credit')
    identities=native['configured_players'];require(len(identities)==2 and len({p['name'] for p in identities})==2 and len({p['anticipated_offline_uuid'] for p in identities})==2,'duplicate configured principals')
    result={**current,'stage':stage,'owner':'D','base_sha':BASE,'docs_sha':m['docs_sha'],'contract_sha256':CONTRACT,
            'unit':metric(spec['unit_planned'],owned,['junit/core/TEST-'+name+'.xml' for name in spec['unit_suites']]),
            'python':metric(len(spec['python_cases']),python_rows,['python-results.xml']),
            'game_tests':metric(len(spec['game_tests']),native_rows,native_details.get('evidence',[])),'client_e2e':metric(0,[],[]),'native_evidence':native_details.get('evidence',[]),
            'blocked':['J01/J02/J04: production registration, runtime authority and selector admission are deferred; '+stage+' production hooks remain disabled; test-only native markers are not A settlement'],
            'regressions':[],'gate_status':'OWNED_GATES_PASS'}
    # Apply the external frozen schema and counter semantics, not our own JSON pass flag.
    import importlib.util
    loader=importlib.util.spec_from_file_location('d_frozen_contract',HERE/'contracts/verify_contract.py')
    contract=importlib.util.module_from_spec(loader);loader.loader.exec_module(contract)
    schema=read(HERE/'contracts/contract.schema.json')
    contract.shape({'schema':'cc.cd/1.0.0','kind':'StageManifest','body':result},schema,schema);contract.semantics('StageManifest',result)
    details={'scope':stage+'_ONLY','resolution_observations':resolution_details,'resolution_queries_are_python_case_observations':True,'acl_sequence_observations':acl_details,'acl_transitions_are_python_case_observations':True,'metadata_recovery_observations':recoveries,'metadata_observations_are_existing_unit_cases':True,'fixture_outcomes':dict(collections.Counter(r['actual'] for r in observations)),
             'regression_modules':regression,'duplicates':[],'native_executions':len(native_rows),'native_details':native_details,'connected_clients_observed':0,
             'docs_pin_is_prequalification':True,'final_docs_pin':'external handoff after report commit'}
    return result,details

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--stage',required=True);p.add_argument('--root',type=Path,required=True)
    p.add_argument('--source',required=True);p.add_argument('--tree',required=True);p.add_argument('--jar',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args()
    try:
        result,details=verify(a.stage,a.root.resolve(),a.source,a.tree,a.jar);write(a.output,result);write(a.output.parent/'oracle-details.json',details)
        print(json.dumps(result,sort_keys=True))
    except Exception as error:
        write(a.output,{'stage':a.stage,'gate_status':'INCONCLUSIVE','reason':str(error),'source_sha':a.source,'tree_sha':a.tree})
        raise
