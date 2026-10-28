"""Exact, read-only input for finishing the witnessed A04 cohort. No fixture is a native effect."""
from pathlib import Path,PurePosixPath
from zipfile import ZipFile
import hashlib,json,math
from continuation import read_input,sha,restore,terminal,balance,CURRENT_PRODUCTION_SHA,LOG

SOURCE='1d43736e92907a2b718655e52ddd6384871fe496'
ARTIFACT=12763321061
ARCHIVE_SHA='6a103764ecc09b035523c8bd3440cf3dc4c85ea2a23fad1121d04fe99528b2f1'
FAILURE='Request refused before execution: run=0109b93b-2d59-40aa-99d0-86cd7643b508 accepted=false reason=INTERRUPTED'
PREFIX={'native-positive-control','actual-old-job-wall','continuous-native-over-64-terminal-jobs','native-job-cut-after_segment_ack','native-job-cut-after_directory_ack'}

def checkpoint_identity(metadata,result):
    assert metadata['sourceCommit']==SOURCE and not metadata['dirty'] and metadata['releaseSha256']==CURRENT_PRODUCTION_SHA
    assert result['status']=='FAIL' and result['reason']==FAILURE
    cases={c['id']:c for c in result['cases']}
    assert len(result['cases'])==len(PREFIX) and set(cases)==PREFIX and all(c['status']=='PASS' for c in cases.values())
    return cases

def read_checkpoint(archive,target):
    assert sha(archive)==ARCHIVE_SHA
    target=Path(target);target.mkdir(parents=True,exist_ok=False)
    roots={'metadata.json','a04-results.json','actual-old-job-wall.json','old-wall-native-jobs.jsonl','continuation-input.zip',
           'independent-admissions.json','models.json','baseline-performance.json','baseline-raw-window.json','candidate-performance.json','candidate-raw-window.json'}
    with ZipFile(archive) as source:
        assert source.testzip() is None
        chosen=[]
        for entry in source.infolist():
            name=entry.filename;parts=PurePosixPath(name)
            assert not parts.is_absolute() and '..' not in parts.parts
            if entry.is_dir():continue
            if name.startswith('qualified-continuous-world/'):destination='server/game/world/'+name[len('qualified-continuous-world/'):]
            elif name in roots or name.startswith(('server/secondary-observations.refs-','server/observation-bodies/','cut-after_segment_ack/','cut-after_directory_ack/')):destination=name
            elif name.startswith('server/game/') and not name.startswith('server/game/world/'):destination=name
            else:continue
            chosen.append((entry,destination))
        assert len(chosen)<16384 and sum(e.file_size for e,n in chosen)<4*1024**3
        for entry,name in chosen:
            path=target/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(source.read(entry))
    read=lambda path:json.loads(path.read_text())
    metadata=read(target/'metadata.json');result=read(target/'a04-results.json');cases=checkpoint_identity(metadata,result)
    previous=read_input(target/'continuation-input.zip',target/'original-source')
    old=cases['actual-old-job-wall'];continuous=cases['continuous-native-over-64-terminal-jobs']
    assert old['cycles']==previous['cycles'] and len(continuous['additional'])==8
    assert continuous['nativeCompanionDeliveries']==69 and continuous['terminalJobs']==71
    assert len(continuous['coldRestarts'])==2 and all(c['sameClients'] for c in continuous['coldRestarts'])
    for mode in ('baseline','candidate'):
        performance=read(target/(mode+'-performance.json'));raw=read(target/(mode+'-raw-window.json'))
        assert sha(target/(mode+'-raw-window.json'))==performance['rawSha256']
        assert len(raw['tickSamples'])==len(raw['captures'])==400 and len(raw['ackNanos'])==64
        for field,values in [('tick',[s['nanos'] for s in raw['tickSamples']]),('capture',[s['nanos'] for s in raw['captures']]),('ack',raw['ackNanos'])]:
            values=sorted(values)
            for percentile in (50,95,99):assert values[math.ceil(len(values)*percentile/100)-1]/1e6==performance[field]['p'+str(percentile)]
            assert values[-1]/1e6==performance[field]['max']
        for field,percentile,limit in [('tick','p95',20),('tick','p99',40),('capture','p99',2),('ack','p95',250),('ack','p99',1000),('ack','max',30000)]:assert performance[field][percentile]<=limit
        assert performance['io']['fixture'] and performance['io']['nativeEffects']==0 and performance['io']['logicalJobs']==64
        assert performance['io']['sourceSha256']==sha(target/'old-wall-native-jobs.jsonl')
        assert performance['nativeProcess']['containerResources']=={'cpusetCpus':'0-1','memoryBytes':2*1024**3}
    world=target/'server/game/world'
    header,payload=(world/'data/cognitivecraft/jobs/v1/state.jsonl').read_bytes().split(b'\n',1);header=json.loads(header)
    assert header['world']==previous['world'] and hashlib.sha256(payload).hexdigest()==header['sha256']
    hot=[json.loads(line) for line in payload.splitlines()]
    assert len(hot)==header['records']==40
    inventory=continuous['inventory'];union={j['id']:j for j in hot}
    for entry in inventory['archived']:
        raw=entry['payload'].encode();assert hashlib.sha256(raw).hexdigest()==entry['sha256']
        component=json.loads(raw);assert component['world']==header['world']
        for job in component['jobs']:assert job['id'] not in union;union[job['id']]=job
    assert len(inventory['archived'])==32 and len(union)==72
    assert all(union[j['id']]==j for j in previous['originalRows'])
    assert sum(j['state'] in ('SUCCEEDED','FAILED','CANCELLED') for j in union.values())==71
    cycles=continuous['additional'];wanted={c[key] for c in cycles for key in ('beforeFrameSha256','afterFrameSha256')};frames={}
    for path in sorted((target/'server').glob('secondary-observations.refs-*.jsonl')):
        for line in path.open('rb'):
            if not line.endswith(b'\n'):break
            wrapper=json.loads(line)
            if wrapper['originalSha256'] in wanted:frames[wrapper['originalSha256']]=json.loads(restore(wrapper,target/'server/observation-bodies'))
    assert set(frames)==wanted
    for number,cycle in enumerate(cycles,62):
        before=frames[cycle['beforeFrameSha256']];after=frames[cycle['afterFrameSha256']];view=terminal(after,cycle['run'])
        assert view==cycle['view'] and view['marker']['outcome']=='SUCCEEDED' and union[view['responsibility']['id']]['state']=='SUCCEEDED'
        a,held_a,calls_a=balance(before,previous['actor']['entity'],[333,208,72]);b,held_b,calls_b=balance(after,previous['actor']['entity'],[333,208,72])
        assert a==number-1 and b==number and not held_a and not held_b and calls_a==calls_b
        assert before['time']==cycle['beforeFrameTime']<cycle['afterFrameTime']==after['time'] and LOG in before['blocks']['326,208,66']
        assert all(sum(r['quantity'] for r in view['resourceReceipts'] if r['stage']==stage and r['item']['id']==LOG)==1 for stage in ('HARVEST','PICKUP','DEPOSIT'))
    hashes={p.relative_to(world).as_posix():sha(p) for p in world.rglob('*') if p.is_file() and p.name!='session.lock'}
    assert len(hashes)==87 and continuous['pinned']==old['pinned'] and previous['original']['job'] in old['pinned']
    previous.update(metadata=metadata,priorResult=result,cycles=previous['cycles']+[dict(c,sourceArtifact=ARTIFACT) for c in cycles],
                    originalRows=hot,worldHashes=hashes,sourceArchiveSha256=ARCHIVE_SHA,sourceArtifact=ARTIFACT,
                    qualifiedPrefix=True,retainedLogicalJobs=72,expectedInventory=inventory)
    return previous
