# C00 interaction contract and evidence harness

C00 is independent of A/D and uses the exact frozen B source. None of these Java classes is registered with the production runtime. The 17 wire kinds are decoded into recursively immutable **untrusted** values; `decodeCandidate` accepts only language candidates. Schema/context validity grants no authority, and there is no public constructor or factory for a trusted projection.

The frozen contract, CI trigger bytes, external freeze manifest and action revisions are copied or pinned here. The contract's 57 fixtures are synthetic. The Java suite includes those 57 cases plus seven strict parser/ingress controls. Python checks cover protected byte/mode changes, real peer history, class dependency descriptors, artifact mutation, duplicate XML, missing frames, fabricated settlement and owned documentation links. A diagnostic Java 17 execution cannot qualify the Java 25 baseline.

`registries/C00.json` freezes the actual schedule and stable XML IDs before candidate measurement. C01–C08 registry files contain supplied specifications with implementation NOT_EXECUTED. `stage.py` refuses them until their own implementation/oracles exist. Future physical/client checks live only in the deferred ledger; C00 executes zero native/client scenarios. Its isolated chat fixture **compiler** is nevertheless mandatory.

## Local qualification

Use the existing Java 25/E2E SDK. `COGNITIVECRAFT_E2E_CACHE` is the existing cache override. Discovery only reads explicitly allowed environment keys; it does not create a model server or modify other owners' containers. The separate documentation checkout must be on the exact frozen B docs lineage.

Commit the source candidate before measurement. From the source checkout:

```bash
git worktree add --detach ../CognitiveCraft-B-baseline 12fb3af99e0c9496171b89d4dd2ffff3f5c7a824
python3 e2e/parallel_c/build.py --repo ../CognitiveCraft-B-baseline --output build/e2e/parallel-c/baseline
python3 e2e/parallel_c/build.py --output build/e2e/parallel-c/candidate
python3 e2e/runtime.py prepare
python3 e2e/runtime.py compile
python3 -m unittest discover -s e2e/parallel_c -p 'test_*.py'
python3 e2e/parallel_c/stage.py run --stage C00 --built --root build/e2e/parallel-c/C00
python3 e2e/parallel_c/verify.py --stage C00 --root build/e2e/parallel-c/C00 --source "$(git rev-parse HEAD)" --tree "$(git rev-parse 'HEAD^{tree}')" --jar fabric/build/libs/ai-villages-0.1.0.jar --output build/e2e/parallel-c/C00/qualified.json
python3 e2e/parallel_c/docs_check.py --docs-root ../CognitiveCraft-Documentation-C --owned-prefix docs/implementation/parallel-c --stage C00
python3 e2e/parallel_c/ownership.py --base 12fb3af99e0c9496171b89d4dd2ffff3f5c7a824 --docs-base 9864841ff76d867fba2ccdab87199d3556d19f22 --docs-root ../CognitiveCraft-Documentation-C --contract-sha256 e1e97de64c8c8934d51b47bba143a1fccb49e9fedb8882380fc5cdd3fe008cb3
```

Build receipts preserve the exact Gradle command, source/tree/JAR, Java version, logs and all three modules' raw XML. The independent verifier recomputes cases/counters from XML, checks the complete baseline and owned registry, all hashes, native compiler/API evidence, and reruns current ownership/docs checks. It rejects missing mandatory observations and never accepts native JSON pass flags. `--static-only` on the ownership CLI is a diagnostic subset; its report retains BLOCKED_DEPENDENCY if target bytecode/release packaging is missing.

Use a new root per retry; the collector refuses to reuse a directory. Raw artifacts and failed/inconclusive candidates remain separate. Results belong in the separate docs checkout, preserving the tested source commit. Record final docs SHA externally to avoid a self-referential report.

## Native fixture and deferred integration

`native/compile.py` verifies the installed 26.3 metadata/client/library checksums and frozen server SDK lock, discovers exact APIs with javap, then invokes javac --release 25 against that SDK and the exact release JAR. Missing APIs/dependencies produce a nonzero compiler gate and a precise symbol/path ledger. Fixture descriptor/entrypoints/Mixin are separate from production descriptors. The fixture sends bounded ordinary chat and observes system chat; it has no Job writer, world mutation, inventory, save, authority or scheduler port.

`compose.yml` consumes an already verified existing runtime **digest**, without a build or new model service. The C project/network and server/two-client world volumes are isolated. Native startup/world-tag/connection schedules and physical or production player UI proof belong to later named C/J owners. No C00 Compose process is launched.

The single final workflow is dormant until C08 changes `qualification-request.json`. Only the template exists at C00. Its branch AND path filter prevents development pushes from requesting an owner run. The identical frozen ten-workflow trigger patch prevents legacy push fan-out on exact C/D/J branches. No PR is opened at C00. Final CI requires an explicitly authorized private docs read credential named `COGNITIVECRAFT_DOCS_READ_TOKEN`; C00 neither creates nor assumes that secret. No docs workflow, main merge or production integration is performed.

Deferred adapters: InteractionIngress; ActivityEvidencePort; A original-root assistance persistence; D selector bridge; native storage selection; A terminal-status projection. See `predecessor-discovery.json` for read-only live A discovery. A remains unqualified; those observations do not change the C base.
