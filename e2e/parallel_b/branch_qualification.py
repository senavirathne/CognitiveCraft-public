#!/usr/bin/env python3
"""B08 exact-candidate gates. Shared integration/endurance are deliberately unclaimed."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from suite import ROOT,atomic,read,require
from runtime import digest
from parallel_b.stage_review import bundle
from parallel_b.guards import clean_owners
from parallel_b.interference_oracle import evaluate

BASE='ea7de8000b2b484b885576c34ed5358274936ce9'
HOSTS=['GeneralizedRecipeChoiceGameTests','ConcretePlankChoiceGameTests','PlanningChoiceHostGameTests',
       'BirchPlankEffectGameTests','OakPlankEffectGameTests','StickEffectGameTests','AxeEffectGameTests',
       'TableEffectGameTests','PlacementEffectGameTests','ParallelBLegacyBreadGameTests',
       'OakLogEffectGameTests','BirchLogEffectGameTests']


def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT,text=True).strip()


def ownership():
    paths=git('diff','--name-only',BASE,'HEAD').splitlines();bad=[];production=[];imports=[]
    core={'ResourceComposition','DeterministicPrerequisiteResolver','RecipeProduction','RecipeTransaction'}
    fabric={'FabricRecipeTransactions','FabricTablePlacement','FabricResourceItems','FabricConcreteRecipeChoices','FabricHostObservations'}
    for name in paths:
        p=ROOT/name
        if '/src/main/' in name:
            allowed=('/core/kernel/production/' in name or name.startswith('core/') and p.stem in core or name.startswith('fabric/') and p.stem in fabric)
            production.append({'path':name,'owned':allowed,'sha256':digest(p)})
            if not allowed:bad.append(name)
            for line in p.read_text().splitlines():
                if not line.startswith('import dev.aivillages.'):continue
                symbol=line.removeprefix('import ').rstrip(';');parts=symbol.split('.')
                if symbol.endswith('.*'):
                    relative=symbol[:-2].replace('.','/')
                    if symbol[:-2].split('.')[-1][0].isupper():relative+='.java'
                    owned=relative.startswith('dev/aivillages/core/kernel/production')
                    existing=any(subprocess.run(['git','cat-file','-e',BASE+':'+module+'/src/main/java/'+relative],cwd=ROOT,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0 for module in ('core','fabric','providers'))
                    imports.append({'source':name,'import':symbol,'existingAtBase':existing,'ownedBType':owned,'wildcardPackage':True})
                    if not existing and not owned:bad.append(name+':'+symbol)
                    continue
                while parts and not parts[-1][0].isupper():parts.pop()
                # Nested imports resolve to the first class, never to an unlanded peer.
                k=next((i for i,v in enumerate(parts) if v[0].isupper()),len(parts))
                relative='/'.join(parts[:k+1])+'.java'
                owned=relative.startswith('dev/aivillages/core/kernel/production/') or parts[k] in fabric if k<len(parts) else False
                existing=any(subprocess.run(['git','cat-file','-e',BASE+':'+module+'/src/main/java/'+relative],cwd=ROOT,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0 for module in ('core','fabric','providers'))
                imports.append({'source':name,'import':symbol,'existingAtBase':existing,'ownedBType':owned})
                if not existing and not owned:bad.append(name+':'+symbol)
        elif name.startswith('.github/') and name!='.github/workflows/parallel-b-production.yml':bad.append(name)
        elif p.name in ('build.gradle','build.gradle.kts','gradle.properties','settings.gradle','settings.gradle.kts','dependencies.json'):bad.append(name)
    merges=git('rev-list','--min-parents=2',BASE+'..HEAD').splitlines()
    return {'base':BASE,'sourceCommit':git('rev-parse','HEAD'),'sourceTree':git('rev-parse','HEAD^{tree}'),
            'changedPaths':paths,'productionFiles':production,'imports':imports,'violations':bad,'merges':merges,
            'status':'PASS' if not bad and not merges else 'FAIL',
            'reviewedBoundary':'B pure proposal/choice helpers and native host implementations only; no lifecycle/store/lease/Kernel/build/production registration edits'}


def exact(meta,head,tree,jar):
    require((meta['sourceCommit'],meta['sourceTree'],meta['dirty'])==(head,tree,False),'Foreign or dirty final native source')
    require(meta.get('releaseSha256',meta.get('productionSha256'))==jar,'Final native binary differs across gates')
    for key in ('GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT'):
        require(str(meta.get('ci',{}).get(key))==os.environ.get(key),'Foreign final native run/attempt')


def qualify(root,output):
    head=git('rev-parse','HEAD');tree=git('rev-parse','HEAD^{tree}');jar=digest(ROOT/'fabric/build/libs/ai-villages-0.1.0.jar')
    require(not git('status','--porcelain'),'Final candidate must be committed and clean')
    owned=ownership();require(owned['status']=='PASS','A-owned edit, unlanded peer import or merge')
    hosts=read(root/'hosts/metadata.json');exact(hosts,head,tree,jar)
    require(hosts['nativeHostOnly'] is True and hosts['connectedClients']==0,'Host tests mislabelled as gameplay')
    require({p['entrypoint'].rsplit('.',1)[1] for p in hosts['phases']}==set(HOSTS),'Missing broad native host phase')
    require(all(p['result']=='PASS' and p['nativeCases']>0 for p in hosts['phases']),'Failed native host phase')
    reports={}
    for name in ('quantity','harness','diagnostics','interference'):
        base=root/name;meta=read(base/'metadata.json');exact(meta,head,tree,jar);r=read(base/'result.json')
        require(r['status']=='PASS','Failed mandatory native track: '+name);reports[name]=r
    for q in (3,4):
        r=read(root/'quantity'/f'q{q}.json');owner=r['root']
        require(owner['state']=='SUCCEEDED' and owner['fulfilled']==q and owner['request']['request']['arguments']['amount']['value']==q,'Original exact quantity/root changed')
        require(r['callsBefore']==r['callsAfter'],'Quantity reran model')
    same=read(root/'quantity/same-container.json')
    require(same['root']['fulfilled']==3 and same['receiptQuantities']=={'WITHDRAW':1,'CRAFT':4,'DEPOSIT':3},'Old stock replaced new MAKE credit')
    require(read(root/'quantity/capacity-negative.json')['status']=='PASS_EXPECTED_REFUSAL','Capacity guard missing')
    require(all(clean_owners(x) for x in reports['quantity']['finalSettlement']['nativeSnapshotAfterSettlement']['knownPlayers'].values()),'Quantity seed has open owners')
    q=read(root/'quantity/qualification.json');require(q['integrity']=='PASS' and q['mandatoryGameplay']=='PASS','Quantity integrity/gameplay gate')
    q=read(root/'harness/qualification.json');require(q['native']['integrity']=='PASS' and q['native']['mandatoryGameplay']=='PASS' and len(q['negativeCollectors'])==5,'Harness native/negative collector gate')
    kill=read(root/'harness/kill-witness.json');require(kill['processExitObserved'] and kill['disconnectionObserved'] and kill['clientDisconnection']['play'] is False,'Actual native kill/disconnection missing')
    require(len(reports['diagnostics']['rows'])==22,'Controlled native matrix incomplete')
    windows=[]
    for p in sorted((root/'interference/cases').glob('*.json')):
        row=read(p);require('proof' in row and evaluate(row['proof'])==row['oracle'],'Final interference evidence/strict oracle mismatch')
        require(all(clean_owners(x) for x in row['settlement']['nativeSnapshotAfterSettlement']['knownPlayers'].values()),'Interference fixture reset under an open owner')
        windows.append(row['oracle']['status'])
    require(windows.count('WITNESSED_SAFE_REFUSAL')>=1 and windows[-1]=='INCONCLUSIVE','Real versus missed-window mandatory gate')
    require(read(root/'interference/shared-reuse.json')['assertionStatus']=='PASS','Shared-method/privacy gate')
    audit_rows=[]
    for name in reports:
        audit=read(root/name/'resource-acquisition-audit.json');require(audit and audit['corruptTraceLines']==0,'Missing/corrupt admission and resource audit')
        audit_rows+=audit['admissions']
    require(len(audit_rows)==4 and all(x['modelAfter']['generationCalls']>x['modelBefore']['generationCalls'] for x in audit_rows),'Every genuine admission must be separately audited')
    output.mkdir(parents=True,exist_ok=False);atomic(output/'ownership-audit.json',owned)
    packages={name:bundle(root/name,output/name) for name in reports}
    for p in (root/'hosts').rglob('*'):
        if p.is_file() and 'game' not in p.relative_to(root/'hosts').parts:
            dst=output/'hosts'/p.relative_to(root/'hosts');dst.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,dst)
    result={'schema':1,'status':'OWNED_GATES_PASS','branchStatus':'HANDOFF_PENDING_DOCUMENTATION','sourceCommit':head,'sourceTree':tree,'productionSha256':jar,
        'run':os.environ['GITHUB_RUN_ID'],'attempt':os.environ['GITHUB_RUN_ATTEMPT'],
        'moduleTests':packages['quantity']['modules'],'nativeHostGameTests':sum(p['nativeCases'] for p in hosts['phases']),
        'nativeHostPhases':len(hosts['phases']),'diagnosticTrials':22,'interferenceWindows':windows,
        'separateAuditedGenuineAdmissions':len(audit_rows),'newV2PlayerRoute':'INTEGRATION_PENDING_DISABLED',
        'full334':'NOT_EXECUTED_I00','all30':'NOT_EXECUTED_I00','endurance1000':'NOT_EXECUTED_I00',
        'I05':'UNKNOWN','W05':'UNKNOWN','W06':'UNKNOWN','mergedMainOrPeer':False}
    atomic(output/'qualification.json',result);atomic(root/'qualification.json',result)
    print('B08_FINAL_QUALIFICATION',json.dumps(result),flush=True);return result


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path);p.add_argument('--output',type=Path,required=True);p.add_argument('--ownership-only',action='store_true');p.add_argument('--retain-incomplete',action='store_true');a=p.parse_args()
    if a.ownership_only:
        v=ownership();atomic(a.output,v);require(v['status']=='PASS','Owned boundary audit failed');print(json.dumps({'status':v['status'],'productionFiles':len(v['productionFiles']),'paths':len(v['changedPaths'])}))
    elif a.retain_incomplete:
        a.output.mkdir(parents=True,exist_ok=False)
        for p in a.root.rglob('*'):
            relative=p.relative_to(a.root)
            if not p.is_file() or 'game' in relative.parts or p.name=='observations.jsonl':continue
            if p.suffix not in ('.json','.xml','.log','.jsonl','.png'):continue
            if p.suffix=='.jsonl' and p.name not in ('b-guards.jsonl','events.jsonl'):continue
            q=a.output/relative;q.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,q)
        atomic(a.output/'nonqualifying-collection.json',{'source':git('rev-parse','HEAD'),'tree':git('rev-parse','HEAD^{tree}'),
            'run':os.environ.get('GITHUB_RUN_ID'),'attempt':os.environ.get('GITHUB_RUN_ATTEMPT'),
            'collectionOnly':True,'nativeGate':'NOT_QUALIFIED','reason':'Original failed/incomplete candidate retained; no prior passing archive substituted'})
    else:qualify(a.root,a.output)


if __name__=='__main__':main()
