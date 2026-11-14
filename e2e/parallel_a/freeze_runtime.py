"""A-owned transport/setup adapter. Original gate assertions and production authority are untouched."""
import hashlib,json,os,subprocess,types
from pathlib import Path
from freeze_plan import CASES,compose_files,JAVA_IMAGE,CLIENT_IMAGE

def isolate_orchestrator(profile):
    """Keep the host observer/CLI off the two cores already reserved for the JVM."""
    before=sorted(os.sched_getaffinity(0))
    if profile not in CASES:raise ValueError('Unknown A10 profile')
    if not {0,1,2,3}<=set(before):raise RuntimeError('Original four-core Docker topology unavailable')
    os.sched_setaffinity(0,{2,3});after=sorted(os.sched_getaffinity(0))
    if after!=[2,3]:raise RuntimeError('Host orchestrator affinity did not take effect')
    return {'profile':profile,'hostBefore':before,'orchestratorAfter':after,'isolationApplied':True,
            'serverCores':[0,1],'clientCores':{'PlayerA':[2],'PlayerB':[3]},'addedCores':0,
            'jvmHeapGcAndGateLimitsChanged':False,'causeOfBatch55CostFailure':'UNPROVEN',
            'lowerProfileChange':'A00-A03 now use the existing four-core split; no additional CPU, memory or sample exclusions'}

def attach(h):
    profile=os.environ.get('COGNITIVECRAFT_A10_PROFILE')
    if not profile:return
    if profile not in CASES:raise ValueError('Unknown A10 native gate')
    h.a10_profile=profile
    # Capture the most-derived implementation, including RecoveryHarness checks.
    h.a10_original_start=h.start
    h.start=types.MethodType(start,h)
    h.a10_original_stop=h.stop
    h.stop=types.MethodType(stop,h)
    h.compose=types.MethodType(compose,h)
    if profile in ('A00','A01','A02','A03','A04','A05'):h.bootstrap=types.MethodType(bootstrap,h)

def start(h,component):
    result=h.a10_original_start(component)
    if component=='server':
        from compiler_budget import prepare
        prepare(h)
    return result

def stop(h,component='server',hard=False):
    if component!='server' or component not in h.booted:
        return h.a10_original_stop(component,hard=hard)
    from suite import atomic,read
    from stop_ack import container,acknowledge
    identifier=h.compose('ps','--all','-q','minecraft').stdout.strip()
    before=container(json.loads(docker(h,'inspect',identifier).stdout),identifier,h.project)
    process=read(h.root/'server/process.json')
    result=h.a10_original_stop(component,hard=hard)
    # Compose stop returning is not a native-process exit acknowledgement.
    # Wait for the exact container, then inspect that same stopped incarnation.
    waited=docker(h,'wait',identifier,timeout=20).stdout.strip()
    after=container(json.loads(docker(h,'inspect',identifier).stdout),identifier,h.project)
    receipt=acknowledge(before,after,waited,process,hard)
    receipt.update(profile=h.a10_profile)
    h.a10_stopped_server=receipt
    path=h.root/('a10-stop-ack-'+__import__('uuid').uuid4().hex+'.json')
    atomic(path,receipt);h.event('a10-container-stop-acknowledged',receipt)
    return result

def docker(h,*args,timeout=300):
    from suite import ROOT
    value=subprocess.run(['docker',*args],cwd=ROOT,env=h.env,text=True,capture_output=True,timeout=timeout)
    with (h.root/'docker.log').open('a') as out:out.write(value.stdout+value.stderr)
    if value.returncode:raise RuntimeError('A10 pinned Docker setup '+str(args[:2])+': '+value.stderr[-2400:])
    return value

def compose(h,*args):
    from suite import ROOT,atomic
    stopped=getattr(h,'a10_stopped_server',None)
    if h.a10_profile=='A03' and args==('up','-d','--no-deps','minecraft') and stopped is not None:
        # Recreate only the stopped container, using the same declared image,
        # environment and bind-mounted saved world. This starts one new JVM;
        # it cannot replay a failed start or use the old Running-state decision.
        args=('up','-d','--no-deps','--force-recreate','minecraft')
        h.a10_stopped_server=None
        h.event('a10-recreate-stopped-server',{'container':stopped['after']['id'],
                'previousStartedAt':stopped['after']['startedAt'],'worldVolumeChanged':False,
                'attempts':1,'sameComposeImageEnvironmentAndResources':True})
    files=compose_files(h.a10_profile);cmd=['docker','compose','-p',h.project]
    for file in files:cmd+=['-f',str(ROOT/file)]
    if args and args[0]=='build':
        for image in (JAVA_IMAGE,CLIENT_IMAGE):docker(h,'pull',image)
    value=subprocess.run(cmd+list(args),cwd=ROOT,env=h.env,text=True,capture_output=True)
    with (h.root/'docker.log').open('a') as out:out.write(value.stdout+value.stderr)
    if value.returncode:raise RuntimeError('A10 Compose '+value.stderr[-2400:])
    if args and args[0]=='build':
        images=json.loads(docker(h,'image','inspect','cognitivecraft-e2e:java25',JAVA_IMAGE,CLIENT_IMAGE).stdout)
        atomic(h.root/'a08-build-images.json',[{k:i[k] for k in ('Id','RepoTags','RepoDigests','Architecture','Os')} for i in images])
        paths=[*files,'e2e/parallel_a/Dockerfile.freeze','e2e/parallel_a/dependency_probe.py']
        atomic(h.root/'a08-build-inputs.json',{'composeCommand':cmd+list(args),'actualDockerfile':'e2e/parallel_a/Dockerfile.freeze',
            'actualBuildContext':'e2e/parallel_a','files':{p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in paths},
            'networkDuringBuildRun':'none','runtimeNetwork':'host','immutableBaseImages':[JAVA_IMAGE,CLIENT_IMAGE]})
        user=h.env.get('E2E_UID','1000')+':'+h.env.get('E2E_GID','1000')
        dependency=json.loads(docker(h,'run','--rm','--network','none','--user',user,'--entrypoint','python3',
                                    'cognitivecraft-e2e:java25','/opt/cognitivecraft/dependency_probe.py').stdout)
        atomic(h.root/'a08-image-dependencies.json',dependency)
    return value

def bootstrap(h,items):
    from recovery_seed import start
    from suite import reconnect_players,require,atomic
    seed=Path(os.environ['COGNITIVECRAFT_A10_SEED']).resolve()
    start(h,seed)
    require(all(any(a['item']==item and a['status']=='PASS' for a in h.admissions) for item in items),'Original genuine admission missing')
    # The shared seed reader deliberately stops after binding; older gate entry points expect a running server.
    h.start('server');reconnect_players(h,list(h.players));h.console(['tick unfreeze','tick rate 20'],lifecycle=True)
    atomic(h.root/'a10-bootstrap.json',{'profile':h.a10_profile,'kind':'EXACT_STOPPED_THREE_ADMISSION_WORLD',
        'requestedMaterials':list(items),'importedCurrentGateSuccesses':0,'newNativeCasesAllRequired':True})
