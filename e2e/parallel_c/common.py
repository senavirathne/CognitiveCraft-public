"""C-owned evidence primitives. No authority, gameplay execution or inferred pass flags."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
BASE = "12fb3af99e0c9496171b89d4dd2ffff3f5c7a824"
BASE_TREE = "3e7d5f96e9317288ce3ae4eae063e5fc45f28765"
DOCS_BASE = "9864841ff76d867fba2ccdab87199d3556d19f22"
CONTRACT = "e1e97de64c8c8934d51b47bba143a1fccb49e9fedb8882380fc5cdd3fe008cb3"
SCHEMA = "99901b31634957fa8d1c3ebeaa668a5ab4297139a98a015bde81f7e5b89c6589"
CI_MANIFEST = "5d687fc1502a583336383785ec136eeab0c1c9e7fd9aae681f0cda2ad62825bc"
FREEZE = "68e8024f8421b24d2a550e2ce7b108ff6fe30cd0f00878d5cde53575adc55c02"
JAR = ROOT / "fabric/build/libs/ai-villages-0.1.0.jar"
BUILD_COMMAND = ["bash", "gradlew", "--no-daemon", "--stacktrace", ":core:test", ":providers:test",
                 ":fabric:test", ":fabric:build", ":fabric:gameTestJar"]


class EvidenceError(ValueError):
    pass


def check(condition, message):
    if not condition:
        raise EvidenceError(message)


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def no_duplicates(pairs):
    out = {}
    for key, value in pairs:
        check(key not in out, "duplicate JSON key: " + key)
        out[key] = value
    return out


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"), object_pairs_hook=no_duplicates,
                      parse_constant=lambda x: (_ for _ in ()).throw(EvidenceError("nonfinite JSON: " + x)))


def write(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False) + "\n")


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()


def contained(root, relative):
    path = Path(relative)
    check(not path.is_absolute() and ".." not in path.parts, "unsafe artifact path: " + str(relative))
    full = (Path(root) / path).resolve()
    check(full.is_relative_to(Path(root).resolve()), "artifact escapes root: " + str(relative))
    return full


def artifact(root, relative, expected=None):
    path = contained(root, relative)
    check(path.is_file(), "missing artifact: " + str(relative))
    actual = digest(path)
    if expected is not None:
        check(actual == expected, "artifact hash mismatch: " + str(relative))
    return actual


def identity(root=ROOT):
    return {"source_sha": git(root, "rev-parse", "HEAD"), "tree_sha": git(root, "rev-parse", "HEAD^{tree}")}


def tracked_clean(root=ROOT):
    check(not git(root, "status", "--porcelain", "--untracked-files=normal"), "freeze source before measuring: dirty checkout")


def contract_hashes(root=HERE / "contracts"):
    check(digest(root / "SHA256SUMS.json") == CONTRACT, "external contract digest mismatch")
    check(digest(root / "contract.schema.json") == SCHEMA, "external schema digest mismatch")
    manifest = read(root / "SHA256SUMS.json")
    expected = set(manifest["files"]) | {"SHA256SUMS.json"}
    actual = {p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file() and "__pycache__" not in p.parts}
    check(actual == expected, "contract file set mismatch")
    for path, sha in manifest["files"].items():
        artifact(root, path, sha)
    check(len(read(root / "fixtures-index.json")["fixtures"]) == 57, "frozen fixture count")
    return {p: digest(root / p) for p in sorted(expected)}


def environment():
    # Read only an explicit allowlist. Credentials and arbitrary process environments are never logged.
    result = {"java_home": os.environ.get("JAVA_HOME"),
              "sdk": str(Path(os.environ.get("COGNITIVECRAFT_E2E_CACHE", ROOT / "build/e2e-sdk")).resolve()),
              "model": os.environ.get("COGNITIVECRAFT_OLLAMA_MODEL"),
              "workflow_run": os.environ.get("GITHUB_RUN_ID"), "attempt": os.environ.get("GITHUB_RUN_ATTEMPT")}
    for name, command in {"java": ["java", "-version"], "javac": ["javac", "-version"],
                          "docker": ["docker", "version", "--format", "{{.Server.Version}}"],
                          "compose": ["docker", "compose", "version", "--short"]}.items():
        try:
            p = subprocess.run(command, capture_output=True, text=True, timeout=15)
            result[name] = {"exit": p.returncode, "output": (p.stdout + p.stderr).strip()}
        except (OSError, subprocess.TimeoutExpired) as failure:
            result[name] = {"exit": None, "output": str(failure)}
    if result["docker"]["exit"] == 0:
        for name, command in {"runtime_images": ["docker", "image", "inspect", "cognitivecraft-e2e:java25", "--format", "{{.Id}} {{json .RepoDigests}}"],
                              "containers": ["docker", "ps", "--format", "{{.ID}} {{.Image}} {{.Names}}"]}.items():
            p = subprocess.run(command, capture_output=True, text=True, timeout=15)
            result[name] = {"exit": p.returncode, "output": (p.stdout + p.stderr).strip()}
    integrity = Path(result["sdk"]) / "integrity.json"
    result["sdk_integrity_sha256"] = digest(integrity) if integrity.is_file() else None
    result["model_digest"] = None  # Must be observed from the existing backend when a provider stage requires it.
    return result
