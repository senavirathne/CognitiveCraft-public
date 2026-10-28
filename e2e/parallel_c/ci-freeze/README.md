# Consolidated CI — latest user-requested amendment

**Design only: no workflows were changed or launched while authoring this package.** Use the existing prepared Minecraft E2E environment locally for logical stage gates, and one new workflow per independent track at C08/D11 handoff, then one later J06 workflow. Each has one qualification job containing sequential unit/Python/native/client/oracle phases and one production build reused throughout. No per-stage/per-test workflow, strategy matrix, automatic child workflow or duplicate push+PR trigger. Local OWNED_GATES_PASS permits sequential advancement with workflow fields null; only final CI grants BRANCH_READY/release recommendation. Target: two independent handoff runs plus one deferred integration run; more attempts need an actual failure/correction/infrastructure reason.

Owner workflow push filters require its exact branch AND only e2e/parallel_c|parallel_d|joint_cd/qualification-request.json. Create a request template at stage00, and the actual request only at final handoff. Workflow-file edits or development pushes alone do not launch a run. Final candidate request/nonce deliberately triggers one run; use github.sha for the actual source pin. Do not put a self-referential commit SHA into that request. Final CI reruns the cumulative required coverage on its exact JAR.

The frozen B tree has 50 workflow files. All first trigger blocks were inspected. Ten inherited workflows have unrestricted push branches and would also fire on new core/provider/fabric/e2e changes. Their full files were retrieved to calculate this exact **trigger-only patch**. Manifest SHA-256: `5d687fc1502a583336383785ec136eeab0c1c9e7fd9aae681f0cda2ad62825bc`. Patch SHA-256: `615c932a6d8dd9d57d9cb71b6ac69100fad21d285812a6a6591fbbace42cf6e1`. See [manifest](trigger-patch-manifest.json), [patch](minimal-push-triggers.patch), and [trigger inventory](../evidence/workflow-trigger-audit.json).

This is a narrow amendment to the initial immutable-workflow rule, requested by the user's CI-minimization instruction. C00 and D00 copy one identical architect-authored patch before their first push. It adds push.branches-ignore for exactly `feature/player-assisted-dialogue`, `feature/storage-mobility-logistics` and `integration/qualified-ab-conversation-logistics` in those ten files. All jobs/tests, path filters, main/A/B behavior and pull_request/workflow_dispatch settings remain unchanged. Do not disable workflows globally or edit live A/B branches. No third coding branch or shared editable owner is introduced.

Copy this directory into the owner's e2e/parallel_c/ci-freeze/ or parallel_d/ci-freeze/. C00 commands, substituting parallel_d for D00:

```bash
python3 e2e/parallel_c/ci-freeze/verify_trigger_patch.py --repo . --manifest e2e/parallel_c/ci-freeze/trigger-patch-manifest.json --state before
git apply --check e2e/parallel_c/ci-freeze/minimal-push-triggers.patch
git apply e2e/parallel_c/ci-freeze/minimal-push-triggers.patch
python3 e2e/parallel_c/ci-freeze/verify_trigger_patch.py --repo . --manifest e2e/parallel_c/ci-freeze/trigger-patch-manifest.json --state after
```

The exact before/after digests are the only permitted baseline exceptions. Verify YAML semantically: only the push filter changes; all jobs and other events are deeply equal. Test that C/D/J pushes are excluded but main/A/B retain previous eligibility. The new owner workflow triggers only its exact branch and owned paths, with no duplicate PR trigger and no docs Actions. Inherited required regressions must be invoked inside the combined qualification job when affected; fewer triggers cannot waive tests.

J00 reconciles this identical patch when merging C/D into qualified I00. If I00 changed a legacy workflow, preserve its new body and apply only these three exclusions with reviewed hashes in the integration lock. Never overwrite I00 using B bytes. Inspect independently added A/I00 workflows for J push fan-out before the first J push. Existing PR checks remain intact for the later release PR; consolidation must not silently bypass required check policy.

GitHub workflow_dispatch requires the workflow on the default branch. Use branch push for initial qualification; do not merge to main just to get a Run workflow button. Manual dispatch is optional only after that requirement is met. Do not use skip-ci on qualification commits or cancel another owner's jobs to reduce counts. Keep raw evidence even on failed/incomplete runs.

One-job timeout defaults to 90 minutes. If measured full J06 safety/endurance exceeds one job, use the minimum necessary sequential jobs **inside the same workflow run**, passing the exact built JAR and hashes. Continuous-world/endurance work cannot be divided by resetting its world. A split requires measured justification, not a new workflow per suite; never relax safety gates to meet a run-count target.

Official references: [workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax), [manual dispatch default-branch requirement](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow), [skip instructions and pending required checks](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/skip-workflow-runs). This design uses no skip mechanism.

---

Documentation archive anchor: `d609e9980ca2cde8330042d2332226f50200d6b6` (historical blueprint audit only). Current C/D baseline is the separately pinned B commit; this anchor does not assert current implementation status.
