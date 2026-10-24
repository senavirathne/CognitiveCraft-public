# Abstract crafting and production campaign

Testing and diagnosis only. Production baseline:
`ea7de8000b2b484b885576c34ed5358274936ce9`.
Documentation baseline: `d80f1db443a2493b3e422f435e069a7863076b07`.

## Current execution status

The campaign is implemented on `test/generalization-crafting` and has **not yet
executed in Minecraft**. No recipe, facility, composition or recovery outcome is
claimed. The Java 25 oracle and observational mixins also await runtime compilation
and loading in Actions. Python evaluator checks are separate from gameplay evidence.

The workflow is manual-only, contains one consolidated job and uploads one artifact.
It builds the production JAR once, prepares official Minecraft/Fabric assets once,
and starts only `minecraft` and `client-a` from the existing `e2e/compose.yml`.
Two citizens are controlled through the same persistent player connection. Ollama
is started once for genuine independent method admission and stopped before the
generated campaign. No production recipes, capabilities or policies are changed.

## Families and oracle

Branch-only continuation adds live-recipe composition arithmetic, measured blueprint
contributions and staging-surplus accounting. Recovery preserves the actual terminal
outcome instead of relabelling a completed request as interrupted, and compares saved
container/actor/player/drop custody after restart. Raw logs use fixed byte boundaries;
the verifier ties commands, identities, vanilla probes and native frames to each case.

The current local checks pass **72 existing harness tests and 54 evaluator tests**.
Seven existing Actions checks passed on the previous published candidate `9ba8477`.
Those checks are not new crafting gameplay evidence. The new manual campaign remains
unexecuted, and its Java 25 instrumentation remains unqualified at runtime.

| Family | Shared property |
|---|---|
| P1 | All discovered vanilla plank recipes/ingredient alternatives, quantities, batches and surplus |
| P2 | Sticks, wooden axes and tables with every accepted plank, valid mixed grids and matching canonical controls |
| P3 | Ready, partial, raw, distributed, held and insufficient stock, plus real client mutations/contention |
| P4 | Different already-completed intermediates, quantities and admitted prerequisite graphs |
| P5 | Existing, stored, produced, placed and reused facilities, invalid sites and collisions |
| P6 | Blueprint rotations, correct existing cells, staged stock, shortages and explicit unsupported transfer |
| P7 | Witnessed primitive boundaries, cancellation, acknowledged save/crash, conservative interruption and offline changed-binding reuse |
| P8 | The separate player-accessible legacy wheat-to-bread executor and physical delivery |

`capabilities.json` audits 18 gameplay entries and 51 registered primitive signatures.
Agent 1 assignments are explicitly unverified; their results are not imported.
Blueprint construction, arbitrary recipes, chest/barrel placement and automatic
instruction replay are not inferred from internal methods or structural data.

`oracle.py` discovers recipe IDs and shapes from the verified official game JAR.
The isolated `CraftingRecipeOracle` then obtains actual loaded ingredient alternatives
from RecipeManager. Every generated recipe grid is independently checked with the
running game's `matches`, `assemble` and remainder APIs. Material names are not a
closed list. New plank types in those rules automatically enter the same generator.

The generator uses a seeded pairwise covering array plus explicit exhaustive ingredient
alternatives. Each family has at least 12 development variations. Definition hashes
are written before a separate held-out seed is drawn. Held-out selections, unscheduled
generated candidates, exact seeds and replay inputs remain distinct.

Physical conservation includes containers, all observed actors, player inventory,
drops, standing logs and placed crafting tables. Production output must agree with
vanilla integer stoichiometry, the requested destination, retained surplus, actual
receipts and the connected client's menu/block observations. Existing Job success
or a safe alternative-material refusal cannot alone produce a PASS.

## Actions execution

The user explicitly authorized branch-only push execution on 24 October 2026.
The push trigger is restricted to `test/generalization-crafting` and changes to
`.github/workflows/generalization-crafting.yml`. It runs the event's exact commit
in full mode with development seed `20261024`; held-out entropy is drawn after
definition freeze. The GitHub plugin can launch it by committing the workflow
file on that branch. No default-branch registration, main change, merge or browser
dispatch is required. Other file changes do not launch this campaign.

The first branch-only run, 40111258592 at `ff6c414`, passed build, evaluator and
Java 25 instrument compilation, then failed before gameplay because the observer
entrypoint shared Mixin's reserved package. Its artifact and original server log
are retained; this is a harness infrastructure failure, not a crafting result.
The two mixins now occupy a dedicated package, separate from normal observer
classes. A fresh branch workflow revision qualifies that correction in Minecraft.

Manual `workflow_dispatch` inputs remain available for a separately registered
manual workflow; push launches have explicit defaults. The full campaign does
not create individual recipe/material runs or an Actions matrix. Existing
project-wide CI remains unchanged and may still run for branch publication.

The workflow's evidence step checks production identity, scheduled denominators,
all recipe alternatives, original log/frame hashes and connected physical evidence.
It does not impose a gameplay success score. Missing admissions, missing cases and
missing runtime evidence remain incomplete execution, even if harness unit tests pass.

## Evidence and replay

One artifact contains `campaign.json`, `assessment.json`, `report.md`,
`capabilityCoverage`, the recipe oracle, freeze/seed/schedule records, all per-case
directories, original native screenshots, server/client logs, process identities,
world checkpoint hashes, stopped saves, the exact production JAR and observer JARs.
Every attempt retains its original result. At most four fresh failure reproductions
are appended; a reduced quantity is labelled a minimization candidate until observed.

Every `cases/<id>/result.json` includes initial parameters and physical stock,
original player command, bindings/principals, planning/Jobs snapshots, receipt
lineage, first divergence, invariant checks, final custody, and byte-addressed raw
server/client log slices. The test-only mixins log starts, handles, terminal results
and cancellation without modifying arguments, return values or gameplay state.

The following are harness commands for an Actions runner with the prepared runtime:

```bash
python3 scripts/campaign-crafting/verify.py baseline
python3 e2e/campaigns/crafting/run.py --root build/e2e/crafting --mode full --seed 20261024
python3 scripts/campaign-crafting/verify.py collect --root build/e2e/crafting
```

Replay any preserved `case.json` in a new run directory with
`--replay <path/to/case.json>`. Bootstrap still performs genuine admission; no
saved executable body is fabricated or silently installed. All failures and
earlier attempts remain intact.

## Known boundaries that remain visible

The source also declares finite owner limits: Jobs allows 64 total records and 32
active records, while BootstrapJournal retains 16 run markers. `JobLifecycleStore`
rejects capacity overflow with `STORAGE_LIMIT_REACHED`. These are source observations,
not measured campaign failures. The schedule interleaves families and executes paired
controls first. If a real request reports that capacity refusal, the campaign records
`BLOCKED_CAPACITY`, stops further submissions and preserves the remaining schedule as
unexecuted. It does not raise production quotas or restart the server to erase history.

- Valid vanilla substitutions safely refused by oak-specific bindings are
  `GAP_RECIPE_EQUIVALENCE`, never gameplay success.
- Vanilla plank outputs with no player adapter are `GAP_NO_GAMEPLAY_ADAPTER`,
  explicitly unexecuted even though their vanilla semantics are probed.
- Actor-held and separate-container stock are evaluated as distribution/binding
  limits where the current contract only accepts one source container.
- A missed dynamic/recovery boundary is `INCONCLUSIVE_BOUNDARY`.
- Unfinished saved work is expected to interrupt conservatively. A new explicit
  request is evaluated separately from automatic replay, which is not supported.
- Simultaneous independent principals and effect-time ownership revocation have
  no coverage claim in this one-client campaign. The coverage ledger requires
  later Agent 1 reconciliation or a genuinely available additional surface.

No production fixes, automatic merges, documentation-repository Actions or
modifications of Agent 1's files belong to this campaign.
