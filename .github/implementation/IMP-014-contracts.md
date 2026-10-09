# IMP-014 AI work broker contract

Architecture extension **0.5** consumes the integrated IMP-013 extension 0.4,
IMP-012 extension 0.3 and IMP-011 extension 0.2. Baseline:
`a3d14207ad8d6ed569be8160b9a2a68ad8ad1cdf`. Generation transport, requests,
Skill IR, artifacts, citizen identities, jobs and leases retain their current
schemas. Broker `View`, `Terminal`, `Stats` and acceptance summaries are schema 1.
There is no persisted broker queue or authoritative broker database.

## Owners and public boundary

`AIWorkBroker` implements the existing `Contracts.GenerationPort`. Research uses
that declared hook; its generation result still has the original request ID,
owner and role. `submit`, `subscribe`, `cancel`, `queueStatus`, `view`,
`terminalResult`, `step`, `enabled`, `status`, `stats` and `close` define the broker
boundary. `Contract(name, version, generationSchema)` pins coalescing compatibility.
The implemented consumer is `research-generation/1` with Generation schema 1.
No planning consumer, remote fallback or alternate provider is introduced.

The broker owns queue membership, subscribers, deterministic scheduling, shared
hardware allowance and usage attribution. LocalGenerationAdapter owns wire
construction, local transport, preparation, byte enforcement, model identity and
truthful compute status. ResearchAdmissionController alone owns retries, repair,
candidate validation, fixtures, physical trials and admission decisions.
VersionedSkillRepository alone publishes artifacts. Existing job/lease/executor/
gateway owners retain all responsibilities and effect checks. Sharing inference
MUST NOT share a physical trial, award completion or publish an artifact.

## Bounds and policy

One broker controls one existing local backend with **one** occupied compute slot.
There are at most **four queued work cohorts**, **eight subscribers per cohort**,
**64 retained subscriber records**, and **one dispatch per step**. A step examines
at most five owned work records and forty cohort members. Admission and status
scan at most the configured bounded records; they never wait on a future.
Requests keep the existing 8192-byte context, 32 primitive and 16 dependency bounds;
adapter wire/response bounds remain 16384/65536 bytes. No broker executor, transport
thread or response buffer is allocated per subscriber.

URGENT precedes NORMAL precedes BACKGROUND, with FIFO sequence ties. After four
starts of other work, a waiting cohort becomes aged and precedes all unaged work;
aged cohorts use FIFO. A continuously eligible cohort is dispatched within at most
`agingStarts + maxQueued = 8` further successful dispatch opportunities. This is an
opportunity bound, conditional on backend cessation, current authority and the
original deadline. A blocked backend is reported; no wall-time success is promised.
Coalesced members cannot extend a cohort's original enqueue age.

A fifth distinct pending demand returns REJECTED/BUDGET_EXHAUSTED with no inference.
A ninth equivalent subscriber requires another bounded cohort or receives
backpressure. Only queued cohorts accept additional members. Dispatch seals the
cohort: late subscriptions receive separate work and their original parent limits.
No automatic retry, outage backlog expansion or budget refresh exists.

The pinned production world-session hardware envelope is 128 calls, 64 repair calls,
2097152 input bytes, 1048576 output bytes and a 24-hour deadline. It never refreshes
through gate toggles, cancellation, queueing or retries. Normal request/research
limits are additionally enforced; exhaustion requires an explicit new session,
and pending work is never resumed by that session.

## Equivalence, authority and privacy

Queued requests coalesce only when consumer contract/version, generation schema,
role, capability/parameter constraints, complete bound arguments, observation,
primitive signatures, pinned dependencies, and exact prompt context are equal.
Their world IDs must also be equal. Private coalescing requires the exact same
TrustedContext. Different actor, world, observation, role, private text or version
means different work. Descriptor/model transport is shared only through this one
configured backend.

Cross-scope coalescing additionally requires an explicit trusted Sharing policy
for **every** pair of members. Production uses `Sharing.privateScopes()`; no
cross-player sharing switch is inferred. A policy cannot bridge different worlds
or unequal prompt fields. The broker checks current authorization at admission,
before dispatch and before candidate delivery. Revoked subscribers receive
DISCARDED/AUTHORITY_DENIED with no candidate. Each recipient gets its own ID,
owner and role; the transport's original owner is never exposed as another
subscriber's owner.

`view`, `terminalResult`, `cancel`, explicit work subscription and queue enumeration
require the subscriber's exact TrustedContext. Work/subscriber UUIDs grant no
permission. Service `Stats` exposes aggregate counts/bytes only, no prompts or
private identifiers; adapter-compatible `status` is an internal composition API.
Production `KernelSession.inferenceStatus(player)` is scope-filtered.

## Cumulative budgets and physical accounting

Each subscription has an immutable independent inference envelope and uses a
child of its existing runtime-owned parent. Remaining limits are clamped at
admission and again at dispatch. A frozen work cohort uses the tightest remaining
input/output/call/repair bounds and earliest original deadline. Cancellation MUST
NOT widen that envelope or refresh backend preparation time.

`Budgets.Ledger.shared` links at most nine beneficiaries (eight subscribers plus
the hardware ledger). Each actual adapter debit atomically charges the physical
work, each distinct subscriber ledger and each distinct shared ancestor **once**.
Different coalesced children of one research parent each receive their own usage,
while that parent receives one physical debit. Separate physical calls charge it
again. Repeated subscriber IDs are idempotent within bounded retention and cannot
replace the original parent, contract or limits. Eviction never resets a parent.
Budget transactions use one short monitor, no I/O or callbacks, and a bounded
64-node accounting graph; neither partial multi-root debits nor deadlock is allowed.

Before preparation, the broker reserves the whole permitted response output in
every distinct beneficiary/ancestor. Actual accepted output consumes this hold.
A measured completed response releases only its unperformed remainder. Once a
call started, abandoned or transport-uncertain output retains the hold until
cessation and then conservatively forfeits its unknown remainder, even if the
subscriber deadline expired. This is explicitly **unmeasured output**, never
claimed as measured generated bytes. A proven never-started call releases its hold.
Cancellation, retry or subscription churn cannot reuse held or forfeited output.

Views separate charged usage, reserved output and unmeasured output. Stats count
physical calls/input/accepted output once and separately report unmeasured output.
Caller terminal outcomes are immutable; later accounting/compute status may become
more precise through the current View/Terminal envelope without reviving the caller.
Token counts remain unknown unless the transport supplies them.

## Cancellation, outage and restart

Subscriber state is separate from Generation.Compute. CANCELLED abandons that
subscriber immediately and never delivers a late candidate. Remaining authorized
members keep running. Cancelling the last member requests backend cessation once.
STOP_UNCONFIRMED or thrown/ignored cancellation retains the slot and backpressure.
Only COMPLETED, STOP_CONFIRMED, or a terminal proven NOT_STARTED result releases
it. Completed compute awaiting result handoff remains owned until the caller states
are settled. A new broker using a still-busy adapter reports its actual compute
status and cannot dispatch replacement work.

Disabled/unavailable requests receive explicit UNAVAILABLE/MODEL_UNAVAILABLE;
queued/active deadlines receive EXPIRED/BUDGET_EXHAUSTED. Status and cancellation
remain available. Known capability selection, queued jobs, worker dispatch and
gateway execution MUST remain model-independent during saturation and outage.

Queue/cache and diagnostics are disposable bounded process-local state. Shutdown
abandons pending subscribers with INTERRUPTED, clears queued membership and requests
cessation of live compute. A fresh broker does not read/replay pending demand.
Existing journal, repository, identity, job, lease and backup formats are untouched.
No inference call or uncertain Minecraft effect is replayed. New demand still
requires current authorization, explicit request limits and backend readiness.
The broker makes no distributed/external-process cancellation guarantee.

## Thread ownership and acceptance

All queue/status/subscription decisions require the creating game thread. The
existing adapter performs preparation/inference on its bounded worker; callbacks
only publish one atomic bounded event. `step` revalidates identity, authority and
deadlines before passing untrusted candidate bytes to research. No future join,
blocking persistence or inference runs in a broker tick.

GP-14 uses a controlled delayed backend and real production kernel owners. It
proves equivalent sharing, private separation, cancellation, queue backpressure,
expiry, unconfirmed occupancy, two physical known crop jobs, scope-filtered status,
and disposable restart without replay. Actual-adapter and actual-research fixtures
separately prove byte accounting and independent trial/publication decisions. The
existing genuine local-model acquisition and model-stopped reuse lanes remain
required regressions; the controlled fixture is not a genuine inference claim.
