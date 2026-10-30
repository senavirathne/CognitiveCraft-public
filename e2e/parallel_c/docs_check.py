#!/usr/bin/env python3
"""Check only C-owned Markdown links/fragments; the J06 repository audit remains deferred."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import unquote, urlsplit

from common import EvidenceError, check, contract_hashes, write


def anchors(path):
    seen = {}; result = set()
    for line in path.read_text().splitlines():
        if not re.match(r"^#{1,6}\s", line): continue
        heading = re.sub(r"\s+#+\s*$", "", re.sub(r"^#{1,6}\s+", "", line)).strip().lower()
        slug = re.sub(r"[^\w\- ]", "", heading).replace(" ", "-")
        ordinal = seen.get(slug, 0); seen[slug] = ordinal + 1
        result.add(slug if ordinal == 0 else slug + "-" + str(ordinal))
    return result


def check_links(root, prefix, stage, require_fragments=True):
    root = Path(root).resolve(); owned = (root / prefix).resolve()
    check(owned.is_relative_to(root) and owned.is_dir(), "missing/unsafe owned docs prefix")
    count = 0; sparse_targets = set()
    for path in sorted(owned.rglob("*.md")):
        # Contract reference documents are frozen inputs; validate their relative links too.
        text = path.read_text()
        for match in re.finditer(r"\[[^\]\n]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)", text):
            link = match.group(1).strip("<>"); parsed = urlsplit(link)
            if parsed.scheme or parsed.netloc: continue
            target = (path.parent / unquote(parsed.path)).resolve() if parsed.path else path
            check(target.is_relative_to(root), "unsafe owned link: " + link)
            if not target.exists():
                # Large historical archives may be omitted by an explicit sparse checkout.
                # Resolve their real committed blob; do not create placeholder evidence files.
                relative = target.relative_to(root).as_posix()
                tracked = subprocess.run(["git", "-C", str(root), "ls-files", "-t", "--", relative], capture_output=True, text=True)
                entry = subprocess.run(["git", "-C", str(root), "ls-tree", "HEAD", "--", relative], capture_output=True, text=True)
                check(tracked.returncode == 0 and tracked.stdout == "S " + relative + "\n"
                      and re.fullmatch(r"100(?:644|755) blob [0-9a-f]{40}\t" + re.escape(relative) + r"\n", entry.stdout),
                      "broken owned link: " + str(path.relative_to(root)) + " -> " + link)
                sparse_targets.add(relative)
            if parsed.fragment:
                check(target.suffix == ".md" and unquote(parsed.fragment) in anchors(target), "broken owned fragment: " + link)
            count += 1
    if require_fragments:
        for name in (stage + ".md", "progress.json", "stage-manifest.json", "decisions.md", "INDEX_FRAGMENT.md", "manifest-fragment.json"):
            check((owned / name).is_file(), "missing owned handoff file: " + name)
        contract_hashes(owned / "contracts")
        manifest = json.loads((owned / "manifest-fragment.json").read_text())
        check(manifest.get("merge_owner") == "J06" and manifest.get("destinations"), "shared destinations must be deferred to J06")
    return {"status": "PASS", "stage": stage, "scope": prefix, "links": count, "sparse_targets": sorted(sparse_targets)}


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__); p.add_argument("--docs-root", type=Path, required=True)
    p.add_argument("--owned-prefix", required=True); p.add_argument("--stage", required=True); p.add_argument("--output", type=Path)
    a = p.parse_args()
    try: result = check_links(a.docs_root, a.owned_prefix, a.stage); code = 0
    except (OSError, EvidenceError, ValueError) as failure: result = {"status": "FAIL", "error": str(failure)}; code = 2
    if a.output: write(a.output, result)
    print(json.dumps(result, sort_keys=True)); raise SystemExit(code)
