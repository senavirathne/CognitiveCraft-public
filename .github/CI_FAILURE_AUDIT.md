# Source CI failure audit

Audit date: 2026-10-05. Source repository: `senavirathne/CognitiveCraft`, read only.
Active development and all new commits/PRs: `senavirathne/CognitiveCraft-public`.

The source Actions API returned 236 failed runs across seven workflow families,
from 2026-09-28 through 2026-10-05. All three result pages were inventoried. The
latest failure in each of 25 distinct workflow/branch pairs was inspected, followed
by eight important executed-job logs. Seven of those latest pairs had no job
steps; they supply no test result and are not treated as runtime failures.

| Source workflow family | Failed runs in inventory | Public coverage |
| --- | ---: | --- |
| Full verification | 140 | Core/provider/Fabric JUnit, release audit, Minecraft cold/warm, cancellation, interference, interruption and actual SIGKILL recovery |
| Capability retrieval | 7 | GP-12 metadata/repository/resolver and 4,096-entry scale fixture |
| Citizen identity | 6 | GP-09 cold/warm identities, scoped control and serial physical work |
| Actual Needle | 32 | Real-model benchmark plus GP-10/11 physical language and disconnected structured fallback |
| Default nearest | 11 | Registered naming and changed-location physical work with deterministic reference binding |
| Research local-model trial | 39 | Genuine Qwen acquisition, physical admission and model-free reuse in a separate restarted Minecraft process |
| Local generation smoke | 1 | Cold/warm real-model adapter smoke, now automatically triggered for relevant public PR/main changes |

## Important executed failures and their regression coverage

| Source failure | Observed defect | Existing public test/gate |
| --- | --- | --- |
| [37274727515](https://github.com/senavirathne/CognitiveCraft/actions/runs/37274727515) | Full CI evidence verifier still expected 23 repeated regressions after adding UUID completion | Current verifier requires all 24 repeated regressions; the two-player production completion fixture executes cold and warm |
| [37136294167](https://github.com/senavirathne/CognitiveCraft/actions/runs/37136294167) | Changed-binding model-free bootstrap reuse ended BLOCKED/BUDGET_EXHAUSTED | `BootstrapGameTests` and genuine Qwen saved-world restart gate preserve finite allowances and require actual successful changed-binding delivery with no inference |
| [37133109236](https://github.com/senavirathne/CognitiveCraft/actions/runs/37133109236) | Nearest naming reported completion before expected identity was visible | `WorldReferenceGameTests` checks acknowledged identity, all naming forms and changed-location physical work |
| [37133109240](https://github.com/senavirathne/CognitiveCraft/actions/runs/37133109240) | Actual Needle fixture rejected foreign-language privacy behavior | `NeedleLanguageGameTests` requires no foreign candidates/run reference and correct scoped refusal; core language/identity policies also have cross-principal cases |
| [37128500990](https://github.com/senavirathne/CognitiveCraft/actions/runs/37128500990) | Generic candidate comparison did not compile | Current `WorldReferenceResolver` compiles; deterministic selection, bounds and authority are covered by its unit suite and physical nearest gate |
| [36981032129](https://github.com/senavirathne/CognitiveCraft/actions/runs/36981032129) | Interrupted-delivery fixture could not observe ticking cold-world chunks | Separate-process interruption and SIGKILL fixtures verify physical partial progress, explicit offline recovery and no duplicate fulfillment |
| [36559131409](https://github.com/senavirathne/CognitiveCraft/actions/runs/36559131409) | `GenerationJUnitTest.structuredRequest` rejected the request JSON | Current provider JUnit retains and executes `structuredRequest()` and strict generation-contract checks |
| [36467157144](https://github.com/senavirathne/CognitiveCraft/actions/runs/36467157144) | Cold local generation returned MALFORMED/ARTIFACT_INVALID | `GenerationLiveSmoke` retains strict candidate validation, two-call cumulative allowance and finite deadline; its unchanged real-model gate is now exercised by public CI |

The first six families already pass on public source
`5f203254b5188863c6b4fa4ad43d1e4979305ba6`, integrated by public PR #1 at
`ad87816ea67724d4c8a914ec02423398192028ec`. The public full push reports were
independently counted: 353 passing JUnit cases (286 core, 48 provider, 19 Fabric),
75 passing Minecraft executions across 48 distinct fixtures, and both UUID
completion executions. Required GP-12, identity, nearest, actual Needle and Qwen
acquisition/restart PR gates passed as well. See
[PR #1](https://github.com/senavirathne/CognitiveCraft-public/pull/1) for the exact
successful runs and artifact evidence.

This follow-up changes CI triggers and evidence collection only. It retains all
production/test Java, toolchain versions, model pins and existing generation
assertions. The newly automated adapter gate requires exactly one successful cold
and warm phase, stable model digest, finite attempts and bounded input/output;
Gradle failures propagate through evidence capture. Candidate acceptance here is
the adapter contract, not physical skill admission, which remains the separate
genuine Qwen/Minecraft gate. No repository secrets or variables are required.

Fresh follow-up results are recorded in its public PR and Actions artifacts. A
historical source failure is not rewritten or rerun against the legacy repository,
and a historical success is not relabeled as verification of a new code snapshot.
