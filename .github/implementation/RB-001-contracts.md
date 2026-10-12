# RB-001 contract extension — version 1 — local implementation, acceptance pending

HISTORICAL_SOURCE_SNAPSHOT: `d609e9980ca2cde8330042d2332226f50200d6b6`. Audited source `bf6b985d3d37c4a1529ae31723bc672f96e69ac2`; documentation `59b7914fc6a74ee5422839d41a8828e828f764ea`. The specification was published before source edits in `6d3da32c6e777239117e189386fea0e036c2651e`. The source implementation and test definitions are written; publication, Actions verification and merge after successful required checks are explicitly authorized. Compilation and all new acceptance gates remain unexecuted.

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

See [report and mandatory mapping](RB-001-report.md) and [full prompt](https://github.com/senavirathne/CognitiveCraft-Documentation/blob/main/docs/source/docs/RB-001-oak-log-acquisition.md). Changes to any proposed decision must update this contract before claiming acceptance.


## Concrete custody and owner details

`ResourceDelivery.ItemIdentity`, `BlockPosition` and `Receipt` carry bounded core data only. A resource receipt includes the original trusted context, run, stable physical-operation ID, batch, actor, exact source/destination, actual source-block position, actual spawned drop UUIDs, item/components and stage quantity. `ActionReceipt`, executor/research summaries, Bootstrap views and Jobs attempts/reports retain resource receipts separately from CropReceipt. Exact duplicates are idempotent; changed receipt IDs/operation claims, cross bindings and duplicate drop ownership cannot multiply credit. Jobs caps combined evidence and allocations under its existing owner.

The lease resource uses `minecraft:oak_log` with slot discriminator -2 for source-block units and -1 for actual container item units. One committed oak harvest debits one block unit, including a zero/partial-loot outcome; loot quantity never becomes extra source stock. Consumed operation IDs are stable and journaled by the existing lease owner. Block and item pools are separate physical forms, with world/dimension/coordinates shared across principals.

Actual eligible oak item entities receive a unique vanilla pickup target equal to their entity UUID. This prevents vanilla merging with unrelated drops or another batch. The existing MobPickupMixin prevents uncontrolled citizen pickup during the run. ItemPickupOwnershipMixin releases the special target on loss of live gateway-controlled ownership, including reopening after interruption. It uses vanilla fields and creates no new persisted work/inventory owner. Ordinary effects and exact component/capacity checks remain in FabricGatewayWorld on the server thread.

The established provider adapter now supports the same bounded causal proposal shape for both registered delivery capabilities, lowering only host-supplied signatures and runtime parameter bindings. It recognizes the oak adjacent-approach operation. Controlled proposal programs live only in test sources and are excluded from the production JAR. Source publication, genuine model/trial success and offline reuse are still pending.
