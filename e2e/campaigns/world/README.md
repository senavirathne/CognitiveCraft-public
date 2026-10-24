# Independent physical world generalization campaign

Frozen production baseline: `ea7de8000b2b484b885576c34ed5358274936ce9`.
Production tree: `f0915a127521cb32f45e606c39098011796d1f1d`.
Documentation baseline: `d80f1db443a2493b3e422f435e069a7863076b07`.
Source branch: `test/generalization-world`; documentation branch: `docs/generalization-world`.

Production code is frozen. Original attempts and raw verdicts remain immutable; harness qualifications and later retries are reported separately. Generated definitions, compilation and unit tests never count as Minecraft gameplay. Unexecuted capabilities remain unverified.

## Actions and launch control

On 24 October 2026 the user authorized a branch-specific push trigger as an exception to the original manual-only launch requirement. The workflow accepts only `test/generalization-world`, a change to `e2e/campaigns/world/launch.json`, and a head commit containing `[world-campaign]`. Main is untouched. `workflow_dispatch` is retained for eventual GitHub registration. The GitHub plugin has no dispatch endpoint, and manual registration on a branch is insufficient until GitHub discovers the workflow on the default branch. The authorized launch marker avoids changing the default branch or requiring browser authentication.

One job, no matrix: build the production mod once, prepare official Minecraft/Fabric assets once, compile shared observers/client drivers plus the agent-owned native respawn instrument once. Reuse one server and one real connected client across compatible cases. Restart only at actual lifecycle/recovery/isolation boundaries or an explicitly recorded infrastructure failure. Launch client-b on demand only for simultaneous-principal scenarios; stop it afterwards. Generate missing resource methods once through genuine model admission, retain failed admissions, stop the backend during measured known-method executions. No simulated primitive outcomes or private-method calls supply gameplay effects.

The [283-case follow-up](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/40085523505) is frozen at source `526c183ea65523744fc7997737790868253fb04b`: 96 W7–W10 base/held-out variations, 72 new W1–W6 held-out variations, 106 original failed/inconclusive replays, and nine minimization comparisons. Campaign seed `104729`; held-out seed `15485863`. Its current outcomes are not claimed here.

The next consolidated launch requests **72 cases**, with twelve base and twelve new held-out variations each for W7, W11 and W12. Held-out seed `32452843` is chosen before any gameplay feedback. W11 covers actual legacy group admission and physical acquisition; W12 covers actual damage/trading safety boundaries. The W7 fixture now derives its strict nearest relationship from the source-area anchor, correcting the pre-result qualification in `followup-qualifications.json`. Legacy stop/release acknowledgements are mandatory before any fixture reset, with a supporting failure-propagation check. This is one complete batch for remaining coverage gaps, not a workflow run per scenario.

Generators, assertions, adapters and replay definitions are hashed before held-out generation in Actions. New held-out gameplay outcomes were unavailable during harness development. The first run's seed `104730` remains in original replay definitions. A 300-minute gameplay watchdog and 360-minute job limit preserve partial evidence if exhausted.

## First execution: raw results

[Actions run 40076986301](https://github.com/senavirathne/CognitiveCraft-public/actions/runs/40076986301) ran source `1ae31f09114e41edb61f41a9dede1b0a040b5269` against the frozen production tree. Requested 144; attempted 56; genuine gameplay 54. Raw verdicts: 32 PASS, 14 FAIL_GENERALIZATION, six EXPECTED_SAFE_REFUSAL, 92 INCONCLUSIVE. W1–W4 each had 12 genuine runs; W5 had six; W6 and all held-out cases were untested after client/fixture failures. Safe refusals are not successful generalization.

Conservation and truthful completion were 48/48 evaluated pipeline cases. Navigation was 47/48, physical delivery 30/48, harvest 32/48. Among positive pipeline requests, 30/42 genuine executions completed delivery; 84 positive cases were requested. Identity was 4/4 evaluated. Recovery and cancellation had zero evaluated cases. Seven existing project CI workflows also passed at that source head; they do not supply campaign gameplay credit.

The complete original artifact `12555009116` is 2,892,448,574 bytes, SHA-256 `e13956b88af83f6c6102260457f032a59384bcc588de538a91081bd433ad9df5`. The connector's 512 MB download cap blocked direct local retrieval. `extract.py` reads the ZIP central directory and selected members through bounded HTTPS ranges inside Actions. The GitHub token is sent only to the GitHub API, never forwarded to signed artifact storage. Per-member CRC and SHA-256 are checked; the whole archive digest is recorded from GitHub, not falsely claimed to have been recomputed. The original archive remains untouched. The next single artifact includes the selected original evidence and all new evidence, with an explicit exclusion manifest for caches/native memory dumps.

## Reproduction on a Docker-capable host

```bash
bash gradlew --no-daemon :fabric:build
python3 e2e/runtime.py prepare
python3 scripts/campaign-world/compile.py
python3 e2e/campaigns/world/runner.py --root build/e2e/world-smoke --mode smoke
python3 e2e/campaigns/world/runner.py --root build/e2e/world-full --mode full --seed 104729
```

Default full mode generates 12 base and 12 held-out variations for each of twelve families (288). Options include `--families W1,W2`, `--budget 24`, `--case heldout-W2-003`, `--heldout-seed 15485863`, `--extra-heldout-families W1,W2`, `--replay-original`, and `--reuse-world build/e2e/previous-world`. `launch.py plan/run/report` executes the exact consolidated launch configuration. Use a fresh evidence root. Generate-only mode is not execution evidence. Each case includes its exact replay command; original-method reproduction requires the selected original artifact and `--reuse-world`.

`seed.py` accepts only the exact first-run source/artifact provenance and a semantically identical production JAR. It copies intact native save files, verifies their hashes, reconnects the real original principal/citizen, and verifies original admitted method hashes. It never manufactures skills, receipts, identity continuity or admission results. A restore failure blocks the batch with evidence rather than silently generating replacement behavior.

## Reusable abstract families

| Family | Generated behavior | Required physical witnesses |
|---|---|---|
| W1 | Resource/container approach through flat terrain, corridors and detours | Native actor positions, actual block effects and transfers |
| W2 | Exact oak/birch logs and mature wheat; line/separated/vertical sources, axes, crop distractors | Mutations, preserved immature/unrelated blocks, drops and lineage |
| W3 | Pickup/custody with zero, six, seven or eight occupied actor slots | Initial occupancy and all actor/container/drop/player custody |
| W4 | Chest/barrel delivery; empty, mergeable, exact and full capacity | Native destination delta, actual connected container menu |
| W5 | Fresh enrollment/naming, reconnect, native chunk unload/reload, same-save restart | Original UUID, scoped citizen identity, native lifecycle generation and client roster |
| W6 | Cancellation, player source removal, gate closure, crash after witnessed harvest/pickup/deposit | Real disturbance and live boundary, save files, termination, same client process, no replay |
| W7 | Real interpreter and nearest eligible typed container | Closer full/wrong-type and farther eligible distractors, selected destination and actual transfer |
| W8 | Legacy food controller harvest/replant, planting, loose pickup, bread delivery, stop/auto/release and death | Normal `/aivillage` commands, actual native effects, custody and truthful bounded controller status |
| W9 | Same-principal competing Job owners, independently admitted second principal, foreign control refusal | Simultaneous clients where needed, actual active lease overlap, scoped receipts, physical custody and cancellation |
| W10 | Native citizen death, permanent fencing, same-save recovery and unrelated same-name successor | Actual kill, native DEATH, terminal availability, owner fencing and no identity inheritance |
| W11 | Public legacy group food request with two actual native workers | Both controllers genuinely active, actual acquisition, native identities and complete physical custody |
| W12 | Real attack, low-health admission refusal and native trading interruption | Native health/menu evidence, safety reason, idle controller and no later task effects |

W1–W4 overlap property views of one acquisition/delivery pipeline. A deterministic greedy design covers new parameter pairs without claiming exhaustive pairwise coverage in twelve samples. Neighboring base variations change at least three factors. Actor roles use independently enrolled native UUIDs; explicit requests retain exact bindings. Queue actor IDs are responsibility anchors, so the runner observes actual authorized workers rather than assuming the dispatcher must choose that anchor.

Fixture writes occur only before tested work and after all known owners are terminal. Client recovery sends ordinary native respawn packets only for a genuinely dead connected player. Scene and remote landing chunks must be observed loaded before fixture writes. No automatic cleanup is allowed to erase active disconnected work. Crash boundaries may freeze the vanilla clock only after actual execution/effects are witnessed; no effect is supplied by the fixture at the checkpoint.

## Independent assertions and qualifications

The oracle counts tested items in all observed containers, actors, dropped entities and player inventories. Existing stock supplies no harvest credit. Unique actor/run/batch/destination receipts must agree with physical mutations and custody; per-batch harvest ≥ pickup ≥ deposit. Receipts alone cannot prove completion. Every case preserves full parameters, provenance, real instruction, native snapshots/observations, admission/run/lease records, raw client/server logs, frames, property evaluations and replay command. Failed attempts survive later retries.

The first run's `generationCalls` summed retained run markers and can decrease when old markers retire. The follow-up uses the broker's cumulative service call counter, alongside the separate interpreter count. Original no-inference flags remain raw until before/after broker snapshots establish their qualification. Server restarts require explicit pre/post counter reasoning rather than an equality across reset counters.

The old tall vertical four-log fixture supplied no supported standing cell within the native 2.5-block feet reach of its highest log. Original tall fixtures remain negative reach-limitation replays; new positive tall fixtures supply ordinary pre-task standing terrain. Native partial outcomes still need evidence. `fixture-qualifications.json` also retains the old 264-cell outside-source distractor mistake; valid source requests are bounded to 256 cells, without shrinking other valid broad search envelopes. Neither harness qualification is a production fix.

Nine minimization comparisons retain the first failing methods and vary quantity, delivery distance, source search volume, exact capacity and supported reach. They are diagnostic comparisons, not already established smallest counterexamples. Reports must distinguish confirmed causes from evidence-bounded hypotheses.

## Inventory and remaining coverage limits

`inventory.json` contains 83 implementation-derived rows: 51 registered primitive signatures, legacy/cross-cutting capabilities, nine registered high-level capabilities and five compound routes. Agent 2's recipe/placement actions remain inventoried and excluded from this independent execution scope. Unsupported spruce/stripped logs, carrots and arbitrary placement are not invented.

Aggregate family success never proves that every primitive opcode ran. Public traces do not expose every primitive start/completion, so no automatic primitive coverage credit is assigned. Legacy bread crafting is Agent 2's primary scope; W8 bread delivery uses pre-existing bread and claims no transformation/full-food-goal completion. W11 tests group admission/acquisition without claiming bread transformation; W12 uses actual damage and trading. Their native results must be read separately from supporting checks. Missing exact invocations, moving/partial loose-drop geometries, dynamic capacity changes, ownership transfer without a public command, explicit entity removal, fire/visible-monster/sleep safety interruption, and conversion/cure continuity retain explicit coverage limitations. Provider-plan home movement and configured legacy respawn are implemented but unavailable under the frozen launcher configuration (`providers=[]`, default permanent death); this does not mean the production features do not exist.

Recovery requires a real saved-world boundary. Missed stages, admission failures, unavailable interpreter/backend, incomplete observations and halted cleanup remain inconclusive/blocked. A family with zero genuine gameplay runs is untested. The mission cannot be marked fully verified while these limits remain. The final documentation must reconcile actual results for every inventory row and retain the first execution separately from follow-up results.
