# IMP-013 implementation evidence

Baseline main: **bf9633597eb1d5371f9d5b1f51fcb027d92525e2** (merged IMP-012 PR #4).
The fresh public audit found no newer main commit or pre-existing IMP-013 work.
Branch: `feature/imp013-worker-dispatcher`. Contract extension: **0.4** in
[IMP-013-contracts.md](IMP-013-contracts.md). All existing schema versions remain unchanged.

This report is being completed against executed local and GitHub acceptance
results. Merge status and exact checked head will be recorded before final delivery;
no planned gate is represented as passed evidence.

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
| 12 jobs/6 citizens, bounded later-page fairness | twelveJobsSixCitizensStayBoundedAndEveryJobGetsAVisit; last-worker eligibility fixture |
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
Final native workflow IDs and release-JAR evidence are recorded below after the
final implementation head is checked.

## Relevant historical failures and regressions

Public Actions run **37594332716**, job **112703557635**, failed the genuine local-model
warm offline physical reuse after two committed units with TARGET_UNAVAILABLE.
Integrated prerequisite commit **107562a72aa458d4f0d6464e8dda402f1468445b** repaired
native navigation replanning. The existing movement/native-route and genuine-model
regression lanes are preserved and must pass on this change. Superseded cancelled
run 37593803124 is historical cancellation evidence, not a passing acceptance gate.
The latest baseline's workflow families were green at audit.

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

## Explicit finite limits

The fairness claim is for the documented finite non-replenishing fixture, not
endless arrivals or permanently blocked resources. Unconfirmed cessation intentionally
holds an uncertain assignment for owner reconciliation; it does not silently reassign.
No IMP-014 or unrelated planning/generation unit is implemented.
