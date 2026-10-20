"""Bounded routes through the F1 client's observed foot-block grid, never world writes."""
from collections import deque
import math
import re

MAX_CELLS = 512
LEVEL = 201
PASSABLE = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:wheat",
            "minecraft:short_grass", "minecraft:tall_grass", "minecraft:water",
            "minecraft:torch", "minecraft:wall_torch"}


def walkable_block(state):
    if not isinstance(state, str):
        return False
    match = re.search(r"minecraft:[a-z_]+", state)
    if not match:
        return False
    block = match.group()
    return block in PASSABLE or block.endswith("_fence_gate") and "open=true" in state


def _grid(blocks):
    if len(blocks) > MAX_CELLS:
        raise ValueError("Bounded observed fixture grid required")
    cells = {}
    for key, state in blocks.items():
        try:
            x, y, z = map(int, key.split(","))
        except (ValueError, AttributeError):
            continue
        if y == LEVEL:
            cells[x, z] = state
    return cells


def in_observed_area(blocks, point):
    cells = _grid(blocks)
    if not cells:
        return False
    x, z = math.floor(point[0]), math.floor(point[2])
    return min(p[0] for p in cells) <= x <= max(p[0] for p in cells) and \
        min(p[1] for p in cells) <= z <= max(p[1] for p in cells)


def walk_route(blocks, position, target, reach=2.7):
    """Choose cardinal cell centers; unknown cells and closed obstacles remain blocked.

    A player touching the narrow side of a fence can occupy its grid cell without
    intersecting its collision box. Such a start may exit only toward the nearest
    cell sides, never cross the fence through its center.
    """
    if not math.isfinite(reach) or reach <= 0:
        raise ValueError("Bounded observed fixture grid and positive reach required")
    cells = _grid(blocks)
    x, _, z = position
    start = math.floor(x), math.floor(z)
    if start not in cells:
        return None
    radius = reach - min(.2, reach / 2)
    goal = target[0] + .5, target[2] + .5
    queue = deque([start])
    parents = {start: None}
    directions = ((1, 0), (-1, 0), (0, 1), (0, -1))
    while queue:
        cell = queue.popleft()
        if walkable_block(cells.get(cell)) and math.hypot(
                goal[0] - cell[0] - .5, goal[1] - cell[1] - .5) <= radius:
            path = [cell]
            while parents[path[-1]] is not None:
                path.append(parents[path[-1]])
            path.reverse()
            return [(cx + .5, cz + .5) for cx, cz in (path[1:] or path)]
        choices = directions
        if cell == start and not walkable_block(cells[cell]):
            choices = ((-1 if x - start[0] < .5 else 1, 0),
                       (0, -1 if z - start[1] < .5 else 1))
        for dx, dz in choices:
            neighbor = cell[0] + dx, cell[1] + dz
            if neighbor not in parents and walkable_block(cells.get(neighbor)):
                parents[neighbor] = cell
                queue.append(neighbor)
    return None
