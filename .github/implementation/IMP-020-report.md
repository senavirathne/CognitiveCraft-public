# IMP-020 completion report

Status: published as PR #19 under explicit public-destination approval; final exact-head CI and authorized IMP-020-only merge remain pending. This report will be finalized after those gates with actual source/merge/tree IDs. IMP-021 is published separately as PR #20 and has no merge authorization.

Baseline main: 01c623f4915e8f760659eac1a1426e1bbca6e3a5, tree 198f6ff191f843f727c7f2298d4a53ab5c580b63. Normative documentation: b1d5bf71365d1b1762699c006465caa1db8e5be5, IMP-020, shared contract, GP-20 and first-build-fixture. User explicitly requested implementation through IMP-020 and merge; this authorizes publication despite the historical shared contract's default local-only delivery instruction.

Architecture 0.5, Contracts/IR 1 and all build/runtime pins remain unchanged. Blueprint body/reference, blueprint metadata and placement policy are version 1. IMP-015 owner-bound schema registrations and IMP-016 manager/root APIs are consumed; existing owners' identities, rights, lifecycles, schemas and physical primitives retain their meanings.

## Delivered behavior

Immutable structural data validates from untrusted strict JSON against bounded snapshots, canonicalizes and deduplicates, computes supported material quantities, supports directional rotations and immutable site views, and publishes/reopens through its own world structural owner. Initial requirements/bill remain distinct from scoped mutable provenance and compatibility evidence. Current incompatibility never substitutes or rewrites a body. Retention protects current/rollback/consumer references and reclaims only validated unreferenced orphan bodies through the existing manager.

See [the exported contract](IMP-020-contracts.md) for field-level supported placement semantics, API/owner boundaries, recovery and finite limits. Construction execution/site selection/catalogs/programs/permissions/recipes/new primitives are absent. Validation establishes no site permission or building completion.

## Feature-to-test mapping

| IMP-020 matrix row | Executed binding |
|---|---|
| Valid structure/material bill | BlueprintValidatorTest.twoRoomFixtureHasIndependentlyCalculatedBillAndBoundedWork; FabricBlueprintRegistryTest.actualNativeRegistrySnapshotValidatesBillAndAllRotatedDoorStatesWithoutWorldHandles |
| Canonical identity | Equivalent orders/defaults; changed geometry/state/constraints; repeated cells/unused palette; BlueprintStoreTest.storeReloadResolveInspectDeduplicatesGeometryAndSeparatesMutableMetadata |
| Malformed/oversized | Negative/overflow dimensions and coordinates; unexpected executable/authority keys; unknown state/block/placement; every exact/lowered/one-over hard bound |
| Door/accounting | Missing/mismatched/support pairs, declared ground-plane support, lower/upper accounting, double slab item semantics |
| Rotation/binding | Four directional rotations, hinge handedness, horizontal axis changes, dimensions/bill/ref stability, origin overflow |
| Compatibility | Changed registry meaning/target/required state/block, retained first validation and bounded advisory history, no automatic substitution |
| Persistence/quotas | Seven fault checkpoints and seven cancellation checkpoints; corrupt/future/foreign formats and stage/body/checksum/UTF-8 handling; exact bytes/variants and maximum provenance/evidence |
| No effects/AI | Unavailable generation/gateway spies, no execution domains, independent cold/warm GP-20 JVM driver |
| Retention/privacy | Current/rollback/root protection, new-pin stale collection, orphan-only reclamation, metered pages, category/world capacity, denied access/authorized sharing/revocation |

Final matrix contains 30 BlueprintValidatorTest, 33 BlueprintStoreTest and 3 FabricBlueprintRegistryTest cases (66 total). Remote workflow requires all to execute and pass, then every core/providers/fabric regression and production/native-test packaging. The additional store case covers the shutdown defect recorded below.

## Local commands and evidence

Java 25 and the pinned cached server SDK compile all production and unit sources via the existing headless helper. All 65 matrix cases passed locally, including the three actual installed-registry snapshot tests. Final production and every existing native GameTest source compiled, and both runtime/native-test jars packaged successfully from the final production sources. The local release is 1,497,192 bytes, SHA-256 ee2cc025025ee04b0d576fde80f8cf26835138828811dc40f18e935df0ac6925; the separate native-test jar is 426,887 bytes. Release inspection confirmed the three blueprint runtime owners are present and test fixtures, GameTests and model weights are absent.

The final full local regression scan executed 1,154 cases (core 1,059, providers 66, fabric 29): 1,143 passing and 11 existing SaveCompatibilityTest failures. These are the previously established sandbox file-store free-space probe limitation (SPACE_UNAVAILABLE / mount point not found), not new blueprint failures. Production probes and tests remain unchanged. The requested bash ./gradlew --no-daemon :core:test cannot download the pinned Gradle distribution here: Network is unreachable. Required pinned-wrapper execution remains a remote exact-head CI gate; local helper results do not replace it.

Standalone GP-20 cold and warm driver runs both passed in actual separate JVMs against the same temporary store, with identical reference d996b766175e99cff272e93e563ebc5e84eef3460b2fbc02715f06380408575f, one retained variant and one prior inactive compatibility assessment. Both observed 331 cells, 224 planks/99 slabs/2 doors/4 torches, zero gateway and inference calls. Input/canonical body bytes: 11,303 / 11,457. Metered work after registry/default descriptor checks: 3,205 of 8,192. Metadata after advisory evidence: 1,364 bytes. This is data/recovery inspection evidence only; no Minecraft building has occurred.

## Recovery and limits

Publication uses locked ownership, create-new body, forced stage/backup, checksum and current-ownership checks, atomic current replacement and directory force. Uncertain publication reports whether replacement may have succeeded and deactivates the writer. Reopen preserves supported original or complete new metadata; corrupt/future/foreign recovery files and required bodies remain preserved/inactive. No physical effects or executable frames exist to replay.

Quotas include immutable bodies plus metadata staging/backup/recovery headroom. No protected variant is evicted. Bounds and exact/overflow measurements are recorded in the contract and active test assertions. Retention collection uses fresh manager-approved root/epoch fences; private metadata requires current access policy and scopes. No production workaround, guard weakening, model fixture masquerading as live inference or physical-completion claim is used.

## Changed files and delivery

Production: Blueprint.java, BlueprintValidator.java, BlueprintStore.java, SaveCompatibility.java; FabricBlueprintRegistry.java and KernelSession.java. Tests: BlueprintFixture.java, BlueprintValidatorTest.java, BlueprintStoreTest.java, BlueprintBoundaryProbe.java; FabricBlueprintRegistryTest.java. Build/test binding: core/build.gradle and .github/workflows/blueprint-boundary.yml. Contracts/report: .github/implementation/IMP-020-contracts.md and this file.

The repository has no docs/backlog tree. This contract establishes the current IMP-020/GP-20 owner/interface handoff against the read-only normative documentation. No accepted current dependent consumes a changed signature; existing prerequisite interfaces remain source compatible. Final report publication will add exact code-head CI, reviewed merge and final-main proof after implementation checks finish.

## Dated defect/fix — 2026-10-11: fence a closed structural writer

Runtime symptom: a stale BlueprintStore reference could publish after close released its file lock and a replacement writer acquired it. Minimal reproducer: open/store/close owner A, open owner B on the same world, then call A.store with different geometry. The concrete path was BlueprintStore.close -> FileLock.release, followed by old store -> commit -> independent file writes. The closed instance remained readOnly=false, violating the sole-writer invariant.

The regression closedOwnerCannotPublishAfterReplacementWriterAcquiresLock fails on the original source with expected UNAVAILABLE but actual STORED. The production correction sets readOnly before removing roots or releasing the writer lock, fencing both publication and collection while preserving recovery data. The test requires exact unchanged metadata after the stale call and a successful publication by the replacement owner. No patch, mixin, parser/custody weakening or temporary workaround is used. Final verification runs the corrected production source; matrix count is now 66.

The initial PR source 471d36181f620f8c8d66c4a0c05566fe4f11cd53 was rebased onto main 96d0de2e6d9a1eabb81cfdfe3b1bff4036fa5b50, preserving merged real-client production fixes. Its Blueprint acceptance run 38150490024 passed all 65 then-current matrix cases, all 1,155 regressions (core 1,059/providers 66/Fabric 30), both GP-20 JVMs and release inspection. That runner has no sandbox SPACE_UNAVAILABLE failures. This evidence establishes the initial source only; the new close-fence source must pass fresh exact-head CI before merge.
