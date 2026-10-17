#!/usr/bin/env python3
"""Pinned native IMP-029 adapter + physical saved-world acceptance; no model service."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
from zipfile import ZipFile
import headless

ROOT = headless.ROOT


def main():
    runtime, _ = headless.build()
    jar = ROOT / "fabric/build/libs/ai-villages-0.1.0-gametest.jar"
    with ZipFile(jar) as archive:
        entries = {name: archive.read(name) for name in archive.namelist()}
    metadata = json.loads(entries["fabric.mod.json"])
    metadata["entrypoints"]["fabric-gametest"] = ["dev.aivillages.fabric." + name for name in
        ("CitizenLifecycleGameTests", "CitizenLifecycleRestartGameTests")]
    entries["fabric.mod.json"] = json.dumps(metadata).encode()
    with ZipFile(jar, "w") as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
    directory = ROOT / "build/lifecycle-run" / str(time.time_ns())
    evidence = ROOT / "build/lifecycle-evidence"
    if evidence.exists():
        previous = evidence.with_name("lifecycle-evidence-previous-" + str(time.time_ns()))
        evidence.rename(previous)
    evidence.mkdir(parents=True, exist_ok=True)
    (directory / "mods").mkdir(parents=True)
    (directory / "server.properties").write_text("online-mode=false\n")
    for mod in (headless.SDK / "fabric-api.jar", headless.SDK / "gametest.jar", jar,
                ROOT / "fabric/build/libs/ai-villages-0.1.0.jar"):
        shutil.copy2(mod, directory / "mods" / mod.name)
    counts = {}
    for phase in ("cold", "warm"):
        report = evidence / (phase + ".xml")
        report.unlink(missing_ok=True)
        env = os.environ.copy()
        env.update(COGNITIVECRAFT_LIFECYCLE_PHASE=phase, COGNITIVECRAFT_OLLAMA_MODEL="",
                   COGNITIVECRAFT_NEEDLE_DIR="", COGNITIVECRAFT_LIFECYCLE_WORLD=str(directory / "lifecycle-state"))
        with (evidence / (phase + ".log")).open("w") as output:
            result = subprocess.run([headless.executable("java"), "-Xmx2G", "-Dfabric-api.gametest",
                "-Dfabric-api.gametest.report-file=" + str(report), "-cp", os.pathsep.join(map(str, runtime)),
                "net.fabricmc.loader.impl.launch.knot.KnotServer", "nogui"], cwd=directory,
                env=env, stdout=output, stderr=subprocess.STDOUT, timeout=300)
        cases = [] if not report.exists() else [c for c in ET.parse(report).getroot().iter("testcase")
                                               if c.get("name", "").startswith("ai_villages_tests:")]
        if result.returncode or len(cases) != 6 or any(c.find(tag) is not None for c in cases
                                                       for tag in ("failure", "error", "skipped")):
            raise RuntimeError("Lifecycle " + phase + " failed; inspect " + str(evidence))
        identity = directory / "lifecycle-state/data/cognitivecraft/citizens/v1/state.json"
        shutil.copy2(identity, evidence / (phase + "-identity.json"))
        saved = json.loads(identity.read_text())
        proof_data = json.loads((directory / "lifecycle-state/lifecycle-proof.json").read_text())
        terminal = next(c for c in saved["citizens"] if c["citizenId"] == proof_data["citizen"])
        if terminal["lifecycle"]["event"] != "DEATH" or terminal["lifecycle"]["acknowledged"] != 7:
            raise RuntimeError("Native " + phase + " checkpoint lost terminal data")
        counts[phase] = len(cases)
        print("IMP-029 native", phase, len(cases), "PASS", flush=True)
    proof = directory / "lifecycle-state/lifecycle-proof.json"
    if not proof.exists():
        found = list(directory.rglob("lifecycle-proof.json"))
        if len(found) != 1:
            raise RuntimeError("Missing physical lifecycle proof")
        proof = found[0]
    shutil.copy2(proof, evidence / "physical-proof.json")
    release = ROOT / "fabric/build/libs/ai-villages-0.1.0.jar"
    (evidence / "acceptance.json").write_text(json.dumps({"schema": 1, "unit": "IMP-029",
        "source": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "releaseSha256": hashlib.sha256(release.read_bytes()).hexdigest(), "phases": counts,
        "identitySchema": 2, "modelsConfigured": False}, indent=2) + "\n")


if __name__ == "__main__":
    main()
