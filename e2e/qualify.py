#!/usr/bin/env python3
"""Require observed successful canonical families and their precise component gates."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from suite import FAMILIES, PROFILES


def qualify(root: Path) -> bool:
    executed = {family: [] for family in FAMILIES}
    identities = []
    missing_envelopes = []
    for path in root.rglob("results.json"):
        document = json.loads(path.read_text())
        if document.get("profile") not in PROFILES:
            continue
        selected = PROFILES[document["profile"]]
        for result in document["results"]:
            if result["id"] in selected:
                executed[result["id"]].append({**result, "report": str(path)})
        metadata = path.with_name("metadata.json")
        if metadata.exists():
            identities.append(json.loads(metadata.read_text()))
        else:
            missing_envelopes.append(str(path))
    family_results = {}
    for family, results in executed.items():
        family_results[family] = {"status": "PASS" if results and all(r["status"] == "PASS" for r in results)
                                  else "FAIL" if results else "NOT_EXECUTED", "observations": results}
    coverage = json.loads(Path(__file__).with_name("coverage.json").read_text())
    units = []
    for unit in coverage["units"]:
        components = []
        for test in unit["componentTests"]:
            reports = list(root.rglob("TEST-" + test["suite"] + ".xml"))
            cases = [c for report in reports for c in ET.parse(report).getroot().iter("testcase")]
            components.append({"suite": test["suite"], "cases": len(cases), "status":
                    "PASS" if cases and not any(c.find(tag) is not None for c in cases
                    for tag in ("failure", "error", "skipped")) else "FAIL" if cases else "NOT_EXECUTED"})
        status = "PASS" if all(family_results[f]["status"] == "PASS" for f in unit["families"]) and \
                  all(c["status"] == "PASS" for c in components) else "FAIL"
        units.append({**unit, "status": status, "componentResults": components})
    binary_evidence = all(m.get("sourceBranch") and m.get("binaries") and all(
            m["binaries"].get(name,{}).get("sha256") and m["binaries"].get(name,{}).get("bytes",0)>0
            for name in ("production","clientDriver","observer","officialClient","officialServer"))
            and m["binaries"]["production"]["sha256"] == m["releaseSha256"] for m in identities)
    consistent = bool(identities) and not missing_envelopes and binary_evidence and len({
            (m["sourceCommit"],m["sourceTree"],m["releaseSha256"]) for m in identities}) == 1 \
            and not any(m["dirty"] for m in identities)
    passed = consistent and all(v["status"] == "PASS" for v in family_results.values()) and \
             all(u["status"] == "PASS" for u in units)
    (root / "qualification.json").write_text(json.dumps({"schema": 1, "status": "PASS" if passed else "FAIL",
            "consistentExactBinary": consistent, "identities": identities, "families": family_results,
            "implementationCoverage": units,"missingEnvelopes":missing_envelopes}, indent=2) + "\n")
    print("Qualification", "PASS" if passed else "FAIL")
    print("Exact binary/source consistency",consistent,"implementation mappings",len(units),
          "passed",sum(u["status"]=="PASS" for u in units))
    if consistent:
        print("Qualified source",identities[0]["sourceCommit"],"tree",identities[0]["sourceTree"],
              "production SHA-256",identities[0]["releaseSha256"])
    for family, result in family_results.items():
        print(family, result["status"])
    return passed


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    args.root.mkdir(parents=True, exist_ok=True)
    raise SystemExit(0 if qualify(args.root) else 1)
