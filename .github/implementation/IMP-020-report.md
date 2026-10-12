# IMP-020 completion report

Current status: **complete; PR #22 merged after all 20 required GitHub Actions passed**. IMP-021 remains open and unmerged.

## Current GitHub Actions acceptance — 2026-10-12

The focused repair is complete and merged in [PR #22](https://github.com/senavirathne/CognitiveCraft-public/pull/22). Reviewed source `8d28e1222b094e8c90533d1a18cb4baa736c41b9`, tree `fd095a8462ae3c7035cd4d85017be33efa9f0212`, passed all **20 required PR workflows** before merge. Actual merge: `c28493a87df877a9573a61cd26f99cbf947b516d` at `2026-10-12T07:36:18Z`. The cumulative source diff contains the eight IMP-020 files plus the separate E2E observer/driver harness correction; the only production edit is duplicate-coordinate rejection in the existing validator. Architecture 0.5, Contracts/IR/body/ref/metadata/placement policy 1 and every Java/Gradle/Minecraft/Fabric dependency pin remain unchanged.

Documentation prompts/index/contracts/backlog were published before implementation in `c1f5a27f3fb7a58761315db7dd2f21754edfb3db`. The latest user instruction authorizing all implementation tests in GitHub Actions and merge after CI was recorded first in `3e5b6507bcfa0e38e2537683bc4ed12cf23dac5d`. No local Gradle download is required. Historical SDK diagnostics below remain supplementary, including their failed space probe; fresh pinned-wrapper acceptance is supplied by Actions.

[Blueprint acceptance 38215786059](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38215786059), job `114700072427`, checked out the exact reviewed head and required clean tracked source. It executed **76/76 matrix cases** (37 validator, 36 store, 3 native registry) and **1,166/1,166 module cases** (core 1,070, providers 66, Fabric 30), with no failures or skips. Runtime/native-test packaging and both cold/warm GP-20 JVMs passed. The acceptance ZIP was downloaded and its SHA-256 independently matched artifact `11732207585` / `sha256:6e21929ad47e74fae570557daacf07aca928d50fc6690cabb1249dc17dfa098e`; its XML counts and source/tree proof were inspected. The production Gradle/Loom JAR SHA-256 is `ae729ffe449f857f35d76e4fd585b170d1ceed686580a1d1a267c3bac6fda5e3`.

Both GP-20 phases inspect seven cells and the independent bill of 3 oak planks, 1 slab, 1 door and 1 torch; reference `e88c32062ecbb9c0ca59ff8a7cab71e3745d2471ad86992fb5b3b27584d46263` is unchanged across reopen. Each records 705 input bytes, 793 canonical bytes, 116 work, 1,360 metadata bytes, one variant and one retained assessment, with zero gateway/model calls. This is structural inspection/persistence evidence. Fixtures remain test-only; no construction or site authority is introduced.

The initial head's gameplay failure is preserved below: its observer assumed one stock-coordinate height, while genuine acquisition recorded the other fixture feet height. The corrected observer alternates two bounded targets and the driver requires exact-target private/foreign witnesses with distinct viewers. The production query and privacy API remain unchanged. This harness correction was documented before implementation.

The compatibility and retention owner workflows passed on the same head. Native verification, actual model/acquisition, worker dispatch, connected-client smoke and real gameplay qualification also passed. The changed production binary used fresh genuine acquisition and a newly certified arena; qualification requires every one of its 13 families and exact-head owner evidence. No skipped/missing case, stale binary certificate or historical run is counted as current acceptance. The unchanged native identity fixture exhausted its 1,800 accelerated-tick deadline on its first attempt (7.872 seconds); targeted retry passed with source and assertions unchanged. Its failed job/artifact remain retained in the execution evidence.

| Required workflow | Exact-head Actions run | Attempt | Result |
|---|---|---|---|
| AI work broker acceptance | [38215786077](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38215786077) | 1 | success |
| Blueprint artifact boundary acceptance | [38215786059](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38215786059) | 1 | success |
| Bounded goal planning acceptance | [38215786098](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38215786098) | 1 | success |
| Capability retrieval acceptance | [38215786198](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38215786198) | 1 | success |
| Citizen identity acceptance | [38215786210](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38215786210) | 2 | success |
| CognitiveCraft verification | [38215786130](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38215786130) | 1 | success |
| Default nearest physical acceptance | [38215786061](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38215786061) | 1 | success |
| Deterministic prerequisite acceptance | [38215786125](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38215786125) | 1 | success |
| Job lifecycle acceptance | [38215786114](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38215786114) | 1 | success |
| Local generation smoke | [38215786254](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38215786254) | 1 | success |
| Needle language acceptance | [38215786148](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38215786148) | 1 | success |
| Observed world knowledge acceptance | [38215786186](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38215786186) | 1 | success |
| Primitive diagnostics acceptance | [38215786081](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38215786081) | 1 | success |
| Real Minecraft client E2E smoke | [38215786062](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38215786062) | 1 | success |
| Real Minecraft gameplay acceptance | [38215786175](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38215786175) | 1 | success |
| Research local-model physical trial | [38215786076](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38215786076) | 1 | success |
| Resource lease acceptance | [38215786165](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38215786165) | 1 | success |
| Save and artifact compatibility acceptance | [38215786132](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38215786132) | 1 | success |
| Worker dispatcher acceptance | [38215786173](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38215786173) | 1 | success |
| World retention acceptance | [38215786101](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38215786101) | 1 | success |

There are no unmet mandatory IMP-020 gates. The existing local IMP-020.1 audit is preserved. IMP-021 remains separately published as [PR #20](https://github.com/senavirathne/CognitiveCraft-public/pull/20), head `438c56a721b45afaf415d76b3837d80605729c0b`, open and unmerged; its code is absent from this merge. RB-001/RB-002 remain outside this assignment. This evidence follow-up changes only this Markdown report; the tested runtime/test/build/workflow files remain byte-for-byte identical.

## Earlier completion and supplementary SDK checkpoints

Current 2026-10-12 acceptance update: **implemented and published for GitHub Actions; required CI pending**. The latest user instruction authorizes publication and merge after required CI passes; all implementation tests run in Actions. Documentation-first publication and new evidence are appended below. The earlier complete/merged status describes the original reviewed source, not a fresh pass for the tightened duplicate policy.

Original status: **complete and merged** as [PR #19](https://github.com/senavirathne/CognitiveCraft-public/pull/19) after all 20 final-source CI workflows passed. Actual merge: `8a93ca1c0fc3baa1802f894c45a9e8f4cdf7e57f` at `2026-10-11T16:59:30Z`. IMP-021 remains separately published as PR #20, open and unmerged.

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

## Acceptance update and production correction — 2026-10-12

Implemented in the provided Work Mode cloud workspace after documentation-first commit `c1f5a27f3fb7a58761315db7dd2f21754edfb3db` was published and verified. Source baseline remains `13f9b941f57022ded8987e4e0aa7652d4231921d`; implementation changes are uncommitted/unpublished. Runtime/test/build/workflow source-manifest SHA-256: `341d0ddb70881be6f781871dd5b61c863da733cd49a069977615369400672ac9`. The manifest enumerates each tested file/hash at `build/imp020-current/source-snapshot.json`; report/contract outputs are excluded from that execution fingerprint. Architecture 0.5, Contracts/IR and body/ref/metadata/placement policy 1, exported APIs and all dependency/wrapper pins remain unchanged. The existing local IMP-020.1 audit is preserved.

The latest supplied brief requires every duplicate coordinate to reject. Minimal reproducer: submit two cells at one coordinate with the same block/default-normalized state. Call/thread path: the owner worker calls `BlueprintStore.store` → `BlueprintValidator.validate` → palette normalization → `canonical` → `positions.putIfAbsent`. Previously the equal-state branch collapsed the declaration and could return VALID/STORED. The correction rejects any prior position before material computation/publication; equivalent states return INVALID_DATA/DUPLICATE_CELL and different states retain CONFLICTING_CELL. Four new regression invocations fail on the baseline validator and pass with the correction. Baseline classes were isolated solely for negative comparison; final tests and packaging use corrected production classes without a patch/mixin/workaround. The earlier original completion deliberately accepted identical repeats and remains historical evidence.

Unused supported palette entries and reordered distinct cells still deduplicate. Existing canonical stored bodies contain unique cells and keep their schema-1 identities; no migration/rewrite or second owner is introduced. The maximum-cell test now uses 512 distinct coordinates instead of repeated cells while retaining exact byte/dimension/palette/work checks. Invalid negative, one-past and overflowing palette references also reject with no accepted body/material bill.

The active test-only compact fixture has dimensions 3×3×2 and seven cells: three plank supports, one bottom slab, matching north-facing/left-hinge door halves and a standing torch. Its independent bill is 3 planks/1 slab/1 door/1 torch. Both GP-20 JVMs validate/rotate/store/reopen the same ref `e88c32062ecbb9c0ca59ff8a7cab71e3745d2471ad86992fb5b3b27584d46263`. Each measures 705 input bytes, 793 canonical bytes, 116 work, 1,360 metadata bytes, one variant and one retained inactive assessment; gateway/model calls are zero. The optional timber fixture remains test-only; neither fixture is bundled in production. No construction, recipes, resources, site permission, AI, jobs, assignments or leases are introduced.

| Mandatory feature | Fresh supplementary cloud SDK evidence |
|---|---|
| Valid supplied structure | Compact fixture and independent bill; optional timber and actual native registry fixtures |
| Canonical identity | Equivalent cell/palette/default/property orders, unused supported palette, meaningful geometry/state/constraint changes and immutable metadata/site binding |
| Malformed/oversized | Duplicate/conflicting cells, negative/one-past/overflow palette refs, dimension/coordinate/payload/byte/token/work ceilings; exact 512 distinct-cell maximum |
| Registry/state semantics | Unknown block/state/unsupported placement tests and three actual installed-registry cases |
| Door accounting | Matching halves cost one item; absent/mismatched/support pairs, slab single/double semantics |
| Rotation | Compact and timber quarter turns, directions/hinges/bounds/materials, four-turn identity and site-coordinate overflow |
| Compatibility | Missing/changed required meaning/target/state preserves body and retained evidence; no substitution |
| Persistence/recovery | Reload plus seven fault and seven cancellation checkpoints, corrupt/unknown/foreign/UTF-8/stage/body handling and closed-writer fencing |
| Quotas/retention | Exact/zero/exceeded limits, current/rollback/consumer roots, stale collection refusal and metered inventory; new lookup-rebuild test preserves body/metadata/evidence hashes |
| Cancellation/publication | Bounded cooperative fences; rejected duplicates leave no ref/body/manifest or variant change; two simultaneous equivalent submissions retain one body and one metadata generation |
| Scope/privacy | Denial, sharing/revocation, same-world private provenance, no physical authority from refs |
| No effects/inference | Model/gateway spies and two-JVM driver report zero calls and absent execution domains |

Final matrix: **76 executed passing cases**, no skips/failures: validator 37, store 36, Fabric registry 3. Compilation covers every core/providers/Fabric production and unit source. Supplementary full suites execute **1,166 cases: 1,155 pass and 11 fail**, no skips (core 1,070 with 11 failures; providers 66 and Fabric 30 all pass). All failures are in existing `SaveCompatibilityTest`; running its 25 cases with the baseline validator reproduces the same 11 failures. The filesystem probe `Files.getFileStore(...).getUsableSpace()` reports `java.io.IOException: Mount point not found` in this cloud sandbox, producing SPACE_UNAVAILABLE before the affected migration/fault paths. Safeguards and expectations remain unchanged; these failures are not passes.

Supplementary production/native-test packaging and every native GameTest source compile pass. Production JAR: 1,483,328 bytes, SHA-256 `d5035b16198f769e0b1af40598ccb91adda1faaaa2d58a2e217ef0bb964a4631`; native-test JAR: 428,519 bytes, SHA-256 `1e0dd15bfc9f4c6fa270438a8322f505d41ad627d194c7986e2878cdeda1dace`. Production contains the three blueprint runtime owners and excludes blueprint fixtures/drivers, GameTest classes and model weights. These artifacts use the checksum-verified server SDK, not a successful Loom/wrapper build. The SDK supplies Minecraft's pinned Gson 2.14.0 runtime library; the declared core Gradle pin remains 2.10.1. No Gradle dependency declaration is changed.

Actual commands: `python3 build/imp020-current/verify.py` with the three blueprint selectors; `python3 build/imp020-current/run-evidence.py` phases `baseline`, `probe`, `package`, `full`, `baseline-space`; and **`bash gradlew :core:test`**. All run in the supplied cloud workspace with JDK 25.0.4.1; explicit library paths resolve that installed JDK's loader. The required wrapper command exits **1 before any test executes**: downloading pinned Gradle 9.7.0 fails with `java.net.SocketException: Network is unreachable`. Full pinned-wrapper/Loom/prerequisite checks therefore remain unverified. SDK diagnostics do not replace that gate. Logs/XML, exact commands, before/after proof, full failure list, cold/warm witnesses, packaging and source fingerprints are retained under `build/imp020-current/` and summarized in `acceptance-summary.json`.

Changed tracked files:

- `.github/implementation/IMP-020-contracts.md`
- `.github/implementation/IMP-020-report.md`
- `.github/workflows/blueprint-boundary.yml`
- `core/src/main/java/dev/aivillages/core/kernel/BlueprintValidator.java`
- `core/src/test/java/dev/aivillages/core/kernel/BlueprintFixture.java`
- `core/src/test/java/dev/aivillages/core/kernel/BlueprintValidatorTest.java`
- `core/src/test/java/dev/aivillages/core/kernel/BlueprintStoreTest.java`
- `core/src/test/java/dev/aivillages/core/kernel/BlueprintBoundaryProbe.java`

Current acceptance remains **incomplete** until the mandatory wrapper/full compatibility checks pass in an environment with the pinned distribution and working filesystem-space probe. Source remains uncommitted and unpublished; IMP-021 stays unmerged. RB-001/RB-002 are not started. Historical original reports/CI/IDs and unrelated local changes remain intact.

Final documentation publication: current report/contracts/status/evidence are committed as `122fed7594b02168ffa5d09a0c0de9c1866c84bc` (tree `76c7635e0f59fcb278b85b6e9fbd8398b0d5d137`) after the documentation-first checkpoint and actual implementation checks. Canonical source main remains `13f9b941f57022ded8987e4e0aa7652d4231921d`; the eight tracked source changes remain uncommitted, and the prior local IMP-020.1 report is untouched.

## Authorized GitHub Actions continuation — 2026-10-12

The latest user request authorizes publishing this repair and merging after CI passes. Documentation prompts/index/contracts/backlog were updated first in `3e5b6507bcfa0e38e2537683bc4ed12cf23dac5d`. The preceding uncommitted/SDK-only observations retain their historical snapshot meaning. All required pinned Gradle unit/module/native/package/prerequisite and two-JVM GP-20 verification now runs in GitHub Actions; no local Gradle download is needed. The scoped acceptance workflow checks out the exact PR head and records source commit/tree in its proof, with a clean tracked-source guard. Final Actions evidence and actual merge will be recorded when available. IMP-021 PR #20 stays open and unmerged.

## Observed Actions harness correction — 2026-10-12

Source `a927b50670b4edfe0620b219fc9a75893dab4d7a` passed 19/20 required workflows: the scoped 76 cases, all 1,166 regressions, packaging, cold/warm GP-20, compatibility and retention all passed. Gameplay run `38212528016` failed E2E-MUTATION-001 because its separate test observer queried only ACTOR_STOCK (6,201,6), but the genuine acquired fixture retained relevant stock at (6,200,6). Artifact `11732415280`, digest `sha256:e89b1621cfafe76a67c600eea7961885f7a1aab7939a21dc2763ca8e3c114d20`, was downloaded and hash-verified; 160 inspected queries correctly returned COMPLETE with zero matching records. Production query/rights behavior is correct.

The observer now alternates two bounded fixture-height queries, each with the same 64-record/8-result/8,192-byte limits and actual requesting player scope. The driver additionally requires a complete bounded positive query and a foreign query of the exact same target from a distinct trusted viewer, with no disclosed private evidence. Physical mutation/false-completion/stale-authority and qualification assertions remain mandatory. This repair was documented before code changes. No production API, privacy rule or data authority changes; a new reviewed head must pass fresh required Actions before merge.
