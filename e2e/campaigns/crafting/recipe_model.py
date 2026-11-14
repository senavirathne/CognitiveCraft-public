"""Finite recipe arithmetic derived from the running game's exported ingredients."""
from __future__ import annotations

from collections import Counter
from dataclasses import dataclass

M = "minecraft:"
OUTPUTS = tuple(M + name for name in ("oak_planks", "birch_planks", "stick", "wooden_axe", "crafting_table"))
PREFERRED = tuple(M + name for name in ("oak_planks", "oak_log", "birch_log", "stick"))


def natural(value, *, positive=False):
    if type(value) is not int or value < (1 if positive else 0):
        raise ValueError("A finite nonnegative integer is required")
    return value


@dataclass(frozen=True)
class Rule:
    output: str
    yield_size: int
    ingredients: tuple[tuple[str, int], ...]


def canonical_grid(recipe):
    groups = recipe["ingredients"]
    if not groups or any(not group for group in groups):
        raise ValueError("Empty live ingredient alternatives")
    selections = [next((item for item in PREFERRED if item in group), group[0]) for group in groups]
    slots = recipe["slots"]
    if not slots or len(slots) > 9 or any(type(i) is not int or i < -1 or i >= len(groups) for i in slots):
        raise ValueError("Invalid live ingredient slot mapping")
    return ["" if i == -1 else selections[i] for i in slots]


def rules(recipes):
    """The selected five producers match the frozen capability domain, not a plan."""
    result = {}
    for output in OUTPUTS:
        recipe = recipes[output]
        if recipe["output"] != output or recipe.get("remainderCount", 0) != 0 or recipe.get("special", False):
            raise ValueError("Unsupported or inconsistent exported vanilla recipe")
        count = natural(recipe["count"], positive=True)
        inputs = Counter(item for item in canonical_grid(recipe) if item)
        result[output] = Rule(output, count, tuple(sorted(inputs.items())))
    return result


def dependency_order(productions):
    ordered, active, done = [], set(), set()
    def visit(output):
        if output in done:
            return
        if output in active:
            raise ValueError("Cyclic recipe network cannot prove a unique production balance")
        active.add(output)
        for ingredient, _ in productions[output].ingredients:
            if ingredient in productions:
                visit(ingredient)
        active.remove(output); done.add(output); ordered.append(output)
    for output in productions:
        visit(output)
    return ordered


def reconcile(before, after, productions):
    """Back-substitute an acyclic integer recipe system over actual physical deltas."""
    remaining = Counter({key: after.get(key, 0)-before.get(key, 0) for key in before.keys() | after.keys()})
    original = {key: value for key, value in remaining.items() if value}
    batches = {}
    for output in reversed(dependency_order(productions)):
        rule = productions[output]
        units = remaining[output]
        if units < 0 or units % rule.yield_size:
            return False, {"delta": original, "reason": "No nonnegative whole live-recipe batch balance", "item": output}
        count = units // rule.yield_size
        batches[output] = count
        remaining[output] -= count * rule.yield_size
        for ingredient, quantity in rule.ingredients:
            remaining[ingredient] += count * quantity
    unexplained = {key: value for key, value in remaining.items() if value}
    return not unexplained, {"delta": original, "batches": batches, "unexplained": unexplained,
                             "recipeRules": {key: {"yield": rule.yield_size, "inputs": dict(rule.ingredients)}
                                             for key, rule in productions.items()}}


def demands(productions, goals, stock):
    """Independently calculate mathematical material demand and whole-batch surplus.

    Each goal names its allocation semantics. A MAKE goal never credits old target
    items; SUPPLY may use existing stock; placement may forbid manufacturing its item.
    This arithmetic neither schedules primitives nor claims a physical execution.
    """
    dependency_order(productions)
    free = Counter({item: natural(quantity) for item, quantity in stock.items()})
    missing, batches = Counter(), Counter()
    def make(item, quantity):
        rule = productions.get(item)
        if rule is None:
            missing[item] += quantity
            return
        count = (quantity + rule.yield_size - 1) // rule.yield_size
        batches[item] += count
        for ingredient, amount in rule.ingredients:
            supply(ingredient, count * amount)
        free[item] += count * rule.yield_size - quantity
    def supply(item, quantity, *, allow_make=True):
        taken = min(free[item], quantity)
        free[item] -= taken
        quantity -= taken
        if quantity:
            if allow_make:
                make(item, quantity)
            else:
                missing[item] += quantity
    for goal in goals:
        quantity = natural(goal["quantity"])
        if goal.get("consumeExisting", False):
            supply(goal["item"], quantity, allow_make=goal.get("allowMake", True))
        elif quantity:
            make(goal["item"], quantity)
    return {"goals": goals, "missingRaw": {key: value for key, value in missing.items() if value},
            "craftBatches": dict(batches), "mathematicalSurplus": {key: value for key, value in free.items() if value},
            "feasible": not any(missing.values())}
