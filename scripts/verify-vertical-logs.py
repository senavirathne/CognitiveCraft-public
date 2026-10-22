#!/usr/bin/env python3
"""Exact physical Task 2 gates; historical assessment files remain unchanged."""
import argparse
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/vertical-evidence"
NATIVE = {"three_high_oak_column_uses_grounded_reach", "suspended_oak_pair_uses_lower_standing_floor",
          "four_high_oak_column_requires_ordinary_step", "elevated_birch_pair_retains_exact_species",
          "too_high_log_cannot_invent_standing_support", "blocked_standing_columns_cannot_bypass_collision"}


def sha(p):
    with p.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def native(regressions=False):
    folder = ROOT / "fabric/build/gametest-run"
    report, log = folder / "gametest-results.xml", folder / "logs/latest.log"
    cases = [c for c in ET.parse(report).getroot().iter("testcase") if c.get("name", "").startswith("ai_villages_tests:")]
    expected = {"ai_villages_tests:vertical_log_game_tests_"+n for n in NATIVE}
    if regressions:
        expected = {"ai_villages_tests:oak_log_effect_game_tests_actual_bare_hand_timing_loot_and_component_preserving_custody",
                    "ai_villages_tests:birch_log_effect_game_tests_exact_birch_loot_timing_components_and_foreign_species"}
    names = [c.get("name") for c in cases]
    assert len(names) == len(expected) and set(names) == expected, names
    assert not any(c.find(t) is not None for c in cases for t in ("failure", "error", "skipped"))
    text = log.read_text()
    markers = {"RB001_EFFECTS": 1, "RB007_LOG_EFFECTS": 1} if regressions else {"A01_VERTICAL_NATIVE_PASS": 4, "A01_VERTICAL_BOUNDARY_PASS": 2}
    for marker, count in markers.items():
        assert sum(marker in line for line in text.splitlines()) == count, marker
    phase = "existing-oak-birch" if regressions else "vertical"
    OUT.mkdir(parents=True, exist_ok=True)
    for p in (report, log):
        shutil.copy2(p, OUT / (phase+"-"+p.name))
    (OUT / (phase+".json")).write_text(json.dumps({"status": "PASS", "caseNames": names,
        "xmlSha256": sha(report), "logSha256": sha(log)}, indent=2)+"\n")
    print("TASK2_NATIVE_PASS", phase, len(names))


def client(original_only=False):
    data = json.loads((ROOT / "build/e2e/vertical/vertical-results.json").read_text())
    import sys
    sys.path.insert(0, str(ROOT / "e2e"))
    from vertical_logs import CASES
    expected = CASES[:1] if original_only else CASES
    assert data["task"] == "2" and data["status"] == "PASS" and data["nextTask"] == "3/A02 NOT_STARTED"
    assert [r["id"] for r in data["cases"]] == list(expected)
    assert all(r["status"] == "PASS" for r in data["cases"])
    assert data["originalCorpusSha256"] == "ee2e323d2c329a4f6040c3b96ffe880a12a3d33a3b8954a7174dc32766eb8bc5"
    metadata = data["metadata"]
    identity = json.loads((ROOT / "build/rb001-evidence/identity.json").read_text())
    assert metadata["sourceCommit"] == identity["source"] and metadata["sourceTree"] == identity["tree"]
    assert metadata["releaseSha256"] == identity["jarSha256"] == sha(ROOT / "fabric/build/libs/ai-villages-0.1.0.jar")
    assert not metadata["dirty"]
    assert len(data["independentAdmissions"]) == (1 if original_only else 2)
    assert all(r["status"] == "PASS" for r in data["independentAdmissions"])
    OUT.mkdir(parents=True, exist_ok=True)
    print("TASK2_CLIENT_PASS", metadata["sourceCommit"], metadata["releaseSha256"], len(expected))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("native", "existing", "client"))
    parser.add_argument("--original-only", action="store_true")
    args = parser.parse_args()
    client(args.original_only) if args.mode == "client" else native(args.mode == "existing")
