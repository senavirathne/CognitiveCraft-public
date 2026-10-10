"""Bounded elevated test setup. Never called while an action under test is active."""
from __future__ import annotations

import json
from pathlib import Path

ARENA = {"floorY": 200, "bounds": [-2, 199, -2, 22, 205, 16],
         "spawnA": [2.5, 201, 5.5], "spawnB": [15.5, 201, 5.5],
         "penA": [1, 201, 1, 3, 203, 4], "penB": [14, 201, 1, 16, 203, 4],
         "gateA": [2, 201, 4], "gateB": [15, 201, 4],
         "source": [6, 201, 6, 7, 201, 8], "destination": [9, 201, 7],
         "reuseSource": [6, 201, 10, 7, 201, 12], "reuseDestination": [7, 201, 9],
         "destinationB": [17, 201, 7], "partialDestination": [18, 201, 10],
         "unreachableDestination": [20, 201, 13], "matureUnits": 6, "reuseUnits": 6}


def commands(two: bool = True, legacy: bool = False) -> list[str]:
    result = ["gamerule minecraft:mob_griefing true", "gamerule minecraft:do_mob_spawning false",
              "gamerule minecraft:random_tick_speed 0", "gamerule minecraft:do_daylight_cycle false",
              "time set day", "setworldspawn 2 201 5", "fill -2 200 -2 22 200 16 minecraft:stone",
              "fill -2 201 -2 22 205 -2 minecraft:glass", "fill -2 201 16 22 205 16 minecraft:glass",
              "fill -2 201 -2 -2 205 16 minecraft:glass", "fill 22 201 -2 22 205 16 minecraft:glass",
              "fill -2 206 -2 22 206 16 minecraft:glass",
              "fill -1 199 -1 21 199 15 minecraft:sea_lantern"]
    for x in ([1, 14] if two else [1]):
        result += [f"fill {x} 201 1 {x+2} 202 1 minecraft:oak_fence",
                   f"fill {x} 201 1 {x} 202 4 minecraft:oak_fence",
                   f"fill {x+2} 201 1 {x+2} 202 4 minecraft:oak_fence",
                   f"fill {x} 201 4 {x+2} 202 4 minecraft:oak_fence",
                   f"setblock {x+1} 201 4 minecraft:oak_fence_gate[facing=south,open=false]",
                   f"setblock {x+1} 202 4 minecraft:air",
                   f"summon minecraft:villager {x+1}.5 201 2.5 {{PersistenceRequired:1b}}"]
    for x in (6, 7):
        for z in (6, 7, 8, 10, 11, 12):
            result += [f"setblock {x} 200 {z} minecraft:farmland[moisture=7]",
                       f"setblock {x} 201 {z} minecraft:wheat[age=7]"]
    result += ["setblock 8 200 7 minecraft:water", "setblock 8 200 11 minecraft:water",
               "setblock 9 201 7 minecraft:chest", "setblock 7 201 9 minecraft:chest",
               "setblock 17 201 7 minecraft:chest", "setblock 4 200 10 minecraft:farmland[moisture=7]",
               "setblock 4 201 10 minecraft:wheat[age=2]",
               'setblock 18 201 10 minecraft:chest{Items:[{Slot:0b,id:"minecraft:wheat",count:3}]}',
               "fill 11 201 9 11 202 10 minecraft:stone",
               "fill 19 201 12 21 204 14 minecraft:stone",
               "setblock 20 201 13 minecraft:chest", "setblock 20 202 13 minecraft:air"]
    if legacy:
        result += ["setblock 10 201 7 minecraft:crafting_table",
                   "fill 23 200 5 62 200 7 minecraft:stone",
                   "fill 23 201 4 62 203 4 minecraft:glass",
                   "fill 23 201 8 62 203 8 minecraft:glass",
                   "fill 63 201 4 63 203 8 minecraft:glass",
                   "fill 23 204 4 63 204 8 minecraft:glass",
                   "fill 22 201 5 22 202 7 minecraft:air"]
    return result


def write(root: Path, kind: str) -> None:
    (root / "fixture.json").write_text(json.dumps({"kind": kind, **ARENA}, indent=2) + "\n")
