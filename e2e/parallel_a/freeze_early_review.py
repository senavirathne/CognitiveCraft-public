"""A10 read-only review of original A00–A05 native, host and metadata gates."""
import hashlib,json,re
from pathlib import Path
from freeze_storage_review import (read,digest,journal,percentiles,history,codec,inventory,totals,process_replaced,
    observed_runs,canonical_world,pinned_jobs,no_effect,complete_performance,replay_lookup,exact_cycle_frames)
from review_lifecycle_connected import entities,chest,original
from review_receipt_prefix import item,block_name,regions
from deposits import manifest
from suite import DENIED_PATTERN

LOG='minecraft:oak_log';DEST=[77,208,72]

def successful(view,amount):
    assert view['phase']=='TERMINAL' and view['marker']['outcome']=='SUCCEEDED'
    assert totals(view['resourceReceipts'])==dict.fromkeys(('HARVEST','PICKUP','DEPOSIT'),amount)
    assert view['responsibility']['state']=='SUCCEEDED'

def interruption(root,folder,row,saved):
    assert read(folder/'result.json')=={k:v for k,v in row.items() if k!='id'}
    before=read(folder/'before-kill.json');job=before['view']['responsibility'];after=row['after']['responsibility']
    assert row['saved']==saved and row['actuallyWitnessedCut']==before['view']
    assert before['postEffectSaveRequested']==saved and bool(before['effectSave'])==saved
    assert totals(before['view']['resourceReceipts'])['DEPOSIT']==1 and before['custody']['destination']==1
    assert after['id']==job['id']==row['job'] and after['generation']==job['generation']
    assert after['submissionId']==job['submissionId']==row['run'] and [a['id'] for a in after['attempts']]==[a['id'] for a in job['attempts']]
    assert after['state']=='INTERRUPTED' and any(a['uncertain'] or a.get('terminal') is None for a in after['attempts'])
    assert not row['automaticRecoveryEnabled'] and row['ownerSettlement']=='FAIL'
    process_replaced(row['beforeNativeProcess'],row['afterNativeProcess'])
    killed=folder/'killed-world';recorded=row['killedWorldHashes']
    assert isinstance(recorded,list) and all(set(r)=={'path','sha256'} for r in recorded)
    expected={r['path']:r['sha256'] for r in recorded};assert len(expected)==len(recorded)
    assert manifest(killed)==expected
    if saved:
        raw=original(killed,job['id']);assert raw['state']=='ACTIVE' and raw['generation']==job['generation']
        assert chest(killed,DEST).get(LOG,0)==1
        for field in ('destination','inventory','drops','blocks','actorEntity'):assert row['beforeCustody'][field]==row['afterCustody'][field]
    return {'saved':saved,'sameOriginalJob':job['id'],'unresolvedSignatureRetained':True,'automaticContinuation':False}

def baseline(root,cases):
    positive=cases['supported-gather-deposit']['evidence'];successful(positive['view'],2)
    assert positive['clientMenuCount']==2
    negative=cases['foreign-principal-negative'];assert re.search(DENIED_PATTERN,negative['feedback'])
    for frame in (negative['before'],negative['after']):
        assert frame['containers']['77,208,72'].get(LOG,0)==0 and LOG in frame['blocks']['70,208,66']
    review={name:interruption(root,root/name,cases[name],name.startswith('saved')) for name in ('unflushed-probe','saved-restart-signature')}
    raw=read(root/'baseline-tick-samples.json');reported=read(root/'a00-results.json')['performance']['tick']
    selection=read(root/'baseline-selected-window.json')
    assert selection['samples']==[s for s in raw['samples'] if s['time']>=selection['startMillis']]
    assert percentiles([s['nanos'] for s in selection['samples']])==reported and reported['samples']>=390
    return {'cuts':review,'observations':observed_runs(root,[positive['view']]),'originalPerformance':reported}

def activity(root,cases):
    positive=cases['legacy-positive']['evidence'];successful(positive['view'],2)
    row=read(root/'saved-original-owner/result.json');cut=interruption(root,root/'saved-original-owner',row,True)
    assert cases['repeated-adoption-cancel-private-view']['evidence']==row
    live=row['liveActivityView'];view=live['snapshot']
    assert live['watchRequest']==read(root/'server/runtime-a-watch.json')=={'schema':1,'roots':[row['job']]}
    assert view['root']==row['job'] and view['route']=='LIVE_BOOTSTRAP' and view['state']=='ACTIVE'
    assert 'activity='+row['job'] in live['authorizedFeedback'] and 'owner=LIVE_BOOTSTRAP' in live['authorizedFeedback']
    assert re.search(DENIED_PATTERN,live['foreignDenied'])
    v=row['recoveryActivityView']
    first=v['first'];assert first['route']=='RECOVERY' and first['blocker']=='NEEDS_ATTENTION'
    assert v['repeated']['recoveryOwner']==v['secondRestart']['recoveryOwner']==first['recoveryOwner']
    assert v['secondRestart']['process']!=first['process'] and v['secondRestart']['cancellationIntent']
    process_replaced(v['beforeNativeProcess'],v['afterNativeProcess'])
    assert v['waiting']['view']['blocker']==v['unrelated']['originalBefore']['view']['blocker']==v['unrelated']['originalAfter']['view']['blocker']=='WAIT_WORLD'
    assert v['waiting']['address']['availability']=='UNLOADED' and v['reloaded']['blocker']=='NEEDS_ATTENTION'
    successful(v['unrelated']['view'],1);assert v['unrelated']['stock']==1 and v['unrelated']['modelCallsUnchanged']
    assert v['ownerSettlement']=='NOT_CLAIMED' and not v['automaticContinuation']
    return {'cut':cut,'privateOwnerStable':True,'unrelatedProgressWhileOriginalUnloaded':True,
            'observations':observed_runs(root,[positive['view'],v['unrelated']['view']])}

def persistence(root,cases):
    for key in ('legacy-positive-and-baseline','history-enabled-native-positive'):
        successful(cases[key].get('control',cases[key].get('evidence'))['view'],2)
    compat=cases['real-world-compatibility-and-acknowledged-history'];before=compat['compat']
    assert compat['repeated']['manifest']['revision']==compat['coldRepeated']['manifest']['revision']==before['manifest']['revision']
    assert compat['coldVerified']['payloadSha256']==before['payloadSha256']
    source=root/'compat-source-job-snapshot.jsonl';target=root/'compat-target-job-component.json'
    head,old=journal(source);component=read(target)
    assert digest(source)==before['sourceSha256'] and digest(target)==before['payloadSha256']
    assert component['schema']==1 and component['world']==head['world']
    assert {r['id']:r for r in component['jobs']}=={r['id']:r for r in old if r['kind']=='job'}
    assert component['allocations']==sorted([r for r in old if r['kind']=='allocation'],key=lambda r:r['id'])
    assert all(r['state'] in ('SUCCEEDED','FAILED','CANCELLED') for r in component['jobs'])
    assert before['executionClass']=='NATIVE_HOST_CLOSED_REAL_JOB_METADATA' and not before['physicalProof'] and not before['domainEviction']
    for key in ('restart','secondRestart'):process_replaced(compat[key]['before'],compat[key]['after']);assert compat[key]['sameClients']
    costs={}
    for mode in ('baseline','candidate'):
        value=read(root/(mode+'-performance.json'));raw=read(root/(mode+'-raw-window.json'))
        assert digest(root/(mode+'-raw-window.json'))==value['rawWindowSha256']
        assert len(raw['tickSamples'])==len(raw['captureNanos'])==400 and len(raw['ackNanos'])==64
        for field,samples in (('tick',[s['nanos'] for s in raw['tickSamples']]),('capture',raw['captureNanos']),('ack',raw['ackNanos'])):assert percentiles(samples)==value[field]
        for field,p,limit in (('tick','p95',20),('tick','p99',40),('capture','p99',2),('ack','p95',250),('ack','p99',1000),('ack','max',30000)):assert value[field][p]<=limit
        costs[mode]=value
    assert costs['baseline']['io']['sourceSha256']==costs['candidate']['io']['sourceSha256']
    pressure=cases['reserved-metadata-under-ordinary-pressure']['evidence'];assert pressure['reservedOriginalsSettled']==8 and pressure['nativeEffects']==0
    unknown=cases['unknown-history-fence-and-whole-world-rollback'];assert unknown['fenced']['storage']['readOnly'] and not unknown['restored']['storage']['readOnly']
    healthy=root/'coherent-before-corruption-world';bad=root/'retained-corrupt-history-world'
    good_manifest=healthy/'data/cognitivecraft/runtime-history/v1/manifest.jsonl';bad_manifest=bad/'data/cognitivecraft/runtime-history/v1/manifest.jsonl'
    head,payload=good_manifest.read_bytes().split(b'\n',1);damaged,body=bad_manifest.read_bytes().split(b'\n',1)
    assert json.loads(head)['schema']==1 and json.loads(damaged)['schema']==99 and payload==body
    assert damaged==head.replace(b'"schema":1',b'"schema":99',1)
    negative=read(root/'unknown-history-negative.json');assert digest(bad_manifest)==negative['currentSha256'] and digest(good_manifest)==negative['originalHeaderSha256']
    assert chest(bad,DEST).get(LOG,0)==0 and block_name(bad,[70,208,66])==LOG
    return {'performance':{k:{s:v[s] for s in ('tick','capture','ack')} for k,v in costs.items()},
            'reservedOriginals':8,'syntheticHeadroomNativeEffects':0,'unknownSchemaPreserved':99,'rollback':'COORDINATED_WORLD_AND_METADATA'}

def leases(root,cases):
    positive=cases['legacy-native-positive']['evidence'];successful(positive['view'],2)
    c=cases['continuous-129-retired-native-leases'];cycles=c['cycles'];assert len(cycles)==43
    rows=inventory(c['inventory']);job_ids=set();actor=None
    world=root/'qualified-continuous-history-world';_,raw=journal(world/'data/cognitivecraft/jobs/v1/state.jsonl');jobs={r['id']:r for r in raw}
    for n,cycle in enumerate(cycles):
        v=cycle['view'];successful(v,1);j=v['responsibility'];assert j['id'] not in job_ids;job_ids.add(j['id'])
        assert jobs[j['id']]['state']=='SUCCEEDED' and not jobs[j['id']]['attempts'][-1]['uncertain']
        assert cycle['beforeDestination']==n and cycle['afterDestination']==n+1 and cycle['modelCallsBefore']==cycle['modelCallsAfter']
        assert cycle['brokerStats']['calls']==cycle['brokerStats']['dispatched']==0
        worker=j['attempts'][-1]['worker'];actor=actor or worker;assert worker==actor
        own=[r for r in rows.values() if r['job']==j['id']];assert len(own)==3 and {r['resource']['kind'] for r in own}=={'STOCK','SPACE','FACILITY'}
        assert len({r['group'] for r in own})==1 and all(r['state']!='ACTIVE' and r['jobGeneration']==j['generation'] for r in own)
        stock=next(r for r in own if r['resource']['kind']=='STOCK');assert stock['quantity']==1 and stock['remaining']==0 and list(stock['consumed'].values())==[1]
    assert sum(r['job'] in job_ids for r in rows.values())==c['actualRetiredLeases']==129
    status=c['status'];assert status['jobs']<64 and status['metrics']['hot']<=128 and status['metrics']['active']<=64 and status['metrics']['evicted']>0
    pinned={r['id']:r for r in c['pinned']};assert len(pinned)==3 and all(r['state']=='INTERRUPTED' for r in pinned.values())
    assert all({r['id']:r for r in status['hot']['leases']}[k]==v for k,v in pinned.items())
    old=c['archiveLookup']['lease'];assert not c['archiveLookup']['hot'] and rows[old['id']]==codec(old)
    selected=c['referenceObservation'];assert selected==read(root/'selected-cold-native-reference.json')
    assert selected['lease']==old and selected['hotAtObservation'] and old['owner']['job'] in job_ids
    observations=[json.loads(line) for line in (root/'server/lease-retired-observations.jsonl').read_text().splitlines()]
    assert selected in observations and old['id'] not in {r['id'] for r in c['inventory']['hot']['leases']}
    assert c['foreignDenied']['status']=='DENIED' and c['coldLookup']['lease']==old and c['coldLookup']['hotUnchanged']
    assert all(c['coldLookup'][k]['code'] not in ('GRANTED','VALID') for k in ('validate','renew','release'))
    process_replaced(c['restart']['before'],c['restart']['after']);archive=history(world)
    assert all(archive['leases'].get(k,rows[k])==rows[k] for k in archive['leases'])
    assert chest(world,[333,208,72]).get(LOG,0)==43 and not entities(world)[actor['entityId']].get('Inventory',[])
    original_view=read(root/'pinned-original/result.json')['after']['responsibility'];cuts=[]
    for point in ('after_segment_ack','after_directory_ack','before_hot_publication','after_hot_acknowledgement'):
        row=cases['native-cut-'+point]['evidence'];folder=root/('cut-'+point)
        assert read(folder/'result.json')==row and row['actuallyWitnessed']['point']==point.upper() and row['sameClients'] and row['saveBeforeCrash']
        process_replaced(row['beforeProcess'],row['afterProcess'])
        for field in ('id','submissionId','generation','allowance','usage'):assert row['original'][field]==original_view[field]
        for a,b in zip(row['original']['attempts'],original_view['attempts'],strict=True):
            for field in ('id','generation','allowance','usage','effects','credited','uncertain','cancellationDeliveries','receipts','resourceReceipts'):assert a[field]==b[field]
        assert not row['automaticContinuation']
        out=row['outcome'];world=folder/'restarted-world'
        if point=='after_segment_ack':
            assert out['storage']=='FENCED_PRESERVED_ORPHAN';assert 'minecraft:oak_log' in out['after']['blocks']['326,208,66']
            assert out['before']['containers']['333,208,72']==out['after']['containers']['333,208,72']
            assert not any(totals(out['negative'].get('resourceReceipts',[])).values())
            assert history(folder/'killed-world',allow_orphan=True)['orphans']
        else:
            assert out['storage']=='RESOLVABLE' and out['lookup']['lease']==old and out['lookup']['hotUnchanged']
            assert all(out['lookup'][k]['code'] not in ('GRANTED','VALID') for k in ('validate','renew','release'))
            inv=inventory(out['inventory']);assert inv[old['id']]==codec(old)
            assert all({r['id']:r for r in out['inventory']['hot']['leases']}[k]==v for k,v in pinned.items())
            history(world)
        cuts.append(point)
    quota=cases['native-archive-pressure-no-effect-refusal'];assert quota['fixture']['fixture'] and quota['fixture']['nativeEffects']==0
    fixture=quota['fixture'];assert fixture['classification']=='SYNTHETIC_EXPIRED_LEASE_METADATA_ONLY' and fixture['activeClaims']==0
    assert len(fixture['rows'])==fixture['added'] and all(r['lease']['state']=='EXPIRED' and not r['lease']['consumed']
        and r['lease']['identity']=='SYNTHETIC_METADATA_ONLY' and r['lease']['owner']['job'] not in job_ids
        and hashlib.sha256(json.dumps(codec(r['lease']),sort_keys=True,separators=(',',':')).encode()).hexdigest()==r['sha256'] for r in fixture['rows'])
    refusal=quota['refusal'];assert 'STORAGE_LIMIT_REACHED' in json.dumps(refusal['view']) and not any(totals(refusal['view']['resourceReceipts']).values())
    assert refusal['beforeDestination']==refusal['afterDestination'] and quota['status']['jobs']<64 and quota['status']['metrics']['hot']<=128
    return {'retiredLeases':129,'nativeDeliveries':43,'pinnedUnresolvedLeases':3,'liveLimit':64,'hotLimit':128,'cuts':cuts,
            'privateColdLookupAndStaleRefusal':True,'observations':observed_runs(root,[positive['view'],*[r['view'] for r in cycles]])}

def jobs(root,cases):
    positive=cases['native-positive-control']['evidence'];successful(positive['view'],2)
    wall=cases['actual-old-job-wall'];c=cases['continuous-native-over-64-terminal-jobs'];cycles=wall['cycles']+c['additional']
    header,oldrows=journal(root/'old-wall-native-jobs.jsonl');assert len(oldrows)==64
    assert wall['foreignDenied']['status']==c['foreignDenied']['status']=='DENIED'
    no_effect(wall['refusal'],'STORAGE_LIMIT_REACHED');pinned=wall['pinned'];assert pinned
    for row in pinned.values():assert row['state']=='INTERRUPTED' and any(a['uncertain'] for a in row['attempts'])
    world=root/'qualified-continuous-world';_,hot,archive,union=canonical_world(world)
    assert len(union)==len(oldrows)+8 and c['terminalJobs']==sum(j['state'] in ('SUCCEEDED','FAILED','CANCELLED') for j in union.values())>64
    assert len(cycles)>64 and len(cycles)==c['nativeCompanionDeliveries'] and c['after']['jobs']['hot']<=64 and c['after']['jobs']['active']<=32
    for n,cycle in enumerate(cycles):
        successful(cycle['view'],1);assert cycle['beforeDestination']==n and cycle['afterDestination']==n+1
        assert union[cycle['view']['responsibility']['id']]['state']=='SUCCEEDED'
    pinned_jobs(hot,pinned);old=wall['oldAuthorized'];replay_lookup(c['oldLookup'],old)
    assert old['job']['id'] not in hot and union[old['job']['id']] in oldrows
    actor=cycles[0]['view']['responsibility']['attempts'][-1]['worker']['entityId'];stock=len(cycles)
    assert chest(world,[333,208,72]).get(LOG,0)==stock and not entities(world)[actor].get('Inventory',[])
    for restart in [c['initialRestart'],*c['coldRestarts']]:process_replaced(restart['before'],restart['after']);assert restart['sameClients']
    costs={k:complete_performance(root,k) for k in ('baseline','candidate')};assert costs['baseline']['io']['sourceSha256']==costs['candidate']['io']['sourceSha256']
    for value in costs.values():
        assert value['nativeProcess']['containerResources']=={'cpusetCpus':'0-1','memoryBytes':2*1024**3}
        assert all(v['availableProcessors']==2 and {'-Xms256M','-Xmx1536M','-XX:G1HeapRegionSize=2M','-XX:MaxGCPauseMillis=10'}<=set(v['inputArguments']) for v in value['jvm'].values())
    cuts=[]
    for point in ('after_segment_ack','after_directory_ack','before_hot_publication','after_hot_acknowledgement'):
        row=cases['native-job-cut-'+point]['evidence'];folder=root/('cut-'+point);before=read(folder/'before-kill.json')
        assert read(folder/'result.json')==row and row['actuallyWitnessed']==before['reached'] and before['reached']['point']==point.upper()
        assert before['armed']['process']==before['reached']['process'] and before['armed']['token']==before['reached']['token']
        assert before['armed']['pid']==before['reached']['pid']==row['beforeProcess']['namespaceProcess']['pid']
        assert row['sameClients'] and not row['automaticContinuation'] and row['pinned']==pinned
        process_replaced(row['beforeProcess'],row['afterProcess']);_,cut_hot,cut_archive,cut_union=canonical_world(folder/'killed-world',allow_orphan=point=='after_segment_ack')
        assert all(cut_union.get(k)==v for k,v in union.items());pinned_jobs(cut_hot,pinned)
        produced=row['produced'];assert 1<=len(produced)<=20
        assert len(cut_union)==len(union)+len(produced)
        for n,cycle in enumerate(produced):
            assert cycle['beforeDestination']==stock+n;successful(cycle.get('view',cycle.get('lastView')),1)
        out=row['outcome']
        if point=='after_segment_ack':
            assert out['storage']=='FENCED'
            refused=json.dumps(out['negative']['result']);reason='INTERRUPTED' if 'INTERRUPTED' in refused else 'STORAGE_UNAVAILABLE'
            no_effect(out['negative'],reason,stock+len(produced))
        else:assert out['storage']=='RESOLVABLE' and out['foreign']['status']=='DENIED';replay_lookup(out['lookup'],old)
        physical=before['physical'];assert physical['containers']['333,208,72'].get(LOG,0)==stock+len(produced)
        assert not next(a['inventory'] for a in physical['actors'] if a['uuid']==actor)
        cuts.append({'point':point,'nativeTriggerDeliveries':len(produced)})
    for kind in ('segment-missing','segment-corrupt','version-missing'):
        row=cases['native-required-'+kind]['evidence'];folder=root/('required-'+kind)
        assert read(folder/'result.json')==row and row['allWriterFlagsOff'] and row['storage']['storage']['readOnly'] and row['lookup']['status']=='UNAVAILABLE'
        assert row['pinned']==pinned and all(row['afterHashes'].get(k)==v for k,v in row['beforeHashes'].items())
        assert set(row['afterHashes'])-set(row['beforeHashes'])<={'v1/writer.lock'}
        preserved=folder/'preserved-version'/(row['sourceDigest']+'.seg') if kind=='version-missing' else folder/'preserved-segment.seg'
        assert digest(preserved)==row['sourceDigest']
        refused=json.dumps(row['negative']['result']);reason='INTERRUPTED' if 'INTERRUPTED' in refused else 'STORAGE_UNAVAILABLE'
        no_effect(row['negative'],reason,stock)
    return {'logicalJobs':len(union),'terminalJobs':c['terminalJobs'],'nativeCurrentCompanionDeliveries':stock,'pinnedUnresolvedJobs':len(pinned),
            'cuts':cuts,'originalLimits':{'active':32,'hot':64},'performance':{k:{s:v[s] for s in ('tick','capture','ack')} for k,v in costs.items()},
            'exactCycleFrames':exact_cycle_frames(root/'server',cycles),'observations':observed_runs(root,[positive['view'],*[r['view'] for r in cycles]])}

def deposits(root,cases):
    from recovery_fixture import participant,marker
    from review_receipt_prefix import canonical,sha
    from deposit_fixture import preparation_window,tick_window
    from nonparticipant_fixture import review_world
    fixture=read(sorted(root.glob('a10-nonparticipant-*.json'))[0])
    task={'source':{'minX':70,'maxX':77,'minY':208,'maxY':208,'minZ':66,'maxZ':66},
          'destination':dict(zip(('x','y','z'),DEST))}
    fixture_worlds=[root/'positive-saved-game/world',*[root/name/'killed-world' for name in (
        'unsaved-before-mutation','unsaved-after-mutation','unsaved-after-journal','saved-prepared-no-effect',
        'journal-ahead-saved-prepared','actor-saved-chest-prepared','chest-saved-actor-prepared')]]
    saved_fixture={world.relative_to(root).as_posix():review_world(world,fixture,task) for world in fixture_worlds}
    positive=cases['saved-positive-repeated-adoption'];live=positive['live'];entry=live['journal']['entries'][0];token=next(iter(live['reconciliation']))
    assert live['reconciliation'][token]=='UNPROVEN' and positive['restored']['reconciliation'][token]=='SAVED_COMPLETE'
    assert not positive['verified']['protectedJobs'] and positive['verify']['classification']=='SAVED_COMPLETE'
    process_replaced(positive['nativeBeforeFirstKill'],positive['nativeAfterFirstRestart'])
    process_replaced(positive['repeatedRestart']['before'],positive['repeatedRestart']['after'])
    saved=root/'positive-saved-game/world';_,rows=journal(saved/'data/cognitivecraft/deposit-evidence/v1/state.jsonl');assert len(rows)==1
    raw=rows[0];assert raw['result']['live']=='COMPLETE' and raw['intent']['key']['step']==positive['job']['id']
    native=entities(saved);actor=raw['intent']['actor']['address'];box=raw['intent']['chest']['address'];position=list(map(int,box.split(',')))
    actual_box=[e for f in regions(saved,'region') for e in f.get('block_entities',[]) if [e.get(k) for k in ('x','y','z')]==position];assert len(actual_box)==1
    participant(native[actor],raw['result']['actor'],marker(raw),'Inventory');participant(actual_box[0],raw['result']['chest'],marker(raw),'Items')
    assert not native[actor].get('Inventory',[]) and chest(saved,position).get(LOG,0)==1
    for name in ('negative-equal-count-swap','negative-components','negative-replacement'):
        row=cases[name];assert row['before']['reconciliation'][token]=='SAVED_COMPLETE' and row['after']['reconciliation'][token]=='UNPROVEN'
        assert row['after']['protectedJobs'] and row['verify']['code']=='STALE';process_replaced(row['restart']['before'],row['restart']['after'])
    assert cases['unloaded-participants']['after']['reconciliation'][token]=='WAIT_WORLD'
    classifications={}
    for name in ('unsaved-before-mutation','unsaved-after-mutation','unsaved-after-journal','saved-prepared-no-effect','journal-ahead-saved-prepared','actor-saved-chest-prepared','chest-saved-actor-prepared'):
        row=cases[name];folder=root/name;assert read(folder/'result.json')==row and manifest(folder/'killed-world')==row['killedWorldFiles']
        before=read(folder/'before-kill.json');assert before['marker']==row['actuallyWitnessed'] and before['run']==row['run']
        assert before['nativeProcess']==row['nativeBefore'] and row['actuallyWitnessed']['token']
        process_replaced(row['nativeBefore'],row['nativeAfter'])
        _,entries=journal(folder/'killed-world/data/cognitivecraft/deposit-evidence/v1/state.jsonl');assert len(entries)==1
        assert entries[0]['intent']['key']['step']==row['restoredEvidence']['journal']['entries'][0]['intent']['key']['step']
        if name in ('saved-prepared-no-effect','journal-ahead-saved-prepared','actor-saved-chest-prepared','chest-saved-actor-prepared'):
            entry=entries[0];intent=entry['intent'];world=folder/'killed-world'
            expected={'schema':1,'effect':sha(canonical(intent['key'])),'intent':sha(canonical(intent)),'phase':'PREPARED','result':''}
            actor_result=name=='actor-saved-chest-prepared';chest_result=name=='chest-saved-actor-prepared'
            actual=entities(world)[intent['actor']['address']];pos=list(map(int,intent['chest']['address'].split(',')))
            boxes=[e for f in regions(world,'region') for e in f.get('block_entities',[]) if [e.get(k) for k in ('x','y','z')]==pos];assert len(boxes)==1
            participant(actual,entry['result']['actor'] if actor_result else intent['actor'],marker(entry) if actor_result else expected,'Inventory')
            participant(boxes[0],entry['result']['chest'] if chest_result else intent['chest'],marker(entry) if chest_result else expected,'Items')
            assert bool(entry.get('result'))==(name!='saved-prepared-no-effect')
        assert set(row['restoredEvidence']['reconciliation'].values())<=set(row['expectedClassification'])
        assert row['physicalAfter']['destination']==row['repeatedAdoption']['destination'] and row['physicalAfter']['actor']['inventory']==row['repeatedAdoption']['actor']['inventory']
        assert not row['newPhysicalExecutionAfterRestart'] and not row['automaticOriginalJobSettlement'];classifications[name]=list(row['restoredEvidence']['reconciliation'].values())
    for mode in ('full-quota','corrupt-current'):
        row=cases['native-storage-'+mode];folder=root/('storage-'+mode);declaration=read(folder/'fixture-declaration.json')
        assert declaration['syntheticMetadataIsNotNativeProvenance'] and digest(folder/'original-current.jsonl')==declaration['originalSha256']
        assert row['beforeStock']['destination']==row['afterStock']['destination']==1 and row['journalBytesUnchanged'] and not totals(row['view']['resourceReceipts'])['DEPOSIT']
        assert row['reader']['fenced']==(mode=='corrupt-current')
        if mode=='full-quota':assert len(row['reader']['journal']['entries'])==64 and row['afterStock']['actor']['inventory'].get(LOG,0)==1
        else:assert row['activationFlag']=='false' and not row['afterStock']['actor']['inventory'] and not row['view']['resourceReceipts']
    capture=read(root/'a05-preparation-window.json');assert preparation_window(capture['allNativeRawMetrics'])==capture['rawSamples']
    assert percentiles([r['nanos'] for r in capture['rawSamples']])==capture['preparation'] and capture['preparation']['p99']<=2
    acks=[n for r in capture['allNativeRawMetrics'] for n in r['metrics']['acknowledgementNanos']];assert len(acks)==13
    assert percentiles(acks)==capture['acknowledgement'] and capture['acknowledgement']['max']<=30000
    windows={}
    for mode in ('baseline','candidate'):
        row=read(root/('a05-'+mode+'-raw-window.json'));samples=row['rawSamples'];assert len(samples)==400
        assert [s['gameTick'] for s in samples]==list(range(row['begin']['gameTick']+1,row['begin']['gameTick']+401))
        measured=percentiles([s['nanos'] for s in samples]);assert measured==row['tick'] and measured['p95']<=20 and measured['p99']<=40
        windows[mode]=measured
    return {'rawSavedParticipants':'EXACT_ORIGINAL_RESULT','cuts':classifications,'actualIndependentPreparations':9,'actualAcknowledgements':13,
            'savedNormalAINonparticipantFixture':saved_fixture,
            'preparationMillis':capture['preparation'],'tickWindows':windows,'automaticOwnerSettlement':False,'syntheticPressureNativeEffects':0}

REVIEW={'A00':baseline,'A01':activity,'A02':persistence,'A03':leases,'A04':jobs,'A05':deposits}
