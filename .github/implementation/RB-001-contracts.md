# RB-001 contract extension — version 1 — local implementation, acceptance pending

HISTORICAL_SOURCE_SNAPSHOT: `d609e9980ca2cde8330042d2332226f50200d6b6`. Audited source `bf6b985d3d37c4a1529ae31723bc672f96e69ac2`; documentation `59b7914fc6a74ee5422839d41a8828e828f764ea`. The specification was published before source edits in `6d3da32c6e777239117e189386fea0e036c2651e`. Source PR #24 is published at `dc3d132902df0f098a9e7f162bb7896ed62f7b9b`; the additive revision and final acceptance remain pending.

One registered `cognitivecraft:deliver_oak_logs` capability, version 1, delivers `minecraft:oak_log` from a bounded authorized source into a supported container. Parameters are `actor: ACTOR`, `amount: INT [1,8]`, `source: AREA`, `destination: CONTAINER`; result is INT. Effects remain OBSERVE, MOVE, HARVEST, PICKUP, TRANSFER. Source volume is at most 256 cells. No natural-versus-player-placed provenance is asserted. Other logs, stripped logs, wood, leaves, crafting, tools, planting and construction are outside this capability.

## Version and compatibility decisions

| Owned form | Decision | Preservation rule |
|---|---|---|
| Architecture, Contracts, Skill IR and request codec | Keep architecture 0.5, Contracts/IR/request 1 | Add a registered capability/completion, without changing the wheat ID, typed request fields or canonical wheat bytes |
| Primitive contracts | Add named oak primitives at version 1 | Preserve every existing gateway v1 fingerprint and wheat result meaning |
| Resource identity/evidence | Add validated namespaced item identity and separately typed delivery receipt v1 | Never relabel oak as a CropReceipt; old constructors default to empty resource evidence |
| Jobs owner | Read original schema 1; introduce schema 2 for snapshots containing oak responsibilities or resource evidence | Schema 1 remains semantically unchanged; schema 2 retains crop receipts and adds separate resource receipts; unknown/corrupt future data remains preserved and inactive |
| Repository/artifact forms | Retain existing schemas/hash rules | New capability and primitive requirements are ordinary immutable artifacts; no rewriting admitted wheat bodies or evidence |
| Leases | Retain schema 1; add exact oak block/container stock forms using `minecraft:oak_log` | Physical world/dimension/area identity is shared across principals; grants remain coordination, never permission |
| Observed knowledge | Retain current schema 2; add explicit oak source/actor/container target kinds | Existing SOURCE_CROPS and wheat/seed maps keep their meaning; unknown kinds preserve the existing inactive-read policy |
| Language | Add an oak action and typed delivery binding | Wheat extraction defaults remain; unsupported explicit references fail closed |
| Compatibility, retention and recovery | Consume existing owners and root enumeration | New evidence is inside existing bounded Jobs/observation storage; no second inventory, journal or scheduler |

Registered primitive interfaces: `observe_oak_source(actor,source) -> INT`, `observe_oak_inventory(actor) -> INT`, `observe_oak_container(actor,destination) -> INT`, `approach_oak_log(actor,source) -> BOOL`, `harvest_next_oak_log(actor,source) -> INT`, `pickup_tracked_oak_logs(actor) -> INT`, `transfer_oak_logs(actor,destination,amount[1,8]) -> INT`. Existing `move_to_destination` remains navigation-only and can be reused. The new source approach selects a loaded oak log and navigates to an adjacent traversable interaction position. It never targets a solid log as a standing position.

## Physical execution, evidence and bounds

Only the installed Minecraft 26.3 oak block and default-component oak-log item are physically supported. Components must compare exactly against the default stack; a nondefault variant is rejected, never normalized. Transfers copy the actual source stack and use real stack/container slot limits. Joined chests, locked containers, pending loot tables and unsupported container types retain current rejection rules.

The implemented support envelope is bare hands: both hands empty, grounded, outside water/lava and with no active effects. Acceptance of this path is pending native verification. The adapter must read current block hardness and `requiresCorrectToolForDrops`, and enforce cumulative server-tick break progress using the installed survival rule (eligible bare-hand speed 1 divided by hardness and 30). Negative/unsupported hardness or tool-required blocks are rejected. Minecraft's BLOCK_DROPS and MOB_GRIEFING rules, loaded state, current mayInteract policy, reach and obstruction are checked throughout and immediately before mutation. Actual installed loot determines produced item entities; no expected-output top-up. API inspection confirms the pinned BLOCK_DROPS/MOB_GRIEFING and destroy-speed/tool methods exist; physical timing/loot acceptance is still unexecuted.

Receipts bind item identity, actor, exact source/destination, stable run/batch/operation/receipt identities and HARVEST, PICKUP, DEPOSIT stage. The trusted executor/Jobs attempt adds origin, generation and run fences. Completion credits the minimum actual harvest/pickup/deposit quantity per batch, deduplicates exact receipt IDs and rejects changed IDs, cross-resource/cross-run/cross-binding evidence. Pre-existing inventory, unrelated drops and chest deltas cannot establish acquisition. Only tracked spawned drops enter custody. Unexpected merges, loss or inventory changes stop credit conservatively; no compensating items or rollback. Jobs alone allocates actual attributable deposited output.

Existing gateway bounds remain: 8 production runs, 512 handles, 64 combined receipts/run, 16 source cells/poll, 256 source cells, one navigation call/poll, 200 ticks/action and 32 travel blocks/action. Oak execution allowances are selected before attempts and charged cumulatively: at most 256 primitive calls, 4096 instructions, 4096 observations, 128 travel blocks, 64 attempted and 64 committed effects, 1200 elapsed ticks and a 600000 ms outer deadline; action wall deadline is 15000 ms. No deadline renewal or budget refill. Research remains two candidates, one repair and one real trial under the existing broker. Oak research selects a parent maximum of 512 calls, 10000 instructions, 10000 observations, 256 travel blocks, 2400 elapsed ticks, 300 attempted/committed effects, 40000 input bytes and 20000 output bytes before work. Existing two-call/one-repair inference limits and 2048-byte production research context remain. The physical trial uses the declared oak execution envelope; isolated fixture work charges the same research parent. No new broker or backend is created.

Discovery retains radius 16, vertical range 4, 9801 search cells, 256 work/poll, 32768 total work, 12000 ms, 128 candidates, 128 inspected container slots and complete-scan nearest/tie rules. Oak source discovery additionally retains at most 32 eligible log points, constructs at most 32 bounded area candidates, and charges candidate comparisons in slices. Any unknown cell in the declared oak source/container search envelope prevents a nearest claim. No unbounded forest traversal or fallback search is added. Oak source candidates must retain exact bounded source evidence and revalidate it, and destination capacity is oak-specific. Existing language limits remain 1024 input bytes, 8192 output bytes, 30000 ms, confidence >=0.35. Existing owner queue/storage/retention limits remain in force; new evidence counts against their current totals.

Every effect rechecks trusted original principal/world/domain, current citizen control, run and Job generation, current leases, dimension, loaded state, target identity, inventory/capacity, access/gamerules and reach. Supported protection envelope is vanilla `mayInteract`, world border, loaded state, current controlled-citizen authority and current gateway policy; no chest-owner property or uninstalled protection integration is invented.

Cancellation follows Jobs/executor/broker/lease owners, stops navigation and fences stale callbacks. Active custody and instruction pointers are ephemeral. A process crash preserves actual committed world changes and owner accounting where durably acknowledged; uncertain unfinished work reopens interrupted/inactive. No stock-based success inference, automatic resume or blind effect replay.

## Commands and learning

Canonical request interface (local source, execution pending): capability `{id:"cognitivecraft:deliver_oak_logs",version:1}`, arguments actor/amount/source/destination in the unchanged typed request codec. Structured command registered under the existing `aivillage` root:

```text
/aivillage kernel gather-oak <actor-id> <amount> <min-x> <min-y> <min-z> <max-x> <max-y> <max-z> <chest-x> <chest-y> <chest-z>
```

This command must submit to normal production KernelSession/Jobs; it must not edit the world. Existing private `job-status`/`job-cancel` controls apply. Existing language command `/aivillage kernel ask Ada, collect 4 oak logs and deliver them to the nearest suitable chest.` is to bind the new registered intent through normal interpretation and deterministic references. These interfaces are written in local source and remain unverified; this document does not claim a successful player execution.

No trusted oak task program is shipped. Research must perform normal resolution, generation, compile, bounded fixture evaluation, authorized physical trial and durable repository admission. Controlled proposals are test-only. Genuine configured-backend generation/admission and language quality are separate mandatory gates. Admitted methods bind all four parameters, and structured known execution must reuse them with zero inference, including a second-JVM saved-world restart with the backend stopped.

See [report and mandatory mapping](RB-001-report.md) and [full prompt](https://github.com/senavirathne/CognitiveCraft-Documentation/blob/42b0c389860469917d3de5dc97fd7c772ba96fec/docs/source/docs/RB-001-oak-log-acquisition.md). Changes to any proposed decision must update this contract before claiming acceptance.


## Concrete custody and owner details

`ResourceDelivery.ItemIdentity`, `BlockPosition` and `Receipt` carry bounded core data only. A resource receipt includes the original trusted context, run, stable physical-operation ID, batch, actor, exact source/destination, actual source-block position, actual spawned drop UUIDs, item/components and stage quantity. `ActionReceipt`, executor/research summaries, Bootstrap views and Jobs attempts/reports retain resource receipts separately from CropReceipt. Exact duplicates are idempotent; changed receipt IDs/operation claims, cross bindings and duplicate drop ownership cannot multiply credit. Jobs caps combined evidence and allocations under its existing owner.

The lease resource uses `minecraft:oak_log` with slot discriminator -2 for source-block units and -1 for actual container item units. One committed oak harvest debits one block unit, including a zero/partial-loot outcome; loot quantity never becomes extra source stock. Consumed operation IDs are stable and journaled by the existing lease owner. Block and item pools are separate physical forms, with world/dimension/coordinates shared across principals.

Actual eligible oak item entities receive a unique vanilla pickup target equal to their entity UUID. This prevents vanilla merging with unrelated drops or another batch. The existing MobPickupMixin prevents uncontrolled citizen pickup during the run. ItemPickupOwnershipMixin releases the special target on loss of live gateway-controlled ownership, including reopening after interruption. It uses vanilla fields and creates no new persisted work/inventory owner. Ordinary effects and exact component/capacity checks remain in FabricGatewayWorld on the server thread.

The established provider adapter now supports the same bounded causal proposal shape for both registered delivery capabilities, lowering only host-supplied signatures and runtime parameter bindings. It recognizes the oak adjacent-approach operation. Controlled proposal programs live only in test sources and are excluded from the production JAR. Source publication is complete; genuine oak model/trial success and offline reuse remain pending.


## Independent acquisition and downstream reuse — additive revision

`cognitivecraft:deliver_oak_logs` v1 is independently requestable with runtime `actor`, `amount`, `source`, and `destination` bindings. Its SkillArtifact composes bounded physical primitives; Jobs owns each invocation. A prerequisite graph may connect Jobs under the existing owner. No "connect skills" physical primitive, new scheduler, crafting station, recipe, RB-002, product or blueprint consumer is introduced.

A direct request for newly collected logs must acquire and deliver the requested attributable quantity even when older logs already occupy the actor or chest. Success requires its own physical harvest/pickup/deposit chain. An admitted artifact must retain its typed four-parameter interface and contain no crafting/RB-002/product-specific dependency. Different quantities and authorized source/destination bindings must reuse the same artifact without a downstream Job. Saved-world reuse must work with inference disabled, the backend stopped, and zero known-execution model calls.

Receipts document that invocation's physical outcome; they create no inventory balance or unconditional reservation. Existing Jobs controls output allocations. A future eligible consumer must inspect current authorized unallocated physical item stock through existing stock/lease/prerequisite owners and request RB-001 only for a supported deficit. Removed or spent stock is unavailable even if a historical receipt exists. Acquisition never guesses a future recipe or product. This contract adds no consumer implementation.

RB-T13 maps independent 1/4/7 reuse, distinct source/destination bindings, typed interface and dependency assertions to ResourceDeliveryTest, OakLogGenerationTest, OakLogKernelGameTests and GP-RB-01. RB-T14 maps older/unrelated stock, physical availability versus receipts and shared allocations to OakLogGatewayTest, OakLogJobLifecycleTest, new prerequisite stock assertions and existing lease/prerequisite regressions. All revised assertions remain pending Actions execution on the revised final head.


## Physical approach correction — pending verification

Oak source arrival requires a grounded face-adjacent block cell (Manhattan horizontal cell distance exactly one), with horizontal distance to the log center at most 1.15 blocks. Diagonal cells do not establish arrival. Recheck this same position at every harvest effect, alongside the existing 2.5-block native reach/ray, loaded state, permission, hands, effects and gamerules. Keep path attempts/polls and all cumulative budgets unchanged. Do not widen pickup reach, move item entities, change their scatter, or grant new primitive authority. Existing wheat movement/fingerprints remain unchanged.

Before removing a log, revalidate capacity for the actual eligible loot computed by the installed server, including excess beyond the requested credit. Unsupported or excessive loot/capacity is rejected before mutation; real surplus remains in the world/inventory without extra job credit.


Standalone acquisition credit is invocation-specific. JobLifecycleStore.allocate must reject allocating a historical oak producer receipt to another direct deliver_oak_logs request; persisted allocations with that meaning are invalid and remain preserved/inactive under existing recovery. A direct oak job succeeds only from its own trusted execution evidence. Original wheat allocation semantics remain unchanged. Typed oak production evidence and actual item stock remain reusable by later eligible consumer contracts under the existing owners; RB-001 introduces no such consumer or allocation shortcut.


## Approach to the run's own pending oak drops

The existing approach_oak_log primitive remains a single MOVE operation with the same actor/source parameters and v1 fingerprint. When this run has pending owned oak drops, it selects one by stable custody insertion order, revalidates that exact entity's item count, dimension, loaded current cell and permission every poll, and approaches its current cell through the existing bounded navigation owner. Otherwise it approaches the next eligible standing source log using face adjacency. It never selects unrelated or pre-existing items. No physical primitive, effect permission, pickup reach, inventory owner or task scheduler is added. Movement and pickup remain separate calls.

The oak proposal uses approach/harvest, a bounded drop wait, then a second approach to the owned drop, pickup, destination movement and transfer. This is model-proposed IR; no trusted complete task program is shipped. The transport grammar permits four oak finish entries while retaining the original three-entry wheat form. All per-action and cumulative navigation/poll/deadline limits remain. Revalidate source/actor/destination bindings and owned drop evidence throughout; stale or replaced drops block without fabricated credit.


## Movement terminal ownership

`approach_oak_log` owns navigation exactly like the existing MOVE operations. Once movement has started, every terminal outcome—including success, stale owned-drop evidence, range/path failure and cancellation—must stop owned navigation through the existing world adapter. Include its operation in existing terminal movement diagnostics; preserve the same primitive fingerprint, effects and budgets. A stale-drop regression must prove navigation stops and repeated terminal polls cause no additional movement or credit. Existing optional native trace flags may be enabled in Actions without changing physical behavior or acceptance assertions.


## Grounded native source-path submission

For the solid oak-source approach, defer path selection/submission while the actual worker is airborne, including the transition after ordinary container navigation/deposit. Keep the same action deadline, elapsed/travel accounting, loaded/permission revalidation and finite approach budget; do not consume a path candidate merely because the installed ground navigator cannot submit a path before landing. Return ongoing with zero pathfinding work, never success, until the worker is grounded. Preserve existing wheat movement and the oak face/reach/harvest guards. Native effects coverage must spawn a genuinely falling worker, prove no path work or false terminal result during that interval, and then complete ordinary survival acquisition.
