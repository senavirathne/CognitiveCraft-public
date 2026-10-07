# IMP-013 deterministic worker dispatch contract

Architecture extension **0.4** consumes IMP-011 extension 0.2 and IMP-012
extension 0.3. Baseline: bf9633597eb1d5371f9d5b1f51fcb027d92525e2.
Jobs, citizen identities, leases, requests, IR, artifacts and bootstrap journals
retain their existing schema versions. There is no dispatcher database or schema.
Platform pins remain unchanged.

## Ownership and admission

WorkerDispatcher owns policy, bounded cursors, diagnostics and orchestration.
JobLifecycleStore alone owns job state, responsibility, remaining quantities,
assignment/run references, generations, inherited budgets and completion credit.
CitizenRegistry owns identity and control; its address-sharing policy does not
confer worker control. ResourceLeaseService owns the single world-wide resource
claim set. CapabilityRetrievalIndex and CapabilityResolver own candidate retrieval
and routing. BoundedSkillExecutor owns the interpreter and terminal outcomes.
SurvivalGateway remains the only physical effect path.

The dispatcher MUST use the job's original TrustedContext. It MUST independently
check current citizen control, LOADED availability, worker occupancy, resolver
eligibility and effect authority. A shared artifact, public address, lease or
RESOLVED decision does not recruit a foreign citizen or retain permission.
Unavailable and UNKNOWN citizens are deferred. Read-only job sharing does not
allow dispatch, cancellation or private candidate diagnostics.

## Deterministic policy and finite work

A caller slice uses circular UUID order for jobs and controlled citizens. The
existing resolver selects exact admitted implementations, preserving every
candidate's reason, including a blocked first strategy and usable alternatives.
No distance, equipment, title, newest-version preference or model score is invented.

The hard configurable maxima are four job inspections, three worker inspections,
two executor starts, two immediate conflict retries, eight live owner references
and sixteen returned diagnostic records per slice. Resolver work retains its
existing independent finite candidate/page limits. Immediate retries are zero:
a conflict returns to a later bounded slice and spends the original job allowance.
The job owner's eight-attempt bound also limits reassignment churn.

One outstanding job publication is permitted by the existing owner. Every
reconsideration debits one INSTRUCTIONS unit through that owner. An asynchronous
acknowledgement is awaited through later ticks; neither a deadline nor allowance
is reset. Repeated calls within one tick cannot obtain another slice. All dispatcher
entry points require their creating game thread.

Private circular pages reuse disposable owner snapshot indexes; rebuilding an
index examines at most the existing 64-row owner quota. Selection inspects only
the reported page. The dispatcher has at most 64 caller cursors and at most one
bounded diagnostic history per retained job. Monitoring examines at most half
the job slice (minimum one) in circular order. Job and worker cursors are coupled:
a blocked job cohort advances after a complete bounded worker sweep, preventing
modular page alignment from permanently hiding the last eligible worker.
An inspected job with controlled workers remaining beyond its current page returns
DEFERRED evidence naming the next bounded slice while retaining the last blocker.
A valid admission held by the slice's start cap also returns DEFERRED evidence.

For a finite, non-replenishing, continuously eligible cohort, one-tick owner
acknowledgements and one-tick known executions, the calculated single-scope bound is:

`J * (ceil(W / workerSlice) + 1) * (8 + 2 * ceil(liveLimit / monitorSlice))`

The pinned 12-job/6-citizen fixture uses 4/3/2/2 slice caps and a 20-tick
assignment timeout, giving 576 ticks. Tests record actual ticks and maxima in
`core/build/dispatch-evidence/scale.json`. This is not a fairness guarantee for
endlessly replenishing arrivals, blocked resources or unacknowledged storage.
The production timeout is 1200 ticks, inside the original job deadline. The
production composition gives each originating scope a circular turn.

## Guarded assignment, claims and compensation

The dispatcher first binds remaining responsibility through JobLifecycleStore,
observes current bounded facts and invokes the existing retrieval/resolver.
An advisory lease inspection avoids spending assignments on a known resource
conflict. It is not a reservation. Historical responsibility anchors may differ
from a prospective replacement worker; actual admission always uses the current
new assignment and its control scope.

It charges the job owner, awaits acknowledgement, then rechecks the job guard,
registry revision, worker control/availability, deadlines and occupancy before
calling guarded `assign`. The existing lease owner API binds claims to a durable
job generation and exact execution run, so assignment publication precedes the
actual lease grant. This is an effect-free preparation phase. After assignment
acknowledgement, routing is re-observed and the same exact artifact is required.
Claims are acquired and acknowledged through ResourceLeaseService, then validated
again before executor start. The interpreter receives the assigned worker, original
scope, exact artifact/dependency closure and remaining inherited allowance.

A failed charge, assignment, claim or start never authorizes effects. Claims are
released through their owner, the release acknowledgement is observed, and the
job owner records a typed no-start outcome or actual executor outcome. Storage
failure fences further admission; uncertain durable state remains protected for
owner recovery. A lease conflict never becomes a model/research request.

Production crop dispatch coordinates source space, destination facility and
mature-wheat stock through the existing lease service. World identity is independent
of principal, so competing scopes cannot each allocate three from the same five.
Gateway effect-time authority, availability, custody and capacity checks remain
mandatory after admission.

## Completion, interruption and recovery

Every progress report is fenced by job guard, assignment generation, exact run,
artifact and dependency pins. Only attributable executor receipts and usage are
forwarded to the job owner; no requested or final-inventory quantity is credited.
Changed partial evidence is durably published before another interpreter tick.

Cancellation, worker loss, authority loss, expiry or shutdown calls the execution
owner to stop. A terminal outcome alone is insufficient: `Run.stopped` also requires
a terminal cancellation receipt for an outstanding action. Unconfirmed cessation
MUST retain the open assignment and exclude overlapping replacements. Claims are
retained while attributable partial receipts and usage continue to be published
through the job owner without falsely declaring terminal disposition. Claims are
released only after confirmed cessation. Terminal handles MUST NOT reacquire claims
while asynchronous release acknowledgement is pending.

A confirmed interruption is reported, acknowledged and reconciled through the job
owner, which binds only remaining legitimate responsibility. A stale handle may
stop and release its own run; it MUST NOT credit or release the replacement run.
Revoked diagnostic read access must still interrupt the actual execution owner.
Shutdown admits no new run; the existing durable owners retain uncertain recovery
roots if publication cannot be completed before process exit.

Restart reconstructs eligibility from job, lease and citizen snapshots. READY jobs
may be reconsidered; restored open attempts remain INTERRUPTED and uncertain until
an explicit owner reconciliation establishes safe disposition. Restored leases use
a new acknowledged epoch and cannot authorize old effects. Policy/index caches are
disposable and confer no execution authority.

## Production entry points and compatibility

`WorkerDispatcher.step`, `diagnostics`, `clearPolicyCache` and `close` expose the
bounded policy boundary. JobLifecycleStore adds `dispatchPage`, `bindWorker`,
`remainingAllowance` and `workerAvailable`; CitizenRegistry adds `controlPage`.
These are owner views or previews, not new persisted state.

KernelSession composes another instance of the existing executor and lease gateway
for queued jobs, sharing the original physical gateway, lease service, registry,
repository and resolver. BootstrapController's existing submission lane retains
its own handles; `ownsSubmission` prevents dispatcher admission of those runs.

`/aivillage kernel queue` accepts the existing harvest coordinates and an owned
citizen as responsibility anchor. `/aivillage kernel job-status` and `job-cancel`
use exact scoped job IDs; suggestions exclude other origins. Queueing uses no
inference. Missing implementation, planning, unsupported runtime, incompatible,
unauthorized and blocked resolution outcomes remain distinct diagnostics.

IMP-014, planning, generation, organizations, new primitive families and independent
job/lease persistence remain outside this extension.
