#!/usr/bin/env python3
"""Capture a real baseline/candidate module build without editing the frozen build system."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

from common import BASE, BUILD_COMMAND, ROOT, digest, environment, identity, tracked_clean, write
from build_profile import capture_profile
from gradle_results import export_binary, prepare_reader


def capture(repo, output):
    repo, output = Path(repo).resolve(), Path(output).resolve()
    tracked_clean(repo)
    output.mkdir(parents=True, exist_ok=False)
    pins = identity(repo)
    env = environment()
    record = {**pins, "command": BUILD_COMMAND, "started_ns": time.time_ns(), "environment": env,
              "exit": None, "jar_sha256": None, "xml": {}, "gradle_results": {}, "blocked": [], "profile": capture_profile(repo)}
    if env["java"]["exit"] != 0 or not __import__("re").search(r'(?:version\s+"|openjdk\s+)25(?:[.\s"+-])', env["java"]["output"]):
        record["blocked"].append("Required Java 25; observed " + env["java"]["output"])
        (output / "build.log").write_text("NOT_EXECUTED: Java 25 is required by the frozen build.\n")
    else:
        # Remove only this checkout's old module result directories so stale XML cannot be recredited.
        for module in ("core", "providers", "fabric"):
            shutil.rmtree(repo / module / "build/test-results/test", ignore_errors=True)
        with (output / "build.log").open("w") as log:
            try:
                result = subprocess.run(BUILD_COMMAND, cwd=repo, stdout=log, stderr=subprocess.STDOUT, timeout=3600)
                record["exit"] = result.returncode
            except subprocess.TimeoutExpired:
                record["blocked"].append("Frozen module build exceeded its one-hour collection timeout")
        jar = repo / "fabric/build/libs/ai-villages-0.1.0.jar"
        if record["exit"] != 0:
            # Preserve whatever the failed command actually produced; never qualify partial data.
            for module in ("core", "providers", "fabric"):
                partial = repo / module / "build/test-results/test"
                if partial.is_dir(): shutil.copytree(partial, output / "partial-results" / module)
        if record["exit"] == 0 and jar.is_file():
            shutil.copy2(jar, output / "production.jar"); record["jar_sha256"] = digest(jar)
            reader = prepare_reader(output / "gradle-reader")
            for module in ("core", "providers", "fabric"):
                for source in sorted((repo / module / "build/test-results/test").glob("TEST-*.xml")):
                    path = Path("unit") / module / source.name
                    (output / path).parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(source, output / path); record["xml"][path.as_posix()] = digest(source)
                binary = repo / module / "build/test-results/test/binary"
                copied = output / "unit" / module / "binary"
                shutil.copytree(binary, copied)
                relative = "identities/" + module + ".jsonl"
                record["gradle_results"][module] = {"identities": relative,
                    "identities_sha256": export_binary(copied, output / relative, reader),
                    "binary": {p.relative_to(output).as_posix(): digest(p) for p in copied.rglob("*") if p.is_file()}}
        elif record["exit"] == 0:
            record["blocked"].append("Gradle returned zero without the exact release JAR")
    record["ended_ns"] = time.time_ns()
    record["build_log_sha256"] = digest(output / "build.log")
    write(output / "build-record.json", record)
    return record


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--repo", type=Path, default=ROOT); p.add_argument("--output", type=Path, required=True)
    a = p.parse_args(); r = capture(a.repo, a.output)
    print(json.dumps({k: r[k] for k in ("source_sha", "tree_sha", "exit", "blocked")}, sort_keys=True))
    raise SystemExit(0 if r["exit"] == 0 and not r["blocked"] else 2)
