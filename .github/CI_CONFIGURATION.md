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
| `job-lifecycle.yml` | `COGNITIVECRAFT_JOB_RESTART=cold/warm` selects two separate model-disabled Minecraft processes; `JAVA_TOOL_OPTIONS` enables tracing; no secrets or repository variables |
| `resource-leases.yml` | `COGNITIVECRAFT_LEASE_RESTART=cold/warm` selects two separate model-disabled Minecraft processes; `JAVA_TOOL_OPTIONS` enables tracing; no secrets or repository variables |
| `citizen-identity.yml` | `COGNITIVECRAFT_IDENTITY_RESTART=cold/warm`; test tracing |
| `default-nearest.yml` | Test tracing through `JAVA_TOOL_OPTIONS` |
| `needle-language.yml` | `NEEDLE_TELEMETRY=0`, `DO_NOT_TRACK=1`; `COGNITIVECRAFT_NEEDLE_DIR` points to assets installed under the automatically supplied `RUNNER_TEMP`; test tracing |
| `research-live-trial.yml` | `COGNITIVECRAFT_OLLAMA_MODEL=qwen3:4b-instruct-2507-q4_K_M`; cold/warm bootstrap fixture selectors; navigation tracing through `JAVA_TOOL_OPTIONS` |
| `local-generation-smoke.yml` | `COGNITIVECRAFT_OLLAMA_MODEL=qwen2.5:1.5b-instruct`; real adapter cold/warm smoke |

The IMP-010 branch push starts full verification and retrieval acceptance. A pull
request against `main` also starts identity, nearest, actual Needle/outage and
genuine Qwen acquisition/restart acceptance. Their existing test assertions,
toolchain/model pins and finite budgets are preserved. Artifacts contain JUnit
reports, disposable-world evidence and built JARs as configured in each workflow.
The standalone adapter smoke also runs on relevant `main` pushes and pull requests,
and remains available through `workflow_dispatch`. It requires successful cold and
warm phases using the unchanged pinned model and finite adapter budgets, retains
the candidate/model digests and usage measurements, and uploads bounded evidence.
The source failure audit and corresponding public coverage are recorded in
[CI_FAILURE_AUDIT.md](CI_FAILURE_AUDIT.md).
Full verification's final saved XML aggregate requires 48 distinct Minecraft
fixtures and 75 successful executions and retains `coverage-summary.json`.

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

IMP-011 introduces the bounded Job Lifecycle Store and architecture extension 0.2.
Its exports and downstream bindings are in [IMP-011-contracts.md](implementation/IMP-011-contracts.md);
validation and completion evidence are in [IMP-011-report.md](implementation/IMP-011-report.md).
IMP-012 adds the bounded Resource Lease Service and architecture extension 0.3.
Its exports and downstream bindings are in [IMP-012-contracts.md](implementation/IMP-012-contracts.md);
its completion and cloud evidence are in [IMP-012-report.md](implementation/IMP-012-report.md).
The lease lane requires executed policy, owner, persistence, interpreter and bound
matrices plus two real Minecraft processes. The test-only two-client fixture has
no model endpoint, proposal port, production skill seed or worker dispatcher.
The next gate is IMP-013 dispatch, IMP-014 AI brokerage, IMP-015 compatibility,
IMP-016 retention and IMP-029 citizen lifecycle reconciliation. Later P2/P3 work
depends on those contracts. Run only the applicable bounded task and acceptance
gates against this public repository; historical source checks are evidence for
their pinned source, not verification of a new public head.
