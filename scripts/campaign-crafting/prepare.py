#!/usr/bin/env python3
"""Compile a read-only recipe oracle into the existing test observer, not the mod."""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "e2e"))
from runtime import SDK, java, digest  # noqa: E402


def main():
    output = ROOT / "build/campaign-crafting-oracle"
    output.mkdir(parents=True, exist_ok=True)
    release = ROOT / "fabric/build/libs/ai-villages-0.1.0.jar"
    observer = ROOT / "build/e2e-driver/observer.jar"
    before = digest(release)
    libs = [SDK / "minecraft-client.jar", release, *sorted((SDK / "libraries").rglob("*.jar")),
            *sorted((ROOT / "build/server-sdk/libs").glob("*.jar"))]
    sources = sorted((ROOT / "e2e/campaigns/crafting/observer/java").rglob("*.java"))
    subprocess.run([java("javac"), "--release", "25", "-encoding", "UTF-8", "-proc:none",
                    "-cp", os.pathsep.join(map(str, libs)), "-d", str(output), *map(str, sources)], check=True)
    temporary = observer.with_suffix(".campaign.jar")
    with ZipFile(observer) as source, ZipFile(temporary, "w", ZIP_DEFLATED) as target:
        for name in source.namelist():
            data = source.read(name)
            if name == "fabric.mod.json":
                manifest = json.loads(data)
                entry = "dev.aivillages.e2e.CraftingRecipeOracle"
                if entry not in manifest["entrypoints"]["main"]:
                    manifest["entrypoints"]["main"].append(entry)
                manifest.setdefault("mixins", []).append("crafting-trace.mixins.json")
                data = json.dumps(manifest).encode()
            if not name.startswith("dev/aivillages/e2e/CraftingRecipeOracle"):
                target.writestr(name, data)
        for path in sorted(output.rglob("*.class")):
            target.write(path, str(path.relative_to(output)))
        target.write(ROOT / "e2e/campaigns/crafting/observer/crafting-trace.mixins.json", "crafting-trace.mixins.json")
    temporary.replace(observer)
    with ZipFile(release) as archive:
        assert not any("CraftingRecipeOracle" in name for name in archive.namelist())
    assert digest(release) == before
    print(json.dumps({"productionSha256": before, "observerSha256": digest(observer),
                      "productionUnchanged": True}))


if __name__ == "__main__":
    main()
