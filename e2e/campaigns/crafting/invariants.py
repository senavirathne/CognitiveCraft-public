"""Physical invariants use game data and measured stock, never the proposed plan."""
from __future__ import annotations

from collections import Counter
import re

from .generator import LOG, OAK, STICK, AXE, TABLE


def totals(state, client=None, *, world=True):
    out = Counter()
    for box in state.get("containers", {}).values():
        out.update(box)
    for actor in state.get("actors", []):
        out.update(actor["inventory"])
    for drop in state.get("drops", []):
        out[drop["item"]] += drop["count"]
    if client:
        for slot in client.get("slots", []):
            if slot.get("playerInventory"):
                out[slot["item"]] += slot["count"]
    if world:
        # Count each observed world coordinate once. Vanilla conversion to an item
        # and table placement must not create or destroy the corresponding resource.
        for value in state.get("blocks", {}).values():
            match = re.search(r"minecraft:[a-z0-9_]+", value)
            if match and (match[0].endswith("_log") or match[0] == TABLE):
                out[match[0]] += 1
    return out


def delta(before, after):
    return {k: after[k] - before[k] for k in before.keys() | after.keys() if after[k] != before[k]}


def recipe_balance(before, after, expected):
    d = delta(before, after)
    output, size = expected["output"], expected["yield"]
    produced = d.get(output, 0)
    if produced < 0 or produced % size:
        return False, {"delta": d, "reason": "Output is not a whole vanilla batch"}
    batches = produced // size
    desired = {output: produced}
    for item, consumed in expected["consumed"].items():
        per = consumed // expected["batches"]
        desired[item] = desired.get(item, 0) - per * batches
    desired = {k: v for k, v in desired.items() if v}
    ok = d == desired and batches <= expected["batches"]
    return ok, {"delta": d, "allowedDelta": desired, "batches": batches, "produced": produced}


def composition_balance(before, after):
    """Solve vanilla integer stoichiometry, accepting every valid execution order."""
    d = delta(before, after)
    axes, tables = d.get(AXE, 0), d.get(TABLE, 0)
    stick_units = d.get(STICK, 0) + 2 * axes
    if axes < 0 or tables < 0 or stick_units < 0 or stick_units % 4:
        return False, {"delta": d, "reason": "Invalid final craft or stick-batch balance"}
    sticks = stick_units // 4
    plank_units = d.get(OAK, 0) + 2 * sticks + 3 * axes + 4 * tables
    if plank_units < 0 or plank_units % 4:
        return False, {"delta": d, "reason": "Invalid plank-batch balance"}
    planks = plank_units // 4
    allowed = {LOG: -planks, OAK: 4 * planks - 2 * sticks - 3 * axes - 4 * tables,
               STICK: 4 * sticks - 2 * axes, AXE: axes, TABLE: tables}
    # Birch's separately registered production shares the same vanilla 1:4 rule.
    birch = d.get("minecraft:birch_planks", 0)
    if birch < 0 or birch % 4:
        return False, {"delta": d, "reason": "Invalid birch-plank balance"}
    allowed.update({"minecraft:birch_planks": birch, "minecraft:birch_log": -birch // 4})
    allowed = {k: v for k, v in allowed.items() if v}
    return d == allowed, {"delta": d, "allowedDelta": allowed,
                          "batches": {"planks": planks, "sticks": sticks, "axes": axes, "tables": tables}}


def classify(*, feasible, completed, equivalent, conservation, delivered, requested,
             unsupported=False, boundary=True, error=None):
    if not conservation:
        return "FAIL_SAFETY"
    if completed and feasible and delivered != requested:
        return "FAIL_SAFETY"
    if not boundary:
        return "INCONCLUSIVE_BOUNDARY"
    if unsupported:
        return "UNSUPPORTED_NEW_CAPABILITY"
    if feasible and completed and delivered == requested and not error:
        return "PASS"
    if equivalent and not completed and delivered == 0:
        return "GAP_RECIPE_EQUIVALENCE"
    if not feasible and not completed and delivered == 0 and not error:
        return "PASS_EXPECTED_REFUSAL"
    return "FAIL_GAMEPLAY"
