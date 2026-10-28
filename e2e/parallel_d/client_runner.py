#!/usr/bin/env python3
"""Raw D05 two-real-client collector. Reuses established Docker/SDK/launch/driver; never issues pass flags."""
import argparse,json,os,shutil,subprocess,time,uuid
from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED
from common import *

WORLD='d0500000-0000-4000-8000-000000000001';PLAYERS=('PlayerD_A','PlayerD_B');POSITIONS={'single':[6,201,8],'double':[10,201,8],'barrel':[14,201,8]};BEFORE={'single':96,'double':97,'barrel':63};AFTER={'single':112,'double':66,'barrel':64}
def lines(path):
    if not path.exists():return []
    data=path.read_text();return [json.loads(s)for s in data.splitlines()if s]if data.endswith('\n')else [json.loads(s)for s in data.rsplit('\n',1)[0].splitlines()if s]
class DStorageObservationClient:
    def __init__(self,root,env):self.root=root;self.env=env;self.requests=0;self.frames=[];self.screenshots=[];self.deadline=time.monotonic()+registry("D05")["adapter_limits"]["client_total_timeout_seconds"]
    def compose(self,*args,check=True):return subprocess.run(['docker','compose','-f',str(HERE/'compose.yml'),*args],env=self.env,capture_output=True,text=True,check=check,timeout=120)
    def wait(self,condition,description,timeout=120):
        end=min(time.monotonic()+timeout,self.deadline)
        while time.monotonic()<end:
            value=condition()
            if value:return value
            time.sleep(.15)
        raise TimeoutError(description)
    def request(self,player,action,**data):
        box=self.root/'clients'/player;box.mkdir(parents=True,exist_ok=True);self.requests+=1;nonce='d05_'+str(self.requests);value={'id':nonce,'action':action,**data};write(box/'request.tmp',value);(box/'request.tmp').replace(box/'request.json')
        def response():
            p=box/'response.json'
            if not p.exists():return None
            try:r=read(p)
            except json.JSONDecodeError:return None
            return r['result']if r.get('id')==nonce else None
        result=self.wait(response,'native client response '+player+' '+action)
        require('error'not in result,'native client rejected action: '+str(result));return result
    def console(self,commands):
        box=self.root/'server';nonce=uuid.uuid4().hex;write(box/'control.tmp',{'id':nonce,'commands':commands});(box/'control.tmp').replace(box/'control.json')
        self.wait(lambda:((p:=box/'control-ack.json').exists() and read(p).get('id')==nonce),'server console handoff')
    def view(self,player,target):
        nonce=uuid.uuid4().hex;self.request(player,'command',text='d-storage view '+target+' '+nonce)
        def feedback():
            rows=[r for r in lines(self.root/'clients'/player/'events.jsonl')if r['kind']=='feedback'and r['data']['text'].startswith('D05_REPLY '+nonce+' ')]
            return rows[-1]if rows else None
        event=self.wait(feedback,'actual private observer response');payload=event['data']['text'].split(' ',2)[2];return {'event':event,'payload':payload}
    def capture(self,player,target,phase,quantity,slots,observer=None):
        def snapshot():
            s=self.request(player,'snapshot');rows=[r for r in s.get('slots',[])if not r['playerInventory']]
            return s if s.get('play')and not s.get('singleplayer')and any(k in s.get('screen','')for k in ('Container','Chest','Barrel'))and len(rows)==slots and sum(r['count']for r in rows if r['item']=='minecraft:oak_planks')==quantity else None
        state=self.wait(snapshot,'actual '+player+' '+phase+' '+target+' container slots');view=observer if observer is not None else self.view(player,target);row={'phase':phase,'target':target,'player':player,'state':state,'observer':view};self.frames.append(row);write(self.root/'menu-observations.json',self.frames);return state
    def open(self,player,target):
        self.request(player,'close-menu');p=POSITIONS[target];self.console(['tp '+player+f' {p[0]+.5} 201 {p[2]-1.5} 0 0']);self.wait(lambda:((s:=self.request(player,'snapshot')).get('play') and sum((s['pos'][i]-[p[0]+.5,201,p[2]-1.5][i])**2 for i in range(3))<2),'actual server-to-client position synchronization');self.request(player,'look',pos=p);self.request(player,'open',pos=p)
    def run(self):
        def scene_ready():
            error=self.root/'server/d05-error.json'
            if error.exists():raise RuntimeError('Native fixture setup failed: '+str(read(error)))
            return (self.root/'server/d05-ready.json').exists()
        self.wait(scene_ready,'actual registered client scene',180)
        for player in PLAYERS:
            self.wait(lambda:bool(lines(self.root/'clients'/player/'events.jsonl')),'client driver startup',180);self.request(player,'connect',address='127.0.0.1:25661');self.wait(lambda:any(r['kind']=='play'for r in lines(self.root/'clients'/player/'events.jsonl')),'authenticated multiplayer play '+player,180)
        for target in ('single','double','barrel'):
            for player in PLAYERS:
                self.request(player,'close-menu');observer=self.view(player,target);self.open(player,target);self.capture(player,target,'BEFORE',BEFORE[target],54 if target=='double'else 27,observer)
        self.console(['d-storage mutate']);self.wait(lambda:(self.root/'server/d05-mutated.json').exists(),'actual native content mutation')
        for target in ('barrel','single','double'):
            for player in PLAYERS:
                if target!='barrel':self.open(player,target)
                self.capture(player,target,'AFTER',AFTER[target],54 if target=='double'else 27)
                if target=='single':
                    self.open(player,target);self.wait(lambda:any(k in self.request(player,'snapshot').get('screen','')for k in ('Container','Chest','Barrel')),'native container screenshot ready');self.screenshots.append({'player':player,'target':target,'result':self.request(player,'screenshot',name='d05-single-after')});write(self.root/'screenshots.json',self.screenshots)
        privacy=[]
        for player,target in [('PlayerD_A','private'),('PlayerD_B','private'),('PlayerD_B','missing'),('PlayerD_A','deposit')]:privacy.append({'player':player,'target':target,**self.view(player,target)})
        write(self.root/'privacy-observations.json',privacy)
        for player in PLAYERS:self.request(player,'close-menu')
        self.console(['d-storage split']);self.wait(lambda:(self.root/'server/d05-split.json').exists(),'actual double-chest split')
        for player in PLAYERS:self.open(player,'double');self.capture(player,'double','SPLIT',1,27)
        for player in PLAYERS:self.request(player,'close-menu');self.request(player,'disconnect');self.request(player,'exit')

def run(root,stage="D05"):
    require(stage in ("D05","D06"),"unimplemented native client campaign")
    world=WORLD if stage=="D05"else "d0600000-0000-4000-8000-000000000001"
    entry="DStorageObservationServer"if stage=="D05"else "DComeServer"
    root=root.resolve();require(not root.exists(),'do not overwrite a client campaign');root.mkdir(parents=True);identity=source_identity();fixture=REPO/'build/parallel-d-native/parallel-d-fixtures.jar';selected=root/'selected-client-fixture.jar';
    with ZipFile(fixture)as source,ZipFile(selected,'w',ZIP_DEFLATED)as target:
        for name in source.namelist():
            data=source.read(name)
            if name=='fabric.mod.json':
                d=json.loads(data);d['id']='cognitivecraft_parallel_d_clients';d['entrypoints']={'main':['dev.aivillages.e2e.paralleld.'+entry]};data=json.dumps(d,separators=(',',':')).encode()+b'\n'
            target.writestr(name,data)
    for player in PLAYERS:(root/'clients'/player).mkdir(parents=True)
    mods=root/'server/game/mods';mods.mkdir(parents=True);shutil.copy2(selected,mods/'parallel-d-client-fixture.jar');shutil.copy2(REPO/'build/server-sdk/gametest.jar',mods/'gametest.jar')
    properties='online-mode=false\nenforce-secure-profile=false\nwhite-list=false\nenforce-whitelist=false\nserver-ip=127.0.0.1\nserver-port=25661\nspawn-protection=0\nview-distance=4\nsimulation-distance=4\nlevel-type=minecraft:flat\ngenerate-structures=false\ndifficulty=peaceful\nsync-chunk-writes=true\nmax-players=4\nlevel-seed=20261027\n'
    (root/'server/game/server.properties').write_text(properties)
    env=os.environ.copy();env['CC_D_RUN_ROOT']=str(root);env['CC_D_SERVER_TEST_JAVA']='-Dcognitivecraft.parallelD.fixtureWorld='+world
    if stage=='D06':
        from come_client import DComeClient
        h=DComeClient(root,env);write(root/'observation-area.json',{'minX':0,'footY':201,'minZ':0,'maxX':19,'maxZ':24})
    else:h=DStorageObservationClient(root,env)
    plan={'identity':identity,'world':world,'case':stage+'-CL01','players':list(PLAYERS),'seed':20261027,'render_backend':'ESTABLISHED_SDL_OFFSCREEN','fixture_sha256':sha(fixture),'selected_fixture_sha256':sha(selected),'driver_sha256':sha(REPO/'build/e2e-driver/client.jar'),'observer_sha256':sha(REPO/'build/e2e-driver/observer.jar'),'phase':'COLLECTING_RAW','integration_root':None,'save_proof':None};write(root/'identity.json',plan)
    try:
        result=h.compose('config');(root/'resolved-compose.yml').write_text(result.stdout);h.compose('up','-d','minecraft','client-a','client-b');(root/'containers.jsonl').write_text(h.compose('ps','-a','--format','json').stdout);h.run();plan['phase']='COLLECTED_RAW'
    finally:
        result=h.compose('logs','--no-color',check=False);(root/'compose.log').write_text(result.stdout+result.stderr);h.compose('down','--remove-orphans',check=False)
        props=root/'server/game/server.properties'
        if props.exists():
            value=dict(line.split('=',1)for line in props.read_text().splitlines()if line and not line.startswith('#')and '='in line);write(root/'server-properties.json',{'sha256':sha(props),'level-seed':value.get('level-seed'),'server-port':value.get('server-port'),'level-type':value.get('level-type')})
        if stage=='D06' and (root/'server/stdout.log').exists():
            from mobility_frames import collect
            collect(root/'server/stdout.log',root/'server/d06')
        for directory in [root/'server/game',*(root/'clients'/p/'game'for p in PLAYERS)]:
            if directory.exists():shutil.rmtree(directory)
        write(root/'identity.json',plan)
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--stage',default='D05');a=p.parse_args();run(a.root,a.stage)
