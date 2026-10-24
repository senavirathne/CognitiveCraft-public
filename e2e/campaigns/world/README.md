# Physical world generalization campaign — work in progress

The campaign is **INCOMPLETE**. No production behavior was modified. No real Minecraft case has run in this environment. The generated corpus is not a gameplay result.

Frozen source baseline: `ea7de8000b2b484b885576c34ed5358274936ce9`.
Frozen source tree: `f0915a127521cb32f45e606c39098011796d1f1d`.
Documentation baseline: `d80f1db443a2493b3e422f435e069a7863076b07`.

## Run

From the source checkout, with Docker Compose, Java 25 and the existing Minecraft runtime dependencies:

```bash
bash gradlew --no-daemon :fabric:build
python3 e2e/runtime.py prepare
python3 e2e/runtime.py compile
python3 e2e/campaigns/world/runner.py --root build/e2e/world-smoke --mode smoke
python3 e2e/campaigns/world/runner.py --root build/e2e/world-full --mode full --seed 104729
```

The manual `generalization-world.yml` workflow builds once, prepares assets once, compiles instruments once and calls one batch. There is one job and no matrix. One server and one connected PlayerA client are reused; same-save recovery restarts the server and reconnects the existing client process. An Ollama service is required once for genuine independent admissions of wheat, oak and birch methods, then stopped before measured cases. No Needle installation or client-b is needed by the currently implemented adapters. No automatic trigger was added or existing CI weakened.

Selection: `--families W1,W2`, `--budget 24`, or `--case W2-003`. Use a new root for every original attempt. Reproduction commands are written per case. `--generate-only` writes the corpus without claiming execution. Full mode freezes generator/assertion file hashes first, then includes twelve cases per family from the requested seed plus twelve from the next held-out seed, **144 requested cases**. Smoke selects six. The cyclic deterministic design changes multiple parameters together; exhaustive pairwise coverage is not claimed.

## Implemented adapters

W1–W4 reuse a parameterized acquisition/delivery transaction for wheat, exact oak logs and exact birch logs. They vary quantity, positions, geometry, log orientation, actor start, obstacle detours, chest/barrel, occupancy and initial destination stock. These are overlapping end-to-end property views, not four independently implemented world actions. W5 currently tests durable renaming of the original enrolled citizen. W6 attempts cancellation, actual connected-player block removal and saved-world server termination. A missed disturbance is inconclusive, never a gameplay pass.

Assertions compare block mutation, native inventory/drop totals, destination delta, actual actor displacement, receipt identity/uniqueness/pickup lineage, normal connected multiplayer state and inference counters. Raw observer snapshots and logs are retained in the consolidated runtime root; each case has parameters, provenance, before/after or failure state, run/request, events, client events, server log excerpt, frame references, property results and replay command when reached. Blocked cases retain parameters, result, provenance limitation and replay command.

## Explicit gaps preventing mission completion

- The inventory is an initial implementation audit: 51 registered primitives plus 13 cross-cutting/legacy capability rows. See `inventory.json`; static inventory is not coverage.
- Fresh per-case enrollment, same-entity native unload/reload, death/removal and unsupported replacement are not implemented by the new W5 generator.
- Nearest-reference and natural-language resolution require a real interpreter and dedicated target-selection assertions. Typed explicit-coordinate commands do not test them.
- Legacy FarmPort planting/replanting, loose seed/wheat collection, home movement, bread delivery and legacy enrollment/auto/stop need their own reusable gameplay adapters. Bread transformation is primarily Agent 2's responsibility.
- Two-principal authority and lease contention require on-demand client-b and native lease assertions. Single-player interference does not test simultaneous competing authority.
- Actor inventory occupancy, partial pickup, moving drops, immature/depleted fields, full/refilled containers, navigation changes during execution and durable acquisition/delivery phase-specific crash boundaries need additional cases and independent observations.
- Recipe and crafting-table placement primitives are retained in the full inventory as Agent 2 primary ownership, with physical-transfer reconciliation open. No other agent's unmerged work is consumed.
- W6 recovery currently captures an observed executing boundary and saved-world hashes; it does not yet independently guarantee a specific acquisition/pickup/deposit checkpoint. Recovery and safety completion rates must remain unevaluated until those assertions are added.
- No confirmed gameplay root cause, minimized counterexample or reproduction consistency can be reported without actual execution.

## Local validation and outcome

Three corpus checks passed: deterministic replay, supported/bounded fixtures and changes to at least three parameters between neighboring variations. Python compilation passed. These are supporting checks only.

The first attempted batch selected 72 cases before held-out support was added. The second selected 144. Both stopped at the Docker preflight; the executable is unavailable. For the retained full attempt: **144 requested / 0 gameplay executions / 144 INFRASTRUCTURE_BLOCKED / 0 PASS / 0 gameplay failures measured**. Every physical property has evaluated denominator zero; rates are undefined, not 0% or 100%. `local-attempt.json` preserves that attempt. No JAR or model identity exists for an unstarted runtime.

The current GitHub connector exposes repository changes and workflow reads/retries, but no new-workflow dispatch operation. The workflow has not been invoked. This new manual-only workflow may also require presence on the default branch before GitHub offers dispatch; no merge is authorized by this campaign. Production CI and workflow registration must be resolved without adding a push trigger or automatically merging these branches.
