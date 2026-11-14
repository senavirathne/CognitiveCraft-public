"""Exact stopped pre-effect A05 seed with a genuinely learned baseline method."""
from pathlib import Path,PurePosixPath
import hashlib,json,shutil
from zipfile import ZipFile
from continuation import bind_port,client_binding

SOURCE='b2224ccd80efb434231d681c12a29a503198414a'
ARTIFACT=12808600136
ARCHIVE_SHA='b0bbedfd6782065c32813ac516d96dcd5d257aa9cbdd8bd78e98e8210aadeb35'

def read_seed(archive,target):
    archive=Path(archive);target=Path(target)
    assert hashlib.file_digest(archive.open('rb'),'sha256').hexdigest()==ARCHIVE_SHA
    target.mkdir(parents=True,exist_ok=False)
    with ZipFile(archive) as source:
        assert source.testzip() is None
        assert all(not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts for n in source.namelist())
        metadata=json.loads(source.read('metadata.json'));result=json.loads(source.read('a05-results.json'))
        assert metadata['sourceCommit']==SOURCE and not metadata['dirty']
        assert result['status']=='FAIL' and result['reason']=='Post-restart real play PlayerA; last=None'
        admission=json.loads(source.read('independent-admissions.json'))
        assert len(admission['admissions'])==1 and admission['admissions'][0]['status']=='PASS'
        view=admission['admissions'][0]['view'];job=view['responsibility'];assert job['state']=='SUCCEEDED'
        assert view['marker']['outcome']=='ADMITTED' and view['marker']['artifactSha256']==admission['artifacts']['cognitivecraft:deliver_oak_logs']
        for stage in ('HARVEST','PICKUP','DEPOSIT'):assert sum(r['quantity'] for r in view['resourceReceipts'] if r['stage']==stage)==1
        prefix='pre-effect-seed-game/'
        selected=[e for e in source.infolist() if not e.is_dir() and e.filename.startswith(prefix)]
        assert selected and len(selected)<=512 and sum(e.file_size for e in selected)<512*1024**2
        for e in selected:
            relative=PurePosixPath(e.filename).relative_to(prefix);path=target/relative
            path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(source.read(e))
    world=target/'world';data=world/'data/cognitivecraft'
    assert not (data/'deposit-evidence').exists() and not (data/'deposit-evidence-required.json').exists()
    header,payload=(data/'jobs/v1/state.jsonl').read_bytes().split(b'\n',1);header=json.loads(header)
    assert hashlib.sha256(payload).hexdigest()==header['sha256']
    rows=[json.loads(line) for line in payload.splitlines()]
    assert len(rows)==header['records']==1 and rows[0]['id']==job['id'] and rows[0]['state']=='SUCCEEDED'
    bootstrap=json.loads((data/'bootstrap/v1/state.json').read_text())
    assert len(bootstrap['runs'])==1 and bootstrap['runs'][0]['phase']=='TERMINAL' and bootstrap['runs'][0]['outcome']=='ADMITTED'
    hashes={p.relative_to(world).as_posix():hashlib.file_digest(p.open('rb'),'sha256').hexdigest() for p in sorted(world.rglob('*')) if p.is_file() and p.name!='session.lock'}
    return {'source':SOURCE,'artifact':ARTIFACT,'archiveSha256':ARCHIVE_SHA,'world':header['world'],'worldHashes':hashes,
            'originalQualification':'FAIL','retainedBaselineNativeDeliveries':1,'nativeA05Effects':0,'metadata':metadata,'admissions':admission,'job':job}

def start(h,archive):
    from suite import ROOT,read,atomic,require
    import time
    seed=h.root/'source-pre-effect-seed';value=read_seed(archive,seed)
    game=h.root/'server/game';shutil.copytree(seed,game,ignore=shutil.ignore_patterns('session.lock'))
    actual={p.relative_to(game/'world').as_posix():hashlib.file_digest(p.open('rb'),'sha256').hexdigest() for p in sorted((game/'world').rglob('*')) if p.is_file()}
    require(actual==value['worldHashes'],'A05 continuation changed original world bytes before startup')
    override=bind_port(game/'server.properties',h.port)
    h.hashes=value['admissions']['artifacts'];h.admissions=value['admissions']['admissions']
    h.env['A05_DEPOSIT_ENABLED']='true';h.activated=int(time.time()*1000)
    h.compose('build','minecraft');h.start('server');h.start('PlayerA');h.start('PlayerB')
    bindings={}
    for player,principal in h.players.items():
        actor=h.wait(lambda:client_binding(read(h.root/'server/snapshot.json'),player,principal),'Original A05 citizen for '+player,60)
        h.citizens[player]=actor['citizenId'];h.entities[player]=actor['entityId'];bindings[player]=actor
        require(actor['citizenId'] in h.command(player,'/aivillage kernel citizens','citizens='),'A05 continuation private binding disagrees')
    h.wait(h.evidence,'Opt-in A05 reader after corrected native composition',60)
    require(h.evidence()['journal']['world']==value['world'],'A05 continuation changed world identity')
    current=read(h.root/'metadata.json');manifest={k:v for k,v in value.items() if k not in ('metadata','admissions','job')}
    manifest.update(newSource=current['sourceCommit'],newJarSha256=current['releaseSha256'],sourceJarSha256=value['metadata']['releaseSha256'],
            configurationOverride=override,clientBindings=bindings,newClientsAtRunBoundary=True,sameClientsFromHere=True,
            productionChange='A05 composition initializes after its existing Jobs owner; legacy storage formats remain unchanged')
    atomic(h.root/'seed-continuation.json',manifest);atomic(h.root/'independent-admissions.json',value['admissions'])
    return seed,manifest
