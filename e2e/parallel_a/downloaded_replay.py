"""A10 full artifact transport and a second, isolated replay on a separate runner."""
import argparse,base64,hashlib,json,os,re,shutil,stat,subprocess,sys,urllib.error,urllib.request,zipfile
from collections import Counter
from pathlib import Path,PurePosixPath
import xml.etree.ElementTree as ET
from freeze_plan import CASES,MODULES,CLIENT_IMAGE,BASE
from freeze_bundle import ROOT,verify

def read(p):return json.loads(Path(p).read_text())
def write(p,v):Path(p).parent.mkdir(parents=True,exist_ok=True);Path(p).write_text(json.dumps(v,indent=2)+'\n')
def digest(p):
    with Path(p).open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

def safe_zip(path,expected_bytes=None,expected_sha=None):
    path=Path(path)
    if expected_bytes is not None:assert path.stat().st_size==expected_bytes
    if expected_sha is not None:assert digest(path)==expected_sha
    with zipfile.ZipFile(path) as z:
        names=z.namelist();assert names and len(names)==len(set(names))
        assert all(not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts and '\\' not in n for n in names)
        assert not any(stat.S_ISLNK(i.external_attr>>16) for i in z.infolist())
        assert len(names)<1000000 and sum(i.file_size for i in z.infolist())<20*1024**3
        assert z.testzip() is None
        return len(names)

def valid_manifest(m):
    assert m['schema']==1 and 0<m['archiveBytes']<=4*419430400 and re.fullmatch('[0-9a-f]{64}',m['archiveSha256'])
    parts=m['parts'];assert 1<=len(parts)<=4 and [p['name'] for p in parts]==['part-%03d.bin'%i for i in range(len(parts))]
    assert sum(p['bytes'] for p in parts)==m['archiveBytes']
    assert all(0<p['bytes']<=419430400 and re.fullmatch('[0-9a-f]{64}',p['sha256']) for p in parts)
    return parts

def same_review(body,expected):
    assert json.loads(body)==json.loads(expected),'Downloaded reader values differ from the first CI review'
    assert body==expected,'Downloaded reader bytes differ from the first CI review'

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):return None

def api_json(path):
    token=os.environ['GH_TOKEN'];repo=os.environ['GITHUB_REPOSITORY']
    request=urllib.request.Request('https://api.github.com/repos/'+repo+path,headers={
        'Authorization':'Bearer '+token,'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28'})
    with urllib.request.urlopen(request,timeout=90) as response:return json.load(response)

def acquire(a,directory,source):
    assert not a['expired'] and a['size_in_bytes']>0
    assert a.get('workflow_run',{}).get('head_sha',source)==source
    expected=a['digest'];assert expected.startswith('sha256:') and re.fullmatch('[0-9a-f]{64}',expected[7:])
    repo=os.environ['GITHUB_REPOSITORY'];url='https://api.github.com/repos/'+repo+'/actions/artifacts/'+str(a['id'])+'/zip'
    request=urllib.request.Request(url,headers={'Authorization':'Bearer '+os.environ['GH_TOKEN'],'Accept':'application/vnd.github+json'})
    try:
        response=urllib.request.build_opener(NoRedirect).open(request,timeout=90)
    except urllib.error.HTTPError as e:
        if e.code not in (301,302,303,307,308):raise RuntimeError('Artifact redirect failed: '+str(a['id'])) from None
        signed=e.headers.get('Location','');assert signed.startswith('https://')
        try:response=urllib.request.urlopen(signed,timeout=120)
        except urllib.error.URLError:raise RuntimeError('Artifact transfer failed: '+str(a['id'])) from None
    directory=Path(directory);directory.mkdir(parents=True,exist_ok=True);archive=directory/'artifact.zip'
    with response,archive.open('wb') as out:shutil.copyfileobj(response,out)
    entries=safe_zip(archive,a['size_in_bytes'],expected[7:])
    with zipfile.ZipFile(archive) as z:z.extractall(directory/'files')
    archive.unlink()  # The verified extracted payload and immutable artifact receipt remain.
    return directory/'files',{'id':a['id'],'name':a['name'],'bytes':a['size_in_bytes'],'sha256':expected[7:],
                              'entries':entries,'status':'PASS_FULL_OUTER_SIZE_SHA_CRC_PATHS_AND_NO_SYMLINKS'}

def rebuild(profile,parts,folder,source):
    inputs=[];receipts=[]
    for i,a in enumerate(parts):
        files,receipt=acquire(a,folder/('part-'+str(i)),source);inputs.append(files);receipts.append(receipt)
    manifests=[p for directory in inputs for p in directory.rglob('parts-manifest.json')]
    assert len(manifests)==len(parts);m=read(manifests[0]);rows=valid_manifest(m)
    assert all(read(p)==m for p in manifests)
    actual={p.name:p for directory in inputs for p in directory.rglob('part-*.bin')}
    assert set(actual)=={p['name'] for p in rows}
    archive=folder/(profile+'.zip')
    with archive.open('wb') as out:
        for row in rows:
            path=actual[row['name']];assert path.stat().st_size==row['bytes'] and digest(path)==row['sha256']
            with path.open('rb') as source_file:shutil.copyfileobj(source_file,out)
    entries=safe_zip(archive,m['archiveBytes'],m['archiveSha256'])
    for i in range(len(parts)):shutil.rmtree(folder/('part-'+str(i)))
    native=folder/'native';native.mkdir()
    with zipfile.ZipFile(archive) as z:z.extractall(native)
    archive.unlink()
    return native,{'status':'PASS_FULL_DOWNLOADED_NATIVE_BYTES_SHA_CRC_SAFE_PATHS','profile':profile,**m,
                   'entries':entries,'extraction':'FULL','outerArtifacts':receipts}

def display(s):
    out='';i=0
    while i<len(s):
        if s[i]!='{':out+=s[i];i+=1;continue
        depth=1;j=i+1
        while j<len(s) and depth:
            if s[j]=='{':depth+=1
            elif s[j]=='}':depth-=1
            j+=1
        assert depth==0;body=s[i+1:j-1];parts=[];start=0;depth=0
        for k,c in enumerate(body):
            if c in '{[(':depth+=1
            elif c in '}])':depth-=1
            elif c==',' and depth==0:parts.append(body[start:k].strip());start=k+1
        parts.append(body[start:].strip());out+='{'+', '.join(sorted(display(p) for p in parts))+'}';i=j
    return out

def producer_reports(contracts,lifecycle,frozen):
    modules={};differences=[]
    for module,count in MODULES.items():
        gradle=[c for p in (contracts/module/'build/test-results/test').glob('TEST-*.xml') for c in ET.parse(p).getroot().iter('testcase')]
        native=[c for p in (ROOT/'build/parallel-a/a10-native-unit'/module).glob('TEST-*.xml') for c in ET.parse(p).getroot().iter('testcase')]
        assert len(gradle)==len(native)==count and all(c.find(t) is None for c in gradle+native for t in ('failure','error','skipped'))
        ids=[];actual=[]
        for c in native:
            lines=c.findtext('system-out').splitlines();ids.append(next(v[11:] for v in lines if v.startswith('unique-id: ')))
            actual.append((c.get('classname'),next(v[14:] for v in lines if v.startswith('display-name: '))))
        expected=[(c.get('classname'),c.get('name')) for c in gradle];assert len(set(ids))==count
        a=Counter((cls,display(n)) for cls,n in actual);e=Counter((cls,display(n)) for cls,n in expected);assert a==e
        differences.append({'module':module,'gradleOnly':list((Counter(expected)-Counter(actual)).elements()),'nativeOnly':list((Counter(actual)-Counter(expected)).elements())})
        modules[module]={'cases':count,'uniqueNativeJUnitIds':len(set(ids)),'sameGradleExecutionMultiset':True}
    directories=list((lifecycle/'lifecycle-evidence').iterdir());assert len(directories)==1
    ev=directories[0];state=lifecycle/'lifecycle-run'/ev.name/'lifecycle-state';accepted=read(ev/'acceptance.json')
    assert accepted['source']==frozen['source'] and accepted['sourceTree']==frozen['tree'] and accepted['releaseSha256']==frozen['productionSha256']
    assert accepted['phases']=={'cold':7,'warm':7} and not accepted['modelsConfigured']
    proof=read(state/'lifecycle-proof.json')
    for phase in ('cold','warm'):
        cases=[c for c in ET.parse(ev/(phase+'.xml')).getroot().iter('testcase') if c.get('name','').startswith('ai_villages_tests:')]
        assert len(cases)==7 and all(c.find(t) is None for c in cases for t in ('failure','error','skipped'))
        saved=read(ev/(phase+'-identity.json'));citizen=next(c for c in saved['citizens'] if c['citizenId']==proof['citizen'])
        assert citizen['lifecycle']['event']=='DEATH' and citizen['lifecycle']['acknowledged']==7
    assert (ev/'warm-identity.json').read_bytes()==(state/'data/cognitivecraft/citizens/v1/state.json').read_bytes()
    assert (ev/'physical-proof.json').read_bytes()==(state/'lifecycle-proof.json').read_bytes()
    assert proof['deposited']==1 and proof['receipts']==proof['effects']==3
    cold=read(state/'lifecycle-trace-cold.json');warm=read(state/'lifecycle-trace-warm.json')
    assert cold['enabled'] and warm['enabled'] and cold['process']!=warm['process'] and cold['actor']==warm['actor']
    for trace in (cold,warm):
        assert len(trace['rows'])<=trace['capacity']==128 and all(a['sequence']<b['sequence'] for a,b in zip(trace['rows'],trace['rows'][1:]))
    masks=[r['marker']['acknowledged'] for r in cold['rows'] if r['phase']=='PUBLISHED' and r['marker']['event']=='DEATH'];assert masks==[0,1,3,7]
    assert not any(r.get('fact',{}).get('event')=='DEATH' for r in warm['rows'])
    traces=list(ET.parse(contracts/'core/build/test-results/test/TEST-dev.aivillages.core.kernel.LifecycleTraceTest.xml').getroot().iter('testcase'))
    assert len(traces)==12 and all(c.find(t) is None for c in traces for t in ('failure','error','skipped'))
    return {'status':'PASS_DOWNLOADED_PRODUCER_COHORT_AND_NATIVE_LIFECYCLE','uniqueModuleContracts':sum(MODULES.values()),'modules':modules,
            'rawDisplayDifferences':differences,'doubleCounted':False,'nativeLifecycleDiagnostics':14,'cold':7,'warm':7,
            'traceContractsIncludedInCore':12,'excludedAlwaysPassControls':2,'deathPublicationMasks':masks,'physicalProof':proof,
            'lifecycleAcceptance':accepted}

def isolated_review(args):
    def guard(event,values):
        if event in ('socket.connect','socket.bind','socket.getaddrinfo','subprocess.Popen','os.system'):
            raise RuntimeError('Network and subprocess launch forbidden during downloaded raw replay')
    sys.addaudithook(guard)
    from review_freeze import review
    value=review(args.root,args.profile,args.source,args.jar_sha256);body=json.dumps(value,indent=2)+'\n'
    same_review(body,args.expected.read_text());args.out.mkdir(parents=True,exist_ok=True)
    (args.out/'review.json').write_text(body)
    write(args.out/'replay-result.json',{'status':'PASS_FULL_DOWNLOADED_NETWORK_DENIED_REPLAY_IDENTICAL_TO_CI','profile':args.profile,
        'source':args.source,'productionSha256':args.jar_sha256,'cases':value['requiredCases'],'reviewSha256':hashlib.sha256(body.encode()).hexdigest(),
        'reviewBytes':len(body.encode()),'bytesIdentical':True,'fullExtraction':True,'network':'Docker --network none plus socket audit rejection',
        'subprocesses':'Audit rejection of subprocess.Popen and os.system'})

def standard_report(scratch,source,native_jar):
    runs=api_json('/actions/runs?head_sha='+source+'&per_page=100')['workflow_runs']
    selected=[r for r in runs if r['name']=='CognitiveCraft verification' and r['head_sha']==source and r['event']=='push']
    assert len(selected)==1;run=selected[0];assert run['status']=='completed' and run['conclusion']=='success'
    artifacts=api_json('/actions/runs/'+str(run['id'])+'/artifacts?per_page=100')['artifacts']
    jars=[a for a in artifacts if a['name'].startswith('cognitivecraft-imp008-'+source+'-')]
    reports=[a for a in artifacts if a['name'].startswith('verification-reports-'+str(run['id'])+'-')]
    assert len(jars)==len(reports)==1
    jar_dir,jar_receipt=acquire(jars[0],scratch/'standard-jar',source)
    report_dir,report_receipt=acquire(reports[0],scratch/'standard-reports',source)
    audit=read(jar_dir/'release-audit.json');jar=jar_dir/'libs/ai-villages-0.1.0.jar'
    assert jar.stat().st_size==audit['bytes'] and digest(jar)==audit['sha256']
    assert audit['testModExcluded'] and audit['testClassesExcluded'] and audit['modelWeightsExcluded']
    modules={}
    for module,count in MODULES.items():
        cases=[c for p in (report_dir/module/'build/test-results/test').glob('TEST-*.xml') for c in ET.parse(p).getroot().iter('testcase')]
        assert len(cases)==count and all(c.find(t) is None for c in cases for t in ('failure','error','skipped'))
        modules[module]=count
    jobs=api_json('/actions/runs/'+str(run['id'])+'/jobs?per_page=100')['jobs']
    verification=next(j for j in jobs if j['name']=='Java 25 / Fabric verification');assert verification['conclusion']=='success'
    result={'status':'PASS_STANDARD_CI_AND_DOWNLOADED_RELEASE_BYTES_VERIFIED','run':run['id'],'job':verification['id'],'source':source,
            'jarSha256':audit['sha256'],'jarBytes':audit['bytes'],'releaseAudit':audit,'modules':modules,
            'nativeAndStandardJarsAreDistinct':audit['sha256']!=native_jar,'artifacts':[jar_receipt,report_receipt]}
    shutil.rmtree(scratch/'standard-jar');shutil.rmtree(scratch/'standard-reports');return result

def run(args):
    frozen=verify(args.source,args.jar_sha256);run_id=os.environ['GITHUB_RUN_ID'];attempt=os.environ['GITHUB_RUN_ATTEMPT']
    artifacts=[];page=1
    while True:
        value=api_json('/actions/runs/'+run_id+'/artifacts?per_page=100&page='+str(page));artifacts+=value['artifacts']
        if len(artifacts)>=value['total_count']:break
        page+=1;assert page<=10
    assert len({a['id'] for a in artifacts})==len(artifacts)
    def named(name):
        found=[a for a in artifacts if a['name']==name];assert len(found)==1;return found[0]
    args.out.mkdir(parents=True,exist_ok=True);scratch=args.out/'materialized';scratch.mkdir()
    contract_a=named('cc-runtime-a-a04-contracts-'+args.source+'-'+run_id+'-'+attempt)
    life_a=named('cc-runtime-a-a07-lifecycle-'+args.source+'-'+run_id+'-'+attempt)
    contracts,contract_receipt=acquire(contract_a,scratch/'contracts',args.source)
    lifecycle,life_receipt=acquire(life_a,scratch/'lifecycle',args.source)
    producer=producer_reports(contracts,lifecycle,frozen);producer['artifacts']=[contract_receipt,life_receipt]
    shutil.rmtree(scratch/'contracts');shutil.rmtree(scratch/'lifecycle')
    standard=standard_report(scratch,args.source,args.jar_sha256)
    subprocess.run(['docker','pull',CLIENT_IMAGE],check=True)
    report={'schema':1,'stage':'A10','status':'RUNNING_FULL_DOWNLOADED_ARTIFACT_REPLAYS','source':args.source,'tree':frozen['tree'],
        'productionSha256':args.jar_sha256,'run':int(run_id),'attempt':int(attempt),'producer':producer,'standard':standard,'profiles':{},
        'binary':{r['path']:{'bytes':r['bytes'],'sha256':r['sha256']} for r in frozen['files'] if r['path'] in
                  ('fabric/build/libs/ai-villages-0.1.0.jar','build/e2e-driver/observer.jar','build/e2e-driver/client.jar')},
        'offlineImage':CLIENT_IMAGE,'omittedTasks':0,'omittedTests':0,'scope':'Original native E2E, native host and metadata-fault classifications retained.'}
    for profile,case_ids in CASES.items():
        prefix='cc-runtime-a-a10-'+profile+'-native-part-';suffix='-'+run_id+'-'+attempt
        parts=sorted([a for a in artifacts if re.fullmatch(re.escape(prefix)+r'[0-3]'+re.escape(suffix),a['name'])],key=lambda a:a['name'])
        assert parts and [int(a['name'][len(prefix)]) for a in parts]==list(range(len(parts)))
        folder=scratch/profile;folder.mkdir();native,transport=rebuild(profile,parts,folder,args.source)
        out=args.out/'replays'/profile;out.mkdir(parents=True);expected=args.diagnostics/profile/'review.json'
        command=['docker','run','--rm','--network','none','--user',str(os.getuid())+':'+str(os.getgid()),
                 '-e','PYTHONDONTWRITEBYTECODE=1','-v',str(ROOT)+':/repo:ro','-v',str(out.resolve())+':/out:rw','-w','/repo',
                 '--entrypoint','python3',CLIENT_IMAGE,'e2e/parallel_a/downloaded_replay.py','review',
                 '--root','/repo/'+native.relative_to(ROOT).as_posix(),'--profile',profile,'--source',args.source,
                 '--jar-sha256',args.jar_sha256,'--expected','/repo/'+expected.relative_to(ROOT).as_posix(),'--out','/out']
        subprocess.run(command,check=True)
        replay=read(out/'replay-result.json');value=read(out/'review.json')
        assert replay['cases']==len(case_ids) and [c['id'] for c in value['cases']]==list(case_ids)
        report['profiles'][profile]={'transport':transport,'replay':replay,'review':value,'metadata':read(native/'metadata.json')}
        write(args.out/'report.json',report);print('A10_DOWNLOADED_PROFILE '+json.dumps({'profile':profile,'cases':replay['cases'],'status':replay['status']}),flush=True)
        shutil.rmtree(folder)
    assert len(report['profiles'])==10 and sum(p['replay']['cases'] for p in report['profiles'].values())==121
    paths=subprocess.check_output(['git','diff','--name-only',BASE],cwd=ROOT,text=True).splitlines()
    report['ownership']={'commonSource':BASE,'paths':[{'path':p,'bytes':(ROOT/p).stat().st_size,'sha256':digest(ROOT/p)} for p in paths]}
    report.update(status='PASS_ALL_TEN_FULL_DOWNLOADED_RAW_REPLAYS_IDENTICAL_TO_FIRST_CI_REVIEW',requiredMixedGates=121,fullRawReplays=10,
                  materialization='Every complete raw archive fully downloaded, verified, reconstructed and extracted before its isolated second review',
                  workspaceUnavailableFallback='Second reader runs on a distinct aggregate runner after actual artifact transport; no local-runtime claim.')
    write(args.out/'report.json',report);body=json.dumps(report,sort_keys=True,separators=(',',':')).encode()
    (args.out/'report.canonical.json').write_bytes(body);encoded=base64.b64encode(body).decode();chunks=[encoded[i:i+12000] for i in range(0,len(encoded),12000)]
    print('A10_DOWNLOADED_REPORT_SHA256 '+hashlib.sha256(body).hexdigest(),flush=True)
    for i,chunk in enumerate(chunks):print('A10_DOWNLOADED_REPORT_PART '+str(i)+'/'+str(len(chunks))+' '+chunk,flush=True)

def main():
    p=argparse.ArgumentParser();p.add_argument('operation',choices=('run','review'));p.add_argument('--source',required=True);p.add_argument('--jar-sha256',required=True)
    p.add_argument('--diagnostics',type=Path);p.add_argument('--root',type=Path);p.add_argument('--profile',choices=CASES);p.add_argument('--expected',type=Path)
    p.add_argument('--out',required=True,type=Path);a=p.parse_args()
    a.out=a.out.resolve()
    if a.operation=='review':isolated_review(a)
    else:
        a.diagnostics=a.diagnostics.resolve();run(a)

if __name__=='__main__':main()
