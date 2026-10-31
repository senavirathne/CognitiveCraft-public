"""Independent frozen C05 selector oracle on raw production-JAR driver observations."""
import hashlib
import importlib.util
import os
from pathlib import Path
import shutil
import struct
import subprocess

from common import HERE, JAR, ROOT, check, digest, identity, read, write

CAMPAIGN = HERE / 'selector-variations.json'
CAMPAIGN_SHA256 = '12c71e9ab374ac472c5a32a7567a1f9c091c20db170be5434e8e6be476c06ff7'
PREDECESSOR_JAR = '459dc4e969873ecbd142d44a5de20bc5319673f7622ac12342398902100a33ab'


def check_frames(observed, campaign):
    check(observed['tier'] == 'SYNTHETIC_SELECTOR_VARIATIONS', 'selector tier changed')
    check(observed['native_executed'] == observed['client_executed'] == 0, 'synthetic selectors cannot claim physical execution')
    expected, actual = campaign['cases'], observed['cases']
    check(len(expected) == len(actual) == 100, '100 frozen actual selector cases required')
    check(len({r['id'] for r in actual}) == 100, 'duplicate selector case')
    spec=importlib.util.spec_from_file_location('selector_contract',HERE/'contracts/verify_contract.py')
    contract=importlib.util.module_from_spec(spec);spec.loader.exec_module(contract);schema=read(HERE/'contracts/contract.schema.json')
    durations=[]
    for want,row in zip(expected,actual):
        check(all(row[k] == want[k] for k in ('id','seed','text')), 'selector schedule identity changed')
        check(row['disposition'] == 'CANDIDATE' and row['routing'] == 'RESOLVE', 'held-out selector did not produce an inert candidate')
        wire=row['intent'];contract.shape(wire,schema,schema);contract.semantics('DialogueIntent',wire['body'])
        check(wire['schema'] == 'cc.cd/1.0.0' and wire['kind'] == 'DialogueIntent', 'unexpected selector protocol')
        body=wire['body'];check(body['utterance'] == want['text'], 'transport text changed')
        for field,value in want['expected'].items(): check(body[field] == value, 'selector observable mismatch: '+want['id']+' '+field)
        check(all(body[k] is None for k in ('label','role','requested_access')), 'selector added metadata edits')
        check(isinstance(row['elapsed_ns'],int) and row['elapsed_ns'] > 0, 'missing measured selector interval');durations.append(row['elapsed_ns'])
    durations.sort()
    return dict(status='PASS',planned=100,executed=100,passed=100,failed=0,
                latency_ns=dict(p50=durations[49],p95=durations[94],p99=durations[98],max=durations[-1]),
                heap_before=observed['heap_before'],heap_after=observed['heap_after'],
                scope='SYNTHETIC_CANDIDATES_ONLY; J04 resolves native identities and revalidates effect authorization')


def verify(folder, registry=None):
    folder=Path(folder);receipt=read(folder/'process.json');observed=read(folder/'frames.json')
    check(digest(CAMPAIGN) == receipt['campaign_sha256'] == CAMPAIGN_SHA256, 'selector held-out schedule changed')
    check(receipt['exit'] == 0 and receipt['jar_sha256'] == observed['production_jar_sha256'] == digest(JAR), 'selector driver did not load exact release JAR')
    check(receipt['driver_classes'], 'missing selector driver bytecode')
    for path,sha in receipt['driver_classes'].items():
        data=(folder/path).read_bytes();check(digest(folder/path)==sha and data[:4]==b'\xca\xfe\xba\xbe' and struct.unpack_from('>H',data,6)[0]==69,'selector driver bytecode/Java 25 mismatch')
    campaign=read(CAMPAIGN)
    if registry is not None: check(registry['selector_variation_cases']==[{k:r[k] for k in ('id','seed')} for r in campaign['cases']], 'selector registry changed')
    before=read(folder/'baseline-frames.json');baseline=read(folder/'baseline-process.json')
    check(baseline['exit'] == 0 and baseline['jar_sha256'] == before['production_jar_sha256'] == PREDECESSOR_JAR, 'selector unchanged C04 comparison missing')
    check([(r['id'],r['text']) for r in before['cases']]==[(r['id'],r['text']) for r in campaign['cases']], 'baseline schedule differs')
    result=check_frames(observed,campaign)
    times=sorted(r['elapsed_ns'] for r in before['cases'])
    result['unchanged_C04_comparison']=dict(jar_sha256=PREDECESSOR_JAR,executed=len(times),candidate=sum(r['disposition']=='CANDIDATE' for r in before['cases']),
         latency_ns=dict(p50=times[49],p95=times[94],p99=times[98],max=times[-1]),heap_before=before['heap_before'],heap_after=before['heap_after'],
         qualification_credit=False,performance_threshold='No per-tick work or new performance gate; raw cold-JVM schedule measures grammar expansion, not equivalent native workloads')
    return result


def run(folder):
    folder=Path(folder);folder.mkdir(parents=True,exist_ok=False)
    classes=folder/'driver-classes/dev/aivillages/core/kernel/interaction';classes.mkdir(parents=True)
    sources=list((ROOT/'core/build/classes/java/test/dev/aivillages/core/kernel/interaction').glob('SelectorVariationProbe*.class'));check(sources,'compiled selector probe missing')
    for source in sources: shutil.copy2(source,classes/source.name)
    java=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    def execute(jar,frame,receipt):
        command=[java,'-cp',os.pathsep.join([str(jar),str(folder/'driver-classes')]),'dev.aivillages.core.kernel.interaction.SelectorVariationProbe',str(CAMPAIGN)]
        with (folder/frame).open('w') as out,(folder/(frame+'.stderr.log')).open('w') as err: result=subprocess.run(command,cwd=ROOT,stdout=out,stderr=err,timeout=60)
        write(folder/receipt,{**identity(),'exit':result.returncode,'command':command,'jar_sha256':digest(jar),'campaign_sha256':digest(CAMPAIGN),
            'driver_classes':{p.relative_to(folder).as_posix():digest(p) for p in classes.glob('*.class')}})
        check(result.returncode==0,'selector probe failed; inspect '+str(folder/(frame+'.stderr.log')))
    # The exact C04 release is already present in the verified snapshot cache or prior artifact.
    # Fetching a predecessor binary here would add an unverified execution input: CI extracts the
    # exact committed comparison JAR retained alongside this harness instead.
    baseline=HERE/'selector-baseline-C04.jar'
    check(digest(baseline)==PREDECESSOR_JAR,'unchanged C04 comparison JAR missing/changed')
    execute(baseline,'baseline-frames.json','baseline-process.json')
    execute(JAR,'frames.json','process.json')
    summary=verify(folder);write(folder/'summary.json',summary);return summary
