#!/usr/bin/env python3
"""Independent C evidence collector. XML/files are observations; runner pass flags have no authority."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET

from common import BASE, BASE_TREE, BUILD_COMMAND, CONTRACT, HERE, ROOT, EvidenceError
from common import artifact, check, contained, contract_hashes, digest, git, read, tracked_clean, write


def xml_cases(paths):
    rows = {}
    for path in sorted(map(Path, paths)):
        data = path.read_bytes()
        check(b"<!DOCTYPE" not in data and b"<!ENTITY" not in data, "XML entities are forbidden")
        root = ET.fromstring(data)
        for suite in root.iter("testsuite"):
            cases = list(suite.findall("testcase"))
            if not cases and list(suite.findall("testsuite")):
                continue
            check(int(suite.get("tests", -1)) == len(cases), "XML test count mismatch: " + str(path))
            for tag, attr in (("failure", "failures"), ("error", "errors"), ("skipped", "skipped")):
                check(int(suite.get(attr, "0")) == sum(case.find(tag) is not None for case in cases),
                      "XML " + attr + " mismatch: " + str(path))
            for case in cases:
                cls, name = case.get("classname"), case.get("name")
                check(cls and name, "XML missing stable case identity")
                key = cls + "#" + name
                check(key not in rows, "duplicate XML case: " + key)
                outcome = "FAIL" if case.find("failure") is not None or case.find("error") is not None else (
                    "INCONCLUSIVE" if case.find("skipped") is not None else "PASS")
                rows[key] = {"outcome": outcome, "file": str(path)}
    check(rows, "missing actual JUnit cases")
    return rows


def verify_jar(jar, expected):
    check(Path(jar).is_file(), "missing production JAR")
    check(digest(jar) == expected, "changed production JAR hash")


def require_frames(root, requirements, artifacts):
    for case in requirements:
        check(case.get("frames"), "mandatory native frames absent: " + case["id"])
        for frame in case["frames"]:
            check(frame in artifacts, "native frame missing from artifact index: " + frame)
            artifact(root, frame, artifacts[frame])
        # Native assertions belong to a named implemented stage oracle; file existence alone cannot prove them.
        raise EvidenceError("native oracle not implemented for this C00 registry: " + case["id"])


def verify_build(directory, pins, expected_jar=None):
    record = read(directory / "build-record.json")
    for key, value in pins.items(): check(record.get(key) == value, "build pin mismatch: " + key)
    check(record.get("command") == BUILD_COMMAND, "unexpected build command")
    check(record.get("exit") == 0 and not record.get("blocked"), "module build unavailable or failed")
    check(record["ended_ns"] > record["started_ns"], "invalid build interval")
    check(re.search(r'(?:version\s+"|openjdk\s+)25(?:[.\s"+-])', record["environment"]["java"]["output"]), "target Java 25 build required")
    verify_jar(directory / "production.jar", record["jar_sha256"])
    artifact(directory, "build.log", record["build_log_sha256"])
    if expected_jar is not None: check(record["jar_sha256"] == expected_jar, "build/release JAR mismatch")
    paths = []
    for path, sha in record["xml"].items(): artifact(directory, path, sha); paths.append(contained(directory, path))
    check({Path(path).parts[1] for path in record["xml"]} == {"core", "providers", "fabric"}, "all three module XML sets required")
    return xml_cases(paths)


def metric(rows, planned):
    counts = {outcome: sum(r["outcome"] == outcome for r in rows.values()) for outcome in ("PASS", "FAIL", "REFUSED_EXPECTED", "INCONCLUSIVE")}
    return {"planned": planned, "executed": len(rows), "passed": counts["PASS"], "failed": counts["FAIL"],
            "refused_expected": counts["REFUSED_EXPECTED"], "inconclusive": counts["INCONCLUSIVE"]}


def integration_claims(run):
    check(run.get("original_root_id") is None and run.get("original_owner_id") is None
          and run.get("a_settlement") is None, "C cannot fabricate integrated A settlement/identity")


def qualify(root, stage, source, tree, jar, docs_root=None):
    root = Path(root).resolve()
    tracked_clean()
    check(git(ROOT, "rev-parse", "HEAD") == source, "oracle must execute at the frozen source commit")
    registry = read(HERE / "registries" / (stage + ".json"))
    check(registry["implementation"] == "IMPLEMENTED" and stage == "C00", "stage is NOT_EXECUTED; implement its own oracle first")
    contract_hashes()
    run = read(root / "run.json")
    check(run["source_sha"] == source and run["tree_sha"] == tree, "run/source/tree mismatch")
    check(git(ROOT, "rev-parse", source + "^{tree}") == tree, "source tree does not match commit")
    check(run["stage"] == stage and run["contract_sha256"] == CONTRACT, "stage/contract mismatch")
    check(run["registry_sha256"] == digest(HERE / "registries" / (stage + ".json")), "case registry changed after freeze")
    check(run.get("jar_sha256") is not None, "missing measured candidate JAR")
    verify_jar(jar, run["jar_sha256"])
    integration_claims(run)
    artifacts = read(root / "artifacts.json")
    check("run.json" in artifacts, "unhashed run identity")
    for path, sha in artifacts.items(): artifact(root, path, sha)
    check(run["seed"] == registry["seed"], "campaign seed changed")
    check(not run.get("native_results") and not run.get("client_results"), "unregistered physical/client result")
    require_frames(root, registry["native_cases"] + registry["client_cases"], artifacts)
    baseline = verify_build(root / "baseline", {"source_sha": BASE, "tree_sha": BASE_TREE})
    unit = verify_build(root / "candidate", {"source_sha": source, "tree_sha": tree}, run["jar_sha256"])
    check(set(baseline).issubset(unit), "a frozen baseline module case is missing")
    expected_unit = {case["xml_id"] for case in registry["unit_cases"]}
    check(set(unit) - set(baseline) == expected_unit, "missing/unregistered owned Java case")
    check(len(baseline) == registry["baseline_module_cases"], "frozen baseline count changed")
    python = xml_cases([root / "python/python.xml"])
    check(set(python) == set(registry["python_cases"]), "missing/unregistered Python case")
    process = read(root / "python/python-process.json")
    check(process["exit"] == 0 and process["source_sha"] == source and process["tree_sha"] == tree, "Python process pin/exit mismatch")
    compile_record = read(root / "native/compile.json")
    check(compile_record["exit"] == 0 and compile_record["jar_sha256"] == run["jar_sha256"], "native fixture compiler failed/unavailable")
    check(compile_record["command"][1:3] == ["--release", "25"] and compile_record["classes"], "missing exact native compiler evidence")
    for path, sha in compile_record["classes"].items():
        artifact(root / "native", path, sha)
        data = contained(root / "native", path).read_bytes()
        check(data[:4] == b"\xca\xfe\xba\xbe" and struct.unpack_from(">H", data, 6)[0] == 69, "fixture was not compiled for Java 25")
    check(compile_record["sdk_artifacts"] and compile_record["api_discovery"], "missing SDK/API discovery")
    for path, sha in compile_record["sdk_artifacts"].items():
        check(Path(path).is_file() and digest(path) == sha, "SDK/release changed after compiler gate: " + path)
    for api in compile_record["api_discovery"]:
        check(api.get("sdk_verified") is True and api.get("evidence"), "unverified native API")
        log = contained(root / "native", api["evidence"])
        check(api["symbol"] in log.read_text(), "native API discovery log does not contain symbol")
    ownership = read(root / "ownership.json")
    check(ownership["gate_status"] == "OWNED_GATES_PASS" and not ownership["blocked"], "complete ownership/bytecode/packaging gate required")
    # Recheck actual trees, bytes, SDK/JAR isolation and docs. A JSON PASS label cannot replace these audits.
    from ownership import audit
    from docs_check import check_links
    docs_root = Path(docs_root or ROOT.parent / "CognitiveCraft-Documentation-C")
    current_owner = audit(docs_root=docs_root)
    check(current_owner["gate_status"] == "OWNED_GATES_PASS", "independent current ownership audit is incomplete")
    check_links(docs_root, "docs/implementation/parallel-c", stage)
    docs = read(root / "docs-check.json")
    check(docs["status"] == "PASS" and docs["stage"] == stage, "owned docs links/fragments gate required")
    totals = {"unit": metric(unit, len(baseline) + len(expected_unit)), "python": metric(python, len(registry["python_cases"]))}
    for rows in (unit, python): check(all(r["outcome"] == "PASS" for r in rows.values()), "a regression blocks advancement")
    return {"gate_status": "OWNED_GATES_PASS", "stage": stage, "source_sha": source, "tree_sha": tree,
            "jar_sha256": run["jar_sha256"], "contract_sha256": CONTRACT, "counts": totals,
            "native_executed": 0, "client_executed": 0, "workflow_run": None, "attempt": None,
            "blocked": registry["deferred"], "claim": "C00 component qualification; no physical or production UI proof"}


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--stage", required=True); p.add_argument("--root", type=Path, required=True)
    p.add_argument("--source", required=True); p.add_argument("--tree", required=True)
    p.add_argument("--jar", type=Path, required=True); p.add_argument("--output", type=Path, required=True)
    p.add_argument("--docs-root", type=Path)
    a = p.parse_args()
    try:
        result = qualify(a.root, a.stage, a.source, a.tree, a.jar, a.docs_root); code = 0
    except (EvidenceError, OSError, ValueError, KeyError, ET.ParseError) as failure:
        result = {"gate_status": "BLOCKED_DEPENDENCY" if isinstance(failure, FileNotFoundError) or "missing measured" in str(failure) else "FAIL",
                  "stage": a.stage, "source_sha": a.source, "tree_sha": a.tree, "error": str(failure),
                  "native_executed": 0, "client_executed": 0, "workflow_run": None, "attempt": None}; code = 2
    write(a.output, result); print(json.dumps(result, sort_keys=True)); raise SystemExit(code)
