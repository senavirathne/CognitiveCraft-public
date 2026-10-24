#!/usr/bin/env python3
"""Verify production identity and evidence completeness, without a gameplay score gate."""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "e2e"))
from campaigns.crafting.oracle import sha  # noqa: E402

BASELINE = "ea7de8000b2b484b885576c34ed5358274936ce9"
CAMPAIGN = ROOT / "e2e/campaigns/crafting"


def read(path):
    return json.loads(path.read_text())


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def baseline():
    frozen = read(CAMPAIGN / "baseline.json")
    if frozen["commit"] != BASELINE:
        raise ValueError("Frozen production commit changed")
    paths = list(frozen["files"])
    mismatch = [name for name in paths if not (ROOT / name).is_file() or
                hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != frozen["files"][name]]
    tracked = subprocess.check_output(["git", "ls-files"], cwd=ROOT, text=True).splitlines()
    production = [p for p in tracked if any(p.startswith(x + "/src/main/") for x in ("core", "providers", "fabric"))]
    mismatch.extend(p for p in production if p not in paths)
    if mismatch:
        raise ValueError("Production baseline changed: " + ", ".join(mismatch))
    record = {"productionBaseline": BASELINE, "filesChecked": len(paths), "unchanged": True,
              "campaignCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
              "manifestHash": sha(frozen)}
    write(ROOT / "build/campaign-crafting-baseline.json", record)
    print(json.dumps(record))


def validate_case(row, root):
    problems = []
    case = row["case"]
    if row["status"].startswith("PASS"):
        if not row.get("attempted") or not row.get("conserved") or not row.get("clientAfter", {}).get("play"):
            problems.append("Passing case lacks actual connected physical conservation evidence")
        if not row.get("rawLogs") or not row.get("screenshots"):
            problems.append("Passing case lacks original logs or native frames")
        if case["mode"] == "recovery" or case.get("dynamic"):
            if row.get("boundaryWitnessed") is not True:
                problems.append("Dynamic/recovery pass lacks a witnessed boundary")
        if case["mode"] != "legacy" and row.get("modelCallsBefore") != row.get("modelCallsAfter"):
            problems.append("Known-capability execution invoked inference")
    for entry in row.get("rawLogs", []):
        if entry.get("available"):
            path = root / entry["slice"]
            if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
                problems.append("Raw log slice missing or changed: " + entry["slice"])
            elif row["status"] == "PASS" and entry["path"] == "server/stdout.log" and row.get("physicalDelta"):
                trace = path.read_text(errors="replace")
                if "CRAFTING_PRIMITIVE_TRACE" not in trace:
                    problems.append("Physical success lacks original primitive-start instrumentation")
                if "CRAFTING_PRIMITIVE_TRACE_INCOMPLETE" in trace:
                    problems.append("Primitive trace serialization is incomplete")
    for capture in row.get("screenshots", []):
        path = root / capture["path"]
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != capture["sha256"]:
            problems.append("Original native frame missing or changed")
    if row.get("attempted") and not row.get("request") and row["status"].startswith("PASS"):
        problems.append("No original player request")
    if row["status"] == "PASS" and row.get("delivered") != row.get("requested"):
        problems.append("False physical completion")
    if row["status"] == "PASS" and case.get("missingAdapter"):
        problems.append("Unreachable adapter cannot count as a gameplay success")
    return problems


def diagnostic(row, all_rows):
    case, status = row["case"], row["status"]
    material_gap = status in {"GAP_RECIPE_EQUIVALENCE", "GAP_NO_GAMEPLAY_ADAPTER"}
    causes = (["RecipeProduction.Definition input identity", "FabricRecipeTransactions.inputItem/preflight/withdraw", "ResourceComposition producer mapping"]
              if material_gap else ["KernelSession player command", "Jobs/prerequisite admission", "SurvivalGateway/Fabric adapter", "Campaign fixture and evidence evaluator"])
    controls = [r["case"]["id"] for r in all_rows if r["status"] == "PASS" and
                r["case"].get("recipe") == case.get("recipe") and not r["case"].get("mixed")]
    return {"case": case["id"], "input": case, "classification": status,
            "expected": row.get("independentExpectation") or row.get("independentShortage"),
            "actual": row.get("request") or row.get("reason"), "baselinePassingControls": controls,
            "firstDivergence": row.get("reason") or (row.get("request") or {}).get("feedback") or "Inspect raw timeline and earliest failed primitive",
            "componentsToInvestigate": causes, "resourceCalculations": row.get("conservation"),
            "failedPrimitiveOrValidator": "Not inferred from status alone; raw views/guard log required",
            "rawLogs": row.get("rawLogs", []), "physicalState": "cases/" + case["id"] + "/result.json",
            "reproductions": [r["case"]["id"] for r in all_rows if r["case"].get("reproductionOf") == case["id"]],
            "minimization": "Candidate only until reproduction outcome confirms the same divergence",
            "rootCauseConfidence": "Source-confirmed narrow material binding; gameplay refusal requires this case's evidence" if material_gap else
                                   "UNCONFIRMED: distinguish production behavior, timing and harness defects using raw evidence",
            "relatedCapabilities": ["stick", "wooden_axe", "crafting_table"] if material_gap else [case.get("output")],
            "architecturalCorrection": "Resolve recipe ingredient alternatives and propagate selected material identities through stock, leases, prerequisites, custody and validation" if material_gap else
                                       "Correct the first violated shared contract after excluding fixture/infrastructure defects; do not patch the individual seed",
            "regressionRisks": ["Changed artifact semantics", "Wrong ingredient eligibility or components", "Duplicate/absent effects at recovery", "Wrong surplus or existing-stock credit"],
            "requiredReusableRegressions": ["All vanilla ingredient alternatives and valid mixed grids", "Independent physical conservation", "Changed bindings with the backend unavailable"]}


def collect(root):
    campaign_path = root / "campaign.json"
    campaign = read(campaign_path) if campaign_path.exists() else {"status": "NOT_EXECUTED", "results": []}
    schedule = read(root / "schedule.json") if (root / "schedule.json").exists() else []
    rows, problems = [], []
    for item in campaign["results"]:
        path = root / item["path"]
        if not path.exists():
            problems.append("Missing result " + item["id"]); continue
        row = read(path)
        problems.extend(item["id"] + ": " + p for p in validate_case(row, root))
        rows.append(row)
    ids = [r["case"]["id"] for r in rows]
    if len(ids) != len(set(ids)):
        problems.append("Duplicate case identity")
    if set(ids) != {c["id"] for c in schedule}:
        problems.append("Scheduled cases and preserved results disagree")
    matrix = []
    for family in ["P"+str(i) for i in range(1, 9)]:
        selected = [c for c in schedule if c["family"] == family]
        actual = [r for r in rows if r["case"]["family"] == family]
        matrix.append({"family": family, "scheduled": len(selected), "attempted": sum(r["attempted"] for r in actual),
                       "terminalObserved": sum(r["terminalObserved"] for r in actual),
                       "physicalSuccesses": sum(r["status"] == "PASS" and bool(r.get("physicalDelta")) for r in actual),
                       "classifications": dict(Counter(r["status"] for r in actual)),
                       "notExecuted": len(selected)-sum(r["attempted"] for r in actual),
                       "heldOut": sum(r["case"].get("heldOut", False) for r in actual)})
        if campaign.get("mode") == "full" and len([c for c in selected if not c.get("heldOut")]) < 12:
            problems.append(family + " has fewer than 12 generated scheduled variations")
    substitutions = []
    oracle_path = root / "oracle-recipes.json"
    if oracle_path.exists():
        oracle = read(oracle_path)["recipes"]
        for recipe_id, recipe in oracle.items():
            if recipe_id.endswith("_planks") or recipe_id in {"minecraft:stick", "minecraft:wooden_axe", "minecraft:crafting_table"}:
                alternatives = set(x for group in recipe["ingredients"] for x in group if x != "minecraft:stick")
                assessed = {x for r in rows if r["case"].get("recipe") == recipe_id for x in r["case"].get("grid", []) if x}
                missing = sorted(alternatives-assessed)
                substitutions.append({"recipe": recipe_id, "alternatives": sorted(alternatives), "assessed": sorted(assessed), "unassessed": missing})
                if campaign.get("mode") == "full" and missing:
                    problems.append("Missing exhaustive recipe alternatives: " + recipe_id)
    by_id = {r["case"]["id"]: r for r in rows}
    metamorphic = []
    for row in rows:
        control_id = row["case"].get("metamorphicControlId")
        if not control_id:
            continue
        control = by_id.get(control_id)
        metamorphic.append({"property": "valid material substitution", "variant": row["case"]["id"],
                            "control": control_id, "controlStatus": control["status"] if control else "NOT_EXECUTED",
                            "variantStatus": row["status"], "outcome": "PRESERVED" if control and
                            control["status"] == row["status"] == "PASS" else "GENERALIZATION_GAP" if
                            control and control["status"] == "PASS" and row["status"].startswith("GAP") else
                            "INCONCLUSIVE_CONTROL"})
    failures = [diagnostic(r, rows) for r in rows if not r["status"].startswith("PASS")]
    capabilities = read(CAMPAIGN / "capabilities.json")
    capability_coverage = []
    for capability in capabilities["capabilities"]:
        surface = capability["playerSurface"].split("<")[0].strip()
        relevant = [r for r in rows if r.get("request", {}).get("command", "").startswith(surface)]
        physical = [r for r in relevant if r["status"] == "PASS" and (r.get("physicalDelta") or
                    r.get("before", {}).get("blocks") != r.get("after", {}).get("blocks"))]
        capability_coverage.append({**capability, "genuineExecutions": sum(r["attempted"] for r in relevant),
                                    "successes": len(physical), "failures": sum(r["status"].startswith("FAIL") for r in relevant),
                                    "safeRefusals": sum(r["status"] == "PASS_EXPECTED_REFUSAL" for r in relevant),
                                    "unsupported": sum(r["status"].startswith("GAP") for r in relevant),
                                    "status": "PHYSICALLY_VERIFIED" if physical else "NOT_PHYSICALLY_VERIFIED",
                                    "caseEvidence": [r["case"]["id"] for r in relevant]})
    execution_complete = campaign.get("status") == "RECORDED" and not problems and all(r["attempted"] or
                        r["status"] == "GAP_NO_GAMEPLAY_ADAPTER" for r in rows) and bool(rows)
    report = {"schema": 1, "campaign": campaign, "coverage": matrix, "recipeSubstitutions": substitutions,
              "capabilityCoverage": capability_coverage, "kernelPrimitiveAudit": capabilities["kernelPrimitiveAudit"],
              "metamorphicComparisons": metamorphic,
              "failures": failures, "evidenceProblems": problems,
              "executionComplete": execution_complete,
              "researchComplete": execution_complete and campaign.get("mode") == "full" and not any(
                  r["status"] in {"INCONCLUSIVE_BOUNDARY", "FAIL_HARNESS", "FAIL_UNCLASSIFIED"} for r in rows),
              "productionUnchanged": read(ROOT / "build/campaign-crafting-baseline.json") if
                  (ROOT / "build/campaign-crafting-baseline.json").exists() else None}
    write(root / "assessment.json", report)
    lines = ["# Crafting generalization campaign", "", "Campaign status: **" + campaign["status"] + "**.", "",
             "A workflow completing does not imply every gameplay test passed.", "",
             "| Family | Scheduled | Attempted | Terminal | Physical successes |", "|---|---:|---:|---:|---:|"]
    lines += [f'| {r["family"]} | {r["scheduled"]} | {r["attempted"]} | {r["terminalObserved"]} | {r["physicalSuccesses"]} |' for r in matrix]
    for failure in failures:
        lines += ["", "## " + failure["case"] + " — " + failure["classification"], "",
                  "Exact parameters, first divergence, components, physical state, raw logs, confidence and correction requirements:",
                  "", "```json", json.dumps(failure, indent=2), "```"]
    if problems:
        lines += ["", "## Evidence limitations", ""] + ["- " + p for p in problems]
    write(root / "coverage.json", matrix)
    (root / "report.md").write_text("\n".join(lines) + "\n")
    print(json.dumps({"status": campaign["status"], "cases": len(rows), "evidenceProblems": problems,
                      "classifications": dict(Counter(r["status"] for r in rows))}))
    if not execution_complete:
        raise SystemExit(2)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["baseline", "collect"])
    parser.add_argument("--root", type=Path, default=ROOT / "build/e2e/crafting")
    args = parser.parse_args()
    baseline() if args.action == "baseline" else collect(args.root.resolve())
