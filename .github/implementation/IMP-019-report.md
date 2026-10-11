# IMP-019 implementation and verification evidence

IMP-019 adds bounded goal decomposition for the recognized two-destination wheat-supply goal. It uses the shared AIWorkBroker, validates typed supported child outcomes and admits their complete graph through existing Jobs. Existing worker dispatch, leases and physical gateway execute the known children independently of models.

Starting public main: **`502bac704a44a21898d9292db07b02cd8525176b`**, verified IMP-018 completion and evidence publication. Dependencies IMP-010/011/014/015/016/017/018/028 are present in live public source. The documentation repository remains read-only. Normative routing follows its `AGENT_DOCUMENTATION_INDEX.md` at independently verified documentation head **`b1d5bf71365d1b1762699c006465caa1db8e5be5`**: implementation registry and backlog, exact IMP-019 prompt plus shared execution contract, architecture invariants/contracts and GP19. Current architecture is **0.5**; Plan / PlanningBudget and the new planning journal are additive schema/contract **1**. Existing Contracts/IR and Jobs/Leases schemas remain **1**, observations remain **2**. The user's explicit completion-and-merge instruction authorizes publication beyond the historical uncommitted-delivery wording.

See [IMP-019-contracts.md](IMP-019-contracts.md) for exact scope, bounds, conservative reservation versus actual usage, effect/authority/cancellation ownership and staged Jobs/metadata recovery. The initial supported goal is narrow and explicit; no arbitrary crafting/building primitives, new worker chooser, dedicated model or automatic planning for ordinary resolver misses is introduced.

## Required acceptance bindings

| Matrix row | Executed binding |
|---|---|
| Prior direct and deterministic handling | Complete direct/prerequisite outcomes and incomplete/unsupported routes invoke zero planner calls |
| Valid typed decomposition | Exact conserved quantities, known methods, canonical scoped children, stable dependency IDs and inherited allowances |
| Malformed, unsupported, fabricated resources | Exact fields, commands/primitive/foreign/budget attempts, integral/overflow quantities, unbound/duplicate nodes, unknown capabilities and insufficient stock |
| Authority, private/shared scope and revocation | Guessed proposal IDs denied, control revocation fences output; explicit template grants bind fresh scope and are rechecked pending reuse |
| Graph granularity and finite complexity | Node/created-work, depth, edge, output/context-byte and capture/validation work boundaries; memoized acyclic validation |
| Current applicability and freshness | Methods/control/stock changes refuse; stale captured evidence requires equivalent fresh current evidence; future timestamps refuse |
| Publication, replay and recovery | Real Jobs owner atomic generation, failed publication leaves no fragments; failed linkage preserves whole Jobs graph; fresh owners preserve spent allowance |
| Cancellation | Delayed output fenced, context/reservation cancellation, admitted objective subtree through Jobs, unrelated and completed partial children preserved |
| Cumulative budgets and deadlines | Two attempts exhaust durable candidate/input allowances, oversized context charged, queue/context time uses original deadline, restart cannot mint budget |
| Explicit model availability / offline execution | Missing model qualified, admitted known children complete through Jobs with disabled broker/backend |
| Selective reuse and persistence privacy | Only demonstrated normalized topology retained; fresh quantities/scope/methods/resources; strict checksummed journal, all interruption points, corrupt current/stage, unknown schema, locks, quotas and retention |

GP-19 uses the production composition with controlled Plan proposals and a test-admitted known P0 method. It separately exercises real planning cancellation/refusal/admission and physical known-child execution, then cold/warm saved-world reopening in different JVMs. It does not claim controlled outputs demonstrate model planning quality. The genuine held-out shared-backend benchmark is a separate acceptance job and evidence artifact.

## Local verification and environment qualification

Pinned Java 25 compilation of core/providers/Fabric production and all GameTest classes passed. The new deterministic matrix contains **59 executed passing cases**: GoalPlanner 43, PlanJournal 12, planning adapter 4. The full affected local suite executed **1,086 tests**, with **1,075 passing** and **11 existing SaveCompatibility space-probe failures** caused by the sandbox filesystem's unusable file-store/space information. The same `SPACE_UNAVAILABLE` migration guard blocks a local production KernelSession GameTest; neither production safeguards nor test expectations are weakened. Exact-head CI on a normal runner is required for full module and GP-19 acceptance.

CI is configured to require all matrix cases, complete module regressions, release packaging, two executed project cases in each native process, active physical/no-replay proofs and separate bounded genuine model measurements. Final CI, review, merge and fetched-main identifiers will replace this pending acceptance section before completion is recorded.
