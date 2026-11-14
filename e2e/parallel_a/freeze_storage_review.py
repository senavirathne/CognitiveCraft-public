"""Read-only archive/frame parsers from the original A03/A04 independent reviews.

Source: CognitiveCraft-Documentation@bd09c72ba350f2a46f96d82fc0d31d19cf700efa,
docs/implementation/parallel-a/review_a03.py and review_a04.py.
Only reusable pure functions are copied; no historical source/run/denominator is imported.
"""
from pathlib import Path,PurePosixPath
import hashlib,json,math,re,struct,uuid

def digest(path):
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()

def read(path):
    return json.loads(Path(path).read_text())

def journal(path):
    header, payload = Path(path).read_bytes().split(b'\n', 1)
    header = json.loads(header)
    assert hashlib.sha256(payload).hexdigest() == header['sha256']
    rows = [json.loads(line) for line in payload.splitlines()]
    assert len(rows) == header['records']
    return header, rows

def percentiles(values):
    values = sorted(values)
    assert values
    return {**{f'p{p}': values[math.ceil(len(values)*p/100)-1]/1e6 for p in (50, 95, 99)},
            'max': max(values)/1e6, 'samples': len(values), 'unit': 'milliseconds'}

def segment(path, world):
    data = path.read_bytes()
    assert hashlib.sha256(data).hexdigest() == path.stem
    assert hashlib.sha256(data[:-32]).digest() == data[-32:] and data[:8] == b'CCAHIST1'
    assert struct.unpack_from('>i', data, 8)[0] == 1 and str(uuid.UUID(bytes=data[12:28])) == world
    owner = data[28]
    revision, generation, count = struct.unpack_from('>qqi', data, 29)
    assert owner in (0, 1) and revision >= 0 and generation >= 0 and 1 <= count <= 64
    offset = 49
    rows = {}
    for _ in range(count):
        kind = data[offset]
        identifier = str(uuid.UUID(bytes=data[offset+1:offset+17]))
        size = struct.unpack_from('>i', data, offset+17)[0]
        offset += 21
        assert 0 <= kind <= 4 and 1 <= size <= 65536 and offset+size <= len(data)-32
        key = (owner, kind, identifier)
        assert key not in rows
        rows[key] = data[offset:offset+size]
        offset += size
    assert offset == len(data)-32
    return owner, revision, generation, rows

def history(world, *, allow_orphan=False):
    root = world/'data/cognitivecraft/runtime-history/v1'
    header, payload = (root/'manifest.jsonl').read_bytes().split(b'\n', 1)
    header = json.loads(header)
    assert header['schema'] == 1 and hashlib.sha256(payload).hexdigest() == header['sha256']
    manifest = json.loads(payload)
    assert header['records'] == len(manifest['segments'])
    physical = {p.stem:segment(p,header['world']) for p in root.glob('*.seg')}
    current = {}
    for ref in manifest['segments']:
        owner, revision, generation, rows = physical[ref['digest']]
        assert ref['owner'] == ('JOBS', 'LEASES')[owner] and revision <= header['owners'][ref['owner']]
        assert generation <= header['retentionGeneration'] and ref['records'] == len(rows)
        assert ref['bytes'] == (root/(ref['digest']+'.seg')).stat().st_size
        for key, value in rows.items():
            assert key not in current or current[key] == value
            current[key] = value
    protected = {r['digest'] for r in manifest['segments']}
    for name in ('manifest.prev.jsonl', 'manifest.next.jsonl'):
        path = root/name
        if path.exists():
            prior_header, prior_payload = path.read_bytes().split(b'\n',1)
            prior_header = json.loads(prior_header)
            assert prior_header['world'] == header['world'] and hashlib.sha256(prior_payload).hexdigest() == prior_header['sha256']
            prior = json.loads(prior_payload)
            assert prior_header['records'] == len(prior['segments'])
            for ref in prior['segments']:
                protected.add(ref['digest'])
                assert all(current.get(k) == v for k,v in physical[ref['digest']][3].items())
    if not allow_orphan:
        assert set(physical) == protected
    jobs = {}
    leases = {}
    for (owner, kind, identifier), raw in current.items():
        assert kind == 0
        row = json.loads(raw)
        if owner == 0:
            assert row['schema'] == 1 and row['world'] == header['world']
            assert str(uuid.UUID(bytes=hashlib.md5(raw).digest(),version=3)) == identifier
            assert raw == json.dumps(row,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()
            assert 1 <= len(row['jobs']) <= 64 and len(row['allocations']) <= 128
            assert {r['job'] for r in row['custody']} == {j['id'] for j in row['jobs']}
            for job in row['jobs']:
                assert job['state'] in ('SUCCEEDED','FAILED','CANCELLED') and all(a.get('terminal') and not a['uncertain'] for a in job['attempts'])
                assert job['id'] not in jobs or jobs[job['id']] == job
                jobs[job['id']] = job
        else:
            assert row['id'] == identifier and row['world'] == header['world'] and row['state'] != 'ACTIVE'
            leases[identifier] = row
    return dict(header=header,jobs=jobs,leases=leases,segments=len(physical),orphans=sorted(set(physical)-protected))

def totals(receipts):
    return {s: sum(r['quantity'] for r in receipts if r['stage'] == s and (r['item'] == 'minecraft:oak_log' or r['item'] == {'id':'minecraft:oak_log','components':'default'}))
            for s in ('HARVEST', 'PICKUP', 'DEPOSIT')}

def reference_review(server, expected_jobs, refusal_time):
    # Immutable evidence bytes are verified once before substitution; the complete
    # original frame is checked every time. This cache is local to one review.
    bodies = {}
    size = 0
    for path in (server/'observation-bodies').glob('*.json'):
        assert not path.is_symlink() and re.fullmatch('[0-9a-f]{64}', path.stem)
        data = path.read_bytes()
        assert len(data) <= 4*1024*1024 and hashlib.sha256(data).hexdigest() == path.stem
        bodies[path.stem] = data
        size += len(data)
    assert len(bodies) <= 32768 and size <= 512*1024*1024
    token = re.compile(rb'\{"\$a04Raw":"([0-9a-f]{64})"\}')
    result = {'bodies': len(bodies), 'bodyBytes': size, 'streams': {}, 'nativeJobIdsObserved': set()}
    for stream in ('observations', 'secondary-observations'):
        count = 0
        tails = []
        prior = None
        after = None
        seen = set()
        for path in sorted(server.glob(stream+'.refs-*.jsonl')):
            with path.open('rb') as source:
                for number, line in enumerate(source, 1):
                    assert len(line) <= 64*1024*1024
                    if not line.endswith(b'\n'):
                        tails.append(dict(path=path.name, line=number, bytes=len(line), sha256=hashlib.sha256(line).hexdigest()))
                        break
                    wrapper = json.loads(line)
                    assert wrapper['schema'] == 1
                    raw = token.sub(lambda match: bodies[match[1].decode()], wrapper['template'].encode())
                    assert hashlib.sha256(raw).hexdigest() == wrapper['originalSha256']
                    frame = json.loads(raw)
                    assert frame['time'] == wrapper['time'] and frame['pid'] == wrapper['pid']
                    count += 1
                    player = frame['knownPlayers'].get('PlayerA', {})
                    visible = dict(player.get('jobs', {}))
                    for run in player.get('runs', {}).values():
                        job = run.get('responsibility')
                        if job is not None:
                            visible[job['id']] = job
                    for identifier, job in visible.items():
                        if identifier in expected_jobs and job['state'] == 'SUCCEEDED':
                            receipts = [r for a in job['attempts'] for r in a.get('resourceReceipts', [])]
                            assert totals(receipts) == dict(HARVEST=1, PICKUP=1, DEPOSIT=1)
                            seen.add(identifier)
                    if frame['time'] <= refusal_time:
                        prior = frame
                    elif after is None:
                        after = frame
        assert count and expected_jobs <= seen, (stream, count, len(seen), sorted(expected_jobs-seen))
        def custody(frame):
            assert frame is not None
            return dict(time=frame['time'], destination=frame['containers'].get('333,208,72'),
                        source=frame['blocks'].get('326,208,66'), broker=frame['brokerStats'],
                        actorInventory={a['uuid']: a['inventory'] for a in frame['actors']})
        result['streams'][stream] = dict(completedFrames=count, retainedIncompleteTails=tails,
                                         nativeJobIdsObserved=len(seen), beforeRefusal=custody(prior), afterRefusal=custody(after))
        result['nativeJobIdsObserved'].update(seen)
    result['nativeJobIdsObserved'] = len(result['nativeJobIdsObserved'])
    return result

def complete_performance(native,mode):
    result=read(native/(mode+'-performance.json'));raw_path=native/(mode+'-raw-window.json');raw=read(raw_path)
    assert digest(raw_path)==result['rawSha256']
    assert len(raw['tickSamples'])==len(raw['captures'])==400 and len(raw['ackNanos'])==64
    for name,values in [('tick',[r['nanos'] for r in raw['tickSamples']]),('capture',[r['nanos'] for r in raw['captures']]),('ack',raw['ackNanos'])]:
        assert percentiles(values)==result[name]
    frozen={'tickP95Ms':20,'tickP99Ms':40,'captureP99Ms':2,'ackP95Ms':250,'ackP99Ms':1000,'ackMaxMs':30000,'ticks':400,'ackSamples':64}
    assert all(result['limits'][k]==v for k,v in frozen.items())
    for field,percentile,limit in [('tick','p95','tickP95Ms'),('tick','p99','tickP99Ms'),('capture','p99','captureP99Ms'),('ack','p95','ackP95Ms'),('ack','p99','ackP99Ms'),('ack','max','ackMaxMs')]:
        assert result[field][percentile]<=result['limits'][limit]
    assert result['io']['fixture'] and result['io']['nativeEffects']==0 and result['io']['logicalJobs']==64
    io=result['io'];assert io['setupOutsideTimer'] and io['inputDurability']=='COPIED_INPUT_FILES_AND_DIRECTORIES_FORCED_BEFORE_PUBLICATION_TIMER'
    prepared=io['preparedInputs'];assert len(prepared)==64
    template=native/'job-cost/template';files={p.relative_to(template).as_posix():p for p in template.rglob('*') if p.is_file()}
    assert 1<=len(files)<=160 and all(not p.is_symlink() for p in files.values())
    expected={name:(p.stat().st_size,digest(p)) for name,p in files.items()}
    assert digest(template/'data/cognitivecraft/jobs/v1/state.jsonl')==io['sourceSha256']
    for number,entry in enumerate(prepared):
        assert entry['fixture']==mode+'-'+str(number) and entry['parentForced'] and entry['beforePublicationTimer']
        assert 1<=entry['directories']<=160
        rows=entry['files'];assert len(rows)==len(files) and len({r['path'] for r in rows})==len(rows)
        for row in rows:
            path=PurePosixPath(row['path']);assert not path.is_absolute() and '..' not in path.parts
            assert (row['bytes'],row['sha256'])==expected[row['path']]
        # The original durable input is still the backup of the timed replacement.
        assert digest(native/'job-cost'/entry['fixture']/'data/cognitivecraft/jobs/v1/state.prev.jsonl')==io['sourceSha256']
    return result

def replay_lookup(value,prior):
    assert value['status']=='PASS' and not value['hot'] and value['hotUnchanged']
    assert value['job']==prior['job'] and value['knowledge']==prior['knowledge'] and value['knowledge']['allowed']
    assert value['accomplishments']==prior['accomplishments']
    assert value['sameReceipt']['code']==value['cancel']['code']=='DUPLICATE'
    assert value['changedReceipt']['code']==value['staleSubmission']['code']=='REJECTED'

def process_replaced(before,after):
    assert (before['hostJvmPid'],before['startTicks'])!=(after['hostJvmPid'],after['startTicks'])
    assert before['namespaceProcess']['releaseSha256']==after['namespaceProcess']['releaseSha256']

def exact_cycle_frames(server,cycles):
    expected={c[field] for c in cycles for field in ('beforeFrameSha256','afterFrameSha256')}
    token=re.compile(rb'\{"\$a04Raw":"([0-9a-f]{64})"\}');bodies={};frames={}
    for path in sorted(server.glob('secondary-observations.refs-*.jsonl')):
        for line in path.open('rb'):
            if not line.endswith(b'\n'):break
            wrapper=json.loads(line);key=wrapper['originalSha256']
            if key not in expected:continue
            def body(match):
                digest=match[1].decode()
                if digest not in bodies:
                    raw=(server/'observation-bodies'/(digest+'.json')).read_bytes()
                    assert len(raw)<=4*1024*1024 and hashlib.sha256(raw).hexdigest()==digest
                    bodies[digest]=raw
                return bodies[digest]
            raw=token.sub(body,wrapper['template'].encode());assert hashlib.sha256(raw).hexdigest()==key
            frame=json.loads(raw);assert frame['time']==wrapper['time'] and frame['pid']==wrapper['pid']
            frames[key]=frame
    assert set(frames)==expected
    for cycle in cycles:
        before=frames[cycle['beforeFrameSha256']];after=frames[cycle['afterFrameSha256']]
        assert before['time']==cycle['beforeFrameTime']<cycle['afterFrameTime']==after['time']
        view=after['knownPlayers']['PlayerA']['runs'][cycle['run']];assert view==cycle['view'] and view['phase']=='TERMINAL'
        assert view['marker']['outcome']=='SUCCEEDED' and totals(view['resourceReceipts'])==dict(HARVEST=1,PICKUP=1,DEPOSIT=1)
        actor=view['responsibility']['request']['request']['arguments']['actor']['value']['entityId']
        for frame,count in [(before,cycle['beforeDestination']),(after,cycle['afterDestination'])]:
            assert frame['containers']['333,208,72'].get('minecraft:oak_log',0)==count
            assert not next(a['inventory'] for a in frame['actors'] if a['uuid']==actor)
        assert 'minecraft:oak_log' in before['blocks']['326,208,66']
        assert before['needleCalls']==after['needleCalls'] and before['brokerStats']['calls']==after['brokerStats']['calls']==0
    return {'cycles':len(cycles),'completeFrames':len(frames),'firstBeforeSha256':cycles[0]['beforeFrameSha256'],'lastAfterSha256':cycles[-1]['afterFrameSha256']}

def codec(n):
    r=n['resource'];area=r['area'];o=n['origin']
    return dict(schema=1,id=n['id'],group=n['group'],epoch=n['epoch'],generation=n['generation'],job=n['owner']['job'],jobGeneration=n['owner']['generation'],
        principal=o['principal']['id'],world=o['scope']['worldId'],domain=o['scope']['domainId'],
        resource=dict(kind=r['kind'],dimension=area['dimension'],item=r['item'],slot=r['slot'],min=[area['minX'],area['minY'],area['minZ']],max=[area['maxX'],area['maxY'],area['maxZ']]),
        **{k:n[k] for k in ('quantity','remaining','identity','granted','renewed','expires','renewals','state','consumed')},reason=n.get('reason',''))

def inventory(v):
    rows={l['id']:codec(l) for l in v['hot']['leases']}
    for r in v['archived']:
        payload=r['payload'].encode();assert sha(payload)==r['sha256'];row=json.loads(payload);assert row['schema']==1 and row['id']==r['id']
        if r['id'] in rows:assert rows[r['id']]==row
        rows[r['id']]=row
    return rows

sha=lambda value:hashlib.sha256(value).hexdigest()

def canonical_world(world,allow_orphan=False):
    header,rows=journal(world/'data/cognitivecraft/jobs/v1/state.jsonl')
    archived=history(world,allow_orphan=allow_orphan);assert header['world']==archived['header']['world']
    hot={r['id']:r for r in rows};assert len(hot)==len(rows) and len(hot)<=64
    union=dict(archived['jobs'])
    for key,row in hot.items():
        assert key not in union or union[key]==row;union[key]=row
    return header,hot,archived,union

def frames(server,stream,*,tails=None):
    """Reconstruct exact original bytes; retain crash-truncated tails without parsing them."""
    plain=server/(stream+'.jsonl')
    epochs=sorted(server.glob(stream+'.epoch-*.jsonl'))
    if epochs:
        assert not plain.exists() and not list(server.glob(stream+'.refs-*.jsonl')),'Ambiguous observation format'
        assert len(epochs)<=128
        for path in epochs:
            match=re.fullmatch(re.escape(stream)+r'\.epoch-(\d{19})-(\d+)-([0-9a-f-]{36})\.jsonl',path.name)
            assert match and not path.is_symlink(),'Invalid native observer epoch'
            with path.open('rb') as source:
                for number,line in enumerate(source,1):
                    assert len(line)<=64*1024*1024
                    if not line.endswith(b'\n'):
                        assert tails is not None,'Incomplete retained log tail must be reported'
                        tails.append(dict(path=path.name,line=number,bytes=len(line),sha256=sha(line),classification='UNKNOWN_INCOMPLETE_FRAME_NOT_NATIVE_PROOF'))
                        break
                    frame=json.loads(line);assert 'time' in frame and frame['pid']==int(match[2])
                    yield frame
        return
    if plain.is_file():
        assert not list(server.glob(stream+'.refs-*.jsonl')),'Ambiguous observation format'
        for number,line in enumerate(plain.open('rb'),1):
            if not line.endswith(b'\n'):
                assert tails is not None,'Incomplete retained log tail must be reported'
                tails.append(dict(path=plain.name,line=number,bytes=len(line),sha256=sha(line),classification='UNKNOWN_INCOMPLETE_FRAME_NOT_NATIVE_PROOF'))
                break
            frame=json.loads(line);assert 'time' in frame and 'pid' in frame
            yield frame
        return
    from collections import OrderedDict
    cache=OrderedDict();token=re.compile(rb'\{"\$a04Raw":"([0-9a-f]{64})"\}')
    def body(match):
        key=match[1].decode()
        if key not in cache:
            raw=(server/'observation-bodies'/(key+'.json')).read_bytes()
            assert len(raw)<=4*1024**2 and sha(raw)==key
            cache[key]=raw
            if len(cache)>128:cache.popitem(last=False)
        return cache[key]
    paths=sorted(server.glob(stream+'.refs-*.jsonl'));assert paths,stream
    for path in paths:
        for number,line in enumerate(path.open('rb'),1):
            if not line.endswith(b'\n'):
                assert tails is not None,'Incomplete retained log tail must be reported'
                tails.append(dict(path=path.name,line=number,bytes=len(line),sha256=sha(line),classification='UNKNOWN_INCOMPLETE_FRAME_NOT_NATIVE_PROOF'))
                break
            wrapper=json.loads(line);assert wrapper['schema']==1
            raw=token.sub(body,wrapper['template'].encode());assert sha(raw)==wrapper['originalSha256']
            frame=json.loads(raw);assert frame['time']==wrapper['time'] and frame['pid']==wrapper['pid']
            yield frame

def observed_runs(root,views):
    """Both independent native observation streams must retain each completed view."""
    wanted={v['id']:v for v in views};assert len(wanted)==len(views)
    streams={}
    for stream in ('observations','secondary-observations'):
        seen=set();count=0;tails=[]
        for frame in frames(root/'server',stream,tails=tails):
            count+=1
            for player in frame['knownPlayers'].values():
                for key,view in player.get('runs',{}).items():
                    if key in wanted and view==wanted[key]:seen.add(key)
        assert count and seen==set(wanted),(stream,sorted(set(wanted)-seen))
        streams[stream]={'frames':count,'exactCompletedViews':len(seen),'retainedIncompleteTails':tails}
    return streams

def pinned_jobs(raw,views):
    for key,view in views.items():
        row=raw[key]
        assert row['id']==view['id'] and row['submission']==view['submissionId'] and row['generation']==view['generation']
        assert row['allowance']['maxima']==view['allowance']['maxima'] and row['allowance']['deadline']==view['allowance']['deadlineEpochMillis']
        assert row['state']==view['state'] and row['usage']==view['usage']
        assert len(row['attempts'])==len(view['attempts'])
        for a,b in zip(row['attempts'],view['attempts'],strict=True):
            assert all(a[k]==b[k] for k in ('id','generation','effects','credited','uncertain','usage'))
            assert a['allowance']['maxima']==b['allowance']['maxima'] and a['allowance']['deadline']==b['allowance']['deadlineEpochMillis']

def no_effect(value,reason,stock=None):
    before,after=value['before'],value['after'];dest='333,208,72';source='326,208,66'
    expected=value['stock'] if stock is None else stock
    assert before['containers'][dest].get('minecraft:oak_log',0)==after['containers'][dest].get('minecraft:oak_log',0)==expected
    assert 'minecraft:oak_log' in before['blocks'][source] and 'minecraft:oak_log' in after['blocks'][source]
    assert value['calls']=={'needleCalls':0,'generationCalls':0}
    assert reason in json.dumps(value['result']) and not any(totals(value['result'].get('resourceReceipts',[])).values())
