"""Reconcile the 30 original adversarial requirements against current raw reviews."""

import argparse
from collections import Counter
import copy
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile

from .identity import BRANCH, ROOT, candidate, sha256, verify_metadata


# Source: docs/implementation/parallel-activity/README.md at 88bc5d2f49da49a9b436c9981eaf075d202f92c2.
# These identifiers are review cases, not the names of the Series A profiles.
CHALLENGES = (
    "Component-bearing lookalike ingredients",
    "Output fits but recipe remainder does not",
    "Mixed valid grids across slots/sources",
    "Equal-count stack swap after observation",
    "Hopper/player removes checkpoint stock",
    "Chest replaced at identical coordinates",
    "Double chest splits or merges",
    "Source equals destination with old final stock",
    "Participants save on different sides of a crash",
    "Recipe reload after preparation",
    "Same recipe ID changes component semantics",
    "Unload at preflight/effect/publication boundaries",
    "Death with surviving, destroyed or unavailable custody",
    "Authority revoked after preparation",
    "Late old-generation callback after handoff",
    "Repeated restart during adoption/settlement",
    "Crash before archive manifest swap",
    "Previous snapshot still needs old archives",
    "Stale IDs and conflicting receipts after compaction",
    "Small requests bypass older feasible demand",
    "Complementary staged ingredients cause hold-and-wait",
    "Hallucinated primitive or unauthorized larger budget",
    "Repeated unchanged checkpoint/replan",
    "Storage fills after preparation",
    "Corrupt current journal with older valid backup",
    "Whole-world versus partial save restore",
    "Locally legal recipe makes full goal infeasible",
    "Static method exists but no native witness",
    "Offline model and counters across JVM restart",
    "Observer drops/mislabels evidence",
)

ROLE_UNLOADS = tuple(role + "-" + phase
                     for role in ("ACTOR", "SOURCE", "FACILITY", "DESTINATION")
                     for phase in ("PREFLIGHT", "EFFECT", "PUBLICATION"))

# These legacy witnesses run against the current production bytes and can close
# the listed native challenge for that existing surface only. They cannot close
# the separate integrated production/recovery requirements.
MATCHING = {
    "A04": {"A05": ("negative-equal-count-swap",), "A08": ("slot-stack-swap-after-claim",)},
    "A06": {"A05": ("negative-replacement",), "A08": ("chest-replacement",)},
    "A07": {"A08": ("double-chest-split",)},
    "A09": {"A05": ("actor-saved-chest-prepared", "chest-saved-actor-prepared")},
    "A12": {"A07": ROLE_UNLOADS},
    "A13": {"A07": ("death-surviving", "death-burned", "death-unavailable")},
    "A16": {"A05": ("saved-positive-repeated-adoption",), "A06": ("W6-003",)},
    "A17": {"A03": ("native-cut-after_segment_ack", "native-cut-after_directory_ack"),
            "A04": ("native-job-cut-after_segment_ack", "native-job-cut-after_directory_ack")},
    # The original independent A04 reader compares the archived original body,
    # invokes same/changed receipt and stale-submission calls in the live JVM,
    # and repeats the lookup after genuine archive publication kill cuts.
    "A19": {"A04": ("continuous-native-over-64-terminal-jobs",
                      "native-job-cut-after_directory_ack", "native-job-cut-after_hot_acknowledgement")},
    "A20": {"A08": ("older-feasible-small-arrivals",)},
    "A21": {"A08": ("complementary-inputs-atomic", "complementary-private-custody-residence-bound")},
}

# Nearby controls have a different trigger, route, oracle, or participant
# boundary. Retain their native evidence without accepting the original case.
PARTIAL = {
    "A01": {"A05": ("negative-components",), "A08": ("component-stack-swap",)},
    "A05": {"A09": ("historical-credit-current-stock",)},
    "A08": {"A08": ("source-destination-alias",)},
    "A14": {"A07": ("handoff-revoked",), "A08": ("revoked-return-retains-custody",)},
    "A15": {"A07": ("handoff-positive", "handoff-stale-successor")},
    "A18": {"A02": ("real-world-compatibility-and-acknowledged-history",), "A04": ("native-required-segment-missing",)},
    "A23": {"A08": ("external-mutation-replan-bound",)},
    "A24": {"A05": ("native-storage-full-quota",)},
    "A25": {"A05": ("native-storage-corrupt-current",)},
    "A26": {"A02": ("unknown-history-fence-and-whole-world-rollback",)},
    "A29": {"A09": ("two-segments-saved-checkpoint-restart",)},
}

INTEGRATED = {
    "A01": ("component-bearing-lookalike", {"realComponentBearingInput": True, "effectCount": 0}),
    "A05": ("player-removes-checkpoint-stock", {"originalCredit": 3, "historicalLineageUnchanged": True,
                                               "playerRemoved": 3, "availableAfter": 0}),
    "A08": ("birch-q3-same-container-old-stock", {"oldOutput": 11, "withdrawn": 1, "crafted": 4,
                                                 "delivered": 3, "surplus": 1}),
    "A23": ("unchanged-planning-then-player-restock", {"stoppedUnchangedChecks": 4, "playerSupplied": 1,
                                                     "delivered": 3, "originalAllowancePreserved": True,
                                                     "newModelCalls": 0}),
    "A27": ("legal-batch-insufficient-for-original-goal", {"legalBatchYield": 4, "originalDemand": 7,
                                                         "effectsWhileInfeasible": 0, "delivered": 7,
                                                         "originalAllowancePreserved": True, "newModelCalls": 0}),
    "A28": ("installed-recipe-without-admitted-method", {"installedRecipe": True, "admittedWitnessMissing": True,
                                                        "nativeEffects": 0, "newModelCalls": 0}),
}

DIRECT_PROFILES = {
    "A14": ("A05", {"revokedAfterPreparation": True, "actorCustodyRetained": True, "depositEffects": 0}),
    "A15": ("A07", {"oldGenerationCallback": True, "successorUnchanged": True, "extraCredit": 0}),
    "A18": ("A04", {"previousOnlySegment": True, "immutableRowsPreserved": True,
                      "missingReferenceFenced": True, "nativeEffects": 0}),
    "A22": ("A09", {"inventedPrimitiveRefused": True, "largerAllowanceRefused": True,
                      "laterDeadlineRefused": True, "nativeEffects": 0}),
    "A25": ("A05", {"olderBackupValid": True, "currentCorrupt": True, "writerRequested": True, "nativeEffects": 0}),
    "A26": ("A05", {"partialRefused": True, "coordinatedSavedReceipt": True, "nativeEffectsAfterRestore": 0}),
}

JOINT = {
    "A02": ("native-remainders", "I00_NATIVE_RECIPE_CHALLENGE", {"resultUnitsFit": 4, "nativeRemainderSlotsNeeded": 1,
                                                                  "nativeRemainderSlotsAvailable": 0, "nativeEffects": 0}),
    "A03": ("mixed-recipe-grid-from-two-authorized-sources", "I00_NATIVE_MIXED_GRID", {"separatelyAuthorizedSources": 2,
                    "exactMixedSlots": 2, "unlistedSourceEffects": 0, "delivered": 3, "retained": 1}),
    "A10": ("same-catalogue-reload", "I00_NATIVE_RECIPE_CHALLENGE", {"durablePreparationBeforeReload": True, "sameRecipeId": True,
                                                                     "componentChange": False, "nativeEffects": 0}),
    "A11": ("changed-result-components", "I00_NATIVE_RECIPE_CHALLENGE", {"durablePreparationBeforeReload": True, "sameRecipeId": True,
                                                                          "componentChange": True, "nativeEffects": 0}),
    "A24": ("storage-pressure", "I00_NATIVE_DURABLE_STORAGE_PRESSURE", {"preparedBeforeExhaustion": True, "nativeFilesystemFull": True,
                                                                       "deliveryCredit": 0, "replayEffects": 0}),
}


def read(path):
    return json.loads(path.read_text())


def reread(path, identity):
    if not path.is_file():
        return None
    if path.is_symlink():
        raise ValueError("Refusing indirect review: " + str(path))
    value = read(path)
    if any(value.get(key) != expected for key, expected in identity.items()):
        raise ValueError("Review candidate provenance differs: " + str(path))
    return value


def profiles(root, identity):
    available = {}
    for name in ({p for item in (MATCHING, PARTIAL) for groups in item.values() for p in groups}
                 | {p for p, _ in DIRECT_PROFILES.values()}):
        path = root / "recovery" / name / "review.json"
        if not path.is_file():
            continue
        # Failed reviews retain diagnostics with no complete identity/case claim.
        if read(path).get("status") != "PASS_INDEPENDENT_I00_PROFILE_REVIEW":
            continue
        review = reread(path, identity)
        rows = review["cases"]
        ids = {row["id"] for row in rows}
        if ((review.get("stage"), review.get("profile")) != ("I00", name)
                or len(ids) != len(rows) or len(ids) != review["requiredCases"]
                or any(row["status"] != "PASS_ORIGINAL_CLASSIFICATION_RETAINED" for row in rows)):
            raise ValueError("Invalid independent profile denominator: " + name)
        available[name] = (ids, sha256(path), review.get("nativeAdversarial", {}))
    return available


def profile_claims(root, available, plan):
    claims = []
    for name, required in plan.items():
        if name in available and set(required) <= available[name][0]:
            claims.append({"scope": "LEGACY_NATIVE_RUNTIME", "profile": name,
                           "cases": list(required), "reviewSha256": available[name][1],
                           "path": (root / "recovery" / name / "review.json").relative_to(root).as_posix()})
    return claims


def observer_audit(raw, identity):
    sys.path.insert(0, str(ROOT / "e2e"))
    from parallel_b.guards import stopped_process
    from parallel_b.native_qualification import collector

    metadata = read(raw / "metadata.json")
    verify_metadata(metadata, identity)
    if metadata.get("profile") != "B01":
        raise ValueError("Expected a B01-format native observer transport producer")
    server = raw / "server/game/mods"
    observer = sha256(server / "observer.jar")
    if sha256(server / "ai-villages-0.1.0.jar") != identity["productionSha256"]:
        raise ValueError("Installed native production JAR differs")
    expected = {
        "sourceCommit": identity["source"], "sourceTree": identity["tree"], "sourceBranch": BRANCH,
        "releaseSha256": identity["productionSha256"], "observerSha256": observer, "profile": "B01",
        "configurationSha256": sha256(raw / "configuration-manifest.json"), **identity["ci"],
    }
    baseline = collector(raw, expected)
    if (baseline["integrity"], baseline["mandatoryGameplay"]) != ("PASS", "PASS"):
        raise ValueError("Original native observer evidence is not valid: " + str(baseline))
    kill = read(raw / "kill-witness.json")
    stop = kill["nativeInspection"]
    stopped_process(stop["data"]["state"], hard=True)
    if (stop["kind"] != "process-stop-witness" or stop["data"]["process"] != kill["process"]
            or stop["data"]["state"].get("OOMKilled") is not False
            or kill["clientDisconnection"]["play"] is not False
            or kill["process"]["releaseSha256"] != identity["productionSha256"]):
        raise ValueError("Raw native process and client disconnection do not bind the observer evidence")

    manifest_path = raw / "manifest.json"
    manifest = read(manifest_path)
    digest_before = sha256(manifest_path)
    required = "server/observations.jsonl"
    negatives = {}
    for mutation in ("dropped", "mislabelled"):
        altered = copy.deepcopy(manifest)
        with tempfile.TemporaryDirectory(prefix="i00-observer-negative-") as work:
            copy_root = Path(work)
            for entry in altered["files"]:
                name = entry["path"]
                if name == required:
                    if mutation == "dropped":
                        continue
                    entry["path"] = "server/observations-wrong-label.jsonl"
                target = copy_root / entry["path"]
                target.parent.mkdir(parents=True, exist_ok=True)
                original = raw / name
                try:
                    os.link(original, target)
                except OSError:
                    shutil.copy2(original, target)
            (copy_root / "manifest.json").write_text(json.dumps(altered) + "\n")
            result = collector(copy_root, expected)
            if (result.get("code"), result["integrity"], result["mandatoryGameplay"]) != (
                    "MISSING", "FAIL", "NOT_QUALIFIED"):
                raise ValueError("Observer evidence mutation improperly accepted: " + mutation)
            negatives[mutation] = result
    if sha256(manifest_path) != digest_before:
        raise ValueError("Audit changed the original native manifest")
    return {"schema": 1, "stage": "I00", "id": "A30", **identity,
            "status": "PASS_INDEPENDENT_OBSERVER_NEGATIVES", "sourceManifestSha256": digest_before,
            "originalGameplay": baseline, "corruptCopies": negatives,
            "scope": "NATIVE_EVIDENCE_TRANSPORT",
            "gameplayExecutedByReviewer": False}


def offline_resume_claim(root, identity):
    path = root / "walkthrough/saved-offline/review.json"
    review = reread(path, identity)
    if not review or review.get("cohort") != "saved-offline":
        return None
    matches = []
    expected = {"needleCalls", "generationCalls"}
    for row in review["cases"]:
        if (row.get("status") != "PASS_NATIVE_RESUMED" or row.get("resumedCompletion") is not True
                or row.get("modelUnavailableAfterRestart") is not True):
            continue
        cold, warm = row["coldProcess"], row["warmProcess"]
        before, cut, after = (row[key] for key in ("modelCallsBefore", "modelCallsAtCut", "modelCallsAfter"))
        if (warm["start"] <= cold["start"] or not cold["releaseSha256"] == warm["releaseSha256"] == identity["productionSha256"]
                or set(before) != expected or set(cut) != expected or set(after) != expected
                or any(cut[key] < before[key] or before[key] < 0 or after[key] != 0 for key in expected)
                or row["cumulativeProcessModelCalls"] != {key: cut[key] + after[key] for key in expected}
                or row["cumulativeMeasuredModelCalls"] != {key: cut[key] - before[key] + after[key] for key in expected}
                or not row.get("independentSavedParticipants")):
            raise ValueError("Invalid native offline recovery or cumulative model counter witness")
        matches.append(row["id"])
    return {"scope": "I00_NATIVE_OFFLINE_CONTINUATION", "cases": matches, "reviewSha256": sha256(path),
            "path": path.relative_to(root).as_posix()} if matches else None


def ledger(root, identity):
    available = profiles(root, identity)
    observer_path = root / "b-compatibility/harness/adversarial-review.json"
    observer = reread(observer_path, identity)
    offline = offline_resume_claim(root, identity)
    controls_path = root / "integrated-controls/review.json"
    controls = reread(controls_path, identity)
    joint_path = root / "joint-controls/review.json"
    joint = reread(joint_path, identity)
    exact_choice = {}
    if controls and controls.get("stage") == "I00":
        for row in controls.get("cases", []):
            if row.get("id") in exact_choice:
                raise ValueError("Duplicate integrated control in independent review")
            exact_choice[row["id"]] = row
    rows = []
    for number, challenge in enumerate(CHALLENGES, 1):
        case_id = f"A{number:02d}"
        required = MATCHING.get(case_id, {})
        claims = profile_claims(root, available, required or PARTIAL.get(case_id, {}))
        status = "NOT_EXECUTED_REQUIRED"
        if claims:
            status = ("PASS_NATIVE_CHALLENGE" if required and len(claims) == len(required)
                      else "PARTIAL_NATIVE_COVERAGE")
        if case_id in INTEGRATED:
            identifier, assertions = INTEGRATED[case_id]
            row = exact_choice.get(identifier, {})
            if ((row.get("status"), row.get("scope")) == ("PASS", "I00_CONNECTED_EXACT_CHOICE")
                    and all(row.get(key) == value for key, value in assertions.items())):
                status = "PASS_NATIVE_CHALLENGE"
                claims.append({"scope": row["scope"], "cases": [row["id"]],
                               "reviewSha256": sha256(controls_path), "path": controls_path.relative_to(root).as_posix()})
        if case_id in DIRECT_PROFILES:
            profile, assertions = DIRECT_PROFILES[case_id]
            direct = available.get(profile, (None, None, {}))[2].get(case_id, {})
            if (direct.get("status") == "PASS_INDEPENDENT_NATIVE_BOUNDARY"
                    and all(direct.get(k) == v for k, v in assertions.items())):
                status = "PASS_NATIVE_CHALLENGE"
                claims.append({"scope": direct["scope"], "cases": [case_id], "profile": profile,
                               "reviewSha256": available[profile][1],
                               "path": f"recovery/{profile}/review.json", "resultSha256": direct["resultSha256"]})
        if case_id in JOINT and joint:
            name, scope, assertions = JOINT[case_id]
            selected = [row for row in joint["cases"] if row["id"] == name]
            if len(selected) == 1:
                row = selected[0]
                if ((row.get("status"), row.get("scope")) == ("PASS", scope)
                        and all(row.get(key) == value for key, value in assertions.items())):
                    status = "PASS_NATIVE_CHALLENGE"
                    claims.append({"scope": row["scope"], "cases": [row["id"]], "reviewSha256": sha256(joint_path),
                                   "path": joint_path.relative_to(root).as_posix(), "resultSha256": row["evidenceSha256"]})
        if case_id == "A29" and offline:
            status = "PASS_NATIVE_CHALLENGE"
            claims.append(offline)
        if case_id == "A30" and observer:
            copies = observer.get("corruptCopies", {})
            if ((observer.get("stage"), observer.get("id"), observer.get("status")) !=
                    ("I00", case_id, "PASS_INDEPENDENT_OBSERVER_NEGATIVES")
                    or set(copies) != {"dropped", "mislabelled"}
                    or any((v.get("code"), v.get("integrity"), v.get("mandatoryGameplay")) !=
                           ("MISSING", "FAIL", "NOT_QUALIFIED") for v in copies.values())):
                raise ValueError("Incomplete adverse native observer review")
            status = "PASS_NATIVE_CHALLENGE"
            claims.append({"scope": observer["scope"], "reviewSha256": sha256(observer_path),
                           "path": observer_path.relative_to(root).as_posix()})
        rows.append({"id": case_id, "challenge": challenge, "status": status, "evidence": claims})
    counts = Counter(row["status"] for row in rows)
    return {"schema": 1, "stage": "I00", **identity, "requiredCases": len(CHALLENGES),
            "counts": dict(counts), "cases": rows,
            "status": "PASS_REQUIRED_ADVERSARIAL" if counts["PASS_NATIVE_CHALLENGE"] == len(CHALLENGES)
            else "FAIL_REQUIRED_ADVERSARIAL",
            "importedPriorPasses": 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("ledger", "audit-observer"))
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--jar-sha256", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    identity = candidate(args.source, args.jar_sha256)
    report = ledger(args.root, identity) if args.operation == "ledger" else observer_audit(args.root, identity)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({key: report[key] for key in ("status", "source", "productionSha256")}), flush=True)
    if not report["status"].startswith("PASS"):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
