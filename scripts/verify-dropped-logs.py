#!/usr/bin/env python3
"""Exact Task 3 native pickup and original A02 gates."""
import argparse
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/dropped-log-evidence"
NATIVE = {"settled_floor_drop_uses_actual_entity_reach", "drop_beside_chest_retains_clear_physical_ray",
          "moving_drop_retains_uuid_across_cells", "enclosed_drop_cannot_be_collected",
          "full_inventory_cannot_consume_drop", "removed_drop_cannot_be_collected_after_navigation_starts",
          "native_pickup_delay_preserves_actual_drop"}


def sha(p):
    with p.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def native(regressions=False):
    folder = ROOT / "fabric/build/gametest-run"
    report, log = folder / "gametest-results.xml", folder / "logs/latest.log"
    cases = [c for c in ET.parse(report).getroot().iter("testcase") if c.get("name", "").startswith("ai_villages_tests:")]
    expected = {"ai_villages_tests:dropped_log_game_tests_"+n for n in NATIVE}
    if regressions:
        expected = {"ai_villages_tests:oak_log_effect_game_tests_actual_bare_hand_timing_loot_and_component_preserving_custody",
                    "ai_villages_tests:birch_log_effect_game_tests_exact_birch_loot_timing_components_and_foreign_species"}
    names = [c.get("name") for c in cases]
    assert len(names) == len(expected) and set(names) == expected, names
    assert not any(c.find(t) is not None for c in cases for t in ("failure", "error", "skipped"))
    text = log.read_text()
    markers = {"RB001_EFFECTS": 1, "RB007_LOG_EFFECTS": 1} if regressions else {"A02_NATIVE_PASS": 4, "A02_NATIVE_BOUNDARY": 3}
    for marker, count in markers.items():
        assert sum(marker in line for line in text.splitlines()) == count, marker
    phase = "existing-oak-birch" if regressions else "dropped-logs"
    OUT.mkdir(parents=True, exist_ok=True)
    for p in (report, log):
        shutil.copy2(p, OUT / (phase+"-"+p.name))
    (OUT / (phase+".json")).write_text(json.dumps({"status": "PASS", "caseNames": names,
        "xmlSha256": sha(report), "logSha256": sha(log)}, indent=2)+"\n")
    print("TASK3_NATIVE_PASS", phase, len(names))


def client(original_only=False):
    data = json.loads((ROOT / "build/e2e/dropped-logs/dropped-log-results.json").read_text())
    assert data["task"] == "3/A02" and data["status"] == "PASS"
    assert [r["id"] for r in data["cases"]] == ["original-A02"]
    assert all(r["status"] == "PASS" for r in data["cases"])
    assert data["originalCorpusSha256"] == "ee2e323d2c329a4f6040c3b96ffe880a12a3d33a3b8954a7174dc32766eb8bc5"
    metadata = data["metadata"]
    identity = json.loads((ROOT / "build/rb001-evidence/identity.json").read_text())
    assert metadata["sourceCommit"] == identity["source"] and metadata["sourceTree"] == identity["tree"]
    assert metadata["releaseSha256"] == identity["jarSha256"] == sha(ROOT / "fabric/build/libs/ai-villages-0.1.0.jar")
    assert not metadata["dirty"]
    assert len(data["independentAdmissions"]) == 1 and all(r["status"] == "PASS" for r in data["independentAdmissions"])
    OUT.mkdir(parents=True, exist_ok=True)
    print("TASK3_CLIENT_PASS", metadata["sourceCommit"], metadata["releaseSha256"], 1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("native", "existing", "client"))
    parser.add_argument("--original-only", action="store_true")
    args = parser.parse_args()
    client(args.original_only) if args.mode == "client" else native(args.mode == "existing")
