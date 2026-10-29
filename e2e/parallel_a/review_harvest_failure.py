"""Read-only Docker review of the failed first-harvest trial; never native qualification."""
import argparse,hashlib,json,tempfile,xml.etree.ElementTree as ET
from pathlib import Path
from review_receipt_prefix import checked_archive,journal,regions,entity_uuid,block_name

SOURCE='543cd78493bd619e05dce073d95db42f8bb94b9f'
ARTIFACTS={
 'native':{'id':12844104501,'size_in_bytes':62026268,'digest':'sha256:826973f868c1a8259f704d10ab0d8c7af31d99368e6b5e450016e5f15c3b711a'},
 'contracts':{'id':12844749324,'size_in_bytes':3373181,'digest':'sha256:c9fcbc98eafcc1b33fc3d9bceeb5e487d5e349ec4d670aca548ef01e573d914b'}}
def read(path):return json.loads(path.read_text())
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
COHORTS={
 'source-543':{'source':SOURCE,'run':40754098206,'artifacts':ARTIFACTS,
               'counts':{'core':[2229,0,0,0],'providers':[131,0,0,0],'fabric':[55,0,0,0]}},
 'source-b075':{'source':'b0759b69cc799bc071542b063ac886a4da74d47b','run':40811904558,
                'artifacts':{
                  'native':{'id':12871296042,'size_in_bytes':61997018,'digest':'sha256:adcc1cf8920a8c1655f82368121109dd4f8a986a1f6d811ca2249c6a8d941b1a'},
                  'contracts':{'id':12871610049,'size_in_bytes':3382091,'digest':'sha256:d3ea72a73c90eccaa8f11d72d0193036fce1eb17b34077c585ee16ce6e81f3f2'}},
                'counts':{'core':[2233,0,0,0],'providers':[131,0,0,0],'fabric':[58,0,0,0]}}
}
def review(native_archive,contracts_archive,out,cohort='source-543'):
    out.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='a06-harvest-review-') as directory:
        return inspect(native_archive,contracts_archive,out,Path(directory),COHORTS[cohort])
def inspect(native_archive,contracts_archive,out,work,cohort):
    archive_reviews={name:checked_archive(cohort['artifacts'][name],path,work/name) for name,path in [('native',native_archive),('contracts',contracts_archive)]}
    native,contracts=work/'native',work/'contracts'
    result=read(native/'a06-harvest-results.json');metadata=read(native/'metadata.json')
    assert metadata.get('reviewedHeadCommit',metadata['sourceCommit'])==cohort['source'] and metadata['dirty'] is False
    assert result['status']=='FAIL' and result['reason']=='Actually reached saved original H boundary; last=None'
    assert result['originalTwelveSettlements']==0
    counts={}
    for path in contracts.glob('*/build/test-results/test/TEST-*.xml'):
        row=ET.parse(path).getroot();v=counts.setdefault(path.parts[-5],[0,0,0,0])
        for n,key in enumerate(('tests','failures','errors','skipped')):v[n]+=int(row.get(key,0))
    assert counts==cohort['counts'],counts
    release=sha(contracts/'fabric/build/libs/ai-villages-0.1.0.jar');observer=sha(contracts/'build/e2e-driver/observer.jar')
    for base in [native/'server/game',*[native/'clients'/p/'game' for p in ('PlayerA','PlayerB')]]:
        assert sha(base/'mods/ai-villages-0.1.0.jar')==release
    assert sha(native/'server/game/mods/observer.jar')==observer
    world=native/'server/game/world';header,rows=journal(world/'data/cognitivecraft/harvest-evidence/v1/state.jsonl')
    assert len(rows)<=64 and all(r['schema']==1 and isinstance(r.get('intent'),dict) for r in rows)
    snapshot=read(native/'server/harvest-snapshot.json')
    assert isinstance(snapshot['pending'],bool) and isinstance(snapshot['fenced'],bool)
    assert isinstance(snapshot['journal']['entries'],list)
    positions=[[73,208,73],[76,208,74]]
    blocks={','.join(map(str,pos)):block_name(world,pos) for pos in positions}
    jobs=snapshot.get('runtime',{}).get('jobs',{}).get('jobs',[])
    selected=[j for j in jobs if j['request']['request']['arguments'].get('amount',{}).get('value')==2]
    assert len(selected)==1
    job=selected[0];attempt=job.get('attempts',[])[-1] if job.get('attempts') else None
    if attempt:
        actors=[e for c in regions(world,'entities') for e in c.get('Entities',[]) if entity_uuid(e)==attempt['worker']['entityId']]
        assert len(actors)<=1 and all(e['id']=='minecraft:villager' for e in actors)
    else:actors=[]
    cases=[]
    for module in ('core','fabric'):
        for path in sorted((contracts/module/'build/test-results/test').glob('*Harvest*Test.xml')):
            row=ET.parse(path).getroot();cases.append({'suite':row.attrib['name'],'tests':int(row.attrib['tests']),'failures':int(row.attrib['failures']),'errors':int(row.attrib['errors'])})
    value={'schema':1,'status':'PASS_FAILURE_EVIDENCE_REVIEW_ONLY','source':SOURCE,'run':40754098206,
           'originalOwnerSettlements':0,'nativeHarvestBoundary':'NOT_REACHED','nativeCompletion':'NOT_PROVED',
           'observedFailure':result['reason'],'journal':{'records':len(rows),'preparedOnly':sum('result' not in r for r in rows),'resultRecords':sum('result' in r for r in rows),'verificationRecords':sum('verification' in r for r in rows),'header':header,'entries':rows},
           'sourceBlocks':blocks,'actorNativeInventory':actors[0].get('Inventory',[]) if actors else None,'originalJob':job,'lastMetrics':snapshot.get('metrics'),'lastPublication':{'pending':snapshot['pending'],'fenced':snapshot['fenced']},
           'diagnosis':'Exact retained failure state is reported without assuming whether harvest started. Source analysis identifies the own-exclusion query and the post-mutation lease-stock check as separate paths; each correction requires native testing. No intended boundary or original-owner pass is inferred from rows or custody.',
           'moduleCases':sum(v[0] for v in counts.values()),'modules':counts,'harvestSuites':cases,
           'releaseSha256':release,'observerSha256':observer,'archives':archive_reviews,
           'claim':'Failure preservation and module/archive integrity only. No saved harvest or original-owner settlement pass.'}
    (out/'review.json').write_text(json.dumps(value,indent=2)+'\n')
    print('A06_HARVEST_FAILURE_REVIEW '+json.dumps({k:value[k] for k in ('status','moduleCases','originalOwnerSettlements','nativeHarvestBoundary')}))
    return value
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--native-archive',type=Path,required=True);p.add_argument('--contracts-archive',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--cohort',choices=COHORTS,default='source-543');a=p.parse_args()
    review(a.native_archive,a.contracts_archive,a.out,a.cohort)
if __name__=='__main__':main()
