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
| Compatible sharing/credit | Two compatible roots, incompatible destination/source/actor/scope/principal/accounting, one physical supply pool, internal unit intents, real terminal receipt credit and partial-receipt producer/demand ceiling |
| Stale/unloaded supply | Unknown, old, future, unloaded, denied, incomplete source; before-admission stock removal/arrival and identity/catalog/method changes |
| Replay/reload/cancel | Stable duplicate child IDs and budget/deadline, JobJournal reopen, cancelled producer retaining partial delivery; different Minecraft JVM retains producer/allocations and quiet stock |
| Current authority/grants | Scope loss, recursive authority loss, actual private Jobs/Leases checks, explicit existing control grant retaining origin, grant revocation fences replay |
| No inference | No model/planner/write port in pure expansion; actual retrieval index plus IMP-028 known-method selection; GP-18 throwing trial/model boundary and zero inference |

The feature workflow requires **43** expansion cases plus **19** Jobs/Leases/admission cases (**62 total**) to execute without failures/errors/skips, then all affected module regressions and production/GameTest builds. It requires two project cases in both cold and warm JVMs (GP-18 plus the actual saved-block restart witness), real stock/allocated totals **5/5**, stable producer/child count, no replay/inference/new primitives, and production packaging without test knowledge/model assets. Final CI head/run IDs and release hash are populated after those gates finish.

## Local validation and reproducibility

Java 25.0.4.1+1 and checksum-pinned `scripts/headless.py` compiled production and GameTest code. The SDK download SHA-256 was `dbb698396d478e7fa2b1e50f4103324b2a99b90569ee27c33f2261f9215cf41e`. Extraction initially left a truncated JDK module archive; rewriting the verified tar entry restored its exact length/hash and the same build then passed. This was a workspace runtime repair.

Local GP-18 passed in separate cold/warm Minecraft processes against the same save: **three cases per process**, including two project cases and Minecraft's control case. Actual evidence retained **5 wheat**, **5 allocated units**, **6 jobs**, one unchanged producer ID, **0 model calls**, **0 new primitives**, and no replay. The first physical-removal fixture removed only `harvest_next_wheat`; the still-registered `harvest_wheat` signature correctly preserved physical support. The fixture now removes every harvest-effect signature; the unchanged unsupported-runtime assertion passes.

The final **62-case** feature gate passed locally, including shared-diamond accounting, explicit grant/revocation coverage and durable charging of fresh duplicate expansion. The final full local run executed **1,026** cases: **1,015 passed**; eleven existing SaveCompatibility tests encounter `SPACE_UNAVAILABLE` because the restricted filesystem cannot supply usable-space information (`Mount point not found`). Those are not counted as passing. The dedicated workflow runs the unchanged full suite on the standard Java 25 runner before merge; final CI counts/results are recorded below once the standard-runner gates finish.

Commands/gates: targeted `:core:test --tests ...DeterministicPrerequisiteResolverTest --tests ...PrerequisiteAdmissionTest`; full `:core:test :providers:test :fabric:test :fabric:build :fabric:gameTestJar`; cold and warm `:fabric:runGameTest` with explicit prerequisite/restart phases; strict XML, saved-world conservation and release-content checks in `.github/workflows/prerequisites.yml`. Local headless unit output is isolated from production compilation to avoid the helper's output-directory replacement during concurrent builds. No assertion, compatibility rule or disk-space check was weakened.

## Source inventory

- Core: `Prerequisites`, `DeterministicPrerequisiteResolver`, `PrerequisiteAdmission`, `LeasePrerequisiteSupplies`; existing `JobLifecycleStore` gains compound admission/billing and bounded pair credit; `ResourceLeaseService` exposes evidence from its existing read-only inspection.
- Runtime: `FabricPrerequisiteCatalog`, `FabricPrerequisiteRuntime`, existing `KernelSession` composition/API.
- Tests: `DeterministicPrerequisiteResolverTest`, `PrerequisiteAdmissionTest`, native `PrerequisiteGameTests`/`PrerequisiteRestartGameTests`, full GameTest registration.
- Delivery: `.github/workflows/prerequisites.yml`, this report and versioned contract.

## CI fixture correction

Initial PR head `9a648ce1f363fc6f26bd1db1cf031705a3720f40` passed all 62 matrix cases and the unchanged full module tests on the standard runner. Cold native GP-18 in run [38102882993](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/38102882993) then blocked in the existing P0 physical execution with `ACTION_TIMEOUT`, seven committed effects and two completed delivery cycles. That failed run is not acceptance evidence.

The fixture enclosed the crop strip with blocks and height changes; trapped/drifting physical drops were a suspected pickup visibility/reach problem. The GP-18 witness now uses a flat farmland buffer and includes native custody/navigation/pickup tracing for a concrete failure diagnostic. Both cold/warm processes passed locally with the corrected fixture (six total cases, 5/5 actual stock/credit, unchanged producer and six Jobs). The production gateway's 200-tick action bound, supported production capability and required successful delivery/receipt assertions are unchanged. This is a gameplay-fixture correction; final-head CI must execute it successfully before merge.

## Final reviewed head and CI evidence

Pending final-head workflow completion, engineering review, authorized merge and fetched-main verification. IMP-018 is not claimed complete until all required gates pass and those identifiers are recorded.
