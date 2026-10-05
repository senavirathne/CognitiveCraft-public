# CI configuration

Active development, branches and pull requests belong to
`senavirathne/CognitiveCraft-public`. The original repository is a read-only
reference. The public base snapshot is `07ee589e9b31d8da493fee7001b0bbac5ebe7583`;
its code and build files match original main
`d609e9980ca2cde8330042d2332226f50200d6b6`.

IMP-010 code and its retrieval acceptance workflow were transferred from original
PR #13, head `64fa582b38193a593e804b2c687663cb07b6b456`, onto this newer snapshot.
The recent executor thread fix and player-scoped UUID completion are retained.
New commits have only the public snapshot as their parent; private repository
history and design documents are not imported.

## Repository settings

No repository secrets or repository variables are required by these workflows.
They use GitHub-hosted Ubuntu runners and `permissions: contents: read`.
`actions/checkout` uses GitHub's automatically supplied token and does not persist
credentials. Enable GitHub Actions and allow the referenced public actions.
Java 25, Gradle, Docker, public Ollama models and pinned public Needle assets are
installed or configured by the workflows. No paid model endpoint is used.

| Workflow | Environment supplied by the workflow |
| --- | --- |
| `capability-retrieval.yml` | None; retrieval and its resolver/repository tests run without models |
| `ci.yml` | `COGNITIVECRAFT_RESTART_PROBE`, `COGNITIVECRAFT_BOOTSTRAP_RESTART`, `COGNITIVECRAFT_BOOTSTRAP_CRASH`, `COGNITIVECRAFT_IDENTITY_RESTART` select cold/warm disposable-world fixtures; `JAVA_TOOL_OPTIONS` enables test tracing |
| `citizen-identity.yml` | `COGNITIVECRAFT_IDENTITY_RESTART=cold/warm`; test tracing |
| `default-nearest.yml` | Test tracing through `JAVA_TOOL_OPTIONS` |
| `needle-language.yml` | `NEEDLE_TELEMETRY=0`, `DO_NOT_TRACK=1`; `COGNITIVECRAFT_NEEDLE_DIR` points to assets installed under the automatically supplied `RUNNER_TEMP`; test tracing |
| `research-live-trial.yml` | `COGNITIVECRAFT_OLLAMA_MODEL=qwen3:4b-instruct-2507-q4_K_M`; cold/warm bootstrap fixture selectors |
| `local-generation-smoke.yml` | `COGNITIVECRAFT_OLLAMA_MODEL=qwen2.5:1.5b-instruct`; standalone optional adapter smoke |

The IMP-010 branch push starts full verification and retrieval acceptance. A pull
request against `main` also starts identity, nearest, actual Needle/outage and
genuine Qwen acquisition/restart acceptance. Their existing test assertions,
toolchain/model pins and finite budgets are preserved. Artifacts contain JUnit
reports, disposable-world evidence and built JARs as configured in each workflow.
The standalone adapter smoke is available through `workflow_dispatch`.

## Public snapshot regression

Public run `37293582672` reached Minecraft but rejected the UUID fixture's owner
request after its foreign run terminated. Citizen availability publication can
still be pending at that point, and the controller correctly refuses new work
until that independent owner acknowledges storage. The fixture now waits for
identity readiness before both following submissions. Its existing game-tick
deadline, privacy, execution, cancellation and read-only suggestion assertions
remain in force. Production admission and readiness rules are unchanged.

IMP-010 is a derived metadata index, not an authoritative catalog. It preserves
architecture contract 0.1, existing request/IR/artifact/manifest schema 1, citizen
schema 1 and bootstrap journal schema 2. Its disposable cache schema is 1.
Stale, incomplete or unavailable retrieval uses the exact catalog within the
original allowance or returns an honest bounded blocker; it cannot prove a false
research gap. Admission, eligibility, authority and world mutation stay with their
existing owners.

## Next implementation gate

IMP-011 (Job Lifecycle Store) remains pending. Subsequent pending P1 boundaries are
IMP-012 leases, IMP-013 dispatch, IMP-014 AI brokerage, IMP-015 compatibility,
IMP-016 retention and IMP-029 citizen lifecycle reconciliation. Later P2/P3 work
depends on those contracts. Run only the applicable bounded task and acceptance
gates against this public repository; historical source checks are evidence for
their pinned source, not verification of a new public head.
