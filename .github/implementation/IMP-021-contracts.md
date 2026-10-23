# IMP-021 accomplishment evidence contracts

Normative source: [IMP-021 and the shared contract](https://github.com/senavirathne/CognitiveCraft-Documentation/blob/b1d5bf71365d1b1762699c006465caa1db8e5be5/docs/source/docs/implementation-prompts.md.md#imp-021), architecture contract sections 7/14/18/22 and GP-21 at the same documentation revision. Public baseline is `01c623f4915e8f760659eac1a1426e1bbca6e3a5`; the prepared local IMP-020 dependency is `19874e59381e9165510ccfc5eaee6474c776ac1f`. IMP-021 directly consumes implemented IMP-008/011/015/016. Documentation remains read-only.

Kernel architecture stays **0.5**, Contracts/IR **1**, citizen identity **1**, Jobs **1**, retention policy **1**. This owner adds accomplishment evidence **schema 1** and recognition rule policy **1**; SaveCompatibility adds its independent `accomplishment-evidence` reader. No historical outcome, identity or prerequisite schema is changed. The pinned Java 25, Gradle 9.7.0, JUnit 5.11.4, Gson 2.10.1, Minecraft 26.3, Loader 0.19.5, Fabric API 0.161.0+26.3 and Loom 1.18.1 declarations remain unchanged.

## Owner interfaces

| Interface | Responsibility and trust boundary |
|---|---|
| Accomplishments.OutcomeRef | World, existing job ID and exact job revision; no supplied credit, worker, narrative or method |
| JobOutcomeSource | Off-thread trusted adapter to acknowledged immutable JobJournal and CitizenIdentityStore snapshots |
| AccomplishmentLedger.recordOutcomes | Atomic bounded batch validation, attributable deltas, persisted repeat-safe progress and derived aggregates |
| AccomplishmentLedger.query | Current controlling principal's private evidence and recognition; maximum 16 aggregate rows |
| AccomplishmentLedger.summaries | Explicit current aggregate policy; returns counts/conditions without job IDs, receipts or private supporting samples |
| AccomplishmentLedger.rules | Small versioned host rule updates, deterministic qualification and retirement; no authority or stat API |
| AccomplishmentService.maintain | One immutable acknowledged job reference per game-thread turn, processed on the existing serialized storage worker |
| KernelSession.accomplishments | Server-thread scoped query intake with at most eight pending queries; no synchronous ledger I/O |
| AccomplishmentLedger.retentionOwner / protectedRoots | Existing manager accounting and conservative protected job/method supporting roots |

Jobs and its physical executor/gateway remain authoritative for lifecycle, receipts, effects, custody and completion. CitizenIdentityStore remains authoritative for stable identity and control. The ledger writes only its own evidence envelope. Its failure cannot rewrite Jobs, refund budgets, resurrect an attempt, assign a worker, perform an effect or fabricate a missing receipt. Neither narrative, current chest inventory, terminal status alone nor caller-supplied aggregate counters can establish credit.

The schema-1 production verifier supports the existing registered `cognitivecraft:deliver_wheat` outcome. An extension to another capability requires an explicit verified source/receipt rule; it cannot infer competence for unsupported work. Source refs must resolve against the same world's exact current job revision. Every contributing worker must match the registry's stable citizen/entity/dimension binding and controlling origin. Each receipt must match that worker, source, destination and a Jobs-pinned execution. Receipt reuse within an attempt family or across source jobs rejects. A claimed credited amount above its verified complete physical stages rejects, including overflow. Successful attempt completion uses the existing CropDelivery completion predicate. Supported immutable owner snapshots are consumed off-thread; no untrusted JSON outcome-import or narrative event API exists.

## Attribution, context and replay

For each run/batch, verified output is the minimum recorded HARVEST/PICKUP/DEPOSIT quantity. The ledger never credits more than the owner-approved attempt credit. Existing run credit is retained first; only a positive verified delta is assigned to the matching worker/method/environment. A worker's partial contribution survives a later shortage/cancellation. Uncertain interrupted effects receive no new credit until Jobs reconciles them. A whole job's completion is counted once, for its actual certain successful attempt; producer and dependency/allocated root counters do not manufacture another physical accomplishment.

Progress stores the job ID/revision, immutable origin/request fingerprint and bounded attempt/generation/worker/run credit watermarks. It contains no unbounded action-ID archive or probabilistic dedup filter. A processed or older ref is a duplicate and changes no bytes. An unseen stale ref waits for the latest acknowledged source revision, which contains cumulative attempts/receipts. Newer owner snapshots cannot reduce or rebind previously credited evidence. Progress is persisted in the same atomic generation as its aggregate and recognition changes, preventing a crash between credit and dedup acknowledgement.

The current Jobs owner retains at most 64 jobs and has no terminal-job eviction API. The ledger retains exact progress for those source IDs and pins `Key("jobs","records")`; it does not drop watermarks to make room. A future source-retention/epoch handoff needs an explicit migration and repeat guarantee before progress may be retired. At capacity, new evidence is rejected without deleting valid credit or changing completed work. Ten thousand repeats compact to the same stored generation; 64 distinct supported jobs produce one contextual aggregate, 64 independent run samples counted, and at most four retained sample witnesses.

Aggregates are keyed by stable ActorRef, trusted origin, capability, exact method and a declared condition descriptor. The condition descriptor records dimension, source-volume band (SMALL <=16 cells, MEDIUM <=128, LARGE otherwise) and source-to-destination Manhattan-distance band (NEAR <=8, MEDIUM <=32, FAR otherwise). These are conditions from the job's trusted request, not omniscient weather/season/resource observations. Changing geometry bands or method retains a separate bounded comparison row. The exact source refs stay available through Jobs. Display names, raw conversation and private coordinates are absent from aggregate keys.

Quantity, completed jobs and distinct contributing runs are separate counters. Repeated partial updates to one run do not create extra independent samples. Compact supporting samples retain job revision, attempt/run, outcome class, bounded reason, contribution delta and up to three representative physical receipt refs. Samples explain a measured contribution or observed termination; their bounded window is not the full history and is not summed to recreate total credit.

Termination counters distinguish success, environment, cancellation, authority, execution failure, infrastructure and interruption. Resource/facility/target/lifecycle/knowledge blockages are environmental context. Model/storage/budget/compatibility failures are infrastructure context. Even an observed execution failure is a recorded outcome class, not an automatic diagnosis of actor incompetence. Qualification uses demonstrated positive measurements only. Zero-receipt shortages, denied authority and cancellation never become completed-work credit or automatic negative competence.

## Recognition and privacy

Production starts with one rule: `crop-contributor`, version 1, label `Measured crop contributor`, requiring one completed job, two attributable units and one independent contributing run in one supported method/context. Up to four host-owned definitions, including retired definitions, may exist. There is no large built-in title catalog, universal level, magical bonus or automatic dispatch/teaching/physical authority.

Rules explicitly declare versions and measured thresholds. A same-version meaning change or downgrade rejects. Withdrawal retains an inactive definition/tombstone, so deletion cannot bypass version checks. Updates recompute existing evidence, reactivate only qualifying metadata or retire it, and retain the previous version/label as bounded provenance. Different contexts are not silently pooled to satisfy a threshold. Recognition retains its supporting aggregate/counts and is keyed to stable citizen/origin/rule; rename preserves all records. Replaying an event or the same rule cannot create another title.

Private query access requires the current identity owner's exact controlling principal/scope, evaluated from its latest snapshot. Knowing a citizen/job/receipt ID or holding a title grants no access. The separate summary seam supports an explicit same-world aggregate grant and checks it on each query; it omits private samples and source refs. Revocation immediately hides the summary, while identity/control loss also hides old private history. The production summary policy defaults to no cross-principal grant. No title, evidence count, sample or scope summary is passed into the gateway permission policy.

## Publication, recovery and retention

The sole writer owns `data/cognitivecraft/accomplishments/v1/state.jsonl`, exact `state.prev.jsonl` rollback, `state.next.jsonl` staging and `writer.lock`. The checksummed JSON-lines header records world, schema, revision, count and payload digest. Fixed record kinds are aggregate/progress/rule/recognition. StrictJson byte/depth/token limits, exact record fields, integer types, known enums, canonical UUIDs and canonical record constructors precede decoding. Optional fields are omitted; JSON null and coercible string counters are rejected.

Publication reserves current/previous/stage headroom through the existing retention category and total quotas. Recovery and source/method roots publish successfully before a new generation may be used. The owner checks exact current/rollback ownership, forces a new stage, preserves/forces the exact current backup, atomically replaces current and forces the directory. Five named fault/cancellation checkpoints cover before-stage, after-stage, after-backup, before-replace and after-replace. An uncertain result fences further writes and reports whether replacement may have succeeded. It never replays physical work.

Reopen reads only a valid supported current generation. A supported interrupted stage is preserved and never auto-replayed. Corrupt, future, foreign, invalid UTF-8 or uncertain current/backup/stage data is preserved and inactive/read-only; no backup silently authorizes overwriting an opaque current. Unknown files are protected. External replacement and symlink paths are rejected, and a file lock excludes another writer. Schema 1 is the only declared reader; no undocumented historical import or invented past accomplishment is synthesized. Rule-version migration is explicit and keeps source evidence intact.

The retention adapter reports actual current/backup/stage/lock bytes and logical rows, and never deletes authoritative progress or recovery files. It reserves a finite owner ceiling. Supporting job/method roots are published at reopen and before ingestion. Existing source Jobs and pinned methods remain protected for the exact declared ingestion guarantee. A corrupt source makes the root provider incomplete, conservatively blocking collection rather than inventing missing references. No separate event scheduler, job journal, citizen writer or collection authority is introduced.

| Bound | Hard maximum |
|---|---:|
| Immutable event refs per explicit batch | 8 |
| Logical validation/source/qualification inspection work | 8,192; existing completion-predicate receipt traversal is charged |
| Source Jobs / attempts per job / executions per attempt / receipts per job | Existing 64 / 8 / 8 / 64 |
| Aggregates / method-condition rows per citizen-origin-capability | 128 / 8 |
| Retained supporting samples per aggregate / refs per sample | 4 / 3 |
| Persisted exact job progress rows | 64 |
| Rule definitions including retirement tombstones / recognition rows | 4 / 256 |
| Title label | 48 ASCII characters |
| Envelope bytes / JSON-lines data rows | 1,048,576 / 452; individual StrictJson limits also apply |
| Current, rollback and staged envelope ceiling / directory files | 3,145,728 bytes / 8 |
| Private/allowed query aggregate page / native pending queries | 16 / 8 |
| Production intake | One ref and one pending storage task; 20 idle turns before a transient retry pass |

Configured ingestion, aggregate, context, sample, job, byte and work allowances may be lowered to zero. Negative and one-over-hard values reject. Samples at zero reject new positive credit, preserving an explainable supporting source. Exact N and N+1 budgets fail atomically with no owner-state rewrite. Encoding, parsing, bounded progress/root traversal and fixed file inventory also have independent structural/byte ceilings; the logical inspection counter is not a hardware-time claim. Typed results distinguish applied, duplicate, stale, unverifiable, denied, limit, cancelled and unavailable.

## GP-21 and dependent handoff

The fake-result matrix runs actual JobLifecycleStore receipt/accounting transitions and trusted registry snapshots. `AccomplishmentGameTests` then binds GP-21 to the installed Minecraft/Fabric gateway, executor, Jobs, citizen registry, leases and ledger: two native villagers complete the supplied test-only known method for two and three wheat, with different source-volume conditions; actual shortage/cancellation outcomes stay contextual, and one citizen is renamed. Reversed refs, replay and a second saved-world JVM preserve credit and exact metadata. The driver checks private access, attribution 5, completion 2, titles 2, unchanged physical stock and zero inference. The admitted-method fixture is controlled test input, not learned-method or model-quality evidence.

The `Accomplishment evidence acceptance` workflow explicitly selects cold and warm drivers plus the saved-block witness, requires active logs/XML/JSON, runs all 85 matrix cases and complete regressions, and checks finite recovery bytes and the production JAR. JVM identity uses PID and JVM start time together, distinguishing separate launches even when the host reuses PID namespaces. No dedicated player command, greeting, GUI, new physical primitive, authority grant, universal level or RPG subsystem is introduced. The fixture is separate from production packaging.

IMP-022 may request current scoped contextual evidence and rule-qualified metadata when evaluating a teacher, but its own owner must establish learning/supervision/grants and independent physical permission. IMP-026 may compare conditions, sample counts and supporting outcomes; one success/title does not prove sustained readiness or settlement authority. Dispatch and learning policies are unchanged. This is the versioned current handoff for those planned dependents; the implementation repository has no docs/backlog tree to rewrite.
