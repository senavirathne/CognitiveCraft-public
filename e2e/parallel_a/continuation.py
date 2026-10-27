"""Read an exact stopped A04 world and its original native evidence without clearing history."""
from pathlib import Path,PurePosixPath
import hashlib
import json
import shutil
from zipfile import ZipFile
from observation_refs import restore

ARCHIVE_SHA='e2a8ec4206327e6ff7fe0dc7a3de427490d943c06032d39529257eea479f9881'
PRODUCTION_SHA='668287bd6226e6a9e4b1edabcc7d2d91e115eb1e5605bbef29c8d451dd3ae66e'
LOG='minecraft:oak_log'

def sha(path):
    with Path(path).open('rb') as source:return hashlib.file_digest(source,'sha256').hexdigest()

def calls(frame):return {'needleCalls':frame['needleCalls'],'generationCalls':frame['brokerStats']['calls']}

def terminal(frame,run):
    view=frame.get('knownPlayers',{}).get('PlayerA',{}).get('runs',{}).get(run)
    return view if view and view.get('phase')=='TERMINAL' else None

def balance(frame,actor,destination):
    inventory=next(a['inventory'] for a in frame['actors'] if a['uuid']==actor)
    return frame['containers'].get(','.join(map(str,destination)),{}).get(LOG,0),inventory,calls(frame)

def read_input(archive,target):
    assert sha(archive)==ARCHIVE_SHA
    target=Path(target);target.mkdir(parents=True,exist_ok=False)
    with ZipFile(archive) as source:
        assert source.testzip() is None
        chosen=[]
        for entry in source.infolist():
            name=entry.filename;parts=PurePosixPath(name)
            assert not parts.is_absolute() and '..' not in parts.parts
            if entry.is_dir():continue
            if (name.startswith('server/game/world/') or name.startswith('server/observation-bodies/')
                or name.startswith('server/secondary-observations.refs-') or name in {
                    'metadata.json','a04-results.json','independent-admissions.json','models.json','observation-area.json',
                    'assessment-language.json','pinned-original/result.json','clients/PlayerA/events.jsonl',
                    'server/snapshot.json','server/secondary-snapshot.json','server/game/ops.json','server/game/usercache.json',
                    'server/game/whitelist.json','server/game/banned-ips.json','server/game/banned-players.json','server/game/server.properties'}):chosen.append(entry)
        assert len(chosen)<8192 and sum(x.file_size for x in chosen)<3*1024**3
        for entry in chosen:source.extract(entry,target)
    read=lambda p:json.loads(p.read_text())
    metadata=read(target/'metadata.json');assert metadata['sourceCommit']=='4517ce82ba8227c6aa101ea24607ab2fd9120ac6' and not metadata['dirty']
    assert metadata['releaseSha256']==PRODUCTION_SHA
    result=read(target/'a04-results.json');assert result['status']=='FAIL' and result['reason']=='Repeated known native delivery changed custody/model contract'
    journal=target/'server/game/world/data/cognitivecraft/jobs/v1/state.jsonl';header,payload=journal.read_bytes().split(b'\n',1);header=json.loads(header)
    assert hashlib.sha256(payload).hexdigest()==header['sha256']
    rows=[json.loads(line) for line in payload.splitlines()];assert len(rows)==header['records']==55 and all(j['kind']=='job' for j in rows)
    jobs={j['submission']:j for j in rows if j['request']['request']['arguments'].get('destination',{}).get('x')==333}
    assert len(jobs)==52 and all(j['state']=='SUCCEEDED' for j in jobs.values())
    accepted=[]
    for line in (target/'clients/PlayerA/events.jsonl').read_text().splitlines():
        event=json.loads(line);text=event.get('data',{}).get('text','')
        if text.startswith('run=') and 'accepted=true' in text:
            identifier=text.split()[0][4:]
            if identifier in jobs:accepted.append((identifier,event['time']))
    assert len(accepted)==52 and len({r for r,t in accepted})==52
    accepted.sort(key=lambda row:row[1]);actor=jobs[accepted[0][0]]['request']['request']['arguments']['actor'];cycles=[];prior=None;body_cache={};tail=[]
    def value(item):
        if set(item)=={'$a04Raw'}:
            key=item['$a04Raw']
            if key not in body_cache:
                path=target/'server/observation-bodies'/(key+'.json');data=path.read_bytes()
                assert len(data)<=4*1024*1024 and hashlib.sha256(data).hexdigest()==key
                if len(body_cache)>=128:body_cache.clear()
                body_cache[key]=json.loads(data)
            return body_cache[key]
        return item
    for path in sorted((target/'server').glob('secondary-observations.refs-*.jsonl')):
        for number,line in enumerate(path.open('rb'),1):
            if not line.endswith(b'\n'):tail.append({'path':path.name,'line':number,'sha256':hashlib.sha256(line).hexdigest()});break
            wrapper=json.loads(line)
            if len(cycles)==52:continue
            run,submitted=accepted[len(cycles)];preview=json.loads(wrapper['template'])
            if wrapper['time']<=submitted and preview['containers'].get('333,208,72',{}).get(LOG,0)==len(cycles) and LOG in preview['blocks'].get('326,208,66',''):
                prior=wrapper
            candidate=preview.get('knownPlayers',{}).get('PlayerA',{}).get('runs',{}).get(run)
            if not candidate or value(candidate).get('phase')!='TERMINAL':continue
            assert prior is not None
            before=json.loads(restore(prior,target/'server/observation-bodies'));after=json.loads(restore(wrapper,target/'server/observation-bodies'));view=terminal(after,run)
            stock,inventory,after_calls=balance(after,actor['entity'],[333,208,72]);before_stock,before_inventory,before_calls=balance(before,actor['entity'],[333,208,72])
            assert view['marker']['outcome']=='SUCCEEDED' and view['responsibility']['id']==jobs[run]['id']
            assert before_stock==len(cycles) and stock==before_stock+1 and not before_inventory and not inventory and before_calls==after_calls
            assert all(sum(r['quantity'] for r in view['resourceReceipts'] if r['stage']==stage and r['item']['id']==LOG)==1 for stage in ('HARVEST','PICKUP','DEPOSIT'))
            cycles.append({'run':run,'view':view,'beforeDestination':before_stock,'afterDestination':stock,'modelCallsUnchanged':True,
                           'modelCallsBefore':before_calls,'modelCallsAfter':after_calls,'brokerStats':after['brokerStats'],
                           'sourceArtifact':12732465516,'beforeFrameSha256':prior['originalSha256'],'afterFrameSha256':wrapper['originalSha256'],
                           'beforeFrameTime':prior['time'],'afterFrameTime':wrapper['time']});prior=None
    assert len(cycles)==52
    original=read(target/'pinned-original/result.json');assert original['job'] in {j['id'] for j in rows if j['state']=='INTERRUPTED'}
    world=target/'server/game/world';world_hashes={p.relative_to(world).as_posix():sha(p) for p in world.rglob('*') if p.is_file() and p.name!='session.lock'}
    return {'metadata':metadata,'priorResult':result,'cycles':cycles,'original':original,'actor':actor,'world':header['world'],
            'originalRows':rows,'worldHashes':world_hashes,'retainedIncompleteTails':tail,'sourceArchiveSha256':ARCHIVE_SHA,'sourceArtifact':12732465516}

def start(h,archive):
    from suite import ROOT,read,atomic,require
    from baseline import native_process
    previous=read_input(archive,h.root.parent/('a04-prior-'+h.root.name))
    require(sha(ROOT/'fabric/build/libs/ai-villages-0.1.0.jar')==PRODUCTION_SHA,'Continuation requires the exact prior production JAR')
    shutil.copyfile(archive,h.root/'continuation-input.zip')
    prior=h.root.parent/('a04-prior-'+h.root.name);shutil.copytree(prior/'server/game',h.root/'server/game',ignore=shutil.ignore_patterns('session.lock'))
    world=h.root/'server/game/world';require({p.relative_to(world).as_posix():sha(p) for p in world.rglob('*') if p.is_file()}==previous['worldHashes'],'Whole-world continuation changed source bytes before boot')
    shutil.copytree(ROOT/'build/needle',h.root/'needle');h.env.update(COGNITIVECRAFT_OLLAMA_MODEL='qwen3:4b-instruct-2507-q4_K_M',COGNITIVECRAFT_NEEDLE_DIR='/evidence/needle')
    h.hashes=read(prior/'independent-admissions.json')['artifacts'];h.admissions=read(prior/'independent-admissions.json')['admissions']
    for name in ('models.json','independent-admissions.json'):shutil.copyfile(prior/name,h.root/name)
    h.companion=(previous['actor']['citizen'],previous['actor']['entity']);h.other_source=[326,208,66];h.other_destination=[333,208,72]
    h.compose('build','minecraft');h.start('server');h.start('PlayerA');h.start('PlayerB');h.companion_region();h.stable()
    ready=read(h.root/'server/job-ready.json');require(ready['world']==previous['world'],'Continuation created another world identity')
    current=(world/'data/cognitivecraft/jobs/v1/state.jsonl').read_bytes();header,payload=current.split(b'\n',1);header=json.loads(header)
    require(hashlib.sha256(payload).hexdigest()==header['sha256'] and [json.loads(line) for line in payload.splitlines()]==previous['originalRows'],'Continuation changed original Job bodies on startup')
    h.wait(lambda:(frame if (frame:=read(h.root/'server/secondary-snapshot.json')) and balance(frame,h.entities['PlayerA'],h.other_destination)[0]==52 else None),'Actual continued destination retains all 52 original native deliveries',40)
    evidence={k:previous[k] for k in ('world','worldHashes','sourceArchiveSha256','sourceArtifact','retainedIncompleteTails')}
    evidence.update(priorSource=previous['metadata']['sourceCommit'],productionJarSha256=PRODUCTION_SHA,retainedJobs=55,retainedNativeDeliveries=52,
                    newClientProcessesAtRunBoundary=True,sameClientsFromHere=True,newNativeProcess=native_process(h),originalSourceQualification='FAIL')
    atomic(h.root/'continuation.json',evidence);return previous,evidence
