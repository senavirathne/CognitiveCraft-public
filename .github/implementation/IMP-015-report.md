# IMP-015 implementation and completion review

Save compatibility now coordinates the existing bootstrap 1 → 2 identity handoff through bounded plans and the original owner writers. Artifact compatibility is a shared pure policy over captured repository/runtime revisions, with exact dependency causes, relevant fingerprints and explicit revalidation requirements. Historical admission and immutable bodies remain unchanged.

**Baseline:** `b5599606a9c668370672722d3e681a785a7a124b`, merged IMP-014.1.
**Specification:** [documentation b844997](https://github.com/senavirathne/CognitiveCraft-Documentation/blob/b84499758bf77c7c529a5971419062bdbe586408/docs/source/docs/implementation-prompts.md#imp-015), published before implementation publication. The preserved original IMP-015/shared contract also applies.
**Status:** Implementation complete; local acceptance and all reviewed-source CI gates passed. Final report-only head verification and authorized merge follow.
**Contracts:** [IMP-015-contracts.md](IMP-015-contracts.md). Architecture stays 0.5; compatibility policy 1 is distinct from existing domain schemas and Contracts/IR 1.

## Original unit matrix and concrete evidence

| Original requirement | Binding and assertions |
|---|---|
| Supported migration | Actual bootstrap schema 1/citizen schema 1 stores → validated journal 2/citizens 1. UUID/entity/control, all history and exact raw legacy file preserved; repeated migration/current-format reopen is byte-identical no-op. |
| Unsupported format | Future bootstrap current plus readable previous remains untouched/inactive. Future citizen `Long.MAX_VALUE` is identified by exact domain/version and preserved even during partial handoff. Existing repository future-body/manifest regressions preserve admission metadata and files. |
| Relevant fingerprints | Actual admitted primitive body remains compatible/hash-identical when an unused primitive is added. Changing its used signature changes relevant fingerprint and blocks use. Runtime restoration rebuilds a compatible assessment. |
| Transitive dependency | Actual stored parent pins a specific child. Missing/changed child blocks parent with affected child/path and `DEPENDENCY_INCOMPATIBLE`; an alternative admitted body is never substituted. Cycle/missing refs and traversal exhaustion remain inactive. |
| Admission orthogonality | Before/after admission/evidence and every owner file hash match for assessment alone. Runtime mismatch does not quarantine admitted knowledge. Existing resolver/repository consumers share the policy. |
| Semantic change | A compiled changed body yields a new unadmitted proposal and explicit validation stages; original body/evidence/storage hashes survive. Same canonical body preserves identity. |
| Fault recovery | All seven coordinator phases, actual identity stage/backup/replace failures and a physically blocked bootstrap staging path preserve original or valid committed data. Reopen reconciles one citizen, same rights and retained raw original. Cancellation before publication and after identity publication and elapsed deadline recover without replay. |
| Space/work caps | All nine limit fields cover zero/exact/negative/one-over/overflow. Real 128-visit graph and exact compiler-byte allowance/zero diagnostics verified. Lowered byte/record/checkpoint/time or insufficient disk allowances refuse before publication. Maximum actual owner inputs retain all 80 records within reserved staging/backup bytes. |
| Concurrent owners | A held commit checkpoint blocks a concurrent existing owner write on the same monitors; after publication its old revision/schema write is refused. Stale plans and nonquiescent use do not publish. |
| Offline/privacy | No model port in core policy/migration. Principal-scoped inspection denies foreign/copied-world access without IDs, revisions, count or source digest. Real Minecraft cold/warm startup uses unavailable generation and inference disabled; foreign identity/history access remains denied. |

## Local executed acceptance

Pinned OpenJDK 25+36 and checksum-verified Minecraft 26.3 / Fabric Loader 0.19.5 / Fabric API 0.161.0+26.3 / GameTest API 4.0.32+3434d6d95d were used with the existing headless build harness. Pins and wrapper checksum are unchanged. The source compiled all three production modules and every GameTest source, and all three modules' JUnit sources were compiled/run. No local model was configured or downloaded for this unit.

- Full affected unit run: **870** tests, zero failures/skips/aborts, including 10 artifact cases and 25 save cases plus retained repository, identity, journal, provider, Fabric policy, broker and diagnostics coverage.
- Cold Minecraft process: **2 active project cases**, legacy loaded-world production startup upgrade and saved-block witness.
- Warm distinct Minecraft JVM, same world: **2 active project cases**, migrated identity/history/original/no-op checks and independent saved-block witness.
- Cold/warm migration: one same UUID/entity/control identity, source 1 → target 2, uncertain run `INTERRUPTED`, synthetic historical effects 3, physical seeded wheat still 3, replayed effects 0, model calls 0, repeated handoff `NO_OP`. These are controlled storage fixtures; the seeded history/ref is not behavioral admission or evidence of actual historical work.
- Production/test JARs compiled separately; release package contains the two policies and no GameTest classes or model weights. Diff whitespace and workflow selector/metrics checks passed.

Commands use `JAVA_HOME` pointed at the verified JDK: `python3 scripts/headless.py build`; JUnit ConsoleLauncher 1.11.4 scanning core/providers/fabric test classes; isolated selected GameTest JAR launched via Fabric `KnotServer` in separate cold and warm processes. Local runtime metadata was selected in copied test JARs; full source registration stayed intact. The dedicated CI workflow repeats this using pinned Gradle `:core:test :providers:test :fabric:test :fabric:build :fabric:gameTestJar :fabric:runGameTest` and exact named cases, active phase log markers, actual maximum-input JSON and release inspection.

## Measured bounded outputs

[IMP-015-measurements.json](IMP-015-measurements.json) records actual controlled fixture outputs. The representative handoff used 702 input bytes, reserved 2,204 additional bytes, retained 614 exact original bytes and completed in 7 checkpoints. The maximum-input fixture used 81,920 input bytes / 64 citizens / 16 runs; baseline directory size 82,534 bytes, observed stage/backup high-water 170,438 bytes and conservative additional reservation 124,745 bytes. Thus observed growth 87,904 bytes stayed within the 262,144-byte hard reservation cap. These observed phase sizes are measurements, not an assertion that arbitrary filesystems cannot add metadata/block allocation overhead. The reservation uses byte accounting and current filesystem usable-space checks; owner I/O errors remain recoverable.

The real bounded graph measured 128 visits, one retained blocking diagnostic; the exact single-body compiler input was 129 bytes. Lowering it by one blocked compilation, and zero diagnostic allowance reported omission. Compatible assessments hash the relevant pinned closure; fail-fast inactive assessments report the first blocking cause rather than pretending to enumerate all failures.

## Review corrections and ownership audit

Review corrected unsupported citizen-version reporting to name its exact domain/version instead of only the bootstrap handoff versions; the `Long.MAX_VALUE` current-file/valid-backup regression verifies no overwrite. A first GameTest assertion incorrectly expected null for foreign private run history; the existing controller correctly throws `SecurityException`, and the fixture now asserts that authorization rejection. No authority change was needed.

`ArtifactCompatibility` owns only policy/derived values; repository owns bodies/admission and captured snapshots. `SaveCompatibility` owns registration/plans/coordination, while `CitizenIdentityStore` and `BootstrapJournal` exclusively validate/write their own records and backups. The only concrete registered old format is bootstrap 1. Future owner rules require an actual owner adapter, not guessed formats or automatic transformation. Kernel startup uses its existing storage worker; live tick execution is fenced until the migration is safe. No second store, migration journal, schema reinterpretation, model rewriting, retention deletion, automatic repair/admission or unrelated gameplay domain was introduced.

Cancellation/deadline/quiescence checks are cooperative at fixed apply phases. They cannot forcibly stop a hung filesystem operation; such I/O stays on the owner worker with startup fenced. This is an explicit operational limit, not a hard wall-clock completion guarantee. Principal inspection/unsupported reports remain bounded; trusted host inspection is not a player endpoint. GP-15 retention/compaction/racing collection stays future IMP-016 work.

## Published-source CI and merge evidence

All **16 runs** on reviewed runtime source `e8300c2976d76504e921fcdc64570e277583014c` passed: **13 pull-request acceptance families and 3 push runs**. The final report-only commit preserves every implementation/test/workflow blob from this reviewed source; its head is separately gated before merge.

| Workflow | Event | Result | Run |
|---|---|---|---|
| AI work broker acceptance | pull_request | Passed | [38071415605](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38071415605) |
| Capability retrieval acceptance | pull_request | Passed | [38071415602](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38071415602) |
| Citizen identity acceptance | pull_request | Passed | [38071415606](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38071415606) |
| CognitiveCraft verification | pull_request | Passed | [38071415612](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38071415612) |
| CognitiveCraft verification | push | Passed | [38071413182](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38071413182) |
| Default nearest physical acceptance | pull_request | Passed | [38071415662](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38071415662) |
| Job lifecycle acceptance | pull_request | Passed | [38071415597](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38071415597) |
| Local generation smoke | pull_request | Passed | [38071415577](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38071415577) |
| Needle language acceptance | pull_request | Passed | [38071415644](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38071415644) |
| Primitive diagnostics acceptance | pull_request | Passed | [38071415647](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38071415647) |
| Primitive diagnostics acceptance | push | Passed | [38071413188](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38071413188) |
| Research local-model physical trial | pull_request | Passed | [38071415595](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38071415595) |
| Resource lease acceptance | pull_request | Passed | [38071415586](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38071415586) |
| Save and artifact compatibility acceptance | pull_request | Passed | [38071415608](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38071415608) |
| Save and artifact compatibility acceptance | push | Passed | [38071413243](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38071413243) |
| Worker dispatcher acceptance | pull_request | Passed | [38071415699](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38071415699) |

The compatibility job required 10 artifact / 25 save cases, real maximum-input accounting, two active cold project cases, two active warm project cases in a separate JVM, exact phase markers and production package checks. CI measured the same scalar bounds as the committed local evidence; canonical source digests differ across synthetic UUID fixtures, as expected.

The full Gradle/Fabric verification ran **48 distinct project GameTests / 75 successful executions**, including explicit saved-world, cancellation, physical attribution, future-manifest refusal, SIGKILL recovery, private commands/completion, identity and injected-NLU nearest binding. The pinned genuine-model trial used existing Ollama 0.12.10 / Qwen3 4b instruct 2507 q4_K_M gates, physically admitted a generated method, then stopped the model service and reused saved knowledge in a separate Minecraft process. Retained IMP-014.1 diagnostics ran six active cold/two warm cases, blocked writer/known physical work, unchanged admitted bytes, private replay and the actual offline host report. Existing citizen/job/lease/dispatcher/broker/retrieval/language/local-generation families all passed. No review submissions or unresolved review threads existed at the completion check.

Delivery: [PR #9](https://github.com/senavirathne/CognitiveCraft-public/pull/9). Merge is authorized by the user's request and will use the verified final head SHA. Documentation links are filled from the actual resulting merge commit; no future merge SHA is fabricated.

## Changed files

The implementation changes `ArtifactCompatibility`, `SaveCompatibility`, two existing migration owners, `VersionedSkillRepository` and Fabric `KernelSession`; adds two core suites, two Fabric GameTest classes, the dedicated `save-compatibility.yml` workflow and this contract/report/measurement set; extends full GameTest registration. No dependency/model/version pins or historical documentation payloads change. Documentation publication separately updates current prompts, backlog/routing/protocol/invariants/gaps and GP-15 acceptance bindings while retaining the 167 exact historical source bodies and 29-unit inventory.
