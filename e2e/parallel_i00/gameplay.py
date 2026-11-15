"""Minecraft Compose transport for the independent I00 gameplay gates."""
from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "e2e"))
from campaigns.crafting.generator import AXE, STICK, COMMANDS
from campaigns.crafting.harness import CraftingHarness, coord
from suite import atomic, read, require

BRANCH_REF = "refs/heads/integration/activity-runtime-production"
BASELINE = "ea7de8000b2b484b885576c34ed5358274936ce9"
PRODUCTION = ROOT / "fabric/build/libs/ai-villages-0.1.0.jar"
NEW_PLANKS = tuple(f"minecraft:{material}_planks" for material in (
    "acacia", "bamboo", "cherry", "crimson", "dark_oak", "jungle", "mangrove", "pale_oak", "poplar", "spruce", "warped"))
QUALIFICATION_COMPOSE = ROOT / "e2e/parallel_i00/compose-qualification.yml"


def point(value):
    return [value[axis] for axis in ("x", "y", "z")]


def recipe_key(output):
    if output not in (*COMMANDS, *NEW_PLANKS):
        raise ValueError("No integrated player recipe binding for " + output)
    if output == STICK:
        return "sticks"
    return output.removeprefix("minecraft:").replace("_", "-")


def variant_material(output):
    if output not in NEW_PLANKS:
        raise ValueError("No independent variant admission binding for " + output)
    return output.removeprefix("minecraft:").removesuffix("_planks")


def variant_input(output):
    material = variant_material(output)
    suffix = {"bamboo": "block", "crimson": "stem", "warped": "stem"}.get(material, "log")
    return "minecraft:" + material + "_" + suffix


class NativeGameplay(CraftingHarness):
    admission_order = CraftingHarness.admission_order + NEW_PLANKS

    def __init__(self, root, *, profile):
        super().__init__(root, BASELINE)
        metadata = read(self.root / "metadata.json")
        metadata["i00Profile"] = profile
        atomic(self.root / "metadata.json", metadata)

    def compose(self, *args):
        command = ["docker", "compose", "-p", self.project, "-f", str(ROOT / "e2e/compose.yml"),
                   "-f", str(QUALIFICATION_COMPOSE), *args]
        result = subprocess.run(command, cwd=ROOT, env=self.env, text=True, capture_output=True, timeout=400)
        with (self.root / "docker.log").open("a") as log:
            log.write(result.stdout + result.stderr)
        if result.returncode:
            raise RuntimeError("Native I00 Compose command failed: " + " ".join(args[:3]) + ": " + result.stderr[-2000:])
        return result

    def recipe_command(self, actor, output, quantity, source, destination, facility=None):
        tail = source + destination
        if output == AXE and facility:
            tail += facility
        return (f"/aivillage kernel production-activity {actor} {recipe_key(output)} {quantity} " + coord(tail))

    def command_text(self, case):
        if case["mode"] != "recipe":
            return super().command_text(case)
        return self.recipe_command(self.citizens["PlayerA"], case["output"], case["quantity"],
                                   self.source, self.destination, self.facility)

    def admission_command_text(self, case):
        output = case.get("output")
        if output not in NEW_PLANKS:
            return super().command_text(case)
        return (f"/aivillage kernel make-variant-planks {variant_material(output)} {self.citizens['PlayerA']} {case['quantity']} "
                + coord(self.source + self.destination))

    def admission_grid(self, output, recipe):
        if output not in NEW_PLANKS:
            return super().admission_grid(output, recipe)
        ingredient = variant_input(output)
        require(len(recipe["ingredients"]) == 1 and recipe["slots"].count(0) == 1
                and all(slot in (-1, 0) for slot in recipe["slots"])
                and ingredient in recipe["ingredients"][0], "Missing installed canonical variant input: " + ingredient)
        return ["" if slot < 0 else ingredient for slot in recipe["slots"]]


def witness_identity(metadata, source, jar, profile):
    tree = subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], cwd=ROOT, text=True).strip()
    ci = metadata.get("ci", {})
    require((metadata.get("sourceCommit"), metadata.get("sourceTree"), metadata.get("releaseSha256")) ==
            (source, tree, jar) and metadata.get("dirty") is False, "Wrong or dirty native I00 candidate")
    require(metadata.get("topology") == "compose" and metadata.get("minecraft") == "26.3"
            and metadata.get("i00Profile") == profile, "Wrong native runtime or profile")
    require((metadata.get("binaries", {}).get("production") or {}).get("sha256") == jar,
            "Native production copy identity differs")
    require(ci.get("GITHUB_REF") == BRANCH_REF and os.environ.get("GITHUB_REF") == BRANCH_REF,
            "Qualification requires the isolated integration branch")
    for key in ("GITHUB_SHA", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"):
        require(os.environ.get(key) and ci.get(key) == os.environ[key], f"Wrong native event {key}")
    require(source == ci.get("GITHUB_SHA"), "Event source must be the exact native source")
    return {"source": source, "tree": tree, "productionSha256": jar,
            "run": ci["GITHUB_RUN_ID"], "attempt": ci["GITHUB_RUN_ATTEMPT"]}
