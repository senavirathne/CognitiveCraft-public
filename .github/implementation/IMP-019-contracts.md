# IMP-019 bounded goal planning contracts

Architecture compatibility remains **0.5**. This is an additive **Plan / PlanningBudget contract 1** extension. Existing Contracts/IR, Jobs/Leases record schemas remain **1**; observed-world records remain **2**. No existing saved record is reinterpreted. The new `goal-plans` domain reader accepts schema **1** only.

## Supported outcome and routing

`cognitivecraft:supply_wheat_pair:1` is the recognized broad goal: an authorized actor delivers a specified total of 2–64 wheat from one loaded source to two distinct loaded destinations. `firstAmount` determines the exact first quantity; the second is the positive remainder. Its typed completion is the additive `ALL_CHILDREN` rule. Existing crop delivery completion and behavior are unchanged.

`PlanningGoals.slots` derives canonical bindings for the two existing `cognitivecraft:deliver_wheat:1` outcomes. Only the trusted driver supplies actor, source, destinations, origin and parent budget. The goal request has no permission, instruction, worker, inference or budget fields.

`FabricGoalPlanning` first captures current authorized world facts, resolves the original request through the existing resolver, and obtains complete deterministic outcome coverage from the IMP-018 native catalog. Only complete `NEEDS_PLANNING` plus complete, insufficient deterministic handling permits a proposal. Directly resolved, deterministically handled, unsupported or incomplete cases do not invoke inference. Both child outcomes must already have eligible admitted compatible methods. There is no automatic planning on every resolver miss and no synthesis of missing physical capabilities.

This intentionally bounded initial production envelope does not imply general building, crafting or arbitrary multistep goal support. The entry point is `KernelSession.createPlanningObjective` followed by `proposePlan`, for structured drivers. IMP-019 does not change natural-language classification or add command syntax.

## Plan, evidence and validation

Plan JSON is separate from Skill IR:

```json
{"schema":1,"nodes":[{"id":0,"capability":"cognitivecraft:deliver_wheat","version":1,"slot":0,"amount":2,"dependencies":[]},{"id":1,"capability":"cognitivecraft:deliver_wheat","version":1,"slot":1,"amount":3,"dependencies":[0]}]}
```

The decoder permits exactly these fields, integral quantities, supported capability/version and finite lists. Extra fields, commands, primitives, executable bodies, actors, resources, permission changes and model-selected workers are rejected. Slots reference immutable trusted bindings; the model can select quantities/dependencies but cannot introduce new source resources. IDs are local graph IDs, not external Job or artifact references.

`PlanValidator` checks unique/bound nodes, acyclicity, depth, edge/work ceilings, supported granularity, positive quantities and exact conserved outcome totals. Memoized graph height evaluation is bounded by nodes plus edges. Current authority is checked before every child resource/method read. Known current methods must match the captured exact artifact references. Current source evidence must have the same target, dimension, status, counts and scan coverage, have no future timestamp and fall inside the current age ceiling. An old captured observation is accepted only when a fresh current observation revalidates the same premise. A shortage, unknown cells or changed stock cannot be repaired by model claims or advisory history.

IMP-017 history is retrieved off-thread only through the existing authorized owner, limited to eight inspected records, two results and 2,048 bytes. The prompt carries advisory identity/freshness/qualification, not private source records from another scope. Current observations remain authoritative. Canonical goal/slot requests and evidence references go only to the existing local backend.

## Finite work and parent allowance

| Ceiling | Default / maximum |
|---|---|
| Nodes / created work | 8 / 8 |
| Dependency depth | 3 |
| Edges | 16 / 32 |
| Context / proposal bytes | 8,192 / 8,192 each |
| Capture plus validation work | 512 / 1,024 units |
| Current observation age | 10 / 40 ticks |
| Pending/diagnostic proposals | 16 |
| Retained active links / demonstrated templates | 16 / 8 |
| Planning file bytes | 262,144 per generation |
| Planning parent candidates | 2 |
| Parent inference input / output | 65,536 / 16,384 bytes |
| Parent execution ticks | 2,400 (two existing 1,200-tick dispatch windows) |

All proposals share the original parent's absolute deadline, including context and queue time. After a bounded synchronous direct/deterministic routing proof, Jobs durably consumes one finite candidate envelope **before asynchronous context work is queued**: one candidate plus the configured capture/validation work ceiling; inference additionally reserves one call, 32,768 input bytes and up to 8,192 output bytes. Exhausted candidates/work are refused before another routing/context read. This conservative policy deliberately does not refund context cancellation/storage failure, rejection, transport failure or restart. `View.reserved` reports that durable envelope; `View.actual` reports the shared broker's real observed usage separately. An oversized context is billed before refusal. A fresh runtime cannot reset the remaining parent allowance.

Capture includes bounded world/method search, deterministic coverage, history inspection and context serialization. Before each validation scan/search, the validator reserves its finite default resolver/area ceiling and rejects insufficient remaining work without calling the port. Unexpected port work beyond that ceiling is rejected. Native observation itself is capped by the existing 256-cell source limit; default resolver candidate/query ceilings remain 32/12. No model field controls a ceiling.

Children receive at most an equal share of the actual remaining parent allowance, the same absolute deadline, and zero inference input/output, candidate, repair or trial allowance. Existing Jobs accounts their execution cumulatively up the parent tree. Planning does not renew execution or research budgets.

The paired goal's execution tick policy is selected at objective creation, before any proposal: two existing dispatch windows. It is never enlarged after an attempt, rejection or budget exhaustion. The original single-request research/execution policies remain unchanged.

## Publication, execution and cancellation

`GoalPlanner` owns proposals; `AIWorkBroker` owns all generation. The broker requires the distinct `goal-plan:1` output contract for `Generation.Role.PLANNING`. `LocalGenerationAdapter` uses the existing configured shared local model and transport with a Plan-specific output shape. It never lowers a Plan into executable Skill IR. There is no dedicated planning model or model download in production startup.

`JobLifecycleStore.admitPlan` publishes the entire validated child/dependency graph in one acknowledged Jobs generation, using stable parent/node-derived IDs and current parent guards. A failed or uncertain Jobs write exposes no partial executable graph. Jobs owns admission, hierarchy, execution attempts, cancellation, retries, accounting and actual effect receipts. The existing dispatcher excludes broad goal Jobs and dispatches their admitted crop children normally; the existing leased gateway remains the only effect path.

Plan metadata is a second staged publication after Jobs acknowledges the graph. If metadata fails or is congested, status still reports the acknowledged admission with a storage qualification, and the whole graph remains discoverable through Jobs. Replay/restart recovers existing child identities without a model call or another publication. No Plan journal reader creates Jobs, workers, leases, executable frames or physical effects. This is a recoverable staged protocol, not a transaction spanning two file owners.

`cancelPlanProposal` fences pending inference/context/validation output; it does not cancel admitted work. It returns false once Jobs publication has begun. `cancelPlanningObjective` explicitly delegates cancellation of that root's owned subtree to Jobs. Unrelated roots and committed partial effects/receipts remain intact. Late responses have no authority to create new children. Backend compute stop acknowledgement is distinct from subscriber cancellation.

An unavailable shared model gives explicit `MODEL_UNAVAILABLE`. After admission, known children execute with every model disabled; no model-free fallback produces proposals or invents missing methods.

## Saved state and reusable templates

`PlanJournal` is an off-tick single writer at `data/cognitivecraft/plans/v1`. It stores active root/child/artifact links and selectively demonstrated templates. Terminal link metadata is pruned only through an acknowledged owner replacement. Templates are retained only after Jobs proves the objective and both attributable children succeeded. They contain two normalized quantity placeholders, slot topology and dependencies; no actor, coordinates, original observations, method references, effect receipts or executable code. Reuse binds quantities, scope, resources and currently eligible methods anew, and rechecks sharing grants throughout the pending reuse. Production templates are private; the sharing policy seam requires an explicit current grant.

The journal has strict UTF-8, schema/world, exact-field, count, byte and checksum validation, an exclusive writer lock, stage verification and atomic replacement. Backup bytes come from the writer's previously validated owned generation, never from an externally corrupted current file. Every interruption boundary is tested. Unknown/corrupt current files are preserved; a valid prior generation can be read inactive, with no speculative migration or replay. Capacity refusal does not evict state. All current, prior, staged and writer files participate in IMP-015 retention accounting; active method roots also feed the existing retention coordinator.

## Acceptance boundaries

The 62-case deterministic matrix covers preconditions, malformed/unsupported/fabricated input, authority and grants/revocation, quantities and all graph/work/byte ceilings, current premises, atomic publication, replay, fresh-owner accounting, cancellation including uncharged-context retry prevention, unavailable models, known offline execution, journal recovery and template privacy.

GP-19 runs the production KernelSession with explicitly controlled proposal outputs and a test-admitted known P0 method. Its compact native fixture puts five real crops and two separate chests within physical reach of a stationary villager and uses the existing harvest/pickup/transfer method; source navigation remains covered by its own acceptance fixtures. It checks cycle/foreign-field/oversize refusal, delayed cancellation/replay, no planning effects/assignments, one two-child admission, actual 2/3 wheat delivery through dispatcher/Jobs/Leases with models disabled, and a second JVM reopening the same saved world without replay. Controlled generation and seeded method admission are plumbing fixtures, not evidence of model planning quality or newly learned method quality.

The separate held-out live benchmark measures three previously unused quantity pairs, actual shared-backend model digest, call/byte/token accounting, structural/semantic validity and latency. It reports observed quality even for invalid candidates, makes no physical behavioral-admission claim and cannot substitute for GP-19. CI preserves both evidence streams separately.
