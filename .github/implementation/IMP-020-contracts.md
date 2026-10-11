# IMP-020 blueprint artifact boundary contracts

The serialized structural owner fences publication and collection before releasing its writer lock on close. A stale reference cannot write after a replacement owner acquires that lock; shutdown grants no second writer.

Specification: [IMP-020 and the shared contract](https://github.com/senavirathne/CognitiveCraft-Documentation/blob/b1d5bf71365d1b1762699c006465caa1db8e5be5/docs/source/docs/implementation-prompts.md.md#imp-020), GP-20 and first-build-fixture at that same documentation revision. Starting implementation main: 01c623f4915e8f760659eac1a1426e1bbca6e3a5; tree 198f6ff191f843f727c7f2298d4a53ab5c580b63. IMP-015 compatibility and IMP-016 retention are present and consumed through their current owner interfaces.

Architecture stays **0.5**, Contracts/IR **1**, placement policy **1**. Blueprint body/reference and blueprint metadata add independent **schema 1** readers to SaveCompatibility; no migration is invented. Java 25, Gradle 9.7.0, JUnit 5.11.4, Gson 2.10.1, Minecraft 26.3, Loader 0.19.5, API 0.161.0+26.3 and Loom 1.18.1 remain pinned.

## Structural interfaces and semantics

| Interface | Responsibility |
|---|---|
| Blueprint.Body / Ref | Immutable dimensions, structural constraints, registry states and relative cells; content-addressed schema-1 reference |
| BlueprintValidator.validate | Strict bounded untrusted JSON validation against an immutable trusted Registry snapshot; typed result, deterministic material bill, used requirements and work counters |
| BlueprintValidator.rotate / bind | Bounded quarter-turn structural view retaining its source reference; no stored-body mutation |
| BlueprintStore.store / inspect | Serialized immutable publication; caller-scoped provenance, material requirements and current compatibility |
| BlueprintStore.recordCompatibility | Explicit bounded advisory evidence publication; preserves the first validation's target, requirements and bill |
| BlueprintStore.retentionOwner / Blueprint.retentionKey | Existing retention-manager accounting, protected current/rollback bodies, consumer pins and conservative orphan collection |
| FabricBlueprintRegistry.capture | Actual installed supported block/item/state values captured on the game thread; no native handles in core |
| KernelSession.storeBlueprint / inspectBlueprint | Server-thread intake with at most eight pending structural operations on the existing world-storage worker |

Body JSON has exactly schema, dimensions, constraints, palette and cells. Dimensions contain positive x/y/z. Constraints declare supportedGround, nonHazardous and perimeter; these are future placement requirements, never proof of a safe or authorized site. Palette entries contain only a registry block identifier and named string properties. Cells contain x/y/z and a palette index. Unknown fields reject at every level: scripts, programs, permissions, NBT, entities, jobs and model instructions are not structural schema fields. No embedded execution, inference, primitive registration, resources or rights are accepted.

Registry definitions are trusted host values, independent of supplied candidates. Each declares a block/item ID, supported SINGLE/SLAB/DOOR item-placement semantics, known property values/defaults and a meaning descriptor. The production snapshot exposes oak planks, oak slabs, oak doors and standing torches only; installed blocks outside that explicit support set return UNSUPPORTED_BLOCK. It contains no design/template catalog. Its descriptor covers the game target, native block/item classes and placement-policy revision; fingerprints also cover defaults, property values, exact item ID and placement kind. Core also permits bounded explicitly supplied host definitions with these same supported semantics. Candidates cannot supply their own registry definitions.

Defaults normalize to full states. Equivalent palette/property/cell orders, repeated equivalent cells and unused supported palette entries canonicalize to one identity. Conflicting duplicate coordinates reject. Canonical used states sort by their strict state encoding; unique cells sort by y/z/x. A SHA-256 over canonical semantic body JSON defines Ref. Provenance, labels, validation history, current compatibility, target-site bindings and rotation requests are absent from the body and cannot change its ref. Changed geometry, constraints or states produce a different ref. Each stored body is immutable and checked against its ref on reads.

Single placements consume one declared item. Top/bottom slabs consume one slab; a double slab consumes two. Waterlogged placement is unsupported in policy 1. A door's lower and upper cells must occupy the same x/z at adjacent y, with the same block, facing, hinge, open and powered values. One lower cell consumes one door item; its upper consumes none. A door needs a declared support plane at y=0 or a supplied lower support cell. This is structural consistency, not a live support, reachability or permission check. Duplicate cells cannot double-charge. Unspecified/unsupported placement semantics reject rather than inventing an item bill. Recipe expansion, acquisition, tools, fuel, staging, preexisting world blocks and construction order belong to future consumers.

Clockwise quarter turns map north -> east -> south -> west, transform local bounds/coordinates and swap horizontal x/z axes. Rotation preserves hinge handedness; it does not mirror doors. Vertical direction/axis and non-directional states remain unchanged. Four quarter turns restore the canonical semantics. SiteBinding is an immutable source ref, dimension identifier, integer origin and rotation. Binding rejects world-coordinate addition overflow and does not grant site permission.

## Compatibility, publication and recovery

Immutable bodies live at data/cognitivecraft/blueprints/v1/<sha256>.body.json. Separate manifest.jsonl metadata stores schema/world/revision, a body checksum, refs, initial material bills and used registry requirements, scoped provenance and a bounded recent compatibility window. manifest.prev.jsonl is the supported rollback envelope; manifest.next.jsonl is a staged candidate; writer.lock enforces one writer. There is no second SkillArtifact repository, job journal, execution frame store, scheduler or index. Lookup uses the bounded validated manifest and is rebuildable from that envelope.

Store revalidates all candidate input, even if its caller already validated it. Same-body metadata changes reuse the original file/ref. A candidate under changed target/placement meaning cannot overwrite an existing ref's recorded requirements or bill. inspect reads the immutable body, validates against the supplied current snapshot and compares the recorded used requirements, target and bill. Known changed/missing states/blocks or meaning become inactive INCOMPATIBLE; there is no substitution, automatic repair, admission or quarantine. Initial validation requirements remain retained while explicit advisory evidence keeps the last four assessments. A compatible inspection does not claim construction success.

Publication reserves new-body and metadata-stage/backup headroom before writing and checks existing category/world capacity through WorldRetentionManager. The root source blueprint-publication successfully publishes a pending ref before storage use. The sole writer checks current-file ownership, forces a create-new immutable body, stages and verifies bounded metadata, preserves its exact original as backup, atomically replaces current and forces the directory. Every named checkpoint checks cooperative cancellation. A result after replacement uncertainty reports publicationMayHaveSucceeded and the writer becomes read-only.

Reopen admits only a fully supported, checksummed metadata generation with readable immutable current bodies. An interrupted supported stage never becomes current automatically. Corrupt/future current, rollback, stage or required body stays preserved and inactive/read-only; no fallback silently overwrites the original. A supported current generation survives failures before replacement; after replacement a valid complete new generation is recovered. No uncertain Minecraft action is replayed because this owner stores and performs no actions. Writer failures preserve uncertain stage/body files for diagnosis. Fresh readers and managers can collect only a verified orphan body, not metadata/recovery files or opaque data.

Current and previous-manifest bodies are protected. The existing retention manager coordinates consumer references using Key("blueprints", sha256), checks the reference-generation fence and owner epochs, and authorizes finite deletion. A new root or storage publication invalidates an old scan. Before collecting an orphan, this owner rechecks manifest ownership, rollback/current membership, file attributes, body hash/schema and cancellation. It never silently removes protected knowledge to satisfy a quota. Unknown/corrupt orphan data invalidates collection. Stages/backups/lock and any unexpected files remain protected and included in usage. No new dependencies between blueprint bodies are encoded; future consumers pin the immutable ref through RetentionRoots before use.

Private metadata access requires a same-world trusted viewer and the configured owner/viewer policy; production defaults to equality. inspect returns only authorized provenance rows. An independently supplied identical valid structure may add its own provenance while sharing the one body; this grants no access to another origin's label. Sharing and revocation are evaluated anew for each inspection, never inferred from ref possession. Unknown/denied queries return empty data.

## Finite bounds

| Bound | Hard maximum / accounting |
|---|---:|
| Input/canonical body bytes | 65,536 each; character cap checked before UTF-8 allocation |
| JSON depth / tokens / strings | Existing StrictJson: 32 / 4,096 / 1,024 |
| Axis dimensions / volume | 64 each / 262,144; no volume-sized allocation |
| Candidate palette / cells | 64 / 512; duplicates still count against candidate bounds |
| Property keys / values in one definition | 16 / 16; names and values at most 32 characters |
| Snapshot block definitions | 256; production captures four |
| Validation work | 8,192 metered registry/default/palette/property/cell inspections and canonical cell-sort comparisons; parsing separately has fixed byte/token limits |
| Structural perimeter requirement | 0..4 |
| Retained variants / provenance per ref / recent evidence per ref | 32 / 4 / 4 |
| Metadata envelope | 65,536 bytes; each row also uses StrictJson bounds |
| Owner physical quota / directory entries | 4,194,304 bytes / 100 files, including recovery/stage/lock |
| Native intake | 8 pending operations, existing serialized worker |
| Retention inventory | Incremental file cursor; all current-manifest/body content reads metered; at most one 65,536-byte file per read |

Validation and storage allowances may be lowered to zero; negative/one-over/overflow values reject. Exact and exceeded limits are tested. Canonicalization or metadata expansion can independently exhaust bytes. Typed outcomes distinguish INVALID_DATA, INCOMPATIBLE, UNSUPPORTED_BLOCK, QUOTA, CANCELLED, denied/unknown refs and unavailable storage. Acceptance/publication is STORED; compatible reuse is RESOLVED. Neither means site authorization or completed building.

## GP-20 binding and dependent handoff

BlueprintFixture lives only in core test sources. Its two_rooms_timber_v1 geometry follows the supplied documentation: dimensions 11x5x9, 331 occupied cells and the independently declared bill **224 oak planks, 99 oak slabs, 2 oak doors and 4 torches**. It is never bundled as production content. Three Fabric JUnit cases separately bootstrap the installed registry and verify actual supported IDs/states, directional door rotations/materials and rejection of an installed unsupported block. These are registry/data tests, not physical building.

GP-20 is bound to BlueprintBoundaryProbe and the Blueprint artifact boundary acceptance workflow. Execute from the repository root in distinct JVMs:

    COGNITIVECRAFT_BLUEPRINT_PHASE=cold bash ./gradlew --no-daemon :core:runBlueprintBoundaryProbe
    COGNITIVECRAFT_BLUEPRINT_PHASE=warm bash ./gradlew --no-daemon :core:runBlueprintBoundaryProbe

The cold run validates supplied/reordered/invalid/executable data, stores one variant, records inactive changed-meaning evidence and saves a witness. The warm run reopens the same world-scoped store/reference. Both inspect all rotations and immutable bindings, enforce quotas, check registered roots, and assert zero gateway/model calls and absent execution domains. The standalone driver uses a controlled registry snapshot; actual native snapshots are separately tested above. Construction, gathering, site selection, permission checks, production design generation, physical placement and building completion are outside this unit. No player command or design catalog is introduced. Later consumers use these interfaces and independent live-site authority checks; the accepted backlog lists no current dependent unit.
