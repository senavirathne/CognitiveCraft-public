# Failure and architectural analysis — incomplete

## Confirmed infrastructure blocker

Both local batch attempts stopped before runtime construction because `docker` is absent. The retained full attempt requested 144 cases and executed none. First divergence: runner Docker preflight. Resource custody, conservation, navigation, authority, safety and recovery were not observed. No gameplay failure classification is justified.

Reproduction: `python3 e2e/campaigns/world/runner.py --root build/e2e/world-new-attempt --mode full --seed 104729`.

The appropriate correction is to execute on a Docker-capable runner after the missing campaign adapters/assertions are completed. This is a testing infrastructure requirement, not a recommended production fix. No production changes were made.

## Evidence-bounded architectural questions

The production implementation uses native approach geometry, exact item identities, tracked drops, custody, effect-time permissions, durable lease owners and lifecycle generation fences. Source inspection identifies these as verification boundaries; it does not establish that any is faulty.

When real failures are available, retain the first ordered observer/run divergence, actual block/drop/actor/container/player quantities, original attempt and replay count. Minimize the generated parameters without changing production or assertions. Attribute causes to navigation, item lineage/custody, stale observations, leases/authority or durable recovery only where those observations support it. Recommendations and regression assertions must cite the actual counterexample. None is presently confirmed.
