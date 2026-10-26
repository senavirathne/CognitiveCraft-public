"""Authentic stopped-world certificates for bounded, independent physical test fixtures.

Every certificate comes after real gameplay admission and acknowledged owner cleanup.
Restoration copies the complete native world byte for byte; no journals, skills or identities
are synthesized or removed. Fixture restores are recorded separately from tested recovery.
"""
import hashlib
from pathlib import Path
import shutil
import uuid

from runtime import ROOT,digest,semantic_jar
from suite import atomic,read,require,reconnect_players
from generate import BASELINE


def hashes(world):
    return [{'path':str(p.relative_to(world)),'bytes':p.stat().st_size,'sha256':digest(p)}
            for p in sorted(world.rglob('*')) if p.is_file() and p.name!='session.lock']


def save(h,name):
    """Capture an intact, genuinely admitted native fixture after the server has stopped."""
    previous=(h.case,h.case_dir,h.family)
    h.case=None; h.case_dir=None
    try:
        h.cleanup_world(); h.save_checkpoint()
        connected=[p for p in ('PlayerA','PlayerB') if p in h.booted]
        processes={p:read(h.root/'clients'/p/'process.json') for p in connected}
        h.stop('server')
        for p in connected: h.wait(lambda p=p:not h.action(p,'snapshot')['play'],'Stopped certificate server disconnected '+p,40)
        directory=h.root/'certificates'/name
        require(not directory.exists(),'New native certificate directory required')
        world=h.root/'server/game/world'; files=hashes(world)
        shutil.copytree(world,directory/'world',ignore=shutil.ignore_patterns('session.lock'))
        require(all(digest(directory/'world'/r['path'])==r['sha256'] for r in files),'Native certificate copy changed bytes')
        proof={'schema':1,'productionBaseline':BASELINE,'semanticJarSha256':semantic_jar(),
            'sourceCommit':read(h.root/'metadata.json')['sourceCommit'],'files':files,
            'citizens':dict(h.citizens),'entities':dict(h.entities),'methods':dict(h.hashes),
            'actorPool':list(getattr(h,'actor_pool',None) or []),
            'foreignMethod':getattr(h,'foreign_method',None),
            'foreignCitizen':getattr(h,'foreign_citizen',None),'foreignEntity':getattr(h,'foreign_entity',None),
            'competitionCitizen':getattr(h,'competition_citizen',None),'competitionEntity':getattr(h,'competition_entity',None),
            'boundary':'Acknowledged native save and genuinely stopped server; no synthesized admission or journal edits',
            'captureFamily':getattr(h,'resource_cohort',None),'persistentClients':processes}
        atomic(directory/'certificate.json',proof)
        h.current_certificate=directory
        h.event('native-fixture-certificate',{'path':str(directory.relative_to(h.root)),
            'sha256':digest(directory/'certificate.json'),'methods':proof['methods'],'files':len(files)})
        h.start('server'); reconnect_players(h,connected)
        require(all(read(h.root/'clients'/p/'process.json')==processes[p] for p in connected),
                'Capturing a certificate replaced a persistent client process')
        return directory
    finally:
        h.case,h.case_dir,h.family=previous; h.samples=[]; h.last_sample=None


def restore(h,directory,quarantine=False):
    directory=Path(directory).resolve(); proof=read(directory/'certificate.json')
    require(proof and proof['productionBaseline']==BASELINE,'Expected a campaign native certificate')
    require(proof['semanticJarSha256']==semantic_jar(),'Certificate crossed a production code change')
    require(all(digest(directory/'world'/r['path'])==r['sha256'] for r in proof['files']),
            'Original native certificate integrity failed')
    h.case=None; h.case_dir=None
    connected=[p for p in ('PlayerA','PlayerB') if p in h.booted]
    processes={p:read(h.root/'clients'/p/'process.json') for p in connected}
    if 'server' in h.booted:
        if not quarantine: h.cleanup_world()
        else:
            h.event('quarantine-unsettled-native-world',{'reason':'Public owner cancellation did not settle; preserve the complete old game and use a distinct certified fixture'})
        h.stop('server'); h.actions_active=False
        for p in connected: h.wait(lambda p=p:not h.action(p,'snapshot')['play'],'Fixture isolation disconnected '+p,40)
    game=h.root/'server/game'
    if game.exists(): game.rename(h.root/('saved-game-isolation-'+str(uuid.uuid4())[:8]))
    shutil.copytree(directory/'world',game/'world',ignore=shutil.ignore_patterns('session.lock'))
    require(all(digest(game/'world'/r['path'])==r['sha256'] for r in proof['files']),'Restored fixture changed native bytes')
    h.citizens=dict(proof['citizens']); h.entities=dict(proof['entities']); h.hashes=dict(proof['methods'])
    h.actor_pool=[tuple(r) for r in proof.get('actorPool',[])]
    attempted=getattr(h,'foreign_admission_attempted',False)
    h.foreign_method=proof.get('foreignMethod'); h.foreign_admission_attempted=bool(h.foreign_method) or attempted
    h.foreign_citizen=proof.get('foreignCitizen'); h.foreign_entity=proof.get('foreignEntity')
    h.competition_citizen=proof.get('competitionCitizen'); h.competition_entity=proof.get('competitionEntity')
    h.current_certificate=directory; h.cohort_case_count=0
    h.fixture_cohort=str(uuid.uuid4())
    h.start('server')
    if connected: reconnect_players(h,connected)
    else: h.start('PlayerA')
    h.ensure_client()
    h.console(['op PlayerA','tp PlayerA 66.5 208 69.5'])
    h.wait(lambda:any(a['uuid']==h.entities['PlayerA'] and a['alive'] for a in h.snapshot()['actors']),
           'Original live native certificate subject loaded',60)
    require(all(read(h.root/'clients'/p/'process.json')==processes[p] for p in connected),
            'Fixture isolation replaced a persistent client process')
    h.command('PlayerA','/aivillage kernel inference false','admission=false')
    h.event('explicit-native-fixture-isolation',{'fixture':h.fixture_cohort,'certificate':str(directory),
        'certificateSha256':digest(directory/'certificate.json'),'methods':h.hashes,
        'purpose':'Separate test fixture, never credited as gameplay recovery or identity revival'})


def resources(family):
    if family=='W7': return ('minecraft:oak_log',)
    if family=='W9': return ('minecraft:wheat',)
    if family in ('W1','W2','W3','W4','W6'): return ('minecraft:wheat','minecraft:oak_log','minecraft:birch_log')
    return ()


def begin(h,family):
    h.case=None; h.case_dir=None; h.family='COHORT-'+family
    if 'server' in h.booted: h.cleanup_world()
    h.hashes={}; h.actor_pool=[]
    h.foreign_method=None; h.foreign_admission_attempted=False
    h.foreign_citizen=None; h.foreign_entity=None; h.competition_citizen=None; h.competition_entity=None
    h.current_certificate=None; h.resource_cohort=family; h.cohort_case_count=0; h.fixture_cohort=str(uuid.uuid4())
    h.bootstrap_world(resources=resources(family))
    if resources(family): save(h,family+'-admitted')
    h.case=None; h.case_dir=None
