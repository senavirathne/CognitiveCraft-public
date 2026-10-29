"""Read-only Docker review of the failed first-harvest trial; never native qualification."""
import argparse,hashlib,json,xml.etree.ElementTree as ET
from pathlib import Path
from review_receipt_prefix import checked_archive,journal,regions,entity_uuid,block_name

SOURCE='543cd78493bd619e05dce073d95db42f8bb94b9f'
ARTIFACTS={
 'native':{'id':12844104501,'size_in_bytes':62026268,'digest':'sha256:826973f868c1a8259f704d10ab0d8c7af31d99368e6b5e450016e5f15c3b711a'},
 'contracts':{'id':12844749324,'size_in_bytes':3373181,'digest':'sha256:c9fcbc98eafcc1b33fc3d9bceeb5e487d5e349ec4d670aca548ef01e573d914b'}}
def read(path):return json.loads(path.read_text())
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def review(native_archive,contracts_archive,out):
    out.mkdir(parents=True,exist_ok=True)
    archive_reviews={name:checked_archive(ARTIFACTS[name],path,out/name) for name,path in [('native',native_archive),('contracts',contracts_archive)]}
    native,contracts=out/'native',out/'contracts'
    result=read(native/'a06-harvest-results.json');metadata=read(native/'metadata.json')
    assert metadata.get('reviewedHeadCommit',metadata['sourceCommit'])==SOURCE and metadata['dirty'] is False
    assert result['status']=='FAIL' and result['reason']=='Actually reached saved original H boundary; last=None'
    assert result['originalTwelveSettlements']==0
    counts={}
    for path in contracts.glob('*/build/test-results/test/TEST-*.xml'):
        row=ET.parse(path).getroot();v=counts.setdefault(path.parts[-5],[0,0,0,0])
        for n,key in enumerate(('tests','failures','errors','skipped')):v[n]+=int(row.get(key,0))
    assert counts=={'core':[2229,0,0,0],'providers':[131,0,0,0],'fabric':[55,0,0,0]},counts
    release=sha(contracts/'fabric/build/libs/ai-villages-0.1.0.jar');observer=sha(contracts/'build/e2e-driver/observer.jar')
    for base in [native/'server/game',*[native/'clients'/p/'game' for p in ('PlayerA','PlayerB')]]:
        assert sha(base/'mods/ai-villages-0.1.0.jar')==release
    assert sha(native/'server/game/mods/observer.jar')==observer
    world=native/'server/game/world';header,rows=journal(world/'data/cognitivecraft/harvest-evidence/v1/state.jsonl')
    assert len(rows)==1 and rows[0]['schema']==1 and 'result' not in rows[0] and 'verification' not in rows[0]
    intent=rows[0]['intent'];source=intent['source'];block=block_name(world,[source[k] for k in ('x','y','z')])
    assert block=='minecraft:oak_log'
    actors=[e for c in regions(world,'entities') for e in c.get('Entities',[]) if entity_uuid(e)==intent['actor']['entity']]
    assert len(actors)==1 and actors[0]['id']=='minecraft:villager' and not actors[0].get('Inventory',[])
    snapshot=read(native/'server/harvest-snapshot.json')
    assert snapshot['pending'] is False and len(snapshot['journal']['entries'])==1 and not snapshot['journal']['entries'][0].get('result')
    cases=[]
    for module in ('core','fabric'):
        for path in sorted((contracts/module/'build/test-results/test').glob('*Harvest*Test.xml')):
            row=ET.parse(path).getroot();cases.append({'suite':row['name'] if isinstance(row,dict) else row.attrib['name'],'tests':int(row.attrib['tests']),'failures':int(row.attrib['failures']),'errors':int(row.attrib['errors'])})
    value={'schema':1,'status':'PASS_FAILURE_EVIDENCE_REVIEW_ONLY','source':SOURCE,'run':40754098206,
           'originalOwnerSettlements':0,'nativeHarvestBoundary':'NOT_REACHED','nativeCompletion':'NOT_PROVED',
           'observedFailure':result['reason'],'journal':'One acknowledged PREPARED intent; no native result or verification',
           'sourceAtOriginalAddress':block,'actorNativeInventory':actors[0].get('Inventory',[]),
           'diagnosis':'Own destination-permission query intersects the pending harvest exclusion (source analysis); scoped read-query fix requires a new native trial.',
           'moduleCases':sum(v[0] for v in counts.values()),'modules':counts,'harvestSuites':cases,
           'releaseSha256':release,'observerSha256':observer,'archives':archive_reviews,
           'claim':'Failure preservation and module/archive integrity only. No saved harvest or original-owner settlement pass.'}
    (out/'review.json').write_text(json.dumps(value,indent=2)+'\n')
    print('A06_HARVEST_FAILURE_REVIEW '+json.dumps({k:value[k] for k in ('status','moduleCases','originalOwnerSettlements','nativeHarvestBoundary')}))
    return value
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--native-archive',type=Path,required=True);p.add_argument('--contracts-archive',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
    review(a.native_archive,a.contracts_archive,a.out)
if __name__=='__main__':main()
