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
('legacy-group-food','VillageSession.villageFood','/aivillage village','W8','Nearby authorized live idle workers; bounded group admission and individual work areas'),
('legacy-safety-interruption','FarmPort.unsafe / VillageSession.controls','Native damage, fire, trade, sleep or visible monster during /aivillage food','W8','Real health/fire/trade/sleep/monster conditions; no invented safety receipt'),
('legacy-death','VillageSession.died / AiVillages.AFTER_DEATH','Native entity death after /aivillage enroll','W5','Default permanent death; stop work and preserve terminal legacy identity'),
('legacy-configured-respawn','VillageSession.tryRespawn / ModConfig','Configured deathMode=respawn followed by native death and delay','W5','Implemented only with nondefault server configuration; loaded safe home cell and actual new entity UUID'),
('legacy-conversion-continuity','VillageSession.converted / AiVillages.MOB_CONVERSION','Native villager conversion and cure','W5','Legacy binding follows actual conversion callback; kernel binding remains unresolved rather than transferring authority'),
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
        row['family']='W10'
        row['limitation']='W5 covers same-entity unload/reload and restart; W10 tests native death, permanent fencing, same-save recovery and unrelated same-name successors. Conversion and explicit entity removal remain unverified.'
    if row['capability']=='acquisition-delivery-recovery':
        row['generatedCandidateVariations']=12
        row['limitation']='W6 attempts witnessed HARVEST/PICKUP/DEPOSIT checkpoints, actual SIGKILL, same save and same client process; missed boundaries remain INCONCLUSIVE'
    if row['capability']=='nearest-reference':
        row['family']='W7'; row['generatedCandidateVariations']=12
        row['limitation']='W7 requires the actual interpreter and typed eligible/full/wrong-type/far container fixtures; successful explicit-coordinate runs do not certify nearest resolution'
    if row['capability']=='leases-contention':
        row['family']='W9'; row['generatedCandidateVariations']=12
        row['limitation']='W9 attempts distinct Job owners, two simultaneous principals with independently admitted methods, and foreign-client refusal. Actual active leases, overlap and physical custody are required; generated definitions grant no coverage.'
    if row['capability'].startswith('legacy-') and row['capability'] not in ('legacy-bread-craft','legacy-go-home','legacy-death','legacy-configured-respawn','legacy-conversion-continuity','legacy-safety-interruption','legacy-group-food'):
        row['family']='W8'; row['generatedCandidateVariations']=12
        row['limitation']='Reusable W8 physical adapter; actual native effects and truthful controller termination must be witnessed. Bread transformation completion is not claimed.'
    if row['capability'] in ('legacy-go-home','legacy-configured-respawn'):
        row['reachability']='CONFIGURATION_DEPENDENT_PLAYABLE'
        row['campaignReachability']='NOT_GAMEPLAY_REACHABLE_IN_FROZEN_LAUNCH_CONFIGURATION'
        row['limitation']='Frozen E2E launcher sets providers=[] and default deathMode=permanent; no public command activates this provider-plan/configuration-dependent action. Production implementation is retained in this inventory, not called internally.'
    if row['capability']=='legacy-death':
        row['family']='W8'; row['generatedCandidateVariations']=12
        row['limitation']='W8 legacy-death cases require actual connected-player native death and truthful legacy controller status; kernel death is tested separately in W10.'
    if row['capability']=='legacy-group-food':
        row['family']='W11'; row['generatedCandidateVariations']=12
        row['limitation']='W11 requires the actual shared public food command, two independently enrolled native workers, simultaneous active controller witnesses and conserved physical acquisition; no bread transformation completion claim.'
    if row['capability']=='legacy-safety-interruption':
        row['family']='W12'; row['generatedCandidateVariations']=12
        row['limitation']='W12 tests actual player attack, genuinely low native health admission refusal, and real trading across controller ticks. Fire, sleep and visible-monster conditions retain explicit coverage limitations.'

registered=[
('deliver_wheat','CropDelivery.java','harvest','W2','Mature wheat -> tracked drops -> actor custody -> chest/barrel; amount 1..64','Agent 1'),
('deliver_oak_logs','OakLogDelivery.java','gather-oak','W2','Exact default oak log -> owned physical drop -> custody -> chest/barrel; amount 1..8','Agent 1'),
('deliver_birch_logs','BirchLogDelivery.java','gather-birch','W2','Exact default birch log -> owned physical drop -> custody -> chest/barrel; amount 1..8','Agent 1'),
('produce_oak_planks','OakPlankProduction.java','make-planks','Agent 2','Withdraw default oak logs; installed recipe; conserve batch surplus; deliver requested planks','Agent 2'),
('produce_birch_planks','BirchPlankProduction.java','make-birch-planks','Agent 2','Withdraw default birch logs; exact species recipe; conserve surplus and deliver planks','Agent 2'),
('produce_sticks','StickProduction.java','make-sticks','Agent 2','Withdraw oak planks; installed stick recipe; conserve surplus and deliver sticks','Agent 2'),
('produce_wooden_axes','WoodenAxeProduction.java','make-axes','Agent 2','Withdraw planks/sticks; actual crafting table; installed axe recipe; amount 1..4','Agent 2'),
('produce_crafting_tables','CraftingTableProduction.java','make-tables','Agent 2','Withdraw four planks per actual table item; amount 1..4; physical delivery','Agent 2'),
('place_crafting_table','CraftingTablePlacement.java','place-table','Agent 2','Debit actual default table item; loaded replaceable permitted site; safe standing geometry','Agent 2'),
]
for name,impl,command,family,domain,owner in registered:
    rows.append(dict(capability='cognitivecraft:'+name,primitive=None,implementation='core/src/main/java/dev/aivillages/core/kernel/'+impl,
        subsystem='KernelSession / registered capability / SurvivalGateway / FabricGatewayWorld',
        gameplayEntryPoint='/aivillage kernel '+command,actualEffect=domain,supportedDomain=domain,
        reachability='CLIENT_REACHABLE_AFTER_GENUINE_ADMISSION',existingCoverage=['e2e/coverage.json','e2e/rb-coverage.json','e2e/generalization.py'],
        dimensions=['quantity','geometry','coordinates','actor','container','capacity','interference','recovery'],owner=owner,family=family,
        generatedCandidateVariations=12 if owner=='Agent 1' else 0,generatedVariations=0,genuineExecutions=0,successful=0,failed=0,safeRefusals=0,blocked=0,inconclusive=0,
        observability='Actual native effect/custody plus matching scoped run and client menu; exact method hash and model provenance',
        limitation='Reconcile against actual family cases; generated definitions grant no coverage' if owner=='Agent 1' else 'EXCLUDED_AGENT_2_PRIMARY; shared physical boundaries retained for reconciliation'))
for name,command in [('compose-axes','compose-axes'),('compose-birch-planks','compose-birch-planks'),('compose-tables','compose-tables'),('provision-axe','provision-axe'),('blueprint-material-staging','blueprint-materials stage')]:
    rows.append(dict(capability=name,primitive=None,implementation='fabric/src/main/java/dev/aivillages/fabric/KernelCommands.java + KernelSession.java',subsystem='Composition / prerequisite / blueprint material owners',
        gameplayEntryPoint='/aivillage kernel '+command,actualEffect='Stock-first physical acquisition/recipe/placement prerequisites and delivery/allocation',supportedDomain='Exact registered compound request, owned actor, valid loaded resource/facility bindings',
        reachability='CLIENT_REACHABLE',existingCoverage=['e2e/composition.py','e2e/blueprints.py','e2e/tableplacement.py'],dimensions=['stock','facilities','quantity','geometry','authority'],owner='Agent 2 primary',family='Agent 2',generatedCandidateVariations=0,generatedVariations=0,genuineExecutions=0,successful=0,failed=0,safeRefusals=0,blocked=0,inconclusive=0,
        observability='Native prerequisite graph, physical custody/effects and actual client output',limitation='EXCLUDED_AGENT_2_PRIMARY; no unmerged work consumed'))
body=dict(schema=1,baseline='ea7de8000b2b484b885576c34ed5358274936ce9',productionTree='f0915a127521cb32f45e606c39098011796d1f1d',inventoryStatus='INITIAL_IMPLEMENTATION_AUDIT_REQUIRES_RECONCILIATION',coverageClaim='NONE',capabilities=rows,unsupported=['spruce or stripped log harvesting','carrot harvesting','arbitrary block placement'],auditSources=['fabric/src/main/java/dev/aivillages/fabric/FabricGatewayWorld.java','fabric/src/main/java/dev/aivillages/fabric/FarmPort.java','fabric/src/main/java/dev/aivillages/fabric/KernelCommands.java','fabric/src/main/java/dev/aivillages/fabric/VillageCommands.java','fabric/src/main/java/dev/aivillages/fabric/FabricWorldReferenceResolver.java','fabric/src/main/java/dev/aivillages/fabric/FabricCitizenLifecycle.java','e2e/observer/java/dev/aivillages/fabric/E2EObserver.java','e2e/client/java/dev/aivillages/e2e/ClientDriver.java','e2e/fixtures.py','e2e/suite.py','e2e/generalization.py','e2e/coverage.json','e2e/rb-coverage.json'])
(OUT/'inventory.json').write_text(json.dumps(body,indent=2)+'\n')
print(f'{len(rows)} inventory rows; no gameplay coverage claimed')
