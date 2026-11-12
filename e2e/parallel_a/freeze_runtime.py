"""A-owned transport/setup adapter. Original gate assertions and production authority are untouched."""
import hashlib,json,os,subprocess,types
from pathlib import Path
from freeze_plan import CASES,compose_files,JAVA_IMAGE,CLIENT_IMAGE

def attach(h):
    profile=os.environ.get('COGNITIVECRAFT_A10_PROFILE')
    if not profile:return
    if profile not in CASES:raise ValueError('Unknown A10 native gate')
    h.a10_profile=profile
    h.compose=types.MethodType(compose,h)
    if profile in ('A00','A01','A02','A03','A04','A05'):h.bootstrap=types.MethodType(bootstrap,h)

def docker(h,*args):
    from suite import ROOT
    value=subprocess.run(['docker',*args],cwd=ROOT,env=h.env,text=True,capture_output=True,timeout=300)
    with (h.root/'docker.log').open('a') as out:out.write(value.stdout+value.stderr)
    if value.returncode:raise RuntimeError('A10 pinned Docker setup '+str(args[:2])+': '+value.stderr[-2400:])
    return value

def compose(h,*args):
    from suite import ROOT,atomic
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
