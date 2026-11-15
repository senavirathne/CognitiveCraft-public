"""Run and independently review the unchanged native A regressions on I00."""

import argparse
import json
from pathlib import Path
import sys
import traceback
from zipfile import ZipFile

from .identity import PRODUCTION, ROOT, candidate, sha256, verify_metadata


# The original A harness is a standalone script suite with sibling imports.
sys.path[:0] = [str(ROOT / "e2e/parallel_a"), str(ROOT / "e2e")]

from freeze_bundle import production  # noqa: E402
from freeze_native import run as run_original_profile  # noqa: E402
from freeze_plan import CASES, plan  # noqa: E402
from recovery_seed import ARCHIVE_SHA  # noqa: E402
from review_freeze import review as review_original_profile  # noqa: E402


A_OBSERVER = "build/e2e-driver/observer.jar"
ADAPTATION = "build/parallel-a/observer-adaptation.json"
REQUIRED = (
    PRODUCTION.relative_to(ROOT).as_posix(),
    "build/e2e-driver/client.jar",
    A_OBSERVER,
    ADAPTATION,
    "build/needle/LICENSE",
    "build/needle/manifest.json",
    "build/needle/needle",
    "build/needle/needle3.cact",
)


def read(path):
    return json.loads(path.read_text())


def bundle(source, jar):
    identity = candidate(source, jar)
    if production(PRODUCTION) != jar:
        raise ValueError("I00 production JAR contains instrumentation or changed during verification")
    instrument = read(ROOT / ADAPTATION)
    files = []
    for name in REQUIRED:
        path = ROOT / name
        if path.is_symlink() or not path.is_file():
            raise ValueError("Missing or indirect A regression instrument: " + name)
        files.append({"path": name, "bytes": path.stat().st_size, "sha256": sha256(path)})
    observed = next(row["sha256"] for row in files if row["path"] == A_OBSERVER)
    if (instrument["releaseSha256"], instrument["observerSha256"]) != (jar, observed):
        raise ValueError("A regression observer differs from the isolated adaptation")
    with ZipFile(ROOT / A_OBSERVER) as observer:
        entrypoints = json.loads(observer.read("fabric.mod.json"))["entrypoints"]["main"]
    if ("dev.aivillages.fabric.RuntimeAArenaObserver" not in entrypoints
            or "dev.aivillages.fabric.E2EObserver" in entrypoints):
        raise ValueError("The A regression-only observer is required")
    return {"schema": 1, "stage": "I00", **identity, "files": files,
            "profilePlan": plan(), "scope": "LEGACY_RUNTIME_REGRESSION"}


def verify_bundle(root, source, jar, metadata):
    current = bundle(source, jar)
    verify_metadata(metadata, current)
    recorded = read(root / "a10-frozen-runtime.json")
    if current != recorded:
        raise ValueError("The A regression used different binaries or a different Actions attempt")
    return recorded


def review(args):
    value = review_original_profile(args.root, args.profile, args.source, args.jar_sha256,
                                    verify_bundle=verify_bundle, stage="I00")
    value["ci"] = read(args.root / "a10-frozen-runtime.json")["ci"]
    value["scope"] = "Original A gates on the I00 candidate, no integrated recipe recovery claim."
    if args.profile == "A06":
        details = value["details"]
        if (details.get("requiredOriginalCases"), details.get("originalOwnerSettlements")) != (12, 12):
            raise AssertionError("All twelve legacy saved resource crash cases are mandatory")
        value["legacySavedResourceCrashes"] = {
            "cases": len(details["cases"]),
            "custodyNoReplay": 12,
            "originalOwnerSettlements": 12,
            "resumedCompletions": 0,
            "separateContinuationControl": "two-unit-delivered-remaining-one",
        }
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="operation", required=True)
    run_parser = commands.add_parser("run")
    review_parser = commands.add_parser("review")
    for command in (run_parser, review_parser):
        command.add_argument("--profile", choices=CASES, required=True)
        command.add_argument("--root", type=Path, required=True)
        command.add_argument("--source", required=True)
        command.add_argument("--jar-sha256", required=True)
    run_parser.add_argument("--seed-archive", type=Path, required=True)
    review_parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if args.operation == "run":
        if args.root.exists() and any(args.root.iterdir()):
            raise ValueError("Recovery requires a fresh isolated profile root")
        if sha256(args.seed_archive) != ARCHIVE_SHA:
            raise ValueError("The original stopped admission seed bytes changed")
        run_original_profile(args, verify_bundle=bundle, stage="I00")
        return
    args.out.mkdir(parents=True, exist_ok=True)
    try:
        value = review(args)
    except Exception as failure:
        value = {"schema": 1, "stage": "I00", "profile": args.profile,
                 "status": "FAIL_INDEPENDENT_I00_PROFILE_REVIEW", "reason": str(failure),
                 "trace": traceback.format_exc()}
    (args.out / "review.json").write_text(json.dumps(value, indent=2) + "\n")
    print({key: value[key] for key in ("stage", "profile", "status")}, flush=True)
    if value["status"] != "PASS_INDEPENDENT_I00_PROFILE_REVIEW":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
