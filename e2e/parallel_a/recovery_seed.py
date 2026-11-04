"""Reuse the exact genuine, stopped three-material admission seed; no recovered case is imported."""
import hashlib,json,shutil,time
from pathlib import Path,PurePosixPath
from zipfile import ZipFile
from continuation import bind_port,client_binding

SOURCE='d1968aacaf2672854f78825e05c4f89698f35c02'
ARTIFACT=13311090608
ARCHIVE_SHA='8e3a030c078d0e1423b4342eae5105eed27c2e848619390a2f40e231a22d8ae8'
MATERIALS={'minecraft:oak_log','minecraft:birch_log','minecraft:wheat'}

def read_seed(archive,target):
    archive=Path(archive);target=Path(target)
    assert hashlib.file_digest(archive.open('rb'),'sha256').hexdigest()==ARCHIVE_SHA,'Admission seed archive identity changed'
    with ZipFile(archive) as z:
        names=z.namelist();assert len(names)==len(set(names)) and z.testzip() is None
        assert all(not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts for n in names)
        metadata=json.loads(z.read('metadata.json'));admission=json.loads(z.read('independent-admissions.json'))
        assert metadata['sourceCommit']==SOURCE and metadata['dirty'] is False and admission['nativeAdmissionsCountedAsA06']==0
        assert len(admission['admissions'])==3 and {a['item'] for a in admission['admissions']}==MATERIALS
        for a in admission['admissions']:
            view=a['view'];assert a['status']=='PASS' and view['responsibility']['state']=='SUCCEEDED' and view['marker']['outcome']=='ADMITTED'
            assert a['callsAfter']['generationCalls']>a['callsBefore']['generationCalls']
            assert view['marker']['artifactSha256'] in admission['artifacts'].values()
            field='receipts' if a['item']=='minecraft:wheat' else 'resourceReceipts';amount='wheat' if field=='receipts' else 'quantity'
            assert all(sum(r[amount] for r in view[field] if r['stage']==s)==1 for s in ('HARVEST','PICKUP','DEPOSIT'))
        prefix='genuinely-admitted-seed-game/'
        selected=[e for e in z.infolist() if not e.is_dir() and e.filename.startswith(prefix)]
        assert 1<=len(selected)<=512 and sum(e.file_size for e in selected)<128*1024**2
        target.mkdir(parents=True,exist_ok=False)
        for e in selected:
            assert (e.external_attr>>16)&0o170000!=0o120000
            path=target/PurePosixPath(e.filename).relative_to(prefix);path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(z.read(e))
    from deposits import manifest
    from review_receipt_prefix import journal
    world=target/'world';assert manifest(world)==admission['worldHashes']
    data=world/'data/cognitivecraft';assert not any((data/name).exists() for name in ('harvest-evidence','pickup-evidence','deposit-evidence'))
    header,rows=journal(data/'jobs/v1/state.jsonl');assert len(rows)==3 and all(j['kind']=='job' and j['state']=='SUCCEEDED' and all(not a['uncertain'] for a in j['attempts']) for j in rows)
    assert {j['id'] for j in rows}=={a['view']['responsibility']['id'] for a in admission['admissions']}
    return {'metadata':metadata,'admission':admission,'world':header['world'],'archiveSha256':ARCHIVE_SHA,'artifact':ARTIFACT}

def start(h,archive):
    from suite import ROOT,read,atomic,require
    seed=h.root/'genuinely-admitted-seed-game';value=read_seed(archive,seed);admission=value['admission']
    h.hashes=admission['artifacts'];h.admissions=admission['admissions'];h.companion=tuple(admission['unrelatedCompanion'])
    h.other_source=[326,208,66];h.other_destination=[333,208,72]
    shutil.copytree(ROOT/'build/needle',h.root/'needle',dirs_exist_ok=True)
    h.env.update(A06_HARVEST_ENABLED='false',A06_PICKUP_ENABLED='false',A05_DEPOSIT_ENABLED='false',A06_RECEIPT_PREFIX_ENABLED='false',COGNITIVECRAFT_NEEDLE_DIR='/evidence/needle')
    game=h.root/'server/game';shutil.copytree(seed,game,ignore=shutil.ignore_patterns('session.lock'));override=bind_port(game/'server.properties',h.port)
    h.compose('build','minecraft');h.start('server');h.start('PlayerA');h.start('PlayerB')
    selected=h.admissions[0]['view']['responsibility']['attempts'][-1]['worker']['citizenId'];bindings={}
    for player in ('PlayerA','PlayerB'):
        actor=h.wait(lambda:client_binding(h.snapshot(),player,h.players[player],selected if player=='PlayerA' else None),'Exact retained admission citizen '+player,60)
        h.citizens[player]=actor['citizenId'];h.entities[player]=actor['entityId'];bindings[player]=actor
        require(actor['citizenId'] in h.command(player,'/aivillage kernel citizens','citizens='),'Retained admission private binding changed')
    h.command('PlayerA','/aivillage kernel inference false','admission=false')
    require(not h.inventory(),'Retained admission actor has unexpected custody')
    h.save_checkpoint();h.stop('server')
    current=read(h.root/'metadata.json');continuation={'kind':'EXACT_THREE_GENUINE_NATIVE_ADMISSIONS','source':SOURCE,'artifact':ARTIFACT,'archiveSha256':ARCHIVE_SHA,
        'startingWorld':value['world'],'worldHashes':admission['worldHashes'],'priorReleaseSha256':value['metadata']['releaseSha256'],'currentReleaseSha256':current['releaseSha256'],
        'currentSource':current['sourceCommit'],'configurationOverride':override,'clientBindings':bindings,'importedA06Settlements':0,'allTwelveMustRunOnCurrentCandidate':True}
    atomic(h.root/'seed-continuation.json',continuation);admission['seedContinuation']=continuation;atomic(h.root/'independent-admissions.json',admission)
    return seed
