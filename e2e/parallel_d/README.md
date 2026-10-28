# D00 storage/logistics tooling

The immutable B base is `12fb3af99e0c9496171b89d4dd2ffff3f5c7a824`; docs base is `9864841ff76d867fba2ccdab87199d3556d19f22`. Design inputs come from documentation commit `37fbf49e2aec4eddb3c7600217e585a1b349e48f`. Only D00 is implemented. New domain code has no initializer or runtime registration. J01/J02 own native admission and A authority. D01 must wait for this stage's independent handoff.

`contracts/` and `ci-freeze/` are unchanged published inputs. Schema identity is embedded in `StorageSchema.java` without changing baseline build/resource files. Wire decoding enforces canonical bytes, safe integers, NFC, exact versions/fields, semantic relationships and immutable projections. Decoding does not authenticate a principal, grant permission or prove custody. `StorageWire.Context` is a separate in-process adapter input.

`case-registry.json` freezes 57 fixture cases, two codec guard methods and 26 Python cases before the candidate run. Fixture dispositions include expected schema/semantic rejection and contextual refusal; their tests pass only when the exact frozen outcome is observed. D00 requires no physical GameTests or client scenarios because it adds no physical implementation. The standalone native fixture is compiled and its actual packaging checked. Two distinct client identities are configured for later stages; they have no observed-client credit here.

The latest user instruction permits an early Actions/Docker run for observed local dependency/test blockers. `blocking-request.json` deliberately requests this D00 run. Development pushes without a request change do not launch owner CI. `qualification-request.template.json` is reserved for D11; it is not an active final request. One workflow/job runs sequential checks within 90 minutes and always uploads partial logs. All ten baseline workflow changes must match the frozen trigger patch byte for byte. No docs workflows are added.

The CI workflow is the executable command plan. Locally, with the established Java 25/SDK/Docker runtime:

```bash
python3 e2e/parallel_d/contracts/verify_contract.py --contract e2e/parallel_d/contracts
python3 e2e/parallel_d/materialize_docs.py --root ../CognitiveCraft-Documentation-D
python3 e2e/parallel_d/ownership.py --docs-root ../CognitiveCraft-Documentation-D
bash gradlew --no-daemon --no-build-cache --stacktrace -I e2e/parallel_d/native/fixture.init.gradle :core:test :providers:test :fabric:test :fabric:build :fabric:gameTestJar :fabric:parallelDNativeJar
python3 e2e/runtime.py prepare
python3 e2e/runtime.py compile
python3 -m unittest discover -s e2e/parallel_d -p 'test_*.py'
python3 e2e/parallel_d/docs_check.py --docs-root ../CognitiveCraft-Documentation-D --stage D00
```

For stage collection use the workflow's environment variables, image identity collection and isolated Compose tools service. `stage.py run --stage D00 --built --root ROOT` consumes the built release and writes raw observations. It does not decide success. `verify.py --stage D00 --root ROOT --source SHA --tree TREE --jar fabric/build/libs/ai-villages-0.1.0.jar --output ROOT/qualified.json` reads actual XML and hashes independently. Unknown stages, skips, missing or duplicate cases, altered JARs, unmanifested frames and invented integrated authority are rejected. Module regressions block advancement. Source must be clean and committed before measurement. `OWNED_GATES_PASS` covers D00 only; D11 readiness remains unqualified.

`docs-snapshot.json` contains exact Git trees/commits and owned prequalification docs. The sparse materializer verifies their original SHA identities and leaves protected historical blobs unmaterialized. It permits credential-free docs parity checks in source CI without copying private historical document contents. After the exact source run, reports go to the docs branch and artifacts; the final docs SHA belongs in the external handoff, avoiding a self-referential source/report commit.

Compose reuses `e2e/Dockerfile`, SDKs and client/server APIs. Its internal D network, namespace, port 25661 and server/two-client mounts are isolated from A/B/C. Do not mount active peer worlds. The tools image extends the established runtime with Git only. No new model/backend, loader migration, global harness override or production authority factory is introduced.
