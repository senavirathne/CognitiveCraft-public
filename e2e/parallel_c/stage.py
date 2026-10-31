#!/usr/bin/env python3
"""Collect predeclared C cases on one already-built JAR; only verify.py decides qualification."""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess

from common import BASE, CONTRACT, HERE, JAR, ROOT, EvidenceError, check, contract_hashes
from common import digest, environment, git, identity, read, tracked_clean, workflow_context, write
from docs_check import check_links
from ownership import audit
from run_tests import run as run_python
from predecessor import verify_predecessor


def collect(stage, root, baseline, candidate, docs_root):
    check(stage in ("C00", "C01", "C02"), "later stages are NOT_EXECUTED; stop at the implemented handoff")
    tracked_clean()
    registry_path = HERE / "registries" / (stage + ".json")
    registry = read(registry_path)
    check(registry["implementation"] == "IMPLEMENTED", "unimplemented registry")
    check(registry["native_cases"] == [] and registry["client_cases"] == [], "C00-C02 cannot execute future physical schedules")
    hashes = contract_hashes()
    root = Path(root).resolve()
    check(root.is_relative_to(ROOT / "build/e2e/parallel-c"), "dedicated C evidence root required")
    check(not root.exists(), "refusing to mix candidates/retries in an existing evidence root")
    root.mkdir(parents=True)
    context = workflow_context()
    try: docs_sha = git(docs_root, "rev-parse", "HEAD")
    except (OSError, subprocess.CalledProcessError): docs_sha = None
    run = {**identity(), **context, "stage": stage, "contract_sha256": CONTRACT, "registry_sha256": digest(registry_path),
           "seed": registry["seed"], "jar_sha256": digest(JAR) if JAR.is_file() else None,
           "docs_sha": docs_sha, "original_root_id": None, "original_owner_id": None,
           "a_settlement": None, "native_results": [], "client_results": [], "collection_blocks": [],
           "compose_project": f"cc-c-{os.environ.get('GITHUB_RUN_ID', 'local')}-{os.environ.get('GITHUB_RUN_ATTEMPT', '1')}",
           "container_image_id": os.environ.get("COGNITIVECRAFT_C_IMAGE_ID")}
    if docs_sha is None: run["collection_blocks"].append("private documentation checkout unavailable; source gates still execute")
    write(root / "environment.json", environment())
    write(root / "contract-artifacts.json", hashes)
    if stage in ("C01", "C02"):
        try: previous = verify_predecessor(docs_root, "C00" if stage == "C01" else "C01")
        except (EvidenceError, OSError, subprocess.CalledProcessError) as failure:
            previous = {"status": "BLOCKED_DEPENDENCY", "error": str(failure)}; run["collection_blocks"].append(str(failure))
        write(root / "predecessor.json", previous)
    for name, directory in (("baseline", baseline), ("candidate", candidate)):
        if directory.is_dir(): shutil.copytree(directory, root / name)
        else: run["collection_blocks"].append(str(directory) + ": missing measured " + name + " build")
    if not JAR.is_file(): run["collection_blocks"].append("fabric/build/libs/ai-villages-0.1.0.jar: missing already-built release")
    # Native compiler is an owned C00 requirement, distinct from nonexistent C00 gameplay cases.
    spec = importlib.util.spec_from_file_location("c_native_compile", HERE / "native/compile.py")
    compiler = importlib.util.module_from_spec(spec); spec.loader.exec_module(compiler)
    compiled = compiler.compile_fixture(root / "native", JAR)
    run["collection_blocks"].extend(compiled["blocked"])
    run_python(root / "python")  # Writes observations only; failures are adjudicated independently from XML.
    if stage == "C02":
        from blocker_runtime import dependency
        write(root / "python/blocker-runtime.json", dependency())
    try:
        owner = audit(docs_root=docs_root)
        run["collection_blocks"].extend(owner["blocked"])
    except (EvidenceError, OSError, subprocess.CalledProcessError) as failure:
        owner = {"gate_status": "FAIL", "blocked": [str(failure)]}; run["collection_blocks"].append(str(failure))
    write(root / "ownership.json", owner)
    try: docs = check_links(docs_root, "docs/implementation/parallel-c", stage)
    except (EvidenceError, OSError) as failure:
        docs = {"status": "FAIL", "stage": stage, "error": str(failure)}; run["collection_blocks"].append(str(failure))
    write(root / "docs-check.json", docs)
    write(root / "run.json", run)
    artifacts = {path.relative_to(root).as_posix(): digest(path) for path in sorted(root.rglob("*")) if path.is_file()}
    write(root / "artifacts.json", artifacts)
    return run


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__); sub = p.add_subparsers(dest="action", required=True)
    run = sub.add_parser("run"); run.add_argument("--stage", required=True); run.add_argument("--built", action="store_true", required=True)
    run.add_argument("--root", type=Path, required=True)
    run.add_argument("--baseline", type=Path, default=ROOT / "build/e2e/parallel-c/baseline")
    run.add_argument("--candidate", type=Path, default=ROOT / "build/e2e/parallel-c/candidate")
    run.add_argument("--docs-root", type=Path, default=ROOT.parent / "CognitiveCraft-Documentation-C")
    a = p.parse_args()
    try:
        r = collect(a.stage, a.root, a.baseline, a.candidate, a.docs_root)
        print(json.dumps({"stage": a.stage, "observations": str(a.root), "collection_blocks": r["collection_blocks"]}, sort_keys=True))
        raise SystemExit(2 if r["collection_blocks"] else 0)
    except (EvidenceError, OSError, ValueError) as failure:
        print(json.dumps({"stage": a.stage, "collection_error": str(failure)})); raise SystemExit(2)
