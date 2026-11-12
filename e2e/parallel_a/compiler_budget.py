"""Lower only owned compiler scheduling priority within A10's existing two cores."""
import hashlib,json,os,uuid
from pathlib import Path

def birth(pid):
    fields=Path(f'/proc/{pid}/stat').read_text().split(') ',1)[1].split()
    if fields[0]=='Z':raise ValueError('Native JVM is no longer live')
    return int(fields[19])

def configure(pid,start_ticks,cpus):
    if birth(pid)!=start_ticks:raise ValueError('Native JVM birth changed')
    if Path(f'/proc/{pid}').stat().st_uid!=os.getuid():raise ValueError('Foreign JVM UID refused')
    cpus=sorted(cpus)
    if len(cpus)!=2 or sorted(os.sched_getaffinity(pid))!=cpus:raise ValueError('Original two-core JVM budget required')
    tasks=list(Path(f'/proc/{pid}/task').iterdir())
    if len(tasks)>256:raise ValueError('Native thread inspection bound')
    rows=[{'tid':int(p.name),'name':(p/'comm').read_text().strip()} for p in tasks]
    servers=[r for r in rows if r['name']=='Server thread']
    compilers=[r for r in rows if r['name'].startswith(('C1 CompilerThre','C2 CompilerThre'))]
    if len(servers)!=1 or not all(any(r['name'].startswith(kind) for r in compilers) for kind in ('C1 ','C2 ')):
        raise ValueError('Actual server and both compiler tiers required before priority changes')
    for row in [*servers,*compilers]:
        row['niceBefore']=os.getpriority(os.PRIO_PROCESS,row['tid'])
        row['cpusBefore']=sorted(os.sched_getaffinity(row['tid']))
        if row['cpusBefore']!=cpus:raise ValueError('Native thread escaped the declared CPU set')
    if birth(pid)!=start_ticks:raise ValueError('Native JVM birth changed before priority changes')
    for row in compilers:os.setpriority(os.PRIO_PROCESS,row['tid'],max(10,row['niceBefore']))
    for row in [*servers,*compilers]:
        row['niceAfter']=os.getpriority(os.PRIO_PROCESS,row['tid'])
        row['cpusAfter']=sorted(os.sched_getaffinity(row['tid']))
        if row['cpusAfter']!=row['cpusBefore']:raise ValueError('Native thread affinity changed')
    if servers[0]['niceAfter']!=servers[0]['niceBefore']:raise ValueError('Server thread priority changed')
    if any(r['niceAfter']!=max(10,r['niceBefore']) for r in compilers):raise ValueError('Compiler niceness did not take effect')
    if birth(pid)!=start_ticks:raise ValueError('Native JVM birth changed after priority changes')
    return {'status':'PASS_OWNED_COMPILER_BUDGET','hostJvmPid':pid,'startTicks':start_ticks,'cpus':cpus,
            'serverThread':servers[0],'compilerThreads':compilers,'heapGcLimitsAndSamplesChanged':False,
            'addedCores':0,'priorityPolicy':'C1/C2 niceness at least 10; server priority unchanged'}

def prepare(h):
    from baseline import native_process
    from suite import atomic
    owned=native_process(h);namespace=owned['namespaceProcess']
    expected=hashlib.sha256((Path(__file__).resolve().parents[2]/'fabric/build/libs/ai-villages-0.1.0.jar').read_bytes()).hexdigest()
    if namespace['kind']!='server' or namespace['releaseSha256']!=expected:raise ValueError('Frozen owned server JAR required')
    if owned['containerResources']['cpusetCpus']!='0-1':raise ValueError('Original Docker server CPU set required')
    value=configure(owned['hostJvmPid'],owned['startTicks'],[0,1])
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
        compilers=row['compilerThreads'];assert compilers
        assert all(any(r['name'].startswith(tier) for r in compilers) for tier in ('C1 ','C2 '))
        assert len({r['tid'] for r in [row['serverThread'],*compilers]})==len(compilers)+1
        for thread in [row['serverThread'],*compilers]:assert thread['cpusBefore']==thread['cpusAfter']==[0,1]
        for thread in compilers:
            assert thread['name'].startswith(('C1 CompilerThre','C2 CompilerThre'))
            assert thread['niceAfter']==max(10,thread['niceBefore'])
    covered={(r['nativeProcess']['namespaceProcess']['pid'],r['nativeProcess']['namespaceProcess']['start']) for r in records}
    assert all((p['pid'],p['start']) in covered for p in stops),'Unrecorded stopped native JVM'
    return {'status':'PASS_ALL_STOPPED_NATIVE_JVMS_COMPILER_BUDGET','records':len(records),'nativeBirths':len(covered),
            'serverCores':[0,1],'addedCores':0,'serverThreadPriorityChanged':False}
