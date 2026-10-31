"""Independent invariants and transition expectations on actual production-JAR C04 reducer frames."""
import importlib.util
import os
from pathlib import Path
import shutil
import struct
import subprocess

from common import HERE, JAR, ROOT, check, digest, identity, read, write
from blocker_runtime import dependency

CAMPAIGN = HERE / "dialogue-sequences.json"
TERMINAL = {"EXPIRED", "CANCELLED", "CLOSED"}


def check_frames(observed, campaign):
    check(observed.get("tier") == "SYNTHETIC_DIALOGUE_SEQUENCES", "wrong dialogue execution tier")
    expected = campaign["sequences"]; actual = observed["sequences"]
    check(len(actual) == len(expected) == 100, "one hundred predeclared sequences required")
    check(len({r['seed'] for r in expected}) == len({r['id'] for r in actual}) == 100, "duplicate sequence seed/identity")
    spec = importlib.util.spec_from_file_location("dialogue_contract", HERE / "contracts/verify_contract.py")
    contract = importlib.util.module_from_spec(spec); spec.loader.exec_module(contract)
    schema = read(HERE / "contracts/contract.schema.json")
    signals = deliveries = decisions = 0
    for want, row in zip(expected, actual):
        check((want['id'], want['seed']) == (row['id'], row['seed']), "sequence identity/seed changed")
        check(len(want['events']) == len(row['frames']) == 20, "twenty actual transition frames required")
        original = row['frames'][0]['before']['question']['body']['activity']
        audience = row['frames'][0]['before']['audience']; question = row['frames'][0]['before']['question']['body']['question_id']
        previous = None; pending_reads = set(); proposed_deliveries = set()
        for step, (event, frame) in enumerate(zip(want['events'], row['frames']), 1):
            check(frame['step'] == step and frame['event'] == event, "event ordering changed")
            before, after, proposals = frame['before'], frame['after'], frame['proposals']
            if previous is not None: check(before == previous, "sequence discontinuity")
            previous = after
            for state in (before, after):
                body = state['question']['body']
                contract.shape(state['question'], schema, schema); contract.semantics('AssistanceRequest', body)
                check(body['activity'] == original and body['question_id'] == question and state['audience'] == audience, "root/owner/attempt/generation/budget/deadline/audience mutated")
                check(body['expires_ms'] <= original['absolute_deadline_ms'], "question extended original deadline")
                check(0 <= state['reply_rounds'] == len(set(state['consumed'])) == len(state['consumed']) <= 8, "reply replay/budget bound")
                check(len(set(state['delivered'])) == len(state['delivered']) <= 16, "delivery replay/budget bound")
                check(0 <= state['identical_checks'] <= 3 and (not state['waiting_change'] or state['identical_checks'] == 3), "unchanged observation bound")
                check(state['next_check_ms'] <= body['expires_ms'], "backoff outlives question/root")
                check((state['observation'] is not None) == (state['phase'] == 'REOBSERVE_REQUESTED'), "observation correlation/phase mismatch")
            check(set(before['consumed']).issubset(after['consumed']) and set(before['delivered']).issubset(after['delivered']), "replay ledger reset")
            check(after['response_generation'] >= before['response_generation'], "response generation regressed")
            check(after['question']['body']['dialogue_revision'] >= before['question']['body']['dialogue_revision'], "dialogue revision regressed")
            check(len(proposals) <= 1, "unbounded proposal fanout")
            if before['phase'] in TERMINAL: check(after == before and not proposals, "terminal question replayed")
            if frame['before_restore_locked']: check(not proposals, "restored state emitted a proposal before A revalidation")
            if event in ('WRONG_PLAYER','WRONG_ROOT','LATE_MODEL','DUPLICATE_REPLY','RESTORE','REVALIDATE','EXPIRE','NATIVE_CHANGE'):
                check(not proposals, "invalid/replayed/restored event emitted a proposal")
            if event == 'RESTORE': check(frame['after_restore_locked'], "restore omitted mandatory native lock")
            for p in proposals:
                kind = p['kind']; check(kind in ('REOBSERVE','PREPARE_PROMPT','DELIVER_PROMPT','OWNER_DECISION'), "world effect proposal exposed")
                if kind == 'REOBSERVE':
                    signal = p['signal']; contract.shape(signal, schema, schema); contract.semantics('ActivityResumeSignal',signal['body'])
                    body=signal['body']; check(body['activity'] == original and body['request_kind'] == 'REVALIDATE_ONLY', "resume signal changed original activity or granted effect authority")
                    check(body['question_id'] == question and body['dialogue_revision'] == before['question']['body']['dialogue_revision'], "signal/question mismatch")
                    check(body['observations'] == [before['question']['body']['blocker']['evidence_id']] and body['observed_world_revision'] == before['world_revision'], "invented observation")
                    check(p['request'] not in pending_reads and after['observation'] == p['request'], "replayed reobservation proposal")
                    pending_reads.add(p['request']); signals += 1
                elif kind == 'DELIVER_PROMPT':
                    key = (p['message'],p['response_generation']); check(key not in proposed_deliveries, "duplicate delivery proposal")
                    check(p['message'] == before['prompt'] and p['response_generation'] == before['response_generation'], "late model delivered")
                    proposed_deliveries.add(key); deliveries += 1
                elif kind == 'OWNER_DECISION':
                    check(p['activity'] == original and after['phase'] == 'WAITING_OWNER_DECISION', "owner proposal mutated root or skipped A")
                    check(p['action'] in ('CANCEL','CHOOSE_TARGET','AMEND'), "language granted continuation")
                    if p['action'] == 'AMEND': check(event == 'CONFIRM' and before['phase'] == 'CLARIFICATION', "unconfirmed goal mutation")
                    decisions += 1
            if step == 4:
                check(event == 'CHECK_AGAIN' and after['phase'] == 'REOBSERVE_REQUESTED' and len(proposals) == 1, "valid control never exercised reobservation")
            if step == 5:
                check(event == 'UNCHANGED' and after['phase'] == 'AWAITING_REPLY' and after['identical_checks'] == 1, "unchanged observation got continuation")
                requirements=after['question']['body']['blocker']['requirements']
                check([(r['item']['registry_id'],r['missing']) for r in requirements] == [('minecraft:oak_planks',2)], "unchanged chest remainder drift")
            if event in ('UNCHANGED','PARTIAL','SUFFICIENT') and before['phase'] == 'REOBSERVE_REQUESTED' and not frame['before_restore_locked']:
                if event == 'SUFFICIENT': check(after['phase'] == 'WAITING_OWNER_DECISION' and not proposals, "sufficient observation skipped A or emitted a second signal")
                else:
                    check(after['phase'] == 'AWAITING_REPLY', "shortage wrongly resumed")
                    check(after['question']['body']['blocker']['requirements'][0]['missing'] == (2 if event == 'UNCHANGED' else 1), "native remainder wrong")
    return dict(status='PASS', planned=100, executed=100, passed=100, failed=0, event_count=2000,
                reobservation_proposals=signals, delivery_proposals=deliveries, owner_decision_proposals=decisions,
                scope='SYNTHETIC_DIALOGUE_AND_B_PLANNER; no native/client/settlement credit')


def verify(folder, registry=None):
    folder=Path(folder); receipt=read(folder/'process.json'); observed=read(folder/'frames.json'); campaign=read(CAMPAIGN)
    check(receipt['exit'] == 0 and receipt['jar_sha256'] == observed['production_jar_sha256'] == digest(JAR), 'dialogue driver/JAR/exit mismatch')
    check(receipt['campaign_sha256'] == digest(CAMPAIGN), 'dialogue campaign changed')
    check(receipt['driver_classes'], 'missing actual dialogue driver classes')
    for path, sha in receipt['driver_classes'].items():
        data=(folder/path).read_bytes(); check(digest(folder/path) == sha and data[:4] == b'\xca\xfe\xba\xbe' and struct.unpack_from('>H',data,6)[0] == 69,'dialogue driver bytecode changed/not Java 25')
    if registry is not None:
        check(registry['dialogue_sequence_cases'] == [{k:r[k] for k in ('id','seed')} for r in campaign['sequences']], 'sequence registry changed')
    return check_frames(observed,campaign)


def run(folder):
    folder=Path(folder); folder.mkdir(parents=True,exist_ok=False)
    classes=folder/'driver-classes/dev/aivillages/core/kernel/interaction'; classes.mkdir(parents=True)
    for name in ('DialogueSequenceProbe','DialogueFixtures','AssistanceFixtures'):
        sources=list((ROOT/'core/build/classes/java/test/dev/aivillages/core/kernel/interaction').glob(name+'*.class'))
        check(sources,'missing compiled dialogue driver: '+name)
        for source in sources: shutil.copy2(source,classes/source.name)
    java=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    cp=os.pathsep.join([str(JAR),str(folder/'driver-classes'),dependency()['path']])
    command=[java,'-cp',cp,'dev.aivillages.core.kernel.interaction.DialogueSequenceProbe',str(CAMPAIGN)]
    with (folder/'frames.json').open('w') as out, (folder/'stderr.log').open('w') as err:
        result=subprocess.run(command,cwd=ROOT,stdout=out,stderr=err,timeout=120)
    write(folder/'process.json',{**identity(),'exit':result.returncode,'command':command,'jar_sha256':digest(JAR),'campaign_sha256':digest(CAMPAIGN),
        'driver_classes':{p.relative_to(folder).as_posix():digest(p) for p in classes.glob('*.class')}})
    check(result.returncode == 0,'dialogue driver failed: '+(folder/'stderr.log').read_text()[-4000:])
    totals=verify(folder); write(folder/'summary.json',totals); return totals
