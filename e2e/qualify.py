#!/usr/bin/env python3
"""Require exact-source network evidence; never promote internal proof to E2E."""
import argparse
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from suite import FAMILIES, PROFILES


def qualify(root: Path) -> bool:
    executed = {family: [] for family in FAMILIES}
    identities, missing = [], []
    for path in root.rglob("results.json"):
        document = json.loads(path.read_text())
        if document.get("profile") not in PROFILES:
            continue
        selected = PROFILES[document["profile"]]
        seen = set()
        for result in document["results"]:
            family = result["id"]
            if family in selected:
                if family in seen:
                    missing.append(str(path) + ": duplicate " + family)
                seen.add(family)
                captures = result.get("screenshots", [])
                if result.get("status") == "PASS" and not captures:
                    missing.append(str(path) + ": no actual client screenshots for " + family)
                for capture in captures:
                    relative = Path(capture.get("path", ""))
                    image = path.parent / relative
                    safe = not relative.is_absolute() and ".." not in relative.parts
                    data = image.read_bytes() if safe and image.is_file() else b""
                    if not (data[:8] == b"\x89PNG\r\n\x1a\n" and len(data) > 1024 and
                            int.from_bytes(data[16:20], "big") >= 800 and int.from_bytes(data[20:24], "big") >= 600 and
                            hashlib.sha256(data).hexdigest() == capture.get("sha256") and capture.get("family") == family and
                            capture.get("nativeClientFrame") is True):
                        missing.append(str(path) + ": invalid screenshot for " + family)
                executed[family].append({**result, "report": str(path)})
        for family in set(selected) - seen:
            missing.append(str(path) + ": missing " + family)
        metadata = path.with_name("metadata.json")
        if metadata.exists():
            identity = json.loads(metadata.read_text())
            identities.append(identity)
            for result in document["results"]:
                if result["id"] in selected:
                    for capture in result.get("screenshots", []):
                        if (capture.get("sourceCommit"),capture.get("releaseSha256")) != \
                                (identity.get("sourceCommit"),identity.get("releaseSha256")):
                            missing.append(str(path) + ": obsolete screenshot envelope for " + result["id"])
        else:
            missing.append(str(path) + ": metadata")
    families = {family: {"status": "PASS" if rows and all(r["status"] == "PASS" for r in rows)
                else "FAIL" if rows else "NOT_EXECUTED", "observations": rows}
                for family, rows in executed.items()}
    coverage = json.loads(Path(__file__).with_name("coverage.json").read_text())
    owner_path = root / "owner-evidence.json"
    owners = json.loads(owner_path.read_text()) if owner_path.exists() else {}
    owner_runs = {row["workflow"]: row for row in owners.get("runs", [])}
    units = []
    for unit in coverage["units"]:
        components = []
        for test in unit["componentTests"]:
            reports = list(root.rglob("TEST-" + test["suite"] + ".xml"))
            cases = [c for report in reports for c in ET.parse(report).getroot().iter("testcase")]
            components.append({"suite": test["suite"], "cases": len(cases), "reports": list(map(str,reports)),
                "status": "PASS" if cases and not any(c.find(t) is not None for c in cases
                    for t in ("failure", "error", "skipped")) else "FAIL" if cases else "NOT_EXECUTED"})
        component_status = "PASS" if components and all(c["status"] == "PASS" for c in components) \
                else "FAIL" if any(c["status"] == "FAIL" for c in components) else "NOT_EXECUTED"
        owner = owner_runs.get(unit.get("ownerWorkflow"), {})
        native_status = owner.get("status", "NOT_EXECUTED")
        exposure = unit.get("networkExposure", "CLIENT_REACHABLE")
        variants = []
        for variant in unit.get("requiredVariants", []):
            proofs = [row.get("evidence",{}).get("variants",{}).get(variant,{})
                      for family in unit["families"] for row in executed[family]]
            variants.append({"id": variant, "status": "PASS" if any(p.get("status") == "PASS" for p in proofs)
                             else "NOT_EXECUTED", "evidence": proofs})
        network = exposure if exposure != "CLIENT_REACHABLE" else (
            "PASS" if unit["families"] and all(families[f]["status"] == "PASS" for f in unit["families"])
            and all(v["status"] == "PASS" for v in variants) else "FAIL")
        conditional = unit["implementation_status"] == "ABSENT"
        qualified = conditional or component_status == "PASS" and native_status == "PASS" and \
                network in ("PASS", "NOT_CLIENT_EXPOSED")
        units.append({**unit, "unit_result": component_status, "GameTest_result": native_status,
                      "network_E2E_result": network, "networkVariants": variants,
                      "live_AI_result": "NOT_EXECUTED" if unit["unit"] == "IMP-019" else "NOT_APPLICABLE",
                      "componentResults": components, "nativeEvidence": owner,
                      "status": "BLOCKED_BY_IMPLEMENTATION" if conditional else "PASS" if qualified else "FAIL"})
    binaries = bool(identities) and all(m.get("sourceBranch") and m.get("binaries") and all(
        m["binaries"].get(name,{}).get("sha256") and m["binaries"].get(name,{}).get("bytes",0)>0
        for name in ("production","clientDriver","observer","officialClient","officialServer"))
        and m["binaries"]["production"]["sha256"] == m["releaseSha256"] for m in identities)
    consistent = binaries and not missing and len({(m["sourceCommit"],m["sourceTree"],m["releaseSha256"])
        for m in identities}) == 1 and not any(m["dirty"] for m in identities)
    owner_consistent = bool(identities) and owners.get("headCommit") == identities[0].get("reviewedHeadCommit") \
        and set(owner_runs) == set(coverage["ownerWorkflows"]) and all(r.get("status") == "PASS" for r in owner_runs.values())
    plan_quality = owner_runs.get("goal-planning.yml",{}).get("liveAI", {})
    for unit in units:
        if unit["unit"] == "IMP-019":
            unit["live_AI_result"] = plan_quality.get("status", "NOT_EXECUTED")
            unit["live_AI_evidence"] = plan_quality
        elif unit["unit"] in ("IMP-005", "IMP-006", "IMP-007", "IMP-014"):
            genuine = [row for row in executed["E2E-AI-001"] if row.get("status") == "PASS" and
                       row.get("evidence",{}).get("acquisition",{}).get("generationCalls",0) > 0 and
                       row.get("evidence",{}).get("models")]
            unit["live_AI_result"] = "PASS" if genuine else "NOT_EXECUTED"
            unit["live_AI_evidence"] = [{"report":row["report"],"evidence":row["evidence"]} for row in genuine]
            unit["knownReuse_result"] = "PASS" if genuine and all(row["evidence"].get("modelFreeReuse",{}).get(
                    "generationCalls") == 0 for row in genuine) else "NOT_EXECUTED"
            if unit["live_AI_result"] != "PASS" or unit["knownReuse_result"] != "PASS": unit["status"] = "FAIL"
        elif unit["unit"] == "IMP-009":
            genuine = [row for row in executed["E2E-NLU-001"] if row.get("status") == "PASS" and
                       row.get("evidence",{}).get("needleCalls",0) > 0]
            unit["live_AI_result"] = "PASS" if genuine else "NOT_EXECUTED"
            unit["live_AI_evidence"] = [{"report":row["report"],"calls":row["evidence"]["needleCalls"]} for row in genuine]
            if not genuine: unit["status"] = "FAIL"
    passed = consistent and owner_consistent and all(f["status"] == "PASS" for f in families.values()) \
        and all(u["status"] in ("PASS", "BLOCKED_BY_IMPLEMENTATION") for u in units) \
        and plan_quality.get("status") == "PASS"
    output = {"schema":2,"status":"PASS" if passed else "FAIL","consistentExactBinary":consistent,
              "ownerSourceConsistency":owner_consistent,"identities":identities,"families":families,
              "implementationCoverage":units,"missingEnvelopes":missing,
              "notClientExposed":[u["unit"] for u in units if u["network_E2E_result"] == "NOT_CLIENT_EXPOSED"],
              "conditionalMissing":[u["unit"] for u in units if u["implementation_status"] == "ABSENT"]}
    (root / "qualification.json").write_text(json.dumps(output,indent=2)+"\n")
    ledger = json.loads(Path(__file__).with_name("reports").joinpath("production-fixes.json").read_text())
    fix_validation = {"schema":1,"status":output["status"],"identities":identities,
            "productionCodeFixesMade":ledger["productionCodeFixesMade"],"productionFixCount":ledger["productionFixCount"],
            "fixes":[{**fix,"finalValidation":{"status":"PASS" if passed else "FAIL",
                "networkFamilies":{f:families[f] for f in fix.get("families",[fix.get("family")]) if f in families},
                "ownerRuns":owners.get("runs",[])}} for fix in ledger["fixes"]]}
    (root / "production-fix-validation.json").write_text(json.dumps(fix_validation,indent=2)+"\n")
    print("Qualification", output["status"], "exact binary",consistent,"owner source",owner_consistent)
    for unit in units:
        print(unit["unit"], "unit="+unit["unit_result"], "GameTest="+unit["GameTest_result"],
              "network="+unit["network_E2E_result"])
    return passed


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root",type=Path)
    args = parser.parse_args()
    args.root.mkdir(parents=True,exist_ok=True)
    raise SystemExit(0 if qualify(args.root) else 1)
