"""Compile the measurement code only into a copy of the isolated observer."""

import argparse
import json
import os
from pathlib import Path
import subprocess
from zipfile import ZIP_DEFLATED, ZipFile

from e2e.runtime import ROOT, SDK, digest, java

ENTRY = "dev.aivillages.fabric.RuntimeI00EnduranceMeter"


def prepare(observer):
    production = ROOT / "fabric/build/libs/ai-villages-0.1.0.jar"
    before = digest(production)
    temporary = observer.with_suffix(".endurance.jar")
    compiled = ROOT / "build/parallel-i00/endurance-observer"
    compiled.mkdir(parents=True, exist_ok=True)
    source = Path(__file__).parent / "java/dev/aivillages/fabric/RuntimeI00EnduranceMeter.java"
    classpath = [production, SDK / "minecraft-client.jar", *sorted((SDK / "libraries").rglob("*.jar")),
                 *sorted((ROOT / "build/server-sdk/libs").glob("*.jar"))]
    subprocess.run([java("javac"), "--release", "25", "-proc:none", "-encoding", "UTF-8",
                    "-cp", os.pathsep.join(map(str, classpath)), "-d", str(compiled), str(source)], check=True)
    classes = {p.relative_to(compiled).as_posix(): p for p in compiled.rglob("RuntimeI00EnduranceMeter*.class")}
    if not classes:
        raise ValueError("Missing compiled native endurance meter")
    previous = digest(observer)
    with ZipFile(observer) as original, ZipFile(temporary, "w", ZIP_DEFLATED) as output:
        names = original.namelist()
        if len(names) != len(set(names)) or set(names) & classes.keys():
            raise ValueError("Expected a single isolated A observer without the endurance meter")
        for name in names:
            data = original.read(name)
            if name == "fabric.mod.json":
                manifest = json.loads(data)
                entries = manifest["entrypoints"]["main"]
                if "dev.aivillages.fabric.RuntimeAJobProbe" not in entries or ENTRY in entries:
                    raise ValueError("Prepare the A probe before the endurance meter")
                entries.append(ENTRY)
                data = json.dumps(manifest).encode()
            output.writestr(name, data)
        for name, path in classes.items():
            output.write(path, name)
    with ZipFile(production) as release:
        if any(name in release.namelist() for name in classes) or digest(production) != before:
            raise ValueError("Instrumentation entered or modified production")
    temporary.replace(observer)
    metadata = {"schema": 1, "previousObserverSha256": previous, "observerSha256": digest(observer),
                "sourceSha256": digest(source), "productionSha256": before, "readOnly": True}
    (compiled / "integrity.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps(metadata))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--observer", type=Path, default=ROOT / "build/e2e-driver/observer.jar")
    prepare(parser.parse_args().observer)


if __name__ == "__main__":
    main()
