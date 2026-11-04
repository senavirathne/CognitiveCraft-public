"""Offline inspection of all twelve native cohorts, their original owners and complete test results."""
import argparse,json,traceback,xml.etree.ElementTree as ET
from pathlib import Path
from recovery_cases import originals,settled,complete,STAGES
from recovery_fixture import native_saved
from review_harvest_current import sha,manifest
from review_receipt_prefix import journal
from review_storage_mechanics import review as storage_review

def read(path):return json.loads(path.read_text())

def modules(repo):
    counts={}
    for module,minimum in (('core',2300),('providers',131),('fabric',110)):
        paths=list((repo/module/'build/test-results/test').glob('TEST-*.xml'));assert paths,('Missing module results',module)
        rows=[ET.parse(path).getroot() for path in paths]
        totals=[sum(int(r.get(k,0)) for r in rows) for k in ('tests','failures','errors','skipped')]
        assert totals[0]>=minimum and totals[1:]==[0,0,0],(module,totals)
        assert all(not r.findall('.//'+tag) for r in rows for tag in ('failure','error','skipped'))
        assert sum(len(r.findall('testcase')) for r in rows)==totals[0]
        counts[module]=totals
    required=[('core','dev.aivillages.core.kernel.CropHarvestGatewayTest',2),
              ('core','dev.aivillages.core.kernel.PickupDelayGatewayTest',2),
              ('core','dev.aivillages.core.kernel.PickupGatewayTest',8),
              ('core','dev.aivillages.core.kernel.runtime.evidence.NativeCropHarvestTest',6),
              ('core','dev.aivillages.core.kernel.runtime.evidence.NativeJoinedDepositTest',9),
              ('core','dev.aivillages.core.kernel.HarvestLeasePublicationTest',10),
              ('fabric','dev.aivillages.fabric.runtime.evidence.EntityTrailRecordTest',5),
              ('fabric','dev.aivillages.fabric.runtime.evidence.EntityChunkMembershipStateTest',7),
              ('fabric','dev.aivillages.fabric.runtime.evidence.EntityChunkRemovalStateTest',16)]
    for module,suite,number in required:
        tree=ET.parse(repo/module/'build/test-results/test'/('TEST-'+suite+'.xml')).getroot()
        assert len(tree.findall('testcase'))==number,(suite,'Required regression omitted')
    return counts

def original_row(world,key):
    _,rows=journal(world/'data/cognitivecraft/jobs/v1/state.jsonl')
    matches=[j for j in rows if j.get('id')==key['step']];assert len(matches)==1
    job=matches[0];a=job['attempts'][-1]
    assert job['generation']==a['generation']==key['generation'] and a['id']==key['attempt']
    return job,a

def replaced(value):
    assert value['sameClients'] is True
    assert tuple(value['before'][k] for k in ('hostJvmPid','startTicks'))!=tuple(value['after'][k] for k in ('hostJvmPid','startTicks'))

def one(root,case):
    directory=root/'original-cases'/case['id'];result=read(directory/'result.json')
    assert result['originalCase']==case and result['status']=='PASS_ORIGINAL_PENDING_INDEPENDENT_REVIEW'
    assert result['nativeOriginalOwnerSettlements']==1
    killed,terminal=(directory/name for name in ('killed-world','terminal-world'))
    assert manifest(killed)==result['killedWorldHashes'] and manifest(terminal)==result['terminalWorldHashes']
    before=native_saved(killed,case['checkpoint'],case);after=native_saved(terminal,case['checkpoint'],case)
    assert before==result['nativeSavedProof'] and before['key']==after['key']==result['key']
    assert {k:v for k,v in before['native'].items() if k!='memberships'}=={k:v for k,v in after['native'].items() if k!='memberships'}
    key=before['key'];cut=result['reached'];arm=result['arm']
    assert cut['stage']==arm['stage']==case['checkpoint'] and cut['point']==arm['point']=='RESULT_ACKNOWLEDGED' and cut['token']==arm['token']
    assert cut['commands']==[{'command':'tick freeze','result':1},{'command':'save-all flush','result':1}]
    original=result['originalAtCut'];assert original['state']=='ACTIVE' and original['attempts'][-1].get('terminal') is None
    raw_cut,cut_attempt=original_row(killed,key)
    assert raw_cut['state']=='ACTIVE' and cut_attempt.get('terminal') is None
    interrupted=result['beforeCancellation'];assert interrupted['state']=='INTERRUPTED' and interrupted['attempts'][-1]['uncertain']
    owner=settled(case,original,result['originalAfterSettlement'],key);assert owner==result['owner']
    assert result['afterTerminalRestart']==result['originalAfterSettlement']
    raw_job,raw_attempt=original_row(terminal,key);effects,credit=STAGES[case['checkpoint']]
    assert raw_job['state']=='CANCELLED' and raw_job['children']==[] and raw_job['cancellationOutcome']=='CANCELLED'
    assert not raw_attempt['uncertain'] and raw_attempt['effects']==effects and raw_attempt['credited']==credit
    assert raw_job['allowance']==raw_cut['allowance'] and raw_attempt['allowance']==cut_attempt['allowance']
    assert raw_attempt['executions']==cut_attempt['executions'] and raw_attempt['worker']==cut_attempt['worker']
    assert raw_attempt['terminal']['status']=='CANCELLED'
    # Native journal receipt IDs must be the original owner's actual accounting, including legacy wheat.
    if case['item']=='minecraft:wheat':
        assert len(raw_attempt['receipts'])==1 and raw_attempt['receipts'][0]['id']==before['entry']['result']['cropReceipt']['receipt']
        assert not raw_attempt.get('resourceReceipts',[])
    else:
        receipts=[before['harvest']['result']['receipt']]
        if effects>=2:receipts.append(before['pickup']['result']['receipt'])
        if effects==3:receipts.append(before['deposit']['result']['receipt'])
        assert [r['id'] for r in raw_attempt['resourceReceipts']]==[r['id'] for r in receipts]
        assert [r['stage'] for r in raw_attempt['resourceReceipts']]==list(STAGES)[:effects]
    verification=after['entry']['verification'];assert verification['outcome']=='SAVED_COMPLETE'
    assert not any(l['owner']['job']==key['step'] and l['state']=='ACTIVE' for l in result['claimsAfterSettlement']['runtime']['leases']['leases'])
    replaced(result['firstRestart']);replaced(result['terminalRestart'])
    if case['id']=='W6-003':
        replaced(result['adoptionRestart']);replaced(result['settlementCutRestart'])
        assert result['verificationCut']['stage']=='PICKUP' and result['verificationCut']['point']=='VERIFICATION_ACKNOWLEDGED'
        assert manifest(directory/'verification-killed-world')
        pending,_=original_row(directory/'verification-killed-world',key)
        assert pending['state']!='CANCELLED','Settlement re-kill missed the pre-publication boundary'
    assert result['modelCallsBefore']==result['modelCallsAfter']
    return {'originalCase':case,'status':'PASS_INDEPENDENT_ORIGINAL_REVIEW','key':key,'owner':owner,
            'nativeCustodyNoReplay':True,'originalOwnerSettled':True,'nativeProof':{'before':before,'after':after}}

def review(root,repo,out,source):
    out.mkdir(parents=True,exist_ok=True);value={'schema':1,'source':source,'status':'FAIL_REQUIRED_ORIGINAL_REVIEW','originalOwnerSettlements':0,'a06Qualified':False}
    reviews=[];failures=[]
    try:
        cases=originals(repo/'e2e/parallel_a/a06_original_cases.json');plan=read(root/'a06-original-results.json')
        assert plan['plannedCases']==[c['id'] for c in cases] and len(plan['cases'])==12
        metadata=read(root/'metadata.json');assert metadata.get('reviewedHeadCommit',metadata['sourceCommit'])==source and metadata['dirty'] is False
        counts=modules(repo);release=sha(repo/'fabric/build/libs/ai-villages-0.1.0.jar');observer=sha(repo/'build/e2e-driver/observer.jar')
        for game in (root/'server/game',*(root/'clients'/p/'game' for p in ('PlayerA','PlayerB'))):assert sha(game/'mods/ai-villages-0.1.0.jar')==release
        assert sha(root/'server/game/mods/observer.jar')==observer
        admissions=read(root/'independent-admissions.json');assert admissions['nativeAdmissionsCountedAsA06']==0
        assert {a['item'] for a in admissions['admissions'] if a['status']=='PASS'}=={'minecraft:oak_log','minecraft:birch_log','minecraft:wheat'}
        for admission in admissions['admissions']:
            assert admission['callsAfter']['generationCalls']>admission['callsBefore']['generationCalls']
            assert admission['view']['marker']['outcome']=='ADMITTED'
        for case in cases:
            try:
                row=one(root,case);reviews.append(row)
                result=read(root/'original-cases'/case['id']/'result.json')
                assert result['originalAtCut']['attempts'][-1]['executions'][0]['artifact']['sha256']==admissions['artifacts'][case['capability']['name']]
            except Exception as error:failures.append({'id':case['id'],'reason':str(error),'trace':traceback.format_exc()})
        value.update(modules=counts,moduleCases=sum(v[0] for v in counts.values()),releaseSha256=release,observerSha256=observer,
                     individuallyReviewedOriginalCases=[r['originalCase']['id'] for r in reviews],nativeStorageMechanics=storage_review(root))
        if not failures:
            assert plan['status']=='PASS_ALL_TWELVE_PENDING_INDEPENDENT_REVIEW' and plan['originalOwnerSettlements']==12
            value.update(complete(cases,reviews))
            value['claim']='Twelve saved original-owner settlements independently reviewed. Wider controls and full branch qualification are separate required gates.'
    except Exception as error:failures.append({'id':'campaign','reason':str(error),'trace':traceback.format_exc()})
    value.update(failures=failures,reviews=reviews)
    (out/'original-cases-review.json').write_text(json.dumps(value,indent=2)+'\n')
    print('A06_ALL_ORIGINAL_REVIEW '+json.dumps({k:v for k,v in value.items() if k not in ('reviews','nativeStorageMechanics')}),flush=True)
    if failures:raise SystemExit(1)
    return value

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('root','repo','out'):p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--source',required=True);a=p.parse_args();review(a.root,a.repo,a.out,a.source)
if __name__=='__main__':main()
