"""Offline Docker inspection of current first-H evidence and complete module results."""
import argparse,hashlib,json,xml.etree.ElementTree as ET
from pathlib import Path
from harvest_fixture import saved_harvest,SOURCE_TAG
from native_nbt import chunk
from review_receipt_prefix import dimension_root,block_name

def read(path):return json.loads(path.read_text())
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def manifest(world):
    return {p.relative_to(world).as_posix():sha(p) for p in sorted(world.rglob('*')) if p.is_file() and p.name!='session.lock'}
def modules(repo,expected=None,removal_cases=16):
    counts={}
    for name in ('core','providers','fabric'):
        rows=list((repo/name/'build/test-results/test').glob('TEST-*.xml'));assert rows,name
        count=[0,0,0,0]
        for path in rows:
            row=ET.parse(path).getroot()
            for n,key in enumerate(('tests','failures','errors','skipped')):count[n]+=int(row.get(key,0))
        counts[name]=count
    assert counts==(expected or {'core':[2268,0,0,0],'providers':[131,0,0,0],'fabric':[82,0,0,0]}),counts
    suite=ET.parse(repo/'core/build/test-results/test/TEST-dev.aivillages.core.kernel.HarvestLeasePublicationTest.xml').getroot()
    assert len(suite.findall('testcase'))==10 and not suite.findall('.//failure') and not suite.findall('.//error') and not suite.findall('.//skipped')
    if removal_cases:
        removal=ET.parse(repo/'fabric/build/test-results/test/TEST-dev.aivillages.fabric.runtime.evidence.EntityChunkRemovalStateTest.xml').getroot()
        assert len(removal.findall('testcase'))==removal_cases and not removal.findall('.//failure') and not removal.findall('.//error') and not removal.findall('.//skipped')
    if expected is None:
        for module,suite,number in [('core','dev.aivillages.core.kernel.runtime.evidence.NativePickupTest',20),
                                    ('core','dev.aivillages.core.kernel.runtime.evidence.HarvestContinuationTest',5),
                                    ('fabric','dev.aivillages.fabric.runtime.evidence.EntityMembershipTrailTest',8)]:
            scoped=ET.parse(repo/module/'build/test-results/test'/('TEST-'+suite+'.xml')).getroot()
            assert len(scoped.findall('testcase'))==number and not scoped.findall('.//failure') and not scoped.findall('.//error') and not scoped.findall('.//skipped')
    return counts
def participants(world,source):
    x,y,z=source['x'],source['y'],source['z']
    raw=chunk(dimension_root(world)/'region'/f'r.{x//512}.{z//512}.mca',x//16,z//16)
    metadata=json.loads(raw[SOURCE_TAG])
    return next(v for v in metadata['sources'] if (v['x'],v['y'],v['z'])==(x,y,z))
def settled(job,original,key,receipt):
    attempt=job['attempts'][-1]
    assert job['id']==original['id']==key['step'] and job['generation']==original['generation']==key['generation']
    assert job['state']=='CANCELLED' and job['children']==[] and job['allowance']==original['allowance']
    assert attempt['id']==key['attempt'] and attempt['generation']==key['generation'] and not attempt['uncertain']
    assert attempt['effects']==1 and attempt['credited']==0 and attempt['receipts']==[]
    assert attempt['worker']==original['attempts'][-1]['worker']
    assert attempt['allowance']==original['attempts'][-1]['allowance']
    assert attempt['executions']==original['attempts'][-1]['executions']
    actual=attempt['resourceReceipts'];assert len(actual)==1
    actual=actual[0]
    for saved,live in (('id','receiptId'),('run','runId'),('operation','operationId'),('batch','batchId')):
        assert receipt[saved]==actual[live],(saved,actual)
    assert actual['stage']=='HARVEST' and actual['quantity']==receipt['quantity'] and actual['dropIds']==receipt['drops']
    assert all(count<=attempt['allowance']['maxima'][kind] for kind,count in attempt['usage'].items())
def review(root,repo,out,source,expected_modules=None,removal_cases=16):
    out.mkdir(parents=True,exist_ok=True)
    result=read(root/'a06-harvest-results.json');metadata=read(root/'metadata.json');counts=modules(repo,expected_modules,removal_cases)
    assert metadata.get('reviewedHeadCommit',metadata['sourceCommit'])==source and metadata['dirty'] is False
    release=sha(repo/'fabric/build/libs/ai-villages-0.1.0.jar');observer=sha(repo/'build/e2e-driver/observer.jar')
    for base in [root/'server/game',*[root/'clients'/p/'game' for p in ('PlayerA','PlayerB')]]:
        assert sha(base/'mods/ai-villages-0.1.0.jar')==release
    assert sha(root/'server/game/mods/observer.jar')==observer
    value={'schema':1,'source':source,'modules':counts,'moduleCases':sum(v[0] for v in counts.values()),
           'releaseSha256':release,'observerSha256':observer,'status':'PASS_FAILURE_EVIDENCE_REVIEW_ONLY',
           'originalOwnerSettlements':0,'remainingOriginalCases':'NOT_EXECUTED_REQUIRED','a06Qualified':False,
           'nativeStatus':result['status'],'nativeReason':result.get('reason')}
    if result['status']=='FAIL':
        assert result['originalTwelveSettlements']==0
        last=result.get('lastEvidence') or {}
        value.update(lastPublication={'pending':last.get('pending'),'fenced':last.get('fenced')},
                     lastJournal=last.get('journal'),lastRuntime=last.get('runtime'),
                     claim='Current failure is retained. No intended saved boundary, native completion or owner settlement is inferred.')
    else:
        assert result['status']=='PASS_FIRST_H_PENDING_ARCHIVE_REVIEW' and result['originalTwelveSettlements']==1
        frozen=read(repo/'e2e/parallel_a/a06_original_cases.json')
        case=next(c for c in frozen['cases'] if c['id']=='heldout-W6-002')
        assert result['originalCase']==case and case['requested']==2
        killed=root/'saved-harvest-killed-world'
        assert manifest(killed)==result['killedWorldHashes']
        proof=saved_harvest(killed);assert proof==result['independentSavedNative']
        entry=proof['entry'];intent,observed=entry['intent'],entry['result'];key=intent['key'];receipt=observed['receipt']
        marker=result['reached']
        assert marker['point']=='RESULT_ACKNOWLEDGED' and marker['token']==result['arm']['token']
        assert marker['commands']==[{'command':'tick freeze','result':1},{'command':'save-all flush','result':1}]
        original=result['originalAtCut'];assert original['state']=='ACTIVE' and original['attempts'][-1].get('terminal') is None
        assert original['id']==key['step']==key['root'] and original['attempts'][-1]['id']==key['attempt'] and key['ordinal']==1
        assert original['request']['request']['arguments']['amount']['value']==2
        assert receipt['source']==[case['source'][k] for k in ('minX','minY','minZ','maxX','maxY','maxZ')]
        assert receipt['destination']==[case['destination'][k] for k in ('x','y','z')] and receipt['item']==case['item']
        assert intent['run']==receipt['run']==original['attempts'][-1]['executions'][0]['runId']
        before=result['beforeCancellation']
        assert before['id']==key['step'] and before['state']=='INTERRUPTED' and before['attempts'][-1]['uncertain']
        settled(result['originalAfterSettlement'],original,key,receipt)
        assert result['afterSettlementRestart']==result['originalAfterSettlement']
        verification=result['verified']['journal']['entries'][0]['verification'];assert verification['outcome']=='SAVED_COMPLETE'
        restart=result['settlementRestart'];assert restart['sameClients'] is True
        assert (restart['before']['hostJvmPid'],restart['before']['startTicks'])!=(restart['after']['hostJvmPid'],restart['after']['startTicks'])
        custody=result['finalCustody'];assert custody['destination']==0 and custody['actorInventory']=={}
        assert sorted(tuple(v) for v in custody['drops'])==sorted((d['entity'],d['quantity']) for d in observed['drops'])
        negative=result['negative'];assert negative['status']=='PASS_REFUSED'
        assert list(negative['native']['reconciliation'].values())==['TORN_SAVED_COHORT']
        assert not negative['native']['journal']['entries'][0].get('verification')
        blocked=negative['originalJob'];assert blocked['id']==key['step'] and blocked['state']!='CANCELLED'
        assert blocked['attempts'][-1]['id']==key['attempt'] and blocked['attempts'][-1]['uncertain']
        negative_world=root/'server/game/world';current=participants(negative_world,observed['source'])
        assert (current['incarnation'],current['revision'])!=(observed['source']['incarnation'],observed['source']['revision'])
        assert block_name(negative_world,[observed['source'][k] for k in ('x','y','z')])=='minecraft:air'
        value.update(status='PASS_FIRST_H_REVIEWED_ONLY',originalOwnerSettlements=1,key=key,nativeSavedProof=proof,
                     originalJob=result['originalAfterSettlement'],equalStateReplacement='PASS_RAW_NATIVE_MISMATCH_REFUSED',
                     claim='One original first-H cancellation is reviewed. Remaining eleven original cases, native P/D and cost qualification remain required.')
    (out/'review.json').write_text(json.dumps(value,indent=2)+'\n')
    print('A06_CURRENT_HARVEST_REVIEW '+json.dumps({k:value[k] for k in ('status','moduleCases','originalOwnerSettlements','a06Qualified','releaseSha256','observerSha256')}))
    return value
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,required=True);p.add_argument('--repo',type=Path,required=True)
    p.add_argument('--out',type=Path,required=True);p.add_argument('--source',required=True);a=p.parse_args()
    review(a.root,a.repo,a.out,a.source)
if __name__=='__main__':main()
