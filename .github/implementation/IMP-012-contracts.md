# IMP-012 resource lease contract

Architecture extension **0.3** consumes the IMP-011 extension 0.2 and bootstrap
0.1 invariants. Resource identities, leases and the lease journal use schema 1.
Job, request, IR, artifact, citizen and bootstrap journal schemas remain unchanged.
Baseline: 5e69d945bee6cb5f3e36c76588a827b40a5efce9. Platform pins remain Java 25,
Gradle 9.7.0, JUnit 5.11.4, Gson 2.10.1, Minecraft 26.3, Loader 0.19.5,
Fabric API 0.161.0+26.3 and Loom 1.18.1.

## Owners and authority

ResourceLeaseService alone owns coordination claims. JobLifecycleStore alone
owns jobs and assignment generations. Minecraft owns stock and physical effects.
The lease service never selects workers, invokes models, loads chunks, mutates
inventory or writes job state. LeasedGateway is a bounded coordination decorator
for the existing SurvivalGateway; physical permission, reach, capacity, custody
and game-rule checks still run there. A lease is not permission or permanent
ownership. Players and other actors may change resources after a grant.

One world-wide service covers every player scope and dimension. A physical
identity contains the world UUID, dimension and canonical target, never a
principal, job or scope. Lease metadata retains the authoritative job origin,
explicit job generation and actual assigned worker. Current job control and
worker control are rechecked; read access alone does not permit acquisition.
Conflict/availability results contain quantities and shared reasons only,
never another principal's job, goal, worker, scope or lease IDs.

## Resource forms and atomic groups

Stock is a quantity of one supported item in an identified container block,
or mature wheat in one bounded crop cuboid. Identical crop pools sum quantities;
different overlapping crop pools conflict conservatively.
Equipment is one supported non-stackable item in a specific container slot.
Facilities are exclusive installed storage or crafting blocks. Spaces are
canonical inclusive integer voxel cuboids. Stock claims sum checked outstanding
quantities; equipment and facilities are exclusive. Space conflicts require
at least one shared voxel: equal end coordinates overlap; adjacent non-overlapping
cells may touch faces and coexist. Conflicts compare physical identities across
scopes. Separate worlds/dimensions remain distinct.

The Fabric binding supports container wheat and mature crop stock, iron-hoe equipment, storage facilities
and crafting tables, plus bounded loaded spaces. A facility claim implements no
new crafting or inventory primitive. Unsupported or ambiguous targets are
rejected. Joined chest identities are conservatively rejected
before grant, never treated as two allocations of the same inventory.

Repeated stock keys are summed with checked arithmetic before evaluation;
identical exclusive keys collapse to one claim. Acquisition evaluates the
normalized group once and publishes all members or none. Conflict does not
enqueue an automatic retry or retain a partial group. Clients may await the
single storage acknowledgement inside their original finite action deadline.

## Generations, observations and time

Every grant has a unique lease and group ID, a service epoch and a monotonically
increasing grant generation. Renewal advances the lease generation; an old
reference cannot renew, release or validate a newer reference. Repeated release
of the same retired reference is safe and produces no world effect.

Ticks are meaningful only within the current epoch. A lease is usable exactly
while currentTick < expiresTick; tick 100 is expired for expiry 100.
Renewal requires at least 10 elapsed ticks, current owner/control and a fresh
observation. Renewal count and duration are finite and cannot extend a job's
original deadline. A backwards tick clock fences use and starts no new grant.
Every restored lease is interrupted under a newly durably published epoch;
wall-clock changes and reset game ticks cannot revive old references.

Observations are immutable, bounded, scope-authorized runtime evidence.
UNKNOWN/unloaded does not mean absent. Acquisition and renewal require freshness;
effect validation re-observes current identity, availability and permission.
Container replacement, stock reduction, disappearance, expiry, cancellation
or owner loss fences the claim before another effect. Actual receipts survive
revocation. Stock consumption can reduce a claim only through guarded trusted
owner accounting; claiming does not move or mint stock.

The gateway debits crop stock through actual HARVEST receipts and durably
publishes each debit before another action. Fully consumed stock still permits
pickup and deposit, but blocks another harvest before physical mutation.
The execution's typed request, authority and current pinned run must match the
durable assignment. Routing may refresh its observation reference.

## Publication, recovery and bounds

Only one immutable snapshot publication is outstanding. Pending claims reserve
capacity but cannot authorize effects. A grant is acknowledged only after exact
durable publication, with revision and epoch matching. Failure, uncertain
acknowledgement or timeout fences the service and retains pending roots.
Journal serialization, fsync and atomic replacement run on the world storage
worker. Recovery keeps originals and backups; corrupt/future data stays inactive
and read-only. No migration from the prototype's ephemeral crop reservations is
asserted.

Strict JSON excludes null. The required lease row reason is an empty string for
an active claim with no failure; terminal reasons use the shared reason enum.

Fixture limits: 8 leases/job, 4 resources/group, maximum duration 100 fake ticks,
minimum renewal interval 10 ticks, 8 renewals, 8 reconciliations/slice, 64 active /
128 retained lease records, 256 inspected cells and finite region axes.
Production uses an explicitly documented 1,200-tick duration and up to 256 cells
per axis while retaining all count/work caps. Storage is capped at 1 MiB / 128
records, with strict 64 KiB / 4,096-token rows and a 30-second acknowledgement cap.
Queries, expiry, owner cleanup and revocation are bounded; no unbounded retry
queue, spatial scan, diagnostic history or silent record eviction is allowed.

## Consumers and GP-13

IMP-013 chooses workers through jobs and supplies exact generation-fenced lease
references; leases never choose assignments. IMP-018 uses fresh availability
without creating stock. IMP-024 retains current scope and revocation checks.
IMP-029 reports owner loss through the existing job/lease owners. Retention must
include committed and uncertain pending job roots from the lease service.

IMP-012 acceptance uses actual JobLifecycleStore clients, ResourceLeaseService,
LeasedGateway and SurvivalGateway independently of dispatch. Two principals
compete for five stock units with demands three plus three; real Minecraft
effects establish conservation. Cancellation, authority revocation, external
container changes, expiry, stale references, storage faults and a separate
Minecraft restart are tested with inference disabled. IMP-013 supplies the
remaining dispatcher portion of GP-13.
