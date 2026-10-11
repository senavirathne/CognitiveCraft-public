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

Pinned Java 25 compilation of core/providers/Fabric production and all GameTest classes passed. The deterministic matrix now contains **62 cases**: GoalPlanner 46, PlanJournal 12, planning adapter 4, including final review's context-cancellation/storage-failure/size accounting cases. An earlier full affected local suite executed **1,086 tests**, with **1,075 passing** and **11 existing SaveCompatibility space-probe failures** caused by the sandbox filesystem's unusable file-store/space information. The same `SPACE_UNAVAILABLE` migration guard blocks a local production KernelSession GameTest; neither production safeguards nor test expectations are weakened. Final exact-head CI on a normal runner supplied full module and GP-19 acceptance below.

CI required every matrix case, complete module regressions, release packaging, two executed project cases in each native process, active physical/no-replay proofs and separate genuine model measurements. Final executed results are recorded below.


## Source and actual driver bindings

The versioned core interfaces are `Plan.java`, `PlanningBudget.java`, `PlanningGoals.java`, `PlanValidator.java`, `GoalPlanner.java` and `PlanJournal.java`. Existing `Contracts.java`, `Generation.java`, `AIWorkBroker.java`, `DeterministicPrerequisiteResolver.java`, `RequestValidator.java`, `JobLifecycleStore.java` and `SaveCompatibility.java` consume the additive goal/plan boundary. Jobs remains the sole owner of work state, usage, dependencies, cancellation and actual execution credit.

Runtime composition lives in `fabric/.../FabricGoalPlanning.java` and `KernelSession.java`. The explicit driver APIs are `createPlanningObjective`, `proposePlan`, `planStatus`, `cancelPlanProposal`, `cancelPlanningObjective`, `retainPlanningTemplate`, `planningTemplates` and `reusePlanningTemplate`. The provider uses `LocalGenerationAdapter.java` with `Generation.Role.PLANNING`, `Plan.OUTPUT` (`goal-plan`, contract 1) and the Plan grammar on the existing shared backend. It does not lower Plan nodes into Skill IR.

`GoalPlannerTest` (46), `PlanJournalTest` (12) and `PlanningAdapterTest` (4) bind the eleven matrix rows above. Native cold binding is `PlanningGameTests.boundedPlanningPublishesOnceAndKnownChildrenFinishWithModelsDisabled`; warm binding is `PlanningRestartGameTests.admittedJobGraphAndDemonstratedTopologySurviveASecondServerJvm`. Each process also runs the existing `ServerRestartProbeGameTests` saved-Minecraft witness. The isolated workflow explicitly selects those registrations and phases, then restores the full registration.

Executed commands: the workflow first runs targeted `:core:test` for GoalPlanner/PlanJournal and `:providers:test` for PlanningAdapter; then `:core:test :providers:test :fabric:test :fabric:build :fabric:gameTestJar`. It runs `:fabric:runGameTest` once with planning/restart-probe phase `cold`, and again with phase `warm` against the same save in another JVM. The separate genuine benchmark is `:providers:runPlanningLiveBenchmark`. Exact bindings and strict XML/packaging/evidence checks are in [goal-planning.yml](../workflows/goal-planning.yml).

Pinned Java 25, Gradle 9.7, Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3, Loom 1.18.1 and JUnit 5.11.4 are unchanged.

## Final accounting review and native fixture correction

Final source review identified cancellation during context preparation as a possible uncharged retry. The planner now checks the available envelope before bounded routing, persists the complete conservative candidate/inference/work reservation through Jobs, waits for its acknowledgement, then queues context preparation. Cancelled, failed and oversized preparation retain that usage; an exhausted third attempt performs no further routing, capture or inference. The three final parametrized cases exercise those boundaries. The original deadline covers preparation and the shared queue, and reloaded owners cannot restore spent allowance. Actual broker usage remains separately observable from conservative reservation.

A paired goal receives two existing 1,200-tick dispatch windows in its initial 2,400-tick execution allowance, fixed before any proposal. Child allowances divide the parent's remaining limits and inherit its absolute deadline. No retry enlarges those limits.

Superseded [run 38115347762](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38115347762) at `d4180d2fa335a3e7481fc2186344c3128bc4ab93` stalled in source navigation after one actual delivery. The compact GP-19 fixture now puts five real mature crops and two separate chests within physical reach of a stationary villager, using the existing admitted harvest/pickup/transfer fixture method. Source navigation remains covered in its own regression workflows.

Superseded [run 38115881627](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38115881627) at `d0be2c400a40070e018a1799ec5276b8a630ad48` completed the first child's two deliveries but timed out in the second child while the driver accelerated ticks with 1 ms pacing. The final driver uses normal 20 Hz pacing so real off-thread journal acknowledgements receive their production share of the game-tick deadlines. The existing gateway, lease, dispatch and storage bounds and required physical receipt assertions are unchanged. These failed runs are excluded from acceptance evidence.

## Final code-head CI and physical evidence

Reviewed code head: **`bf259e27adad8f70d296a7f97905d8dd06f44629`**, [PR #16](https://github.com/senavirathne/CognitiveCraft-public/pull/16). Feature [run 38116300594](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38116300594), acceptance job **114401749692**, passed the exact matrix, full regressions, packaging and both native phases.

| Executed gate | Passing cases | Failures / errors / skipped |
|---|---:|---:|
| GoalPlanner matrix | 46 | 0 / 0 / 0 |
| PlanJournal recovery/retention matrix | 12 | 0 / 0 / 0 |
| Shared provider/planning boundary | 4 | 0 / 0 / 0 |
| Complete IMP-019 matrix | **62** | **0 / 0 / 0** |
| Core module, including feature cases | 997 | 0 / 0 / 0 |
| Providers module | 66 | 0 / 0 / 0 |
| Fabric unit module | 26 | 0 / 0 / 0 |
| All unit modules, without double-counting the matrix | **1,089** | **0 / 0 / 0** |
| Cold GP-19 and saved-Minecraft project witness | 2 | 0 / 0 / 0 |
| Warm GP-19 and saved-Minecraft project witness | 2 | 0 / 0 / 0 |

Each Minecraft process also executed one built-in control case: three total cases per process, six executions including four project cases. Cold and warm proof both retain actual destination stocks **2/3 wheat**, objective **`f9e5a1f1-7489-47bb-a9dc-fb83b6de2b12`** and children **`eac05028-9844-3732-a846-2349b07ab6db`**, **`c5bf6c53-177d-3eea-a8f7-247ccf92e999`**. Both children succeeded with attributable fulfillment 2 and 3; the aggregate root succeeded through Jobs. Planning performed zero physical effects and assigned no worker. The controlled cold phase made five proposal calls (delayed cancellation, three deterministic invalid alternates and one valid proposal), then disabled inference and the backend. Known execution made **0 model calls**. The second JVM made **0 proposal/model calls**, preserved those exact child identities, the demonstrated template, cancelled work and job count, and observed unchanged stock during quiet ticks. No uncertain effect was replayed.

Production JAR SHA-256: **`9f65f80efcf6be0a456aed2c7e3882522c12052005b2dea30abca09d68174d2c`**. The release gate required all six planning runtime classes and excluded GameTest classes and model weights.

| Retained artifact | Exact-head archive | SHA-256 archive digest |
|---|---|---|
| imp-019-planning-acceptance | [11693424138](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38116300594/artifacts/11693424138) | `14bbd73888de737de6e215fd22257479e5757d21ac57f012f8f6be0ed98222f2` |
| imp-019-held-out-planning-quality | [11693324328](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38116300594/artifacts/11693324328) | `c4746dc44afd1a8c58cf908e2ad727067d7e87944a1617be95a78a35fb94dc6f` |

Both artifact records identify the reviewed code head above. The acceptance archive retains strict unit XML, cold/warm XML/logs, saved-world `cold.json`/`warm.json`/`restart.json` and `acceptance.json`. The quality archive retains the genuine live log and its independently extracted result JSON.

All seventeen applicable pull-request workflow families passed on this exact code head:

| Workflow | Final code-head run | Result |
|---|---|---|
| AI work broker acceptance | [38116300553](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38116300553) | Success |
| Bounded goal planning acceptance | [38116300594](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38116300594) | Success |
| Capability retrieval acceptance | [38116300587](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38116300587) | Success |
| Citizen identity acceptance | [38116300573](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38116300573) | Success |
| CognitiveCraft verification | [38116300555](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38116300555) | Success |
| Default nearest physical acceptance | [38116300634](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38116300634) | Success |
| Deterministic prerequisite acceptance | [38116300539](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38116300539) | Success |
| Job lifecycle acceptance | [38116300557](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38116300557) | Success |
| Local generation smoke | [38116300540](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38116300540) | Success |
| Needle language acceptance | [38116300562](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38116300562) | Success |
| Observed world knowledge acceptance | [38116300595](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38116300595) | Success |
| Primitive diagnostics acceptance | [38116300542](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38116300542) | Success |
| Research local-model physical trial | [38116300588](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38116300588) | Success |
| Resource lease acceptance | [38116300535](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38116300535) | Success |
| Save and artifact compatibility acceptance | [38116300581](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38116300581) | Success |
| Worker dispatcher acceptance | [38116300541](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38116300541) | Success |
| World retention acceptance | [38116300605](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38116300605) | Success |

## Separate genuine held-out quality and latency

Quality job **114401749604** in the same exact-head feature run made three actual shared-backend inference calls using pinned Ollama 0.12.10 and its existing `qwen2.5:1.5b-instruct` smoke model. These are the frozen held-out quantity pairs, each attempted once. The current-method/resource/authority ports in this benchmark are bounded synthetic read-only evidence for typed semantic validation; the benchmark performs no native admission or effects. Native execution is established by the separate GP-19 fixture above.

Model digest: **`65ec06548149b04c096a120e4a6da9d4017ea809c91734ea5631e89f96ddc57b`**. All three calls identify that same model. Dedicated planner models: **0**; actual inference calls: **3**; repairs: **0**; native effects: **0**.

| Case | Requested destination quantities | Result | Latency (ms) | Input / output bytes | Prompt / output tokens |
|---|---|---|---:|---:|---:|
| 0 | 1 / 4 | Rejected: QUANTITY | 31,179 | 3781 / 358 | 886 / 123 |
| 1 | 3 / 2 | Valid typed decomposition | 28,830 | 3781 / 357 | 908 / 119 |
| 2 | 2 / 6 | Rejected: QUANTITY | 27,734 | 3781 / 357 | 882 / 119 |

Observed validity is **1/3 (33.3%)**. Two actual model outputs failed exact quantity conservation and were rejected. Latency ranged **27,734–31,179 ms**, with median **28,830 ms**. All requests/responses remained within their byte allowances. This small sample shows weak quantity-decomposition reliability for the selected smoke model; it does not establish general planning accuracy. The benchmark gate requires genuine bounded measurements and retains failures instead of substituting controlled outputs or selecting a favorable sample.

Candidate SHA-256 values, preserving the exact measured responses without embedding their text:

- Case 0: `099711ce93b6b02690df92dcb65ab0a3a078c476634cebc33053c79d245467c0`
- Case 1: `6fbbaeaec5b3a9cfcc61f34cb4c12dd4366112795e18e88bd86a1bb1022778f4`
- Case 2: `a00bb1dcb94bbe83739c97bc03bb3cdd42e9c833a1169043fc05b497ab4ac8da`

## Engineering review, authorized merge and freshly fetched main

Engineering review [**5482120281**](https://github.com/senavirathne/CognitiveCraft-public/pull/16#pullrequestreview-5482120281) is anchored to `bf259e27adad8f70d296a7f97905d8dd06f44629`. It covers the Plan/IR separation, recognized-goal routing, finite/context/quantity checks, current authority/resource/method fences, durable pre-capture accounting, atomic Jobs publication/replay, cancellation and partial-effect preservation, template privacy/grants, journal recovery/retention, native continuation/restart and packaging. This is an implementation-agent engineering review, not independent human approval. There were no unresolved inline threads or requested changes. Fresh policy checks found main unprotected and no repository rulesets; no protection was bypassed. The authorized merge used the established merge-commit convention with an expected-head guard.

| Source evidence | Exact identifier |
|---|---|
| Starting public main | `502bac704a44a21898d9292db07b02cd8525176b` |
| Final reviewed code head / PR | `bf259e27adad8f70d296a7f97905d8dd06f44629` / [#16](https://github.com/senavirathne/CognitiveCraft-public/pull/16) |
| Executed code merge, freshly fetched main | `fb0c6a09d71e2e17c8db89fe2c5d6e12870d50bc` |
| Reviewed code and merged complete tree | `da9b954c3396df9808e8745e43c2ac0315d7ffec` |
| Verified merge parents | `502bac704a44a21898d9292db07b02cd8525176b`, `bf259e27adad8f70d296a7f97905d8dd06f44629` |
| Completion-report branch | `work/imp-019-final-report`, based on that verified merge |

PR #16 is merged. Fresh authenticated repository reads of public main and its Git commit verified merge `fb0c6a09d71e2e17c8db89fe2c5d6e12870d50bc`, the exact parents above and the complete tree equal to the reviewed code tree. The identical Git object was verified locally by its SHA; `git diff --exit-code bf259e27adad8f70d296a7f97905d8dd06f44629 origin/main` returned success with an empty complete-tree diff. Source, tests, workflows and contracts match the reviewed head.

This report is published separately so it records executed code-merge identifiers. The follow-up changes only this report and preserves the verified executable source, tests, workflow and contract tree. Its own publication SHA is not self-referentially embedded; the publication PR and completion response record its applicable CI and final fetched-main proof.

## Completion scope and remaining limitations

IMP-019 is complete for the explicit recognized two-destination wheat-supply goal and eligible known crop-delivery skills. Plans remain bounded decomposition proposals; current authority/resources/methods are rechecked, and existing Jobs/Leases/dispatch/gateway own admitted effects. Pending proposals and bounded rejected scratch are disposable; acknowledged Jobs preserve responsibility and spent budget across restart. Production template sharing is private by default; the explicit policy seam and tests require current grants and fresh bindings for allowed shared use.

General crafting/building, arbitrary goals or primitives, compulsory planning, a dedicated planner model and worker-selection replacement remain outside this unit. The live smoke model's observed quality is limited as recorded above. Architecture 0.5 and the existing record schemas are preserved. The documentation repository is read-only, and unrelated PR #7 remains outside scope.
