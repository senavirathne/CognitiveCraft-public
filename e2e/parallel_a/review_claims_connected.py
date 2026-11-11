"""Independent A08 raw-world/Jobs review. Live move observations never become durability receipts."""
import argparse,json,traceback
from pathlib import Path
from claims_cases import CASES,service
from deposits import manifest
from review_lifecycle_connected import runtime_proof,entities,chest,original,restart_proof,worker_matches,allowance
from review_receipt_prefix import item

LOG='minecraft:oak_log';PLANK='minecraft:oak_planks';STICK='minecraft:stick'
def read(path):return json.loads(Path(path).read_text())
def inventory(entity):
    out={}
    for stack in entity.get('Inventory',[]):
        _,name,count,_=item(stack);out[name]=out.get(name,0)+count
    return out
def custody(world,fixture,extra=False):
    native=entities(world);out={};private={}
    def add(name,count):out[name]=out.get(name,0)+count
    for player,id in fixture['actors'].items():
        actor=native[id];assert actor['id']=='minecraft:villager';private[player]=inventory(actor)
        for name,count in private[player].items():add(name,count)
    points={tuple(fixture[k]) for k in ('source','aDestination','bDestination')}
    if extra:points|={(326,208,66),(328,208,66)}
    boxes={}
    for pos in sorted(points):
        goods=chest(world,list(pos));boxes[','.join(map(str,pos))]=goods
        for name,count in goods.items():add(name,count)
    drops=[]
    for id,entity in native.items():
        if entity.get('id')=='minecraft:item':
            _,name,count,_=item(entity['Item'])
            if name in (LOG,PLANK,STICK):add(name,count);drops.append({'entity':id,'item':name,'quantity':count})
    return {'total':out,'private':private,'boxes':boxes,'drops':drops}
def denominator(result):
    assert result['plannedCases']==list(CASES) and result['requiredCases']==len(CASES)
    assert len(result['cases'])==len(CASES) and {r['id'] for r in result['cases']}==set(CASES)
    assert not result['failures'] and result['omittedTasks']==result['omittedTests']==0
    assert result['status']=='PASS_NATIVE_CANDIDATE_PENDING_REVIEW' and result['a08Qualified'] is False
    assert all(r['status']=='PASS_NATIVE_CANDIDATE' for r in result['cases'])
def review(root,source):
    result=read(root/'a08-results.json');denominator(result);metadata=read(root/'metadata.json')
    assert metadata['sourceCommit']==source and metadata['dirty'] is False and metadata['minecraft']=='26.3'
    runtime=runtime_proof(root,metadata);rows=[];waits=[];rpcs=[]
    for file in sorted((root/'a08-rpc').glob('*.json')):rpcs.append(read(file))
    assert rpcs and all(r['response']['process']==r['request']['process'] for r in rpcs)
    for row in result['cases']:
        name=row['id'];folder=root/'cases'/name;assert read(folder/'result.json')==row
        world=folder/'terminal-world';assert manifest(world)==row['terminalWorldHashes']
        assert manifest(folder/'starting-world')==row['startingWorldHashes']
        assert row['modelCallsBefore']==row['modelCallsAfter'];fixture=row['fixture']
        saved=custody(world,fixture,name=='crash-after-withdraw-scoped-fence-C-progress')
        assert saved['total'].get(LOG,0)==row['expectedLogs'],(name,saved)
        assert not saved['drops'],(name,'Unexpected staged drop')
        state=row['runtime'];assert state['ready'];limits=state['fairLimits']
        assert limits=={'queued':16,'waitingTicks':1200,'idleTicks':40,'backoffTicks':2,'claimTicks':20}
        assert state['stagingLimits']=={'frames':32,'units':64,'residenceTicks':600,'replans':8}
        jobs={j['id']:j for j in state['jobs']['jobs']}
        for key,player in (('a','PlayerA'),('b','PlayerB'),('c','PlayerB')):
            if key not in row:continue
            id=row[key];raw=original(world,id);view=jobs[id];attempt=raw['attempts'][-1];current=view['attempts'][-1]
            assert raw['id']==id and raw['generation']==view['generation']==1 and len(raw['attempts'])==1
            assert attempt['id']==current['id'] and attempt['generation']==current['generation']
            assert worker_matches(attempt['worker'],current['worker']) and attempt['worker']['entity']==fixture['actors'][player]
            assert raw['request']['principal']==fixture['principals'][player]
            assert attempt['executions']==current['executions']==[] and attempt['credited']==current['credited']==0
            assert attempt.get('resourceReceipts',[])==current.get('resourceReceipts',[])==[]
            assert raw['children']==[] and allowance(raw['allowance'],view['allowance'])
            assert all(n<=raw['allowance']['maxima'][kind] for kind,n in raw['usage'].items())
        for frame in state['frames'].values():
            job=jobs[frame['owner']['job']];assert frame['owner']['generation']==job['generation']
            assert sum(frame['acquired'].values())<=64 and len(frame['moves'])<=2
            assert all(n<=job['attempts'][-1]['allowance']['maxima'][kind] for kind,n in frame['usage'].items())
        for entry in row['fairA']+row['fairB']:
            if entry['grantedTick']>=0:waits.append(entry)
        if name=='five-logs-three-and-four-external-two':
            assert row['aHeld']['acquired']=={LOG:3} and row['bHeld']['acquired']=={LOG:4}
            assert row['bObservedTwo']['phase']=='WAITING' and row['bObservedTwo']['moves']==[]
            assert row['twoLogNative']['targets']['74,208,69']['stock']=={LOG:2}
            assert row['external'][0]['delta']==2 and row['external'][0]['native']['targets']['74,208,69']['stock']=={LOG:4}
            assert saved['boxes']['74,208,69']=={LOG:7} and saved['boxes']['73,208,68']==saved['boxes']['75,208,68']=={}
            assert [m['kind'] for m in row['aReturn']['moves']]==['WITHDRAW','RETURN'] and [m['kind'] for m in row['bReturn']['moves']]==['WITHDRAW','RETURN']
            assert saved['private']=={'PlayerA':{},'PlayerB':{}} and saved['total'][LOG]==7
        elif name=='older-feasible-small-arrivals':
            assert len(row['arrivals'])==6 and len({r['ticket'] for r in row['arrivals']})==6
            assert all(r['claim'].get('group') is None and r['claim']['leases']==[] for r in row['arrivals'])
            assert row['olderWaiting']['moves']==[] and row['bHeld']['acquired']=={LOG:4} and saved['private']['PlayerB']=={LOG:4}
            eligible=next(r for r in row['fairB'] if r['owner']['job']==row['b'] and r['grantedTick']>=0)
            physical=row['bHeld']['moves'][0]['tick']-eligible['eligibleTick'];assert 0<=physical<=60,physical
            row_service={'eligibilityToPhysicalTicks':physical,'eligibilityToGrant':service([eligible]),'boundTicks':60,
                         'assumptions':'This finite six-arrival scenario: feasible oldest owner polls within40 ticks; successful claim acknowledgement/effect within20 ticks. Storage timeout grants no effect.'}
        elif name=='impossible-large-useful-small':
            assert row['olderWaiting']['moves']==[] and row['aHeld']['acquired']=={LOG:1}
            assert saved['private']['PlayerA']=={LOG:1} and saved['boxes']['74,208,69']=={LOG:1}
        elif name=='complementary-inputs-atomic':
            assert row['missingSecondary']['moves']==[] and row['return']['phase']=='RETURNED'
            assert saved['total'].get(PLANK)==3 and saved['total'].get(STICK)==2 and saved['private']=={'PlayerA':{},'PlayerB':{}}
            assert [m['kind'] for m in row['return']['moves']]==['WITHDRAW','RETURN']
        elif name=='complementary-private-custody-residence-bound':
            assert saved['private']=={'PlayerA':{PLANK:3},'PlayerB':{STICK:2}}
            assert row['blockedA']['blocker']==row['blockedB']['blocker']=='ACTION_TIMEOUT'
            assert not row['blockedA']['moves'] and not row['blockedB']['moves']
        elif name in ('initial-full-zero-effect','dynamic-fill-before-withdraw','source-destination-alias','component-stack-swap','chest-replacement','double-chest-split','slot-stack-swap-after-claim'):
            assert row['blocked']['moves']==[] and saved['private']=={'PlayerA':{},'PlayerB':{}}
            if name=='slot-stack-swap-after-claim':assert row['blocked']['usage']['ATTEMPTED_EFFECTS']==1 and row['blocked']['blocker']=='STALE_OBSERVATION'
        elif name=='dynamic-fill-after-withdraw-custody':
            assert row['waiting']['acquired']=={LOG:3} and len(row['waiting']['moves'])==1
            assert [m['kind'] for m in row['return']['moves']]==['WITHDRAW','RETURN']
            assert saved['private']=={'PlayerA':{},'PlayerB':{}} and saved['boxes']['74,208,69']=={LOG:5}
        elif name in ('full-return-retains-custody','revoked-return-retains-custody'):
            assert saved['private']['PlayerA']=={LOG:3} and saved['private']['PlayerB']=={}
            assert len((row.get('waiting') or row['blocked'])['moves'])==1
        elif name=='lawful-return-new-action':
            assert row['return']['phase']=='RETURNED' and [m['kind'] for m in row['return']['moves']]==['WITHDRAW','RETURN']
            assert saved['boxes']['74,208,69']=={LOG:5} and saved['private']=={'PlayerA':{},'PlayerB':{}}
            assert row['cancelled']['state']=='CANCELLED' and row['cancelled']['attempts'][-1]['effects']==2 and row['cancelled']['attempts'][-1]['credited']==0
        elif name=='cross-principal-denial':assert row['denied']['status']=='DENIED' and saved['private']['PlayerA']=={LOG:3}
        elif name=='external-mutation-replan-bound':
            last=row['changes'][-1];assert last['phase']=='BLOCKED' and last['blocker']=='BUDGET_EXHAUSTED' and not last['moves'] and last['changes']==9
        elif name=='navigation-no-storage-claim':assert not row['unreachable']['moves'] and row['unreachable']['phase']=='WAITING' and saved['private']['PlayerA']=={LOG:3}
        elif name=='stale-epoch-release':
            assert row['oldReference']['validate']['code']==row['oldReference']['release']['code']=='STALE'
            assert saved['private']=={'PlayerA':{},'PlayerB':{}};restart_proof(row['restart'],runtime,metadata['releaseSha256'])
        elif name=='crash-after-withdraw-scoped-fence-C-progress':
            killed=folder/'killed-world';assert manifest(killed)==row['killedWorldHashes']
            cut=custody(killed,fixture);assert cut['private']['PlayerA']=={LOG:3} and cut['boxes']['74,208,69']=={LOG:2}
            before,after=row['originalBefore'],row['originalAfter'];assert before['id']==after['id']==row['a'] and before['allowance']==after['allowance']
            assert before['attempts'][-1]['id']==after['attempts'][-1]['id'] and before['generation']==after['generation']==1
            assert before['attempts'][-1]['effects']==after['attempts'][-1]['effects']==1 and after['attempts'][-1]['uncertain']
            assert after['state']=='INTERRUPTED' and row['fence']['fenced'];assert saved['private']['PlayerA']=={LOG:3}
            assert saved['boxes']['326,208,66']=={LOG:1} and saved['boxes']['328,208,66']=={} and saved['private']['PlayerB']=={}
            assert [m['kind'] for m in row['cReturn']['moves']]==['WITHDRAW','RETURN']
            restart_proof(row['restart'],runtime,metadata['releaseSha256'])
        else:raise AssertionError('Unreviewed required native case '+name)
        rows.append({'id':name,'status':'PASS_RAW_NATIVE_AND_ORIGINAL_JOB_REVIEW','custody':saved})
    return {'schema':1,'stage':'A08','status':'PASS_BOUNDED_STAGING_NATIVE_REVIEW','a08Qualified':False,'source':source,'tree':metadata['sourceTree'],
            'releaseSha256':metadata['releaseSha256'],'runtime':runtime,'cases':rows,'requiredCases':len(CASES),'grantWaitDistribution':service(waits),
            'olderFeasibleService':row_service,'saveConfirmedStagingReceipts':False,'learnedMethodExecution':False,'automaticGoalSelection':False,
            'scope':'Native explicit deterministic staging driver under original Kernel Jobs. Complete stage qualification additionally requires target module/required regression and production adoption review.'}

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--source',required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True)
    try:value=review(a.root,a.source)
    except Exception as failure:
        value={'schema':1,'stage':'A08','status':'FAIL_INDEPENDENT_NATIVE_REVIEW','a08Qualified':False,'reason':str(failure),'trace':traceback.format_exc()}
        (a.out/'a08-independent-review.json').write_text(json.dumps(value,indent=2)+'\n');print(json.dumps(value),flush=True);raise
    (a.out/'a08-independent-review.json').write_text(json.dumps(value,indent=2)+'\n');print('A08_INDEPENDENT_REVIEW '+value['status'],flush=True)
if __name__=='__main__':main()
