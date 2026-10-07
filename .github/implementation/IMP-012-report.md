# IMP-012 — Resource Lease Service

Implementation and independently read cloud acceptance logs:
`c44800548c193891834c95f746530e3aefaf0d36`.
Public baseline: `5e69d945bee6cb5f3e36c76588a827b40a5efce9`.
[Public PR #4](https://github.com/senavirathne/CognitiveCraft-public/pull/4)
contains this completion report and the final-head regression checks.
Completion metadata changes no runtime behavior. Every Actions run records its
exact source SHA. All compilation, JUnit and Minecraft execution used cloud
Actions; no local builds or runtime tests were used.

## Contract and ownership

[IMP-012-contracts.md](IMP-012-contracts.md) is architecture extension **0.3**,
published before consumer implementation. Lease identities, records and their
journal use schema **1**. Job extension 0.2, bootstrap extension 0.1 and their
invariants remain. Request, IR, artifact, outcome, citizen and job schemas and
bootstrap journal schema 2 remain unchanged. Platform pins remain Java 25,
Gradle 9.7.0, JUnit 5.11.4, Gson 2.10.1, Minecraft 26.3, Loader 0.19.5,
Fabric API 0.161.0+26.3 and Loom 1.18.1.

ResourceLeaseService owns temporary coordination records; JobLifecycleStore
owns responsibility, assignments and status; Minecraft owns physical stock.
Leases cannot select workers, create items, grant permission, load chunks or
resume an interpreter. Resource identities contain world/dimension/target,
with no scope component that could hide cross-principal conflicts.

| Source file | Export / responsibility |
| --- | --- |
| `core/.../ResourceLeases.java` | Immutable resource, demand, owner facts, epoch/generation reference, lease, observation, snapshot, result, roots and finite settings |
| `core/.../ResourceLeaseService.java` | Serialized game-thread acquisition, normalized atomic groups, availability, current-owner validation, renewal, release, consumption, expiry and bounded cleanup |
| `core/.../ResourceLeaseCodec.java`, `ResourceLeaseJournal.java` | Strict data-only NDJSON, single writer, digest/revision checks, bounded worker publication and preserved conservative recovery |
| `core/.../JobLeaseOwners.java` | Read-only actual job/generation/control/current-execution binding; refreshed observation evidence cannot change typed arguments or authority |
| `core/.../JobLifecycleStore.java` | New read-only `mayControl` query; existing lifecycle and schemas unchanged |
| `core/.../LeasedGateway.java` | Existing SurvivalGateway decorator; exact current pin and all lease members checked before every native poll; no alternate effect owner |
| `fabric/.../FabricResourceLeaseWorld.java`, `LeaseTargetWitnesses.java` | Loaded, permitted bounded observations; actual container/stack replacement witnesses, no forced loading |
| `fabric/.../KernelSession.java` | Existing worker opens/closes lease storage; composes the world-wide service and guarded production executor |

Core paths above expand to `core/src/main/java/dev/aivillages/core/kernel/`;
Fabric paths expand to `fabric/src/main/java/dev/aivillages/fabric/`.

Supported forms are container wheat, bounded mature-wheat crop pools, one
iron hoe in a container slot, storage/crafting facilities and voxel workspaces.
Stock sums checked outstanding quantities. Exclusive classes and overlapping
spaces conflict; equal end coordinates share a voxel, adjacent distinct blocks
may touch faces and coexist. Different overlapping crop pools conflict
conservatively. Joined chests and unsupported/ambiguous targets are rejected.
Repeated stock keys sum, exclusive duplicates collapse, and a conflicting
member leaves the whole group ungranted.

Production work claims its source workspace and destination facility through
the existing job and gateway. The independent stock client also claims crop
quantities. Actual HARVEST receipts durably debit that quantity before another
action; pickup/deposit can finish after full consumption, but an extra harvest
is rejected before mutation. Current execution pins are checked even when a
new research trial supersedes a run without advancing the job generation.
Every physical effect still passes original permission, reach, custody,
capacity and game-rule checks.

## Feature-to-test map

| Feature | Executable evidence |
| --- | --- |
| Physical identity, private scopes and five-unit stock | ResourceLeaseServiceTest cross-scope 3+3 conflict and compatible 3+2; dimension/world and privacy cases; real two-principal fixture |
| Equipment, facility, spatial and duplicate keys | Exclusive-class parameterization, distinct equipment slots, inclusive boundary/adjacency, overlapping crop pools, duplicate quantities and ambiguous groups |
| Atomic publication | Four-member conflicting/successful groups, held acknowledgement, no usable pending refs, exact acknowledgement and protected pending roots |
| Generation/expiry/renewal | Ticks 99/100/101, minimum interval 9/10, eight/ninth renewal, expired renewal, old callback after renewal/reacquisition and duplicate release |
| Current authority and scope | Actual JobLeaseOwnersTest ready/active/terminal owners, argument/context/artifact/run mismatch, read versus control sharing, revocation and original deadline |
| Actual effect fencing | LeasedGatewayIntegrationTest real interpreter plus SurvivalGateway: held acquisition/consumption, cancellation, external removal, permission denial, original wait budget, extra harvest, owner loss and replaced trial pin |
| Target identity | LeaseTargetWitnessesTest object identity versus equality, replacement, capacity and invalid bounds; real same-coordinate chest replacement |
| Data-only persistence | ResourceLeaseJournalTest exact round trip, five publication fault points, writer exclusion, future/corrupt/world/row rejection, preserved backup, symlink and stale publication |
| Recovery and clocks | New epoch, tick reset/backwards time, wall-clock changes, failed/late/wrong acknowledgement, interrupted restored claims, mixed-group and configuration rejection |
| Quantitative bounds | Settings zero/N+1/overflow, eight leases/job, four members/group, finite region, active 64/65, retained 128/129, no eviction, eight selected records/slice and 1,000 queries |
| Real Minecraft acceptance | ResourceLeaseGameTests, two separate JVMs, real CitizenRegistry/identity store, JobLifecycleStore/JobJournal, ResourceLeaseJournal, interpreter and effect gateway; no model or dispatcher |

The six new JUnit suites execute **106 cases**: policy **68**, journal **16**,
job-owner **8**, interpreter/gateway **8**, bounds **2**, Fabric witnesses **4**.
The lease lane runs the whole core and Fabric unit suites and `:fabric:build`.
Its XML verifier rejects missing, failed, errored or skipped matrix cases.
The original full CI requirement remains **48 distinct / 75 successful**
Minecraft executions; the isolated lease lane contributes two additional
successful Minecraft executions. Existing job, retrieval, actual Needle,
nearest, identity, adapter smoke and genuine Qwen gates remain enabled.

## Actual cloud acceptance

[Lease acceptance run 37593795201](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/37593795201)
passed on the implementation snapshot. Commands:

```sh
bash ./gradlew --no-daemon --stacktrace :core:test :fabric:test :fabric:build
COGNITIVECRAFT_LEASE_RESTART=cold bash ./gradlew --no-daemon --stacktrace :fabric:runGameTest
COGNITIVECRAFT_LEASE_RESTART=warm bash ./gradlew --no-daemon --stacktrace :fabric:runGameTest
```

The workflow selects only ResourceLeaseGameTests for this lane. A test-only
compiled body uses installed navigation, harvest, pickup and transfer operations,
and never enters production skill storage. Two actual enrolled workers and
separately scoped durable jobs request three units each from five mature crops.
The second claim reports two available units with no private IDs. Cancelling
the first executor after two actual deposits durably retains two completion
credits and releases its group; the second then delivers the remaining three.
The original five-unit crop pool becomes empty and its destination contains five.

A separate mutation chest tests actual five-unit container stock, removal to
two and an identically restocked replacement block entity. The fixture also
expires a spatial claim at its original 100-tick boundary and removes current
owner control. Test-only actor placement keeps the idle, unleased worker outside
tracked-drop pickup range; this is fixture setup, not dispatcher implementation.
Claims themselves cannot prevent another actor taking physical drops.

Cold saves one ACTIVE lease on a READY job and a separate ACTIVE assignment
without starting its executor. Warm opens the same actual saved Minecraft world
in a different JVM, publishes a new lease epoch, interrupts restored claims and
the live job, keeps the READY job ready, rejects the old callback and private
cross-scope queries, and observes a quiet period without replay. Cancellation
credit stays **2**, success credit stays **3**, destination wheat stays **5**.
All inference/trial calls are **zero** throughout both processes.

Actions artifact `lease-reports-37593795201-1` (1,053,348 bytes) reports SHA-256
`9702efb47e874ea21fea08f47789f1c18318045c648c931a2759a4efd17fb725`.
The workflow verifies XML and saved snapshots and retains both logs/XML files,
cold/warm lease snapshots, job/citizen state, restart proof, acceptance JSON and
measured scale/resource JSON. Its warm lease snapshot has nine records, 6,939
bytes and SHA-256
`5fca983bdc3790ffe739012a5214b7c157121acf5f93d163c86f03a7dfbd8067`.

The accepted lease-evidence release JAR, before the navigation follow-up below, is 1,022,664 bytes, SHA-256
`e67a515e29dcd8e68f7aa860a1bf0c987f84cb841f034eb4a6a2c093c9181f36`.
The workflow inspects its archive and rejects inclusion of any GameTest class.

| Measurement on this runner | Actual |
| --- | --- |
| Maximum active / retained / job / group records | 64 / 128 / 8 / 4 |
| Maximum reconciliation selection per slice | 8 |
| Scale retained snapshot | 84,712 bytes |
| Bounded queries / p95 / maximum | 1,000 / 5,027 ns / 26,340 ns |
| Journal publication | 6,323,027 ns |
| Cold / warm maximum measured owner slice | 340,233 ns / 60,621 ns |
| Cold / warm full Minecraft fixture peak used heap | 526,385,152 / 370,147,328 bytes |

Timing/heap values describe this runner; checked work, counts and bytes enforce
the bounds. The owner-slice measurement covers registry/jobs/gateway/lease
maintenance, not Minecraft or executor work outside that measured interval.
Fixture fake-tick lease duration is 100, renewal interval 10 and maximum renewals
8. Production duration is explicitly 1,200 ticks with at most 256 cells and
finite axes; all count caps remain. Snapshots cap at 1 MiB / 128 rows, each strict
row at 64 KiB / 4,096 tokens. Witnesses cap at 128 without eviction; adapter work
caps at 256 inspected cells/slots. One publication is outstanding and its
acknowledgement cap is 30 seconds. Gateway runs/handles and original action
waiting are finite; neither conflicts nor delayed storage reset a budget.

## Inherited navigation regression

The final full regression passed all 48 distinct physical GameTests (75
successful cold/warm executions). The separately pinned genuine-Qwen lane
[then exposed a warm offline reuse failure](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/37594332716):
Minecraft's automatic recomputation replaced the gateway-owned path just
outside the crop-approach threshold, after two genuine delivered units.
The strict route policy correctly rejected the replacement. Lease acceptance
and cold generation had already passed; this failure cannot be accepted as
successful delivery.

The follow-up suppresses native `recomputePath` only while that villager is
under gateway custody and clears native delayed recomputation. Explicit
gateway `createPath` calls still obey the original counted attempts, stall,
elapsed and travel budgets. Missing, changed, stalled or replaced paths still
fail under the unchanged route policy. Unowned villagers retain vanilla
replanning. The existing physical cancellation regression now verifies stable
owned path identity, cleanup of a pre-existing delayed flag, no next-run
revival, and restored native replanning after release. No model pin, delivery
assertion, fixture geometry, route threshold or execution budget is relaxed.

The nine workflow families must pass again on this final code, including
the pinned genuine-Qwen cold generation and offline warm reuse. Final-head
packaging is verified independently by those workflows; the artifact/JAR
measurements above identify the earlier accepted lease-evidence source.

## Recovery, compatibility and follow-on gates

A pending grant reserves its group but authorizes no effects. Uncertain storage,
bad acknowledgement, late completion or timeout fences the service and retains
committed/pending job roots. Corrupt/future current files and backups remain
preserved and read-only; recovery cannot use an old backup as permission to
overwrite newer data. Writable restoration publishes every old ACTIVE claim as
INTERRUPTED under a new epoch before readiness. Read-only recovered data is also
unusable, fenced by the runtime epoch. No prototype reservation migration or
physical-world replay is asserted.

No repository secrets or variables are required. The workflow supplies
`COGNITIVECRAFT_LEASE_RESTART=cold/warm` and test tracing itself.
[CI_CONFIGURATION.md](../CI_CONFIGURATION.md) records all migrated workflow
environments. IMP-010 and IMP-011 are already merged in public PRs #1 and #3,
and the source CI audit in PR #2 remains. This implementation writes commits
and pull requests exclusively to the public repository; the source repository
receives no commits, pushes, merges or other writes.

No unmet IMP-012 unit acceptance criterion remains. All nine workflow families
must pass on PR #4's final head before merge, followed by relevant main checks.
GP-13's remaining worker-dispatch portion belongs to IMP-013. IMP-013 and IMP-029
must use authoritative job generations, current execution/control and lease
roots/cleanup; IMP-018 must use fresh bounded stock rather than create it;
IMP-024 must preserve current scope/revocation checks. Retention must retain
uncertain pending roots and cannot silently evict leases at the record cap.
