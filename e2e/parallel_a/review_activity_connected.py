"""Independent A09 raw-world and canonical Jobs reader, run read-only with networking disabled."""
import argparse,json,traceback
from pathlib import Path
from activity_cases import CASES,REGRESSIONS,complete
from deposits import manifest
from review_lifecycle_connected import runtime_proof,restart_proof,original,entities,chest
from review_claims_connected import inventory
from review_receipt_prefix import journal,block_name
from recovery_fixture import native_saved
from review_recovery_current import one as review_original,review_control
from baseline import percentiles

LOG='minecraft:oak_log'
def read(path):return json.loads(Path(path).read_text())
def physical(world,fixture):
    native=entities(world);actor=native[fixture['actor']]
    assert actor['id']=='minecraft:villager' and actor.get('Health',1)>0
    boxes=chest(world,fixture['destination']);held=inventory(actor)
    assert not any(e.get('id')=='minecraft:item' and e.get('Item',{}).get('id')==LOG for e in native.values())
    return {'destination':boxes.get(LOG,0),'inventory':held,'blocks':[block_name(world,p) for p in fixture['points']]}
def actual_chain(world,root,attempt):
    chain=[];receipts=[]
    for ordinal,(stage,folder) in enumerate((('HARVEST','harvest-evidence'),('PICKUP','pickup-evidence'),('DEPOSIT','deposit-evidence')),1):
        header,rows=journal(world/'data/cognitivecraft'/folder/'v1/state.jsonl')
        selected=[r for r in rows if r['intent']['key']['step']==root and r['intent']['key']['attempt']==attempt['id']]
        assert len(selected)==1,(root,attempt['id'],stage);entry=selected[0];key=entry['intent']['key'];result=entry['result']
        assert key['root']==root and key['generation']==attempt['generation'] and key['ordinal']==ordinal and key['world']==header['world']
        assert result['live']=='COMPLETE' and result['receipt']['stage']==stage and result['receipt']['quantity']==1
        assert result['usage']['COMMITTED_EFFECTS']==ordinal
        chain.append(key);receipts.append(result['receipt'])
    stored=attempt['resourceReceipts'];assert len(stored)==3 and [r['stage'] for r in stored]==['HARVEST','PICKUP','DEPOSIT']
    for raw,observed in zip(stored,receipts):
        assert all(raw[k]==observed[k] for k in ('id','run','operation','batch','quantity','stage','world','principal','domain','item'))
        assert raw['actor']['entity']==observed['entity'] and raw['actor']['citizen']==observed['citizen']
    assert len({r['id'] for r in stored})==len({r['operation'] for r in stored})==3
    return {'keys':chain,'receipts':[r['id'] for r in stored]}
def authoritative(world,row,expected):
    job=original(world,row['root']);p=job['activity'];snapshot=row['finalRuntime']
    assert job['schema']==4 and p['schema']==1 and p['mode']=='ACQUIRE_NEW' and p['policy']=='ORIGINAL_ACTOR_SETTLED_EFFECTS'
    assert job['id']==job['submission']==snapshot['root']==row['root']
    assert 'parent' not in job and job['children']==job['dependencies']==[]
    assert job['request']['principal']==row['fixture']['principal'] and job['request']['request']['arguments']['actor']['entity']==row['fixture']['actor']
    assert job['request']['request']['arguments']['amount']['value']==2 and len(job['attempts'])==expected and job['generation']==expected
    assert len(p['checkpoints'])==len(p['steps'])==expected and p['checkpoints']==snapshot['checkpoints']
    assert job['allowance']['deadline']==row['created']['originalDeadline']==snapshot['originalDeadline']==snapshot['remaining']['deadlineEpochMillis']
    assert job['usage']==snapshot['cumulativeUsage'] and all(v<=job['allowance']['maxima'][k] for k,v in job['usage'].items())
    assert sum(a['credited'] for a in job['attempts'])==snapshot['confirmedCredit']==expected and snapshot['verifiedCheckpoints']==expected
    assert snapshot['plannerEnabled'] is False and snapshot['plannedSteps']==2
    keys=[]
    for index,(step,cp,a) in enumerate(zip(p['steps'],p['checkpoints'],job['attempts'])):
        assert step['id']==cp['step'] and step['attempt']==cp['attempt']==a['id'] and step['creditBefore']==index
        assert cp['generation']==a['generation']==index+1 and cp['evidence']=='LIVE_RECEIPTS'
        assert a['terminal']['status']=='SUCCEEDED' and not a['uncertain'] and a['credited']==cp['quantity']==1 and a['effects']==3
        assert a['request']['request']['arguments']['amount']['value']==1 and a['worker']['entity']==row['fixture']['actor']
        assert a['allowance']['deadline']<=job['allowance']['deadline'] and step['allowance']['deadline']==a['allowance']['deadline']
        assert step['artifact']['sha256']==a['executions'][0]['artifact']['sha256'] and step['preparedRevision']<job['revision']
        proof=actual_chain(world,job['id'],a);assert proof['receipts']==cp['receipts'];keys.append(proof)
    assert len({k['attempt'] for c in keys for k in c['keys']})==expected
    assert len(snapshot['acceptedEffects'])==expected*3 and {r['receipt'] for r in snapshot['acceptedEffects']}=={r for c in keys for r in c['receipts']}
    return job,keys
def review(root,source):
    result=read(root/'a09-results.json');complete(result['cases'])
    assert result['plannedCases']==list(CASES) and result['requiredCases']==len(CASES) and result['status']=='PASS_NATIVE_CANDIDATES_PENDING_REVIEW'
    assert result['a09Qualified'] is False and result['runtimeDriven'] is True and result['plannerEnabled'] is False and result['omittedTasks']==result['omittedTests']==0
    metadata=read(root/'metadata.json');assert metadata['sourceCommit']==source and metadata['dirty'] is False and metadata['minecraft']=='26.3' and metadata['loader']=='0.19.5'
    runtime=runtime_proof(root,metadata);rpcs=[read(p) for p in (root/'a09-rpc').glob('*.json')]
    assert rpcs and all(r['response']['process']==r['request']['process'] for r in rpcs)
    build=read(root/'a08-build-inputs.json');assert 'e2e/parallel_a/compose-activity-runtime.yml' in build['files'] and build['networkDuringBuildRun']=='none'
    dependencies=read(root/'a08-image-dependencies.json');assert dependencies['status'].startswith('PASS')
    reviewed=[]
    for row in result['cases']:
        name=row['id'];details={}
        if name.startswith('legacy-') and name.endswith('-original'):
            assert row['regression']['originalCase']['id']==REGRESSIONS[name.split('-')[1]]
            details=review_original(root,row['regression']['originalCase'])
        elif name=='legacy-unsaved-pickup-fence':review_control(root,'unsaved-pickup-containment')
        else:
            folder=root/'cases'/name;assert read(folder/'result.json')==row
            world=folder/'terminal-world';assert manifest(world)==row['terminalWorldHashes'] and manifest(folder/'starting-world')==row['startingWorldHashes']
            assert row['modelCallsBefore']==row['modelCallsAfter'];fixture=row['fixture'];initial=physical(folder/'starting-world',fixture);terminal=physical(world,fixture)
            assert initial=={'destination':0,'inventory':{},'blocks':[LOG,LOG]}
            if name in ('two-segments-saved-checkpoint-restart','historical-credit-current-stock'):
                job,chains=authoritative(world,row,2);assert job['state']=='SUCCEEDED' and terminal['inventory']=={} and terminal['blocks']==['minecraft:air','minecraft:air']
                assert all('checkpoints=2/2' in row[k] for k in ('finalConnectedProgress',)) and 'checkpoints=1/2' in row['firstConnectedProgress']
                assert row['first']['confirmedCredit']==row['firstPhysical']['destination']==1 and row['secondBefore']['confirmedCredit']==1
                if name=='two-segments-saved-checkpoint-restart':
                    killed=folder/'killed-world';assert manifest(killed)==row['killedWorldHashes'];cut=original(killed,row['root']);first=cut['attempts'][0]
                    assert cut['state']=='WAITING' and len(cut['activity']['checkpoints'])==len(cut['attempts'])==1 and not first['uncertain']
                    assert physical(killed,fixture)['destination']==1 and len([b for b in physical(killed,fixture)['blocks'] if b==LOG])==1
                    proof=native_saved(killed,'DEPOSIT',job_id=row['root'],attempt_id=first['id'])
                    assert proof['key']['attempt']==first['id'] and first['effects']==3 and first['credited']==1
                    assert row['actualCut']['token']==row['intendedCut']['token'] and row['actualCut']['job']['id']==row['root'] and row['actualCut']['saveResult']==row['actualCut']['freezeResult']==1
                    assert row['restored']['checkpoints']==row['first']['checkpoints']==cut['activity']['checkpoints'] and row['restored']['cumulativeUsage']==cut['usage']
                    details['restart']=restart_proof(row['restart'],runtime,metadata['releaseSha256']);assert terminal['destination']==2
                    # Only the final attempt's current native participant marker remains current after step2.
                    native_saved(world,'DEPOSIT',job_id=row['root'],attempt_id=job['attempts'][-1]['id'],chest_units=2)
                else:
                    assert terminal['destination']==1 and row['beforeExternal']['destination']==2 and row['externalRemoval']['delta']==-1
                    assert row['afterExternal']['confirmedCredit']==2 and row['afterExternal']['availability']['destinationQuantity']==1 and 'currentStock=1' in row['afterExternalConnectedProgress']
                costs={}
                for key,segment in (('firstCost','segment-one'),('secondCost','segment-two')):
                    cost=row[key];raw=read(folder/(segment+'-ticks.json'));samples=[v['nanos'] for v in raw['samples'] if cost['start']<=v['time']<=cost['end']]
                    assert percentiles(samples)==cost['tickMillis'] and percentiles([v['durationNanos'] for v in cost['publicationTimings']])==cost['publicationMillis']
                    assert cost['publicationMillis']['max']<=30000;costs[segment]=cost
                details['measuredCosts']=costs
                details.update(root=job['id'],originalAttempts=[a['id'] for a in job['attempts']],confirmedCredit=2,actualCurrentStock=terminal['destination'],nativeEffects=chains)
            elif name in ('expired-original-deadline','private-runtime-boundary'):
                job,chains=authoritative(world,row,0);assert job['state']=='WAITING' and terminal==initial and not chains
                if name=='expired-original-deadline':
                    assert row['refused']['change']['reason']=='BUDGET_EXHAUSTED' and row['after']['next']=='EXPIRED' and 'next=EXPIRED' in row['connectedProgress']
                else:assert row['denied']['status']=='DENIED' and row['deniedPrepare']['change']['reason']=='AUTHORITY_DENIED'
            else:
                job=original(world,row['legacy']['responsibility']['id']);assert 'activity' not in job and job['state']=='SUCCEEDED' and terminal['destination']==1
                actual_chain(world,job['id'],job['attempts'][0])
                native_saved(world,'DEPOSIT',job_id=job['id'])
        reviewed.append({'id':name,'status':'PASS_INDEPENDENT_NATIVE_REVIEW',**details})
        print('A09_INDEPENDENT_CASE '+json.dumps({'id':name,'status':reviewed[-1]['status']}),flush=True)
    return {'schema':1,'stage':'A09','status':'PASS_ALL_REQUIRED_A09_NATIVE_REVIEW','source':source,'tree':metadata['sourceTree'],'releaseSha256':metadata['releaseSha256'],
            'observerSha256':metadata['binaries']['observer']['sha256'],'runtime':runtime,'cases':reviewed,'requiredCases':len(CASES),'a09Qualified':False,
            'runtimeDriven':True,'plannerEnabled':False,'claim':'Native owned slice and selected regressions only; aggregate stage receipt remains separate.'}
def main():
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--source',required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True)
    try:value=review(a.root.resolve(),a.source)
    except Exception as failed:value={'stage':'A09','status':'FAIL_INDEPENDENT_A09_REVIEW','a09Qualified':False,'reason':str(failed),'trace':traceback.format_exc()}
    (a.out/'a09-independent-review.json').write_text(json.dumps(value,indent=2)+'\n');print('A09_INDEPENDENT_REVIEW '+json.dumps(value),flush=True)
    if value['status']!='PASS_ALL_REQUIRED_A09_NATIVE_REVIEW':raise SystemExit(1)
if __name__=='__main__':main()
