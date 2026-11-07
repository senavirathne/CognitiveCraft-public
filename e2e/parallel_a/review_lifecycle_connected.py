"""Independent offline A07 review. Raw native worlds remain necessary; a green collector cannot qualify A07."""
import argparse,json,traceback
from pathlib import Path
from lifecycle_cases import contexts,LIVE_CASES,DEATH_CASES,HANDOFF_CASES,disposition,handoff
from deposits import manifest
from recovery_fixture import native_saved
from review_receipt_prefix import journal,regions,entity_uuid,item

def read(path):return json.loads(Path(path).read_text())
def allowance(raw,view):return raw['maxima']==view['maxima'] and raw['deadline']==view['deadlineEpochMillis']
def actor(raw,view):return raw=={'citizen':view['citizenId'],'entity':view['entityId'],'dimension':view['dimension']}
def original(world,id):
    _,rows=journal(world/'data/cognitivecraft/jobs/v1/state.jsonl');matches=[r for r in rows if r.get('id')==id]
    assert len(matches)==1;return matches[0]
def entities(world):return {entity_uuid(e):e for f in regions(world,'entities') for e in f.get('Entities',[])}
def chest(world,pos):
    rows=[e for f in regions(world,'region') for e in f.get('block_entities',[]) if [e.get(k) for k in ('x','y','z')]==pos]
    assert len(rows)==1
    stock={}
    for stack in rows[0].get('Items',[]):
        _,name,count,_=item(stack);stock[name]=stock.get(name,0)+count
    return stock

def review(root,source):
    repo=Path(__file__).resolve().parents[2];result=read(root/'a07-results.json');metadata=read(root/'metadata.json')
    assert metadata['sourceCommit']==source and not metadata['dirty']
    assert metadata['minecraft']=='26.3' and metadata['loader']=='0.19.5'
    assert metadata['releaseSha256']==metadata['binaries']['production']['sha256']
    expected=[r['case']['id'] for r in contexts(repo/'e2e/parallel_a/a07_w04_contexts.json')]+list(LIVE_CASES)+list(DEATH_CASES)+list(HANDOFF_CASES)
    assert result['plannedCases']==expected and result['requiredCases']==27
    assert len(result['cases'])==27 and {r['id'] for r in result['cases']}==set(expected)
    assert not result['failures'] and result['omittedTasks']==result['omittedTests']==0
    assert result['status']=='PASS_CONNECTED_CANDIDATE_PENDING_INDEPENDENT_REVIEW'
    reviews=[]
    for row in result['cases']:
        name=row['id'];assert row['status'].startswith('PASS'),(name,row.get('reason'))
        folder=root/('w04' if name not in LIVE_CASES+DEATH_CASES+HANDOFF_CASES else 'connected' if name in LIVE_CASES else 'controls')/name
        assert read(folder/'result.json')==row
        world=folder/'terminal-world';assert manifest(world)==row['terminalWorldHashes']
        assert row['modelCallsBefore']==row['modelCallsAfter'],(name,'Unexpected model call')
        native=entities(world)
        if name in LIVE_CASES:
            before,after=row['before']['identity'],row['after']['identity'];assert before['actor']==after['actor'] and before['owner']==after['owner']
            assert after['lifecycle']['generation']==before['lifecycle']['generation']+1
            cut=row['cut'];assert cut['mode']==name.split('-')[1] and cut['freezeResult']==1
            absent=row['absent'];p=row['fixture']['target'];assert not absent['targets'][','.join(map(str,p))]['loaded'] and cut['time']<=absent['time']
            assert not absent['entities'][before['actor']['entityId']]['loaded']
            old=row['originalAtLoss'];saved=original(world,old['id']);assert allowance(saved['allowance'],old['allowance']) and saved['submission']==row['run']
            assert saved['generation']==old['generation'],'Unload minted another execution generation'
            assert before['actor']['entityId'] in native
            other=row['unrelated']['view']['responsibility'];raw_other=original(world,other['id']);assert raw_other['state']=='SUCCEEDED'
            assert sum(a['credited'] for a in raw_other['attempts'])==1
            assert chest(world,row['unrelated']['destination']).get('minecraft:oak_log',0)==1
            if name=='ACTOR-PUBLICATION':assert row['unloadedRestart']['sameClients']
        elif name in DEATH_CASES:
            case=row['originalCase'];proof=native_saved(folder/'killed-world','HARVEST',case);assert proof==row['nativeSavedProof']
            drop=proof['harvest']['result']['drops'][0]['entity'];actor=proof['harvest']['intent']['actor']['entity']
            assert actor not in native and row['death']['identity']['lifecycle']['event']=='DEATH'
            assert row['death']['identity']['lifecycle']['acknowledged']==7
            assert disposition(row['finalNative']['entities'][drop],row['finalNative']['damage'])==row['custody']
            if name=='death-burned':assert drop not in native
            else:assert drop in native and item(native[drop]['Item'])[1:3]==(case['item'],1)
            saved=original(world,row['key']['step']);assert saved['generation']==row['key']['generation'] and sum(a['credited'] for a in saved['attempts'])==0
        elif name in HANDOFF_CASES:
            proof=native_saved(folder/'killed-world','DEPOSIT',row['originalCase']);assert proof==row['nativeSavedProof']
            saved=original(world,row['key']['step']);assert saved['state']==row['originalJob']['state'] and allowance(saved['allowance'],row['originalAtCut']['allowance'])
            assert all('denied' in row['denied'][k].lower() or 'handoff=false' in row['denied'][k] for k in row['denied'])
            assert {'foreign','staleRevision','staleGeneration'}<=set(row['denied'])
            if name=='handoff-positive':
                assert handoff(row['beforeHandoff'],row['originalJob'],row['successor'])==row['handoffReview']
                assert saved['generation']==row['key']['generation']+1 and len(saved['attempts'])==2
                assert actor(saved['attempts'][-1]['worker'],row['successor']) and all(a['effects']==3 and a['credited']==1 and not a['uncertain'] for a in saved['attempts'])
                assert row['originalAtCut']['attempts'][0]['worker']['entityId'] not in native
                assert chest(world,proof['deposit']['intent']['destination'] if 'destination' in proof['deposit']['intent'] else [row['originalCase']['destination'][k] for k in ('x','y','z')]).get('minecraft:oak_log',0)==2
                assert row['restart']['sameClients'] and 'obsoleteGeneration' in row['denied']
            else:assert sum(a['credited'] for a in saved['attempts'])==1 and all(a['effects']==0 for a in saved['attempts'][1:])
        else:
            frozen=next(r for r in contexts(repo/'e2e/parallel_a/a07_w04_contexts.json') if r['case']['id']==name)
            assert row['originalCase']==frozen['case'] and row['historicalFailure']==frozen['reason']
            b,a=row['before']['identity'],row['after']['identity'];u=row['boundary']['identity']
            assert b['actor']==a['actor']==u['actor'] and b['owner']==a['owner']==u['owner']
            assert u['availability']=='UNLOADED' and u['lifecycle']['event']=='UNLOAD' and a['lifecycle']['generation']==b['lifecycle']['generation']+1
            assert row['unloadSeconds']<=row['originalTimeoutSeconds']==110
            assert any(e['event']=='ENTITY_UNLOAD' and e['entity']==b['actor']['entityId'] for e in row['nativeAtUnload']['nativeEvents'])
            assert b['actor']['entityId'] in native
        reviews.append({'id':name,'status':'PASS_INDEPENDENT_NATIVE_REVIEW','savedWorldFiles':len(row['terminalWorldHashes'])})
    return {'schema':1,'source':source,'status':'PASS_A07_CONNECTED_NATIVE_REVIEW','cases':reviews,'requiredCases':27,
            'a07Qualified':False,'qualificationScope':'Connected controls only; all module/native host/standard CI gates must also pass.',
            'w04':'OPEN_CAUSE_NOT_ESTABLISHED','w04Distribution':{'candidateUnloadReload':9,'historicalTimeoutsRetained':9},'releaseSha256':metadata['releaseSha256']}

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--source',required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True)
    try:value=review(a.root,a.source)
    except Exception as failure:value={'status':'FAIL_INDEPENDENT_A07_REVIEW','a07Qualified':False,'reason':str(failure),'trace':traceback.format_exc()}
    (a.out/'review.json').write_text(json.dumps(value,indent=2)+'\n');print(json.dumps(value),flush=True)
    if value['status']!='PASS_A07_CONNECTED_NATIVE_REVIEW':raise SystemExit(1)
if __name__=='__main__':main()
