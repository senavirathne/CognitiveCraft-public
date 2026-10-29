#!/usr/bin/env python3
"""Raw native host collection. Fixed D-only worlds, one release, two JVMs for cold reload."""
import base64,errno,json,os,shutil,socket,struct,subprocess,time
from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED
from common import REPO,HERE,require,read,write,sha

WORLD='d0200000-0000-4000-8000-000000000001'
SCHEDULE=[('baseline','DStorageBaselineGameTests','BASELINE-D01'),('incarnation','DStorageIncarnationGameTests','D02-GT01'),('topology','DStorageTopologyGameTests','D02-GT02-TOPOLOGY'),('reload-write','DStorageTopologyReloadGameTests','D02-GT02-RELOAD'),('reload-read','DStorageTopologyReloadGameTests','D02-GT02-RELOAD'),('performance','DStorageLifecycleCostGameTests','D02-PERF')]

def run(root,release,fixture,identity,baseline,stage="D02"):
    root=Path(root);root.mkdir(parents=True,exist_ok=False)
    sdk=REPO/'build/server-sdk';lock=read(REPO/'scripts/dependencies.json')
    with ZipFile(sdk/'server26.jar') as z:
        libraries=[sdk/'libs'/Path(line.split('\t')[2]).name for line in z.read('META-INF/libraries.list').decode().splitlines()]
    runtime=[sdk/'minecraft-server.jar',*libraries,*[sdk/'libs'/v['file'] for v in lock['loader_libraries']]]
    require(sha(baseline)==read(HERE/'predecessors/D01-lock.json')['jar_sha256'],'wrong unchanged baseline JAR')
    metadata={'identity':identity,'release_sha256':sha(release),'fixture_sha256':sha(fixture),'world':WORLD,'seed':20261027,
              'runtime':[{'path':str(p.relative_to(REPO)),'sha256':sha(p)} for p in runtime],
              'stage':stage,'connected_clients':0,'phases':[]}
    write(root/'identity.json',metadata)
    subprocess.run(['javap','-classpath',str(sdk/'minecraft-server.jar'),'-p','-c',*['net.minecraft.world.level.block.entity.'+n for n in ('BlockEntity','BaseContainerBlockEntity','ChestBlockEntity','BarrelBlockEntity')]],stdout=(root/'api-bytecode.txt').open('w'),check=True)
    for phase,entry,case in SCHEDULE+([('adapters','DStorageAdaptersGameTests','D05-GT01')]if stage in ('D05','D06','D07','D08')else[])+([('come','DComeGameTests','D06-GT01')]if stage in ('D06','D07','D08')else[])+([('follow','DFollowGameTests','D07-GT01')]if stage in ('D07','D08')else[])+([('courier','DChestCourierGameTests','D08-GT01'),('courier-crash-write','DChestCourierCrashGameTests','D08-GT01'),('courier-crash-read','DChestCourierCrashGameTests','D08-GT01')]if stage=='D08'else[]):
        phase_release=baseline if phase=='baseline' else release
        target=root/phase;target.mkdir();game=root/'worlds'/('courier-crash'if phase.startswith('courier-crash')else'reload' if phase.startswith('reload') else phase);mods=game/'mods';mods.mkdir(parents=True,exist_ok=True)
        report=target/'native-results.xml';evidence=target/'evidence';evidence.mkdir()
        with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
        properties='online-mode=false\nserver-ip=127.0.0.1\nserver-port='+str(port)+'\nlevel-name=d-isolated\nlevel-seed=20261027\n'
        (game/'server.properties').write_text(properties);(game/'eula.txt').write_text('eula=true\n')
        selected=mods/'parallel-d-fixtures.jar'
        with ZipFile(fixture) as source,ZipFile(selected,'w',ZIP_DEFLATED) as dest:
            for name in source.namelist():
                data=source.read(name)
                if name=='fabric.mod.json':
                    value=json.loads(data);value['entrypoints']['fabric-gametest']=['dev.aivillages.e2e.paralleld.'+entry]
                    if phase=='baseline':value['mixins']=[]
                    data=json.dumps(value,separators=(',',':')).encode()+b'\n'
                dest.writestr(name,data)
        shutil.copy2(selected,target/'selected-fixture.jar')
        for p in (sdk/'fabric-api.jar',sdk/'gametest.jar',phase_release):shutil.copy2(p,mods/p.name)
        java=os.environ.get('JAVA_HOME','');java=str(Path(java)/'bin/java') if java else 'java'
        command=[java,*(['-Xlog:class+init=info:file='+str((target/'class-init.log').resolve())]if phase.startswith('courier-crash')else[]),'-Xms256M','-Xmx1100M','-Dfabric-api.gametest','-Dfabric-api.gametest.report-file='+str(report.resolve()),
                 '-Dcognitivecraft.parallelD.fixtureWorld='+WORLD,'-Dcognitivecraft.parallelD.phase='+phase,
                 '-Dcognitivecraft.parallelD.shared='+str((root/'metadata').resolve()),'-Dcognitivecraft.parallelD.evidence='+str(evidence.resolve()),
                 '-cp',os.pathsep.join(map(str,runtime)),'net.fabricmc.loader.impl.launch.knot.KnotServer','nogui']
        row={'phase':phase,'entrypoint':'dev.aivillages.e2e.paralleld.'+entry,'case':case,'world_path':str(game.relative_to(root)),
             'selected_fixture_sha256':sha(selected),'release_sha256':sha(mods/phase_release.name),'command':command,'exit':None,'duration_seconds':None}
        metadata['phases'].append(row);write(root/'identity.json',metadata)
        start=time.monotonic()
        with (target/'stdout.log').open('wb') as log:
            try:
                if phase=='courier-crash-write':
                    process=subprocess.Popen(command,cwd=game,stdout=log,stderr=subprocess.STDOUT);end=time.monotonic()+500;armed=evidence/'kill-ready.json'
                    while process.poll()is None and time.monotonic()<end:
                        if armed.exists() and 'LIVE_IN_FLIGHT'in (target/'stdout.log').read_text(errors='replace'):
                            row['termination']={'kind':'SIGKILL','sentinel_sha256':sha(armed),'boundary':'AFTER_NATIVE_LIVE_WITHDRAW_BEFORE_SAVE'};process.kill();break
                        time.sleep(.025)
                    if process.poll()is None and time.monotonic()>=end:process.kill();row['termination']={'kind':'TIMEOUT'}
                    row['exit']=process.wait(timeout=10)
                else:
                    result=subprocess.run(command,cwd=game,stdout=log,stderr=subprocess.STDOUT,timeout=500);row['exit']=result.returncode
            except subprocess.TimeoutExpired:row['exit']='TIMEOUT'
            log.flush();os.fsync(log.fileno())
        if phase=='come':
            from mobility_frames import collect
            collect(target/'stdout.log',evidence)
        row['duration_seconds']=time.monotonic()-start
        # Preserve saved world bytes at the process boundary, before the second JVM opens them.
        saved=[]
        if phase.startswith('reload'):
            regions=list(game.glob('**/dimensions/minecraft/overworld/region/r.0.0.mca'))
            require(len(regions)==1,'witnessed reload region missing/ambiguous')
            region=regions[0];data=region.read_bytes();offset=(8+8*32)*4;location=data[offset:offset+4]
            sector=int.from_bytes(location[:3],'big');count=location[3];require(sector>=2 and count>0,'saved chunk not allocated')
            chunk=data[sector*4096:(sector+count)*4096];length=int.from_bytes(chunk[:4],'big')
            require(1<length<=len(chunk)-4 and chunk[4]==2,'unsupported saved native compression')
            dest=target/'saved-chunk.json'
            write(dest,{'chunk_x':8,'chunk_z':8,'native_region_path':str(region.relative_to(game)),
                        'region_sha256':sha(region),'region_bytes':len(data),'location_hex':location.hex(),
                        'native_chunk_base64':base64.b64encode(chunk[:length+4]).decode(),
                        'native_chunk_sha256':__import__('hashlib').sha256(chunk[:length+4]).hexdigest()})
            saved.append({'path':str(dest.relative_to(root)),'sha256':sha(dest),'bytes':dest.stat().st_size})
        if phase.startswith('courier-crash'):
            for kind,x,z,label in [('region',8,8,'source'),('region',9,8,'destination'),('entities',8,8,'carrier')]:
                regions=list(game.glob('**/dimensions/minecraft/overworld/'+kind+'/r.0.0.mca'));require(len(regions)==1,'courier saved participant region missing '+label);region=regions[0];data=region.read_bytes();entry=data[((x%32)+(z%32)*32)*4:((x%32)+(z%32)*32)*4+4];sector=int.from_bytes(entry[:3],'big');count=entry[3];require(sector>=2 and count>0,'courier participant chunk not allocated '+label);chunk=data[sector*4096:(sector+count)*4096];length=int.from_bytes(chunk[:4],'big');require(1<length<=len(chunk)-4 and chunk[4]==2,'courier saved compression');dest=target/('saved-'+label+'.json');write(dest,{'chunk_x':x,'chunk_z':z,'native_region_path':str(region.relative_to(game)),'region_sha256':sha(region),'native_chunk_base64':base64.b64encode(chunk[:length+4]).decode(),'native_chunk_sha256':__import__('hashlib').sha256(chunk[:length+4]).hexdigest()});saved.append({'path':str(dest.relative_to(root)),'sha256':sha(dest),'bytes':dest.stat().st_size})
        row['saved_world_files']=saved;write(root/'identity.json',metadata)
        require(sha(release)==metadata['release_sha256'],'native phase changed release')
        print('NATIVE_PROCESS',phase,'exit',row['exit'],flush=True)
        if row['exit']!=0 and not (phase=='courier-crash-write'and row['exit']==-9 and row.get('termination',{}).get('kind')=='SIGKILL'):break
    # Mutable world includes caches/session locks, not needed for independent observations; saved region bytes above are retained.
    for cleanup_attempt in range(3):
        try:shutil.rmtree(root/'worlds');break
        except OSError as error:
            if error.errno!=errno.ENOTEMPTY or cleanup_attempt==2:raise
            time.sleep(.2)
    return metadata
