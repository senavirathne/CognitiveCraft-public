# IMP-017 implementation and verification evidence

IMP-017 adds a private, bounded owner for actual previously observed crop/stock evidence, with provenance, conservative freshness, explicit sharing, compatibility and retention. Minecraft and the survival gateway retain current physical truth and effect authority.

Initial public `main`: **`ae4bbe5423ef1a9e8d4aee94e11d64aba3dca65b`**. This is the live IMP-016 merge; no newer `main` commits existed at the initial audit. All remote branches and recent open/draft/merged PRs were inspected; no existing IMP-017 branch, implementation or PR was found. Open PR #7 was unrelated unfinished real-client work, and was not modified or reset. The most recent nine merge-head workflow runs were successful. Historical documentation status is used only as provenance.

Implementation branches: **`work/imp-017`** (primary) and **`work/imp-017-evidence`** (final corrections), exclusively in `senavirathne/CognitiveCraft-public`. The documentation repository was read through its routing index; the historical/private repository was not accessed. Architecture **0.5**, Contracts/IR **1**, observation contract **1**, observation schema **2**, compatibility/retention policies **1**. [Detailed owner/API contract](IMP-017-contracts.md).

## Verified prerequisite bindings

| Unit | Live interface consumed |
|---|---|
| IMP-002 | `SurvivalGateway` bounded observations and effect checks, `Contracts.ObservationSnapshot`, actual `FabricGatewayWorld` stock/crop adapters. Additive private-issued evidence preserves existing primitive results and schemas. |
| IMP-015 | Explicit registered `observed-world` versions 1/2 and rule; owner `migrate` preserves exact old bytes, identity, age and scope. Existing bootstrap/other readers retain their meanings. |
| IMP-016 | Existing manager `Owner`/incremental cursor, `RetentionRoots`, `assessReplacement`, `approveDiscard`, generation fences, `RetentionFileOwner` and existing world-storage worker. |

## Feature-to-test bindings

The exact original IMP-017 nine-row matrix is implemented in `ObservedWorldKnowledgeStoreTest`. Its 43 executed-case gate includes five parameterized replacement boundaries. Nested corruption/migration fixtures are additional assertions, not inflated test counts.

| Required feature | Actual test binding |
|---|---|
| Fresh facts | Actual gateway-issued source/container/inventory evidence; immutable context and first/latest proof; finite freshness and no fabricated global revision. |
| Unknown/absent | Unloaded scan, bounded actual empty scan, unobserved region and other dimension; corrupt recovery cannot claim exhaustive absence/unknown. |
| Invalidation | Changed/removed targets, superseded historical proof, explicit invalidation, controlled expiry, clock regression and source loss. |
| Dedup/conflict | 1000 actual fresh captures retain one record/history; replay/out-of-order checks, source alternatives/conflicts, protected history rollover and saturating counters. |
| Model assertions | Plain snapshots, record values, maps and model text cannot enter the physical store; no model dependency is present. |
| Isolation/sharing | Two principals at identical coordinates; world/domain relabel rejection; explicit per-record policy grant/revocation; unrelated records/recipients and foreign denials stay private. |
| Persistence | Current-schema reopen; checksum/malformed/partial/missing/future/world mismatches; oversized sparse input; exclusive writer; exact v1 archive and age/scope migration; all five publication fault points. |
| Cache/limits | Delete/rebuild indexes; zero/lowered scan/result/context limits; stale build publication; file/count/history limits; active reference protection; quota refusal and stale collection. |
| No models/effects | World access disabled after capture; queries do not scan/move/harvest/pick up/transfer. Service queue/slices, delayed authority/source revocation, cancellation and immutable snapshot fences. |

The review adopted relevant existing failures as regression boundaries: unknown-versus-absence, shared coordinate privacy, worker generation drift, corrupt current with valid prior generation, oversized bounded reads, stage/backup/replace failures, and IMP-016 reference fences. Existing compatibility, retention, gateway, job and lease tests are preserved. No assertion or workflow requirement was weakened.

## Executed final implementation validation

Verified final code head: **`8a7b9f6f6f54f222f01a4a94ff6dd5f09601bb6d`**, [PR #12](https://github.com/senavirathne/CognitiveCraft-public/pull/12), building on merged [PR #11](https://github.com/senavirathne/CognitiveCraft-public/pull/11). The final observed-knowledge [run 38086539557](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38086539557), job **114314119966**, executed the exact feature matrix first, followed by all affected modules and separate actual cold/warm Minecraft processes.

| Executed gate | Passing cases | Failures / errors / skipped |
|---|---:|---:|
| IMP-017 exact matrix | 43 | 0 / 0 / 0 |
| Core module, including those 43 feature cases | 876 | 0 / 0 / 0 |
| Providers module | 62 | 0 / 0 / 0 |
| Fabric unit tests | 26 | 0 / 0 / 0 |
| All unit modules (feature cases are not counted twice) | **964** | **0 / 0 / 0** |
| Cold GP-17 + saved-world project witness | 2 | 0 / 0 / 0 |
| Warm GP-17 + saved-world project witness | 2 | 0 / 0 / 0 |

Each Minecraft process also executed one Fabric built-in smoke test: three total game cases per process, six total, including four project cases. No skipped or undiscovered cases count as passing.

Exact CI commands:

```sh
bash ./gradlew --no-daemon --stacktrace :core:test --tests dev.aivillages.core.kernel.ObservedWorldKnowledgeStoreTest
bash ./gradlew --no-daemon --stacktrace :core:test :providers:test :fabric:test :fabric:build :fabric:gameTestJar
COGNITIVECRAFT_OBSERVATION_RESTART=cold COGNITIVECRAFT_RESTART_PROBE=cold bash ./gradlew --no-daemon --stacktrace :fabric:runGameTest
COGNITIVECRAFT_OBSERVATION_RESTART=warm COGNITIVECRAFT_RESTART_PROBE=warm bash ./gradlew --no-daemon --stacktrace :fabric:runGameTest
```

The workflow selects the exact cold/warm entrypoints before each GameTest command and keeps the same saved world for the second JVM. It requires both active project cases, zero failure/error/skipped nodes and each phase's acceptance marker. Persistence/reload, age/scope preservation, index deletion/rebuild, source-session loss and live effect-time validation passed. Five parameterized publication-fault points, corrupt/future/partial/missing/oversized records, malformed unbounded deadlines, explicit age-preserving v1 migration and cancellation-after-backup accounting are covered in the 43-case gate.

Actual 1000 newly captured gateway observations retained **one record, one history entry, 1430 bytes**. Both unit and loaded-world observation scenarios require **zero model calls and zero historical-query effects**. Store/service have no model or Minecraft effect port; the deterministic fixture disables world access after capture and checks unchanged physical counters. Real GP-17 also verifies that sharing cannot control another principal's actor and that harvesting a removed crop is rejected.

Production JAR SHA-256 from the final feature workflow: **`69e0608558836f52e320fce74b3c29373a7d32412654f6cdc48c1e9893955dca`**. Packaging checks require both observation classes, exclude GameTest code/model weights and require one production artifact. The workflow's `imp-017-observed-knowledge` artifact **11682087798** retains XML, logs, deduplication measurements, restart provenance and `acceptance.json`; artifact archive digest is `b7cae57679f39813a64f8e77244e74250814f9bdb16c9cdf5e525d41d7a375f1`.

Repository-supported `scripts/headless.py build` also compiled production and GameTest source locally with checksum-verified dependencies and Java 25. Direct JUnit execution passed all 43 final feature cases. An earlier local cold/warm driver also passed all six game cases before the final public-provenance projection change; the final code's cold/warm evidence is the successful GitHub run above. Local full-module execution found 964 cases, 953 passing and 11 existing save-migration failures, zero skipped. A direct `Files.getFileStore(path).getUsableSpace()` probe reproduced `IOException: Mount point not found`, causing those local migrations to return `SPACE_UNAVAILABLE` before injected fault points. The unchanged compatibility tests and actual loaded-world migration/restart passed on the final GitHub runner; this environment limitation was not hidden or counted as a local pass.

Final review fixed the record-count reservation/accounting after an interrupted backup replacement. The existing cancellation case now verifies exact retained bytes, unchanged current generation and both current/backup record counts. An earlier local GP-17 partial-capture retry exhausted its fixture offer allowance while the second actor was not yet ticking; final fixtures wait for both actors and reset intake per real test tick. The revised loaded-world gates passed without weakening their privacy or discovery assertions.

## Final code-head GitHub Actions

All fifteen pull-request workflow families completed successfully for **`8a7b9f6f6f54f222f01a4a94ff6dd5f09601bb6d`**. These are final-head results, not historical main evidence. Existing regression workflows include gateway effects, jobs/leases, dispatch, saved-world restart, compatibility, retention, privacy and older feature owners.

| Workflow | Run | Result |
|---|---|---|
| AI work broker acceptance | [38086539592](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38086539592) | Success |
| Capability retrieval acceptance | [38086539556](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38086539556) | Success |
| Citizen identity acceptance | [38086539527](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38086539527) | Success |
| CognitiveCraft verification | [38086539552](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38086539552) | Success |
| Default nearest physical acceptance | [38086539607](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38086539607) | Success |
| Job lifecycle acceptance | [38086539522](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38086539522) | Success |
| Local generation smoke | [38086539603](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38086539603) | Success |
| Needle language acceptance | [38086539559](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38086539559) | Success |
| Observed world knowledge acceptance | [38086539557](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38086539557) | Success |
| Primitive diagnostics acceptance | [38086539586](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38086539586) | Success |
| Research local-model physical trial | [38086539546](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38086539546) | Success |
| Resource lease acceptance | [38086539547](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38086539547) | Success |
| Save and artifact compatibility acceptance | [38086539622](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38086539622) | Success |
| Worker dispatcher acceptance | [38086539538](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38086539538) | Success |
| World retention acceptance | [38086539541](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38086539541) | Success |

Engineering review [**5480830730**](https://github.com/senavirathne/CognitiveCraft-public/pull/12#pullrequestreview-5480830730) is anchored to that exact head. It covers state ownership, typed proof trust, freshness, scope/share revocation, snapshot/reference fences, compatibility, retention, worker threading and recovery; it is an implementation-agent review, not a claim of independent human approval. There were no unresolved review threads or requested changes. Main had no branch protection or rulesets to bypass; the merge used the repository's established merge-commit convention.

## GP-17 and runtime boundaries

`ObservedKnowledgeGameTests` uses actual Fabric gateway stock/crop snapshots. It observes seven wheat in a permitted container, removes that container/crop, verifies historical supersession and live gateway rejection of the removed crop, observes an unloaded area as unknown, runs a second principal at the same coordinates, applies/revokes an explicit existing visibility-policy grant, expires controlled time, reopens persistence and deletes/rebuilds indexes. `ObservedKnowledgeRestartGameTests` requires a different JVM/process-start witness and the same Minecraft save, preserves proof identity/age/scope and qualifies the old gateway session as lost. Model statements are rejected. No planner/future facility consumer is created; the supported gateway/test-driver binding is the gameplay acceptance consumer.

Production `KernelSession` automatically captures issued primitive observations through the bounded service, rechecks current citizen ownership on its storage worker, integrates migration and retention, and exposes a bounded asynchronous historical evidence query. Production's existing private visibility policy continues to deny cross-player sharing until an existing policy owner explicitly permits it. The fixture's revocable explicit grant demonstrates the supported sharing seam without adding authority services.

## Source inventory

- `core/src/main/java/dev/aivillages/core/kernel/ObservedWorldKnowledgeStore.java`: authoritative records, typed freshness, bounded queries/snapshots/indexes, sharing, migration, persistence and retention owner.
- `core/src/main/java/dev/aivillages/core/kernel/ObservedKnowledgeService.java`: bounded I/O-free intake and established worker publication.
- `core/src/main/java/dev/aivillages/core/kernel/SurvivalGateway.java`: private-issued evidence and nonblocking trusted observation sink.
- `core/src/main/java/dev/aivillages/core/kernel/SaveCompatibility.java`: explicit reader/rule registration; `core/src/test/java/dev/aivillages/core/kernel/SaveCompatibilityTest.java` preserves the old migration gate and asserts the new rule.
- `core/src/test/java/dev/aivillages/core/kernel/ObservedWorldKnowledgeStoreTest.java`: complete IMP-017 matrix and review regressions.
- `fabric/src/main/java/dev/aivillages/fabric/KernelSession.java`: actual owner composition, worker-safe control view, source fence, asynchronous bounded query and shutdown.
- `fabric/src/gameTest/java/dev/aivillages/fabric/ObservedKnowledgeGameTests.java`, `fabric/src/gameTest/java/dev/aivillages/fabric/ObservedKnowledgeRestartGameTests.java` and `fabric/src/gameTest/resources/fabric.mod.json`: real loaded-world cold/warm GP-17.
- `.github/workflows/observed-knowledge.yml`: exact feature/regression/gameplay/packaging gates.
- This report and `IMP-017-contracts.md`: versioned implementation semantics and executed evidence.

## Final review corrections

Primary PR #11 merged as `0a6145cfa45daf824c278669abba059036c5c570`; its fetched full tree matched reviewed head `36570ecbf21386e646de40863f10b24207e06b82`. Final corruption review then reproduced an external change after staging that overwrote the usable backup: the new regression expected 1413 original bytes and received 26 corrupt bytes. The earlier external-change test exercised corruption before admission and did not cover this interval.

[PR #12](https://github.com/senavirathne/CognitiveCraft-public/pull/12), on `work/imp-017-evidence`, corrects this by taking an immutable bounded copy of the validated current bytes and using that exact copy for the backup. The final current-state fence preserves corrupt externally replaced current bytes and refuses publication. Migration similarly validates the owned bytes before archiving them. Nested checks cover AFTER_STAGE, BEFORE_REPLACE, restart with retained age/provenance and refusing an unverified migration archive. Privacy review additionally removes the gateway-wide issuance counter from public query provenance. Immutable `RecordView`/`Provenance` values preserve supported snapshot revision/tick, source identity, original age and scope; public context accounting and snapshot hashes exclude private ordering values. The existing scope test asserts that public fields/string output do not expose that counter. These extend existing cases; discovered feature count remains 43.

The corrected source passed all fifteen final-head workflow families and merged in PR #12 as `1241f710cf4ad00b83d1693f698d2dfe6dfeca9d`. The final release hash and artifact identifiers above supersede the primary-head values.

One intermediate local 1000-capture stress run conservatively returned `STORAGE_UNAVAILABLE` at capture 466 while the owner remained writable. Eight isolated 1000-capture probe runs did not reproduce it. The strengthened normal test requires every intake to be recorded and includes the capture/result/read-only diagnostic; it passed locally and on the final CI head. No failed intake is counted as a successful capture, and finite storage pressure remains an explicit refusal boundary.

## Delivery and merged-source proof

| Evidence | Exact identifier |
|---|---|
| Initial public main | `ae4bbe5423ef1a9e8d4aee94e11d64aba3dca65b` |
| Primary reviewed code head / PR | `36570ecbf21386e646de40863f10b24207e06b82` / [#11](https://github.com/senavirathne/CognitiveCraft-public/pull/11) |
| Primary merge | `0a6145cfa45daf824c278669abba059036c5c570` |
| Final reviewed code head / PR | `8a7b9f6f6f54f222f01a4a94ff6dd5f09601bb6d` / [#12](https://github.com/senavirathne/CognitiveCraft-public/pull/12) |
| Final code merge, fetched public main | `1241f710cf4ad00b83d1693f698d2dfe6dfeca9d` |
| Final code head and merged complete tree | `04e669fa07414b349d63d142bb7b35c131fc835c` |
| Final merge parents | `0a6145cfa45daf824c278669abba059036c5c570`, `8a7b9f6f6f54f222f01a4a94ff6dd5f09601bb6d` |
| Completion report branch | `work/imp-017-final-report`, based on the final code merge |

Both implementation PRs are merged. Final review has no unresolved threads or requested changes, and all fifteen final code-head workflows are successful. The final merge's complete Git tree equals the reviewed head; no source, test, workflow or contract changes were introduced by merging.

The public repository was freshly cloned and explicitly fetched. These checks returned success:

```sh
git fetch origin main
git diff --exit-code 8a7b9f6f6f54f222f01a4a94ff6dd5f09601bb6d origin/main
git show --no-patch --format=fuller origin/main
```

The fetched main was `1241f710cf4ad00b83d1693f698d2dfe6dfeca9d`; the diff was empty and its merge parents matched the table. This final report is published in a separate documentation-only follow-up so it can contain the already executed merge identifiers. That follow-up changes this report alone; its own publication commit is not self-referentially embedded here. Its final PR-head checks and fetched-main verification are linked in the publication PR and completion response.

## Documented limits and remaining scope

This is finite retained historical evidence. It cannot prove continuous current-world truth, identify an identical container reincarnation from an address alone, or infer absence beyond an actually completed supported scan. Freshness defaults to 30 seconds and is capped at 300; retention defaults to seven days and is capped at thirty. Hard caps are 128 records, eight history rows per typed target, eight explicit recipients, 65,536 bytes per envelope, 128 candidate references per query, sixteen returned rows and 16,384 context bytes. The bounded service admits eight offers per tick, thirty-two queued items and four items per worker turn. Pressure, incomplete indexes/coverage, source loss and incompatible recovery are explicit qualified/refusal results, not silent certainty.

Physical effects continue through current gateway authority checks. Existing production visibility policy is private by default; explicit sharing is supported through its existing policy interface and exercised by the revocable loaded-world fixture. No new sharing command, exploration/facility primitive, authority service or future planner is introduced. Protected asynchronous consumers must pin exact retention keys before use and revalidate their snapshots. No model calls occur in the observation feature acceptance; unrelated existing research/model workflow families intentionally test their own model paths.

No implementation acceptance gap remains on the final code head. Local full-suite filesystem limitations are recorded above rather than represented as passing local checks. IMP-018 and later remain out of scope.
