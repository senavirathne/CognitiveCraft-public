#!/usr/bin/env python3
"""Actions evidence checks for the non-executable blueprint staging owner."""
from pathlib import Path
import argparse,hashlib,json,shutil,subprocess,xml.etree.ElementTree as ET,zipfile
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/rb008-evidence'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def junit():
    modules={};scoped={}
    for module in ('core','providers','fabric'):
        paths=list((ROOT/module/'build/test-results/test').glob('TEST-*.xml'));cases=[c for p in paths for c in ET.parse(p).getroot().iter('testcase')]
        assert paths and cases and not any(c.find(t) is not None for c in cases for t in ('failure','error','skipped'))
        modules[module]={'cases':len(cases),'reports':{p.name:sha(p) for p in paths}}
        if module=='core':
            for suite,minimum in (('BlueprintMaterialsTest',30),('BlueprintStagingOwnerTest',15)):
                p=next(p for p in paths if p.name.endswith('.'+suite+'.xml'));n=len(list(ET.parse(p).getroot().iter('testcase')));assert n>=minimum;scoped[suite]={'cases':n,'sha256':sha(p)}
    OUT.mkdir(parents=True,exist_ok=True);(OUT/'junit.json').write_text(json.dumps({'modules':modules,'scoped':scoped},indent=2)+'\n')
    print('RB008_MODULE_COUNTS '+json.dumps({m:r['cases'] for m,r in modules.items()}));print('RB008_SCOPED_CASES '+str(sum(r['cases'] for r in scoped.values())))
def identity():
    jar=ROOT/'fabric/build/libs/ai-villages-0.1.0.jar'
    with zipfile.ZipFile(jar) as z:
        assert 'dev/aivillages/core/kernel/BlueprintMaterials.class' in z.namelist()
        assert 'dev/aivillages/fabric/FabricBlueprintMaterials.class' in z.namelist()
        assert not any('BlueprintStagingGameTests' in n or 'BlueprintMaterialsTest' in n or 'Fixtures' in n for n in z.namelist())
    git=lambda arg:subprocess.check_output(['git','rev-parse',arg],cwd=ROOT,text=True).strip()
    value={'source':git('HEAD'),'tree':git('HEAD^{tree}'),'jarSha256':sha(jar),'java':25,'gradle':'9.7.0','minecraft':'26.3','loader':'0.19.5','fabricApi':'0.161.0+26.3','loom':'1.18.1'}
    OUT.mkdir(parents=True,exist_ok=True);(OUT/'identity.json').write_text(json.dumps(value,indent=2)+'\n');print('RB008_IDENTITY '+json.dumps(value))
def native(phase):
    folder=ROOT/'fabric/build/gametest-run';report=folder/'gametest-results.xml';log=folder/'logs/latest.log'
    cases=[c for c in ET.parse(report).getroot().iter('testcase') if c.get('name','').startswith('ai_villages_tests:')]
    assert len(cases)==1 and 'blueprint_staging_game_tests' in cases[0].get('name','') and not any(cases[0].find(t) is not None for t in ('failure','error','skipped'))
    lines=[l for l in log.read_text().splitlines() if 'RB008_NATIVE phase='+phase in l];assert len(lines)==5
    assert all('modelCalls=0' in l for l in lines)
    OUT.mkdir(parents=True,exist_ok=True)
    for p,suffix in ((report,'.xml'),(log,'.log')):shutil.copy2(p,OUT/(phase+suffix))
    (OUT/(phase+'.json')).write_text(json.dumps({'phase':phase,'cases':1,'rows':5,'xmlSha256':sha(report),'logSha256':sha(log)},indent=2)+'\n')
def client():
    folder=ROOT/'build/e2e/rb008';data=json.loads((folder/'results.json').read_text());rows=[r for r in data['results'] if r['id']=='GP-RB-08'];assert len(rows)==1 and rows[0]['status']=='PASS'
    e=rows[0]['evidence'];assert all(e.get(k) for k in ('admissions','artifacts','staged','preflight','unsupported','foreignDenied','foreignCancelDenied','clientContainers','offline','offlineProcessProof','cancelled','killBoundary','checkpoint','recovered'))
    assert len(e['admissions'])==2 and len(e['artifacts'])==2 and len(e['staged'])>=3
    assert [x['deposited'] for x in e['staged']]==[0,3,5]
    assert e['offlineProcessProof']['backendUnavailable'] and e['offlineProcessProof']['sameRelease']
    assert e['offlineProcessProof']['warm']['start']>e['offlineProcessProof']['cold']['start']
    assert len(e['clientContainers'])>=2 and all(x['actual']==x['expected'] for x in e['clientContainers'])
    assert 'INTERRUPTED' in e['recovered']['state'] and len(rows[0]['screenshots'])>=6
    assert e['killBoundary']['deposited']>0 and e['killBoundary']['remaining']>0 and 1<=e['clockSteps']<=512
    OUT.mkdir(parents=True,exist_ok=True);shutil.copy2(folder/'results.json',OUT/'connected-client.json')
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['junit','identity','native','client']);p.add_argument('--phase');a=p.parse_args()
    native(a.phase) if a.mode=='native' else globals()[a.mode]()
