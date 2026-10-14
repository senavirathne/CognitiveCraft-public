#!/usr/bin/env python3
"""Strict Actions-only native/connected composition evidence verification."""
from pathlib import Path
import argparse,json,hashlib,shutil,subprocess,zipfile,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/rb005-evidence'
def native(phase):
    root=ROOT/'fabric/build/gametest-run';xml=root/'gametest-results.xml';log=root/'logs/latest.log'
    cases=[c for c in ET.parse(xml).getroot().iter('testcase') if c.get('name','').startswith('ai_villages_tests:')]
    assert len(cases)==1 and 'composition_game_tests' in cases[0].get('name','')
    assert not any(c.find(t) is not None for c in cases for t in ('failure','error','skipped'))
    body=log.read_text();genuine=phase!='controlled';warm=phase.endswith('warm')
    expected='RB005_NATIVE phase='+('warm' if warm else 'cold')+' genuine='+str(genuine).lower()
    lines=[l for l in body.splitlines() if expected in l]
    assert len(lines)==(1 if warm else 8) and all('modelCalls=0' in l for l in lines)
    if not warm:assert len([l for l in body.splitlines() if 'RB005_ADMISSION genuine='+str(genuine).lower() in l])==4
    OUT.mkdir(parents=True,exist_ok=True)
    for p in (xml,log):shutil.copy2(p,OUT/(phase+p.suffix))
    (OUT/(phase+'.json')).write_text(json.dumps({'phase':phase,'nativeCases':1,'goldenRows':len(lines),'xmlSha256':hashlib.sha256(xml.read_bytes()).hexdigest(),'logSha256':hashlib.sha256(log.read_bytes()).hexdigest()},indent=2)+'\n')
def client():
    folder=ROOT/'build/e2e/rb005';data=json.loads((folder/'results.json').read_text());rows=[r for r in data['results'] if r['id']=='GP-RB-COMPOSE-01']
    assert len(rows)==1 and rows[0]['status']=='PASS'
    e=rows[0]['evidence'];assert len(e['independentAcquisitions'])==4 and len(e['goldenRows'])==7
    assert all(e.get(k) for k in ('actualLanguage','foreignDenied','offlineWarmReuse','cancelled','killBoundary','recovered'))
    assert e['offlineProcessProof']['sameRelease'] and e['offlineProcessProof']['backendUnavailable']
    assert e['offlineProcessProof']['warm']['start']>e['offlineProcessProof']['cold']['start']
    assert e['recovered']['state']=='INTERRUPTED'
    OUT.mkdir(parents=True,exist_ok=True);shutil.copy2(folder/'results.json',OUT/'connected-client.json')
def junit():
    result={}
    for module in ('core','providers','fabric'):
        files=sorted((ROOT/module/'build/test-results/test').glob('TEST-*.xml'))
        cases=[c for p in files for c in ET.parse(p).getroot().iter('testcase')]
        assert files and cases and not any(c.find(t) is not None for c in cases for t in ('failure','error','skipped'))
        result[module]={'cases':len(cases),'suites':len(files),'reports':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in files}}
    OUT.mkdir(parents=True,exist_ok=True);(OUT/'junit.json').write_text(json.dumps(result,indent=2)+'\n')
    print('RB005_MODULE_COUNTS '+json.dumps({k:v['cases'] for k,v in result.items()}))
def identity():
    jar=ROOT/'fabric/build/libs/ai-villages-0.1.0.jar'
    with zipfile.ZipFile(jar) as z:
        assert 'dev/aivillages/core/kernel/ResourceComposition.class' in z.namelist()
        assert 'dev/aivillages/core/kernel/Jobs$Preparation.class' in z.namelist()
        assert not any('CompositionGameTests' in n or 'AxeFixtures' in n for n in z.namelist())
    git=lambda arg:subprocess.check_output(['git','rev-parse',arg],cwd=ROOT,text=True).strip()
    value={'source':git('HEAD'),'tree':git('HEAD^{tree}'),'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'java':25,'gradle':'9.7.0','minecraft':'26.3','loader':'0.19.5','fabricApi':'0.161.0+26.3','loom':'1.18.1'}
    OUT.mkdir(parents=True,exist_ok=True);(OUT/'identity.json').write_text(json.dumps(value,indent=2)+'\n');print('RB005_IDENTITY '+json.dumps(value))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['native','client','junit','identity']);p.add_argument('--phase');a=p.parse_args()
    if a.mode=='native':native(a.phase)
    else:globals()[a.mode]()
