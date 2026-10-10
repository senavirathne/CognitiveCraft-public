# IMP-015 save and artifact compatibility contract

Baseline: merged IMP-014.1, `b5599606a9c668370672722d3e681a785a7a124b`.
Specification: [current owner bindings at documentation b844997](https://github.com/senavirathne/CognitiveCraft-Documentation/blob/b84499758bf77c7c529a5971419062bdbe586408/docs/source/docs/implementation-prompts.md#imp-015), together with the preserved shared contract and original IMP-015 requirements.
Architecture remains **0.5**, Contracts and Skill IR remain **1**. Artifact assessment and save compatibility policy are **1**. Existing domain schemas, outcome definitions, rights, admission rules and persistence paths retain their meanings.

## Version and owner matrix

| Domain | Registered readable versions | Current owned encoding | Adapter / publication owner |
|---|---|---|---|
| Bootstrap | 1, 2 | 2 after identity handoff | `BootstrapJournal` legacy enrollment → identity references; coordinated with `CitizenIdentityStore` |
| Citizens | 1 | 1 | Existing immutable identity/control and single import receipt |
| Skill body | 1 | 1 | `RepositoryCodec`; no invented historical body migration |
| Skill manifest | 1 | 1 | `VersionedSkillRepository`; admission metadata remains owned here |
| Jobs | 1 | 1 | `JobJournal`; no lifecycle transformation |
| Leases | 1 | 1 | `ResourceLeaseJournal`; no authority transformation |
| Private primitive diagnostics | 1 | 1 | `PrimitiveDiagnostics` / `PrimitiveDiagnosticStore`; defaults and segments unchanged |
| Capability request | 1 | 1 | `RequestCodec`; consumed request meanings unchanged |

`SaveCompatibility.registered()` binds existing owner constants, rather than duplicating schema numbers. `Registry` accepts at most 16 unique readers and 16 unique source rules; each reader declares at most 16 supported positive versions. Only bootstrap 1 → 2 is currently registered as a migration. A future domain owner adds an explicit reader/rule **and an owner adapter with its own snapshots/publication hooks**. A registry declaration alone does not execute or invent a transformation. The concrete bootstrap adapter is `inspectBootstrap` / `migrateBootstrap`; no generic deserializer or rival writer is introduced.

## Artifact assessment

`ArtifactCompatibility.assess(ref, graph, runtime, limits, validateBody)` is pure, model-free and fail-closed. `Entry` is an immutable value supplied by the repository owner, not a new graph store. `VersionedSkillRepository.compatibilityProvider()` captures repository/runtime revisions; callers discard/rebuild it on revision drift. `assessCompatibility(ref)` uses current revisions and executable validation. Existing `find`, resolution, page and metadata consumers use the same policy. Existing `Contracts.Compatibility.runtimeFingerprint` continues to carry the game target for source compatibility; the additive assessment's `relevantFingerprint` carries the policy-1 SHA-256.

Assessment checks integrity/readable format, recorded admission, game target, exact canonical capability contract, used primitive ID/version/fingerprint and each exact pinned dependency. Executable consumption recompiles canonical IR with the existing `SkillCompiler` and requires the stored descriptor to match. Metadata checks consume already verified descriptors and omit repeated compilation. A missing or incompatible child reports that child's exact ref and root-to-child path; the parent summary preserves `DEPENDENCY_INCOMPATIBLE`. No latest version is substituted. Budget exhaustion, future schemas or unavailable graph/runtime lookups yield inactive `UNKNOWN`; known incompatible data yields `INCOMPATIBLE`.

The relevant fingerprint includes policy/Contracts/IR/game versions, canonical stored/current capability semantics, exact artifact identities and actual used primitive parameter names/types/bounds, result, effects and declared fingerprint, sorted deterministically. It never reads unused catalog entries. For a compatible assessment it covers the visited pinned dependency closure. Assessment stops at the first blocking cause; an inactive result's fingerprint covers only the requirements inspected before refusal. `diagnosticsComplete` means the encountered cause was retained within the diagnostic allowance, **not** that every possible blocking cause was enumerated. Causes and advisory revalidation identify the affected ref; private repository access stays with the existing owner/consumers, with no new public command or endpoint.

Compatibility does not rewrite admission, evidence, origin, quarantine records, artifact bodies or manifests. Revalidation exposes current static check, current fixture and authorized behavioral admission requirements; it never schedules repair/research or awards approval. `inspectTransformation(original, SkillCompiler.Success)` takes a trusted owner's already compiled transformation and returns a pure proposal. Changed canonical semantics yield a distinct immutable ref and explicit validation/admission requirements; identical canonical semantics retain identity. It does not write a body or admit the proposal. No generated code rewriting is implemented.

## Owner migration and privacy

Trusted startup/offline inspection reads consistent snapshots from the two existing owners. Principal-scoped inspection denies a foreign world, legacy owner or included citizen owner before returning revisions, counts or digests. Its denial plan is empty. Trusted handles remain internal host authority; migration is not exposed to players. Plans contain only bounded scalar accounting, source/target versions, revision leases, a private canonical-source digest and at most two unsupported domain/version pairs. Unknown future current files are reported directly even when a readable previous backup exists; the coordinator never overwrites that current file.

The adapter previews both owners' transformation and encoding validation before any publication. The existing identity import receipt pins the source revision/canonical digest/citizen binding. It preserves citizen UUID, entity UUID, controlling principal/scope and all run refs/counters. The journal retains the **exact original raw bytes** as `state.legacy-v1.json`, then publishes schema 2 identity references through its existing atomic stage/backup/replace hook. The canonical source digest is a lease on meaning; it is distinct from the retained original's raw-file SHA-256. Citizen import publication likewise uses the existing stage/backup/replace hooks. It remains the sole identity writer.

Coordinator lock order is journal, then identities. Existing synchronized writers share those same monitors and revision checks, so they cannot publish a mixed handoff. Fixed ordering has no opposite-order owner callback. `KernelSession` opens/migrates on the existing world-storage worker before composing identity/controller/execution owners; an unsafe or unsupported transition leaves the kernel inactive and releases opened stores. File I/O, probes, compilation for migration and directory-space checks never run on the Minecraft tick. The adapter is for quiescent startup/offline use, not concurrent migration of an active gameplay session. An explicit quiescence port rejects nonquiescent use.

A first-store publication followed by cancellation/failure is recoverable through the existing receipt: reopening validates the same identity/rights, retries journal handoff and does not import a second citizen. A failure after final publication can report uncertainty; the next valid reopen is a current-format no-op. Owners retain originals/backups and become read-only on uncertain I/O. No rollback replays Minecraft effects. Existing `BootstrapController` recovery marks uncertain `ACTIVE` history `INTERRUPTED`, retaining effect/model counters and artifact refs without executing old work.

## Bounds and outcomes

| Allowance | Hard accepted maximum | Behavior on exhaustion |
|---|---:|---|
| Artifact visits / dependency depth | 128 / 8 | Inactive unknown with affected ref/path; no unbounded graph traversal |
| Retained causes / revalidation rows | 16 each | Omission flag; no unbounded diagnostic payload |
| Aggregate compiler input | 8,388,608 bytes; each body still ≤65,536 | Unknown with `BUDGET_EXHAUSTED` |
| Save records | 96 (current owners can supply at most 64 citizens + 16 runs) | No publication if planned work exceeds allowance |
| Save input bytes | 81,920 (65,536 citizens + 16,384 bootstrap) | Bounded format probing and owner decoding; no full-world scan |
| Additional migration file reservation | 262,144 | Reject insufficient allowance or usable disk space before publication |
| Cooperative checkpoints | 8; successful handoff uses 7 | Recoverable refusal/cancellation at phase boundary |
| Elapsed apply allowance | Default 5 seconds; hard 60 seconds | Recoverable `LIMIT_REACHED` at the next checkpoint |

Zero/lowered/exact allowances are accepted; negative, one-over and overflowing values are rejected. Space reservation conservatively covers identity stage/backup, original archive, bootstrap backup and bootstrap stage. Existing files/owner format caps are independently enforced. The full-input fixture retains all 80 current records, an exact 16,384-byte original and records actual staging/backup sizes. Diagnostic paths have at most ten refs; fingerprints retain only a bounded set of visited requirements.

Cancellation, elapsed time and quiescence are cooperative checks before inspection/transform/validation and around owner commits. They do not forcibly interrupt a hung filesystem operation; a storage call remains on the owned worker and startup remains fenced until completion/failure. Inspection is separately bounded by the owners' record/byte caps, while elapsed apply accounting includes monitor acquisition and repeated validation. No extra replacement worker is started to bypass a stuck owner. The returned report is bounded and ephemeral; no new migration journal/domain, retention deletion or world content deletion is introduced.

Statuses distinguish ready/current no-op/applied, exact unsupported version, corrupt source, recovery required, denied authority, nonquiescence, stale plan, cancellation, limit, unavailable space and unavailable storage. Migration completion is distinct from artifact compatibility and recorded admission. Models are unnecessary for assessment and migration; behavioral revalidation still requires its existing authorized admission path.

## GP-15 and dependent integration

`ArtifactCompatibilityTest` and `SaveCompatibilityTest` bind all ten original IMP-015 matrix rows with actual repository/primitive/owner stores. `CompatibilityUpgradeGameTests` binds GP-15 older-world upgrade and preserved originals through production startup, while `CompatibilityRestartGameTests` verifies a distinct JVM reopening the saved actor/chest and owners. The restart witness independently verifies the actual Minecraft save. Synthetic historical counters/ref are clearly fixture values, not evidence of behavioral admission or old physical work. Admission-preserving assessment uses actual stored, controlled-unit-admitted bodies; existing genuine-model and physical known-work workflows remain separate required regressions.

GP-15 retention/collection races and compaction remain IMP-016 work and are not claimed complete. No automatic pruning is added. Existing architecture, module direction, namespaces, pins, diagnostic privacy/defaults, inference broker ownership and gameplay admission/lease lifecycles remain unchanged. Later IMP-017/019–027 owners consume these explicit bounded seams without forcing their future schemas into this unit.
