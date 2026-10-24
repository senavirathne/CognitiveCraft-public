# Independent physical world generalization campaign

Frozen production baseline: `ea7de8000b2b484b885576c34ed5358274936ce9`.
Production tree: `f0915a127521cb32f45e606c39098011796d1f1d`.
Documentation baseline: `d80f1db443a2493b3e422f435e069a7863076b07`.
Source branch: `test/generalization-world`; documentation branch: `docs/generalization-world`.

**Mission incomplete.** Generated definitions and harness checks are not Minecraft gameplay evidence. Original blocked attempts remain unchanged in `local-attempt*.json`. Actions results must be read independently of those historical local attempts. Production code, production dependencies, original fixtures, other agents' work, mandatory CI, and main remain unchanged.

## Controlled Actions execution

On 24 October 2026 the user authorized a branch-only push trigger to replace the original manual-only launch restriction. `.github/workflows/generalization-world.yml` accepts a push only on `test/generalization-world`, only when `e2e/campaigns/world/launch.json` changes, and only when the head commit contains `[world-campaign]`. Ordinary development pushes do not launch the campaign. Existing `workflow_dispatch` remains available after GitHub registration.

Edit `launch.json` to set the full/smoke mode, seed, families, budget and unique launch identity. Publish one prepared batch with the explicit marker. No browser authentication or default-branch change is required for this trigger. No documentation-repository Actions are added. Existing source CI may also run on the branch; these runs are supporting project checks, not campaign gameplay.

The workflow uses one job and no matrix. It preserves the requested corpus before the build, builds the mod once, prepares official assets once, and compiles existing observers/drivers once. One server and one connected client are reused across compatible cases. Genuine model admission happens once per resource family, then the model service is stopped. A failed resource admission is retained; it does not erase other resources' trials. Restart cases replace the server process and require the original client process to survive. One consolidated evidence artifact is uploaded even after failure. A setup failure produces an explicit blocked record for every requested case.

## Local reproduction on a Docker-capable host

```bash
bash gradlew --no-daemon :fabric:build
python3 e2e/runtime.py prepare
python3 e2e/runtime.py compile
python3 e2e/campaigns/world/runner.py --root build/e2e/world-smoke --mode smoke
python3 e2e/campaigns/world/runner.py --root build/e2e/world-full --mode full --seed 104729
```

Selection options: `--families W1,W2`, `--budget 24`, `--case W2-003`. Use a fresh evidence root for every attempt. Full mode requests 12 cases per family plus 12 from the subsequent held-out seed, for 144 cases. Generator and assertion hashes are frozen before held-out generation. Every held-out reproduction command retains the original campaign seed and full case ID. Smoke selects one case per family. `--generate-only` produces definitions without claiming execution.

A deterministic greedy design selects combinations that cover previously unseen pairs among item, quantity, geometry, container, terrain and orientation. It does not claim exhaustive pairwise coverage in twelve samples. Every neighboring base design changes at least three independent factors. Coordinates, actor start, destination slots, actor occupancy, crop maturity distractors and disturbance/lifecycle modes vary independently of original fixtures. The observed platform is relocated to x/z 64 and foot height 208.

## Abstract families and observations

| Family | Generated behavior | Independent physical checks |
|---|---|---|
| W1 | Acquisition/delivery through flat terrain, detours and corridors | Actual actor positions during execution, block effects and destination transfer |
| W2 | Exact oak, birch and mature wheat; line, separated and vertical arrangements; log axes; immature crops and unrelated material | Native source mutations, preserved distractors, physical custody and attributable receipts |
| W3 | Pickup with empty, six-, seven- and eight-slot actor inventories | Native inventory occupancy precondition; all actor/container/drop/player custody; partial or safe refusal outcomes |
| W4 | Chest/barrel delivery with empty, mergeable, exact and exhausted capacity | Initial slot contents, actual destination delta and synchronized connected-client container menu |
| W5 | Naming, fresh enrollment on four variations per seed, reconnect, native unload/reload and same-save restart | Real entity UUID, durable citizen identity/name/owner, native lifecycle events and generation, connected roster |
| W6 | Cancellation, actual player resource removal, gate closure, and crashes after witnessed HARVEST/PICKUP/DEPOSIT stages | Disturbance occurrence, actual server termination, durable save files, same client process, no replay and truthful interruption |

W1–W4 are overlapping physical property views of the same production acquisition/delivery pipeline. They do not pretend to be four new player actions. W5 fresh subjects are bounded to stay below the production roster's sixteen-result page. Correct refusal is counted separately from successful delivery. A failed model admission, missed dynamic boundary, incomplete observation or cleanup failure is never promoted to a gameplay pass.

`oracle.py` counts the tested resource across actual containers, actors, dropped entities and independently observed player inventories. Each harvested ordinary log or mature wheat plant creates one tested item under the fixture's unmodified vanilla drop rules; seed byproducts are outside that item's ledger. Existing stock grants no harvest credit. Receipt checks require actor, entity, run and destination identity, unique receipt IDs, per-batch HARVEST ≥ PICKUP ≥ DEPOSIT, physical harvest attribution and actual deposit delta. Receipts alone cannot prove success.

Each reached case preserves parameters, exact provenance and production JAR hash, before/after or failure snapshots, submitted instruction, run outcome, ordered physical observations, lease views, raw server/client events, native frame references, expected/actual properties, and replay command. Crash cases additionally preserve durable checkpoint data and same-save hashes. Original failures survive later runs. Summary rates retain separate requested, attempted, genuine-execution and property-evaluation denominators.

## Inventory and explicit remaining limitations

`inventory.json` retains all 51 registered primitive signatures and 13 cross-cutting/legacy capabilities, including Agent 2's recipe/placement scope. The inventory generator inspects the registered production signatures, and its source references lead to the real gateway, Fabric adapter, lifecycle and legacy controllers. Withdrawing logs for recipes belongs to Agent 2's primary recipe scope. Shared movement and custody boundaries remain visible for reconciliation.

A candidate family request does **not** prove that a particular primitive opcode ran: admitted methods can select alternate primitive paths, and existing public status/trace logs do not expose every primitive start and completion. Coverage reconciliation therefore gives no automatic primitive credit. Native effects and family results are reported separately. Zero genuine runs means untested.

The following gaps still prevent mission completion even if all currently generated cases pass:

- Legacy FarmPort planting/replanting, loose seed/wheat collection, bread delivery, legacy enrollment/auto/stop, and conditional provider-plan home movement require dedicated reusable adapters. Legacy bread transformation is primarily Agent 2's scope.
- Nearest-reference resolution requires a real interpreter, competing eligible/ineligible targets and a dedicated selected-target oracle; explicit-coordinate requests do not test it.
- Two simultaneous owners' resource lease contention and foreign citizen control require on-demand client-b, independent scope/lease observations, and any necessary genuine method admission for that principal. The current batch does not launch client-b or simulate a second player.
- Native citizen death/removal, unsupported replacement, authority changes not exposed by a player command, moving drops, and specific partial pickup geometries retain explicit coverage limitations.
- Admission errors, missed checkpoint stages and absent primitive invocation witnesses need evidence-based follow-up. Recovery success requires actual saved-world execution, not a mocked result.
- No production root cause or minimized counterexample is confirmed solely by this source audit. Reports must distinguish observed facts from hypotheses and retain first attempts.

The supporting campaign checks validate deterministic replay, observation bounds, multi-factor changes, held-out selection, complete custody accounting and refusal of phantom completion. The existing Python harness suite is also checked. These are not gameplay executions.
