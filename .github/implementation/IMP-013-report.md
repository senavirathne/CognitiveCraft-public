# IMP-013 implementation evidence

Baseline main: **bf9633597eb1d5371f9d5b1f51fcb027d92525e2** (merged IMP-012 PR #4).
The fresh public audit found no newer main commit or pre-existing IMP-013 work.
Branch: `feature/imp013-worker-dispatcher`. Contract extension: **0.4** in
[IMP-013-contracts.md](IMP-013-contracts.md). All existing schema versions remain unchanged.

Implementation and native acceptance source:
**2f61277dd2f53e5e1d4f9e5ac4bdc996a6df1cd2**.
[Public PR #5](https://github.com/senavirathne/CognitiveCraft-public/pull/5)
records the final report-only head, merge commit and merged-main checks. This
completion metadata changes no runtime source. Every Actions run records its
exact checked SHA; the table below identifies the verified implementation runs.

## Implemented boundary

`WorkerDispatcher` provides deterministic scoped UUID pagination, coupled job/worker
fairness, current eligibility and availability checks, seven-outcome resolver evidence,
guarded owner assignment, acknowledged lease admission, exact pinned existing-executor
starts, durable partial progress, stopped-owner disposition and stale-run fencing.
Jobs own all assignments and credit; leases own all claims; citizens own identities;
physical changes remain in SurvivalGateway. No dispatcher database or model port exists.

Owner view additions are `dispatchPage`, `bindWorker`, `remainingAllowance`,
`workerAvailable` and `controlPage`. `LeasedGateway` adds advisory inspection and
pre-interpreter admission/validation. `BoundedSkillExecutor.Run.stopped` distinguishes
terminal outcome from confirmed cessation. KernelSession composes queued dispatch,
and scoped queue/status/cancel commands are present. Existing bootstrap submissions
remain with BootstrapController.

| Source | Responsibility |
| --- | --- |
| `core/.../WorkerDispatcher.java` | Bounded policy, original scopes, owner acknowledgements, pinned starts, stop/release disposition and private diagnostics |
| `core/.../Jobs.java`, `JobLifecycleStore.java` | Immutable dispatch page and owner previews; existing durable assignment, budget and credit operations |
| `core/.../CitizenRegistry.java` | Disposable private control index and bounded current addresses |
| `core/.../LeasedGateway.java` | Actual claim admission/validation and production source-space, facility and crop-stock coordination |
| `core/.../BoundedSkillExecutor.java` | Confirmed-stop observation and retained owner protection for unconfirmed cancellation |
| `core/.../BootstrapController.java` | Current and persisted submission ownership prevents competing dispatch of legacy work |
| `fabric/.../KernelSession.java`, `KernelCommands.java` | Production composition, scoped queue/status/cancel and private suggestions |
| `core/.../WorkerDispatcherTest.java`, `DispatcherOwnersIntegrationTest.java`, `ExecutorJUnitTest.java` | Policy matrix, actual owners and direct executor stop-safety regression |
| `fabric/src/gameTest/.../WorkerDispatcherGameTests.java`, `WorkerDispatchWiringGameTests.java` | Isolated saved-world GP-13 and actual production command/gateway wiring |
| `.github/workflows/worker-dispatcher.yml`, `fabric/build.gradle` | Executed matrix gates, isolated native fixtures, release audit and headless test classpath |

Core prefixes expand to `core/src/main/java/dev/aivillages/core/kernel/` for
production and `core/src/test/java/dev/aivillages/core/kernel/` for tests. Fabric
production expands to `fabric/src/main/java/dev/aivillages/fabric/`; its GameTests
use `fabric/src/gameTest/java/dev/aivillages/fabric/`. Jobs and leases remain schema
1; bootstrap journal remains schema 2. No independent dispatch persistence exists.

## Routing and prerequisite audit

The documentation retrieval began with CognitiveCraft-Documentation's
`AGENT_DOCUMENTATION_INDEX.md`, following the named implementation-unit route.
The exact IMP-013 implementation-unit record and prompt were read, followed by
architecture contracts sections 6, 14-15, 19-20 and 22, the routed architecture and
invariants, targeted knowledge reasoning and GP-13. The public IMP-011/012 source,
contract extensions, tests and completion reports determined current interfaces.
IMP-003/008/010/011/012/028 are integrated prerequisites, not reimplemented here.
No historical/private repository writes occurred.

## Mandatory feature mapping

| Feature | Executed test surface |
| --- | --- |
| Success, exact pins, originating scope, acknowledged admission | WorkerDispatcherTest; DispatcherOwnersIntegrationTest; production wiring GameTest |
| Stable ties across input iteration order | deterministicRoundRobinSelectsStableUuidWorker |
| 12 jobs/6 citizens, bounded later-page fairness | twelveJobsSixCitizensStayBoundedAndEveryJobGetsAVisit; last-worker fixture also asserts explicit bounded-page DEFERRED diagnostics |
| Availability, occupied workers and seven outcomes | Parameterized availability/outcome cases and busy-worker cases |
| Resolver alternatives | Real resolver blocked-first strategy integration |
| Two competing dispatchers | Guarded real job owner competing-dispatch case |
| Authority and citizen revision races | Ranking/commit, rename-generation and post-assignment revocation cases |
| Partial orchestration and storage failure | Job publication, lease failure, executor rejection and delayed-release cases |
| Cancel before deferred start; repeated ticks | Pending lease cancellation and repeated-same-tick cases |
| 2/5 loss, confirmed/unconfirmed stop, 3 remaining | Real gateway partial-loss integration and policy stop/reassignment cases |
| Old completion after new generation | Policy duplicate report and saved-world GP-13 stale completion |
| Restart with READY/interrupted jobs; cache deletion | Job owner recovery case; cold/warm dispatcher GameTest |
| Private scopes and shared addressing does not confer control | Private page, diagnostics, foreign citizen and production status/suggestion assertions |
| Known execution without models | Real-owner integration, physical GP-13 and production KernelSession fixture |

## Measured local validation

The project-supported headless Gradle profile uses the pinned official Minecraft
26.3 server SDK, Fabric dependencies and Java 25. Equivalent compilation and
unit validation run with absolute `serverJar` and `serverLibraries` properties.
The headless profile now includes its existing server library inputs in Fabric's
JUnit compile/runtime classpath, fixing missing Brigadier for affected adapter tests.

The 12/6 fixture selected and completed every job in **37 ticks**, within the
calculated **576-tick** bound. Observed maxima: **4 jobs**, **3 citizens**, **1 start**,
**0 immediate retries** per slice; configured caps are 4/3/2/2. Every reconsideration
and reassignment retains the original inherited deadline and budget. Fixture
assignment timeout is 20 ticks; production uses 1200.

The full local suite executed **664 cases**: core 593, providers 48 and Fabric 23,
with zero failures, errors or skips. The new dispatch matrix comprises **65 policy
cases and 7 actual-owner integration cases**. The production KernelSession GameTest
passed locally in 23.653 seconds, exercising two principals, two real queued jobs,
the actual queue/status commands, two physical deliveries and cache deletion.
One explicit fake acquisition seeds its known method before production dispatch;
dispatch then records zero additional generation calls and zero language calls.
The independently downloaded native verification artifact confirms the same
593/48/23 counts and zero failures, errors or skips. Native dispatcher evidence
also requires all 65 policy, 7 actual-owner, 20 executor, 68 lease-service and 112
job-store cases. The 72 dispatcher cases are included in the 664 total.

The exact native build/unit command is:

```sh
bash ./gradlew --no-daemon --stacktrace :core:test :providers:test :fabric:test :fabric:build
```

The workflow selects only `dev.aivillages.fabric.WorkerDispatcherGameTests` in
the GameTest manifest, then executes separate JVMs:

```sh
COGNITIVECRAFT_DISPATCH_RESTART=cold JAVA_TOOL_OPTIONS=-Dcognitivecraft.gametest.trace=true bash ./gradlew --no-daemon --stacktrace :fabric:runGameTest
COGNITIVECRAFT_DISPATCH_RESTART=warm JAVA_TOOL_OPTIONS=-Dcognitivecraft.gametest.trace=true bash ./gradlew --no-daemon --stacktrace :fabric:runGameTest
```

It then selects `dev.aivillages.fabric.WorkerDispatchWiringGameTests` and runs:

```sh
JAVA_TOOL_OPTIONS=-Dcognitivecraft.gametest.trace=true bash ./gradlew --no-daemon --stacktrace :fabric:runGameTest
```

The isolated selection leaves the existing acceptance manifests and IMP-012
fixture source unchanged in the delivered repository.

## Native GP-13 and production acceptance

Run **37621151520**, job **112791459555**, passed all three isolated feature
executions. Each process also ran Minecraft's platform `always_pass`; those
three platform cases are not counted as dispatcher feature coverage.

| Feature execution | XML duration | Verified result |
| --- | --- | --- |
| GP-13 cold | 41.328 s | Two enrolled workers/principals, overlapping source/target, requests 3+3 against five actual units; explicit stock conflict; cancellation credits 2 and successor completes 3; physical wheat exactly 5; no double credit; zero model calls |
| GP-13 new JVM on the same saved world | 0.783 s | Acknowledged READY and assigned cold jobs; restored attempt INTERRUPTED; new lease epoch; old epoch rejected; cache deletion and 20 quiet ticks without replay; confirmed never-started disposition followed by generation-2 assignment; old completion rejected without crediting the replacement |
| Production KernelSession/commands | 24.020 s | Two scopes, two queued jobs, one assignment each, two real physical deliveries, actual queue/status commands and private suggestions/diagnostics; zero dispatch-time generation/language calls; cache deletion preserves jobs |

The GP-13 test also executes the original lease-owner expiry, external stock
removal, same-coordinate container replacement and permission-revocation probes.
The independent unchanged IMP-012 acceptance lane passes separately.
Production setup explicitly acquires one test-only method with a fake provider;
inference is then disabled and both known jobs run without another model call.
GP-13 itself admits a test-only known method and records zero model invocations.

Cold storage contains four acknowledged jobs, including C READY and D assigned
but never started, plus one ACTIVE lease. Warm load interrupts D conservatively.
Only the fixture's explicit proof that D never started permits zero-effect owner
reconciliation and the stale-generation test. The final warm journal retains C
READY and D's effect-free generation-2 assignment; it does not restart an old
interpreter. All nine warm lease records are inactive; physical delivery remains 5.

The final lease snapshot is **6,937 bytes**, within the 1 MiB/128-record limit,
SHA-256 `75b05fed1fec34ebe37bab9d9f2dca67500210fdad50b1989aec914aed90e485`.
Observed maximum fixture owner slice: **32,004,568 ns cold / 4,648,661 ns warm**;
peak JVM heap **595,491,528 / 405,518,784 bytes**. These are finite-fixture measurements.

The production JAR audit excludes every GameTest class. The checked native JAR
`ai-villages-0.1.0.jar` is **1,062,773 bytes**, SHA-256
`354ee1e5626d71d4b086a685d83a7213617db9eb5f6c05ae849611d2331fa9e2`.

## Exact implementation-head Actions evidence

Every following pull-request run checked
**2f61277dd2f53e5e1d4f9e5ac4bdc996a6df1cd2** and completed successfully.

| Workflow | Successful run |
| --- | --- |
| CognitiveCraft verification: unit/build, release audit, physical, interruption/hard-kill, commands | [37621151565](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/37621151565) |
| Worker dispatcher: policy, actual owners, saved-world GP-13, production commands | [37621151520](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/37621151520) |
| Job lifecycle | [37621151435](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/37621151435) |
| Resource leases | [37621151712](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/37621151712) |
| Capability retrieval | [37621151338](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/37621151338) |
| Citizen identity | [37621151576](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/37621151576) |
| Default nearest physical references | [37621151584](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/37621151584) |
| Needle language and outage | [37621151397](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/37621151397) |
| Genuine local generation smoke | [37621151369](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/37621151369) |
| Genuine local-model physical trial and warm offline reuse | [37621151556](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/37621151556) |

Same-head push verification **37621144939** and dispatcher **37621144879** also
passed. Downloaded dispatcher artifact **11482002588** (1,108,330 bytes) matches
GitHub's archive SHA-256
`47e84c2f8c5b8eaa90892f965063870e82e976ffd4febde2d9c1157b4890fe80`.
Its XML, saved jobs/citizens/leases, bounded metrics and production/cold/warm logs
were independently inspected. Native unit artifact **11481774312**, from push
verification 37621144939, matches SHA-256
`165a7dab57b9b7fcb54131df15a1258a841257ec597fa9b95630ea6e0bd001bc`
and confirms all 664 executed unit cases without skips.

## Relevant historical failures and regressions

Public Actions run **37594332716**, job **112703557635**, failed the genuine local-model
warm offline physical reuse after two committed units with TARGET_UNAVAILABLE.
Integrated prerequisite commit **107562a72aa458d4f0d6464e8dda402f1468445b** repaired
native navigation replanning. The existing movement/native-route and genuine-model
regression lanes are preserved and must pass on this change. Superseded cancelled
run 37593803124 is historical cancellation evidence, not a passing acceptance gate.
The latest baseline's workflow families were green at audit.
The genuine local-model and physical/native-navigation lanes listed above pass
on the accepted implementation; historical green runs are not substituted for them.

New owner integration exposed and fixed attempted-assignment churn during resource
contention. Physical dispatch exposed and fixed reacquisition of claims while a
terminal run awaited asynchronous release. Dedicated policy coverage checks that
terminal cleanup performs no new acquisition. Delayed assignment publication is now awaited instead of misclassified as stale;
a dedicated regression holds that acknowledgement across six ticks. Post-assignment observation failures
and revoked read access now stop safely instead of abandoning a live execution.
Unconfirmed terminal stop now preserves attributable partial receipts while keeping
the assignment open; its regression observes two delivered units and six committed
effects in the actual durable job owner before cancellation is confirmed.
The existing executor also retains its release callback after an unconfirmed or
throwing gateway cancellation. A direct executor regression covers both cases,
partial evidence and repeated terminal calls; the executor suite executes 20 cases.

New candidate failures were inspected from actual logs. Runs 37615314279 and
37615314361 exposed an incorrect integration-test assertion on the retrieval
language-query counter; the actual resolver uses bounded pages, so the assertion
now checks pages/fallback pages without changing production routing. Run 37616530848
passed both physical GP-13 processes but correctly rejected the production fixture's
second enrollment beyond six blocks; the mock principal is now placed beside its
worker for enrollment, preserving the production restriction. Run 37619969058
failed because a publication commit omitted prerequisite files (`gradlew` absent).
The full existing tree was restored in a forward commit and its exact diff verified.
Superseded intermediate cancellations are not acceptance evidence. No failure was
masked by rerunning unchanged code or weakening an existing acceptance fixture.

## Explicit finite limits

The fairness claim is for the documented finite non-replenishing fixture, not
endless arrivals or permanently blocked resources. Unconfirmed cessation intentionally
holds an uncertain assignment for owner reconciliation; it does not silently reassign.
No IMP-014 or unrelated planning/generation unit is implemented.
