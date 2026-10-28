#!/usr/bin/env python3
"""Frozen blob/mode, ancestry, namespace, contract and packaging audit for C only."""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import re
import struct
import subprocess
from zipfile import ZipFile

from common import BASE, BASE_TREE, CI_MANIFEST, CONTRACT, DOCS_BASE, FREEZE, HERE, JAR, ROOT
from common import EvidenceError, check, contract_hashes, digest, git, read, write

PREFIXES = ("core/src/main/java/dev/aivillages/core/kernel/interaction/",
            "core/src/test/java/dev/aivillages/core/kernel/interaction/",
            "providers/src/main/java/dev/aivillages/providers/interaction/",
            "providers/src/test/java/dev/aivillages/providers/interaction/", "e2e/parallel_c/")
DOC_PREFIX = "docs/implementation/parallel-c/"
FORBIDDEN = (b"net/minecraft/", b"net/fabricmc/", b"dev/aivillages/fabric/")
PEER_BRANCHES = ("storage-mobility-logistics", "activity-runtime-recovery", "qualified-ab-conversation-logistics")


def allowed(path, docs=False):
    if docs:
        return path.startswith(DOC_PREFIX)
    return path.startswith(PREFIXES) or re.fullmatch(r"\.github/workflows/parallel-c-[^/]+\.yml", path) is not None


def tree(root, ref):
    data = subprocess.check_output(["git", "-C", str(root), "ls-tree", "-rz", ref])
    result = {}
    for row in data.split(b"\0"):
        if row:
            meta, name = row.split(b"\t", 1)
            mode, kind, sha = meta.decode().split()
            check(kind == "blob", "unsupported tracked type: " + name.decode())
            result[name.decode()] = (mode, sha)
    return result


def ancestry(root, base, docs=False, exceptions=None):
    subprocess.run(["git", "-C", str(root), "merge-base", "--is-ancestor", base, "HEAD"], check=True)
    commits = set(git(root, "rev-list", base + "..HEAD").split())
    for row in git(root, "rev-list", "--parents", base + "..HEAD").splitlines():
        check(len(row.split()) == 2, "merge or unrelated root after frozen base: " + row)
    refs = git(root, "for-each-ref", "--format=%(refname)").splitlines()
    for ref in refs:
        if any(part in ref for part in PEER_BRANCHES):
            peer = set(git(root, "rev-list", ref, "^" + base).split())
            check(not commits.intersection(peer), "imported peer/A commit: " + ref)
    for commit in sorted(commits):
        parent = git(root, "rev-parse", commit + "^")
        before, after = tree(root, parent), tree(root, commit)
        audit_trees(before, after, docs, exceptions or {})
    return len(commits)


def audit_trees(before, after, docs=False, exceptions=None, root=None):
    exceptions = exceptions or {}
    for path, entry in before.items():
        if after.get(path) == entry:
            continue
        check(path in exceptions and after.get(path, (None,))[0] == entry[0], "protected baseline blob/mode: " + path)
        check(root is not None, "baseline exception requires exact byte verification: " + path)
        content = subprocess.check_output(["git", "-C", str(root), "cat-file", "blob", after[path][1]])
        check(hashlib.sha256(content).hexdigest() == exceptions[path], "unfrozen workflow edit: " + path)
    for path in after.keys() - before.keys():
        check(allowed(path, docs), "unowned new path: " + path)


def worktree(root, current, docs=False):
    skip = {line[2:] for line in git(root, "ls-files", "-t").splitlines() if line.startswith("S ")}
    checked = 0
    for path, (mode, sha) in current.items():
        p = root / path
        if path in skip and not p.exists():
            continue  # Explicit sparse index; the complete frozen tree was separately compared above.
        check(p.is_file() and not p.is_symlink(), "missing/symlink tracked path: " + path)
        content = p.read_bytes()
        actual = hashlib.sha1(b"blob " + str(len(content)).encode() + b"\0" + content).hexdigest()
        check(actual == sha, "uncommitted tracked bytes: " + path)
        check(mode == ("100755" if p.stat().st_mode & 0o111 else "100644"), "uncommitted mode: " + path)
        checked += 1
    check(not git(root, "diff", "--cached", "--name-only"), "uncommitted index changes")
    for path in git(root, "ls-files", "--others", "--exclude-standard").splitlines():
        check(allowed(path, docs), "unowned untracked path: " + path)
    return checked


def constant_pool(data):
    check(data[:4] == b"\xca\xfe\xba\xbe", "invalid class file")
    count = struct.unpack_from(">H", data, 8)[0]
    pos = 10; index = 1; values = []
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        tag = data[pos]; pos += 1
        if tag == 1:
            length = struct.unpack_from(">H", data, pos)[0]; pos += 2
            values.append(data[pos:pos + length]); pos += length
        else:
            check(tag in sizes, "unknown constant pool tag")
            pos += sizes[tag]
        index += 2 if tag in (5, 6) else 1
    for value in values:
        check(not any(prefix in value for prefix in FORBIDDEN), "core bytecode dependency outside domain")
    return len(values)


def audit(root=ROOT, docs_root=None, static_only=False):
    root = Path(root).resolve()
    check(git(root, "rev-parse", BASE + "^{tree}") == BASE_TREE, "B base tree mismatch")
    ci_dir = root / "e2e/parallel_c/ci-freeze"
    check(digest(ci_dir / "trigger-patch-manifest.json") == CI_MANIFEST, "CI manifest digest mismatch")
    ci = read(ci_dir / "trigger-patch-manifest.json")
    check(digest(ci_dir / "minimal-push-triggers.patch") == ci["patch_sha256"], "CI patch digest mismatch")
    exceptions = {f["path"]: f["after_sha256"] for f in ci["files"]}
    check(digest(root / "e2e/parallel_c/design-freeze.json") == FREEZE, "external freeze manifest mismatch")
    base, head = tree(root, BASE), tree(root, "HEAD")
    # Per-commit audit uses the same exact workflow exception, including its object bytes.
    subprocess.run(["git", "-C", str(root), "merge-base", "--is-ancestor", BASE, "HEAD"], check=True)
    for row in git(root, "rev-list", "--parents", BASE + "..HEAD").splitlines():
        parts = row.split(); check(len(parts) == 2, "peer/A merge after frozen base")
        audit_trees(tree(root, parts[1]), tree(root, parts[0]), exceptions=exceptions, root=root)
    audit_trees(base, head, exceptions=exceptions, root=root)
    # Catch imported peer history even when that commit changes only an allowed path.
    new = set(git(root, "rev-list", BASE + "..HEAD").split())
    for ref in git(root, "for-each-ref", "--format=%(refname)").splitlines():
        if any(branch in ref for branch in PEER_BRANCHES):
            check(not new.intersection(git(root, "rev-list", ref, "^" + BASE).split()), "imported peer/A commit: " + ref)
    checked = worktree(root, head)
    for f in ci["files"]:
        check(digest(root / f["path"]) == f["after_sha256"], "frozen trigger patch absent: " + f["path"])
    hashes = contract_hashes(root / "e2e/parallel_c/contracts")
    check(docs_root is not None, "separate frozen docs checkout required")
    docs_root = Path(docs_root).resolve()
    ancestry(docs_root, DOCS_BASE, docs=True)
    audit_trees(tree(docs_root, DOCS_BASE), tree(docs_root, "HEAD"), docs=True)
    docs_checked = worktree(docs_root, tree(docs_root, "HEAD"), docs=True)
    check(contract_hashes(docs_root / DOC_PREFIX / "contracts") == hashes, "source/docs contract bytes differ")
    for path in (root / "core/src/main/java").rglob("*.java"):
        text = path.read_bytes()
        for prefix in FORBIDDEN:
            check(re.search(rb"\bimport\s+(?:static\s+)?" + re.escape(prefix.replace(b"/", b".")), text) is None,
                  "core import outside domain: " + str(path))
    blocks = []; compiled = 0
    classes = list((root / "core/build/classes/java/main").rglob("*.class"))
    if not classes:
        blocks.append("core/build/classes/java/main: target-JDK compiled dependency audit unavailable")
    for path in classes:
        constant_pool(path.read_bytes()); compiled += 1
    release = root / "fabric/build/libs/ai-villages-0.1.0.jar"
    if release.is_file():
        with ZipFile(release) as jar:
            names = jar.namelist(); check(len(names) == len(set(names)), "duplicate release JAR entries")
            check(not any("e2e" in name.lower() or "parallelcfixture" in name.lower() for name in names), "fixture in release JAR")
            for name in names:
                if name.startswith("dev/aivillages/core/") and name.endswith(".class"):
                    constant_pool(jar.read(name))
    else:
        blocks.append("fabric/build/libs/ai-villages-0.1.0.jar: release packaging audit unavailable")
    return {"gate_status": "BLOCKED_DEPENDENCY" if blocks else "OWNED_GATES_PASS",
            "static_status": "PASS", "base_sha": BASE, "docs_base_sha": DOCS_BASE,
            "baseline_files": len(base), "worktree_files": checked, "docs_worktree_files": docs_checked,
            "source_commits_after_base": len(new), "compiled_core_classes": compiled,
            "contract_sha256": CONTRACT, "blocked": blocks, "scope": "STATIC_ONLY" if static_only else "COMPLETE"}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--base", required=True); p.add_argument("--docs-base", required=True)
    p.add_argument("--docs-root", type=Path, required=True); p.add_argument("--contract-sha256", required=True)
    p.add_argument("--static-only", action="store_true"); p.add_argument("--output", type=Path)
    a = p.parse_args()
    try:
        check((a.base, a.docs_base, a.contract_sha256) == (BASE, DOCS_BASE, CONTRACT), "freeze pins cannot be overridden")
        result = audit(docs_root=a.docs_root, static_only=a.static_only)
    except (EvidenceError, OSError, subprocess.CalledProcessError, ValueError) as failure:
        result = {"gate_status": "FAIL", "error": str(failure)}
    if a.output: write(a.output, result)
    print(__import__("json").dumps(result, sort_keys=True))
    return 0 if result.get("gate_status") == "OWNED_GATES_PASS" or (a.static_only and result.get("static_status") == "PASS") else 2


if __name__ == "__main__":
    raise SystemExit(main())
