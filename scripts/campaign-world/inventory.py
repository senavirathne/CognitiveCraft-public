#!/usr/bin/env python3
"""Implementation-derived inventory; static presence never counts as gameplay coverage."""
import json
import re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'e2e/campaigns/world'
p=ROOT/'core/src/main/java/dev/aivillages/core/kernel/GatewayPrimitives.java'
text=p.read_text(); rows=[]
for match in re.finditer(r'^        ([A-Z_]+)\("([a-z_]+)",\s*Type\.[A-Z]+,\s*Effect\.([A-Z]+)',text,re.M):
    operation,name,effect=match.groups()
    recipe=(any(x in operation for x in ('PLANK','STICK','AXE','TABLE','INPUT','PLACEMENT'))
            or operation.startswith('WITHDRAW_'))
    family={'MOVE':'W1','HARVEST':'W2','PICKUP':'W3','TRANSFER':'W4','OBSERVE':'W2','WITHDRAW':'W4','CRAFT':'Agent 2','PLACE':'Agent 2'}[effect]
    world_effect=not recipe
    rows.append(dict(capability=operation,primitive='cognitivecraft:'+name,effect=effect,
        implementation=f'{p.relative_to(ROOT)}:{text[:match.start()].count(chr(10))+1}',
        subsystem='SurvivalGateway / BoundedSkillExecutor / FabricGatewayWorld',
        gameplayEntryPoint='/aivillage kernel harvest' if not recipe and not any(x in operation for x in ('OAK','BIRCH')) else '/aivillage kernel gather-birch' if 'BIRCH' in operation else '/aivillage kernel gather-oak' if not recipe else '/aivillage kernel make-* or place-table; see KernelCommands',
        supportedDomain=('Exact default oak/birch logs; acquisition amount 1..8; loaded bounded cuboid; exact scoped actor; native 2.5-block interaction reach, safe standing cell, capacity and effect-time lease guards' if any(x in operation for x in ('OAK','BIRCH')) and not recipe else
                         'Mature wheat only; amount 1..64; loaded bounded cuboid; exact scoped actor; tracked drops and native reach/capacity/effect-time lease guards' if not recipe else
                         'Installed exact recipe/placement signature; amount 1..64 or axes/tables 1..4; default components; physical custody and native facility/standing geometry guards'),
        reachability='CLIENT_REACHABLE as part of registered capability; primitive is not itself a player command',
        existingCoverage=['e2e/suite.py','e2e/birch.py' if 'BIRCH' in operation else 'e2e/oak.py' if 'OAK' in operation and not recipe else 'e2e/generalization.py','e2e/coverage.json','e2e/rb-coverage.json'],
        dimensions=['coordinates','quantity','actor position','geometry','container kind','occupancy','obstacle'],
        owner='Agent 1' if world_effect else 'Agent 2 primary recipe/placement; Agent 1 physical transfer reconciliation pending',
        family=family,generatedCandidateVariations=12 if world_effect else 0,generatedVariations=0,genuineExecutions=0,successful=0,failed=0,safeRefusals=0,
        observability='Native block/drop/inventory snapshots + actor UUID movement + run receipts + client state/menu + leases',
        limitation='UNVERIFIED_PRIMITIVE_PATH: native logs do not expose every primitive start/completion; candidate requests cannot certify this particular opcode without an invocation witness' if world_effect else 'EXCLUDED_AGENT_2_PRIMARY: no dependency on unmerged work; physical boundary reconciliation remains open'))
extras=[
('legacy-harvest-replant','FarmPort.crop','/aivillage food NAME; /aivillage teach NAME','W2','Mature wheat; automatic seed replant when available; bounded work area'),
('legacy-plant','FarmPort.crop','/aivillage food NAME','W2','Seeds in actor custody; air over valid farmland'),
('legacy-loose-pickup','FarmPort.collect','/aivillage food NAME','W3','Only wheat and wheat seeds; native item pickup delay and inventory capacity'),
('legacy-bread-craft','FarmPort.craft','/aivillage food NAME','Agent 2','Three wheat plus nearby crafting table'),
('legacy-bread-delivery','FarmPort.deliver','/aivillage food NAME','W4','Unlocked chest/barrel at home; native stack limits'),
('legacy-go-home','FarmPort.approach','/aivillage home NAME X Y Z; /aivillage food NAME','W1','Bounded home/work area and native navigation'),
('legacy-enroll-release-auto-stop','VillageSession','/aivillage enroll NAME; release NAME; auto NAME BOOL; stop NAME','W5','Nearby adult, player ownership and safety checks'),
('kernel-enroll-name','KernelCommands / CitizenRegistry','/aivillage kernel enroll; name ID NAME','W5','Nearby normal adult; durable owner identity/name'),
('citizen-lifecycle','FabricCitizenLifecycle / CitizenLifecycle / CitizenWorkReconciler','Native chunk unload/reload, death/removal and same-save restart','W5','Same entity and generation; no unsupported successor inheritance'),
('nearest-reference','FabricWorldReferenceResolver / WorldReferenceBinding','/aivillage kernel ask TEXT','W4','Typed loaded eligible nearest candidate, scoped authority and capacity; actual Needle required'),
('cancellation','KernelSession / BoundedSkillExecutor','/aivillage kernel cancel ID; job-cancel ID','W6','Exact owner, partial truthful receipts'),
('leases-contention','FabricResourceLeaseWorld / ResourceLeaseService','Two real principals submit competing gameplay requests','W6','Exact scoped resource owner and effect-time validation'),
('acquisition-delivery-recovery','KernelSession / repository / CitizenWorkReconciler','save-all flush + actual server termination + reconnect + production commands','W6','Same saved world, admitted method, identity and durable custody; interruption without replay'),
]
for name,impl,entry,family,domain in extras:
    rows.append(dict(capability=name,primitive=None,implementation=impl,subsystem='Legacy farming' if name.startswith('legacy') else 'Kernel/session/lifecycle',gameplayEntryPoint=entry,actualEffect=name,supportedDomain=domain,reachability='CLIENT_REACHABLE',existingCoverage=['e2e/suite.py','e2e/lifecycle.py' if 'lifecycle' in name else 'e2e/coverage.json'],dimensions=['identity','position','capacity','authority','lifecycle boundary'],owner='Agent 2' if family=='Agent 2' else 'Agent 1',family=family,generatedVariations=12 if name=='kernel-enroll-name' else 0,genuineExecutions=0,successful=0,failed=0,safeRefusals=0,observability='Native entity identity/lifecycle, raw world snapshots, client feedback and durable run/lease records',limitation='W5 currently tests rename only; fresh enrollment adapter not complete' if name=='kernel-enroll-name' else 'Explicit adapter coverage limitation: generic acquisition does not certify this capability; dedicated generator/observer assertions remain required'))
for row in rows:
    row.setdefault('actualEffect',row.get('effect'))
    row['blocked']=0; row['inconclusive']=0
    if row['capability']=='kernel-enroll-name':
        row['generatedCandidateVariations']=12; row['generatedVariations']=0
        row['limitation']='W5 varies naming, genuine fresh enrollment, reconnect, same-entity unload/reload and same-save restart; actual results are reconciled separately'
    if row['capability']=='citizen-lifecycle':
        row['generatedCandidateVariations']=12
        row['limitation']='W5 covers unload/reload and restart; generated death, removal, unsupported replacement and cross-owner effects still require adapters'
    if row['capability']=='acquisition-delivery-recovery':
        row['generatedCandidateVariations']=12
        row['limitation']='W6 attempts witnessed HARVEST/PICKUP/DEPOSIT checkpoints, actual SIGKILL, same save and same client process; missed boundaries remain INCONCLUSIVE'
body=dict(schema=1,baseline='ea7de8000b2b484b885576c34ed5358274936ce9',productionTree='f0915a127521cb32f45e606c39098011796d1f1d',inventoryStatus='INITIAL_IMPLEMENTATION_AUDIT_REQUIRES_RECONCILIATION',coverageClaim='NONE',capabilities=rows,unsupported=['spruce or stripped log harvesting','carrot harvesting','arbitrary block placement'],auditSources=['fabric/src/main/java/dev/aivillages/fabric/FabricGatewayWorld.java','fabric/src/main/java/dev/aivillages/fabric/FarmPort.java','fabric/src/main/java/dev/aivillages/fabric/KernelCommands.java','fabric/src/main/java/dev/aivillages/fabric/VillageCommands.java','fabric/src/main/java/dev/aivillages/fabric/FabricWorldReferenceResolver.java','fabric/src/main/java/dev/aivillages/fabric/FabricCitizenLifecycle.java','e2e/observer/java/dev/aivillages/fabric/E2EObserver.java','e2e/client/java/dev/aivillages/e2e/ClientDriver.java','e2e/fixtures.py','e2e/suite.py','e2e/generalization.py','e2e/coverage.json','e2e/rb-coverage.json'])
(OUT/'inventory.json').write_text(json.dumps(body,indent=2)+'\n')
print(f'{len(rows)} inventory rows; no gameplay coverage claimed')
