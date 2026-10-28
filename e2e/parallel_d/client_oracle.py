"""Independent two-real-client oracle over actual driver packets, menus, server projections and process identities."""
import collections,json,struct,uuid
from zipfile import ZipFile
from common import *
PLAYERS=('PlayerD_A','PlayerD_B');BEFORE={'single':96,'double':97,'barrel':63};AFTER={'single':112,'double':66,'barrel':64}
def offline(name):return str(uuid.UUID(bytes=__import__('hashlib').md5(('OfflinePlayer:'+name).encode()).digest(),version=3))
def lines(path):return [json.loads(s,object_pairs_hook=no_duplicates)for s in path.read_text().splitlines()if s]
def counts(rows):
    result=collections.Counter()
    for r in rows:result[r['item']]+=r['count']
    return dict(+result)
def menu_observations(frames,ready):
    expected=[(phase,target,player)for phase,targets in [('BEFORE',('single','double','barrel')),('AFTER',('barrel','single','double')),('SPLIT',('double',))]for target in targets for player in PLAYERS]
    require([(r['phase'],r['target'],r['player'])for r in frames]==expected,'missing/duplicate real-client menu observations')
    for r in frames:
        phase,target,player=r['phase'],r['target'],r['player'];state=r['state'];require(state['play']is True and state['singleplayer']is False and state['uuid']==offline(player),'simulated/disconnected/swapped client principal')
        require(any(k in state.get('screen','')for k in ('Container','Chest','Barrel')),'native container GUI is not visible');slots=[s for s in state['slots']if not s['playerInventory']];denominator=54 if target=='double'and phase!='SPLIT'else 27;require(len(slots)==denominator,'wrong client native menu slot count')
        expected_count=1 if phase=='SPLIT'else BEFORE[target]if phase=='BEFORE'else AFTER[target];require(counts(slots).get('minecraft:oak_planks',0)==expected_count,'native client contents changed or fabricated')
        payload=r['observer']['payload']
        if phase=='SPLIT':require(payload=='UNAVAILABLE'and 'type=single'in state['blocks']['10,201,8']and 'minecraft:air'in state['blocks']['11,201,8'],'topology update/stale registered pair not synchronized');continue
        require(payload!='UNAVAILABLE','public observer refused');view=json.loads(payload);require(view['world']==ready['world']and view['storage']==ready['ids'][target],'observer identity differs from registered native storage')
        projected=[{'item':s['item']['registryId']if s['item']else'minecraft:air','count':s['count']}for s in view['slots']];require(len(projected)==denominator and counts(projected)==counts(slots),'real client and mod observer stock disagree')
        require(collections.Counter((s['item'],s['count'])for s in projected)==collections.Counter((s['item'],s['count'])for s in slots),'per-slot synchronized counts disagree')
    for player in PLAYERS:
        before=next(r for r in frames if (r['player'],r['phase'],r['target'])==(player,'BEFORE','barrel'));after=next(r for r in frames if (r['player'],r['phase'],r['target'])==(player,'AFTER','barrel'));require(before['state']['menu']==after['state']['menu'],'barrel synchronization reopened menu instead of observing update')
    return {'menu_observations':14,'real_client_principals':list(map(offline,PLAYERS)),'native_container_types':3,'topology_updates':1}
def privacy_observations(rows,ready):
    require([(r['player'],r['target'])for r in rows]==[('PlayerD_A','private'),('PlayerD_B','private'),('PlayerD_B','missing'),('PlayerD_A','deposit')],'privacy observation schedule changed')
    private=json.loads(rows[0]['payload']);require(private['storage']==ready['ids']['private'],'owner private control not observed');require(sum(s['count']for s in private['slots']if s['item']and s['item']['registryId']=='minecraft:diamond')==7,'private positive control missing')
    require(all(r['payload']=='UNAVAILABLE'for r in rows[1:]),'private/missing/deposit-only observer leaked identity or stock');return {'privacy_observations':4,'uniform_refusals':3}
def verify(root,identity,fixture):
    m=read(root/'identity.json');require(m['identity']==identity and m['case']=='D05-CL01'and m['world']=='d0500000-0000-4000-8000-000000000001','client campaign identity swapped');require(m['phase']=='COLLECTED_RAW'and m['players']==list(PLAYERS),'real-client collection incomplete');require(m['integration_root']is None and m['save_proof']is None,'fabricated client save/root authority')
    selected=root/'selected-client-fixture.jar';require(sha(selected)==m['selected_fixture_sha256']and sha(fixture)==m['fixture_sha256'],'client fixture mutated')
    with ZipFile(fixture)as original,ZipFile(selected)as observed:
        require(original.namelist()==observed.namelist(),'client fixture members changed')
        for n in original.namelist():
            if n!='fabric.mod.json':require(original.read(n)==observed.read(n),'client fixture implementation swapped')
        d=json.loads(original.read('fabric.mod.json'));d['id']='cognitivecraft_parallel_d_clients';d['entrypoints']={'main':['dev.aivillages.e2e.paralleld.DStorageObservationServer']};require(json.loads(observed.read('fabric.mod.json'))==d,'client fixture entrypoint not frozen')
    ready=read(root/'server/d05-ready.json');require(m['seed']==ready['seed']==20261027 and m['render_backend']=='ESTABLISHED_SDL_OFFSCREEN','client seed/render plan changed');props=read(root/'server-properties.json');require(props['level-seed']=='20261027'and props['server-port']=='25661','actual client server properties changed');require(ready['world']==m['world']and len(ready['ids'])==5 and len(set(ready['ids'].values()))==5,'client native registration identities missing/duplicate');frames=read(root/'menu-observations.json');details=menu_observations(frames,ready);details.update(privacy_observations(read(root/'privacy-observations.json'),ready))
    require(m['driver_sha256']==sha(REPO/'build/e2e-driver/client.jar')and m['observer_sha256']==sha(REPO/'build/e2e-driver/observer.jar'),'established real driver identity changed')
    containers=lines(root/'containers.jsonl');containers=containers[0]if len(containers)==1 and isinstance(containers[0],list)else containers;require({r['Service']for r in containers}=={'minecraft','client-a','client-b'}and len({r['ID']for r in containers})==3,'independent real container identities missing')
    process=read(root/'server/process.json');require(process['releaseSha256']==identity['jar_sha256']and any(r['sha256']==sha(selected)for r in process['mods']),'server used different release/fixture')
    screenshots=read(root/'screenshots.json');require([s['player']for s in screenshots]==list(PLAYERS) and all(any(k in s['result'].get('screen','')for k in ('Container','Chest','Barrel'))for s in screenshots),'screenshots did not show actual native container screens');render_namespaces=set();container_ids={c['Service']:c['ID']for c in containers}
    for player in PLAYERS:
        box=root/'clients'/player;p=read(box/'process.json');events=lines(box/'events.jsonl');observed=lines(box/'observations.jsonl');require(p['kind']=='client'and p['releaseSha256']==identity['jar_sha256']and 'net.fabricmc.loader.impl.launch.knot.KnotClient'in p['command']and p['command'][p['command'].index('--username')+1]==player,'wrong actual client process/release');require(any(e['kind']=='play'and e['data']['uuid']==offline(player)for e in events),'no authenticated native multiplayer play');require(p['renderEnvironment'].get('SDL_VIDEO_DRIVER')=='offscreen'and p['renderEnvironment'].get('LIBGL_ALWAYS_SOFTWARE')=='1','established native render environment changed');require(any(e['kind']=='driver'and e['data']['pid']==p['pid']for e in events),'native JVM process identity mismatch');render_namespaces.add((container_ids['client-a'if player=='PlayerD_A'else'client-b'],p['pid']))
        for row in [r for r in frames if r['player']==player]:require(any(s['state']==row['state']for s in observed)and row['observer']['event']in events,'menu/feedback not from actual client driver')
        for row in [r for r in read(root/'privacy-observations.json')if r['player']==player]:require(row['event']in events,'privacy not from actual incoming server chat')
        before=next(r for r in frames if (r['player'],r['phase'],r['target'])==(player,'BEFORE','barrel'));after=next(r for r in frames if (r['player'],r['phase'],r['target'])==(player,'AFTER','barrel'));updates=[e for e in events if e['kind']=='menu-slot'and e['data']['menu']==after['state']['menu']and e['data']['item']=='minecraft:oak_planks'and e['data']['count']==64 and before['observer']['event']['time']<=e['time']<=after['observer']['event']['time']];require(updates,'no native in-place slot synchronization packet')
        screenshot=box/'screenshots/d05-single-after.png';data=screenshot.read_bytes();require(data[:8]==b'\x89PNG\r\n\x1a\n'and struct.unpack('>II',data[16:24])==(800,600),'native rendered client screenshot missing')
    require(len(render_namespaces)==2,'two clients shared one native process namespace');private_id=ready['ids']['private'];bfeedback='\n'.join(e['data']['text']for e in lines(root/'clients/PlayerD_B/events.jsonl')if e['kind']=='feedback');require(private_id not in bfeedback and 'minecraft:diamond'not in bfeedback,'private observer leaked to foreign client')
    server=lines(root/'server/d05-observations.jsonl');require(len([e for e in server if e['event']=='BEFORE_MUTATION'])==len([e for e in server if e['event']=='AFTER_MUTATION'])==3,'native before/after snapshots missing');split=[e for e in server if e['event']=='SPLIT'];require(len(split)==1 and split[0]['data']['oldBindingVisible']is False,'stale double binding survived topology change')
    details.update(connected_clients=2,case='D05-CL01',save_claims=0);evidence=[str((root/p).relative_to(root.parent))for p in ('identity.json','menu-observations.json','privacy-observations.json','server/d05-observations.jsonl','clients/PlayerD_A/events.jsonl','clients/PlayerD_B/events.jsonl')];return [{'id':'D05-CL01','status':'PASS'}],{**details,'evidence':evidence}
