"""Independent recipe inputs from official game data and live RecipeManager probes."""
from __future__ import annotations

from collections import Counter
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

from .recipe_model import natural


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"))


def sha(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def candidates(jar: Path):
    """Discover recipes, never infer a wood list or copy the mod's recipe registry."""
    result = []
    with ZipFile(jar) as archive:
        for name in sorted(archive.namelist()):
            if not name.startswith("data/minecraft/recipe/") or not name.endswith(".json"):
                continue
            key = name.removeprefix("data/minecraft/recipe/").removesuffix(".json")
            if key not in {"stick", "wooden_axe", "crafting_table", "bread"} and not key.endswith("_planks"):
                continue
            raw = archive.read(name)
            data = json.loads(raw)
            if data["type"] == "minecraft:crafting_shaped":
                pattern = data["pattern"]
                width, height = len(pattern[0]), len(pattern)
            elif data["type"] == "minecraft:crafting_shapeless":
                width, height = len(data["ingredients"]), 1
            else:
                continue
            result.append({"id": "minecraft:" + key, "width": width, "height": height,
                           "officialDataSha256": hashlib.sha256(raw).hexdigest(), "raw": data})
    if not {"minecraft:stick", "minecraft:wooden_axe", "minecraft:crafting_table",
            "minecraft:oak_planks", "minecraft:birch_planks", "minecraft:bread"} <= {r["id"] for r in result}:
        raise ValueError("Official Minecraft recipe data is incomplete")
    return result


def grids(recipe, alternatives=None):
    """Fill a live recipe's slot mapping with homogeneous or mixed ingredients."""
    alternatives = alternatives or {}
    return ["" if i < 0 else alternatives.get(i, recipe["ingredients"][i][0])
            for i in recipe["slots"]]


def requirements(probe, quantity):
    if not probe.get("matches") or probe.get("remainderCount") != 0:
        raise ValueError("An actual matching, remainder-free vanilla probe is required")
    quantity = natural(quantity, positive=True)
    yield_size = natural(probe["count"], positive=True)
    if not isinstance(probe.get("grid"), list) or not 1 <= len(probe["grid"]) <= 9 or not all(
            isinstance(item, str) for item in probe["grid"]):
        raise ValueError("A bounded actual crafting grid is required")
    batches = (quantity + yield_size - 1) // yield_size
    return {"batches": batches, "produced": batches * yield_size,
            "surplus": batches * yield_size - quantity,
            "consumed": {k: v * batches for k, v in Counter(x for x in probe["grid"] if x).items()},
            "output": probe["output"], "yield": yield_size}


def rotated_cells(body, origin, turns):
    """Geometric quarter turns from the blueprint data contract, independent of its plan."""
    cells = [(c["x"], c["y"], c["z"], c["palette"]) for c in body["cells"]]
    width, depth = body["dimensions"]["x"], body["dimensions"]["z"]
    for _ in range(turns):
        cells = [(depth - 1 - z, y, x, p) for x, y, z, p in cells]
        width, depth = depth, width
    return [[origin[0] + x, origin[1] + y, origin[2] + z, p] for x, y, z, p in cells]
