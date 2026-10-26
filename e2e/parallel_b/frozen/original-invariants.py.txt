"""Physical invariants use game data and measured stock, never the proposed plan."""
from __future__ import annotations

from collections import Counter
import re

from .generator import LOG, OAK, STICK, AXE, TABLE
from .recipe_model import rules, reconcile


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


def composition_balance(before, after, recipes):
    """Accept every order with nonnegative whole batches of actual live recipes."""
    return reconcile(before, after, rules(recipes))


def staging_verification(projection, available, allocation, receipts, root):
    """SUPPLY allocates the observed outstanding bill and may retain extra stock."""
    required = projection["outstanding"]
    allocated = (allocation or {}).get("quantity", 0)
    new_outputs = sum(r["quantity"] for r in receipts if r["stage"] == "DEPOSIT" and r["item"]["id"] == OAK)
    facts = {"expectedAllocation": required, "actualAllocation": allocated, "availableStaged": available,
             "currentlyAllocated": (allocation or {}).get("currentlyAllocated", False),
             "expectedNewOutputs": projection["newOutputRequired"], "actualNewOutputs": new_outputs,
             "rootExecuted": bool(root.get("attempts")), "rootManufactureCredit": root.get("fulfilled", 0)}
    ok = (facts["currentlyAllocated"] and allocated == required and available >= required and
          new_outputs == projection["newOutputRequired"] and not facts["rootExecuted"] and
          facts["rootManufactureCredit"] == 0)
    return ok, facts


def physical_custody(state):
    """Stable material custody, excluding harmless clock/position/network-ID changes."""
    return {"containers": state["containers"], "blocks": state["blocks"],
            "actors": {actor["uuid"]: actor["inventory"] for actor in state["actors"]},
            "drops": sorted((drop["uuid"], drop["item"], drop["count"]) for drop in state["drops"]),
            "players": {player["uuid"]: player.get("assessmentInventory", {})
                        for player in state["knownPlayers"].values()}}


def interruption_verification(request, result, interruption):
    """A success marker must never be relabelled as an interrupted task."""
    views = result.get("views", [])
    root = views[0] if views else {}
    marker = root.get("marker") or {}
    outcome = root.get("outcome") or {}
    if request.get("kind") == "job":
        status, reason = root.get("state"), root.get("reason")
    else:
        status = "INTERRUPTED" if marker.get("phase") == "INTERRUPTED" else outcome.get("status") or marker.get("outcome")
        reason = outcome.get("reason") or marker.get("reason")
    allowed = {"INTERRUPTED"} if interruption == "restart" else {"CANCELLED", "INTERRUPTED"}
    ok = not result.get("completed") and status in allowed and reason in allowed
    return ok, {"expected": sorted(allowed), "actualStatus": status, "actualReason": reason,
                "originalCompleted": result.get("completed"), "request": request.get("identity")}


def classify(*, feasible, completed, equivalent, conservation, delivered, requested,
             unsupported=False, boundary=True, error=None, refusal_reason=None, physical_effects=False):
    if not conservation:
        return "FAIL_SAFETY"
    if completed and delivered != requested:
        return "FAIL_SAFETY"
    if not boundary:
        return "INCONCLUSIVE_BOUNDARY"
    if unsupported:
        return "UNSUPPORTED_NEW_CAPABILITY"
    if feasible and completed and delivered == requested and not error:
        return "PASS"
    if (equivalent and not completed and delivered == 0 and not physical_effects and not error and
            refusal_reason in {"RESOURCE_MISSING", "UNSUPPORTED_PRIMITIVE", "TARGET_INVALID"}):
        return "GAP_RECIPE_EQUIVALENCE"
    if not feasible and not completed and delivered == 0 and not error:
        return "PASS_EXPECTED_REFUSAL"
    return "FAIL_GAMEPLAY"
