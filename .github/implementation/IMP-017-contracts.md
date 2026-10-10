# IMP-017 observed world knowledge contract

Baseline: `ae4bbe5423ef1a9e8d4aee94e11d64aba3dca65b` (IMP-016 / PR #10).
Normative route: documentation `AGENT_DOCUMENTATION_INDEX.md` → original IMP-017 prompt in `docs/source/docs/implementation-prompts.md.md`, original backlog IMP-017, architecture observations/storage/future boundaries, and GP-17. The historical registry's absent IMP-015/016 statuses do not override their live public implementations. Architecture remains **0.5**, Contracts/Skill IR **1**, IMP-015 compatibility policy **1**, and IMP-016 retention policy **1**. This additive observation contract is **1**; its current envelope/record schema is **2**, with an explicitly specified inactive schema-1 reader and owner migration.

## Trusted physical evidence and ownership

Minecraft owns current blocks, entities and inventories. `SurvivalGateway` owns observations and effect-time validation. `ObservedWorldKnowledgeStore` owns retained historical records, metadata, publication and disposable indexes. The existing storage worker owns serialization, disk I/O, queries, indexing and collection; the server thread captures live evidence. No model, planning, lease or job ownership is added.

Plain `Contracts.ObservationSnapshot` is a constructible value and is insufficient ingestion proof. Only `SurvivalGateway` can construct `ObservedEvidence`; it does so when an authorized observation primitive finishes. Evidence contains its actual trusted request owner, observer actor, precise typed target, gateway snapshot, capture clock time, gateway-session UUID and monotone local issuance sequence. `observedEvidence(handle, caller)` checks current observation authority and target permission. The runtime's I/O-free sink feeds `ObservedKnowledgeService`; queue rejection cannot change the primitive result. The store accepts this issued type exclusively, rechecks the originating context/world/current owner authorization and source session, validates facts and checks authorization again immediately before replacement.

Supported targets are source crop counts in the exact scanned cuboid, actor wheat/seed stock at the captured actor location/entity, and container wheat/seed stock at its exact location. Counts are limited to the gateway's supported typed fields. A source absence means no mature wheat in that fully observed bounded scan. It says nothing about other crops, unsurveyed cells or the rest of the world. Stock zero with `PRESENT` is an observed present stock with zero wheat, not an absent facility. `UNKNOWN` never establishes absence.

The gateway's existing observation revision/tick is a capture tick, **not a global Minecraft mutation revision**. A source scan may span bounded polls; it is not an atomic world image. Gateway session plus issuance sequence establishes local evidence order without inventing a global revision. Container identity is the gateway's reported address, not an immutable block-entity incarnation. Identical replacement containers cannot be distinguished unless the supported source supplies changed identity/facts or explicit invalidation. No live object graphs, chunks or model hypotheses are persisted.

## Record meaning, deduplication and history

Every record preserves a stable first-snapshot UUID, trusted owner principal/world/domain, typed dimension/target/observer context, first and latest proofs, original capture times/ticks/revisions/source IDs, saturating accepted-observation count, finite freshness and retention deadlines, invalidation reason/time, and bounded explicit sharing recipients. The enclosing schema is authoritative for all records. The public projection exposes schema 2 and a deterministic fact/source/observer/target/owner deduplication digest. Proof snapshots are contextual evidence references; arbitrary executable artifact references are outside these observation primitives.

Equivalent currently active evidence from the same gateway session **and observer**, owner and target coalesces when identity, status, scanned scope and typed counts match. Only a genuinely newer issued observation advances latest proof and freshness. Replaying the latest capture is `UNCHANGED`; older unretained captures are `OUT_OF_ORDER`, without a new count or expiry. First proof and identity remain unchanged. Different sources/observers keep separate provenance. New contradictory evidence from a comparable source supersedes old evidence explicitly; its old proof remains stale history. Incomparable current conflicting evidence qualifies both alternatives as stale/conflicting rather than manufacturing certainty.

History rollover keeps the configured newest bounded records under deterministic time/UUID ordering, and requires IMP-016 discard approval for every removed key. A pin can refuse rollover. Storage pressure rejects new evidence; it does not evict protected records or alter other owners. Re-observation after explicit invalidation starts a new historical record. Invalid target/source/incompatible metadata is authoritative retained metadata; inferred expiry is a query classification.

## Freshness and authorized queries

| Result | Exact meaning |
|---|---|
| `CURRENTLY_VERIFIED` | Supported trusted observed fact, same available gateway source, no invalidation/conflict, nonregressed clock, and capture age inside the finite freshness window. This is verification under the retained-evidence contract, not guaranteed current Minecraft state. |
| `OBSERVED_ABSENT` | The same checks hold and a permitted actual observation reported absence in its exact scope. |
| `UNKNOWN` | No applicable authorized evidence, or a trusted bounded observation was itself unknown. |
| `STALE` | Expired, source lost, superseded, clock regressed, conflicting, or query coverage incomplete. |
| `INVALID_TARGET` | Owner explicitly invalidated a changed/removed target. |
| `INCOMPATIBLE` | Record invalidated as incompatible, supported old schema not yet migrated, or conservative recovery cannot trust current publication. |

`queryFreshEvidence` / `createEvidenceSnapshot` return immutable records, classification, qualification, capture time and bounded coverage/work/context metadata. Limits are explicit: an incomplete result has `LIMIT_REACHED` and cannot be used as exhaustive evidence. A truncated verified/absent result is conservatively stale/incomplete. Deleted or stale indexes produce `INDEX_UNAVAILABLE`; corrupt recovery produces `STORAGE_UNAVAILABLE`, optional incompatible historical recovery rows, and never complete coverage. Loading, querying, replaying, indexing or migration cannot refresh observation time.

`snapshotCurrent` rechecks the relevant authorized projection and freshness before future asynchronous consumption. Unrelated private writes do not expose a global revision through this check. Snapshot references are exact IMP-016 observation keys; a consumer must successfully publish them through `RetentionRoots` **before using protected references**, and revalidate the snapshot. The immutable snapshot itself is neither a resource lease nor executable authorization. Every physical effect still uses the existing gateway's current checks. Staleness never invokes research, generation or planning.

`KernelSession.observedKnowledge` accepts a real server player on the server thread, resolves its trusted context, caps outstanding queries at eight, and schedules the same owner API on its existing storage executor. No new command or future planning consumer is implemented.

## Privacy and explicit sharing

Owner and world/domain/principal are part of ingestion, deduplication and index keys. Private target histories at identical coordinates remain separate. Query results, denials, counts, recovery rows and snapshots expose only that caller's own or explicitly shared records; denial carries no foreign ID/count/revision. Retention accounting attributes records only to their owner; shared-envelope bytes remain host aggregate accounting under IMP-016's existing policy.

`shareObservation` requires the exact owner, same-world recipient, owner-generation lease, and the existing `VersionedSkillRepository.VisibilityPolicy` grant. That policy is checked before and immediately before publication, at query disclosure, and at snapshot revalidation. The persisted recipient set selects the exact record; policy alone does not reveal all the origin's observations. Recipient projections redact other recipients. A revoked policy grant hides the retained share. No control, job rights, resource ownership or skill eligibility is transferred. Production composes the existing private `TrustedContext::equals` policy; the loaded-world fixture supplies an explicit revocable grant through this existing interface. No second visibility registry is created.

Policy callbacks read thread-safe immutable owner views and fail closed on unavailable policy/source lookups. Production ingestion consults `CitizenIdentityStore`'s current immutable owner snapshot on the storage worker, never Minecraft off-thread. Server-side control remains with the citizen/gateway owners.

## Finite bounds and disposable indexes

| Bound | Default / accepted hard maximum |
|---|---:|
| Authoritative records | 64 / 128, also constrained by envelope byte/token limits |
| History per owner/typed target | 4 / 8 |
| Sharing recipients per record | 8 |
| Record proofs | First and latest only; count saturates at `Long.MAX_VALUE` |
| Current envelope | 65,536 bytes; strict JSON depth 32, tokens 4096 and string 1024 |
| Current + previous + staging | 196,608 bytes; optional exact v1 archive adds at most 65,536 |
| Freshness window | 30 seconds / 300 seconds |
| History retention | 7 days / 30 days |
| Query authorized payload scan / results / context | 128 / 16 / 16,384 bytes (default results 8, context 8192) |
| Query scope-index/policy checks | At most 128 candidate references; payload scan obeys the requested lower allowance |
| Conflict comparisons | At most 128², further bounded by inspected payloads; no world reads |
| Index rebuild slice | At most 16 records; at most 128 records and 1152 owner/recipient links per build |
| Tick offers / pending queue / worker turn | 8 / 32 / 4 |
| Session asynchronous queries | 8 outstanding |

The in-memory index is a disposable view keyed by trusted viewer, world/domain, exact dimension/target, and captured owner revision; rows preserve source session and observation revision. Index deletion changes no record, timestamp or permissions. A build captures immutable state and refuses publication after owner revision drift. Complete normal replacement rebuilds this fixed-size view on the worker in metered slices. No cache files or competing spatial/world maps are introduced.

## Compatibility, persistence and retention

`SaveCompatibility.registered()` adds the `observed-world` reader for versions 1/2 and one explicit nonsemantic 1→2 rule; bootstrap's existing migration and all existing readers remain intact. Schema 1 is specifically defined as the same typed records without `invalidatedAt`. Its owner adapter supplies zero for no invalidation or the existing observation time as a conservative lower bound for an existing invalidation, then retains the exact original bytes as `state.legacy-v1.json`. Observation times, identity, source, expiry and scope remain unchanged. This schema is an explicit supported compatibility fixture, **not a claim of a previously deployed observation store**. The registry alone performs no migration. `migrate(expectedRevision, cancelled)` is the actual quiescent owner adapter; runtime calls it on startup after registering all retention owners. Quota refusal creates no archive.

The sole writer uses an exclusive file lock, bounded strict/checksummed UTF-8 readers, validated staging, forced stage/backup, atomic replacement and directory force. Existing current bytes/meaning are revalidated before publication, including after staging. Cancellation/fault checkpoints cover all five replacement boundaries. An uncertain post-replace failure reports publication uncertainty. Unsupported/corrupt/missing/oversized current data is preserved, read-only, with a valid prior generation available only as incomplete incompatible historical evidence. Unknown backups are preserved/inactive. A valid current state wins over abandoned staging; no physical action is replayed.

`retentionOwner()` registers `observations` in the existing `EVIDENCE` category. Incremental logical records plus physical current/previous/stage/archive accounting use the existing `RetentionFileOwner`; backup envelopes and migration originals remain protected. Full replacement reserves staging/recovery headroom through `assessReplacement`, including the old-current backup count if cancellation precedes replacement. Backup accounting is updated when the backup is forced, even when cancellation preserves current state. History removal requires `approveDiscard` and the existing reference-generation lease. Manager-driven expiry collection checks owner revision, eligibility, current file meaning and the current clock, and publishes through this owner. Root pins protect snapshot references; stale scans and changed files cannot authorize deletion. No garbage collector, migration framework, effect recovery or physical world cleanup is added.

All store/service operations are model-free. Neither class has a model provider, gateway mutation port, chunk loader or planning dependency. GP-17 binds the existing crop/container observation and effect interfaces rather than inventing exploration/facility primitives.
