# RB-001 implementation and revised acceptance report — pending

Baseline `bf6b985d3d37c4a1529ae31723bc672f96e69ac2`; original documentation-first commit `6d3da32c6e777239117e189386fea0e036c2651e`. The updated additive prompt SHA256 is `acc5e3a12c343a7d310ce573a6bf56c3500aab30cab32d338942aabb532eb940`. Its prompts/index/contracts/backlog were published in documentation commit `d839e58f3208f0e1f337cdf951f5d86a147a639d` before revised implementation edits. [PR #24](https://github.com/senavirathne/CognitiveCraft-public/pull/24) is published and unmerged. The user's explicit instruction authorizes Actions and merge after successful required checks. IMP-021 PR #20 remains unmerged; RB-002 is outside scope.

`cognitivecraft:deliver_oak_logs` v1 independently acquires 1–8 actual `minecraft:oak_log` items through existing resolution/research/repository, Jobs/dispatch/leases and gateway/native owners. Actor, quantity, authorized source and destination remain runtime parameters. Success requires attributable harvest/pickup/deposit evidence even when older logs are in inventory or storage. No recipe, consumer Job, crafting station, product dependency, blueprint action, second inventory or new scheduler is introduced. Historical receipts remain evidence; future consumers must inspect actual available authorized stock through the existing owners.

Canonical syntax: `/aivillage kernel gather-oak <actor-id> <amount> <min-x> <min-y> <min-z> <max-x> <max-y> <max-z> <chest-x> <chest-y> <chest-z>`. Language: `/aivillage kernel ask Ada, collect 4 oak logs and deliver them to the nearest suitable chest.` Both enter the normal lifecycle; neither directly edits blocks or grants control.

Contracts/IR/request and original wheat primitive versions remain 1; original wheat fingerprints and receipt meaning are preserved. Oak resource receipts have their own schema 1. Jobs schema-1 wheat rows remain unchanged; resource rows use schema 2 under the same journal/root and backward readers. Source-block lease units (-2) and container-item units (-1) remain distinct physical forms. Unknown future/corrupt data remains preserved and inactive. [Full contract](RB-001-contracts.md) records limits, schema decisions, pickup isolation, capacity and recovery.

## Revised definitions and mandatory gates

At least 31 scoped JUnit cases include the original 27 plus OakLogPrerequisiteStockTest (2), scattered owned-drop approach (1) and stale-drop terminal cleanup (1). ResourceDeliveryTest now executes the actual bounded fixture runner and checks the standalone typed interface/dependencies. Native cold/warm and GP-RB-01 require the same admitted method for 1/4/7, different source and destination bindings, no downstream Job, actual contamination/conservation, stopped-backend saved-world reuse and zero known-execution inference. The connected gate checks exact terminal outcomes, a genuine language backend, two principals and actual SIGKILL/no replay. `scripts/verify-rb001.py` rejects empty/skipped/failed selectors and missing standalone evidence. All affected existing workflows must pass too. The earlier revised definitions executed; the owned-drop correction remains pending final-head Actions.

## Executed earlier attempts and production defects

The first head `859c9f1c9627aca1cbddd97f3fbcc33212bd23c3` ran 1095 core tests with four failures. The resolver accepted an oak physical signature as proof of missing wheat support; capability-specific signature filtering repairs that regression. The raw oak nearest-suitable destination parser rejected the required phrase; oak-specific optional-suitable parsing preserves the old wheat policy. Controlled proposal symbols collided with a parameter, and a journal test attempted to skip revisions; fixtures now respect those existing contracts.

At `dade6de4cd10e1330f63e6e930f5407e9d4e1e07`, core XML recorded 1095 and provider XML 68 cases, all passing without errors/skips (artifact 11755073136, ZIP SHA256 `e09cc15a1feaf8fa073caad621ccc7411f72179e32c58d23b4b1bd83f9bc2069`). Native fixture compilation failed on the installed AABB API and was corrected. Genuine oak generation reached NOT_ADMITTED/ARTIFACT_INVALID with no physical credit. Its terminal harness incorrectly matched ADMITTED inside NOT_ADMITTED; the revised assertion compares the exact outcome and preserves rejection evidence.

At `dc3d132902df0f098a9e7f162bb7896ed62f7b9b`, 18/21 PR workflows passed. Actual native oak survival/component effect coverage passed. Controlled KernelSession admission failed MODEL_UNAVAILABLE with zero model calls because the fixture did not enable inference; the revised fixture explicitly opts in only for cold admission and stops on terminal rejection. Existing prerequisite native acceptance failed waiting for owner/actor acknowledgements; real-gameplay qualification and the oak workflow did not pass. These remain unmet gates. The genuine oak brief now explicitly names causal phase order; it still supplies no trusted executable method and cannot bypass compilation, fixtures or real trial.

The revised working-code manifest below is not a tested commit or binary. No local Gradle download/build/test has been used; all builds and tests run in GitHub Actions. The private documentation audit fails before any steps execute; no audit pass is claimed. The unrelated IMP-020.1 report is preserved and excluded from publication.

## Completion

RB-001 is incomplete. Revised standalone/stock assertions, genuine oak admission, language, offline/restart, abrupt interruption and all final-head regressions must pass before merge. Exact final source/JAR/model identities, counts, artifact hashes and actual merge evidence will replace pending entries after verification. [Documentation readiness and RB-T01–14 mapping](https://github.com/senavirathne/CognitiveCraft-Documentation/blob/d839e58f3208f0e1f337cdf951f5d86a147a639d/docs/implementation/RB-001-report.md).

Revised working-code manifest SHA256 `81bc3533738e74fa1e22dfde02772fb9196ff7f2ae1aa9e60d0fce8dabb9d456` across 56 files. [Snapshot](RB-001-source-snapshot.json).


## Corrective checkpoint after revised Actions

Documentation contracts were updated first in `be7519e99d5eb82bd4c64c397e061e2b22919ee0` and `05c8e1b9e5ad51054f1fc8b6f79808c08ac2bbd4`. At source `15a0fa4`, all 29 scoped cases executed; a native push run completed controlled and genuine cold physical admission plus standalone quantity-one reuse in a second chest. Genuine artifact `6a180d9af385862440c678dcba613a91a2d6837762ca7005aa92a019938d3760` resolved after backend shutdown in another JVM with zero model calls, then pickup correctly blocked before full warm delivery. No warm or connected pass is claimed.

Native artifact 11759998917, SHA256 `c9b44c007697364398798b56cc94a360b30d830ef94cac245912d2e07b5193c3`, preserves a separate controlled partial outcome: two deposits and a third harvested item. Saved actual actor/drop coordinates show distance 2.642195, exceeding unchanged reach 2.5. Root cause: source arrival treated a diagonal cell as adjacent. FabricGatewayWorld now selects face neighbors, requires horizontal arrival distance at most 1.15, and repeats that position check at harvest. The native effect case starts diagonal and requires rejection before ordinary movement. Drops are not moved, pickup reach is not widened, and existing wheat movement is unchanged. Native harvesting also checks capacity against the actual eligible loot before mutation.

The additive contract audit found the initial generic oak allocation test allowed historical deposits to complete a new direct acquisition job. JobLifecycleStore now rejects that allocation and equivalent persisted snapshots; the revised regression proves no credit/state change, then completes the new job through its own fresh evidence. Future consumers are not implemented; original wheat allocation rules remain. The connected setup now waits for the actual unrelated item snapshot rather than racing an already-empty chest observation.

These corrections remain unverified until final-head Actions pass. The current working-code manifest is in RB-001-source-snapshot.json; prior manifests and failed attempts are historical checkpoints, not final acceptance.


## Owned-drop movement correction — final acceptance pending

The contract and active prompt were updated first in documentation commit `1cbfcff357a597d971744f715b1e211a78221fe8`. Newer documentation main `1d7a5e857faf9e1b7b923599520c376c33a47999` retains that specification and unrelated IMP-020.1/P2/prompt updates. At source `bad21e5`, 19/21 PR workflows passed. The controlled physical admission and native effect case passed; the genuine PR trial blocked on pickup scatter. The push native job completed genuine cold and stopped-backend same-save warm reuse, but both connected jobs blocked during physical pickup. A separate local-generation smoke timed out after Loom configuration before candidate evidence. None establishes final acceptance.

The existing `approach_oak_log(actor,source)` MOVE operation now selects the first pending owned drop when this run has harvested one. It rechecks the exact tracked UUID, quantity, dimension and OBSERVE/MOVE permissions on every poll, then uses the existing bounded source navigation to its current empty cell. Without a pending drop it retains face-adjacent standing-log selection. The method must approach again after harvest/wait and before pickup; movement grants no receipt or stock credit. The adapter gives this repeated call a distinct result symbol. No new physical primitive, wider reach, moved loot, output top-up or budget refill is introduced. Wheat primitives and lowering remain unchanged.

The real-client fixture now explicitly creates its second destination chest before preloading older stock; that coordinate was absent from the base arena. The new scoped regression proves navigation toward only an owned scattered drop, foreign-stock exclusion and no acquisition credit before physical pickup/deposit. Final-head Actions must execute all 30 scoped cases, genuine admission/language, stopped-backend saved-world reuse, abrupt interruption and affected regressions before merge.


## Client menu-route correction — full acceptance pending

Documentation 33e4740c61dbd0fba75297034d584957555b04b4 records the actual network failure before fixture edits. At source 3bd3949, genuine canonical four-log acquisition, real-language four-log delivery and fresh one-log standalone delivery succeeded; the client menu observer then walked straight into the first chest. Artifact 11768081794 (ZIP SHA256 afec65fe5b150d43ac1d5e20bf074f387b915e957b4efcf9f7846628b387ef60) preserves the actual position and completed Jobs. The RB fixture now uses ordinary north-side movement waypoints before the unchanged second-chest menu assertion. No production change, teleport, wider reach or bypass is introduced. Full final-head offline/SIGKILL/network gates remain pending.


## Oak terminal navigation ownership

Documentation f26e851f1fc8bc88a6c51e4535d0d4d77c0277e1 specifies the cleanup before correction. SurvivalGateway.finish now stops started APPROACH_OAK_LOG navigation on every terminal result, matching the existing movement operations. A regression removes a tracked drop during movement and requires one stop with no subsequent movement or credit. Existing optional native trace is enabled in the Actions job to expose remaining physical failures; it changes no effects or budgets. The prior PR warm attempt blocked after six deposits, and the separate genuine wheat gameplay qualification exhausted its existing budget. Neither is relabeled as a pass; all final-head gates remain required. Scoped minimum is now 31.
