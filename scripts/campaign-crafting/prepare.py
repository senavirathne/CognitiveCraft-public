#!/usr/bin/env python3
"""Compile a read-only recipe oracle into the existing test observer, not the mod."""
from __future__ import annotations

import json
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "e2e"))
from runtime import SDK, java, digest  # noqa: E402


def main():
    output = ROOT / "build/campaign-crafting-oracle"
    release = ROOT / "fabric/build/libs/ai-villages-0.1.0.jar"
    observer = ROOT / "build/e2e-driver/observer.jar"
    before = digest(release)
    from parallel_b.guards import instruments
    sources = sorted((ROOT / "e2e/campaigns/crafting/observer/java").rglob("*.java"))
    configuration = ROOT / "e2e/campaigns/crafting/observer/crafting-trace.mixins.json"
    source_digest = hashlib.sha256(json.dumps({str(p.relative_to(ROOT)):digest(p)
                for p in [*sources, configuration]}, sort_keys=True).encode()).hexdigest()
    cache = output / "integrity.json"
    if cache.is_file():
        old = json.loads(cache.read_text())
        if old.get("productionSha256") == before and old.get("sourceSha256") == source_digest and old.get("observerSha256") == digest(observer):
            instruments(release, observer)
            print(json.dumps({**old, "sameHashReuse":True}))
            return
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True, exist_ok=True)
    libs = [SDK / "minecraft-client.jar", release, *sorted((SDK / "libraries").rglob("*.jar")),
            *sorted((ROOT / "build/server-sdk/libs").glob("*.jar"))]
    subprocess.run([java("javac"), "--release", "25", "-encoding", "UTF-8", "-proc:none",
                    "-cp", os.pathsep.join(map(str, libs)), "-d", str(output), *map(str, sources)], check=True)
    temporary = observer.with_suffix(".campaign.jar")
    replacement_classes = {str(p.relative_to(output)) for p in output.rglob("*.class")}
    with ZipFile(observer) as source, ZipFile(temporary, "w", ZIP_DEFLATED) as target:
        for name in source.namelist():
            data = source.read(name)
            if name == "fabric.mod.json":
                manifest = json.loads(data)
                entry = "dev.aivillages.e2e.CraftingRecipeOracle"
                if entry not in manifest["entrypoints"]["main"]:
                    manifest["entrypoints"]["main"].append(entry)
                manifest["mixins"] = list(dict.fromkeys([*manifest.get("mixins", []), "crafting-trace.mixins.json"]))
                data = json.dumps(manifest).encode()
            if name not in replacement_classes and name != "crafting-trace.mixins.json":
                target.writestr(name, data)
        for path in sorted(output.rglob("*.class")):
            target.write(path, str(path.relative_to(output)))
        target.write(configuration, "crafting-trace.mixins.json")
    temporary.replace(observer)
    with ZipFile(release) as archive:
        assert not any("CraftingRecipeOracle" in name for name in archive.namelist())
    assert digest(release) == before
    instruments(release, observer)
    integrity = {"productionSha256":before,"observerSha256":digest(observer),"sourceSha256":source_digest,
                 "productionUnchanged":True}
    cache.write_text(json.dumps(integrity, indent=2) + "\n")
    print(json.dumps(integrity))


if __name__ == "__main__":
    main()
