"""Aggregate independently reviewed native campaign shards from one exact run."""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import re

from .campaign import PROFILE as ORIGINAL_PROFILE
from .frozen_campaign import ORIGINAL_SHA256, HELD_OUT_SHA256, held_out, original, shard
from .identity import checkout
from campaigns.crafting.evidence import PASS_STATUSES
from suite import atomic, require

HELD_OUT_PROFILE = "I00_B08_HELD_OUT"


def verify_review(path, profile, cases, expected, shard_spec=None):
    report = json.loads(path.read_text())
    require(all(report.get(key) == value for key, value in expected.items()), "Foreign source, tree, binary or Actions attempt")
    require(report.get("profile") == profile and report.get("status") == "PASS_INDEPENDENT_NATIVE_REVIEW",
            "Required native independent review did not pass")
    require(report.get("requiredCases") == len(cases), "Required native denominator differs")
    if shard_spec:
        require(tuple(report.get(field) for field in ("shardIndex", "shardCount")) == shard_spec,
                "Review belongs to a different campaign shard")
    rows = report["cases"]
    require([row["id"] for row in rows] == [case["id"] for case in cases], "Missing, duplicated, or foreign native cases")
    for case, row in zip(cases, rows, strict=True):
        positive_recipe = case.get("mode") == "recipe" and not case.get("dynamic")
        expected_pass = {"PASS"} if profile == HELD_OUT_PROFILE or positive_recipe else PASS_STATUSES
        require(row["status"] in expected_pass and re.fullmatch(r"[a-f0-9]{64}", row.get("resultSha256", "")),
                "Native case failed or has no independently reviewed original result digest: " + case["id"])
    return rows


def aggregate(campaign_root, count, held_out_review, expected):
    original_cases = original()
    later = held_out()["cases"]
    report = {"schema": 1, **expected, "status": "FAIL_REQUIRED_CAMPAIGN", "shards": [],
              "original": {"requiredCases": len(original_cases), "definitionSha256": ORIGINAL_SHA256},
              "laterHeldOut": {"requiredCases": len(later), "definitionSha256": HELD_OUT_SHA256}}
    by_id = {}
    for index in range(count):
        want = shard(original_cases, index, count)
        try:
            path = campaign_root / str(index) / "review.json"
            rows = verify_review(path, ORIGINAL_PROFILE, want, expected, (index, count))
            by_id.update({row["id"]: row for row in rows})
            report["shards"].append({"index": index, "status": "PASS", "requiredCases": len(want)})
        except Exception as error:
            report["shards"].append({"index": index, "status": "FAIL_REVIEW", "reason": str(error), "requiredCases": len(want)})
    report["original"].update(cases=[by_id.get(case["id"], {"id": case["id"], "status": "NO_QUALIFYING_REVIEW"})
                                     for case in original_cases],
                              verifiedDevelopment=sum(case["id"] in by_id and not case["heldOut"] for case in original_cases),
                              verifiedHeldOut=sum(case["id"] in by_id and case["heldOut"] for case in original_cases),
                              verifiedHistoricalGaps=sum(case["id"] in by_id and case.get("missingAdapter") is True for case in original_cases),
                              classifications=dict(Counter(row["status"] for row in by_id.values())))
    try:
        report["laterHeldOut"].update(status="PASS", cases=verify_review(held_out_review, HELD_OUT_PROFILE, later, expected))
    except Exception as error:
        report["laterHeldOut"].update(status="FAIL_REVIEW", reason=str(error))
    if len(by_id) == len(original_cases) and report["laterHeldOut"]["status"] == "PASS":
        report["status"] = "PASS_INDEPENDENT_CAMPAIGN_REVIEW"
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--campaign-root", required=True, type=Path)
    parser.add_argument("--shard-count", required=True, type=int)
    parser.add_argument("--held-out-review", required=True, type=Path)
    parser.add_argument("--source", required=True)
    parser.add_argument("--jar-sha256", required=True)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    pinned = checkout(args.source)
    if not re.fullmatch(r"[a-f0-9]{64}", args.jar_sha256) or not 1 <= args.shard_count <= len(original()):
        parser.error("An exact production digest and valid shard count are required")
    expected = {"source": pinned["source"], "tree": pinned["tree"], "productionSha256": args.jar_sha256,
                "run": pinned["ci"]["GITHUB_RUN_ID"], "attempt": pinned["ci"]["GITHUB_RUN_ATTEMPT"]}
    report = aggregate(args.campaign_root, args.shard_count, args.held_out_review, expected)
    atomic(args.out, report)
    print(report["status"], len(report["original"]["cases"]), report["laterHeldOut"]["status"], flush=True)
    if report["status"] != "PASS_INDEPENDENT_CAMPAIGN_REVIEW":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
