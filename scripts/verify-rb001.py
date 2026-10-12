#!/usr/bin/env python3
"""Strict Actions-only RB evidence checks; definitions and historical CI are never acceptance."""
from pathlib import Path
import argparse,hashlib,json,shutil,subprocess,xml.etree.ElementTree as ET,zipfile

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/rb001-evidence'

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def junit():
    expected={'core':{'ResourceDeliveryTest':7,'OakLogGatewayTest':9,'OakLogJobLifecycleTest':5,'OakLogDiscoveryTest':3,'OakLogLanguageTest':2,
                      'OakLogPrerequisiteStockTest':2},
              'providers':{'OakLogNeedleWireTest':1,'OakLogGenerationTest':1}}
    results={}
    for module,classes in expected.items():
        for name,minimum in classes.items():
            paths=list((ROOT/module/'build/test-results/test').glob('TEST-*.'+name+'.xml'))
            if len(paths)!=1:raise SystemExit('Missing/duplicate RB JUnit selector '+name)
            cases=list(ET.parse(paths[0]).getroot().iter('testcase'))
            if len(cases)<minimum or any(c.find(t) is not None for c in cases for t in ('failure','error','skipped')):
                raise SystemExit('Nonpassing/empty mandatory RB selector '+name)
            results[name]={'cases':len(cases),'xml':str(paths[0].relative_to(ROOT)),'sha256':sha(paths[0])}
    OUT.mkdir(parents=True,exist_ok=True)
    (OUT/'junit.json').write_text(json.dumps(results,indent=2)+'\n')
    print(json.dumps(results,indent=2))

def native(phase,classname,marker):
    folder=ROOT/'fabric/build/gametest-run';report=folder/'gametest-results.xml'
    cases=[c for c in ET.parse(report).getroot().iter('testcase') if c.get('name','').startswith('ai_villages_tests:')]
    if len(cases)!=1 or classname not in cases[0].get('name','') or any(c.find(t) is not None for c in cases for t in ('failure','error','skipped')):
        raise SystemExit('Expected one actually passing selected RB native case: '+phase)
    log=folder/'logs/latest.log'
    if marker not in log.read_text():raise SystemExit('Missing RB native physical/model marker '+marker)
    OUT.mkdir(parents=True,exist_ok=True)
    for source,label in [(report,phase+'.xml'),(log,phase+'.log')]:shutil.copy2(source,OUT/label)
    (OUT/(phase+'.json')).write_text(json.dumps({'phase':phase,'cases':1,'name':cases[0].get('name'),'xmlSha256':sha(report),'logSha256':sha(log)},indent=2)+'\n')

def identity():
    jar=ROOT/'fabric/build/libs/ai-villages-0.1.0.jar'
    with zipfile.ZipFile(jar) as z:
        required=['dev/aivillages/core/kernel/OakLogDelivery.class','dev/aivillages/core/kernel/ResourceDelivery.class',
                  'dev/aivillages/fabric/mixin/ItemPickupOwnershipMixin.class']
        if any(name not in z.namelist() for name in required):raise SystemExit('Missing RB production packaging')
        if any('OakLogFixtures' in name or 'OakLogTestSupport' in name for name in z.namelist()):raise SystemExit('Test proposal shipped in production JAR')
    def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT,text=True).strip()
    value={'source':git('rev-parse','HEAD'),'tree':git('rev-parse','HEAD^{tree}'),'jarSha256':sha(jar),
           'java':25,'gradle':'9.7.0','minecraft':'26.3','loader':'0.19.5','fabricApi':'0.161.0+26.3','loom':'1.18.1'}
    OUT.mkdir(parents=True,exist_ok=True);(OUT/'identity.json').write_text(json.dumps(value,indent=2)+'\n');print(json.dumps(value))

def client():
    folder=ROOT/'build/e2e/rb001';data=json.loads((folder/'results.json').read_text())
    cases=[x for x in data['results'] if x['id']=='GP-RB-01']
    if len(cases)!=1 or cases[0]['status']!='PASS':raise SystemExit('Mandatory real RB client family did not pass')
    e=cases[0]['evidence']
    if not all(e.get(k) for k in ('canonicalAcquisition','actualLanguage','offlineWarmReuse','foreignDenied','killBoundary','recovered')):
        raise SystemExit('Incomplete RB network/genuine/offline/kill proof')
    invocations=e.get('standaloneReuse',[])
    if len(invocations)!=3 or sorted(r['quantities']['DEPOSIT'] for r in invocations)!=[1,4,7]:
        raise SystemExit('Missing independent standalone amount assertions')
    if len({r['artifact'] for r in invocations})!=1 or len({tuple(r['source']) for r in invocations})<2 or len({tuple(r['destination']) for r in invocations})<2:
        raise SystemExit('Standalone method did not rebind both source and destination')
    interface=e.get('typedInterface',{})
    if interface.get('descriptor',{}).get('dependencies')!=[] or interface.get('spec',{}).get('capability')!='cognitivecraft:deliver_oak_logs':
        raise SystemExit('Standalone typed interface or dependency evidence absent')
    stock=e.get('stockBoundary',{})
    if stock.get('initialOldStock')!=11 or stock.get('newlyAcquired')!=1 or stock.get('consumerJobs')!=0:
        raise SystemExit('Old stock was substituted for independent acquisition')
    OUT.mkdir(parents=True,exist_ok=True);shutil.copy2(folder/'results.json',OUT/'connected-client.json')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('mode',choices=['junit','native','identity','client'])
    parser.add_argument('--phase');parser.add_argument('--class-name');parser.add_argument('--marker');a=parser.parse_args()
    if a.mode=='native':native(a.phase,a.class_name,a.marker)
    else:globals()[a.mode]()
