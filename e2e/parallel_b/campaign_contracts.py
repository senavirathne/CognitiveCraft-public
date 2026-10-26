#!/usr/bin/env python3
"""Frozen independent qualification definitions; definition counts are never native passes.

Only the Series-B markdown was supplied. The 12/30 supplemental IDs below are
locally specified fixture contracts, pending parity with any unsupplied bundle.
The original 334 IDs and their original assertion programs remain byte-pinned.
"""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import random
import sys

HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE.parent))
from parallel_b.heldout_bindings import resolve


def sha(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':')).encode()).hexdigest()


def file_sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()


def contracts():
    original=json.loads((HERE/'frozen/original-schedule.json').read_text())
    programs={n:file_sha(HERE/n) for n in ('frozen/original-invariants.py.txt','frozen/original-oracle.py.txt',
              'frozen/original-recipe_model.py.txt','interference_oracle.py','contract_runner.py')}
    material=json.loads((HERE/'freeze.json').read_text())['materialCases']
    rows=[]
    for case in original:
        rows.append({'id':case['id'],'definitionSha256':sha(case),'family':case['family'],
                     'cohort':'original-held-out' if case['heldOut'] else 'original-development',
                     'assertionPrograms':programs,'expectedDefinition':case,
                     'status':'NOT_EXECUTED_BY_B07','replacesOriginal':False})
    cohorts=[]
    for c in material:
        for kind in ('minimized','canonical-control','retry'):
            expected=json.loads(json.dumps(c));definition=json.loads(json.dumps(next(x for x in original if x['id']==c['id'])))
            definition.update(id=c['id']+'-b07-'+kind,cohort=kind,originalId=c['id'],surplus=0)
            if kind=='minimized':
                expected.update(requested=1,physical=4,surplus=3)
                if c['output']=='minecraft:birch_planks':expected['input']={next(iter(c['input'])):1}
                elif len(c['input'])==1:expected['input']={next(iter(c['input'])):2}
                definition['quantity']=1
            elif kind=='canonical-control':
                canonical='minecraft:birch_log' if c['output']=='minecraft:birch_planks' else 'minecraft:oak_planks'
                expected['input']={canonical:sum(c['input'].values())}
                definition['grid']=[canonical if x else x for x in definition['grid']]
            expected['id']=definition['id']
            cohorts.append({'id':c['id']+'-b07-'+kind,'originalId':c['id'],'cohort':kind,
                            'expected':expected,'definition':definition,'status':'NOT_EXECUTED','separateFromOriginal':True})
    cuts=[]
    pre={'sourceBlocks':4,'drops':0,'actor':0,'destination':0,'historicalCredit':0}
    vectors={'harvest':dict(sourceBlocks=3,drops=1,actor=0,destination=0,historicalCredit=0),
             'pickup':dict(sourceBlocks=3,drops=0,actor=1,destination=0,historicalCredit=0),
             'deposit':dict(sourceBlocks=3,drops=0,actor=0,destination=1,historicalCredit=1)}
    for op in vectors:
        before=pre if op=='harvest' else vectors['harvest' if op=='pickup' else 'pickup']
        for cut in ('before-native','after-native-before-publication','after-publication-before-save','after-certified-saves'):
            cuts.append({'id':f'fixture:b07-resource-{op}-{cut}','operation':op,'cut':cut,
                'before':before,'after':vectors[op],'requested':4,'remainingAfter':3 if op=='deposit' else 4,
                'expected':{'automaticReplay':False,'missingReceiptProvesNoEffect':False,
                    'privateOracleFeedsRuntime':False,'unknownEffect':'preserve original owner and scoped fence',
                    'resumeRequires':'A-owned causal effect/progress and participant-save metadata plus current stock'},
                'executionStatus':'INTEGRATION_PENDING','adapter':'A lifecycle effect identity/publication/save-cut port'})
    names=('stale-revision','duplicate-proposal','wrong-world','wrong-root','wrong-principal','replacement-actor',
           'old-final-goods-make','removed-input','foreign-root-custody','source-stack-swap','source-block-replacement',
           'table-replacement','double-chest-topology','destination-fill','hopper-source-removal','output-removal',
           'permission-revocation','shared-method-access','private-method-denial','revoked-method-denial','recipe-reload',
           'component-substitution','remainder-capacity','cancel-before-effect','stale-after-cancel','cumulative-budget',
           'original-deadline','missing-journal','torn-participant-save','corrupt-observer-input')
    adversarial=[]
    for i,name in enumerate(names,1):
        expected={'sourceLogs':1,'actorLogs':0,'actorPlanks':0,'destinationPlanks':0,'rootCredit':0,
                  'modelCallsAfterAdmission':0,'automaticReplay':False}
        if name in ('shared-method-access','old-final-goods-make'):
            expected.update(sourceLogs=0,destinationPlanks=4,rootCredit=4)
        if name=='old-final-goods-make':expected['destinationPlanks']=15
        if name in ('removed-input','source-stack-swap','hopper-source-removal'):
            expected.update(sourceLogs=0,externalPlayerOrHopperLogs=1)
        if name=='output-removal':expected.update(sourceLogs=0,externalPlanks=4,historicalCredit=4)
        if name=='destination-fill':expected['destinationCobblestone']=1728
        if name=='corrupt-observer-input':expected['oracle']='INVALID_EVIDENCE'
        if name in ('missing-journal','torn-participant-save'):
            expected={'participantVectors':['PRE/PRE','POST/PRE','PRE/POST','POST/POST'],
                      'ambiguous':'scoped fence original owner','automaticReplay':False,'rootCreditMax':4}
        adversarial.append({'id':f'fixture:b07-adversarial-{i:02d}','name':name,'quantity':4,
              'initial':{'sourceLogs':1,'actorLogs':0,'actorPlanks':0,'destinationPlanks':11 if name=='old-final-goods-make' else 0},
              'expected':expected,'status':'NOT_EXECUTED',
              'prerequisite':'A native principal grant/private-method/effect-save fixture port' if name in
                ('permission-revocation','private-method-denial','revoked-method-denial','missing-journal','torn-participant-save')
                else 'native public baseline action or isolated B host fixture',
              'runner':'interference_native.py' if name in ('removed-input','shared-method-access') else 'joint adapter: execute(request, cut), export_native_evidence()'})
    boundaries=[{'id':'fixture:b07-'+name,'status':'INTEGRATION_PENDING','automaticReplay':False,
                 'expected':expectation,'adapter':'A lifecycle archive/replay/migration/save metadata'} for name,expectation in (
        ('migration-legacy-immutable',{'oldArtifactBytesUnchanged':True,'oldValidation':'unchanged','newRouteEnabled':False}),
        ('archive-terminal-owner',{'terminalOwnerImmutable':True,'newAttemptCount':0,'newEffects':0}),
        ('replay-unknown-effect',{'newAttemptCount':0,'newEffects':0,'fencedOriginalOwner':True}),
        ('fixed-wheat-travel32',{'requested':3,'sourcePlants':3,'travelBudget':32,'budgetRenewals':0,
             'historicalReference':{'harvest':2,'pickup':2,'deposit':1},'originalOwnerAndDeadline':True,
             'completionRequiredAtI00':True,'noResetCountsAsEndurance':True}))]
    return {'schema':1,'provenance':'local supplemental fixtures; original bundle ZIP not supplied',
            'originalCount':334,'original':rows,'cohorts':cohorts,'resourceInterruptions':cuts,
            'craftPlacementSurplus':{'path':'choice-crash-cuts.json','sha256':file_sha(HERE/'choice-crash-cuts.json'),
                                    'count':20,'status':'INTEGRATION_PENDING'},
            'adversarial':adversarial,'boundaries':boundaries,'assertionPrograms':programs,
            'generatorPrograms':{name:file_sha(HERE/name) for name in ('campaign_contracts.py','heldout_bindings.py','frozen/original-generator.py.txt')},
            'nativePassesFromDefinitions':0,'freshSeedsCreated':False,
            'interference':{'attempts':2,'missedControls':1,'requiredWitnesses':1,
                'window':'captured recipe preflight -> final legacy withdrawal return; no tick split inside atomic mutation',
                'inputItem':'minecraft:oak_log','movedItem':'minecraft:cobblestone',
                'normalAction':'PlayerB QUICK_MOVE the unrelated cobblestone stack into own inventory; preserve the original leased oak input stock',
                'ack':'native moved-stack decrement and PlayerB inventory increment plus synchronized real client menu'}}


def held_out(seed):
    """Independent one-factor variations; fixture tests never claim fresh holdout evidence."""
    rng=random.Random(seed);rows=[]
    dimensions={'material':['minecraft:oak_log','minecraft:birch_wood','minecraft:stripped_birch_log'],
                'quantity':[1,3,4,7,11],'actor':[0,1],'sourceBinding':[0,1,2],
                'destinationBinding':[0,1,2],'geometry':['near','offset','around-obstacle'],
                'dimension':['minecraft:overworld']}
    base={k:rng.choice(v) for k,v in dimensions.items()}
    for axis,values in dimensions.items():
        for value in values:
            binding={**base,axis:value};q=binding['quantity'];n=(q+3)//4
            rows.append({'id':'B07-H-'+sha([seed,axis,value,binding])[:16],'seed':seed,'axis':axis,
                         'bindings':binding,'nativeBindings':resolve(binding),'expected':{'inputLogs':n,'physicalPlanks':4*n,
                             'delivered':q,'actorSurplus':4*n-q,'rootRequested':q},
                         'status':'NOT_EXECUTED','dimensionSupport':'only baseline overworld; no extra dimension claim'})
    return rows


def validate(value):
    if value!=contracts():raise ValueError('Frozen assertion/definition bytes changed')
    if len({r['id'] for r in value['original']})!=334:raise ValueError('Original case lost')
    if len(value['resourceInterruptions'])!=12 or len(value['adversarial'])!=30:raise ValueError('Supplemental denominator changed')
    return {'integrity':'PASS','originalDefinitions':334,'supplementalResourceCuts':12,'adversarialDefinitions':30,
            'nativeExecuted':0,'mandatoryGameplay':'NOT_QUALIFIED_BY_MANIFEST'}


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['freeze','verify','held-out'])
    p.add_argument('--output',type=Path);p.add_argument('--seed',type=int);p.add_argument('--freeze-commit');p.add_argument('--entropy-run')
    a=p.parse_args();path=HERE/'qualification-specs.json'
    if a.action=='freeze':
        if path.exists():raise ValueError('Refuse to overwrite frozen definitions')
        path.write_text(json.dumps(contracts(),indent=2)+'\n')
    elif a.action=='verify':print(json.dumps(validate(json.loads(path.read_text()))))
    else:
        validate(json.loads(path.read_text()))
        if not a.output or a.output.exists() or a.seed is None or not a.freeze_commit or not a.entropy_run:
            raise ValueError('New output, seed and prior freeze/entropy provenance required')
        a.output.write_text(json.dumps({'schema':1,'freezeCommit':a.freeze_commit,'entropyRunAfterFreeze':a.entropy_run,
            'seed':a.seed,'generatorSha256':file_sha(Path(__file__)),'assertionSpecSha256':file_sha(path),
            'cases':held_out(a.seed),'nativeExecuted':0},indent=2)+'\n')


if __name__=='__main__':main()
