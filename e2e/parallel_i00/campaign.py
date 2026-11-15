"""Execute and independently review shards of the exact 334-case native campaign."""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import traceback
import runpy

from .frozen_campaign import ORIGINAL_SHA256, original, shard
from .gameplay import NativeGameplay, ROOT, witness_identity
from campaigns.crafting.evidence import PASS_STATUSES, evidence_path, record_problems
from campaigns.crafting.invariants import composition_balance, recipe_balance, totals, delta
from campaigns.crafting.run import needed_artifacts, require_case_fence, run_case
from runtime import digest
from suite import atomic, read, require

PROFILE = "I00_ORIGINAL_334"


def schedule_file(root, index, count):
    cases = shard(original(), index, count)
    value = {"frozenScheduleSha256": ORIGINAL_SHA256, "shardIndex": index, "shardCount": count, "cases": cases}
    atomic(root / "schedule.json", value)
    return cases


def run(root, index, count):
    root.mkdir(parents=True, exist_ok=False)
    cases = schedule_file(root, index, count)
    statuses = {case["id"]: "NOT_EXECUTED_REQUIRED" for case in cases}
    result = {"profile": PROFILE, "status": "FAIL_REQUIRED_CASES", "cases": statuses}
    h = None
    try:
        h = NativeGameplay(root, profile=PROFILE)
        h.bootstrap({output for case in cases for output in needed_artifacts(case)})
        for case in cases:
            row = run_case(h, case, integrated_recipe=case["mode"] == "recipe")
            statuses[case["id"]] = row["status"]
            atomic(root / "campaign.json", result)
            print("I00_ORIGINAL_CASE " + json.dumps({"id": case["id"], "status": row["status"],
                                                      "reason": row.get("reason")}), flush=True)
            require_case_fence(row, case["id"])
        if all(status in PASS_STATUSES for status in statuses.values()):
            result["status"] = "COLLECTED_PENDING_INDEPENDENT_REVIEW"
    except Exception as error:
        result.update(error=str(error), trace=traceback.format_exc())
    finally:
        if h:
            h.finish()
        atomic(root / "campaign.json", result)
    return result["status"] == "COLLECTED_PENDING_INDEPENDENT_REVIEW"


def admissions(root):
    path = root / "admissions.json"
    if not path.is_file():
        return {}
    value = read(path)
    for item, method in value["artifacts"].items():
        rows = [row for row in value["rows"] if row["capability"] == item and row["status"] == "PASS"]
        require(len(rows) == 1, f"Missing genuine method admission {item}")
        result = rows[0]
        root_view = result["result"]["views"][0]
        require(root_view["marker"]["artifactSha256"] == method and root_view["marker"]["outcome"] == "ADMITTED"
                and bool(root_view["resourceReceipts"]) and result["callsAfter"]["generationCalls"] > result["callsBefore"]["generationCalls"],
                "Method was never genuinely admitted through measured gameplay: " + item)
    return value["artifacts"]


def review_case(case, row, root, pins, recipes, validate):
    require(row["case"] == case, "Native case deviates from the frozen definition")
    require(row["status"] in PASS_STATUSES and row["attempted"] is True and row["terminalObserved"] is True,
            "No passing connected native execution")
    integrated = case["mode"] == "recipe"
    problems = validate(row, root, integrated_recipe=integrated)
    require(not problems, "; ".join(problems))
    before, after = totals(row["before"], row["clientBefore"]), totals(row["after"], row["clientAfter"])
    balanced, _ = (recipe_balance(before, after, row["independentExpectation"])
                   if row.get("independentExpectation") else composition_balance(before, after, recipes))
    require(balanced and delta(before, after) == row["physicalDelta"], "Native world quantities do not independently reconcile")
    if integrated and not case.get("dynamic"):
        output = case["output"]
        # Every frozen ordinary recipe is a valid positive, including the
        # historical missing-adapter entries. Refusal/containment cannot replace it.
        require(row["status"] == "PASS" and row["request"]["kind"] == "job"
                and row["request"]["command"].startswith("/aivillage kernel production-activity "),
                "Positive recipe did not execute through the integrated owner")
        job = row["execution"]["views"][0]
        require(job["id"] == row["request"]["identity"] == row["integratedRecipe"]["root"]
                and job["state"] == "SUCCEEDED" and job["fulfilled"] == case["quantity"]
                and job["request"]["request"]["arguments"]["amount"]["value"] == case["quantity"],
                "Original Job or requested quantity changed")
        steps = [step for step in job["activity"]["steps"] if step.get("choice")]
        require(steps == row["integratedRecipe"]["choices"] and len(steps) >= 1
                and {step["artifact"]["sha256"] for step in steps} == {pins[output]},
                "Exact recipe choice or genuine immutable method is missing")
        reconciled = row["receiptReconciliation"]
        require(reconciled["exact"] and reconciled["attribution"], "Exact input/output receipts are missing")
        require(row["delivered"] == case["quantity"], "Original exact goal was not delivered")
    return {"id": case["id"], "status": row["status"], "resultSha256": digest(root / "cases" / case["id"] / "result.json")}


def review(root, source, jar):
    output = root / "review.json"
    result = {"profile": PROFILE, "status": "FAIL_REQUIRED_CASES", "cases": []}
    try:
        scheduled = read(root / "schedule.json")
        expected = shard(original(), scheduled["shardIndex"], scheduled["shardCount"])
        require(scheduled["cases"] == expected and scheduled["frozenScheduleSha256"] == ORIGINAL_SHA256,
                "Changed or incomplete frozen case schedule")
        manifest = read(root / "metadata.json")
        result.update(witness_identity(manifest, source, jar, PROFILE), shardIndex=scheduled["shardIndex"],
                      shardCount=scheduled["shardCount"], requiredCases=len(expected))
        catalogue = read(root / "oracle-recipes.json")
        require(catalogue["worldEffects"] == 0 and catalogue["minecraft"] == "26.3" and catalogue["serverPid"],
                "Missing native read-only recipe catalogue")
        pins = admissions(root)
        frozen_verifier = runpy.run_path(str(ROOT / "scripts/campaign-crafting/verify.py"))["validate_case"]
        for case in expected:
            try:
                path = evidence_path(root, f"cases/{case['id']}/result.json")
                require(path.is_file(), "Required case was not executed")
                row = read(path)
                require(row["metadata"] == manifest, "Case belongs to a different native producer")
                result["cases"].append(review_case(case, row, root, pins, catalogue["recipes"], frozen_verifier))
            except Exception as error:
                result["cases"].append({"id": case["id"], "status": "FAIL_REVIEW", "reason": str(error)})
        result["counts"] = dict(Counter(row["status"] for row in result["cases"]))
        if all(row["status"] in PASS_STATUSES for row in result["cases"]):
            result["status"] = "PASS_INDEPENDENT_NATIVE_REVIEW"
    except Exception as error:
        result.update(error=str(error), trace=traceback.format_exc())
    atomic(output, result)
    print("I00_ORIGINAL_REVIEW " + json.dumps({k: v for k, v in result.items() if k not in {"cases", "trace"}}), flush=True)
    return result["status"] == "PASS_INDEPENDENT_NATIVE_REVIEW"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    runner = commands.add_parser("run")
    runner.add_argument("--root", type=Path, required=True)
    runner.add_argument("--shard-index", type=int, default=0)
    runner.add_argument("--shard-count", type=int, default=1)
    reader = commands.add_parser("review")
    reader.add_argument("--root", type=Path, required=True)
    reader.add_argument("--source", required=True)
    reader.add_argument("--jar-sha256", required=True)
    args = parser.parse_args()
    passed = (run(args.root.resolve(), args.shard_index, args.shard_count) if args.command == "run"
              else review(args.root.resolve(), args.source, args.jar_sha256))
    if not passed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
