# IMP-016 world retention contracts

World retention bounds history and reclaims eligible owner data while preserving authoritative knowledge, rights and job accounting. Architecture remains **0.5**, Contracts/IR **1**, retention policy **1**. The new task/research evidence domain has its own schema **1**; `SaveCompatibility.registered()` now declares nine explicit readers and the existing bootstrap handoff remains the only migration.

## Owners and boundaries

| Owner | Retention binding | Protected data |
|---|---|---|
| `VersionedSkillRepository` | Incremental cursor; orphan/stage collection; admission capacity callback | Every current admitted/quarantined alternative, dependencies, current/previous manifests and quarantine files |
| `RetentionEvidenceStore` | Typed task/research summaries, contextual failure aggregates, bounded window replacement | Registered direct/transitive pins, retained rollback references and valid original/new snapshots |
| `JobLifecycleStore` / `JobJournal` | Nonblocking pre-publication pins, actual protected-root API, file/count accounting | All retained attempts/executions, pending pins, budgets, receipts, credits, dependencies and cancellation accounting |
| `BootstrapController` / `BootstrapJournal` | Digest roots for retained/active runs, terminal summary intake, accounting | Run ownership/history and supported migration originals |
| Citizens / resource leases | Accounting adapters | Entire authoritative domains, permissions and recovery files; no deletion interface |
| Primitive diagnostics / retrieval | File accounting; diagnostic concurrent-writer reservation | Original domain writers/limits; retrieval deletion remains an explicit cache-owner operation |

`WorldRetentionManager` owns policy, consistent bounded scans, reachability, eligibility and approval. An `Owner` supplies a revision and incremental cursor, declares protection/eligibility and performs approved mutation. Register storage owners on the world-storage worker before service scheduling; subsequent same-worker registrations advance an epoch and invalidate old plans. Root providers can register later through `RetentionRoots` without depending on repository/research/job classes.

`Key("skills", sha256)` locates the physical immutable body for retention. Full `ArtifactRef` identity, capability/version, admission and access semantics are unchanged. No latest-version substitution, semantic-equivalence proof, admission/retraction, schema migration, compression or Minecraft cleanup is implemented.

## Reference fence and collection

`RetentionRoots` publishes immutable, complete source snapshots with monotone source revisions and a global generation. Its 8-attempt CAS budget returns `APPLIED`, `UNCHANGED`, `BUSY`, `STALE` or `LIMIT`. A source **must successfully publish before using new references**. `BUSY` means acquisition failed; retry and revalidate the owner record. Existing unchanged pins remain valid while collection is in progress. No tick call waits for a worker-held monitor or filesystem operation.

Scans meter inventory, root seeding, iterative graph nodes/edges and eligibility. Missing dependency edges, cycles anywhere in the known graph, duplicate keys, incomplete owners/roots, over-budget pages and changing epochs invalidate collection. An external root may describe a not-yet-durable research candidate; its absence does not invent a body. Every declared existing dependency must resolve. Incoming edges preserve referential integrity even for unrooted data: collect an eligible parent before its dependency, then rescan.

Immediately before each deletion the manager acquires an exclusive reference-generation lease and rechecks every participating owner revision and registration epoch. New reference changes return `BUSY` during this finite owner operation. The repository additionally holds its writer monitor, rechecks current/previous membership and verifies file attributes plus the orphan body's SHA-256. Body publication/staging attempts advance the retention epoch even if manifest publication fails. A changed owner, new pin or changed file refuses the old proposal. Every mutation requires a fresh scan.

The repository parses supported rollback manifests and orphan descriptors/IR dependencies in bounded reads. Current and previous bodies stay protected. A superseded rollback body becomes eligible only after its owner replaces that rollback envelope and no registered root/reference reaches it. Orphan parent/child fixtures prove closure protection and parent-first collection. Malformed/future/opaque orphan data fails closed; integrity quarantine stays repository-owned.

History rollover/expiry also obtains a manager-approved discard lease. Approval checks the complete graph, registered pins and surviving incoming references before replacing a window. Pins on a trace or failure aggregate can cause explicit capacity rejection. Manager-driven aggregate collection replaces only the approved expired record, preserving other pinned aggregates. Window replacement never erases job accounting or immutable skill bodies.

## Usage, capacity and finite bounds

| Bound | Current maximum/default |
|---|---:|
| Root providers / declared roots, including duplicates | 16 / 4096 |
| Storage owners / graph nodes / dependency links | 16 / 8192 / 65536 |
| Traversal slice inspections / read bytes | 16 / 131072 |
| Whole inventory reads | 16777216 bytes |
| Inventory inspection allowance | Node cap plus 8 per owner |
| Diagnostics / omitted counter | 16 / saturating scalar |
| Category quota / world admission quota | 16 MiB and 4096 records per category / 64 MiB and 16384 records total |
| Evidence aggregates / recent traces / representative samples | 32 / 16 / 3 |
| Evidence current envelope / stage+backup+current ceiling | 65536 / 196608 bytes |
| Temporary matching shortage premise / history retention | 30 seconds / 7 days |
| Tick offers / session queue / background event batch | 8 / 64 / 8 |

Quotas can be lowered to zero; arithmetic overflow or stale/incomplete accounting cannot claim capacity. The traversal byte limit meters cursor reads; owner atomic collection/replacement has separately fixed envelope/body bounds. Repository deletion reads at most one 65536-byte rollback envelope and one 60000-byte orphan. Evidence replacement writes at most one 65536-byte stage plus one bounded backup, with up to three envelopes coexisting. No compression/decompression path exists.

Usage includes current, previous, stage, quarantine and preserved recovery files. Counts represent owner logical records and artifact/stage/quarantine files; evidence counts include current and rollback summaries. Byte ownership for shared envelopes remains aggregate. `privateUsage(caller)` returns only attributable byte/count totals; whole-world aggregate inspection requires an explicit policy grant. Production defaults to denying unscoped aggregate readers. No raw prompts, conversations or exception messages are stored or exposed.

Repository publication assesses **replacement** usage, including new body and temporary manifest headroom, before writing. It retains the repository's independent artifact/body/metadata/variant/file/disk limits. `STORAGE_LIMIT_REACHED` is returned for category/total or recovery-file headroom exhaustion; stale/unavailable accounting returns `STORAGE_UNAVAILABLE`. Existing admitted knowledge remains usable. Other owners keep their own hard persistence limits; current defaults fit the world envelope. The independently running primitive diagnostic writer reserves its complete 10 MiB hard ceiling in admission assessments, preventing a race with its later growth. Cache writers run on the same owned storage worker. No eviction follows admission rejection.

## Failure evidence and recovery

Failure equivalence is explicit: trusted scope, capability, exact artifact when known, reason and digest of the supplied bounded request/observations. It is observational equivalence, not a semantic proof about all world state. Random observation UUID/tick are excluded from a known observation premise. Repair/direct-candidate contexts lacking complete facts use distinct bounded observation context. Counts saturate at `Long.MAX_VALUE`; samples preserve a representative first observation and bounded recent examples. Successes remain typed recent summaries and are never encoded as failures.

`premiseApplies()` matches only resource/facility shortages, the supplied observed digest and the finite interval. Changed observed facts or expiry invalidate it. It is advisory and is never consumed as a routing veto, permanent defect, quarantine decision or skill degradation.

The evidence owner validates UTF-8, strict JSON byte/token/shape bounds, schema, world, checksum, unique IDs and typed counters before accepting a file. Encodings are validated against the reader before stage publication. Fault/cancellation checkpoints surround stage, backup and replacement. Unsupported/corrupt current data stays preserved and read-only, even with a valid older backup. An uncertain replacement exposes `publicationMayHaveSucceeded`; reopen selects a valid original/new authoritative snapshot. Five separately killed JVMs verify actual interrupted boundaries. Retention performs no model calls or Minecraft effects, and reopens no interpreter frames.

Tick intake and reference updates are bounded and I/O-free. `WorldRetentionService` schedules at most one turn on the existing world-storage worker; it batches evidence and advances one traversal/collection slice. Queue pressure and unsuccessful owner operations are counted. Offers are advisory until durable replacement; stopping can discard unpublished queued observations and reports them as dropped. Stores close after pending worker turns. This does not lose job accounting, admission, rights or execute uncertain effects.
