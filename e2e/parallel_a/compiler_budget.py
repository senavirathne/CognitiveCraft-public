"""Keep owned compiler and test-writer bursts on one of A10's existing two cores."""
import hashlib,json,os,uuid
from pathlib import Path

def birth(pid):
    fields=Path(f'/proc/{pid}/stat').read_text().split(') ',1)[1].split()
    if fields[0]=='Z':raise ValueError('Native JVM is no longer live')
    return int(fields[19])

def configure(pid,start_ticks,cpus,require_observers=False):
    if birth(pid)!=start_ticks:raise ValueError('Native JVM birth changed')
    if Path(f'/proc/{pid}').stat().st_uid!=os.getuid():raise ValueError('Foreign JVM UID refused')
    cpus=sorted(cpus)
    if len(cpus)!=2 or sorted(os.sched_getaffinity(pid))!=cpus:raise ValueError('Original two-core JVM budget required')
    tasks=list(Path(f'/proc/{pid}/task').iterdir())
    if len(tasks)>256:raise ValueError('Native thread inspection bound')
    rows=[{'tid':int(p.name),'name':(p/'comm').read_text().strip()} for p in tasks]
    servers=[r for r in rows if r['name']=='Server thread']
    compilers=[r for r in rows if r['name'].startswith(('C1 CompilerThre','C2 CompilerThre'))]
    observers=[r for r in rows if r['name']=='e2e-observation' or r['name'].startswith('runtime-a-')]
    if len(servers)!=1 or not all(any(r['name'].startswith(kind) for r in compilers) for kind in ('C1 ','C2 ')):
        raise ValueError('Actual server and both compiler tiers required before priority changes')
    if require_observers and (sum(r['name']=='e2e-observation' for r in observers)!=2
                              or not any(r['name']=='runtime-a-measu' for r in observers)):
        raise ValueError('Both actual arena writers and measurement writer required before budget changes')
    background=[*compilers,*observers];target=[cpus[1]]
    for row in [*servers,*background]:
        row['niceBefore']=os.getpriority(os.PRIO_PROCESS,row['tid'])
        row['cpusBefore']=sorted(os.sched_getaffinity(row['tid']))
        if not row['cpusBefore'] or not set(row['cpusBefore'])<=set(cpus):raise ValueError('Native thread escaped the declared CPU set')
    if servers[0]['cpusBefore']!=cpus:raise ValueError('Server must retain both original cores')
    if birth(pid)!=start_ticks:raise ValueError('Native JVM birth changed before priority changes')
    for row in background:
        os.setpriority(os.PRIO_PROCESS,row['tid'],max(10,row['niceBefore']))
        os.sched_setaffinity(row['tid'],set(target))
    for row in [*servers,*background]:
        row['niceAfter']=os.getpriority(os.PRIO_PROCESS,row['tid'])
        row['cpusAfter']=sorted(os.sched_getaffinity(row['tid']))
        if row['cpusAfter']!=(cpus if row is servers[0] else target):raise ValueError('Declared thread affinity did not take effect')
    if servers[0]['niceAfter']!=servers[0]['niceBefore']:raise ValueError('Server thread priority changed')
    if any(r['niceAfter']!=max(10,r['niceBefore']) for r in background):raise ValueError('Background niceness did not take effect')
    if birth(pid)!=start_ticks:raise ValueError('Native JVM birth changed after priority changes')
    return {'status':'PASS_OWNED_COMPILER_BUDGET','hostJvmPid':pid,'startTicks':start_ticks,'cpus':cpus,
            'serverThread':servers[0],'compilerThreads':compilers,'observerThreads':observers,'observersRequired':require_observers,
            'backgroundCpus':target,'heapGcLimitsAndSamplesChanged':False,
            'addedCores':0,'priorityPolicy':'Owned C1/C2 and test writers use the second original core, niceness >=10; server retains both original cores and priority'}

def prepare(h):
    from baseline import native_process
    from suite import atomic
    owned=native_process(h);namespace=owned['namespaceProcess']
    expected=hashlib.sha256((Path(__file__).resolve().parents[2]/'fabric/build/libs/ai-villages-0.1.0.jar').read_bytes()).hexdigest()
    if namespace['kind']!='server' or namespace['releaseSha256']!=expected:raise ValueError('Frozen owned server JAR required')
    if owned['containerResources']['cpusetCpus']!='0-1':raise ValueError('Original Docker server CPU set required')
    value=configure(owned['hostJvmPid'],owned['startTicks'],[0,1],require_observers=True)
    value.update(profile=h.a10_profile,nativeProcess=owned)
    path=h.root/f"a10-compiler-budget-{owned['hostJvmPid']}-{owned['startTicks']}-{uuid.uuid4()}.json"
    atomic(path,value);h.event('a10-compiler-budget',value)
    return value

def review(root,profile,jar,stops):
    records=[json.loads(p.read_text()) for p in sorted(root.glob('a10-compiler-budget-*.json'))]
    assert records,'Missing owned native compiler budget records'
    for row in records:
        assert row['status']=='PASS_OWNED_COMPILER_BUDGET' and row['profile']==profile
        assert row['cpus']==[0,1] and row['addedCores']==0 and row['heapGcLimitsAndSamplesChanged'] is False
        owned=row['nativeProcess'];process=owned['namespaceProcess']
        assert owned['hostJvmPid']==row['hostJvmPid'] and owned['startTicks']==row['startTicks']
        assert owned['containerResources']['cpusetCpus']=='0-1' and process['kind']=='server' and process['releaseSha256']==jar
        assert row['serverThread']['name']=='Server thread'
        assert row['serverThread']['niceBefore']==row['serverThread']['niceAfter']
        assert row['serverThread']['cpusBefore']==row['serverThread']['cpusAfter']==[0,1]
        assert row['backgroundCpus']==[1] and row['observersRequired'] is True
        compilers=row['compilerThreads'];assert compilers
        assert all(any(r['name'].startswith(tier) for r in compilers) for tier in ('C1 ','C2 '))
        observers=row['observerThreads'];assert sum(t['name']=='e2e-observation' for t in observers)==2
        assert any(t['name']=='runtime-a-measu' for t in observers)
        assert all(t['name']=='e2e-observation' or t['name'].startswith('runtime-a-') for t in observers)
        background=[*compilers,*observers]
        assert len({r['tid'] for r in [row['serverThread'],*background]})==len(background)+1
        for thread in background:
            assert thread['cpusBefore'] in ([0,1],[1]) and thread['cpusAfter']==[1]
            assert thread['niceAfter']==max(10,thread['niceBefore'])
        assert all(t['name'].startswith(('C1 CompilerThre','C2 CompilerThre')) for t in compilers)
    covered={(r['nativeProcess']['namespaceProcess']['pid'],r['nativeProcess']['namespaceProcess']['start']) for r in records}
    assert all((p['pid'],p['start']) in covered for p in stops),'Unrecorded stopped native JVM'
    return {'status':'PASS_ALL_STOPPED_NATIVE_JVMS_COMPILER_BUDGET','records':len(records),'nativeBirths':len(covered),
            'serverCores':[0,1],'backgroundCores':[1],'addedCores':0,'serverThreadPriorityChanged':False,'serverThreadAffinityChanged':False}
