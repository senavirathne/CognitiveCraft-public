# IMP-011 job contract

Architecture extension **0.2**, retaining the kernel's architecture 0.1 invariants.
Job records, assignments, events and allocations use schema **1**. Request,
capability, IR and outcomes remain schema 1; citizen schema 1 and bootstrap
attempt journal schema 2 are unchanged. Baseline:
a3cea0e785045b1327a5dfd219b173c6aab27328.

Jobs owns durable responsibility, dependencies, assignments and accounting.
The executor owns live instruction/action state; the bootstrap controller owns
input, resolution and research orchestration and retains attempt summaries.
No job operation selects a worker, reserves physical resources, invokes a model,
or mutates Minecraft. The assigned worker is explicitly supplied by a trusted
owner and checked against current citizen control.

## State transitions

| From | Allowed destinations | Required owner evidence |
| --- | --- | --- |
| READY | WAITING, ACTIVE, CANCELLED, FAILED | Hold/dependency; durable assignment; no live attempt for direct termination |
| WAITING | READY, ACTIVE, CANCELLING, CANCELLED, FAILED, INTERRUPTED | Dependencies satisfied and original allowance remains; explicit preparation assignment; cancellation or saved pending work |
| ACTIVE | WAITING, CANCELLING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED | Observed executor termination or a durable cancellation intent |
| CANCELLING | CANCELLED, FAILED, INTERRUPTED | All execution owners and owned children observed stopped; never notification delivery alone |
| INTERRUPTED | READY, WAITING, CANCELLING, CANCELLED, FAILED | Explicit owner reconciliation; unknown effects/budget keep the job inactive |
| SUCCEEDED / FAILED / CANCELLED | None | Duplicate reports are idempotent; late effects may extend bounded evidence without new completion credit |

Special transitions use assignment/result/cancel/reconcile operations, not an
unguarded status setter. Every mutation checks expected job revision and assignment
generation. Each atomic publication advances a changed job by one revision, even when accounting and state propagation both update it. A revision conflict commits nothing. Assignment generations increase
monotonically; an old run/generation can record attributable late effects but
cannot complete or release the current assignment.

An assignment reserves a finite attempt envelope against the job and all parents
before effects. Reported usage reduces that reservation and charges each ancestor
once. Cancellation never refunds consumed work. Uncertain assignments retain their
reservation across reload. Only a trusted stopped-owner observation with complete
usage/effect evidence releases unused reservation. No retry or child renews the
original deadline. Preparing research is distinct from an executable artifact;
exact trial run/artifact/dependency references must be pinned durably before a
trial starts.

## Dependencies and output

Dependency and child updates are atomic, same-origin, bounded and acyclic.
A parent becomes ready when all dependencies succeed. A cancelled dependency
cancels its dependent work and owned siblings; a failed dependency fails dependent
work with ACTION_FAILED. Active siblings enter CANCELLING until observed stopped.
Interrupted children keep parents waiting. Cancelling a parent does not cancel an
unrelated shared dependency.

Only trusted runtime receipts matching the assigned run, actor, source and target
contribute. Wheat credit requires all three registered harvest/pickup/deposit
stages. Repeated or reordered receipts never multiply credit. Output allocation
is separately identified and bounded by both the deposit receipt and its
attributable batch; allocating 3 + 3 from 5 is rejected. Allocation does not
create stock or a resource lease.

## Access and bounds

Read/control checks use the current policy and exact original TrustedContext.
An ID is not a grant. Explicit sharing can reveal/control a job through policy,
but does not rewrite its origin or widen worker authority. Views are immutable.

Fixture limits: 32 active / 64 total jobs, 8 children and 8 dependencies per job,
depth 4, 64 inspected dependency edges per operation, 8 ready results/slice,
16 retained events/job, 8 attempts/job, 64 retained crop receipts/job, 128 output
allocations, two cancellation deliveries/assignment and 30 seconds for a storage
acknowledgement. Exhaustion is explicit; referenced records are never evicted.
One lifecycle publication is in flight; one acknowledgement and at most one
cancellation delivery are processed per tick.

## Storage and evolution

World-relative directory: data/cognitivecraft/jobs/v1. A bounded JSON-lines
snapshot has a schema-1 header and individually strict schema-1 records, a payload
digest, a single writer, staged/fsynced publication, an old committed backup and
atomic replacement. Serialization and disk I/O run on the storage worker.
Individual rows retain the strict 64 KiB / 4,096-token caps; the snapshot is capped at 2 MiB / 192 records. Recovered/corrupt/future data stays preserved and inactive. Reload marks live or
uncertain work INTERRUPTED; it never dispatches, replays an action, or infers
success from inventory.

A documented schema-0 import envelope contains the same fully bound schema-1
records, references and accounting, with no active interpreter state. Its
one-time migration preserves the original and IDs. This is an explicit import
format, not a claim that legacy prototype tasks were valid Jobs. Ambiguous legacy
tasks and unknown schemas are not admitted or executed.

Protected roots include retained/pending artifact closures, run IDs, dependency
jobs and allocation sources/targets. Incomplete recovery roots prohibit
collection. IMP-012, IMP-013, IMP-016, IMP-029 and later job consumers must use
this owner and its guarded operations, not an independent work ledger.

## Runtime exports and downstream bindings

`Jobs` publishes immutable schema-1 Job, Attempt, Guard, ExecutionReference,
Allocation, Snapshot, Report and finite Settings. `JobLifecycleStore` is the
single game-thread owner; Storage publishes immutable snapshots on the existing
world worker. `JobJournal` and `JobCodec` own the strict durable encoding.
`JobArtifactPins` resolves an I/O-free, exact closure of at most 16 artifacts.
`BootstrapController`'s optional job-owner constructor preserves existing ports
while production `KernelSession` uses it for submitted typed/NLU work. The
executor still owns live state. Research TrialPort.prepare may defer start until
the exact run/artifact pin is acknowledged; no grant, trial debit or physical
trial starts while it waits.

IMP-012 must consume guarded assignments and cancellation/reconciliation rather
than treating IDs as lease grants. IMP-013 must select an explicit currently
controlled worker and reserve a remaining allowance through assign; it cannot
reset deadlines or revive a stopped Run. IMP-016 must include protectedRoots and
freeze collection when complete=false, including pending/fenced publications.
IMP-029 must supply actual stopped-owner observations to reconcile; final stock
is insufficient. Later prerequisite, planning, accomplishment, delegation and
organization consumers retain the original scope and bounded output allocation.

## GP-13 binding for this unit

Use JobLifecycleStore with the real BoundedSkillExecutor and SurvivalGateway,
durable JobJournal, explicit trusted assignment, two scopes, cancellation,
duplicate/stale notifications, and a separate saved-world restart with all
models disabled. Test resource allocation bookkeeping without implementing
IMP-012 leases or IMP-013 dispatch. Those later units complete the overlapping
multi-worker/lease portion of GP-13.

The executable cloud binding is `.github/workflows/job-lifecycle.yml`. It runs
JobLifecycleGameTests in cold/warm Minecraft JVMs through real KernelSession,
commands, identity, executor, SurvivalGateway and JobJournal. It reuses the
citizen fixture's test-only acquisition (one fake proposal, actual physical
trial/admission) before model-disabled production jobs. It separately checks
partial cancellation, scopes and world-save mismatch, and preserves XML,
logs, both job snapshots and structured byte/count evidence. No completed IR or
setup fixture is included in the release JAR. The normal CI aggregate remains
48 distinct/75 successful Minecraft executions; the job lane adds two executions.
