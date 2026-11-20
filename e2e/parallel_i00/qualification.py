"""Require the full set of independently reviewed native evidence from this Actions attempt."""
import argparse
import json
import os
from pathlib import Path

from .adversarial import ledger
from .campaign_review import aggregate
from .identity import candidate
from .joint_controls import REQUIRED as JOINT_CASES
from .walkthrough import COHORTS, cases
from suite import atomic


def collect(artifacts, out, exact, jobs, shards):
    ci = exact["ci"]
    suffix = f'{ci["GITHUB_RUN_ID"]}-{ci["GITHUB_RUN_ATTEMPT"]}'
    expected = {k: exact[k] for k in ("source", "tree", "productionSha256")}
    evidence = out / "reviews"
    results = {}

    def accept(key, artifact, target, status, predicate=lambda record: True):
        source = artifacts / artifact
        try:
            record = json.loads(source.read_text())
            run = record.get("run", record.get("ci", {}).get("GITHUB_RUN_ID"))
            attempt = record.get("attempt", record.get("ci", {}).get("GITHUB_RUN_ATTEMPT"))
            if any(record.get(k) != value for k, value in expected.items()) or (run, attempt) != (
                    ci["GITHUB_RUN_ID"], ci["GITHUB_RUN_ATTEMPT"]):
                raise ValueError("Foreign source, tree, binary or attempt")
            atomic(evidence / target, record)
            if record.get("status") != status or not predicate(record):
                raise ValueError("Mandatory independent review did not pass")
            results[key] = {"status": "PASS"}
        except (OSError, ValueError) as error:
            results[key] = {"status": "FAIL_REVIEW", "reason": str(error)}

    for number in range(10):
        profile = f"A{number:02d}"
        accept(profile, f"i00-review-a-{profile}-{suffix}/review.json", f"recovery/{profile}/review.json",
               "PASS_INDEPENDENT_I00_PROFILE_REVIEW", lambda r: r.get("profile") == profile)
    b = f"i00-review-b-{suffix}"
    for key, path, status in (
        ("observer-negatives", "b-compatibility/harness/adversarial-review.json", "PASS_INDEPENDENT_OBSERVER_NEGATIVES"),
        ("integrated-controls", "integrated-controls/review.json", "PASS_REQUIRED_INTEGRATED_CONTROLS"),
    ):
        original = f"{b}/{path}"
        if not (artifacts / original).exists():
            original = f"{b}/{Path(path).name}"
        accept(key, original, path, status)
    for cohort in COHORTS:
        directory = f"i00-review-walkthrough-{cohort}-{suffix}"
        review = f"{directory}/review.json"
        if not (artifacts / review).is_file():
            review = f"{directory}/walkthrough/{cohort}/review.json"
        accept(cohort, review, f"walkthrough/{cohort}/review.json",
               "PASS_INDEPENDENT_NATIVE_REVIEW", lambda r: r.get("cohort") == cohort
               and r.get("requiredCases") == list(cases(cohort))
               and (cohort != "saved-offline" or r.get("offlineResumedCompletions", 0) > 0))
    accept("joint-controls", f"i00-review-walkthrough-live-{suffix}/joint/joint-review.json",
           "joint-controls/review.json", "PASS_REQUIRED_JOINT_CONTROLS",
           lambda row: row.get("requiredCases") == list(JOINT_CASES)
           and [case["id"] for case in row["cases"] if case["status"] == "PASS"] == list(JOINT_CASES))
    accept("fixed-wheat", f"i00-review-fixed-wheat-{suffix}/review.json", "fixed-wheat/review.json",
           "PASS_INDEPENDENT_FIXED_WHEAT", lambda r: r.get("sourcePlants") == r.get("wheatDelivered") == 3
           and r.get("travelBudget") == 32 and r.get("budgetRenewals") == 0)
    for index in range(shards):
        accept(f"original-{index}", f"i00-review-original-{index}-{suffix}/review.json", f"campaign/{index}/review.json",
               "PASS_INDEPENDENT_NATIVE_REVIEW")
    accept("held-out", f"i00-review-held-out-{suffix}/review.json", "held-out/review.json", "PASS_INDEPENDENT_NATIVE_REVIEW")
    accept("endurance", f"i00-review-endurance-{suffix}/review.json", "endurance/review.json",
           "PASS_INDEPENDENT_NATIVE_ENDURANCE", lambda r: r.get("terminalActivities") == 1_000)
    campaign = aggregate(evidence / "campaign", shards, evidence / "held-out/review.json",
                         {**expected, "run": ci["GITHUB_RUN_ID"], "attempt": ci["GITHUB_RUN_ATTEMPT"]})
    adverse = ledger(evidence, exact)
    atomic(out / "campaign.json", campaign)
    atomic(out / "adversarial.json", adverse)
    passed = (jobs and all(j["result"] == "success" for j in jobs.values())
              and all(r["status"] == "PASS" for r in results.values())
              and campaign["status"] == "PASS_INDEPENDENT_CAMPAIGN_REVIEW"
              and adverse["status"] == "PASS_REQUIRED_ADVERSARIAL")
    return {"schema": 1, "stage": "I00", **exact, "status": "NATIVE_GATES_PASS" if passed else "NOT_READY",
            "jobs": {key: job["result"] for key, job in jobs.items()}, "independentReviews": results,
            "campaign": campaign["status"], "adversarial": adverse["status"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--source", required=True)
    parser.add_argument("--jar-sha256", required=True)
    parser.add_argument("--shard-count", required=True, type=int)
    args = parser.parse_args()
    result = collect(args.artifacts, args.out, candidate(args.source, args.jar_sha256),
                     json.loads(os.environ["I00_WORKFLOW_NEEDS"]), args.shard_count)
    atomic(args.out / "qualification.json", result)
    print(json.dumps(result, indent=2))
    if result["status"] != "NATIVE_GATES_PASS":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
