"""Run and independently review the unchanged native A regressions on I00."""

import argparse
import json
import os
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


def review_previous_deposit_journal(root):
    from generalization_support import LOG
    from review_receipt_prefix import journal
    from suite import require

    folder = root / "storage-corrupt-current"
    result = folder / "older-valid-backup.json"
    row = read(result)
    current = folder / "actual-corrupt-current.jsonl"
    previous = folder / "actual-previous.jsonl"
    head, _ = journal(folder / "original-current.jsonl")
    old, entries = journal(previous)
    require(sha256(current) == row["currentSha256"] and sha256(previous) == row["previousSha256"]
            and head["world"] == old["world"] and head["epoch"] == old["epoch"]
            and head["revision"] == row["revisionBeforeDamage"] == old["revision"] + 1
            and len(entries) > 0, "Native previous deposit journal is missing, invalid, or not older")
    corrupted = False
    try:
        journal(current)
    except (AssertionError, ValueError):
        corrupted = True
    before, after, restart = row["before"], row["after"], row["restart"]
    require(corrupted and row["writerRequested"] is True and restart["sameClients"] is True
            and restart["reader"]["fenced"] and row["reader"]["fenced"]
            and restart["reader"]["journal"]["revision"] == row["reader"]["journal"]["revision"] == old["revision"]
            and restart["reader"]["journal"]["world"] == old["world"]
            and restart["before"]["startTicks"] != restart["after"]["startTicks"],
            "Corrupt current journal did not expose just the read-only earlier snapshot in the new JVM")
    response = row["response"]
    refused = (response.get("marker", {}).get("outcome") != "SUCCEEDED" and not response.get("resourceReceipts")) if row["intake"]["accepted"] else (
            any("reason=" + reason in response["feedback"] for reason in ("STORAGE_UNAVAILABLE", "INTERRUPTED")))
    require(refused and row["modelCallsBefore"] == row["modelCallsAfter"]
            and before["destination"] == after["destination"] == 1
            and before["actor"]["inventory"] == after["actor"]["inventory"] == {}
            and LOG in before["candidateSource"] == after["candidateSource"],
            "Older backup authorized an effect or rewrote current custody")
    return {"status": "PASS_INDEPENDENT_NATIVE_BOUNDARY", "scope": "NATIVE_PREVIOUS_DEPOSIT_JOURNAL_READ_ONLY",
            "olderBackupValid": True, "currentCorrupt": True, "writerRequested": True, "nativeEffects": 0,
            "resultSha256": sha256(result)}


def review_prepared_revocation(root):
    from freeze_storage_review import process_replaced, totals
    from generalization_support import DST, LOG
    from recovery_fixture import participant
    from review_lifecycle_connected import chest_row, entities, original
    from review_receipt_prefix import canonical, journal, sha
    from suite import require

    folder = root / "prepared-authority-revoked"
    result = folder / "result.json"
    row = read(result)
    observed, reader = row["nativeRevocation"], row["reader"]
    world = folder / "saved-world"
    head, entries = journal(world / "data/cognitivecraft/deposit-evidence/v1/state.jsonl")
    recorded, = entries
    native, = reader["journal"]["entries"]
    key = recorded["intent"]["key"]
    job = original(world, key["root"])
    require(observed["point"] == "PREPARED_MARKED" and observed["beforeAllowed"] is True
            and observed["afterAllowed"] is False and row["armed"]["token"] == observed["token"]
            and observed["originalIntent"] == native["intent"]
            and observed["revision"] == head["revision"] and observed["epoch"] == head["epoch"]
            and observed["pid"] == row["restart"]["before"]["namespaceProcess"]["pid"]
            and not recorded.get("result") and not native.get("result") and job["attempts"][-1]["id"] == key["attempt"],
            "No actual authority revocation at an acknowledged original preparation")
    original_effects = totals(row["original"]["resourceReceipts"])
    require(original_effects == {"HARVEST": 1, "PICKUP": 1, "DEPOSIT": 0}
            and row["original"]["responsibility"]["id"] == job["id"]
            and set(reader["reconciliation"].values()) == {"PREPARED_NO_EFFECT"}
            and row["held"]["destination"] == row["after"]["destination"] == 0
            and row["held"]["actor"]["inventory"] == row["after"]["actor"]["inventory"] == {LOG: 1}
            and row["beforeCalls"] == row["afterCalls"], "Revocation lost original custody or allowed a late effect")
    intent = recorded["intent"]
    marker = {"schema": 1, "phase": "PREPARED", "effect": sha(canonical(key)), "intent": sha(canonical(intent)), "result": ""}
    participant(entities(world)[intent["actor"]["address"]], intent["actor"], marker, "Inventory")
    participant(chest_row(world, DST), intent["chest"], marker, "Items")
    process_replaced(row["restart"]["before"], row["restart"]["after"])
    return {"status": "PASS_INDEPENDENT_NATIVE_BOUNDARY", "scope": "NATIVE_AUTHORITY_RECHECK_AFTER_DEPOSIT_PREPARE",
            "revokedAfterPreparation": True, "actorCustodyRetained": True, "depositEffects": 0,
            "resultSha256": sha256(result)}


def review(args):
    value = review_original_profile(args.root, args.profile, args.source, args.jar_sha256,
                                    verify_bundle=verify_bundle, stage="I00")
    value["ci"] = read(args.root / "a10-frozen-runtime.json")["ci"]
    value["scope"] = "Original A gates on the I00 candidate, no integrated recipe recovery claim."
    if args.profile == "A04":
        from .archive_cut import review as previous_archive

        value["nativeAdversarial"] = {"A18": previous_archive(args.root)}
    if args.profile == "A05":
        from .save_restore import review as save_restore

        value["nativeAdversarial"] = {"A14": review_prepared_revocation(args.root),
                                      "A25": review_previous_deposit_journal(args.root), "A26": save_restore(args.root)}
    if args.profile == "A07":
        row = read(args.root / "controls/handoff-positive/result.json")
        reply = row["lateOriginalReport"]
        witness = reply["evidence"]
        before, after = witness["jobBefore"], witness["jobAfter"]
        original, report = witness["originalAttempt"], witness["report"]
        if not (reply["status"] == "PASS" and witness["snapshotUnchanged"] is True
                and before == after == row["originalJob"] and before["state"] == "SUCCEEDED"
                and original == before["attempts"][0] and original["generation"] < before["generation"]
                and original["worker"] != before["attempts"][-1]["worker"]
                and report["attemptId"] == original["id"] and report["generation"] == original["generation"]
                and report["effects"] == original["effects"] == 3 and report["usage"] == original["usage"]
                and report["resourceReceipts"] == original["resourceReceipts"]
                and report["terminal"]["status"] == "SUCCEEDED" and witness["reply"]["code"] == "DUPLICATE"
                and report["terminal"]["completion"]["id"] == original["executions"][0]["runId"]
                and report["terminal"]["completion"]["validatorVersion"] == "resource-delivery:1"
                and report["terminal"]["completion"]["scope"] == before["request"]["context"]["scope"]["domainId"]
                and witness["guard"]["generation"] == original["generation"]
                and witness["guard"]["revision"] == row["originalAtCut"]["revision"]
                and sum(a["credited"] for a in after["attempts"]) == 2):
            raise AssertionError("Original-generation native callback changed successor authority or credit")
        value["nativeAdversarial"] = {"A15": {"status": "PASS_INDEPENDENT_NATIVE_BOUNDARY",
                "scope": "ORIGINAL_NATIVE_REPORT_AFTER_PHYSICAL_HANDOFF", "oldGenerationCallback": True,
                "successorUnchanged": True, "extraCredit": 0, "resultSha256": sha256(
                    args.root / "controls/handoff-positive/result.json")}}
    if args.profile == "A09":
        row = read(args.root / "cases/private-runtime-boundary/result.json")
        controls = row["invalidProposals"]
        conserved = all(row["initialPhysical"][key] == row["finalPhysical"][key]
                        for key in ("destination", "inventory", "blocks", "drops"))
        if set(controls) != {"primitive", "allowance", "deadline"} or not conserved:
            raise AssertionError("Missing exact invalid-proposal controls or native no-effect witness")
        rpc = [read(path) for path in (args.root / "a09-rpc").glob("*.json")]
        for kind, control in controls.items():
            before, after, proposal = control["before"], control["after"], control["proposal"]
            matches = [r for r in rpc if r["request"].get("op") == "invalid-prepare"
                       and r["request"].get("kind") == kind and r["request"].get("root") == row["root"]]
            if not (len(matches) == 1 and matches[0]["response"]["value"] == control
                    and matches[0]["request"]["principal"] == row["fixture"]["principal"]
                    and matches[0]["request"]["process"] == matches[0]["response"]["process"]
                    and before == after and before["root"] == row["root"] and before["generation"] == 0
                    and not before["acceptedEffects"] and not before["checkpoints"]
                    and control["snapshotUnchanged"] is True and control["change"]["code"] == "REJECTED"
                    and control["change"]["reason"] == ("REQUEST_INVALID" if kind == "primitive" else "BUDGET_EXHAUSTED")):
                raise AssertionError("Invalid native proposal gained authority: " + kind)
            if kind == "primitive":
                if proposal["request"]["capability"]["name"] != "cognitivecraft:invented_integration_capability":
                    raise AssertionError("No invented primitive was actually submitted")
            elif kind == "allowance":
                if proposal["allowance"]["maxima"]["TRAVEL_BLOCKS"] != before["remaining"]["maxima"]["TRAVEL_BLOCKS"] + 1:
                    raise AssertionError("No unauthorized larger allowance was actually submitted")
            elif proposal["allowance"]["deadlineEpochMillis"] != before["originalDeadline"] + 1:
                raise AssertionError("No unauthorized later deadline was actually submitted")
        value["nativeAdversarial"] = {"A22": {"status": "PASS_INDEPENDENT_NATIVE_BOUNDARY",
                "scope": "CONNECTED_ORIGINAL_RUNTIME_ADMISSION", "inventedPrimitiveRefused": True,
                "largerAllowanceRefused": True, "laterDeadlineRefused": True, "nativeEffects": 0,
                "resultSha256": sha256(args.root / "cases/private-runtime-boundary/result.json")}}
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
        os.environ["COGNITIVECRAFT_I00_ADVERSARIAL"] = args.profile
        run_original_profile(args, verify_bundle=bundle, stage="I00")
        return
    args.out.mkdir(parents=True, exist_ok=True)
    try:
        value = review(args)
    except Exception as failure:
        value = {"schema": 1, "stage": "I00", "profile": args.profile,
                 "status": "FAIL_INDEPENDENT_I00_PROFILE_REVIEW", "reason": str(failure),
                 "trace": traceback.format_exc()}
        try:
            identity = candidate(args.source, args.jar_sha256)
            verify_metadata(json.loads((args.root / "metadata.json").read_text()), identity)
        except (OSError, ValueError):
            pass
        else:
            value.update(identity)
    (args.out / "review.json").write_text(json.dumps(value, indent=2) + "\n")
    print({key: value[key] for key in ("stage", "profile", "status")}, flush=True)
    if value["status"] != "PASS_INDEPENDENT_I00_PROFILE_REVIEW":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
