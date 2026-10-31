# C interaction contract and evidence harness

C00 is independent of A/D and uses the exact frozen B source. None of these Java classes is registered with the production runtime. The 17 wire kinds are decoded into recursively immutable **untrusted** values; `decodeCandidate` accepts only language candidates. Schema/context validity grants no authority, and there is no public constructor or factory for a trusted projection.

The frozen contract, CI trigger bytes, external freeze manifest and action revisions are copied or pinned here. The contract's 57 fixtures are synthetic. The Java suite includes those 57 cases plus seven strict parser/ingress controls. Python checks cover protected byte/mode changes, real peer history, class dependency descriptors, artifact mutation, duplicate XML, missing frames, fabricated settlement and owned documentation links. A diagnostic Java 17 execution cannot qualify the Java 25 baseline.

`registries/C00.json` freezes the actual schedule and stable XML IDs before candidate measurement. C01 and C02 have implemented cumulative registries. C03–C08 contain supplied specifications with implementation NOT_EXECUTED; `stage.py` refuses them until their own implementation/oracles exist. Future physical/client checks live only in the deferred ledger; C00 executes zero native/client scenarios. Its isolated chat fixture **compiler** is nevertheless mandatory.

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

The frozen `fabric/build.gradle` already has a server-SDK mode for environments where Loom cannot probe Unix sockets. In that environment, run the existing `python3 scripts/headless.py prepare` once and set `ORG_GRADLE_PROJECT_serverJar` to the verified `build/server-sdk/minecraft-server.jar` and `ORG_GRADLE_PROJECT_serverLibraries` to that SDK's `libs` directory for both baseline and candidate commands above. The task list and existing build definitions remain unchanged. Receipts hash the complete prepared SDK against its pinned official archives, and the verifier refuses different baseline/candidate modes or SDK bytes. This mode does not satisfy a native/client scenario.

Gradle XML display names are not unique method identifiers: two existing parameterized methods both emit names such as `[1] BEFORE_STAGE`. The read-only `junit/GradleResults.java` reader uses the pinned Gradle 9.7.0 binary result-store API to retain the distinct legacy method names. Capture retains the original binary store, XML and identity export. Qualification independently re-exports the binary store and reconciles the complete multiset of display names/outcomes with XML before counting unique class/method IDs. It rejects duplicated stable IDs, extra/missing XML rows or altered artifacts; no tests are renamed or instrumented.

Use a new root per retry; the collector refuses to reuse a directory. Raw artifacts and failed/inconclusive candidates remain separate. Results belong in the separate docs checkout, preserving the tested source commit. Record final docs SHA externally to avoid a self-referential report.

## Native fixture and deferred integration

`native/compile.py` verifies the installed 26.3 metadata/client/library checksums and frozen server SDK lock, discovers exact APIs with javap, then invokes javac --release 25 against that SDK and the exact release JAR. Missing APIs/dependencies produce a nonzero compiler gate and a precise symbol/path ledger. Fixture descriptor/entrypoints/Mixin are separate from production descriptors. The fixture sends bounded ordinary chat and observes system chat; it has no Job writer, world mutation, inventory, save, authority or scheduler port.

`compose.yml` consumes an already verified existing runtime **digest**, without a build or new model service. The C project/network and server/two-client world volumes are isolated. Native startup/world-tag/connection schedules and physical or production player UI proof belong to later named C/J owners. No C00 Compose process is launched.

The single final workflow is dormant until C08 changes `qualification-request.json`. Only the template exists at C00. Its branch AND path filter prevents development pushes from requesting an owner run. The identical frozen ten-workflow trigger patch prevents legacy push fan-out on exact C/D/J branches. No PR is opened at C00. Final CI requires an explicitly authorized private docs read credential named `COGNITIVECRAFT_DOCS_READ_TOKEN`; C00 neither creates nor assumes that secret. No docs workflow, main merge or production integration is performed.

Deferred adapters: InteractionIngress; ActivityEvidencePort; A original-root assistance persistence; D selector bridge; native storage selection; A terminal-status projection. See `predecessor-discovery.json` for read-only live A discovery. A remains unqualified; those observations do not change the C base.
# C01 candidate and context extension

C01 adds inert IntentParser, CandidateValidator and ConversationContext APIs under the owned core
namespace. The finite grammar covers all frozen dialogue actions and reply variants. Strict decoding,
exact source spans and authenticated transport correlation precede a candidate routing decision;
no class admits or executes a world action. J supplies real ingress, resolution, confirmation and A
question/authority revalidation. The legacy LanguageRequests action enum is unchanged.

Context is keyed by stable world/principal/citizen IDs and bounded to eight turns, 1024 characters per
utterance, 4096 retained characters, ten minutes idle and 128 live server sessions. Active assistance
pins cannot be evicted to make room. FORGET and logout affect C memory only. Explicit extraction
permits one primary call plus at most one correction to the same selected extractor; replay and late
response fences prevent another request or stale text deletion. C01 contains no genuine provider call.

The C01 cumulative registry includes the C00 cases, 22 new Java cases and 11 new Python cases.
The stage collector and independent oracle accept only C00/C01, verify the exact qualified C00
predecessor, and require fresh complete module/Python results and isolated fixture compilation.
Native/client campaigns and C02 remain unimplemented. Development pushes do not change the
qualification request and must launch no Actions run.

## C02 grounded assistance extension

C02 adds a read-only proposed ActivityEvidencePort, BlockerProjection, PlanningExplanationAdapter and AssistanceFeasibility. It reads the actual frozen B Analysis/Decision types and rechecks bounded virtual material additions against the unchanged planner, coordinator, recipe choices, methods, original policy, budgets and deadline. B residual is available stock rather than missing material. Privacy/unknown/unsupported/capacity blockers cannot become material promises. Multiple exact materials are supported, and absent native choices/admission remain J03 dependencies. No virtual stock is persisted or credited.

The C02 cumulative registry freezes 2415 module cases and 54 Python checks, including all prior owned regressions, 22 new Java cases and 14 new Python checks. Python executes the compiled Java planner and records the exact existing core Gson dependency. C02 has no native/client/live-provider cases and changes no production entrypoint. The actual A adapter and original activity continuation remain deferred to J03. Stop after C02. The user-authorized infrastructure recovery schedule below supersedes the original C08-only request restriction.

The C02 adapter requires explicit current B decision Memory. It never substitutes empty memory in production code, so native B scope/usage monotonicity and remaining replan checks apply to every hypothesis. Trial memory is not persisted or reused as authoritative history.

## Consolidated Docker CI recovery (2026-10-31 user instruction)

The user explicitly superseded the C08-only CI restriction after local execution disconnected. Ready C candidates may request one consolidated infrastructure-recovery run. The existing parallel-c-qualification workflow keeps its exact branch AND qualification-request path trigger, with one sequential 90-minute job. ci_host.py builds/reuses the existing e2e/Dockerfile stack plus Git/unzip harness prerequisites, inspects and caches its exact image ID, and executes ci.py inside Docker. No model credential or Git token enters the container.

All cumulative modules, complete C Python schedule, existing drivers, isolated fixture compiler, ownership/contract/docs audits and independent oracle run on the one built JAR. Cache/partial artifact publication runs even after failure. Missing private-docs credentials do not skip source tests or qualify the docs gate. GitHub Actions requires COGNITIVECRAFT_DOCS_READ_TOKEN for the private docs checkout; the connected ChatGPT GitHub app is separate authentication. Each request pins docs, contract, implemented stage, purpose and nonce; source/run/attempt/image are measured from the actual workflow. C08 still requires final cumulative CI including every then-required native/client/provider case. No unimplemented or future stage is credited.
