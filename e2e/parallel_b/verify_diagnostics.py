#!/usr/bin/env python3
"""Check B06 raw connected-client trials without inferring historical issue closure."""
import collections
import hashlib
import json
import pathlib
import sys

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def load(path):return json.loads(path.read_text())

def clean(data):
    owners=list(data.get('jobs',{}).values())
    for run in data.get('runs',{}).values():
        assert run['phase'] not in {'PERSISTING','ROUTING','EXECUTING','RESEARCHING','RESOLVING','INTERPRETING'}
        assert run.get('responsibility') is not None
        owners.append(run['responsibility'])
    return all(o['state'] in {'SUCCEEDED','FAILED','CANCELLED'} and isinstance(o.get('attempts'),list)
               and all(a.get('terminal') is not None and a.get('uncertain') is False for a in o['attempts']) for o in owners)

def verify(base,head,tree,matrix_source):
    metadata=load(base/'metadata.json');manifest=load(base/'manifest.json');matrix=load(base/'matrix.json');result=load(base/'result.json')
    assert metadata['sourceCommit']==head and metadata['sourceTree']==tree and not metadata['dirty']
    assert metadata['profile']=='B06' and sha(matrix_source)==metadata['matrixSha256'] and load(matrix_source)==matrix
    for entry in manifest['files']:
        path=base/entry['path'];assert path.stat().st_size==entry['bytes'] and sha(path)==entry['sha256'],entry['path']
    assert result['status']=='PASS' and result['issueClosure']=={'W-05':'UNKNOWN','W-06':'UNKNOWN'}
    assert not result['automaticRecovery'] and result['connectedClients']==2
    rows=[load(p) for p in sorted((base/'cases').glob('*.json'))]
    assert len(rows)==len(result['rows'])==len(matrix['seeds'])*len(matrix['variants'])==22
    assert matrix['families']['seven-log']['item']=='minecraft:birch_log'
    expected={(seed,v['id']) for seed in matrix['seeds'] for v in matrix['variants']}
    assert {(r['scheduleSeed'],r['variant']['id']) for r in rows}==expected
    distribution=collections.Counter();physical_runs=0;native_refusals=[];stable=collections.Counter()
    enrolled=load(base/'enrollment-fixture.json')['subjects']
    assert len(enrolled)==2 and len({x['entity'] for x in enrolled})==len({x['citizen'] for x in enrolled})==2
    for r in rows:
        assert r['assertionStatus']=='PASS' and r['settlement']['clean']
        settled=r['settlement']['nativeSnapshotAfterSettlement']
        assert all(clean(data) for data in settled['knownPlayers'].values())
        assert not r['automaticReplay'] and r['retryOf'] is None and r['callsBefore']==r['callsAfter']
        assert r['targetObservation']['worldEffects']==r['targetAfter']['worldEffects']==0
        variant=r['variant']['id'];family=matrix['families'][r['family']]
        assert r['item']==family['item'] and r['quantity']==family['quantity']
        assert r['methodPin']==result['methods']['cognitivecraft:'+('deliver_birch_logs' if r['family']=='seven-log' else 'produce_oak_planks')]
        distribution[r['classification']]+=1
        view=r.get('view') or {};receipts=view.get('resourceReceipts',[])
        if receipts:physical_runs+=1
        if r['classification']=='SUCCEEDED':
            assert r['delivered']==r['quantity'] and view['marker']['artifactSha256']==r['methodPin']
            stable[r['family']]+=1
            assert sum(x['quantity'] for x in receipts if x['stage']=='DEPOSIT')==r['quantity']
        if r['classification']=='PARTIAL_PRESERVED_NO_REPLAY':
            assert view['marker']['outcome']!='SUCCEEDED'
            original=view['responsibility']['id']
            assert any(a['job']==original and 'cancellation=true' in a['feedback'] for a in r['settlement']['cancellationAcknowledgements'])
            owner=next(v['responsibility'] for v in settled['knownPlayers']['PlayerA']['runs'].values() if v.get('responsibility',{}).get('id')==original)
            assert owner['state']=='CANCELLED'
            actor=view['responsibility']['request']['request']['arguments']['actor']['value']['entityId']
            before_custody=next(a['inventory'] for a in r['after']['actors'] if a['uuid']==actor)
            after_custody=next(a['inventory'] for a in settled['actors'] if a['uuid']==actor)
            assert before_custody==after_custody
        if variant in ('missing-target','moved-target','full-target','unloaded-target'):
            assert r['physicalBefore']==r['physicalAfter'] and not receipts
            assert r['classification']!='SUCCEEDED'
            native_refusals.append({'seed':r['scheduleSeed'],'variant':variant,'feedback':r['request']['feedback'],
                                   'description':view.get('description'),'returnedPreflights':[g['actualReturned'] for g in r['guards'] if g['phase']=='preflight']})
        if variant in ('unloaded-target','loaded-target'):
            assert r['targetObservation']['positions'][0]['position']==matrix['farPair']['destination']
            assert r['targetObservation']['positions'][0]['loaded']==(variant=='loaded-target')
    assert all(stable[family]>=2 for family in matrix['families'])
    assert dict(distribution)==result['distribution']
    guards=[json.loads(x) for x in (base/'server/b-guards.jsonl').read_text().splitlines()]
    assert guards and all(x['readOnly'] and not x['saveConfirmed'] for x in guards)
    return {'integrity':'PASS','mandatoryDiagnostic':'PASS','source':head,'tree':tree,
            'manifestFiles':len(manifest['files']),'connectedClientTrials':len(rows),'physicalEffectRuns':physical_runs,
            'distribution':dict(distribution),'successfulTrialsPerFamily':dict(stable),'guardRows':len(guards),
            'nativeRefusals':native_refusals,'methods':result['methods'],'issueClosure':result['issueClosure'],
            'automaticRecovery':False,'productionSha256':metadata['releaseSha256']}

if __name__=='__main__':print(json.dumps(verify(pathlib.Path(sys.argv[1]),sys.argv[2],sys.argv[3],pathlib.Path(sys.argv[4])),indent=2))
