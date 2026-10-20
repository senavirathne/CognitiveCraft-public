#!/usr/bin/env python3
"""Inspect pinned post-merge client failure; read original observations only."""
import importlib.util
import json
from pathlib import Path
import zipfile
spec=importlib.util.spec_from_file_location("review",Path(__file__).with_name("review-rb-assessment.py"))
review=importlib.util.module_from_spec(spec);spec.loader.exec_module(review)
PIN=(12290415981,213881849,"dda20944be2901566b4a20206b10e77d72b9857308d5989a39627947ccf275c9")
HEAD="c30f60aed63269c1e8f40c6e085988560e5da693"
def main():
    result={"schema":1,"sourceHead":HEAD,"run":39474152370,"job":118805638846,
        "archive":{"id":PIN[0],"bytes":PIN[1],"sha256":PIN[2]},
        "scope":"Original post-merge timeout; does not replace primary assessment or passing final-head qualification"}
    path=review.download("postmerge-facility-original",PIN)
    with zipfile.ZipFile(path) as archive:
        name=review.member(archive,"/results.json");raw=archive.read(name);data=json.loads(raw)
        result["originalResultsMember"]=name;result["originalResultsSha256"]=review.sha(raw)
        result["results"]=review.details(data)
        events=list(review.lines(archive,review.member(archive,"/supervisor.jsonl")))
        result["lastCommands"]=[e for e in events if e.get("kind")=="command"][-55:]
        result["lastEvents"]=review.details(events[-12:])
        name=review.member(archive,"/server/snapshot.json");raw=archive.read(name);state=json.loads(raw)
        result["snapshotMember"]=name;result["snapshotSha256"]=review.sha(raw)
        result["physical"]=review.physical(state)
        result["blocks"]=state.get("blocks",{})
        result["knownPlayers"]={p:{k:v for k,v in row.items() if k not in ("commandTree","standable")}
            for p,row in state.get("knownPlayers",{}).items()}
        result["playerJobStates"]={p:[{k:j.get(k) for k in ("id","state","reason","fulfilled","request","children","dependencies","attempts","preparation")}
            for j in row.get("jobs",{}).values()] for p,row in state.get("knownPlayers",{}).items()}
        result["actorAvailability"]=state.get("actors",[])
        result["snapshotKeys"]=list(state)
        result["journalMembers"]=[{"name":info.filename,"bytes":info.file_size} for info in archive.infolist()
            if info.filename.endswith((".json",".jsonl")) and any(s in info.filename for s in ("job","responsibil","citizen","dispatch"))]
        for suffix in ("/server/logs/latest.log","/server/stdout.log"):
            names=[n for n in archive.namelist() if n.endswith(suffix)]
            if len(names)==1:
                raw=archive.read(names[0])
                result.setdefault("serverLogTails",[]).append({"member":names[0],"sha256":review.sha(raw),
                    "lastLines":raw.decode(errors="replace").splitlines()[-100:]})
    path.unlink()
    review.emit_file("evidence/rb-generalization-postmerge-facility-failure.json",review.canonical(result).decode()+"\n")
    print("POSTMERGE_FAILURE_REVIEW "+json.dumps({"run":result["run"],"job":result["job"],
        "results":[{"id":r.get("id"),"status":r.get("status"),"reason":r.get("reason")} for r in data.get("results",[])],
        "jobs":{p:[{"id":j["id"],"state":j["state"],"reason":j["reason"]} for j in rows]
            for p,rows in result["playerJobStates"].items()}},separators=(",",":")),flush=True)
if __name__=="__main__":main()
