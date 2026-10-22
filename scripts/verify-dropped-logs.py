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
    root = ROOT / "build/e2e/dropped-logs"
    data = json.loads((root / "dropped-log-results.json").read_text())
    import sys, struct
    sys.path.insert(0, str(ROOT / "e2e"))
    from dropped_logs import CASES
    expected = list(CASES[:1] if original_only else CASES)
    assert data["task"] == "3/A02" and data["status"] == "PASS"
    assert [r["id"] for r in data["cases"]] == expected
    assert all(r["status"] == "PASS" for r in data["cases"])
    assert data["originalCorpusSha256"] == "ee2e323d2c329a4f6040c3b96ffe880a12a3d33a3b8954a7174dc32766eb8bc5"
    assert data["frozenDriverSha256"] == sha(ROOT / "e2e/generalization.py")
    metadata = data["metadata"]
    identity = json.loads((ROOT / "build/rb001-evidence/identity.json").read_text())
    assert metadata["sourceCommit"] == identity["source"] and metadata["sourceTree"] == identity["tree"]
    assert metadata["releaseSha256"] == identity["jarSha256"] == sha(ROOT / "fabric/build/libs/ai-villages-0.1.0.jar")
    assert not metadata["dirty"]
    assert len(data["independentAdmissions"]) == (1 if original_only else 2)
    assert all(r["status"] == "PASS" for r in data["independentAdmissions"])
    jars = []
    for path in sorted(root.rglob("ai-villages-0.1.0.jar")):
        digest = sha(path)
        assert digest == identity["jarSha256"]
        jars.append({"path": str(path.relative_to(root)), "bytes": path.stat().st_size, "sha256": digest})
    assert len(jars) == 3
    summary = {"status": "PASS", "source": identity["source"], "tree": identity["tree"],
               "jarSha256": identity["jarSha256"], "actualRuntimeCopies": jars, "cases": [], "frames": []}
    for case in data["cases"]:
        e = case["evidence"]; rows = e["view"]["resourceReceipts"]; q = len(e["positions"])
        assert {s: sum(r["quantity"] for r in rows if r["stage"] == s) for s in ("HARVEST", "PICKUP", "DEPOSIT")} == {s: q for s in ("HARVEST", "PICKUP", "DEPOSIT")}
        assert len(rows) == 3*q and len({r["receiptId"] for r in rows}) == len(rows)
        assert e["clientMenuCount"] == q and e["inventoryAfter"] == e["inventoryBefore"]
        assert e["modelCallsBefore"] == e["modelCallsAfter"]
        assert e["verifiedLineage"]["batches"] == q and not e["verifiedLineage"]["remainingAttributableDrops"]
        before, after = e["before"], e["physicalAfter"]
        item = rows[0]["item"]["id"]
        def total(snapshot):
            result = sum(values.get(item, 0) for values in snapshot["containers"].values())
            result += sum(a["inventory"].get(item, 0) for a in snapshot["actors"])
            result += sum(p.get("assessmentInventory", {}).get(item, 0) for p in snapshot["players"].values())
            result += sum(d["count"] for d in snapshot["drops"] if d["item"] == item)
            result += sum("Block{"+item+"}" in block for block in snapshot["blocks"].values())
            return result
        assert total(before) == total(after), (case["id"], total(before), total(after))
        destination = ",".join(str(x) for x in e["destination"])
        assert after["containers"][destination].get(item, 0) == before["containers"].get(destination, {}).get(item, 0)+q
        assert all("minecraft:air" in after["blocks"][",".join(str(x) for x in pos)] for pos in e["positions"])
        assert {p: v.get("assessmentInventory", {}) for p, v in before["players"].items()} == {p: v.get("assessmentInventory", {}) for p, v in after["players"].items()}
        assert all(client.get("play") and not client.get("singleplayer") for client in case["connectedClientObservations"].values())
        summary["cases"].append({"id": case["id"], "run": e["run"], "quantity": q, "item": item,
                                 "physicalTotalBefore": total(before), "physicalTotalAfter": total(after),
                                 "beforeTick": before["gameTick"], "afterTick": after["gameTick"],
                                 "knownArtifact": e["view"]["marker"]["artifactSha256"], "remainingOwnedDrops": []})
        for frame in case["screenshots"]:
            path = root / frame["path"]; raw = path.read_bytes()
            assert hashlib.sha256(raw).hexdigest() == frame["sha256"] and len(raw) == frame["bytes"]
            assert raw[:8] == b"\x89PNG\r\n\x1a\n" and struct.unpack(">II", raw[16:24]) == (800, 600)
            assert frame["sourceCommit"] == identity["source"] and frame["releaseSha256"] == identity["jarSha256"]
            summary["frames"].append({k: frame[k] for k in ("path", "sha256", "bytes", "checkpoint", "family")})
    assert len(summary["frames"]) >= len(expected)*2
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "client-independent-review.json").write_text(json.dumps(summary, indent=2)+"\n")
    print("TASK3_CLIENT_PASS "+json.dumps(summary, separators=(",", ":")), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("native", "existing", "client"))
    parser.add_argument("--original-only", action="store_true")
    args = parser.parse_args()
    client(args.original_only) if args.mode == "client" else native(args.mode == "existing")
