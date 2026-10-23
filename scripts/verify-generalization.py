#!/usr/bin/env python3
"""Verify unchanged production and complete, explicitly classified assessment evidence."""
import argparse
import hashlib
import json
import struct
import subprocess
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ALLOWED = {"PASS", "FAIL", "UNSUPPORTED", "BLOCKED", "INCONCLUSIVE", "NOT_EXECUTED"}
CORPUS_SHA256 = "ee2e323d2c329a4f6040c3b96ffe880a12a3d33a3b8954a7174dc32766eb8bc5"


def load(path):
    return json.loads(path.read_text())


def require(condition, message):
    if not condition:
        raise ValueError(message)


def corpus():
    path = ROOT / "e2e/generalization/cases.json"
    require(hashlib.sha256(path.read_bytes()).hexdigest() == CORPUS_SHA256, "Frozen corpus changed")
    return load(path)


def baseline(baseline_path=None):
    frozen = load(baseline_path or ROOT / "e2e/generalization/baseline.json")
    require(frozen["corpusSha256"] == CORPUS_SHA256, "Frozen input identity changed")
    for entry in frozen["productionFiles"]:
        path = ROOT / entry["path"]
        require(hashlib.sha256(path.read_bytes()).hexdigest() == entry["sha256"], "Production changed: "+entry["path"])
        blob = subprocess.check_output(["git", "hash-object", entry["path"]], cwd=ROOT, text=True).strip()
        require(blob == entry["gitBlob"], "Production Git identity changed: "+entry["path"])
    changed = subprocess.check_output(["git", "diff", "--name-only", frozen["sourceCommit"], "HEAD"], cwd=ROOT, text=True).splitlines()
    for name in changed:
        require(name.startswith("e2e/generalization") or name in {
            "e2e/client/java/dev/aivillages/e2e/ClientDriver.java",
            "e2e/observer/java/dev/aivillages/fabric/E2EObserver.java",
            "e2e/test_generalization_evidence.py", "scripts/verify-generalization.py",
            ".github/workflows/rb-generalization.yml"}, "Unexpected assessment change: "+name)
    jar = ROOT / "fabric/build/libs/ai-villages-0.1.0.jar"
    require(hashlib.sha256(jar.read_bytes()).hexdigest() == frozen["releaseJarSha256"], "Production JAR differs from frozen baseline")
    report = {"status": "PASS", "productionBaseline": frozen["sourceCommit"],
              "productionFilesUnchanged": len(frozen["productionFiles"]),
              "releaseJarSha256": frozen["releaseJarSha256"], "corpusSha256": CORPUS_SHA256,
              "assessmentCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
              "assessmentTree": subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], cwd=ROOT, text=True).strip(),
              "changedPaths": changed}
    path = ROOT / "build/generalization/production-identity.json"; path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2)+"\n")
    print(json.dumps(report))


def frame_check(root, capture, head, jar):
    path = root / capture["path"]
    require(path.resolve().is_relative_to(root.resolve()), "Capture escaped artifact")
    data = path.read_bytes()
    require(data[:8] == b"\x89PNG\r\n\x1a\n" and len(data) == capture["bytes"], "Capture bytes invalid")
    require(hashlib.sha256(data).hexdigest() == capture["sha256"], "Capture hash mismatch")
    require(struct.unpack(">II", data[16:24]) == (capture["width"], capture["height"]), "Capture dimensions mismatch")
    require(capture["width"] >= 800 and capture["height"] >= 600 and capture["nativeClientFrame"], "Native client frame required")
    require(capture["sourceCommit"] == head and capture["releaseSha256"] == jar, "Capture source/binary mismatch")


def case_check(row, expected):
    for field in ("id", "group", "category", "requirement", "expected", "composition"):
        require(row.get(field) == expected[field], "Changed frozen case field: "+expected["id"]+" "+field)
    require(row["status"] in ALLOWED, "Unknown scenario classification")
    if row["status"] == "UNSUPPORTED":
        require(row["category"] == "boundary" and row.get("evidence", {}).get("boundaryCheck") == "PASS",
                "Unsupported gameplay must have a separate successful boundary check")
    if row["status"] in {"FAIL", "BLOCKED", "INCONCLUSIVE", "NOT_EXECUTED"}:
        require(bool(row.get("reason")), "Nonpassing scenario requires an explicit reason")
    if row["status"] == "PASS":
        require(row["category"] != "boundary", "Boundary refusal cannot be gameplay PASS")
        require(bool(row.get("evidence")) and bool(row.get("screenshots")), "PASS needs original gameplay/interpretation evidence and a native frame")
        require(bool(row.get("physicalFinalState")), "PASS needs the actual final server observation for independent physical reconciliation")
        clients = row.get("connectedClientObservations", {})
        require(set(clients) == {"PlayerA", "PlayerB"} and all(v and v.get("play") and not v.get("singleplayer") for v in clients.values()),
                "PASS needs actual connected client observations for both multiplayer principals")
        known = row["physicalFinalState"].get("knownPlayers", {})
        require(all(clients[p].get("uuid") == known.get(p, {}).get("uuid") and clients[p].get("uuid") for p in clients),
                "Client observations must match the two server-recognized principal identities")


def metric_report(rows):
    def metric(selected, predicate=lambda r: r["status"] == "PASS"):
        cases = [r for r in rows if selected(r)]
        good = sum(bool(predicate(r)) for r in cases)
        return {"correct": good, "requestedDenominator": len(cases), "rate": good/len(cases) if cases else None,
                "executed": sum(r["status"] not in ("BLOCKED", "NOT_EXECUTED") for r in cases),
                "classifications": {s: sum(r["status"] == s for r in cases) for s in sorted(ALLOWED)}}
    return {
        "parameterGeneralization": metric(lambda r: r["category"] == "parameter" and not r["composition"]),
        "compositionGeneralization": metric(lambda r: r["category"] == "parameter" and r["composition"]),
        "supportedGameplay": metric(lambda r: r["category"] == "parameter"),
        "dynamicWorldCorrectness": metric(lambda r: r["category"] == "dynamic"),
        "naturalLanguageInterpretation": metric(lambda r: r["category"] == "language",
                                               lambda r: r["status"] == "PASS" and r.get("evidence", {}).get("interpretationCorrect") is True),
        "capabilityBoundaryCorrectness": metric(lambda r: r["category"] == "boundary",
                                               lambda r: r["status"] == "UNSUPPORTED" and r.get("evidence", {}).get("boundaryCheck") == "PASS"),
        "crashRecovery": metric(lambda r: r["id"] == "C09"),
        "resourceConservation": {"status": "PENDING_INDEPENDENT_PHYSICAL_RECONCILIATION",
                                 "requestedScenarioIds": [r["id"] for r in rows if r["category"] in ("parameter", "dynamic") or r["id"] in ("D03", "D04")],
                                 "requestedDenominator": sum(r["category"] in ("parameter", "dynamic") or r["id"] in ("D03", "D04") for r in rows),
                                 "reason": "Per-scenario receipts, real stocks/custody and original mutations remain available; aggregate CI does not infer conservation from assertion status."}}


def collect(directory, baseline_path=None):
    specs = {r["id"]: r for r in corpus()["scenarios"]}; rows = {}; groups = {}; frames = 0
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    frozen = load(baseline_path or ROOT / "e2e/generalization/baseline.json")
    for path in directory.rglob("generalization-results.json"):
        data = load(path); group = data["group"]
        require(group not in groups, "Duplicate group artifacts")
        groups[group] = {"measurementStatus": data["measurementStatus"], "counts": data["counts"]}
        meta = data.get("metadata", {})
        require(meta.get("sourceCommit") == head and meta.get("releaseSha256") == frozen["releaseJarSha256"] and not meta.get("dirty"), "Group lacks clean exact-source/binary provenance")
        for row in data["scenarios"]:
            require(row["id"] not in rows and row["group"] == group, "Duplicate or cross-group case")
            case_check(row, specs[row["id"]])
            for capture in row.get("screenshots", []):
                frame_check(path.parent, capture, head, frozen["releaseJarSha256"]); frames += 1
            rows[row["id"]] = row
    require(set(rows) == set(specs) and len(groups) == 5, "All 45 frozen cases and five original groups required")
    report = {"schema": 1, "assessmentCommit": head, "productionBaseline": frozen["sourceCommit"],
              "releaseJarSha256": frozen["releaseJarSha256"], "corpusSha256": CORPUS_SHA256,
              "measurementStatus": "COMPLETE" if all(g["measurementStatus"] == "COMPLETE" for g in groups.values()) else "INCOMPLETE",
              "gameplayPassRateEnforced": False, "groups": groups, "verifiedNativeFrames": frames,
              "metrics": metric_report(list(rows.values())), "scenarios": [rows[k] for k in sorted(rows)]}
    target = directory / "generalization-assessment.json"; target.write_text(json.dumps(report, indent=2)+"\n")
    print(json.dumps({k: v for k, v in report.items() if k != "scenarios"}, indent=2))
    require(report["measurementStatus"] == "COMPLETE", "Incomplete execution; original unexecuted/blocked evidence retained")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("baseline", "junit", "collect")); parser.add_argument("--directory", type=Path)
    args = parser.parse_args()
    if args.command == "baseline": baseline()
    elif args.command == "collect": collect(args.directory)
    else:
        counts = {}
        for module in ("core", "providers", "fabric"):
            cases = [c for p in (ROOT / module / "build/test-results/test").glob("TEST-*.xml")
                     for c in ET.parse(p).getroot().iter("testcase")]
            require(cases and not any(c.find(t) is not None for c in cases for t in ("failure", "error", "skipped")), "Nonpassing module tests: "+module)
            counts[module] = len(cases)
        require(sum(counts.values()) >= 1700, "Module regression selection shrank")
        print(json.dumps({"moduleCases": counts, "total": sum(counts.values())}))


if __name__ == "__main__": main()
