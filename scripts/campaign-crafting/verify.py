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
from campaigns.crafting.evidence import (record_problems, evidence_path, file_hash, log_facts,
                                        compact_result, PASS_STATUSES)

BASELINE = "ea7de8000b2b484b885576c34ed5358274936ce9"
CAMPAIGN = ROOT / "e2e/campaigns/crafting"


def read(path):
    return json.loads(path.read_text())


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def production_mismatches(root, frozen, tracked):
    paths = list(frozen["files"])
    mismatch = [name for name in paths if not (root / name).is_file() or
                file_hash(root / name) != frozen["files"][name]]
    production = {p for p in tracked if any(p.startswith(x + "/src/main/") for x in ("core", "providers", "fabric"))}
    production.update(str(p.relative_to(root)) for module in ("core", "providers", "fabric")
                      for p in (root / module / "src/main").rglob("*") if p.is_file())
    mismatch.extend(p for p in production if p not in paths)
    return sorted(set(mismatch))


def baseline():
    frozen = read(CAMPAIGN / "baseline.json")
    if frozen["commit"] != BASELINE:
        raise ValueError("Frozen production commit changed")
    paths = list(frozen["files"])
    tracked = subprocess.check_output(["git", "ls-files"], cwd=ROOT, text=True).splitlines()
    mismatch = production_mismatches(ROOT, frozen, tracked)
    if mismatch:
        raise ValueError("Production baseline changed: " + ", ".join(mismatch))
    record = {"productionBaseline": BASELINE, "filesChecked": len(paths), "unchanged": True,
              "campaignCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
              "manifestHash": sha(frozen)}
    write(ROOT / "build/campaign-crafting-baseline.json", record)
    print(json.dumps(record))


def validate_case(row, root):
    problems = record_problems(row)
    case = row["case"]
    passing = row["status"] in PASS_STATUSES
    row["logFacts"] = {}
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
    available = {entry.get("path") for entry in row.get("rawLogs", []) if entry.get("available")}
    required = {"server/stdout.log", "server/observations.jsonl", "clients/PlayerA/events.jsonl", "clients/PlayerA/stdout.log"}
    if row.get("attempted") and required - available:
        problems.append("Attempted case lacks original server/client logs: " + ", ".join(sorted(required-available)))
    for entry in row.get("rawLogs", []):
        if entry.get("available"):
            try:
                path = evidence_path(root, entry["slice"])
            except ValueError as error:
                problems.append(str(error)); continue
            if not path.is_file() or file_hash(path) != entry["sha256"]:
                problems.append("Raw log slice missing or changed: " + entry["slice"])
                continue
            start, end = entry.get("startByte"), entry.get("endByte")
            if type(start) is not int or type(end) is not int or not 0 <= start <= end or path.stat().st_size != end-start:
                problems.append("Raw log byte boundaries disagree with preserved bytes")
            if entry["path"] in {"server/stdout.log", "clients/PlayerA/events.jsonl"}:
                try:
                    facts = log_facts(path, partial_first=bool(start), cutoff=row.get("evidenceCutoffBeforeCleanup"),
                                      strict_events=entry["path"] == "clients/PlayerA/events.jsonl")
                except (ValueError, KeyError, TypeError) as error:
                    problems.append("Malformed original trace: " + str(error)); continue
                row["logFacts"][entry["path"]] = facts
                if passing and entry["path"] == "server/stdout.log":
                    changed = row.get("physicalDelta") or row.get("before", {}).get("blocks") != row.get("after", {}).get("blocks")
                    if changed and not facts["primitiveStarts"]:
                        problems.append("Physical success lacks original primitive-start instrumentation")
                    if facts["incompletePrimitiveTrace"]:
                        problems.append("Primitive trace serialization is incomplete")
    commands = row["logFacts"].get("clients/PlayerA/events.jsonl", {}).get("commands", [])
    request = row.get("request") or {}
    row["connectedCommandObserved"] = request.get("command") in commands and bool(request.get("feedback"))
    if passing and not row["connectedCommandObserved"]:
        problems.append("Passing case lacks the original connected command and its acknowledgement")
    if passing and any(r.get("command") not in commands for r in row.get("requests", [])):
        problems.append("An execution or reuse request has no original client command trace")
    for capture in row.get("screenshots", []):
        try:
            path = evidence_path(root, capture["path"])
        except ValueError as error:
            problems.append(str(error)); continue
        if not path.is_file() or file_hash(path) != capture["sha256"]:
            problems.append("Original native frame missing or changed")
            continue
        with path.open("rb") as stream:
            header = stream.read(24)
        if (capture.get("nativeClientFrame") is not True or header[:8] != b"\x89PNG\r\n\x1a\n" or
                len(header) != 24 or int.from_bytes(header[16:20], "big") < 800 or int.from_bytes(header[20:24], "big") < 600):
            problems.append("Invalid native frame identity or dimensions")
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
                r["case"]["id"] == case.get("metamorphicControlId")]
    first_failure = row.get("logFacts", {}).get("server/stdout.log", {}).get("firstFailedPrimitive")
    return {"case": case["id"], "input": case, "classification": status,
            "expected": row.get("independentExpectation") or row.get("independentShortage"),
            "actual": row.get("request") or row.get("reason"), "baselinePassingControls": controls,
            "firstDivergence": first_failure or row.get("reason") or (row.get("request") or {}).get("feedback") or "Inspect raw timeline and earliest failed primitive",
            "componentsToInvestigate": causes, "resourceCalculations": row.get("conservation"),
            "failedPrimitiveOrValidator": first_failure or "Not inferred from status alone; raw views/guard log required",
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
    metadata = read(root / "metadata.json") if (root / "metadata.json").exists() else {}
    if not metadata or metadata.get("productionBaseline") != BASELINE:
        problems.append("Missing or mismatched campaign runtime identity")
    frozen = read(root / "definitions-frozen.json") if (root / "definitions-frozen.json").exists() else None
    held = read(root / "held-out-seed.json") if (root / "held-out-seed.json").exists() else None
    if not frozen or sha(frozen) != campaign.get("definitionHash"):
        problems.append("Missing or changed frozen test definitions")
    if not campaign.get("replay") and (not held or not frozen or held.get("definitionHash") != sha(frozen) or
            held.get("generatedAt", -1) < frozen.get("frozenAt", 0) or held.get("seed") != campaign.get("heldOutSeed")):
        problems.append("Held-out seed is not tied to the earlier definition freeze")
    for item in campaign["results"]:
        try:
            path = evidence_path(root, item["path"])
            if not path.exists():
                problems.append("Missing result " + item["id"]); continue
            row = read(path)
            if item.get("id") != row.get("case", {}).get("id") or item.get("status") != row.get("status"):
                problems.append("Campaign summary differs from original case identity/classification")
            scheduled_case = next((case for case in schedule if case.get("id") == item["id"]), None)
            if scheduled_case != row.get("case"):
                problems.append("Case parameters differ from the frozen scheduled inputs: " + item["id"])
            identity_fields = ("sourceCommit", "sourceTree", "releaseSha256", "productionBaseline", "minecraft")
            if any(row.get("metadata", {}).get(key) != metadata.get(key) for key in identity_fields):
                problems.append("Case runtime identity differs from this campaign: " + item["id"])
            problems.extend(item["id"] + ": " + p for p in validate_case(row, root))
            compact = compact_result(row)
            compact["connectedCommandObserved"] = row["connectedCommandObserved"]
            rows.append(compact)
        except (ValueError, TypeError, AttributeError, KeyError, OSError) as error:
            problems.append("Case evidence cannot be verified for " + str(item.get("id")) + ": " + repr(error))
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
                       "genuineExecutions": sum(r.get("connectedCommandObserved", False) for r in actual),
                       "physicalSuccesses": sum(r["status"] == "PASS" and r.get("physicalEffectObserved", False) for r in actual),
                       "classifications": dict(Counter(r["status"] for r in actual)),
                       "notExecuted": len(selected)-sum(r["attempted"] for r in actual),
                       "heldOut": sum(r["case"].get("heldOut", False) for r in actual)})
        if campaign.get("mode") == "full" and len([c for c in selected if not c.get("heldOut")]) < 12:
            problems.append(family + " has fewer than 12 generated scheduled variations")
    substitutions = []
    oracle_path = root / "oracle-recipes.json"
    if not oracle_path.exists():
        problems.append("No actual running-game recipe catalogue was preserved")
    else:
        oracle_record = read(oracle_path)
        if oracle_record.get("minecraft") != "26.3" or oracle_record.get("worldEffects") != 0 or not oracle_record.get("serverPid"):
            problems.append("Recipe catalogue lacks the actual read-only server identity")
        oracle = oracle_record["recipes"]
        for recipe_id, recipe in oracle.items():
            if recipe_id.endswith("_planks") or recipe_id in {"minecraft:stick", "minecraft:wooden_axe", "minecraft:crafting_table"}:
                alternatives = set(x for group in recipe["ingredients"] for x in group if x != "minecraft:stick")
                assessed = {x for r in rows if r["case"].get("recipe") == recipe_id and
                            r.get("vanillaProbe", {}).get("matches") is True and
                            r.get("vanillaProbe", {}).get("worldEffects") == 0 for x in r["case"].get("grid", []) if x}
                executed = {x for r in rows if r["case"].get("recipe") == recipe_id and r.get("connectedCommandObserved")
                            for x in r["case"].get("grid", []) if x}
                missing = sorted(alternatives-assessed)
                substitutions.append({"recipe": recipe_id, "alternatives": sorted(alternatives), "assessed": sorted(assessed),
                                      "genuineGameplayAlternatives": sorted(executed), "unassessed": missing})
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
        physical = [r for r in relevant if r["status"] == "PASS" and r.get("physicalEffectObserved")]
        capability_coverage.append({**capability, "genuineExecutions": sum(r.get("connectedCommandObserved", False) for r in relevant),
                                    "successes": len(physical), "failures": sum(r["status"].startswith("FAIL") for r in relevant),
                                    "safeRefusals": sum(r["status"] == "PASS_EXPECTED_REFUSAL" for r in relevant),
                                    "unsupported": sum(r["status"].startswith("GAP") for r in relevant),
                                    "status": "PHYSICALLY_VERIFIED" if physical else "NOT_PHYSICALLY_VERIFIED",
                                    "caseEvidence": [r["case"]["id"] for r in relevant]})
    execution_complete = campaign.get("status") == "RECORDED" and not problems and all(r["attempted"] or
                        r["status"] == "GAP_NO_GAMEPLAY_ADAPTER" for r in rows) and bool(rows)
    report = {"schema": 1, "campaign": campaign, "coverage": matrix, "recipeSubstitutions": substitutions,
              "capabilityCoverage": capability_coverage, "kernelPrimitiveAudit": capabilities["kernelPrimitiveAudit"],
              "finiteOwnerLimits": capabilities.get("finiteOwnerLimits", {}),
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
