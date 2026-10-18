#!/usr/bin/env python3
"""Strict Actions-only birch-family evidence selection, counts and source identity."""
from pathlib import Path
import argparse,hashlib,json,shutil,subprocess,xml.etree.ElementTree as ET,zipfile
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/rb007-evidence'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def junit():
    expected={'core':{'BirchResourceFamilyTest':30,'BirchLeaseDiscoveryTest':6},'providers':{'BirchResourceWireTest':4}}
    result={};scoped={}
    for module in ('core','providers','fabric'):
        paths=list((ROOT/module/'build/test-results/test').glob('TEST-*.xml'));cases=[c for p in paths for c in ET.parse(p).getroot().iter('testcase')]
        assert paths and cases and not any(c.find(t) is not None for c in cases for t in ('failure','error','skipped'))
        result[module]={'cases':len(cases),'suites':len(paths),'reports':{p.name:sha(p) for p in paths}}
        for name,minimum in expected.get(module,{}).items():
            selected=[p for p in paths if p.name.endswith('.'+name+'.xml')];assert len(selected)==1
            selected_cases=list(ET.parse(selected[0]).getroot().iter('testcase'));assert len(selected_cases)>=minimum
            scoped[name]={'cases':len(selected_cases),'sha256':sha(selected[0])}
    OUT.mkdir(parents=True,exist_ok=True);(OUT/'junit.json').write_text(json.dumps({'modules':result,'scoped':scoped},indent=2)+'\n')
    print('RB007_MODULE_COUNTS '+json.dumps({m:r['cases'] for m,r in result.items()}));print('RB007_SCOPED_CASES '+str(sum(r['cases'] for r in scoped.values())))
def native(phase,classname,marker):
    folder=ROOT/'fabric/build/gametest-run';report=folder/'gametest-results.xml';log=folder/'logs/latest.log'
    cases=[c for c in ET.parse(report).getroot().iter('testcase') if c.get('name','').startswith('ai_villages_tests:')]
    assert len(cases)==1 and classname in cases[0].get('name','') and not any(cases[0].find(t) is not None for t in ('failure','error','skipped'))
    count=sum(marker in line for line in log.read_text().splitlines());expected=3 if classname=='birch_kernel_game_tests' else 1
    assert count==expected,(phase,count,expected)
    if classname=='birch_kernel_game_tests':
        genuine='true' if phase.startswith('genuine') else 'false';warm=phase=='genuine-warm'
        lines=[l for l in log.read_text().splitlines() if 'RB007_INDEPENDENT phase=' in l]
        assert len(lines)==(2 if warm else 5),(phase,len(lines))
        assert all(('genuine='+genuine) in l for l in lines)
        for capability in ('cognitivecraft:deliver_birch_logs','cognitivecraft:produce_birch_planks'):
            assert any(capability in l for l in lines)
        if warm:assert all('modelCalls=0' in l for l in lines)
    OUT.mkdir(parents=True,exist_ok=True)
    for p,suffix in ((report,'.xml'),(log,'.log')):shutil.copy2(p,OUT/(phase+suffix))
    (OUT/(phase+'.json')).write_text(json.dumps({'phase':phase,'cases':1,'rows':count,'xmlSha256':sha(report),'logSha256':sha(log)},indent=2)+'\n')
def identity():
    jar=ROOT/'fabric/build/libs/ai-villages-0.1.0.jar'
    with zipfile.ZipFile(jar) as z:
        names=z.namelist()
        for name in ('BirchLogDelivery','BirchPlankProduction','LogAcquisition'):
            assert 'dev/aivillages/core/kernel/'+name+'.class' in names
        assert 'dev/aivillages/fabric/FabricResourceItems.class' in names
        assert not any('BirchFixtures' in n or 'BirchKernelGameTests' in n or 'BirchResourceFamilyTest' in n for n in names)
    git=lambda arg:subprocess.check_output(['git','rev-parse',arg],cwd=ROOT,text=True).strip()
    value={'source':git('HEAD'),'tree':git('HEAD^{tree}'),'jarSha256':sha(jar),'java':25,'gradle':'9.7.0','minecraft':'26.3','loader':'0.19.5','fabricApi':'0.161.0+26.3','loom':'1.18.1'}
    OUT.mkdir(parents=True,exist_ok=True);(OUT/'identity.json').write_text(json.dumps(value,indent=2)+'\n');print('RB007_IDENTITY '+json.dumps(value))
def client():
    folder=ROOT/'build/e2e/rb007';data=json.loads((folder/'results.json').read_text());rows=[r for r in data['results'] if r['id']=='GP-RB-07'];assert len(rows)==1 and rows[0]['status']=='PASS'
    e=rows[0]['evidence'];assert all(e.get(k) for k in ('independentAdmissions','artifacts','knownBindings','actualLanguage','composition','foreignDenied','foreignCancelDenied','foreignJobDenied','foreignJobCancelDenied','clientContainers','offlineWarmReuse','offlineGraph','offlineProcessProof','cancelled','killBoundary','recovered','checkpoint'))
    assert len(e['independentAdmissions'])==2 and len(e['artifacts'])==2 and len(e['actualLanguage'])==2 and len(e['offlineWarmReuse'])==2
    assert sorted(r['amount'] for r in e['knownBindings'])==[1,4,8,64]
    assert [r['harvested'] for r in e['composition']]==[0,1,2] and e['offlineGraph']['harvested']==2
    assert len({r['actor'] for r in e['independentAdmissions']+e['offlineWarmReuse']})==2
    assert len(e['clientContainers'])==4 and {r['player'] for r in e['clientContainers']}=={'PlayerA','PlayerB'}
    assert {r['item'] for r in e['clientContainers']}=={'minecraft:birch_log','minecraft:birch_planks'} and all(r['actual']==r['expected'] and r['actual']>0 for r in e['clientContainers'])
    assert e['killBoundary']['phase']=='EXECUTING' and any(r['stage']=='CRAFT' for r in e['killBoundary']['resourceReceipts'])
    assert 1<=e['clockSteps']<=512 and 'INTERRUPTED' in e['recovered']['description'] and 'CANCELLED' in e['cancelled']['description']
    proof=e['offlineProcessProof'];assert proof['sameRelease'] and proof['backendUnavailable'] and proof['warm']['start']>proof['cold']['start']
    assert len(rows[0]['screenshots'])>=6
    OUT.mkdir(parents=True,exist_ok=True);shutil.copy2(folder/'results.json',OUT/'connected-client.json')
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['junit','native','identity','client']);p.add_argument('--phase');p.add_argument('--class-name');p.add_argument('--marker');a=p.parse_args()
    if a.mode=='native':native(a.phase,a.class_name,a.marker)
    else:globals()[a.mode]()
