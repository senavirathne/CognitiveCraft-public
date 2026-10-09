# IMP-014 completion evidence

Baseline: `a3d14207ad8d6ed569be8160b9a2a68ad8ad1cdf` (merged IMP-013).
No pre-existing IMP-014 branch or PR was present. Work branch:
`feature/imp014-ai-work-broker`. Architecture extension 0.5 and broker envelope
schema 1 are defined in `IMP-014-contracts.md`; authoritative persisted schemas
are unchanged. **IMP-014 acceptance passed** on the verified functional source below.

## Implementation

- AIWorkBroker: deterministic bounded queue, compatible queued cohorts, private
  subscribers, immutable deadlines, one occupied backend slot, aging fairness,
  cancellation/cessation separation, explicit backpressure and scoped status.
- Budgets: atomic bounded multi-root debit, ancestor deduplication, response output
  reservations, truthful measured versus unknown output attribution.
- KernelSession: existing research GenerationPort now uses the shared world broker;
  known dispatch and gateway paths remain independent. Inference gate and shutdown
  fence pending subscribers. No queue is persisted or replayed.
- LocalGenerationAdapter: cancellation before preparation confirms that transport
  never started and releases the slot; a regression protects late worker dispatch.

Research still owns compiler, candidate policy, fixtures, physical trials, retries
and guarded admission; the repository remains the artifact owner. The broker does
not generate effects or award retries. Production coalescing is private by default.

## Required matrix and execution

The pure matrix covers capacity four/concurrency one, mixed-priority aging,
compatible coalescing, context/version/role/observation/world isolation, explicit
sharing, one/last subscriber cancellation, confirmed/unconfirmed stop, late and
duplicate results, cumulative parent/repair/output attribution, tightest deadline,
queue/cold preparation time, outage, gate toggles, authorization races, private
status, frozen in-flight cohorts, bounded retention, restart and thread ownership.

Controlled load submits 1000 private demands before dispatch: four accepted work
cohorts and 996 explicit rejections; the fixture publishes measured elapsed/slice
times, physical usage and queue/concurrency/retention bounds to
`core/build/broker-evidence/load.json`.

Actual-adapter fixtures use LocalGenerationAdapter's real request serialization,
bounded transport callbacks and byte ledger with a controlled backend. Actual
research fixtures use its compiler, CropFixtureRunner, executor, DecisionGuard
and repository: one shared generation call leads to separate trials/publications;
cancelled subscribers cannot admit late candidates; repair uses its original parent.

Local command:

```sh
python3 ../tooling/run_gradle.py \
  -PserverJar=/workspace/scratch/9799c042259e/CognitiveCraft-public/build/server-sdk/minecraft-server.jar \
  -PserverLibraries=/workspace/scratch/9799c042259e/CognitiveCraft-public/build/server-sdk/libs \
  :core:test :providers:test :fabric:test :fabric:build
```

Native CI uses the equivalent pinned standard Gradle tasks and an isolated GP-14
GameTest. Exact executed counts, run IDs, source SHA and artifact hashes are recorded
below; undiscovered or skipped cases are not accepted.

Local final-source validation completed successfully in 19 seconds: **725 JUnit cases**
(core 646, providers 56, Fabric 23), zero failures/errors/skips. The new matrices
contain AIWorkBrokerTest 50, BrokerResearchIntegrationTest 3 and
BrokerAdapterIntegrationTest 8. The release build and `git diff --check` passed.

## GP-14 binding

`AIWorkBrokerGameTests.stalledSharedInferenceCannotBlockKnownPhysicalJobsOrReplayAfterRestart`
is the test-driver binding for every applicable GP-14 step. One explicit fake setup
acquisition seeds the known skill. The test then uses real KernelSession owners and
LocalGenerationAdapter with one controlled delayed transport, two equivalent
authorized subscribers and private incompatible cohorts; it cancels one then the
last subscriber, rejects queue overflow, expires queued work, and holds unconfirmed
compute. Two principals' known crop jobs each deliver one actual wheat unit without
additional generation/Needle calls. Scoped status, disabled gate, cache deletion,
owner reload and twenty quiet restart ticks must not replay inference or effects.

Initial native source `aad646c3efefdfc7e40cd0e7903ad1d67996970b` passed GP-14
in run 37920339087/job 113786474690, with one discovered passing fixture and no
skips. Its controlled 1000-request load measured 115873533 ns total and a maximum
5598432 ns slice; bounds were four queued work records, one occupied slot, one
start per slice and 64 retained records. Physical work consumed four calls and
40 accepted output bytes. Native release audit: 1098034 bytes, SHA-256
`6bce25a72b55d79bd084cfa942b3dbc1558a74bd96319e102a840dd082c128d1`,
broker present and GameTest classes absent. These initial-source results are
superseded by final-head evidence below. The local isolated fixture also passed
in 23.951 seconds. Controlled inference is explicitly labeled; known runtime
execution itself used zero additional generation/Needle calls.

## Regression audit

The public failure at run 37594332716/job 112703557635 was re-read: genuine acquisition
passed but model-stopped warm reuse ended TARGET_UNAVAILABLE after two delivered units.
The already integrated navigation fix 107562a72aa458d4f0d6464e8dda402f1468445b is preserved.
The unchanged genuine research/offline-reuse workflow is required on this PR head.
Original dispatcher, job, lease, retrieval, citizen, Needle and physical acceptance
lanes are preserved. Historical green runs validate the starting baseline only.

Initial IMP-014 PR run 37920339248/job 113786476160 exposed the existing real-disk
JobExecutorIntegrationTest's 1000 x 1ms wait window. Its retained XML shows a timeout
in Scene.until before terminal acknowledgement, rather than failed credit assertions.
The fixture now awaits the same durable condition with a finite five-second/5000-tick
bound and reports pending owner states on failure. No owner condition, storage ACK,
effect assertion or production timeout was weakened. Changed-head run 37921725742
passed, as did the same suite in every full-validation lane. Independent native
artifact inspection found all five durable owner cases passing in 0.171 seconds.
The initial failed run is not passing evidence.

## Delivery record

Verified functional source: `d435179058136c72b7a126582941004144859228`.
Tree: `b6c68398304e03fd5d2fe5c90d6d3eefc18f829c`.
PR: [CognitiveCraft-public #6](https://github.com/senavirathne/CognitiveCraft-public/pull/6).
The final report commit changes this file only; source, tests, workflows and the
production JAR remain identical to this verified functional source. The PR metadata
is the canonical exact final-head and subsequent merge/main record: those SHAs
cannot be embedded in the commit that creates them. Every final-head gate is checked
before the true two-parent merge, and merged-main acceptance is verified afterward.

All **11 workflow families**, plus the separate push verification run, passed on
the exact functional SHA. No cancelled, skipped or historical run substitutes for
these results:

| Workflow | Run ID | Result |
| --- | --- | --- |
| AI work broker acceptance | 37921725751 | PASS |
| Job lifecycle acceptance | 37921725742 | PASS |
| Worker dispatcher acceptance | 37921725734 | PASS |
| Resource lease acceptance | 37921726052 | PASS |
| Capability retrieval acceptance | 37921725790 | PASS |
| Citizen identity acceptance | 37921725900 | PASS |
| Default nearest physical acceptance | 37921725931 | PASS |
| Needle language acceptance | 37921725788 | PASS |
| Local generation smoke | 37921725861 | PASS |
| Research local-model physical trial | 37921725916 | PASS |
| CognitiveCraft verification, pull request | 37921725803 | PASS |
| CognitiveCraft verification, push | 37921720186 | PASS |

## Independent final-source artifact verification

Run 37921725751/job 113790990744 retained artifact 11611918597,
`broker-37921725751-1`, 76982 bytes, SHA-256
`0067412d7e6dd7f307d14799bcd4aeab935d8703dcd4161e2fcaa9f0e448d741`.
The downloaded ZIP independently verified:

- **725** JUnit cases: core 646, providers 56, Fabric 23; no failures, errors or skips.
- New matrices: **50** broker policy/budget/load, **3** actual research owner and
  **8** actual adapter cases. Original suites remain discovered and passing.
- Exactly one discovered passing GP-14 Minecraft fixture, **23.894 seconds**,
  without failures/errors/skips. Equivalent demand shared one controlled call;
  private cohorts stayed separate; one subscriber cancel preserved the other;
  last cancel retained unconfirmed compute; queue overflow rejected; original
  queued deadline expired. Two physical known jobs delivered one unit each;
  status and terminal cancel preserved credit with zero additional inference.
  Disabled status, scoped views, cache deletion and owner reload with twenty
  quiet ticks produced zero queue/effect replay.
- Controlled 1000-demand load: **4** accepted cohorts, **996** rejections,
  **4** physical calls, **40** accepted output bytes, one occupied slot, one
  start per slice and **64** retained records. Measured total **176.933106 ms**;
  maximum measured slice **7.845214 ms**. These are fixture measurements, not
  a universal hardware latency guarantee. The full-cohort case separately proves
  five enumerated work records, forty members and at most **56** authority checks.
- Production JAR **1098103 bytes**, SHA-256
  `3d5866bfbf3a53a4130df15fdbdb176dc9df3bc00eaeec8d68e5aad086ffdf1a`;
  broker class present, no GameTest classes packaged. Platform pins are unchanged.

The controlled broker fixture does not claim genuine inference. The unchanged
local-generation and research-live-trial workflows separately passed genuine
local-model cold/warm generation, physical admission and model-stopped saved-world
reuse. Existing navigation, job cancellation, lease expiry, stock conservation,
stale generations, privacy and restart regressions passed on this source.

Genuine research run 37921725916 used `qwen3:4b-instruct-2507-q4_K_M`:
one acquisition call produced twelve physical effects and twelve receipts. After
the model service stopped, a separate Minecraft server reused the same admitted
artifact with zero generation calls, eighteen effects and eighteen receipts.
General verification run 37921725803 accepted 48 distinct project GameTests across
75 successful executions, including fresh JVM reuse and interrupted-owner recovery.

## Bounded limitations and ownership audit

Coalescing is intentionally exact and limited to queued cohorts; dispatched cohorts
are sealed. Production shares no private player scope. A tighter subscriber envelope
bounds the whole frozen response; removal never widens it. The fairness bound is
eight successful dispatch opportunities, conditional on backend cessation and
continued eligibility inside the original deadline. Unconfirmed compute keeps
backpressure; its unknown output consumes a conservative reserved remainder.

Diagnostics are disposable, scoped and bounded to 64 records. Restart abandons
pending demand, never infers completion and never automatically replays inference
or Minecraft effects. Hardware limits are finite per world session. External or
distributed backend cancellation is outside this local broker contract. Research,
repository, identity, job, lease, interpreter and gateway lifecycle owners remain
unchanged. No IMP-015 implementation is included.
