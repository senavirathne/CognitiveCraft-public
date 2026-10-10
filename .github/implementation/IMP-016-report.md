# IMP-016 implementation and completion review

World retention now uses bounded owner snapshots, dependency-aware collection, nonblocking reference fences, contextual failure aggregates and explicit admission capacity results. Existing stores retain publication, admission, permissions and job-accounting authority.

**Baseline:** merged IMP-015, `3dc86c8a7aba11c70e59ede8806c5387921e626e`.
**Specification:** [documentation 4393eec](https://github.com/senavirathne/CognitiveCraft-Documentation/blob/4393eecb349de4028059c2acac93b1df7ed22ea2/docs/source/docs/implementation-prompts.md#imp-016), published before implementation. The preserved original IMP-016/shared contract remains normative.
**Status:** Implementation and local acceptance complete; repository CI and final merge verification pending.
**Contracts:** [IMP-016-contracts.md](IMP-016-contracts.md); architecture 0.5, policy/evidence schema 1. [Executed measurements](IMP-016-measurements.json).

## Original ten-row matrix

| Required row | Executed binding |
|---|---|
| Referenced closure | Shared job/research/run/rollback/access roots; real repository parent/child and useful alternatives; actual 16-job journal/root simulation. Exact reachable versions survive while uncommitted bodies collect. |
| Reference race | Pin acquired after scan invalidates deletion. Acquisition during an owner operation returns `BUSY`; stale owner/registration generations and changed files cannot use a proposal. |
| Capacity | Zero/exact/one-over category bytes/count and total limits; overflow/incomplete accounting; diagnostic concurrent-writer reservation. Actual publication rejects new knowledge without changing existing owner file hashes. Recovery-file staging headroom is reserved. |
| Failure aggregation | 10000 equivalent shortages → 1 aggregate, exact count 10000, 3 samples and 16 recent summaries; changed observed digest and 30-second expiry invalidate the premise. Distinct scopes/artifacts/conditions do not merge. |
| Alternatives | Distinct currently admitted versions survive; no newest-only or compatibility-driven eviction. Supported and configured rollback closures retain parent/child bodies. Superseded unreferenced rollback bodies collect parent-first only after owner publication replaces their supported envelope. |
| Interrupted compaction | All five stage/backup/replace fault boundaries and four cancellation boundaries recover original/new evidence. Five actual child JVMs are forcibly killed at those boundaries, then reopened without corruption or effects. |
| Root registration | Later access roots, storage-owner epochs and direct/transitive evidence/trace pins prevent unsafe collection or rollover, preserving identity/admission. |
| Bounded work | 4096-node iterative graph uses 1539 slices, maximum 16 inspections. Cycles, missing refs, node limits, empty progress pages, byte/token bounds and diagnostics remain finite/fail-closed. |
| Cache independence | Actual retrieval-owner deletion plus repository/job reopen preserves admission, private origins and accounting. Loaded-world warm process deletes retrieval cache and verifies identical manifest, identities, jobs and evidence hashes. |
| Offline/privacy | No model dependency in retention. Scoped totals/history exclude foreign data; aggregate access requires policy. Two loaded-world phases use an unavailable generator and prove zero calls, unchanged chest stock and no replay. |

## Local executed acceptance

The existing checksum-verified headless harness used OpenJDK 25+36, Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3 and GameTest API 4.0.32+3434d6d95d. All production modules and GameTest sources compiled; all three modules' unit suites ran. Build/dependency pins are unchanged. No local model was configured/downloaded for this unit.

- **921** unit tests passed, zero failures/errors/skips. The retention suites contain **51** cases: manager 16, evidence 26, owner integration 2, review safety 7.
- Equivalent-failure persistent directory: maximum **15784 bytes**, below the **196608-byte** three-envelope ceiling; counts/samples survive reopening.
- Actual repository/job simulation: **256** interrupted publications followed by **256** orphan collections; **16** retained jobs, **2** protected artifacts, maximum measured total **96721 bytes**, exact job accounting unchanged on every iteration and reopen.
- Graph: **4096** nodes, **1539** slices, maximum **16** inspections; no recursive stack and no protected collection.
- Loaded Minecraft cold/warm: **2 active project tests in each separate JVM**, plus the vanilla built-in case. Twenty actual shortage requests retain one aggregate/three samples; the uncommitted body disappears; chest stock stays seven; model calls and uncertain effects replayed stay zero. Warm startup after explicit cache-owner deletion preserves four authoritative file hashes and saved citizen/control identity. Process ID plus JVM start time proves separation even when namespaces reuse PIDs.

Local commands: `JAVA_HOME=<verified-jdk25> python3 scripts/headless.py build`; full core/providers/fabric JUnit compilation and ConsoleLauncher scan; two isolated KnotServer invocations selecting `RetentionGameTests` / `RetentionRestartGameTests` and `ServerRestartProbeGameTests`. Reports are under `build/verification/imp-016-all-unit`, `build/retention-evidence` and `build/retention-gametest-run`.

## GP-15 drivers and CI gates

GP-15 repeated history, protected alternatives/reference races, capacity and interrupted compaction map to the above real-owner tests and the active loaded-world drivers. IMP-015 migration/fingerprint fixtures remain unchanged. `.github/workflows/world-retention.yml` executes pinned Gradle module tests/build, requires all 51 retention cases including five actual process kills, validates measured simulation JSON and requires both loaded-world phases plus production-only packaging. It retains XML, measured JSON, saved-world witness and logs. Existing compiler/runtime, diagnostics, genuine-model physical admission, known work, broker, identity, language, retrieval, jobs, leases, dispatcher and compatibility gates remain required.

## Review findings resolved

Review added reference leases around history rollover; direct/transitive pins and surviving incoming edges prevent dangling records. Orphan bodies now expose their exact dependency graph so a pinned superseded rollback parent protects its child. Parent-first collection avoids leaving corrupt unrooted edges. Root seeding and empty-page traversal are metered. Failed publication attempts invalidate physical snapshots, and collection rechecks body digests/file identity. Evidence validates byte/token readability before publication, conservatively fences unknown/corrupt formats, closes writers safely and preserves uncertain outcomes. Diagnostic growth uses reserved headroom rather than racing measured usage.

## Limits

Every admitted/quarantined current strategy stays protected. This unit introduces no knowledge retirement/admission retraction, semantic-equivalence prover, schema migration, compression or world cleanup. History is advisory until durable publication; full queues, pinned windows or unavailable storage can drop/reject new summaries visibly. Required job budgets, credits, receipts and permissions remain with their existing owners. Synthetic repository admissions in retention fixtures exercise storage/retention, **not** physical behavioral admission; the existing genuine-model and known-work CI fixtures supply that independent evidence.
