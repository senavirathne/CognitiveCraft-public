# Reviewable Actions registration change

The only proposed default-branch change is the exact file
`.github/workflows/generalization-crafting.yml` from this campaign branch.
It contains only `workflow_dispatch`, one job, read-only repository permissions,
Java 25, a single production build, existing Docker Compose services, one report
and one evidence upload. `source_ref` defaults to `test/generalization-crafting`.

This file is prepared but has not been added to main. Neither campaign branch
has been merged. Registration must not alter production or unrelated workflows.

Reason: GitHub's manual workflow registration requirement:
https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow

After registration and authenticated dispatch access, run full mode once using
the exact published campaign SHA. Smoke is available if runtime instrumentation
needs qualification. Preserve failed attempts and do not relabel an unexecuted
campaign as complete.
