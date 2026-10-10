# IMP-017 implementation and verification evidence

IMP-017 adds a private, bounded owner for actual previously observed crop/stock evidence, with provenance, conservative freshness, explicit sharing, compatibility and retention. Minecraft and the survival gateway retain current physical truth and effect authority.

Initial public `main`: **`ae4bbe5423ef1a9e8d4aee94e11d64aba3dca65b`**. This is the live IMP-016 merge; no newer `main` commits existed at the initial audit. All remote branches and recent open/draft/merged PRs were inspected; no existing IMP-017 branch, implementation or PR was found. Open PR #7 was unrelated unfinished real-client work, and was not modified or reset. The most recent nine merge-head workflow runs were successful. Historical documentation status is used only as provenance.

Branch: **`work/imp-017`**, exclusively in `senavirathne/CognitiveCraft-public`. The documentation repository was read through its routing index; the historical/private repository was not accessed. Architecture **0.5**, Contracts/IR **1**, observation contract **1**, observation schema **2**, compatibility/retention policies **1**. [Detailed owner/API contract](IMP-017-contracts.md).

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

## Executed validation status

Final local Java 25/JUnit execution passed **43 feature cases**, zero failures/errors/skips, including recovered-store completeness, actor stock, saturating counters, revision/deadline overflow and bounded stored deadlines. Direct execution was necessary because Gradle's JVM network connection was unavailable in this environment. Repository-supported `scripts/headless.py build` compiled all production modules and GameTest sources with checksum-verified dependencies. Full module execution discovered **964 cases: 953 passed, 11 failed, zero skipped**. All 11 failures are existing save-migration cases returning `SPACE_UNAVAILABLE` before their injected fault points. A direct `Files.getFileStore(path).getUsableSpace()` probe reproduced `IOException: Mount point not found` in this container. This is an environment limitation, not a passing compatibility claim; the existing migration assertions remain intact. The first PR head `40b1c7430bd231a84ae4d867f076df1c0780fc09` passed the exact Gradle matrix and all 964 module cases (core 876, providers 62, Fabric 26), with zero failures/errors/skips, in observed-knowledge run [38083594988](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38083594988). Its cold/warm GP-17 gates each executed two project cases successfully. Final review adds cancellation-after-backup accounting/reservation checks and waits for both fixture actors before starting captures; per-tick fixture intake prevents partial retries from exhausting a stale offer budget. The revised source must pass the same final-head workflows before merge.

Required CI commands in `.github/workflows/observed-knowledge.yml`:

```sh
bash ./gradlew --no-daemon --stacktrace :core:test --tests dev.aivillages.core.kernel.ObservedWorldKnowledgeStoreTest
bash ./gradlew --no-daemon --stacktrace :core:test :providers:test :fabric:test :fabric:build :fabric:gameTestJar
bash ./gradlew --no-daemon --stacktrace :fabric:runGameTest # cold GP-17 and saved-world witness
bash ./gradlew --no-daemon --stacktrace :fabric:runGameTest # second JVM, same saved world
```

The workflow checks exact case discovery with zero skips, actual 1000-capture JSON, complete module counts, both active project tests in each JVM, retained source age/scope, actual physical revalidation, saved-world witness and production packaging. It exports XML/logs, deduplication measurement, restart proof, release hash and acceptance counts.

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

## Delivery

Implementation [PR #11](https://github.com/senavirathne/CognitiveCraft-public/pull/11) is open in draft. Final revised head/Actions, merge and merged-source verification: **pending execution; no merge is claimed at this checkpoint**. The post-merge evidence update will record the exact implementation head and merge identifiers, which cannot be embedded in their own Git commit. IMP-018 and later units remain out of scope. Bounded history and queue pressure explicitly refuse/drop new advisory evidence; queries retain freshness/source limitations and always require gateway effect-time validation.
