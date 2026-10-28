# D storage/logistics tooling — D00/D01

The immutable B base is `12fb3af99e0c9496171b89d4dd2ffff3f5c7a824`; docs base is `9864841ff76d867fba2ccdab87199d3556d19f22`. Design inputs come from documentation commit `37fbf49e2aec4eddb3c7600217e585a1b349e48f`. D00 is qualified at source `313f32b80f6b4c1c6e8182982f40c85d417d38ba`; D01 adds registry metadata and bounded filesystem persistence. Only D00/D01 are implemented. New domain code has no initializer or runtime registration. J01/J02 own native admission and A authority. D02 must wait for D01's independent handoff.

`contracts/` and `ci-freeze/` are unchanged published inputs. Schema identity is embedded in `StorageSchema.java` without changing baseline build/resource files. Wire decoding enforces canonical bytes, safe integers, NFC, exact versions/fields, semantic relationships and immutable projections. Decoding does not authenticate a principal, grant permission or prove custody. `StorageWire.Context` is a separate in-process adapter input.

`case-registry.json` preserves D00's 59 Java/26 Python cases and freezes D01's cumulative 90 Java/42 Python cases before the candidate run. D01 also requires ten raw error/kill observations belonging to existing Java cases; they are not extra scenarios. Fixture dispositions include expected schema/semantic rejection and contextual refusal; their tests pass only when the exact frozen outcome is observed. D00 requires no physical GameTests or client scenarios because it adds no physical implementation. The standalone native fixture is compiled and its actual packaging checked. Two distinct client identities are configured for later stages; they have no observed-client credit here.

The latest user instruction permits an early Actions/Docker run for observed local dependency/test blockers. `blocking-request.json` deliberately requests the current D01 run, with the standing user authorization for local Docker/full-SDK blockers. Development pushes without a request change do not launch owner CI. `qualification-request.template.json` is reserved for D11; it is not an active final request. One workflow/job runs sequential checks within 90 minutes and always uploads partial logs. All ten baseline workflow changes must match the frozen trigger patch byte for byte. No docs workflows are added.

The CI workflow is the executable command plan. Locally, with the established Java 25/SDK/Docker runtime:

```bash
python3 e2e/parallel_d/contracts/verify_contract.py --contract e2e/parallel_d/contracts
python3 e2e/parallel_d/materialize_docs.py --root ../CognitiveCraft-Documentation-D
python3 e2e/parallel_d/ownership.py --docs-root ../CognitiveCraft-Documentation-D
bash gradlew --no-daemon --no-build-cache --stacktrace -I e2e/parallel_d/native/fixture.init.gradle :core:test :providers:test :fabric:test :fabric:build :fabric:gameTestJar :fabric:parallelDNativeJar
python3 e2e/runtime.py prepare
python3 e2e/runtime.py compile
python3 -m unittest discover -s e2e/parallel_d -p 'test_*.py'
python3 e2e/parallel_d/prerequisites.py --stage D01 --docs-root ../CognitiveCraft-Documentation-D
python3 e2e/parallel_d/docs_check.py --docs-root ../CognitiveCraft-Documentation-D --stage D01
```

For stage collection use the workflow's environment variables, image identity collection and isolated Compose tools service. `stage.py run --stage D01 --built --root ROOT` consumes the built release and writes raw observations. It does not decide success. `verify.py --stage D01 --root ROOT --source SHA --tree TREE --jar fabric/build/libs/ai-villages-0.1.0.jar --output ROOT/qualified.json` reads actual XML and hashes independently. Unknown stages, skips, missing or duplicate cases, altered JARs, unmanifested frames and invented integrated authority are rejected. Module regressions block advancement. Source must be clean and committed before measurement. `OWNED_GATES_PASS` covers only the requested stage; D11 readiness remains unqualified.

`docs-snapshot.json` contains exact Git trees/commits and owned prequalification docs. The sparse materializer verifies their original SHA identities and leaves protected historical blobs unmaterialized. It permits credential-free docs parity checks in source CI without copying private historical document contents. After the exact source run, reports go to the docs branch and artifacts; the final docs SHA belongs in the external handoff, avoiding a self-referential source/report commit.

Compose reuses `e2e/Dockerfile`, SDKs and client/server APIs. Its internal D network, namespace, port 25661 and server/two-client mounts are isolated from A/B/C. Do not mount active peer worlds. The tools image extends the established runtime with Git only. No new model/backend, loader migration, global harness override or production authority factory is introduced.

D01 metadata API is under `core/.../storage/`: `StorageRegistry`, `StorageMetadataStore`, `StorageMetadataCodec`, and `StorageRegistryLimits`. One bounded dedicated IO worker owns immutable snapshots; registration is PENDING_BIND, role assignments are separate from ownership/ACL, labels normalize for display and full Unicode case-fold only for lookup. Hard caps include 4096 identities with tombstones, 4096 assignments, 32 grants and 8 MiB current snapshots. Current/previous/pending files are checksummed, forced and atomically replaced. Unknown/corrupt/foreign/stale-pending files stay preserved read-only. No item quantities, Jobs, effects or credits enter the format.

`StorageMetadataProbe` is test-only, compiled with core tests and explicitly rejected from release packaging. Python file tests invoke the actual candidate code against synthetic metadata files and the installed SDK Gson; no simulated-native credit. `recovery_oracle.py` reads actual current/pending/previous hashes and killed-child logs independently. All filesystem recovery checks are metadata unit evidence, not Minecraft saves.

`build.py` preserves per-attempt raw logs and bounds repository connection/read waits. It retries at most three dependency configurations only before any Gradle task executes; started tasks and test assertions do not retry silently. The build files, dependency coordinates and test bodies remain protected.

The current docs snapshot is a sparse Git-object checkout. Exact ancestor commits prove D00/frozen-B ancestry; historical raw blobs remain identified in their original Git trees. Only current owned pages, referenced handoff/manifest JSON and frozen contracts are materialized for local links/parity. Final docs retain all historical raw bytes.
