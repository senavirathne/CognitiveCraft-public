"""Actual two-client safety campaign; semantic qualification is independent of collection."""
from pathlib import Path
import json
from common import HERE,JAR,ROOT,check,digest,read
from client_conversation import run as collect_clients,events,native_player,check_guards
from oracle import SCENARIOS,SCENARIOS_SHA256,freeze,check_output,check_provider,check_messages

def run(output,fixture):return collect_clients(output,fixture,safety_campaign=SCENARIOS)

def verify(output,registry=None):
    output=Path(output);schedule=freeze();receipt=read(output/'process.json')
    check(receipt['exit']==0 and receipt['tier']=='COMPONENT_CLIENT' and receipt['facts_tier']=='SYNTHETIC_CONTRACT','completed actual C07 client transport required')
    check(receipt['campaign_sha256']==SCENARIOS_SHA256 and receipt['jar_sha256']==digest(JAR),'candidate/safety schedule identity mismatch')
    check(receipt['fixture_sha256']==digest(output.parent/'native/chat-fixture.jar'),'safety fixture/compiler identity mismatch')
    check(receipt['launcher_sha256']==digest(ROOT/'e2e/launch.py') and receipt['owned_launcher_sha256']==digest(HERE/'native/launch_conversation.py'),'existing native launcher changed')
    check(receipt['runtime_image_id']==read(output.parent/'run.json')['container_image_id'] if (output.parent/'run.json').is_file() else bool(receipt['runtime_image_id']),'native runtime identity absent/changed')
    check(receipt['ended_ns']>receipt['started_ns'] and len(receipt['processes'])==3 and all(p['exit']==0 for p in receipt['processes']),'complete server/two-client process intervals/exits required')
    processes={}
    for name in ['server','clients/CPlayerA','clients/CPlayerB']:
        p=read(output/'raw'/name/'process.json');ex=read(output/'raw'/name/'exit.json');processes[name]=p
        check(p['pid']==ex['pid'] and ex['exit']==0 and '25.' in p['javaVersion'] and p['releaseSha256']==receipt['jar_sha256'],'actual Java 25 Minecraft lifecycle/release changed')
        mods={m['name']:m['sha256'] for m in p['mods']}
        check(mods['parallel-c-conversation-fixture.jar']==receipt['fixture_sha256'] and mods['ai-villages-0.1.0.jar']==receipt['jar_sha256'],'wrong actual native mod bytes')
    check(len({p['pid'] for p in processes.values()})==3,'three distinct actual native JVMs required')
    check(digest(output/'requested-server.properties')==receipt['requested_server_properties_sha256'] and receipt['world_seed']==20261027,'declared seeded world changed')
    properties=output/'raw/server/server.properties';check(digest(properties)==receipt['server_properties_sha256'],'actual server properties changed')
    values=dict(line.split('=',1) for line in properties.read_text().splitlines() if line and not line.startswith('#'))
    check(values['level-seed']=='20261027' and values['level-name']=='C07Component' and values['server-ip']=='127.0.0.1' and values['server-port']=='25651','isolated C07 world configuration required')
    server=events(output/'raw/server/c-conversation/events.jsonl');check_guards(server)
    observations=events(output/'raw/server/observations.jsonl');check(observations,'existing native server observations missing')
    for observation in observations:
        for player in observation.get('knownPlayers',{}).values():check(not player.get('jobs') and not player.get('runs'),'conversation admitted a native Job/run')
    expected=schedule['cases'];frames=receipt['frames'];check_messages(frames,expected)
    requests=[e for e in server if e['kind']=='request'];sent=[e for e in server if e['kind']=='sent'];semantic=[e for e in server if e['kind']=='safety-observation']
    check(len(requests)==len(sent)==len(semantic)==60,'missing/duplicate native server safety observation')
    clients={p:events(output/'raw/clients'/p/'c-conversation/events.jsonl') for p in schedule['clients']}
    received=[(p,e) for p,rows in clients.items() for e in rows if e['kind']=='payload-received']
    transmitted=[(p,e) for p,rows in clients.items() for e in rows if e['kind']=='payload-sent']
    check(len(received)==len(transmitted)==60 and not any(e['kind']=='client-error' for rows in clients.values() for e in rows),'unsolicited/missing payload or native client failure')
    for player,rows in clients.items():
        base=events(output/'raw/clients'/player/'events.jsonl')
        check(sum(e['kind']=='play' and e['data']['uuid']==native_player(player) for e in base)==1,'actual authenticated local test client play absent')
        for event in rows:
            if event['kind']=='payload-received':
                want=next((w for w in expected if w['id']==event['data']['payload']['case_id']),None)
                check(want is not None and want['player']==player and event['data']['native_player']==native_player(player),'cross-player/undeclared safety delivery')
    roots={};sessions={}
    for want,frame,request_event,sent_event,observation_event in zip(expected,frames,requests,sent,semantic):
        native=native_player(want['player']);request=frame['request'];packet=frame['received']['data']['payload'];observation=observation_event['data']
        check(frame['id']==want['id'] and frame['player']==want['player'] and frame['operation']=='SAFETY','frozen native message order/identity changed')
        check(0<frame['ended_ns']-frame['started_ns']<=30_000_000_000,'transport message deadline exceeded')
        check(frame['received'] in clients[want['player']] and frame['rendered'] in clients[want['player']],'runner substituted for actual client receive/render')
        sent_request=dict(request,schema='cc.c.client-input/1')
        check(request_event['data']['native_player']==native and request_event['data']['input']==sent_request,'native server identity/request differs')
        check(any(p==want['player'] and e['data']['payload']==sent_request for p,e in transmitted),'actual ClientPlayNetworking send absent')
        check(sent_event['data']['native_player']==native and sent_event['data']['payload']==packet and observation['output']==packet,'native server/client wire reconciliation mismatch')
        check_output(packet,want,native,request);check_provider(observation,want)
        check(observation['native_player']==native and observation['citizen']==want['citizen'] and observation['declared_facts']==want['facts'],'swapped fixture principal/shortage')
        render=frame['rendered']['data'];check(render['message_id']==request['id'] and render['text']==packet['text'] and render['accessible_text']==packet['accessible_text'] and render['native_player']==native,'actual native UI differs from authorized output')
        key=(native,want['citizen'])
        if packet['root_id'] is not None:
            check(key not in roots or roots[key]==(packet['root_id'],packet['question_id']),'original component output lineage changed');roots[key]=(packet['root_id'],packet['question_id'])
        check(native not in sessions or sessions[native]==packet['session'],'native conversation session changed without rejoin');sessions[native]=packet['session']
        if want['expected_class']=='FORGET':check(not observation['context_after'] and observation['association_retained'],'forget erased assistance or retained caller text')
        if want['expected_reason']=='STALE_GENERATION':check(request['reply_generation']==0 and observation['question_matches_current'] is False and packet['candidate'] is None,'old generation obtained a candidate')
        if 'question token' in want['text'].lower():
            check(request['reply_question_id']!=packet['question_id'] and observation['question_matches_current'] is False and packet['candidate'] is None,'cross-player question token accepted')
    for path in receipt['screenshots']:check((output/'raw'/path).read_bytes().startswith(b'\x89PNG\r\n\x1a\n'),'actual safety UI screenshot absent')
    check(len(receipt['screenshots'])==2,'two-client safety screenshot evidence required')
    if registry is not None:check(registry['language_cases']==[dict(id=w['id'],seed=w['seed'],expected=w['expected_outcome']) for w in expected],'language case registry changed')
    # One mandatory suite, sixty individual messages. Expected refusal is not fulfillment.
    return dict(status='PASS',planned=60,executed=60,passed=sum(w['expected_outcome']=='PASS' for w in expected),refused_expected=sum(w['expected_outcome']=='REFUSED_EXPECTED' for w in expected),failed=0,inconclusive=0,
                cases=[dict(id=w['id'],seed=w['seed'],outcome=w['expected_outcome'],tier='COMPONENT_CLIENT') for w in expected],suite_case=dict(id='C07-CL01',seed=20261027,outcome='PASS',tier='COMPONENT_CLIENT',messages=60),
                native_clients=2,native_server=1,world_operation_calls=0,job_calls=0,facts_tier='SYNTHETIC_CONTRACT',production_enabled=False,activity_gameplay_executed=0)
