#!/usr/bin/env python3
"""Execute supplemental native contracts through an explicitly supplied joint adapter.

Adapter protocol: execute(contract) returns an immutable evidence directory with
metadata.json, before.json, after.json, production.json and measurements.json.
The adapter controls production requests, never receives private oracle snapshots.
Absent ports are recorded INTEGRATION_PENDING, not emulated by a second runtime.
"""
from __future__ import annotations
import argparse
import importlib.util
import json
from pathlib import Path
import sys
from campaign_contracts import HERE,validate,file_sha


def check(contract, evidence, expected_source, expected_jar):
    def load(name):return json.loads((evidence/name).read_text())
    meta=load('metadata.json');production=load('production.json');actual=load('measurements.json')
    if (meta.get('sourceCommit'),meta.get('releaseSha256'),meta.get('dirty'))!=(expected_source,expected_jar,False):
        raise ValueError('Foreign native producer identity')
    if not all((evidence/n).is_file() for n in ('before.json','after.json','production.json')):
        raise ValueError('Missing independent native participants or production metadata')
    if not production.get('originalOwner') or production.get('privateOracleInputs') is not False:
        raise ValueError('Original production owner and oracle separation required')
    expected=contract.get('expected',{})
    assertions={k:actual.get(k)==v for k,v in expected.items() if isinstance(v,(int,bool))}
    if not assertions:raise ValueError('No executable quantitative assertions')
    if 'cut' in contract:
        if production.get('witnessedCut')!=contract['cut']:
            return {'status':'INCONCLUSIVE','reason':'Requested native boundary not witnessed','assertions':assertions}
        if contract['cut']!='before-native':
            assertions.update({k:actual.get('afterNative',{}).get(k)==v for k,v in contract['after'].items()})
        if contract['cut']=='after-certified-saves':
            assertions['causalParticipantSaveMetadata']=production.get('causalParticipantSaveMetadata') is not None
        else:assertions['noUnknownReplay']=production.get('unknownEffectsReplayed')==0
    return {'status':'PASS' if all(assertions.values()) else 'FAIL_GAMEPLAY','assertions':assertions,
            'evidence':str(evidence),'metadataSha256':file_sha(evidence/'metadata.json')}


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--adapter',type=Path);p.add_argument('--source');p.add_argument('--jar');a=p.parse_args()
    specs=json.loads((HERE/'qualification-specs.json').read_text());validate(specs)
    if a.output.exists():raise ValueError('Retain first failures; use a new output path')
    adapter=None
    if a.adapter:
        if not a.source or not a.jar:raise ValueError('Pin native source and binary')
        spec=importlib.util.spec_from_file_location('joint_qualification_adapter',a.adapter)
        adapter=importlib.util.module_from_spec(spec);spec.loader.exec_module(adapter)
    rows=[]
    for c in specs['resourceInterruptions']+specs['adversarial']+specs['boundaries']:
        if adapter is None or not adapter.supports(c):
            row={'status':'INTEGRATION_PENDING','nativeExecuted':False,'reason':'Required native fixture port absent'}
        else:
            row=check(c,Path(adapter.execute(c)),a.source,a.jar);row['nativeExecuted']=True
        rows.append({'id':c['id'],**row})
    a.output.parent.mkdir(parents=True,exist_ok=True)
    a.output.write_text(json.dumps({'schema':1,'rows':rows,'nativeExecuted':sum(r['nativeExecuted'] for r in rows)},indent=2)+'\n')


if __name__=='__main__':main()
