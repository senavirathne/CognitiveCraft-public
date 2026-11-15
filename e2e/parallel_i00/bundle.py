"""Bind one I00 production build and isolated observers to its exact Actions event."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import stat
from zipfile import ZipFile

from .identity import PRODUCTION as PRODUCTION_FILE, ROOT, checkout, sha256

PRODUCTION = PRODUCTION_FILE.relative_to(ROOT).as_posix()
MANIFEST = "build/i00-bundle.json"
FIXED = (
    PRODUCTION, "fabric/build/libs/ai-villages-0.1.0-gametest.jar",
    "build/e2e-driver/client.jar", "build/parallel-a/observer-adaptation.json",
    "build/campaign-crafting-oracle/integrity.json", "build/b-diagnostics/integrity.json",
    "build/i00-instruments/a/observer.jar", "build/i00-instruments/b/observer.jar",
    "build/e2e-sdk/integrity.json", "build/i00-instruments/endurance/observer.jar",
    "build/parallel-i00/endurance-observer/integrity.json",
)
MODULES = ("core", "providers", "fabric")


def identity() -> dict:
    return checkout(os.environ["GITHUB_SHA"])


def files() -> list[str]:
    groups = [list((ROOT / module / "build/test-results/test").glob("TEST-*.xml")) for module in MODULES]
    if any(not group for group in groups):
        raise ValueError("All three module test reports are required")
    needle = [path for path in (ROOT / "build/needle").rglob("*") if path.is_file()]
    if not needle:
        raise ValueError("The pinned Needle installation is required")
    return sorted({*FIXED, *(p.relative_to(ROOT).as_posix() for group in [*groups, needle] for p in group)})


def file_row(name: str) -> dict:
    path = ROOT / name
    if not path.is_file() or path.is_symlink() or not path.resolve().is_relative_to(ROOT):
        raise ValueError("Missing or unsafe bundle member " + name)
    return {"path": name, "bytes": path.stat().st_size, "sha256": sha256(path),
            "mode": stat.S_IMODE(path.stat().st_mode)}


def audit_production() -> None:
    with ZipFile(ROOT / PRODUCTION) as jar:
        names = jar.namelist()
        if len(set(names)) != len(names) or jar.testzip() is not None:
            raise ValueError("Invalid production ZIP")
        if any("e2e" in name.lower() or "gametest" in name.lower()
               or name.endswith((".gguf", ".safetensors", ".cact"))
               for name in names):
            raise ValueError("An observer, test, or model is packaged with production")


def export() -> dict:
    current = identity()
    audit_production()
    rows = [file_row(name) for name in files()]
    production_hash = next(row["sha256"] for row in rows if row["path"] == PRODUCTION)
    manifest = {"schema": 1, **current, "productionSha256": production_hash, "files": rows}
    (ROOT / MANIFEST).write_text(json.dumps(manifest, indent=2) + "\n")
    if os.environ.get("GITHUB_OUTPUT"):
        with Path(os.environ["GITHUB_OUTPUT"]).open("a") as out:
            out.write(f"production_sha256={production_hash}\n")
            out.write(f"source_tree={current['tree']}\n")
    return manifest


def verify(expected_hash: str, observer: str) -> dict:
    manifest = json.loads((ROOT / MANIFEST).read_text())
    if manifest.get("schema") != 1 or manifest.get("productionSha256") != expected_hash:
        raise ValueError("Foreign binary or bundle schema")
    qualifiers = identity()
    if any(manifest.get(key) != value for key, value in qualifiers.items()):
        raise ValueError("Foreign bundle source, tree, run or attempt")
    expected = {row["path"]: row for row in manifest["files"]}
    if len(expected) != len(manifest["files"]) or sorted(expected) != files():
        raise ValueError("Missing, duplicate, or unexpected bundle member")
    for name, row in expected.items():
        observed = file_row(name)
        if any(observed[key] != row[key] for key in ("bytes", "sha256")):
            raise ValueError("Mismatched bundle member " + name)
        if name.startswith("build/needle/"):
            (ROOT / name).chmod(row["mode"])
    if expected[PRODUCTION]["sha256"] != expected_hash:
        raise ValueError("Mismatched production JAR bytes")
    target = ROOT / "build/e2e-driver/observer.jar"
    target.write_bytes((ROOT / f"build/i00-instruments/{observer}/observer.jar").read_bytes())
    audit_production()
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("export", "verify"))
    parser.add_argument("--jar-sha256")
    parser.add_argument("--observer", choices=("a", "b", "endurance"))
    args = parser.parse_args()
    if args.operation == "verify" and (args.jar_sha256 is None or args.observer is None):
        parser.error("verify requires --jar-sha256 and --observer")
    result = export() if args.operation == "export" else verify(args.jar_sha256, args.observer)
    print(json.dumps({key: value for key, value in result.items() if key != "files"}))


if __name__ == "__main__":
    main()
