"""Read retained A10 failures only; never runs or qualifies a native gate."""
import argparse,base64,collections,datetime,hashlib,json,math,os,re,shutil,subprocess,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent/'e2e/parallel_a'))
EXPECTED_SOURCE='200d51b100088afc14dafe2cb03b13759fd5f638'
EXPECTED_TREE='23b83a0c4f3cb073db76e5ebb1749f98c6c0e9e9'
EXPECTED_JAR='2287e83c32700c8bcde8e504d7371a74a55eace7cdc8b49164327b896feb3d46'
RUN=43431476056
IMAGE='mcr.microsoft.com/playwright/python@sha256:a7f6cf3ae520c9d670ad956572c13747ed5abdbba5123a01526f873ed1662528'
def read(p):return json.loads(Path(p).read_text())
def sha(p):
    h=hashlib.sha256()
    with Path(p).open('rb') as f:
        while block:=f.read(1024*1024):h.update(block)
    return h.hexdigest()
def write(p,v):
    Path(p).parent.mkdir(parents=True,exist_ok=True);Path(p).write_text(json.dumps(v,indent=2)+'\n')
def duration(s):
    if isinstance(s,(int,float)):return s/1000000
    m=re.fullmatch(r'PT([0-9.]+)S',str(s));return float(m[1])*1000 if m else None
def jfr_summary(path):
    rows=read(path)['recording']['events'];types=collections.Counter();threads=collections.Counter();leaf=collections.Counter();inclusive=collections.Counter();alloc=collections.Counter();allocation_sites=collections.Counter();stacks=[];pauses=[]
    for e in rows:
        kind=e['type'];v=e.get('values',{});types[kind]+=1
        thread=v.get('sampledThread') or v.get('eventThread') or {};name=thread.get('javaName') or thread.get('osName') or 'unknown'
        trace=v.get('stackTrace') or {};frames=[]
        for f in trace.get('frames',[]):
            m=f.get('method',{});t=m.get('type') or {};frames.append(t.get('name','?')+'.'+m.get('name','?'))
        if kind in ('jdk.ExecutionSample','jdk.NativeMethodSample'):
            threads[name]+=1
            if name=='Server thread' and frames:
                leaf[frames[0]]+=1
                for frame in set(frames):inclusive[frame]+=1
                stacks.append({'time':v.get('startTime'),'frames':frames})
        if kind=='jdk.ObjectAllocationSample':
            c=v.get('objectClass') or {};weight=int(v.get('weight',0));alloc[c.get('name','?')]+=weight
            if frames:allocation_sites[frames[0]]+=weight
        if kind in ('jdk.GarbageCollection','jdk.GCPhasePause','jdk.SafepointBegin','jdk.JavaMonitorEnter'):
            d=duration(v.get('duration'))
            if d is not None and d>=1:pauses.append({'event':kind,'thread':name,'time':v.get('startTime'),'millis':d,'name':v.get('name'),'frames':frames[:12]})
    return {'scope':'SEPARATE_RECORDED_DIAGNOSTIC_WINDOW; ORIGINAL_QUALIFYING_GATE_REMAINS_FAILED','types':dict(types),'sampleThreads':dict(threads),
            'serverSamples':len(stacks),'serverLeaf':leaf.most_common(45),'serverInclusive':inclusive.most_common(65),
            'sampledAllocationBytesByClass':alloc.most_common(35),'sampledAllocationBytesByLeaf':allocation_sites.most_common(35),
            'pausesOver1ms':sorted(pauses,key=lambda p:p['millis'],reverse=True)[:120],'serverStacks':stacks}
def analyse(root,out):
    root=Path(root);out=Path(out);out.mkdir(parents=True,exist_ok=True)
    def guard(event,values):
        if event in ('socket.connect','socket.bind','socket.getaddrinfo','subprocess.Popen','os.system'):raise RuntimeError('Diagnostic analysis forbids network and subprocesses')
    sys.addaudithook(guard)
    meta=read(root/'metadata.json');frozen=read(root/'a10-frozen-runtime.json')
    assert meta['sourceCommit']==frozen['source']==EXPECTED_SOURCE and meta['sourceTree']==frozen['tree']==EXPECTED_TREE
    assert meta['releaseSha256']==frozen['productionSha256']==EXPECTED_JAR
    summary={'source':EXPECTED_SOURCE,'tree':EXPECTED_TREE,'productionSha256':EXPECTED_JAR,'metadata':meta,'nativeQualification':False,'importedPasses':0,'selectedFiles':{},'results':{},'windows':{},'jfr':{},'gcLogs':{}}
    for path in sorted(root.glob('*.json')):
        if path.name in ('metadata.json','a10-frozen-runtime.json'):continue
        value=read(path);summary['selectedFiles'][path.name]={'bytes':path.stat().st_size,'sha256':sha(path)}
        if path.name.endswith('results.json'):
            failed=[];passed=[]
            for row in value.get('cases',[]):
                status=row.get('status','');status=status.get('status','') if isinstance(status,dict) else status
                if str(status).startswith('PASS'):passed.append({'id':row.get('id'),'status':status})
                else:failed.append(row)
            summary['results'][path.name]={'status':value.get('status'),'reason':value.get('reason'),'trace':value.get('trace'),
                'cases':len(value.get('cases',[])),'passedCollectionRows':passed,'failedRows':failed,
                'originalStatus':value.get('originalStatus'),'originalOwnerSettlements':value.get('originalOwnerSettlements')}
            shutil.copy2(path,out/path.name)
        elif any(t in path.name for t in ('performance','raw-window','cost','preparation-window','cpu-profile','collection','fixture','continuation')):
            summary['windows'][path.name]=value;shutil.copy2(path,out/path.name)
    for path in sorted(root.rglob('*cost-window*.json')):
        rel=path.relative_to(root).as_posix();summary['windows'][rel]=read(path);summary['selectedFiles'][rel]={'bytes':path.stat().st_size,'sha256':sha(path)}
    rpc=root/'storage-rpc'
    if rpc.exists():summary['storageRpcs']={p.name:read(p) for p in sorted(rpc.glob('*.json'))}
    for path in sorted(root.glob('server/*gc*.log*')):
        lines=path.read_text(errors='replace').splitlines();pauses=[];safe=[]
        for line in lines:
            stamp=re.match(r'\[([^\]]+)\]',line);when=None
            if stamp:
                try:when=int(datetime.datetime.fromisoformat(stamp[1]).timestamp()*1000)
                except ValueError:pass
            if re.search(r'\[gc\s*\]',line) and 'Pause' in line:
                m=re.search(r'([0-9.]+)ms\s*$',line)
                if m:pauses.append({'timeMillis':when,'millis':float(m[1]),'line':line})
            if 'Safepoint' in line:
                m=re.search(r'Total: ([0-9]+) ns',line)
                if m and int(m[1])>=1000000:safe.append({'timeMillis':when,'millis':int(m[1])/1000000,'line':line})
        summary['gcLogs'][path.name]={'bytes':path.stat().st_size,'sha256':sha(path),'gcPauses':pauses,'safepointsOver1ms':safe}
    for path in sorted(root.glob('diagnostic-jfr-json/*.json')):
        summary['jfr'][path.name]=jfr_summary(path)
    correlations={}
    for name,value in summary['windows'].items():
        if not isinstance(value,dict) or not isinstance(value.get('tickSamples'),list):continue
        ticks=value['tickSamples']
        if not ticks or not all(isinstance(t,dict) and 'time' in t and 'nanos' in t for t in ticks):continue
        samples=sorted(t['nanos']/1000000 for t in ticks);rank=lambda p:samples[max(0,math.ceil(len(samples)*p/100)-1)]
        start=min(t['time'] for t in ticks);end=max(t['time'] for t in ticks)
        pauses=[{'log':log,**p} for log,v in summary['gcLogs'].items() for p in v['gcPauses'] if p['timeMillis'] is not None and start-50<=p['timeMillis']<=end+50]
        correlations[name]={'samples':len(ticks),'allIncluded':True,'startMillis':start,'endMillis':end,'tickMs':{'p50':rank(50),'p95':rank(95),'p99':rank(99),'max':max(samples)},'gcPausesInWindow':pauses}
    summary['originalRawWindowCorrelations']=correlations
    write(out/'analysis.json',summary)
def run(out):
    from downloaded_replay import api_json,acquire,rebuild
    out=Path(out).resolve();out.mkdir(parents=True,exist_ok=True);material=out/'materialized';material.mkdir()
    run=api_json('/actions/runs/'+str(RUN));assert run['head_sha']==EXPECTED_SOURCE and run['status']=='completed'
    jobs=api_json('/actions/runs/'+str(RUN)+'/jobs?per_page=100')['jobs']
    native=[j for j in jobs if re.fullmatch(r'a10-native \(A0[0-9]\)',j['name'])];assert len(native)==10 and all(j['status']=='completed' for j in native)
    failed={re.search(r'(A0[0-9])',j['name'])[1]:j for j in native if j['conclusion']!='success'};assert failed
    artifacts=api_json('/actions/runs/'+str(RUN)+'/artifacts?per_page=100')['artifacts']
    report={'schema':1,'kind':'READ_ONLY_COMPLETE_RETAINED_FAILURE_ARTIFACT_DIAGNOSIS','source':EXPECTED_SOURCE,'tree':EXPECTED_TREE,'productionSha256':EXPECTED_JAR,
            'originalRun':RUN,'diagnosticSource':os.environ['GITHUB_SHA'],'diagnosticRun':int(os.environ['GITHUB_RUN_ID']),'nativeQualification':False,'importedCurrentPasses':0,'profiles':{}}
    for profile,job in sorted(failed.items()):
        folder=material/profile;folder.mkdir();prefix='cc-runtime-a-a10-'+profile+'-native-part-';suffix='-'+str(RUN)+'-1'
        parts=sorted([a for a in artifacts if re.fullmatch(re.escape(prefix)+r'[0-3]'+re.escape(suffix),a['name'])],key=lambda a:a['name'])
        assert parts and [int(a['name'][len(prefix)]) for a in parts]==list(range(len(parts)))
        raw,transport=rebuild(profile,parts,folder,EXPECTED_SOURCE)
        selected=[a for a in artifacts if a['name']=='cc-runtime-a-a10-'+profile+'-diagnostics-'+str(RUN)+'-1'];assert len(selected)==1
        diagnostic,receipt=acquire(selected[0],folder/'first-diagnostics',EXPECTED_SOURCE)
        exports=raw/'diagnostic-jfr-json';exports.mkdir();jfr=[]
        for index,path in enumerate(sorted(raw.rglob('*.jfr'))):
            target=exports/(str(index)+'.json')
            with target.open('w') as output:
                subprocess.run(['jfr','print','--json','--events','jdk.ExecutionSample,jdk.NativeMethodSample,jdk.ObjectAllocationSample,jdk.GarbageCollection,jdk.GCPhasePause,jdk.SafepointBegin,jdk.CPULoad,jdk.JavaMonitorEnter',str(path)],check=True,stdout=output)
            jfr.append({'path':path.relative_to(raw).as_posix(),'bytes':path.stat().st_size,'sha256':sha(path),'exportBytes':target.stat().st_size})
        dest=out/profile;dest.mkdir()
        subprocess.run(['docker','run','--rm','--network','none','--user',str(os.getuid())+':'+str(os.getgid()),
                        '-e','PYTHONDONTWRITEBYTECODE=1','-v',str(Path.cwd())+':/repo:ro','-v',str(raw)+':/raw:ro','-v',str(dest)+':/out:rw',
                        '--entrypoint','python3',IMAGE,'/repo/a10_diagnostics.py','analyse','--root','/raw','--out','/out'],check=True)
        summary=read(dest/'analysis.json');report['profiles'][profile]={'job':job['id'],'originalConclusion':job['conclusion'],'transport':transport,'firstDiagnosticTransport':receipt,'jfrInputs':jfr,'analysis':summary}
        shutil.rmtree(folder);write(out/'report.json',report)
        print('A10_DIAGNOSED_PROFILE '+json.dumps({'profile':profile,'originalConclusion':job['conclusion'],'nativeQualification':False,'archiveBytes':transport['archiveBytes'],'archiveSha256':transport['archiveSha256']}),flush=True)
    shutil.rmtree(material);body=json.dumps(report,sort_keys=True,separators=(',',':')).encode();(out/'report.canonical.json').write_bytes(body)
    encoded=base64.b64encode(body).decode();chunks=[encoded[i:i+12000] for i in range(0,len(encoded),12000)]
    print('A10_FAILURE_DIAGNOSTIC_SHA256 '+hashlib.sha256(body).hexdigest(),flush=True)
    for i,chunk in enumerate(chunks):print('A10_FAILURE_DIAGNOSTIC_PART '+str(i)+'/'+str(len(chunks))+' '+chunk,flush=True)
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('operation',choices=['run','analyse']);p.add_argument('--root');p.add_argument('--out',required=True);a=p.parse_args()
    analyse(a.root,a.out) if a.operation=='analyse' else run(a.out)
