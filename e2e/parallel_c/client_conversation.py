"""Collect and independently verify two actual connected-client C06 campaigns."""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import time
import uuid

from common import HERE,JAR,ROOT,EvidenceError,check,digest,identity,read,write

CAMPAIGN=HERE/'client-conversation-campaign.json'
CAMPAIGN_SHA256='59a7533764b7dab23a7c029544fdb2db7330cc0651296f737e92db19f7f422b8'
UUID_PATTERN=re.compile(r'(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}')

def native_player(name):
    return str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+name).encode()).digest(),version=3))

def events(path):
    if not Path(path).is_file():return []
    # Only complete append-only records; a concurrent trailing write is retried by the collector.
    raw=Path(path).read_text();lines=raw.splitlines()
    if raw and not raw.endswith('\n'):lines=lines[:-1]
    return [json.loads(line) for line in lines if line]

def wait_for(predicate,label,seconds=30,processes=()):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        for p in processes:check(p.poll() is None,'native JVM launcher exited while waiting: '+label)
        value=predicate()
        if value:return value
        time.sleep(.1)
    raise EvidenceError('native wait timed out: '+label)

def run(output,fixture):
    output=Path(output);output.mkdir(parents=True,exist_ok=False)
    context=identity();nonce=output.parent.name
    world=ROOT/'build/parallel-c-worlds'/('C06-'+nonce)
    check(not world.exists(),'refusing to reuse a mutable C/peer world');world.mkdir(parents=True)
    campaign=read(CAMPAIGN);check(digest(CAMPAIGN)==CAMPAIGN_SHA256,'frozen client schedule changed')
    environment=dict(os.environ,COGNITIVECRAFT_C_FIXTURE_ALLOWED='chat-only',COGNITIVECRAFT_C_FIXTURE_JAR=str(Path(fixture).resolve()),
                     COGNITIVECRAFT_C_CAMPAIGN=str(CAMPAIGN),LIBGL_ALWAYS_SOFTWARE='1',SDL_VIDEO_DRIVER='offscreen')
    receipt=dict(**context,exit=None,tier='COMPONENT_CLIENT',facts_tier='SYNTHETIC_CONTRACT',campaign_sha256=digest(CAMPAIGN),
                 jar_sha256=digest(JAR),fixture_sha256=digest(fixture),launcher_sha256=digest(ROOT/'e2e/launch.py'),
                 owned_launcher_sha256=digest(HERE/'native/launch_conversation.py'),runtime_image_id=os.environ.get('COGNITIVECRAFT_C_IMAGE_ID'),
                 mutable_world_root=str(world),started_ns=time.time_ns(),processes=[],frames=[],screenshots=[])
    game=world/'server/game';game.mkdir(parents=True)
    properties='online-mode=false\nenforce-secure-profile=false\nwhite-list=false\nenforce-whitelist=false\nserver-ip=127.0.0.1\nserver-port=25651\nspawn-protection=0\nview-distance=4\nsimulation-distance=4\nlevel-type=minecraft:flat\ngenerate-structures=false\ndifficulty=peaceful\nsync-chunk-writes=true\nmax-players=4\nlevel-name=C06Component\nlevel-seed=20261027\n'
    (game/'server.properties').write_text(properties)
    receipt['world_seed']=20261027;receipt['requested_server_properties_sha256']=digest(game/'server.properties')
    (output/'requested-server.properties').write_text(properties)
    children=[];logs=[];views={}
    def uid(name):return str(uuid.uuid5(uuid.NAMESPACE_URL,'c06:'+nonce+':'+name))
    def remember():write(output/'process.json',receipt)
    def launch(kind,player=None):
        command=['python3',str(HERE/'native/launch_conversation.py'),kind,'--root',str(world),'--port','25651']
        if player:command+=['--player',player]
        log=(output/('launcher-'+(player or 'server')+'.log')).open('w');logs.append(log)
        p=subprocess.Popen(command,cwd=ROOT,env=environment,stdout=log,stderr=subprocess.STDOUT);children.append(p)
        receipt['processes'].append(dict(kind=kind,player=player,pid=p.pid,command=command));remember();return p
    def issue(path,value):
        path.parent.mkdir(parents=True,exist_ok=True);temporary=path.with_suffix('.tmp');write(temporary,value);temporary.replace(path)
        with (output/'issued-requests.jsonl').open('a') as log:log.write(json.dumps(dict(path=str(path.relative_to(world)),time_ns=time.time_ns(),request=value))+'\n')
    def driver(player,action,**data):
        token=uid(player+':'+action+':'+str(time.monotonic_ns()));box=world/'clients'/player
        issue(box/'request.json',dict(id=token,action=action,**data))
        return wait_for(lambda:(r if (r:=read(box/'response.json') if (box/'response.json').is_file() else None) and r['id']==token else None),player+' '+action,45,children)
    def latest(player,kind,case):
        return next((e for e in reversed(events(world/'clients'/player/'c-conversation/events.jsonl'))
                     if e['kind']==kind and (e['data'].get('payload',e['data'])).get('case_id')==case),None)
    try:
        server=launch('server')
        wait_for(lambda:next((e for e in events(world/'server/c-conversation/events.jsonl') if e['kind']=='server-start'),None),'server startup',180,children)
        wait_for(lambda:next((e for e in events(world/'server/c-conversation/events.jsonl') if e['kind']=='guards-armed'),None),'native mutation guards armed',60,children)
        for player in campaign['clients']:launch('client',player)
        for player in campaign['clients']:
            wait_for(lambda p=player:(world/'clients'/p/'c-conversation/events.jsonl').is_file(),player+' startup',180,children)
            driver(player,'connect',address='127.0.0.1:25651')
            wait_for(lambda p=player:any(e['kind']=='play' for e in events(world/'clients'/p/'events.jsonl')),player+' actual play',180,children)
        for case in campaign['cases']:
            for step in case['steps']:
                name,player,operation=step['id'],step['player'],step['operation'];start=time.time_ns();frame=dict(id=name,case_id=case['id'],player=player,operation=operation,started_ns=start)
                if operation=='DISCONNECT':
                    before=sum(e['kind']=='native-disconnect' and e['data']['player']==native_player(player) for e in events(world/'server/c-conversation/events.jsonl'))
                    frame['driver']=driver(player,'disconnect')
                    wait_for(lambda:sum(e['kind']=='native-disconnect' and e['data']['player']==native_player(player) for e in events(world/'server/c-conversation/events.jsonl'))>before,'server observed disconnect',30,children)
                else:
                    if operation=='REJOIN':
                        before=sum(e['kind']=='play' for e in events(world/'clients'/player/'events.jsonl'))
                        frame['driver']=driver(player,'connect',address='127.0.0.1:25651')
                        wait_for(lambda:sum(e['kind']=='play' for e in events(world/'clients'/player/'events.jsonl'))>before,'client actual rejoin',120,children)
                        request=dict(id=uid(name),operation='RESUME',case_id=name)
                    elif operation=='MESSAGE':request=dict(id=uid(name),operation='MESSAGE',case_id=name,text=step['text'])
                    else:
                        v=views[step['view']];request=dict(id=uid(name),operation=operation,case_id=name,event_id=uid(step['event']),message_id=v['message_id'],generation=v['generation'],action=step['action'])
                    issue(world/'clients'/player/'c-conversation/request.json',request);frame['request']=request
                    got=wait_for(lambda:latest(player,'payload-received',name),player+' received '+name,30,children);frame['received']=got
                    if operation in ('MESSAGE','REJOIN'):
                        views[name]=got['data']['payload']['view']
                        frame['rendered']=wait_for(lambda:latest(player,'native-ui-rendered',name),player+' native UI '+name,30,children)
                        if name in ('A-wait','B-wait'):
                            screenshot=driver(player,'screenshot',name='C06-'+name)
                            frame['screenshot']=screenshot;receipt['screenshots'].append(screenshot['result']['path'])
                frame['ended_ns']=time.time_ns();receipt['frames'].append(frame);remember()
        # Keep both real receivers online long enough to observe unsolicited/cross-player delivery.
        end=time.monotonic()+2
        while time.monotonic()<end:time.sleep(.1)
        receipt['exit']=0
    except (OSError,ValueError,KeyError,subprocess.SubprocessError) as failure:
        receipt.update(exit=2,error=str(failure))
    finally:
        for player in campaign['clients']:
            try:
                if any(p.poll() is None for p in children[1:]):issue(world/'clients'/player/'request.json',dict(id=uid(player+':exit'),action='exit'))
            except OSError:pass
        for p in reversed(children[1:]):
            try:p.wait(timeout=20)
            except subprocess.TimeoutExpired:p.terminate()
        if children and children[0].poll() is None:children[0].send_signal(signal.SIGTERM)
        for p in children:
            try:p.wait(timeout=35)
            except subprocess.TimeoutExpired:p.kill();p.wait(timeout=10)
        for log in logs:log.close()
        for name in ['server','clients/CPlayerA','clients/CPlayerB']:
            for path in (world/name).rglob('*'):
                if path.is_file() and 'game' not in path.relative_to(world/name).parts:
                    target=output/'raw'/path.relative_to(world);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,target)
        (output/'raw/server').mkdir(parents=True,exist_ok=True)
        shutil.copy2(game/'server.properties',output/'raw/server/server.properties')
        receipt['server_properties_sha256']=digest(output/'raw/server/server.properties')
        for item,p in zip(receipt['processes'],children):item['exit']=p.returncode
        receipt['ended_ns']=time.time_ns();remember()
    return receipt

def check_view(view,want,native):
    check(view['schema']=='cc.c.presentation/1','wrong presentation schema')
    check(view['audience']['player']==view['scope']['principal']==native,'cross-principal presentation')
    check(view['status']==want['expected_status'] and view['text']==want['expected_text'],'wrong observed status/text: '+want['id'])
    check(view['accessible_text'].startswith(view['text']) and all(c['label'] in view['accessible_text'] for c in view['choices']),'missing accessible status/choices')
    check(not UUID_PATTERN.search(view['text']) and not UUID_PATTERN.search(view['accessible_text']),'raw UUID exposed without details request')
    check(view['language']=='en' and view['locale_key']=='interaction.'+view['status'].lower(),'locale key/fallback changed')
    check('Private secret chest' not in json.dumps(view),'private storage existence disclosed')
    if view['status']=='DENIED':check(view['activity'] is view['question_id'] is None and not view['choices'],'redacted denial carries private correlation')
    return True

def check_guards(rows):
    starts=[e for e in rows if e['kind']=='server-start'];stops=[e for e in rows if e['kind']=='server-stop']
    check(len(starts)==len(stops)==1,'complete native server lifecycle required')
    armed=[i for i,e in enumerate(rows) if e['kind']=='guards-armed']
    check(len(armed)==1,'one actual native guards-armed frame required')
    arm=armed[0];required={'JobLifecycleStore','SurvivalGateway'}
    check(set(rows[arm]['data']['active_hooks'])==required,'actual native mutation hooks not loaded')
    ready={e['data']['hook'] for e in rows[:arm] if e['kind']=='guard-ready'}
    check(ready==required,'both native constructor hooks must precede guard arming')
    for i,e in enumerate(rows):
        if e['kind']=='request':check(i>arm and set(e['data']['guards']['active_hooks'])==required,'transport request preceded native guard arming')
    check(arm<len(rows)-1 and rows[-1]['kind']=='server-stop','guard window must extend through native shutdown')
    check(not any(e['kind'] in ('job-call','world-operation-call','rejected-input') for e in rows),'native effect/Job call or unexpected transport rejection')
    for e in rows:
        guards=e['data'].get('guards',e['data'] if e['kind']=='server-stop' else None)
        if guards is not None:check(guards['job_calls']==guards['world_operation_calls']==0,'conversation attempted native Job/world operation')

def verify(output,registry=None):
    output=Path(output);receipt=read(output/'process.json');campaign=read(CAMPAIGN)
    check(receipt['exit']==0 and receipt['tier']=='COMPONENT_CLIENT' and receipt['facts_tier']=='SYNTHETIC_CONTRACT','no completed actual C client transport')
    check(receipt['campaign_sha256']==digest(CAMPAIGN)==CAMPAIGN_SHA256,'frozen client schedule changed')
    check(receipt['jar_sha256']==digest(JAR),'native client campaign used another release')
    check(digest(output/'requested-server.properties')==receipt['requested_server_properties_sha256'],'declared native world configuration changed')
    properties=output/'raw/server/server.properties';check(receipt['world_seed']==20261027 and digest(properties)==receipt['server_properties_sha256'],'native world fixture configuration changed')
    values=dict(line.split('=',1) for line in properties.read_text().splitlines() if line and not line.startswith('#'))
    check(values['level-seed']=='20261027' and values['level-name']=='C06Component' and values['server-ip']=='127.0.0.1' and values['server-port']=='25651','isolated seeded native fixture required')
    check(receipt['fixture_sha256']==digest(output.parent/'native/chat-fixture.jar'),'native fixture differs from exact compiler gate')
    check(receipt['launcher_sha256']==digest(ROOT/'e2e/launch.py') and receipt['owned_launcher_sha256']==digest(HERE/'native/launch_conversation.py'),'existing launcher changed')
    check(len(receipt['processes'])==3 and all(p['exit']==0 for p in receipt['processes']),'server and two real client launcher exits required')
    roots={}
    for name in ['server','clients/CPlayerA','clients/CPlayerB']:
        process=read(output/'raw'/name/'process.json');exit_record=read(output/'raw'/name/'exit.json');roots[name]=process
        check(exit_record['exit']==0 and exit_record['pid']==process['pid'],'native JVM did not exit cleanly')
        check(process['releaseSha256']==receipt['jar_sha256'] and '25.' in process['javaVersion'],'native JVM release/Java mismatch')
        mods={m['name']:m for m in process['mods']};check(mods['parallel-c-conversation-fixture.jar']['sha256']==receipt['fixture_sha256'],'native presentation fixture changed')
        check(mods['ai-villages-0.1.0.jar']['sha256']==receipt['jar_sha256'],'wrong installed native release')
    check(len({p['pid'] for p in roots.values()})==3,'separate real Minecraft JVMs required')
    server=events(output/'raw/server/c-conversation/events.jsonl');check_guards(server)
    observers=events(output/'raw/server/observations.jsonl');check(observers,'existing native observer frames absent')
    for observation in observers:
        for player in observation.get('knownPlayers',{}).values():check(not player.get('jobs') and not player.get('runs'),'native observer found a synthetic Job/run')
    actual=receipt['frames'];expected=[s for c in campaign['cases'] for s in c['steps']]
    check(len(actual)==len(expected)==18 and len({r['id'] for r in actual})==18,'missing/duplicate native client step')
    views={};client_events={}
    for player in campaign['clients']:
        client_events[player]=events(output/'raw/clients'/player/'c-conversation/events.jsonl')
        check(not any(e['kind']=='client-error' for e in client_events[player]),'native client adapter failed')
        base=events(output/'raw/clients'/player/'events.jsonl')
        check(any(e['kind']=='play' and e['data']['uuid']==native_player(player) for e in base),'actual native client play absent')
        for event in client_events[player]:
            if event['kind']=='payload-received':
                payload=event['data']['payload'];check(event['data']['native_player']==native_player(player),'wrong native receiver identity')
                want=next((s for s in expected if s['id']==payload['case_id']),None)
                check(want is not None and want['player']==player,'cross-player or unregistered output')
    deliveries=[e for e in server if e['kind']=='sent']
    received=[(player,e) for player,rows in client_events.items() for e in rows if e['kind']=='payload-received']
    check(len(deliveries)==len(received)==17,'missing, duplicate or unsolicited native payload')
    for player,event in received:
        packet=event['data']['payload'];matches=[s for s in deliveries if s['data']['native_player']==native_player(player) and s['data']['payload']==packet]
        check(len(matches)==1,'native server/client wire reconciliation mismatch')
    for want,row in zip(expected,actual):
        check(all(row[k]==want[k] for k in ('id','player','operation')),'native client schedule/order changed')
        check(0<row['ended_ns']-row['started_ns']<=120_000_000_000,'bounded client step interval missing/exceeded')
        if want['operation']=='DISCONNECT':continue
        got=row['received'];check(got in client_events[want['player']] and got['kind']=='payload-received','runner frame substituted for actual received payload')
        packet=got['data']['payload'];check(packet['case_id']==want['id'] and packet['facts_tier']=='SYNTHETIC_CONTRACT','packet/case/provenance mismatch')
        if want['operation']=='MESSAGE':
            check(packet['kind']=='VIEW','missing native response view');view=packet['view'];check_view(view,want,native_player(want['player']));views[want['id']]=view
            if want['fixture']=='CHAT':check(packet['phrasing_reason']=='OFFLINE','C03 provider fallback path not exercised')
        elif want['operation']=='REJOIN':
            view=packet['view'];before=views[want['view']];check(view['message_id']==before['message_id'] and view['generation']>before['generation'] and view['audience']['session']!=before['audience']['session'],'rejoin lost output identity or callback fence')
            check(view['activity']==before['activity'] and view['question_id']==before['question_id'] and view['text']==before['text'],'rejoin changed root/question/status facts');views[want['id']]=view
            base=events(output/'raw/clients'/want['player']/'events.jsonl');check(sum(e['kind']=='play' for e in base)==2 and any(e['kind']=='disconnect' for e in base),'real native disconnect/rejoin absent')
        else:
            check(packet['kind']=='CALLBACK_ACK' and packet['disposition']==want['expected'],'callback disposition mismatch')
            check((packet['proposal'] is not None)==(want['expected']=='PROPOSED'),'refused/duplicate callback emitted a proposal')
            if packet['proposal'] is not None:check(packet['proposal']['action']==want['action'] and packet['proposal']['message_id']==views[want['view']]['message_id'],'callback action/correlation changed')
            if want['operation']=='CLICK':check(any(e['kind']=='native-button-pressed' and e['data']['case_id']==want['id'] for e in client_events[want['player']]),'native Button callback was not exercised')
        if want['operation'] in ('MESSAGE','REJOIN'):
            render=row['rendered'];view=packet['view'];check(render in client_events[want['player']] and render['kind']=='native-ui-rendered','no actual native Screen render frame')
            check(render['data']['message_id']==view['message_id'] and render['data']['text']==view['text'] and render['data']['accessible_text']==view['accessible_text'],'native rendered status differs from received view')
    for screenshot in receipt['screenshots']:
        p=output/'raw'/screenshot;check(p.is_file() and p.read_bytes().startswith(b'\x89PNG\r\n\x1a\n'),'actual native UI screenshot missing')
    check(len(receipt['screenshots'])==2,'one UI screenshot per real client required')
    cases=[dict(id=c['id'],seed=c['seed'],outcome='PASS',tier='COMPONENT_CLIENT',steps=len(c['steps'])) for c in campaign['cases']]
    if registry is not None:check([(r['id'],r['seed']) for r in registry['client_cases']]==[(r['id'],r['seed']) for r in cases],'native client case registry changed')
    return dict(status='PASS',planned=2,executed=2,passed=2,failed=0,refused_expected=0,inconclusive=0,cases=cases,transport_steps=18,
                native_clients=2,native_server=1,world_operation_calls=0,job_calls=0,tier='COMPONENT_CLIENT',facts_tier='SYNTHETIC_CONTRACT',
                activity_gameplay_executed=0,production_enabled=False)
