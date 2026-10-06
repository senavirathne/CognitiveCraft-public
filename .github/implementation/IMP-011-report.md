# IMP-011 — Job Lifecycle Store

Lifecycle implementation and independently inspected test snapshot:
`eae8073590dfa41c4aaf1fc20e54b893125fa4d8`.
Restart-fixture follow-up: `980f02b8a03517a5e83cf4c9afee0e79dd106832`.
Public baseline: `a3cea0e785045b1327a5dfd219b173c6aab27328`.
[Public PR #3](https://github.com/senavirathne/CognitiveCraft-public/pull/3)
contains the completion metadata and final-head checks. Completion metadata
commits change only reports and configuration documentation. GitHub Actions records
its exact source SHA in each run; historical source runs are not acceptance of
this public implementation. All compilation and runtime tests used cloud Actions.

## Contract and exports

[IMP-011-contracts.md](IMP-011-contracts.md) is architecture extension **0.2**.
The bootstrap architecture 0.1 invariants and pinned platform versions remain.
Job, assignment, event, allocation and NDJSON journal schemas are **1**;
request, capability, IR, artifact and shared outcomes remain schema 1, citizen
schema 1 and bootstrap journal schema 2. Schema-0 migration is a documented
fully bound import envelope, not admission of legacy prototype tasks.

| File in `core/src/main/java/dev/aivillages/core/kernel/` | Export / owner |
| --- | --- |
| `Jobs.java` | Immutable Job, Attempt, Guard, ExecutionReference, Allocation, Snapshot, Report, Settings and legal transition table |
| `JobLifecycleStore.java` | Sole game-thread responsibility/status/accounting owner; create/query/ready, guarded transitions, dependencies/children, explicit assignment, pin, result, cancel, reconcile and protected roots |
| `JobJournal.java`, `JobCodec.java` | Strict world-authoritative storage, single writer, bounded encoding, recoverable atomic publication, conservative readers and explicit import migration |
| `JobArtifactPins.java` | Exact bounded artifact closure before assignment/trial publication |
| `BootstrapController.java` | Existing input/research/execution orchestration with optional job-owner integration; finite envelopes captured once; no effects before acknowledgements |
| `ResearchAdmissionController.java` | Backward-compatible default TrialPort.prepare; waits for exact durable run/artifact pins before grant or trial debit |

`fabric/src/main/java/dev/aivillages/fabric/KernelSession.java` opens job storage
on the existing worker, composes the owner and routes production typed/NLU work
through it. Job queries preserve original scope. Worker selection, live interpreter
state, physical effects, resource leases and artifact admission remain with their
respective owners. A changed job publishes one guarded revision per transaction,
including cumulative ancestor charges and terminal propagation.

## Feature-to-test map

| Feature | Executable evidence |
| --- | --- |
| Typed creation, responsibility, immutable scope, sharing/revocation | JobLifecycleStoreTest creation, private/sharing, invalid bindings and assignment dimension/capability cases; real two-principal Minecraft commands |
| All legal/illegal state pairs and competing commits | Parameterized 25 allowed / 39 forbidden state pairs; revision/generation conflict and worker-claim cases |
| Dependency readiness, cycles and atomic children | A→B→C, self/back-edge/new-child cycles, rejected mixed batches, exact children 8/9 and depth 4/5 |
| Finite configuration/work | Invalid settings/amounts, active 32/33, total-record quota, exact 64-edge acceptance / 65-edge rejection, ready slices 8, attempts 8/9, receipts 64/65, overflow and retained events 16 |
| Cumulative inherited budgets | Child cancellation/sibling/retry, deadline inheritance, protected reservations and atomic parent accounting/termination revision |
| Duplicate, reordered, foreign and stale reports | Stable receipt deduplication, collision/reordered cases, narrated/foreign completion rejection, obsolete-generation late-effect attribution without new credit |
| Output conservation | Actual 5-unit output cannot satisfy 3 + 3; forged imported allocation rejected before publication |
| Cancellation and dependent failure | 2/5 retained, durable intent versus observed cessation, failed notifications retried within cap, sibling stop and unrelated dependency preservation |
| Publication/recovery/migration | All five fault boundaries, old/new whole snapshots, writer lease, corrupt/future inactive recovery, original preservation, schema-0 repeated migration and legacy task refusal |
| Real orchestration and executor | JobExecutorIntegrationTest: exact dependency closure, durable completion/deduplication, held creation/assignment acknowledgements, queued cancellation, actual executor partial cancellation and live-run reload |
| Physical world-save mismatch / model outage | JobLifecycleGameTests cold/warm processes through KernelSession, real commands, executor, SurvivalGateway and JobJournal; metadata interrupted, inventory never supplies completion, no replay |
| Growth/resource evidence | JobResourceBoundsTest: 32 active records, 24 updates each, 16 events retained, 1,000 bounded ready queries and actual journal bytes |

The four new JUnit suites execute **132 cases**: lifecycle **112**, journal
**14**, real executor integration **5**, resource bounds **1**. The job lane
runs all **418 core cases** and `:fabric:build`. No skipped or failed cases are
accepted by its XML verifier. The dedicated physical lane contributes **two**
successful Minecraft executions; existing full CI retains its **48 distinct /
75 successful** project GameTest requirement.

## Actual cloud evidence

[Full verification run 37497522130](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/37497522130)
passed on the lifecycle snapshot: **485 JUnit cases** (418 core, 48 providers,
19 fabric), plus **48 distinct / 75 successful** project Minecraft executions.
Downloaded report artifact `verification-reports-37497522130-1` independently
matched SHA-256
`68c29c84be4513ea98087e5c2033e81908d8534b1570d5a120838be738540514`;
all XML cases were checked for failures, errors and skips. The release JAR is
945,341 bytes, SHA-256
`431c2f2655bc1bad6fabbf4db96d9e2c48e4b385b3896955abffd34d61fad02b`.
All 18 test-source classes and GameTest metadata are excluded from that JAR.

[Job acceptance run 37497522336](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/37497522336)
passed on the implementation snapshot. Commands:

```sh
bash ./gradlew --no-daemon --stacktrace :core:test :fabric:build
COGNITIVECRAFT_JOB_RESTART=cold bash ./gradlew --no-daemon --stacktrace :fabric:runGameTest
COGNITIVECRAFT_JOB_RESTART=warm bash ./gradlew --no-daemon --stacktrace :fabric:runGameTest
```

The workflow selects only JobLifecycleGameTests for the two separate Minecraft
JVMs. Test-only setup acquires one fake proposal through actual physical trial
and durable admission; production jobs then run with inference disabled. The
cold process verifies three serial scoped jobs, cancellation after two deposits
of a five-unit request, and a second live job with two physical deliveries newer
than its still-active metadata. Warm recovery leaves that assignment INTERRUPTED
and uncertain, with **zero** invented completion credit and a held reservation.
The cancelled job retains **two** credited units. Real stock stays **seven**
through the restart and quiet observation period. Model calls for these jobs:
**zero**. Exact artifact/execution roots and private views survive.

Downloaded artifact `job-reports-37497522336-1` independently matched SHA-256
`8da536b04fc82dfb16460ece433beabe04594c696ad7b63b8b9e9025e618007a`.
Its XML was independently checked for executed cases and absence of failures,
errors and skips. It includes cold/warm XML, logs, restart proof, both job
snapshots, acceptance JSON and measured scale JSON.

| Measurement on that cloud runner | Actual |
| --- | --- |
| Scale records / events per record / ready slice | 32 / 16 / 8 |
| Scale committed snapshot | 63,778 bytes |
| Ready queries / p95 / maximum | 1,000 / 10,105 ns / 42,063 ns |
| Journal publication (off-tick production boundary) | 5,306,665 ns |
| Warm physical fixture job records / snapshot | 6 / 27,144 bytes |
| Warm full Minecraft fixture peak used heap | 560,707,536 bytes |
| Warm maximum measured KernelSession tick | 4,071,694 ns |

Timing/heap measurements describe this runner and the whole Minecraft fixture;
counts, checked work and bytes enforce the bounds. Storage caps are 2 MiB / 192
records, with strict 64 KiB / 4,096-token individual rows. Serialization and I/O
are worker tasks. One lifecycle publication is outstanding, one acknowledgement
or notification preparation is processed per tick, cancellation delivery is
capped at two and acknowledgement waiting at 30 seconds. Protected/pending
references are retained on uncertain publication; incomplete roots freeze
future collection.

## Recovery and follow-on gates

Publication faults yield a complete old or new snapshot. Corrupt/future current
data and backups remain preserved; recovered/future stores stay read-only.
Supported import migration archives its exact original and preserves IDs,
accounting and references; repeat opening cannot duplicate jobs. Live work
becomes durably INTERRUPTED on reload before admission is ready. Only a trusted
stopped-owner observation with complete effects/usage can reconcile it. The
store never reconstructs a success from stock or resumes an old executor.

IMP-010 is already merged in public PR #1, and the source CI migration/audit in
public PR #2 is retained. Source open PR #13 is the already-transferred IMP-010;
source PR #16 changes only excluded design documentation. The source repository
received no commits, pushes, merges or other writes from this work.

No repository secrets or variables are needed. The new workflow supplies
`COGNITIVECRAFT_JOB_RESTART=cold/warm` and test tracing itself. All eight workflow
families are required on the final PR head before merging; their current source
and final results are linked from PR #3.

The genuine Qwen gate also exercises acquisition, admission and six-unit reuse
in a second Minecraft process after stopping Ollama. Its restart fixture now
waits for the manually relocated villager to land before submitting a saved
method that may navigate. Navigation tracing is enabled in that workflow.
The original physical receipt, delivery, exact-artifact, stopped-endpoint and
zero-model-call assertions, deadlines, budgets and model/toolchain pins remain.

No unmet IMP-011 criterion remains. GP-13's overlapping-worker/resource-lease
portion belongs to IMP-012/IMP-013 and is deliberately still pending. IMP-016
must consume protected roots, including pending/fenced references; IMP-029 must
supply actual reconciliation observations. Retained jobs are not evicted to
make room at the 64-record cap. The public CI configuration and contract document
record the next gates and consumer bindings.
