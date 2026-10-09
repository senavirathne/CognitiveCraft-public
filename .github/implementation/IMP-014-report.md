# IMP-014 completion evidence

Baseline: `a3d14207ad8d6ed569be8160b9a2a68ad8ad1cdf` (merged IMP-013).
No pre-existing IMP-014 branch or PR was present. Work branch:
`feature/imp014-ai-work-broker`. Architecture extension 0.5 and broker envelope
schema 1 are defined in `IMP-014-contracts.md`; authoritative persisted schemas
are unchanged. This report is being finalized against executed acceptance evidence.

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
below after verification; undiscovered or skipped cases are not accepted.

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
effect assertion or production timeout was weakened. A fresh changed-head run is
required; the failed run is not passing evidence.

## Delivery record

Final native source/PR/merged-main evidence will be added here before completion.
The PR metadata is the canonical exact final-head and subsequent merge/main record:
those SHAs cannot be embedded in the commit that creates them. No IMP-015 work is
part of this change.
