#!/usr/bin/env python3
"""Task 2 only: invoke frozen A01 unchanged, then independent physical regressions."""
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

CASES = ("original-A01", "elevated-oak-pair", "four-high-with-step", "two-oak-columns",
         "flat-oak", "original-A15-birch", "elevated-birch-pair", "unreachable-height", "blocked-standing")


def boundary(h, collision=False):
    h.reset({}, table=False)
    target = at(11, 6, 1 if collision else 3)
    h.logs([target])
    if collision:
        h.console(["setblock "+coord([target[0]+dx, 208+dy, target[2]+dz])+" minecraft:stone"
                   for dx, dz in ((-1, 0), (1, 0), (0, -1), (0, 1)) for dy in range(5)])
    before = h.snapshot(); calls = h.model_calls(); inv = h.inventory()
    rid = h.submit(LOG, 1, minimum=target, maximum=target)
    view = h.finish_run(rid, success=False)
    require(view["marker"]["outcome"] == "BLOCKED" and
            view["marker"]["reason"] == "TARGET_UNAVAILABLE", "Unreachable target did not block physically")
    require(not view.get("resourceReceipts") and view["marker"]["effects"] == 0,
            "Unreachable target fabricated progress")
    require(LOG in h.block(target) and h.container(DST, LOG) == 0 and h.inventory() == inv,
            "Blocked approach changed a block or physical custody")
    require(h.model_calls() == calls, "Blocked known execution invoked inference")
    return {"run": rid, "view": view, "target": target, "before": before,
            "physicalAfter": h.snapshot(), "modelCallsBefore": calls, "modelCallsAfter": h.model_calls(),
            "expectedSafeBoundary": True, "collision": collision}


def execute(h, ident, corpus):
    if ident == "original-A01":
        return cases(h, "A01", next(r for r in corpus["scenarios"] if r["id"] == "A01"))
    if ident == "elevated-oak-pair":
        return acquisition(h, 2, [at(11, 4, dy) for dy in (1, 2)])
    if ident == "four-high-with-step":
        h.reset({}, table=False)
        h.console(["setblock "+coord(at(7, z))+" minecraft:stone" for z in (6, 7)])
        return acquisition(h, 4, [at(8, 6, dy) for dy in range(4)], reset=False)
    if ident == "two-oak-columns":
        return acquisition(h, 4, [at(x, 9, dy) for x in (8, 12) for dy in (0, 1)])
    if ident == "flat-oak":
        return acquisition(h, 3, [at(x, 3) for x in (6, 8, 10)])
    if ident == "original-A15-birch":
        return cases(h, "A15", next(r for r in corpus["scenarios"] if r["id"] == "A15"))
    if ident == "elevated-birch-pair":
        return acquisition(h, 2, [at(12, 5, dy) for dy in (1, 2)], item=BIRCH)
    return boundary(h, collision=ident == "blocked-standing")


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
    result = {"task": "2", "status": "NOT_EXECUTED", "nextTask": "3/A02 NOT_STARTED",
              "originalCorpusSha256": hashlib.sha256((ROOT / "e2e/generalization/cases.json").read_bytes()).hexdigest(),
              "frozenDriverSha256": hashlib.sha256((ROOT / "e2e/generalization.py").read_bytes()).hexdigest(),
              "cases": [{"id": ident, "status": "NOT_EXECUTED"} for ident in selected]}
    h = None
    try:
        h = AssessmentHarness(args.root, "vertical-task2")
        area = read(args.root / "observation-area.json")
        area["maxObservationDy"] = 4
        atomic(args.root / "observation-area.json", area)
        metadata = read(args.root / "metadata.json")
        metadata["assessment"]["observerMaxDy"] = 4
        atomic(args.root / "metadata.json", metadata)
        h.bootstrap([LOG] if args.original_only else [LOG, BIRCH])
        for i, ident in enumerate(selected):
            h.family = ident; started = time.time(); first_frame = len(h.screenshots)
            record = result["cases"][i]
            try:
                evidence = execute(h, ident, corpus)
                verify_lineage(h, evidence)
                if ident == "original-A01":
                    rid = evidence["run"]
                    evidence["foreignDenied"] = h.command("PlayerB", "/aivillage kernel status "+rid, DENIED_PATTERN)
                    h.command("PlayerB", "/aivillage kernel cancel "+rid, DENIED_PATTERN)
                    require(rid not in h.suggestions("PlayerB", "/aivillage kernel status "), "Foreign run suggestion leak")
                clients = {p: h.client_snapshot(p) for p in ("PlayerA", "PlayerB")}
                require(all(s and s.get("play") and not s.get("singleplayer") for s in clients.values()),
                        "Both genuine multiplayer clients must remain connected")
                record.update(status="PASS", evidence=evidence, connectedClientObservations=clients)
                h.screenshot("PlayerA", "vertical-result", chat=True)
            except Exception as error:
                record.update(status="FAIL", reason=str(error), trace=traceback.format_exc())
                record["physicalFailureState"] = h.snapshot()
                record["connectedClientObservations"] = {p: h.client_snapshot(p) for p in ("PlayerA", "PlayerB")}
                h.screenshot("PlayerA", "vertical-failure", chat=True)
                raise
            finally:
                record.update(durationSeconds=time.time()-started, screenshots=h.screenshots[first_frame:])
                atomic(args.root / "vertical-results.json", result)
                print("TASK2_CASE", ident, record["status"], flush=True)
            h.cleanup()
        result["status"] = "PASS"
    except Exception as error:
        result.update(status="FAIL", failure=str(error), trace=traceback.format_exc())
        print("TASK2_FAILURE", str(error), flush=True)
    finally:
        if h:
            result.update(independentAdmissions=h.admissions, admittedArtifacts=h.hashes,
                          metadata=read(args.root / "metadata.json"))
            h.finish()
        atomic(args.root / "vertical-results.json", result)
    raise SystemExit(0 if result["status"] == "PASS" else 1)


if __name__ == "__main__":
    main()
