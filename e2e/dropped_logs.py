#!/usr/bin/env python3
"""Task 3: execute frozen A02 unchanged with genuine clients and stopped inference."""
from __future__ import annotations
import argparse
import hashlib
import time
import traceback
from pathlib import Path
from suite import ROOT, DENIED_PATTERN, atomic, read, require
from generalization_support import AssessmentHarness, LOG
from generalization import cases
from vertical_logs import verify_lineage


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args(); args.root.mkdir(parents=True, exist_ok=True)
    corpus = read(ROOT / "e2e/generalization/cases.json")
    result = {"task": "3/A02", "status": "NOT_EXECUTED", "phase": "DIAGNOSTIC_REPRODUCTION",
              "originalCorpusSha256": hashlib.sha256((ROOT / "e2e/generalization/cases.json").read_bytes()).hexdigest(),
              "frozenDriverSha256": hashlib.sha256((ROOT / "e2e/generalization.py").read_bytes()).hexdigest(),
              "cases": [{"id": "original-A02", "status": "NOT_EXECUTED"}]}
    h = None
    try:
        h = AssessmentHarness(args.root, "dropped-log-task3")
        h.bootstrap([LOG])
        h.family = "original-A02"; started = time.time(); first_frame = len(h.screenshots)
        record = result["cases"][0]
        try:
            evidence = cases(h, "A02", next(r for r in corpus["scenarios"] if r["id"] == "A02"))
            verify_lineage(h, evidence)
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
            print("TASK3_CASE original-A02 "+record["status"], flush=True)
        result["status"] = "PASS"
    except Exception as error:
        result.update(status="FAIL", failure=str(error), trace=traceback.format_exc())
        print("TASK3_FAILURE "+str(error), flush=True)
    finally:
        if h:
            result.update(independentAdmissions=h.admissions, admittedArtifacts=h.hashes,
                          metadata=read(args.root / "metadata.json"))
            h.finish()
        atomic(args.root / "dropped-log-results.json", result)
    raise SystemExit(0 if result["status"] == "PASS" else 1)


if __name__ == "__main__":
    main()
