#!/usr/bin/env python3
"""Task 3: frozen A02 plus independent oak arrangements and the original exact birch regression."""
from __future__ import annotations

import argparse
import hashlib
import json
import time
import traceback
from pathlib import Path

from suite import ROOT, DENIED_PATTERN, atomic, read, require
from generalization_support import AssessmentHarness, at, coord, LOG, BIRCH, DST
from generalization import acquisition, cases, receipts

CASES = ("original-A02", "oak-spread-barrel", "oak-chest-corner", "original-A15-birch")


def execute(h, ident, corpus):
    if ident == "original-A02":
        return cases(h, "A02", next(r for r in corpus["scenarios"] if r["id"] == "A02"))
    if ident == "oak-spread-barrel":
        return acquisition(h, 5, [at(x, z) for x, z in ((6, 3), (8, 3), (10, 3), (7, 7), (9, 7))], container="barrel")
    if ident == "oak-chest-corner":
        return acquisition(h, 3, [at(9, z) for z in (4, 5, 6)])
    if ident == "original-A15-birch":
        return cases(h, "A15", next(r for r in corpus["scenarios"] if r["id"] == "A15"))
    raise AssertionError("Unknown Task 3 case "+ident)


def verify_lineage(h, evidence):
    view = evidence["view"]
    rows = view.get("resourceReceipts", [])
    context = view["responsibility"]["request"]["context"]
    require(all(r["origin"] == context and r["runId"] == evidence["run"] and
                r["actor"]["entityId"] == h.entities["PlayerA"] for r in rows), "Receipt owner/run/actor mismatch")
    if evidence.get("expectedSafeBoundary"):
        return
    batches = {}
    for r in rows:
        batches.setdefault(r["batchId"], []).append(r)
    require(len(batches) == len(evidence["positions"]), "Harvest batches do not match physical blocks")
    for batch in batches.values():
        require(sorted(r["stage"] for r in batch) == ["DEPOSIT", "HARVEST", "PICKUP"] and
                all(r["quantity"] == 1 for r in batch) and
                all(r["dropIds"] == batch[0]["dropIds"] and r["sourceBlock"] == batch[0]["sourceBlock"] for r in batch),
                "Physical block/drop/receipt lineage mismatch")
    ids = {i for r in rows for i in r["dropIds"]}
    state = h.snapshot()
    remaining = [d for d in state["drops"] if d.get("uuid", d.get("id")) in ids]
    require(not remaining, "An attributable produced drop remains")
    require(h.inventory() == evidence["inventoryBefore"], "Acquisition changed pre-existing actor custody")
    evidence["verifiedLineage"] = {"batches": len(batches), "ownedDropIds": sorted(ids),
                                    "remainingAttributableDrops": remaining, "origin": context}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--original-only", action="store_true")
    args = parser.parse_args(); args.root.mkdir(parents=True, exist_ok=True)
    corpus = read(ROOT / "e2e/generalization/cases.json")
    selected = CASES[:1] if args.original_only else CASES
    result = {"task": "3/A02", "status": "NOT_EXECUTED", "phase": "CORRECTED_VERIFICATION",
              "originalCorpusSha256": hashlib.sha256((ROOT / "e2e/generalization/cases.json").read_bytes()).hexdigest(),
              "frozenDriverSha256": hashlib.sha256((ROOT / "e2e/generalization.py").read_bytes()).hexdigest(),
              "cases": [{"id": ident, "status": "NOT_EXECUTED"} for ident in selected]}
    h = None
    try:
        h = AssessmentHarness(args.root, "dropped-log-task3")
        h.bootstrap([LOG] if args.original_only else [LOG, BIRCH])
        for i, ident in enumerate(selected):
            h.family = ident; started = time.time(); first_frame = len(h.screenshots)
            record = result["cases"][i]
            try:
                evidence = execute(h, ident, corpus)
                verify_lineage(h, evidence)
                if ident == "original-A02":
                    rid = evidence["run"]
                    evidence["foreignDenied"] = h.command("PlayerB", "/aivillage kernel status "+rid, DENIED_PATTERN)
                    h.command("PlayerB", "/aivillage kernel cancel "+rid, DENIED_PATTERN)
                    require(rid not in h.suggestions("PlayerB", "/aivillage kernel status "), "Foreign run suggestion leak")
                clients = {p: h.client_snapshot(p) for p in ("PlayerA", "PlayerB")}
                require(all(s and s.get("play") and not s.get("singleplayer") for s in clients.values()),
                        "Both genuine multiplayer clients must remain connected")
                record.update(status="PASS", evidence=evidence, connectedClientObservations=clients)
                h.screenshot("PlayerA", "dropped-log-result", chat=True)
            except Exception as error:
                record.update(status="FAIL", reason=str(error), trace=traceback.format_exc())
                record["physicalFailureState"] = h.snapshot()
                record["connectedClientObservations"] = {p: h.client_snapshot(p) for p in ("PlayerA", "PlayerB")}
                h.screenshot("PlayerA", "dropped-log-failure", chat=True)
                raise
            finally:
                record.update(durationSeconds=time.time()-started, screenshots=h.screenshots[first_frame:])
                atomic(args.root / "dropped-log-results.json", result)
                print("TASK3_CASE", ident, record["status"], flush=True)
            h.cleanup()
        result["status"] = "PASS"
    except Exception as error:
        result.update(status="FAIL", failure=str(error), trace=traceback.format_exc())
        print("TASK3_FAILURE", str(error), flush=True)
    finally:
        if h:
            result.update(independentAdmissions=h.admissions, admittedArtifacts=h.hashes,
                          metadata=read(args.root / "metadata.json"))
            h.finish()
        atomic(args.root / "dropped-log-results.json", result)
    raise SystemExit(0 if result["status"] == "PASS" else 1)


if __name__ == "__main__":
    main()
