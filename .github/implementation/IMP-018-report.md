# IMP-018 implementation and verification evidence

IMP-018 expands bounded material/recipe/known-method prerequisites without inference, uses approved current stock before proposing production, and publishes children/dependencies/usage and actual receipt credit through the existing Jobs/Leases owners. There are no new crafting effects, planners or worker-selection services.

Starting public main: **`5d31cd5ac6d78d4c41d114446135ba2655c14da6`**, the verified IMP-017 evidence publication. The initial audit inspected all 14 remote branches, open/draft PRs and 12 recent merged PRs; no prior IMP-018 branch/implementation existed. The unrelated open PR #7 was left unchanged. Dependencies IMP-010/011/012/028 are present in the live public implementation. Historical documentation gaps are reconciled against that live state. The documentation repository is read-only; this implementation and its contracts/evidence are in the public implementation repository.

Normative documentation was routed through `AGENT_DOCUMENTATION_INDEX.md` at documentation commit **`b1d5bf71365d1b1762699c006465caa1db8e5be5`**: the implementation registry/dependencies, gaps and architecture invariants, shared execution contract plus the exact IMP-018 prompt/backlog entry, architecture-contract resolution/reason/resource/future boundaries, and GP18 gameplay instructions. Current architecture compatibility is **0.5**, prerequisite contract **1**, and existing Contracts/IR/Jobs/Leases/record schemas remain **1**. The user's explicit completion-and-merge instruction authorizes publication beyond the historical uncommitted-delivery wording.

## Implementation and ownership

See `IMP-018-contracts.md` for the exact interfaces, finite bounds, quantity/coalescing rules and refusal/recovery semantics. The pure graph engine takes immutable source snapshots, explicit current stock/access/known-method ports and a cumulative parent Ledger. The runtime checks actual caller authority before inspecting private supply and performs expansion plus authoritative admission/billing in one operation. Jobs owns admitted work, stable children, usage, cancellation, retries and receipt allocation; leases owns current conflicts and effect-time claims. Proposals are disposable scratch.

The native recipe adapter reads at most eight exact selected installed recipe keys. The executable registered mapping is the existing attributable wheat-delivery capability. The installed bread recipe demonstrates that recipe data alone cannot provide crafting physics. Generic recipe batch/remainder/cycle/shared-subgraph cases use explicit fake registered-production fixtures, without claiming real Minecraft crafting. Real new crafting is optional only when a tested physical capability already exists; P0 supplies none, so it remains explicitly unsupported.

Existing stock satisfies a material need, never attributable crop-delivery completion. Root delivery credit comes only from actual job attempts or existing Jobs allocations against terminal deposit receipts. Source/destination/actor/principal/domain/allocation differences prevent producer sharing; every current read/control grant is rechecked and revocation fences replay.

## Required acceptance bindings

| IMP-018 matrix row | Executed binding/gate |
|---|---|
| Existing supply before production | Quantities 1/8/64, partial-stock exact deficit, material stock never completes a delivery job; GP-18 two-stock/five-needed leaves three |
| Recipe batches/remainders | Exact integral batches, surplus and returned bowls; separate output/input/remainder overflow and nonintegral rejection |
| Unsupported physical capability | Installed recipe with no production mapping; actual native bread recipe; remove every registered harvest-effect signature while recipe data remains |
| Cycles and bounds | A→B→A, shared diamond, later actual Jobs cycle rejection; exact N and N+1 nodes/depth/edges/alternatives/work/context/quantity |
| Compatible sharing/credit | Two compatible roots, incompatible destination/source/actor/scope/principal/accounting, changed-cohort canonical-owner fencing, one physical supply pool, internal unit intents, real terminal receipt credit and partial-receipt producer/demand ceiling |
| Stale/unloaded supply | Unknown, old, future, unloaded, denied, incomplete source; before-admission stock removal/arrival and identity/catalog/method changes |
| Replay/reload/cancel | Stable duplicate child IDs and budget/deadline, JobJournal reopen, cancelled producer retaining partial delivery; different Minecraft JVM retains producer/allocations and quiet stock |
| Current authority/grants | Scope loss, recursive authority loss, actual private Jobs/Leases checks, explicit existing control grant retaining origin, grant revocation fences replay |
| No inference | No model/planner/write port in pure expansion; actual retrieval index plus IMP-028 known-method selection; GP-18 throwing trial/model boundary and zero inference |

The feature workflow requires **43** expansion cases plus **20** Jobs/Leases/admission cases (**63 total**) to execute without failures/errors/skips, then all affected module regressions and production/GameTest builds. It requires two project cases in both cold and warm JVMs (GP-18 plus the actual saved-block restart witness), real stock/allocated totals **5/5**, stable producer/child count, no replay/inference/new primitives, and production packaging without test knowledge/model assets. The final CI head, run IDs and release hash are recorded below.

## Local validation and reproducibility

Java 25.0.4.1+1 and checksum-pinned `scripts/headless.py` compiled production and GameTest code. The SDK download SHA-256 was `dbb698396d478e7fa2b1e50f4103324b2a99b90569ee27c33f2261f9215cf41e`. Extraction initially left a truncated JDK module archive; rewriting the verified tar entry restored its exact length/hash and the same build then passed. This was a workspace runtime repair.

Local GP-18 passed in separate cold/warm Minecraft processes against the same save: **three cases per process**, including two project cases and Minecraft's control case. Actual evidence retained **5 wheat**, **5 allocated units**, **6 jobs**, one unchanged producer ID, **0 model calls**, **0 new primitives**, and no replay. The first physical-removal fixture removed only `harvest_next_wheat`; the still-registered `harvest_wheat` signature correctly preserved physical support. The fixture now removes every harvest-effect signature; the unchanged unsupported-runtime assertion passes.

The final **63-case** feature gate passed locally, including shared-diamond accounting, changed-cohort canonical-owner fencing, explicit grant/revocation coverage and durable charging of fresh duplicate expansion. The final full local run executed **1,027** cases: **1,016 passed**; eleven existing SaveCompatibility tests encounter `SPACE_UNAVAILABLE` because the restricted filesystem cannot supply usable-space information (`Mount point not found`). Those are not counted as passing. The unchanged full suite passed on the standard Java 25 runner at the final reviewed head; the executed CI counts/results are recorded below.

Commands/gates: targeted `:core:test --tests ...DeterministicPrerequisiteResolverTest --tests ...PrerequisiteAdmissionTest`; full `:core:test :providers:test :fabric:test :fabric:build :fabric:gameTestJar`; cold and warm `:fabric:runGameTest` with explicit prerequisite/restart phases; strict XML, saved-world conservation and release-content checks in `.github/workflows/prerequisites.yml`. Local headless unit output is isolated from production compilation to avoid the helper's output-directory replacement during concurrent builds. No assertion, compatibility rule or disk-space check was weakened.

## Source inventory

- Core: `Prerequisites`, `DeterministicPrerequisiteResolver`, `PrerequisiteAdmission`, `LeasePrerequisiteSupplies`; existing `JobLifecycleStore` gains compound admission/billing and bounded pair credit; `ResourceLeaseService` exposes evidence from its existing read-only inspection.
- Runtime: `FabricPrerequisiteCatalog`, `FabricPrerequisiteRuntime`, existing `KernelSession` composition/API.
- Tests: `DeterministicPrerequisiteResolverTest`, `PrerequisiteAdmissionTest`, native `PrerequisiteGameTests`/`PrerequisiteRestartGameTests`, full GameTest registration.
- Delivery: `.github/workflows/prerequisites.yml`, this report and versioned contract.

## CI fixture correction

Initial PR head `9a648ce1f363fc6f26bd1db1cf031705a3720f40` passed all 62 matrix cases and the unchanged full module tests on the standard runner. Cold native GP-18 in run [38102882993](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38102882993) then blocked in the existing P0 physical execution with `ACTION_TIMEOUT`, seven committed effects and two completed delivery cycles. That failed run is not acceptance evidence.

The fixture enclosed the crop strip with blocks and height changes; trapped/drifting physical drops were a suspected pickup visibility/reach problem. The GP-18 witness now uses a flat farmland buffer and includes native custody/navigation/pickup tracing for a concrete failure diagnostic. Both cold/warm processes passed locally with the corrected fixture (six total cases, 5/5 actual stock/credit, unchanged producer and six Jobs). The production gateway's 200-tick action bound, supported production capability and required successful delivery/receipt assertions are unchanged. This is a gameplay-fixture correction; final-head CI executed both phases successfully before merge.

## Final reviewed head and CI evidence

Final source review found that a consumer could already depend on a shared producer owned by a root omitted from a new cohort. Adding a lower-ID root changed canonical ownership and could otherwise create another producer for the same consumer and bindings. Jobs now checks equivalent work through every requested root's existing children and dependencies before publishing a new producer. The regression fixes UUID ordering, verifies the old owner is absent, and requires a stale refusal with no extra Job, child or replaced dependency. This uses the existing bounded Jobs graph; it adds no ledger or recovery state.

Reviewed final code head: **`3dae87ca12247d497b8cd6edd924a3c9212f97e2`**, [PR #14](https://github.com/senavirathne/CognitiveCraft-public/pull/14). Feature [run 38103907701](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38103907701), job **114365324619**, executed the exact prerequisite matrix first, all affected module regressions, production/GameTest packaging and native GP-18 in two separate Minecraft JVMs against the same save.

| Executed gate | Passing cases | Failures / errors / skipped |
|---|---:|---:|
| Deterministic expansion/recipe matrix | 43 | 0 / 0 / 0 |
| Jobs/Leases/admission matrix | 20 | 0 / 0 / 0 |
| Complete IMP-018 matrix | **63** | **0 / 0 / 0** |
| Core module, including those 63 feature cases | 939 | 0 / 0 / 0 |
| Providers module | 62 | 0 / 0 / 0 |
| Fabric unit module | 26 | 0 / 0 / 0 |
| All unit modules, without double-counting the matrix | **1,027** | **0 / 0 / 0** |
| Cold GP-18 plus saved-world project witness | 2 | 0 / 0 / 0 |
| Warm GP-18 plus saved-world project witness | 2 | 0 / 0 / 0 |

Each Minecraft process also executed one built-in control case: three total game cases per process, six executions including four project cases. The workflow rejects missing, failed, errored or skipped cases. Cold and warm proofs both retain **5 wheat**, **5 allocated units**, **6 Jobs**, producer **`ca8f76e3-3fae-32fe-871c-76f9f7b76956`**, **0 model calls** and **0 new primitives**. Their producer identities/Job counts match. The saved-world test requires a different JVM/process witness, preserved responsibility and actual partial-credit records, inactive recovered leases and unchanged stock during quiet ticks; cancelled work is refused and does not replay. The production Kernel composition and existing command, identity, gateway, Jobs/Leases, compatibility, retention, dispatch, observation and research regressions passed in their final-head workflows.

Production JAR SHA-256: **`49a63333892276993b19afb48c19670c86903f4ecf87ae5b9cb37941de4134d3`**. The `imp-018-prerequisites` artifact **11688473810** retains strict unit XML, cold/warm logs/XML, saved-world proofs and `acceptance.json`. Archive digest: **`4da2466cb788daba22ecbc0ba722c58848d84b6d0a80f9f57587d7e45cd607db`**. Its workflow metadata identifies exact head `3dae87ca12247d497b8cd6edd924a3c9212f97e2`. Release checks require the prerequisite runtime classes and exclude GameTest code/model weights.

All sixteen applicable pull-request workflow families completed successfully on this exact code head. Historical, failed or superseded runs are excluded from acceptance.

| Workflow | Final code-head run | Result |
|---|---|---|
| AI work broker acceptance | [38103907726](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38103907726) | Success |
| Capability retrieval acceptance | [38103907706](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38103907706) | Success |
| Citizen identity acceptance | [38103907776](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38103907776) | Success |
| CognitiveCraft verification | [38103907704](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38103907704) | Success |
| Default nearest physical acceptance | [38103907731](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38103907731) | Success |
| Deterministic prerequisite acceptance | [38103907701](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38103907701) | Success |
| Job lifecycle acceptance | [38103907719](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38103907719) | Success |
| Local generation smoke | [38103907693](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38103907693) | Success |
| Needle language acceptance | [38103907718](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38103907718) | Success |
| Observed world knowledge acceptance | [38103907727](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38103907727) | Success |
| Primitive diagnostics acceptance | [38103907737](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38103907737) | Success |
| Research local-model physical trial | [38103907739](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38103907739) | Success |
| Resource lease acceptance | [38103907700](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38103907700) | Success |
| Save and artifact compatibility acceptance | [38103907724](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38103907724) | Success |
| Worker dispatcher acceptance | [38103907723](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38103907723) | Success |
| World retention acceptance | [38103907740](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38103907740) | Success |

Engineering review [**5481578713**](https://github.com/senavirathne/CognitiveCraft-public/pull/14#pullrequestreview-5481578713) is anchored to `3dae87ca12247d497b8cd6edd924a3c9212f97e2`. It reviewed deterministic bounds/arithmetic/cycles, source pooling and compatible sharing, current authority and observation/method fences, parent work/deadline charging, changed-cohort producer duplication, actual receipt conservation, persistence/restart and packaging. This is an implementation-agent engineering review, not independent human approval. There were no unresolved review threads or requested changes. Main had no branch protection or repository rulesets to bypass. The authorized merge used the established merge-commit convention and an expected-head guard.

## Delivered source and fetched-main proof

| Evidence | Exact identifier |
|---|---|
| Starting public main | `5d31cd5ac6d78d4c41d114446135ba2655c14da6` |
| Final reviewed code head / PR | `3dae87ca12247d497b8cd6edd924a3c9212f97e2` / [#14](https://github.com/senavirathne/CognitiveCraft-public/pull/14) |
| IMP-018 code merge, fetched public main | `d68b314aa85e7c9806c97f524c9a96ba3ed562cb` |
| Reviewed code and merged complete tree | `b7814d78856db98673c6a134d4b2d8278fe03fbf` |
| Merge parents | `5d31cd5ac6d78d4c41d114446135ba2655c14da6`, `3dae87ca12247d497b8cd6edd924a3c9212f97e2` |
| Completion-report branch | `work/imp-018-final-report`, based on the verified code merge |

PR #14 is merged. A fresh `git fetch origin main` and `git diff --exit-code 3dae87ca12247d497b8cd6edd924a3c9212f97e2 origin/main` returned success with an empty complete-tree diff at `d68b314aa85e7c9806c97f524c9a96ba3ed562cb`; the fetched merge parents match the table. Source, tests, workflows and contracts were identical to the reviewed head.

This completed report is published separately so it can record the executed merge identifiers. The follow-up changes only this report. Its own publication SHA is not self-referentially embedded here; the publication PR and completion response record its applicable CI and final fetched-main verification.

## Scope and remaining limitations

IMP-018 is complete. Current native executable prerequisites remain the tested attributable wheat-delivery capability. Installed recipe data does not add crafting physics; unsupported crafting and non-wheat/future receipt accounting remain explicit refusals. Graph/recipe/remainder fixtures test finite proposal semantics without claiming generic native crafting. Scratch proposals cannot authorize execution after reload, and bounded unknown/stale/unloaded/denied supply does not become zero stock. Changed responsibility or conflicting existing work is refused rather than replanned.

Architecture 0.5 and all existing saved record schemas remain unchanged. No documentation-repository modification, new crafting primitive, planner, worker-selection service or IMP-019 work is included. The unrelated PR #7 remains outside scope.
