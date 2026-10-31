#!/usr/bin/env python3
"""One container, one production JAR, all predeclared gates; preserve partial evidence on failure."""
import argparse
import os
from pathlib import Path
import subprocess
import time

from common import BASE, ROOT, check, digest, git, identity, read, tracked_clean, workflow_context, write
from qualification_request import validate


def main():
    p = argparse.ArgumentParser(description=__doc__); p.add_argument("--request", type=Path, required=True)
    args = p.parse_args(); request = validate(args.request); stage = request["stage"]
    context = workflow_context(); check(context["workflow_run"] is not None, "CI runner requires actual workflow identity")
    tracked_clean(); check(git(ROOT, "rev-parse", "HEAD") == os.environ.get("GITHUB_SHA"), "workflow/source mismatch")
    run_id, attempt = context["workflow_run"], context["attempt"]
    output = ROOT / f"build/e2e/parallel-c/ci-{run_id}-{attempt}"
    output.mkdir(parents=True, exist_ok=False)
    result = {**identity(), **context, "stage": stage, "purpose": request["purpose"], "request": request,
              "execution": "DOCKER", "image_id": os.environ.get("COGNITIVECRAFT_C_IMAGE_ID"), "phases": [],
              "started_ns": time.time_ns(), "docs_available": False, "jar_sha256": None, "gate_status": "IN_PROGRESS"}
    docs = ROOT.parent / "CognitiveCraft-Documentation-C"
    result["docs_available"] = subprocess.run(["git", "-C", str(docs), "rev-parse", "--verify", "HEAD"], capture_output=True).returncode == 0
    result["docs_sha"] = git(docs, "rev-parse", "HEAD") if result["docs_available"] else None

    def phase(name, command, timeout=1800):
        record = {"name": name, "command": command, "started_ns": time.time_ns(), "exit": None}
        with (output / (name + ".log")).open("w") as log:
            try: record["exit"] = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=timeout).returncode
            except (OSError, subprocess.TimeoutExpired) as failure: log.write("\n" + str(failure) + "\n")
        record["ended_ns"] = time.time_ns(); record["log_sha256"] = digest(output / (name + ".log"))
        result["phases"].append(record); write(output / "ci-result.json", result)
        print(name + ": exit=" + str(record["exit"]), flush=True)
        return record["exit"] == 0

    phase("sdk", ["python3", "e2e/runtime.py", "prepare"])
    os.environ["ORG_GRADLE_PROJECT_serverJar"] = str(ROOT / "build/server-sdk/minecraft-server.jar")
    os.environ["ORG_GRADLE_PROJECT_serverLibraries"] = str(ROOT / "build/server-sdk/libs")
    baseline_source = ROOT / f"build/parallel-c-baseline-source-{run_id}-{attempt}"
    baseline, candidate = output / "baseline", output / "candidate"
    phase("baseline-checkout", ["git", "worktree", "add", "--detach", str(baseline_source), BASE], 120)
    phase("baseline-build", ["python3", "e2e/parallel_c/build.py", "--repo", str(baseline_source), "--output", str(baseline)])
    phase("candidate-build", ["python3", "e2e/parallel_c/build.py", "--output", str(candidate)])
    phase("existing-drivers", ["python3", "e2e/runtime.py", "compile"], 300)
    evidence = ROOT / f"build/e2e/parallel-c/{stage}-{run_id}-{attempt}"
    phase("collect", ["python3", "e2e/parallel_c/stage.py", "run", "--stage", stage, "--built", "--root", str(evidence),
                      "--baseline", str(baseline), "--candidate", str(candidate), "--docs-root", str(docs)])
    phase("oracle", ["python3", "e2e/parallel_c/verify.py", "--stage", stage, "--root", str(evidence), "--source", result["source_sha"],
                     "--tree", result["tree_sha"], "--jar", "fabric/build/libs/ai-villages-0.1.0.jar", "--docs-root", str(docs),
                     "--output", str(evidence / "qualified.json")], 600)
    qualified = evidence / "qualified.json"
    oracle = read(qualified) if qualified.is_file() else {}
    release = ROOT / "fabric/build/libs/ai-villages-0.1.0.jar"
    result.update(ended_ns=time.time_ns(), jar_sha256=digest(release) if release.is_file() else None, evidence_root=str(evidence), oracle=oracle)
    passed = all(x["exit"] == 0 for x in result["phases"]) and result["docs_sha"] == request["docs_candidate_sha"] and oracle.get("gate_status") == "OWNED_GATES_PASS"
    result["gate_status"] = "OWNED_GATES_PASS" if passed else "BLOCKED_DEPENDENCY" if not result["docs_available"] else "FAIL"
    write(output / "ci-result.json", result)
    print(__import__("json").dumps(result, sort_keys=True), flush=True)
    return 0 if passed else 2


if __name__ == "__main__":
    raise SystemExit(main())
