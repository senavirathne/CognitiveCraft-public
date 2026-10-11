#!/usr/bin/env python3
"""Bounded text diagnostics usable when an Actions artifact is not downloadable."""
from pathlib import Path
import json
import sys

for argument in sys.argv[1:]:
    root = Path(argument)
    if (root / "results.json").exists():
        for case in json.loads((root / "results.json").read_text()).get("results",[]):
            print("CASE",case["id"],case["status"],case.get("reason",""))
    for pattern in ("results.json", "metadata.json", "**/stdout.log", "*-launcher.log", "**/response.json", "server/snapshot.json"):
        for path in root.glob(pattern):
            print("\nDIAGNOSTIC", path)
            lines = path.read_text(errors="replace").splitlines()
            print("\n".join(line[:2000] for line in lines[-110:]))
    for path in root.glob("clients/*/events.jsonl"):
        print("\nCLIENT EVENTS", path)
        print("\n".join(line[:2000] for line in path.read_text(errors="replace").splitlines()[-15:]))
    for path in root.glob("cases/*/failure.json"):
        data = json.loads(path.read_text())
        state = data.get("snapshot") or {}
        print("\nFAILED FAMILY",data["family"],data["reason"])
        print("TRACE",data.get("trace",""))
        print("COUNTERS", {k:state.get(k) for k in ("tick","arenaLoaded","arenaTicking","needleCalls","generationCalls")})
        print("ACTOR INVENTORIES",[(a["uuid"],a["inventory"],a["pos"]) for a in state.get("actors",[])])
        print("LOOSE DROPS",state.get("drops",[]))
        print("FAILED CLIENT RESPONSES",data.get("clients",{}))
        for player,records in state.get("knownPlayers",{}).items():
            print("CITIZENS",player,records.get("citizens"),"online="+str(records.get("online")))
            for identifier,view in records.get("runs",{}).items():
                print("RUN",player,identifier,view.get("description",view.get("phase")),
                      "usage=" + str(view.get("responsibility",{}).get("usage")),
                      "receipts=" + str(len(view.get("receipts",[]))),
                      "quantities=" + str({stage:sum(r.get("wheat",0) for r in view.get("receipts",[]) if r.get("stage")==stage)
                                            for stage in ("HARVEST","PICKUP","DEPOSIT")}))
            for identifier,job in records.get("jobs",{}).items():
                print("JOB",player,identifier,job["state"],job.get("reason"),job.get("fulfilled"))
                print("JOB ATTEMPTS",json.dumps(job.get("attempts",[]),separators=(",",":"))[:18000])
