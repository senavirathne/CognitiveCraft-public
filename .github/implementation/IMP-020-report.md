# IMP-020 completion report

Status: **complete and merged** as [PR #19](https://github.com/senavirathne/CognitiveCraft-public/pull/19) after all 20 final-source CI workflows passed. Actual merge: `8a93ca1c0fc3baa1802f894c45a9e8f4cdf7e57f` at `2026-10-11T16:59:30Z`. IMP-021 remains separately published as PR #20, open and unmerged.

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

## Initial local commands and historical evidence

Java 25 and the pinned cached server SDK compile all production and unit sources via the existing headless helper. All 65 matrix cases passed locally, including the three actual installed-registry snapshot tests. Final production and every existing native GameTest source compiled, and both runtime/native-test jars packaged successfully from the final production sources. The initial local release is 1,497,192 bytes, SHA-256 ee2cc025025ee04b0d576fde80f8cf26835138828811dc40f18e935df0ac6925; the separate native-test jar is 426,887 bytes. Release inspection confirmed the three blueprint runtime owners are present and test fixtures, GameTests and model weights are absent.

The initial full local regression scan executed 1,154 cases (core 1,059, providers 66, fabric 29): 1,143 passing and 11 existing SaveCompatibilityTest failures. These are the previously established sandbox file-store free-space probe limitation (SPACE_UNAVAILABLE / mount point not found), not new blueprint failures. Production probes and tests remain unchanged. The requested bash ./gradlew --no-daemon :core:test cannot download the pinned Gradle distribution here: Network is unreachable. Required pinned-wrapper execution remains a remote exact-head CI gate; local helper results do not replace it.

Standalone GP-20 cold and warm driver runs both passed in actual separate JVMs against the same temporary store, with identical reference d996b766175e99cff272e93e563ebc5e84eef3460b2fbc02715f06380408575f, one retained variant and one prior inactive compatibility assessment. Both observed 331 cells, 224 planks/99 slabs/2 doors/4 torches, zero gateway and inference calls. Input/canonical body bytes: 11,303 / 11,457. Metered work after registry/default descriptor checks: 3,205 of 8,192. Metadata after advisory evidence: 1,364 bytes. This is data/recovery inspection evidence only; no Minecraft building has occurred.

## Recovery and limits

Publication uses locked ownership, create-new body, forced stage/backup, checksum and current-ownership checks, atomic current replacement and directory force. Uncertain publication reports whether replacement may have succeeded and deactivates the writer. Reopen preserves supported original or complete new metadata; corrupt/future/foreign recovery files and required bodies remain preserved/inactive. No physical effects or executable frames exist to replay.

Quotas include immutable bodies plus metadata staging/backup/recovery headroom. No protected variant is evicted. Bounds and exact/overflow measurements are recorded in the contract and active test assertions. Retention collection uses fresh manager-approved root/epoch fences; private metadata requires current access policy and scopes. No production workaround, guard weakening, model fixture masquerading as live inference or physical-completion claim is used.

## Changed files and delivery

Production: Blueprint.java, BlueprintValidator.java, BlueprintStore.java, SaveCompatibility.java; FabricBlueprintRegistry.java and KernelSession.java. Tests: BlueprintFixture.java, BlueprintValidatorTest.java, BlueprintStoreTest.java, BlueprintBoundaryProbe.java; FabricBlueprintRegistryTest.java. Build/test binding: core/build.gradle and .github/workflows/blueprint-boundary.yml. Contracts/report: .github/implementation/IMP-020-contracts.md and this file.

The repository has no docs/backlog tree. This contract establishes the current IMP-020/GP-20 owner/interface handoff against the read-only normative documentation. No accepted current dependent consumes a changed signature; existing prerequisite interfaces remain source compatible. The final source, passed CI, actual merge and main tree are recorded below. The report follow-up changes documentation only.

## Dated defect/fix — 2026-10-11: fence a closed structural writer

Runtime symptom: a stale BlueprintStore reference could publish after close released its file lock and a replacement writer acquired it. Minimal reproducer: open/store/close owner A, open owner B on the same world, then call A.store with different geometry. The concrete path was BlueprintStore.close -> FileLock.release, followed by old store -> commit -> independent file writes. The closed instance remained readOnly=false, violating the sole-writer invariant.

The regression closedOwnerCannotPublishAfterReplacementWriterAcquiresLock fails on the original source with expected UNAVAILABLE but actual STORED. The production correction sets readOnly before removing roots or releasing the writer lock, fencing both publication and collection while preserving recovery data. The test requires exact unchanged metadata after the stale call and a successful publication by the replacement owner. No patch, mixin, parser/custody weakening or temporary workaround is used. Final verification runs the corrected production source; matrix count is now 66.

The initial PR source 471d36181f620f8c8d66c4a0c05566fe4f11cd53 was rebased onto main 96d0de2e6d9a1eabb81cfdfe3b1bff4036fa5b50, preserving merged real-client production fixes. Its Blueprint acceptance run 38150490024 passed all 65 then-current matrix cases, all 1,155 regressions (core 1,059/providers 66/Fabric 30), both GP-20 JVMs and release inspection. That runner has no sandbox SPACE_UNAVAILABLE failures. This evidence establishes the initial source only; the new close-fence source must pass fresh exact-head CI before merge.

## Dated harness repair — 2026-10-11: reacquire changed production binaries

The close-fence source c4480d0be36cb9e1d977a7642d80e78ebae7b08d passed all 66 blueprint cases and its other 19 workflows, with one targeted retry each for the existing identity, prerequisite and goal fixtures. Real gameplay run 38151157720 stopped before acquisition: the unconditional committed-arena replay tried to restore an F1 certificate from the old production binary and correctly rejected the changed JAR contents. This affects every production change, including IMP-020 and IMP-021.

The workflow now selects the committed arena only when its semantic production identity matches. A changed binary proceeds through the already mandatory smoke, actual generation, trial, admission, offline reuse, newly certified stopped-world fixture, three connected-player profiles and exact-head qualification. Archive/world integrity checks and the qualification requirement remain unchanged; mismatched certificates are never used as gameplay evidence. The regression requires an unchanged JAR to select the fixture, a changed JAR to require fresh acquisition, restore to continue rejecting that changed JAR, and malformed production identities to fail selection. The final source must pass fresh exact-head CI; the failed run is retained as evidence.

## Dated harness repair — 2026-10-11: isolate native unit-test log output

Source `987ce6c26f88d34ae96a7b2d77d59b434bcf2ae4` passed the blueprint boundary and the other 19 owner workflows. Gameplay run `38152488965` passed genuine acquisition, offline reuse, all three connected-player profiles and every one of its 13 families. Final qualification correctly failed its clean-source guard: both smoke/acquisition metadata envelopes reported dirty=true after Fabric native unit tests ran in the checkout. All five envelopes have identical source/tree/JAR hashes, every screenshot envelope passes, and exact-head owner evidence passes. Failed qualification artifact `11708450673`, digest `sha256:798eef1fc8df8e1daa2492d9747987628144c8602c3af931d6d091c3b58a602a`, preserves the discrepancy.

The local three-case installed-registry reproducer passes but creates `logs/latest.log` and changes `git status` to an untracked log directory. Running the same cases in the Fabric module reproduces `fabric/logs/latest.log`. The correction gives only Fabric Test tasks an isolated `fabric/build/test-runtime` working directory, created before execution. All 30 Fabric unit cases pass there with the log contained under build and no untracked checkout files; all 22 harness regressions also pass. Core/providers keep their existing relative evidence paths. Runtime logs are retained in the acquisition artifact; source checks, binary identities, screenshot integrity and qualification requirements remain unchanged. No output is deleted or treated as clean by overriding metadata. Fresh final-source CI must execute the native unit cases and the complete gameplay qualification before merge.

## Final source, executed CI and actual merge — 2026-10-11

Final implementation head `1796a452a07f540f4109a1ae02d4edcde566d6ac`, tree `bff29a4b724d9aeaed51acbcbb2c48fe32659209`, targets main `96d0de2e6d9a1eabb81cfdfe3b1bff4036fa5b50`. GitHub merged that exact head as `8a93ca1c0fc3baa1802f894c45a9e8f4cdf7e57f`; the merged main tree is exactly `bff29a4b724d9aeaed51acbcbb2c48fe32659209`. The IMP-020 PR has no unresolved review threads or submitted change requests. No independent reviewer approval is claimed. Only IMP-020 is merged; PR #20 remains unmerged.

[Blueprint acceptance run 38155526161](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38155526161), job `114517600945`, passed all **66** matrix cases and all **1,156** module cases (core 1,060/providers 66/Fabric 30), with no failures, errors or skips. Both GP-20 JVM inspections pass with the same immutable ref, 331 cells, the exact 224 planks/99 slabs/2 doors/4 torches bill, zero gateway/model calls, 3,205 validation work, 11,303 input bytes, 11,457 canonical bytes, 1,364 metadata bytes, one variant and one inactive prior compatibility assessment. Production JAR SHA-256: `6b3bf5af741f2159f44e83ac0f1fea8c88e4771a691c37cde5158a688fa2cc31`. Evidence artifact `imp-020-blueprint-boundary-acceptance`, ID `11707734067`, 110,745 archive bytes, digest `sha256:599c29fe5d6f50a0bea61581bec7725fb51d42e1f2074da31cf67ed3e2103129`, records that exact reviewed source.

Every final-source workflow completed successfully before the merge:

| Workflow | Passed run |
|---|---|
| AI work broker acceptance | [38155526269](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38155526269) |
| Blueprint artifact boundary acceptance | [38155526161](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38155526161) |
| Bounded goal planning acceptance | [38155526104](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38155526104) |
| Capability retrieval acceptance | [38155526225](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38155526225) |
| Citizen identity acceptance | [38155526114](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38155526114) |
| CognitiveCraft verification | [38155526049](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38155526049) |
| Default nearest physical acceptance | [38155526058](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38155526058) |
| Deterministic prerequisite acceptance | [38155526092](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38155526092) |
| Job lifecycle acceptance | [38155526121](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38155526121) |
| Local generation smoke | [38155526187](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38155526187) |
| Needle language acceptance | [38155526255](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38155526255) |
| Observed world knowledge acceptance | [38155526112](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38155526112) |
| Primitive diagnostics acceptance | [38155526189](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38155526189) |
| Real Minecraft client E2E smoke | [38155526080](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38155526080) |
| Real Minecraft gameplay acceptance | [38155526200](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38155526200) |
| Research local-model physical trial | [38155526113](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38155526113) |
| Resource lease acceptance | [38155526122](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38155526122) |
| Save and artifact compatibility acceptance | [38155526211](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38155526211) |
| Worker dispatcher acceptance | [38155526135](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38155526135) |
| World retention acceptance | [38155526239](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38155526239) |

The changed-binary gameplay run executes fresh genuine acquisition and certifies its own production binary; the incompatible old arena is not counted as evidence. Historical failed attempts remain identified above. The final report publication adds no production or test changes and does not merge IMP-021.
