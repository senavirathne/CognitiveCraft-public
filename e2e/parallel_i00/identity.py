"""Exact checkout and GitHub Actions attempt identity for I00 evidence readers."""

from pathlib import Path
import hashlib
import os
import re
import subprocess


ROOT = Path(__file__).resolve().parents[2]
BRANCH = "integration/activity-runtime-production"
PINS = (
    "0312c55c06a72be7556a0d8563b7cb644d312617",
    "12fb3af99e0c9496171b89d4dd2ffff3f5c7a824",
)
PRODUCTION = ROOT / "fabric/build/libs/ai-villages-0.1.0.jar"


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def candidate(source, jar):
    ci = {key: os.environ.get(key, "") for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT")}
    if not all(re.fullmatch(r"[1-9][0-9]*", value) for value in ci.values()):
        raise ValueError("I00 requires a GitHub Actions run and attempt")
    if (source != os.environ.get("GITHUB_SHA") or source != git("rev-parse", "HEAD")
            or os.environ.get("GITHUB_REF") != "refs/heads/" + BRANCH
            or git("status", "--porcelain")):
        raise ValueError("I00 requires the exact clean integration event checkout")
    for predecessor in PINS:
        if subprocess.run(["git", "merge-base", "--is-ancestor", predecessor, source], cwd=ROOT).returncode:
            raise ValueError("I00 candidate does not include frozen handoff " + predecessor)
    if jar != sha256(PRODUCTION):
        raise ValueError("I00 candidate production binary changed")
    return {"source": source, "tree": git("rev-parse", "HEAD^{tree}"),
            "productionSha256": jar, "ci": ci}


def verify_metadata(metadata, expected):
    identity = metadata.get("sourceCommit"), metadata.get("sourceTree"), metadata.get("releaseSha256")
    binding = expected["source"], expected["tree"], expected["productionSha256"]
    if identity != binding or metadata.get("dirty") is not False:
        raise ValueError("Native evidence is from a different or dirty checkout/binary")
    if metadata.get("sourceBranch") != BRANCH or metadata.get("topology") != "compose":
        raise ValueError("Native evidence is from a different branch or runtime topology")
    if any(metadata.get("ci", {}).get(key) != value for key, value in expected["ci"].items()):
        raise ValueError("Native evidence is from a different GitHub Actions run or attempt")
