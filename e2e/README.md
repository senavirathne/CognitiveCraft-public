# Real Minecraft gameplay acceptance

This suite crosses the multiplayer boundary with the installed production JAR,
an official Minecraft 26.3 client, and an independently running dedicated server.
The client and server use Fabric Loader 0.19.5, Fabric API 0.161.0+26.3 and Java 25.
SDL offscreen rendering uses Mesa software OpenGL inside Docker; it runs the real
client renderer, screens, game loop and multiplayer protocol.

The scope is IMP-001–014 with IMP-028 as a foundational resolver dependency.
The October 9 design's absent IMP-014 finding is superseded: public PR 6 merged
the broker before this suite's implementation baseline
`b5ccd06b9c6a6dd8cfde6f344fc34e1a953f2d3d`.

## Run

Use Linux x86-64, a Java 25 JDK, Python 3.11 or later, Docker Engine and Compose v2.
Install official runtime assets once; every use verifies their pinned hashes.
The production binary comes from the normal release build.

```sh
bash gradlew --no-daemon :core:test :providers:test :fabric:test :fabric:build
python3 e2e/runtime.py prepare
python3 e2e/runtime.py compile
python3 e2e/suite.py --profile smoke --root build/e2e/smoke
```

The first physical fixture must come from actual local generation, validation,
physical admission and a successful changed-binding reuse with both backends off:

```sh
python3 tools/needle/install.py build/needle
python3 e2e/suite.py --profile acquire --root build/e2e/acquire --seed build/e2e-certified-seed
python3 e2e/suite.py --profile deterministic --root build/e2e/deterministic --seed build/e2e-certified-seed
python3 e2e/suite.py --profile nlu --root build/e2e/nlu --seed build/e2e-certified-seed
python3 e2e/suite.py --profile resilience --root build/e2e/resilience --seed build/e2e-certified-seed
```

`--profile all` acquires its own genuine fixture first, then executes every family.
`ai` selects acquisition and NLU together. `--topology process` uses independently
supervised native JVMs for model-free local diagnosis; acquisition requires the
owned Compose Ollama service. Root paths must be distinct for separate runs.
The isolated server binds loopback, accepts the test EULA, and uses offline login
with fixed independently recorded UUIDs. This proves multiplayer login/play and
scope identity, not Mojang account authentication.

## Families and profiles

| Family | Player journey | Profile |
|---|---|---|
| E2E-CONN-001 | Join, enclosed nearest enrollment, Tab, name, reconnect; JVM restart in Tier 2 | smoke, deterministic |
| E2E-CMD-001 | Received tree, live op/deop, malformed and Unicode input | deterministic |
| E2E-SUGGEST-001 | Two-player citizens, runs and jobs; guessed UUID denial | deterministic |
| E2E-PHYSICAL-001 | Known delivery, real movement/custody/menu and negative variants | deterministic |
| E2E-LEGACY-001 | Named food work, synchronized bread, nonowner denial and speech radius | deterministic |
| E2E-CANCEL-001 | Travel and committed partial cancellation | deterministic |
| E2E-MUTATION-001 | Ordinary other-player crop/destination changes | deterministic |
| E2E-CLIENT-LOSS-001 | Client JVM death, server survivor, fresh client login | resilience |
| E2E-RECOVERY-001 | Graceful server shutdown and conservative restoration | deterministic |
| E2E-RECOVERY-002 | Saved partial deposit, server SIGKILL, conservative recovery and explicit remaining work | resilience |
| E2E-NLU-001 | Actual Needle, grounded references, private tickets and outages | nlu |
| E2E-AI-001 | Actual Ollama, candidate/trial/admission, model-free reuse | acquire |
| E2E-LEASE-001 | Real queued jobs, owned workers and limited shared stock | deterministic |

[`coverage.json`](coverage.json) maps each implementation unit's happy path,
negative behavior, authorization, recovery and AI dependency to these families
and the precise existing component tests. Internal broker fairness/coalescing,
finite concurrency, budgets and unconfirmed cancellation use the existing broker
matrices and GP-14 acceptance. Its observable provider integration uses the actual
client acquisition family. No extra broker command or new production owner is
introduced.

## Fixtures and authority

All scenarios start in a lit, elevated, bounded glass arena with adult villagers
inside separate closed pens. AI remains enabled. Players have exactly one eligible
villager within six blocks; real enroll feedback supplies citizen and entity UUIDs.
Players open the gates through ordinary interaction before physical work.
Hydrated mature crops, immature stock, distinct empty and partially filled chests,
an obstructed destination and alternate walking space are setup resources.

F0 has no knowledge. F2 requires fresh generation. F3 isolates the legacy owner.
F1 is an integrity-checked copy of a stopped world after real admission and offline
changed-binding execution. Its certificate includes producer identity, exact
artifact, stable actors and file hashes. Exact class/resource content must match
the tested release binary, irrespective of ZIP timestamps. Active worlds and
successful gameplay outcomes are never put in the dependency cache.
The changed-binding request starts with six verified mature input crops and no
old loose drops in its separate field. That recorded fixture reset occurs before
submission; it supplies no delivered stock or task receipts.

Recovery preserves the original citizen and its uncertain worker reservation.
The suite checks that new work with that worker is blocked, then enrolls a fresh
owned adult through the connected player for explicit remaining work. It does
not clear or reconcile a private job through instrumentation. Loose crash wheat
is recovered through ordinary player movement and verified in synchronized
inventory slots before the next request.

`ClientDriver` runs bounded actions on the client thread through vanilla screens,
input/game-mode APIs and the client connection. The mailbox does not call server
handlers. Fresh Tab requires an observed outbound suggestion request matched to
the received transaction and replacement range. Opened synchronized menu slots
are the client inventory oracle.

The separately packaged observer samples loaded world state and immutable scoped
production views on the server thread. It never enrolls, submits, grants authority,
seeds knowledge or mutates the world. Administrative construction runs only before
the tested action. During an action the supervisor permits only recorded normal
lifecycle operations, such as saving or stopping. External interference uses the
other actual player.

## Evidence and qualification

Each run retains source commit/tree, dirty state, release SHA-256, verified client
and libraries, exact Java process commands/PIDs, Docker image configuration,
fixture specification, server observations, client packet/event traces, commands,
UUIDs, receipts, model descriptors/counters, resource samples and lifecycle events.
Model-free assertions compare call counters before and after the request: the
generation counter survives a world restart. The fixture artifact includes hidden
writer-lock files so every certified file reaches downstream runners unchanged.
Independent families continue after a failure and retain the failure snapshot;
failed genuine acquisition prevents dependent fixture qualification.
`results.json` and `junit.xml` contain actual results. An absent mandatory family
fails the selected profile; an out-of-profile family remains `NOT_EXECUTED`.
Missing F1 or runtime infrastructure is `BLOCKED_BY_TEST_INFRASTRUCTURE` and exits
nonzero. No skip is treated as successful coverage.

The smoke workflow is the first gate. Gameplay acceptance runs smoke before actual
acquisition, publishes the certified stopped fixture and exact binary, then runs
deterministic, NLU and resilience tiers independently. The same workflow runs on
relevant PRs, main, nightly and manual release qualification. The final merger
requires all 13 families, passing mapped JUnit suites and one consistent clean
source/tree/binary identity. Existing Fabric GameTest and owner acceptance workflows
remain separate checks; their passing result is never labeled network E2E.

Resource samples measure the current runner instead of assuming a model deadline
fits it. A resource shortage or deadline failure remains a failed qualification;
execution and inference budgets are never raised for CI. Hard-kill assertions
respect the separate vanilla-world and mod-journal durability domains and require
conservative interruption rather than automatic uncertain-effect replay.
