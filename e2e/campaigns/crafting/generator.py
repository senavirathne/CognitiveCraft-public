"""Shared behavioral definitions, exhaustive alternatives and seeded dimension coverage."""
from __future__ import annotations

from collections import Counter
from itertools import combinations, product
import random

from .oracle import grids, sha

M = "minecraft:"
OAK, BIRCH, STICK, AXE, TABLE = (M + x for x in
                               ("oak_planks", "birch_planks", "stick", "wooden_axe", "crafting_table"))
LOG = M + "oak_log"
COMMANDS = {OAK: "make-planks", BIRCH: "make-birch-planks", STICK: "make-sticks",
            AXE: "make-axes", TABLE: "make-tables"}


def pairwise(dimensions, rng):
    """Greedy covering array, with the actual achieved pair denominator recorded."""
    keys = list(dimensions)
    rows = [dict(zip(keys, values)) for values in product(*(dimensions[k] for k in keys))]
    rng.shuffle(rows)
    def pairs(row):
        return {(a, str(row[a]), b, str(row[b])) for a, b in combinations(keys, 2)}
    uncovered = set().union(*(pairs(row) for row in rows))
    selected = []
    while uncovered:
        row = max(rows, key=lambda r: len(pairs(r) & uncovered))
        if not pairs(row) & uncovered:
            raise ValueError("Uncoverable parameter pair")
        selected.append(row)
        uncovered -= pairs(row)
        rows.remove(row)
    return selected


def blueprint(n, material=OAK):
    width = min(n, 4)
    return {"schema": 1, "dimensions": {"x": width, "y": 1, "z": (n + width - 1) // width},
            "constraints": {"supportedGround": True, "nonHazardous": True, "perimeter": 0},
            "palette": [{"block": material, "properties": {}}],
            "cells": [{"x": i % width, "y": 0, "z": i // width, "palette": 0} for i in range(n)]}


def generate(recipes, seed, *, held_out=False):
    """No world setup or hand-authored material-specific test executors live here."""
    rng = random.Random(seed)
    cases = []
    def add(family, mode, **params):
        common = {"actor": rng.randrange(2), "layout": rng.choice(["packed", "split", "last"]),
                  "location": rng.randrange(3), "container": rng.choice(["chest", "barrel"])}
        common.update(params)
        case = {"family": family, "mode": mode, "seed": seed, "heldOut": held_out, **common}
        case["id"] = family + "-" + sha(case)[:14]
        if case["id"] not in {c["id"] for c in cases}:
            cases.append(case)

    # Every live ingredient alternative is explicit, including bark/stripped inputs.
    for key, recipe in sorted(recipes.items()):
        if not key.endswith("_planks"):
            continue
        for ingredient in recipe["ingredients"][0]:
            add("P1", "recipe", recipe=key, grid=grids(recipe, {0: ingredient}),
                quantity=rng.choice([1, 3, 5, 7, 11]), surplus=rng.randrange(3), equivalence=True,
                output=recipe["output"], missingAdapter=recipe["output"] not in COMMANDS)
    dims = {"quantity": [1, 4, 7, 13, 63, 64], "layout": ["packed", "split", "last"],
            "container": ["chest", "barrel"], "location": [0, 1, 2], "actor": [0, 1]}
    for row in pairwise(dims, rng):
        key = rng.choice([OAK, BIRCH]); recipe = recipes[key]
        add("P1", "recipe", recipe=key, grid=grids(recipe, {0: M + key.split(":")[1].replace("planks", "log")}),
            output=key, surplus=2, equivalence=False, **row)

    for key in (STICK, AXE, TABLE):
        recipe = recipes[key]
        groups = [i for i, group in enumerate(recipe["ingredients"]) if OAK in group]
        if not groups:
            raise ValueError("Live vanilla recipe has no discovered plank ingredient: " + key)
        allowed = sorted(set.intersection(*(set(recipe["ingredients"][i]) for i in groups)))
        for i, material in enumerate(allowed):
            quantity = rng.randint(1, 4) if key in (AXE, TABLE) else rng.choice([1, 3, 4, 7, 11])
            grid = grids(recipe, {g: material for g in groups})
            add("P2", "recipe", recipe=key, output=key, grid=grid, quantity=quantity,
                surplus=1, equivalence=True, material=material, mixed=False)
            if len(allowed) > 1:
                mixed = list(grid)
                slots = [s for s, g in enumerate(recipe["slots"]) if g in groups]
                mixed[slots[0]] = allowed[(i + 1) % len(allowed)]
                add("P2", "recipe", recipe=key, output=key, grid=mixed, quantity=1,
                    surplus=0, equivalence=True, material=material, mixed=True)

    distributions = ["ready", "logs", "partial", "sticks", "held", "split-containers", "insufficient"]
    for i in range(14):
        add("P3", "compose", output=AXE, quantity=1 + i % 3,
            distribution=distributions[i % len(distributions)], worldLogs=0, variant=i)
    for event in ("remove-input", "fill-destination", "break-facility", "competing-actors"):
        key = AXE if event == "break-facility" else OAK
        recipe = recipes[key]
        add("P3", "recipe", output=key, recipe=key,
            grid=grids(recipe, {i: OAK if OAK in group else LOG if LOG in group else group[0]
                               for i, group in enumerate(recipe["ingredients"])}),
            quantity=2 if key == AXE else 7, surplus=0, dynamic=event,
            equivalence=False, actor=0, location=0)
    targets = [AXE, TABLE, BIRCH]
    for i in range(15):
        add("P4", "compose", output=targets[i % 3], quantity=1 + i % 4,
            distribution=["ready", "logs", "partial", "sticks", "gather"][i // 3],
            worldLogs=8 if i // 3 == 4 else 0, variant=i)
    facility_states = ["existing", "stored-item", "produce-item", "raw", "occupied", "unsupported-floor",
                       "entity-collision", "held-item", "direct", "reuse", "absent", "actor-collision"]
    for i, state in enumerate(facility_states):
        add("P5", "facility", output=AXE if state not in {"direct", "occupied", "unsupported-floor",
            "entity-collision", "actor-collision", "held-item"} else TABLE,
            quantity=1, facility=state, variant=i, worldLogs=4 if state == "raw" else 0)
    for i in range(16):
        add("P6", "blueprint", body=blueprint([1, 5, 8, 13][i % 4]),
            rotation=i % 4, completed=i % 3, distribution=["ready", "logs", "partial", "insufficient"][i // 4],
            separateSource=i == 15, variant=i)
    boundaries = ["before-withdrawal", "after-withdrawal", "after-intermediate", "after-craft",
                  "before-deposit", "after-placement", "before-checkpoint", "after-checkpoint"]
    for i in range(16):
        add("P7", "recovery", output=TABLE if i % 8 == 5 else AXE, quantity=1,
            boundary=boundaries[i % 8], interruption="cancel" if i < 8 else "restart",
            distribution="logs", variant=i)
    # Legacy food is player-accessible but has a separate executor and no kernel adapter.
    for i in range(12):
        add("P8", "legacy", output=M + "bread", wheat=3 * (1 + i % 6) + i % 3,
            facility="existing" if i < 10 else "absent", variant=i)
    # Pair every reachable material substitution with an otherwise identical
    # canonical-input control. A failed control makes the comparison inconclusive.
    controls = []
    for case in cases:
        if case["mode"] != "recipe" or not case.get("equivalence") or case.get("missingAdapter"):
            continue
        recipe = recipes[case["recipe"]]
        canonical = {}
        for i, group in enumerate(recipe["ingredients"]):
            canonical[i] = (OAK if OAK in group else LOG if LOG in group else
                            M + "birch_log" if M + "birch_log" in group else group[0])
        grid = grids(recipe, canonical)
        if grid == case["grid"]:
            continue
        control = {**case, "grid": grid, "equivalence": False, "mixed": False,
                   "material": canonical.get(0), "pairedControlFor": case["id"]}
        control["id"] = case["family"] + "-control-" + sha(control)[:14]
        case["metamorphicControlId"] = control["id"]
        controls.append(control)
    cases.extend(controls)
    return cases


def select(cases, mode):
    if mode == "full":
        return cases
    result = []
    for family in sorted({c["family"] for c in cases}):
        group = [c for c in cases if c["family"] == family]
        # Smoke deliberately includes one supported recipe and one substituted recipe.
        if family in ("P1", "P2"):
            supported = [c for c in group if set(c.get("grid", [])) <= {"", LOG, M + "birch_log", OAK, STICK}]
            result.extend((supported or group)[:1])
            alternatives = [c for c in group if c not in result and not c.get("missingAdapter")]
            result.extend((alternatives or [c for c in group if c not in result])[:1])
        else:
            result.append(group[0])
    by_id = {c["id"]: c for c in cases}
    for row in list(result):
        control = by_id.get(row.get("metamorphicControlId"))
        if control and control not in result:
            result.append(control)
    return result
