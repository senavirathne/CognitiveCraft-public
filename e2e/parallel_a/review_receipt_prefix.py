"""Read-only independent review of A06's preliminary receipt-retention archives, not original-owner qualification."""
import argparse,hashlib,json,struct,subprocess,time,uuid,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
from native_nbt import chunk

REPO='senavirathne/CognitiveCraft-public'
TAG='cognitivecraft_deposit_evidence'
def sha(raw):return hashlib.sha256(raw).hexdigest()
def canonical(v):return json.dumps(v,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()
def read(path):return json.loads(Path(path).read_text())
def api(path):return json.loads(subprocess.check_output(['gh','api','repos/'+REPO+'/'+path]))
def download(a,path):
    with path.open('wb') as target:subprocess.run(['gh','api','repos/'+REPO+'/actions/artifacts/'+str(a['id'])+'/zip'],stdout=target,check=True)
    digest=sha(path.read_bytes());assert path.stat().st_size==a['size_in_bytes'] and a['digest']=='sha256:'+digest
    with zipfile.ZipFile(path) as z:
        names=z.namelist();assert len(names)==len(set(names)) and len(names)<20000 and z.testzip() is None
        assert sum(n.file_size for n in z.infolist())<2*1024**3
        for n in z.infolist():
            p=Path(n.filename);assert not p.is_absolute() and '..' not in p.parts and (n.external_attr>>16)&0o170000!=0o120000
        z.extractall(path.with_suffix(''))
    return {'id':a['id'],'bytes':path.stat().st_size,'sha256':digest,'entries':len(names),'crcPathsDuplicates':'PASS'}
def journal(path):
    head,payload=Path(path).read_bytes().split(b'\n',1);h=json.loads(head)
    assert sha(payload)==h['sha256'];rows=[json.loads(x) for x in payload.splitlines()]
    assert len(rows)==h['records'];assert all(canonical(r)==line for r,line in zip(rows,payload.splitlines()))
    return h,rows
def raw_receipt(r,stage=True):
    source=r['source'];destination=r['destination'];block=r['sourceBlock'];origin=r['origin'];actor=r['actor']
    row={'schema':1,'id':r['receiptId'],'principal':origin['principal']['id'],'world':origin['scope']['worldId'],'domain':origin['scope']['domainId'],
         'run':r['runId'],'operation':r['operationId'],'batch':r['batchId'],'citizen':actor['citizenId'],'entity':actor['entityId'],'dimension':actor['dimension'],
         'source':[source[k] for k in ('minX','minY','minZ','maxX','maxY','maxZ')],'destination':[destination[k] for k in ('x','y','z')],
         'item':r['item']['id'],'quantity':r['quantity'],'sourceBlock':[block[k] for k in ('x','y','z')],'drops':r['dropIds']}
    assert r['item']['components']=='default'
    if stage:row['stage']=r['stage']
    return row
def regions(world,folder):
    for region in sorted((world/folder).glob('r.*.*.mca')):
        _,rx,rz,_=region.name.split('.');rx,rz=int(rx),int(rz);raw=region.read_bytes()
        assert len(raw)>=8192
        for n in range(1024):
            if not int.from_bytes(raw[n*4:n*4+3],'big'):continue
            value=chunk(region,rx*32+n%32,rz*32+n//32)
            if value is not None:yield value
def entity_uuid(row):
    value=row.get('UUID')
    if isinstance(value,list) and len(value)==4:return str(uuid.UUID(bytes=struct.pack('>iiii',*value)))
    return value
def item(row):
    return (int(row.get('Slot',0)),row['id'],int(row.get('count',row.get('Count',1))),row.get('components',{}))
def block_name(world,pos):
    x,y,z=pos;region=world/'region'/('r.'+str(x//512)+'.'+str(z//512)+'.mca')
    data=chunk(region,x//16,z//16);assert data is not None
    section=next(s for s in data['sections'] if s['Y']==y//16);states=section['block_states'];palette=states['palette']
    if len(palette)==1:return palette[0]['Name']
    bits=max(4,(len(palette)-1).bit_length());per=64//bits;index=((y&15)<<8)|((z&15)<<4)|(x&15)
    value=(states['data'][index//per]&((1<<64)-1))>>((index%per)*bits)&((1<<bits)-1)
    return palette[value]['Name']
def saved_native(world,entry):
    intent=entry['intent'];result=entry['result'];entities=[e for c in regions(world,'entities') for e in c.get('Entities',[])]
    actors=[e for e in entities if entity_uuid(e)==intent['actor']['address']];assert len(actors)==1 and actors[0]['id']=='minecraft:villager'
    pos=tuple(map(int,intent['chest']['address'].split(',')))
    chests=[e for c in regions(world,'region') for e in c.get('block_entities',[]) if (e['x'],e['y'],e['z'])==pos]
    assert len(chests)==1 and chests[0]['id']=='minecraft:chest'
    marker_rows={}
    for name,tag in (('actor',actors[0]),('chest',chests[0])):
        metadata=json.loads(tag[TAG]);marker=json.loads(metadata['marker']);snapshot=result[name]
        assert metadata['schema']==1 and metadata['incarnation']==snapshot['incarnation'] and metadata['revision']==snapshot['revision']
        assert marker=={'effect':sha(canonical(intent['key'])),'intent':sha(canonical(intent)),'phase':'RESULT','result':sha(canonical(result))}
        marker_rows[name]={'incarnation':metadata['incarnation'],'revision':metadata['revision'],'marker':marker}
    actor_items=actors[0].get('Inventory',[]);chest_items=chests[0].get('Items',[])
    assert actor_items==[] and [item(r) for r in chest_items]==[(0,'minecraft:oak_log',1,{})]
    assert all(s['count']==0 for s in result['actor']['slots'])
    assert [(n,s['item'],s['count']) for n,s in enumerate(result['chest']['slots']) if s['count']]==[(0,'minecraft:oak_log',1)]
    source=intent['receiptPrefix'][0]['sourceBlock'];assert block_name(world,source)=='minecraft:air'
    drops=set(intent['receiptPrefix'][0]['drops']);assert not any(entity_uuid(e) in drops for e in entities)
    return {'nativeActorItems':actor_items,'nativeChestItems':chest_items,'sourceAtOriginalAddress':'minecraft:air','originalDropsAbsentInSavedEntityRegions':True,
            'absenceIsProductionLineageProof':False,'participants':marker_rows}
def verify(root,contract,source,expected_status,run):
    result=read(root/'a06-prefix-results.json');metadata=read(root/'metadata.json')
    assert metadata.get('reviewedHeadCommit',metadata['sourceCommit'])==source
    assert metadata['dirty'] is False
    assert result['status']==expected_status and result['nativeA06OriginalOwnerSettlements']==0 and result['originalTwelveCases']=='NOT_EXECUTED'
    assert [c['id'] for c in result['cases']]==['saved-positive-repeated-adoption','saved-original-receipt-prefix']
    rows={}
    for p in contract.glob('*/build/test-results/test/TEST-*.xml'):
        r=ET.parse(p).getroot();v=rows.setdefault(p.parts[-5],[0,0,0,0])
        for n,k in enumerate(('tests','failures','errors','skipped')):v[n]+=int(r.get(k,0))
    assert rows and all(v[1:]==[0,0,0] for v in rows.values())
    release=contract/'fabric/build/libs/ai-villages-0.1.0.jar';release_sha=sha(release.read_bytes())
    matching=['server/game/mods/ai-villages-0.1.0.jar',*['clients/'+p+'/game/mods/ai-villages-0.1.0.jar' for p in ('PlayerA','PlayerB')]]
    for name in matching:assert sha((root/name).read_bytes())==release_sha
    assert sha((root/'server/game/mods/observer.jar').read_bytes())==sha((contract/'build/e2e-driver/observer.jar').read_bytes())
    cases=[]
    for n,(name,world) in enumerate((('saved-positive-repeated-adoption',root/'positive-saved-game/world'),('saved-original-receipt-prefix',root/'saved-original-receipt-prefix/killed-world'))):
        _,records=journal(world/'data/cognitivecraft/deposit-evidence/v1/state.jsonl');assert len(records)==1
        entry=records[0];assert entry['schema']==2 and entry['result']['live']=='COMPLETE'
        prefix=entry['intent']['receiptPrefix'];assert [r['stage'] for r in prefix]==['HARVEST','PICKUP'] and all(r['quantity']==1 for r in prefix)
        case=result['cases'][n];domain=(case['live'] if n==0 else case['restoredEvidence'])['journal']['entries'][0]
        assert prefix==[raw_receipt(r) for r in domain['intent']['receiptPrefix']['receipts']]
        assert entry['result']['receipt']==raw_receipt(domain['result']['receipt'],False)
        assert len({r['id'] for r in [*prefix,entry['result']['receipt']]})==3
        if n==0:
            view=read(root/'positive-live-result.json')['view']
            assert prefix==[raw_receipt(r) for r in view['resourceReceipts'] if r['stage']!='DEPOSIT']
            assert entry['result']['receipt']==next(raw_receipt(r,False) for r in view['resourceReceipts'] if r['stage']=='DEPOSIT')
            before,after=case['nativeBeforeFirstKill'],case['nativeAfterFirstRestart']
        else:
            assert case['actuallyWitnessed']['point']=='RESULT_ACKNOWLEDGED' and case['actuallyWitnessed']['nativeSaveExplicitlyRequested']
            before,after=case['nativeBefore'],case['nativeAfter']
        assert (before['hostJvmPid'],before['startTicks'])!=(after['hostJvmPid'],after['startTicks'])
        cases.append({'id':name,'key':entry['intent']['key'],'actualPrefixIds':[r['id'] for r in prefix],'actualCurrentReceiptId':entry['result']['receipt']['id'],
                      'resultUsage':entry['result']['usage'],'actualJvmReplaced':True,'savedNative':saved_native(world,entry)})
    cut_entry=records[0];key=cut_entry['intent']['key']
    final_native=saved_native(root/'server/game/world',cut_entry)
    assert final_native==cases[-1]['savedNative']
    _,jobs=journal(root/'server/game/world/data/cognitivecraft/jobs/v1/state.jsonl');job=next(j for j in jobs if j.get('id')==key['step']);attempt=job['attempts'][-1]
    assert job['state']=='INTERRUPTED' and job['cancellationOutcome']=='CANCELLED' and attempt['id']==key['attempt'] and job['generation']==key['generation'] and attempt['uncertain']
    if expected_status.startswith('PASS'):
        c=result['cases'][1];assert not c['originalOwnerSettled'] and c['retainedPrefixCannotManufactureNativeProof']
        assert c['originalJobBeforeCancellationRestart']['state']=='CANCELLING' and c['originalJobAfterCancellationRestart']['state']=='INTERRUPTED'
        original=contract/'build/parallel-a/source-audit/a06-cancellation-restart/original-guard.xml';r=ET.parse(original).getroot()
        assert int(r.get('tests'))==int(r.get('failures'))==1 and int(r.get('errors'))==0
    else:assert 'Original unproved owner stays cancelling after another real kill' in result['reason']
    return {'schema':1,'status':'PASS_INDEPENDENT_RETENTION_REVIEW','source':source,'run':run,'nativeResult':result['status'],'originalFailure':result.get('reason'),
            'moduleCases':sum(v[0] for v in rows.values()),'modules':rows,'productionReleaseSha256':release_sha,'sameJarInSourceServerBothClients':True,
            'observerSha256':sha((root/'server/game/mods/observer.jar').read_bytes()),'cases':cases,
            'originalJob':{'id':job['id'],'state':job['state'],'cancellationOutcome':job['cancellationOutcome'],'attempt':attempt['id'],'generation':job['generation'],
                           'uncertain':attempt['uncertain'],'recordedEffects':attempt['effects'],'recordedCredit':attempt['credited'],'children':job['children']},
            'sameNativeCustodyAfterCancellationRestart':True,'finalNativeCustody':final_native,'originalTwelveCaseSettlements':0,'nativeSourceDropLineageQualified':False,'stageA06Qualified':False,'performanceQualified':False,'runtimeMetadataSha256':sha((root/'metadata.json').read_bytes()),'runtimeMetadata':metadata}
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run',required=True,type=int);p.add_argument('--source',required=True);p.add_argument('--expected-status',required=True);p.add_argument('--out',required=True,type=Path);a=p.parse_args()
    a.out.mkdir(parents=True,exist_ok=True)
    for attempt in range(30):
        r=api('actions/runs/'+str(a.run));assert r['head_sha']==a.source
        if r['status']=='completed':break
        time.sleep(5)
    else:raise TimeoutError('Original exact-source run has not completed its artifact publication')
    artifacts=api('actions/runs/'+str(a.run)+'/artifacts')['artifacts']
    selected=[next(x for x in artifacts if x['name'].startswith(prefix)) for prefix in ('cc-runtime-a-a06-prefix-native-','cc-runtime-a-a04-contracts-')]
    assert all(a.source in x['name'] and not x['expired'] for x in selected)
    reports=[download(v,a.out/(name+'.zip')) for name,v in zip(('native','contracts'),selected)]
    result=verify(a.out/'native',a.out/'contracts',a.source,a.expected_status,a.run);result['archives']=reports
    (a.out/'review.json').write_text(json.dumps(result,indent=2)+'\n')
    print('A06_PREFIX_INDEPENDENT_REVIEW '+json.dumps(result),flush=True)
if __name__=='__main__':main()
