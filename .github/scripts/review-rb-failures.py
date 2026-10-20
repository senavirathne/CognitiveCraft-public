#!/usr/bin/env python3
"""Inspect retained qualification failures and exact binary evidence; no gameplay/inference."""
import importlib.util
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile
spec=importlib.util.spec_from_file_location("review",Path(__file__).with_name("review-rb-assessment.py"))
review=importlib.util.module_from_spec(spec);spec.loader.exec_module(review)
NATIVE=(12285570241,5076190,"844647536956e4848f133e5ccc43b75baff5a56e262fc382ebb682a6787cfafc")
CLIENT=(12285257424,120888034,"99d50fe1f8d801d43ea6a5824202501d24a205a5e86954d85e7534c6d925a2db")
BASELINE=(12284533372,113183,"5b96b3dbc697ceebd36635599a23c5e6c038b89ab9173cda222713f83c09eb4c")
ROOT="49246600-c1c7-4597-a41d-e2a616d6d724"
CHILD="6d1cf16f-28bf-30db-bfbd-ecddb7843820"
HEAD="e416a15f932593be68b704bb27c227171dbe6562"
TREE="491ecefd02a5718e877a8a99fbdc2e5946ed27ca"

def matches(value,found):
    if isinstance(value,dict):
        if value.get("id") in (ROOT,CHILD):
            found.append(value)
        for v in value.values():
            matches(v,found)
    elif isinstance(value,list):
        for v in value:matches(v,found)
    elif isinstance(value,str) and (ROOT in value or CHILD in value) and value.startswith(("{","[")):
        try:matches(json.loads(value),found)
        except json.JSONDecodeError:pass

def slim(state):
    if not isinstance(state,dict):return state
    return {"originalCanonicalSha256":review.object_sha(state),
        **{k:state[k] for k in ("time","gameTick","tick","actors","containers","drops","primitiveDiagnostics") if k in state},
        "players":{p:{k:v[k] for k in ("uuid","pos","online","assessmentInventory") if k in v}
            for p,v in state.get("knownPlayers",{}).items()},
        "blocks":state.get("blocks",{})}

def main():
    result={"schema":1,"qualificationHead":HEAD,"qualificationTree":TREE,
        "primary45CaseMeasurementsUnchanged":True,"scope":"Original first-attempt facility failures and independent current-head baseline identity review",
        "native":{"run":39462203454,"job":118764281452,"artifact":NATIVE[0],"sha256":NATIVE[2],"memberEvidence":[]},
        "client":{"run":39462203454,"job":118764282037,"artifact":CLIENT[0],"sha256":CLIENT[2]},
        "baseline":{"run":39462203414,"job":118780200485,"artifact":BASELINE[0],"sha256":BASELINE[2]}}
    path=review.download("native-failure",NATIVE)
    with zipfile.ZipFile(path) as archive:
        names=archive.namelist()
        jars=[n for n in names if n.endswith("fabric/build/libs/ai-villages-0.1.0.jar")]
        review.require(len(jars)==1 and review.sha(archive.read(jars[0]))==review.JAR,"Actual current-head full production JAR differs")
        result["actualFullProductionJarSha256"]=review.sha(archive.read(jars[0]))
        result["native"]["relevantMembers"]=[n for n in names if ("rb006b-native-owner" in n or "rb006b-native-marker" in n) and not n.endswith("/")]
        for info in archive.infolist():
            if info.file_size>15_000_000 or not info.filename.endswith((".json",".jsonl",".xml")):
                continue
            raw=archive.read(info);text=raw.decode(errors="strict")
            if info.filename.endswith(".xml") and ROOT in text:
                root=ET.fromstring(text)
                result["native"]["failedJUnit"]=[{"name":c.get("name"),"class":c.get("classname"),
                    "failures":[{"message":f.get("message"),"body":f.text} for f in c.findall("failure")]}
                    for c in root.iter("testcase") if c.find("failure") is not None]
            if (ROOT not in text and CHILD not in text) or info.filename.endswith(".xml"):
                continue
            values=[json.loads(line) for line in text.splitlines() if line.strip()] if info.filename.endswith(".jsonl") else [json.loads(text)]
            found=[]
            for value in values:matches(value,found)
            result["native"]["memberEvidence"].append({"member":info.filename,"sha256":review.sha(raw),
                "matchingJobObjects":review.details(found[-8:])})
    path.unlink()
    path=review.download("client-failure",CLIENT)
    with zipfile.ZipFile(path) as archive:
        filename=review.member(archive,"/results.json");raw=archive.read(filename);data=json.loads(raw);root=filename.rsplit("/",1)[0]
        result["client"]["originalResultsSha256"]=review.sha(raw)
        result["client"]["results"]=review.details(data)
        events=list(review.lines(archive,review.member(archive,"/supervisor.jsonl")))
        result["client"]["lastCommands"]=[e for e in events if e["kind"]=="command"][-30:]
        result["client"]["lastEventKinds"]=[{"time":e.get("time"),"kind":e.get("kind")} for e in events[-30:]]
        snapshot=review.member(archive,"/server/snapshot.json");state=json.loads(archive.read(snapshot))
        result["client"]["lastServerSnapshot"]=slim(state)
        captures=data.get("results",[])
        allcaptures=[c for row in captures for c in row.get("screenshots",[])]
        if allcaptures:
            capture=allcaptures[-1];image=archive.read(root+"/"+capture["path"])
            review.require(review.sha(image)==capture["sha256"],"Failed original native frame differs")
            frame="docs/e2e/assets/rb-generalization/qualification-facility-first-failure.png"
            result["client"]["retainedFailureFrame"]={**capture,"retainedPath":frame}
            review.emit_file(frame,image,"base64")
    path.unlink()
    path=review.download("current-baseline",BASELINE)
    with zipfile.ZipFile(path) as archive:
        filename=review.member(archive,"qualification.json");data=json.loads(archive.read(filename))
        review.require(data["status"]=="PASS" and data["consistentExactBinary"] and data["ownerSourceConsistency"],"Original baseline did not qualify")
        review.require(len(data["families"])==13 and all(r["status"]=="PASS" for r in data["families"].values()),"Original 13-family campaign incomplete")
        review.require(data["identities"] and all(v["sourceCommit"]==HEAD and v["sourceTree"]==TREE and
            v["releaseSha256"]==review.JAR and v["dirty"] is False for v in data["identities"]),"Actual qualification source/tree/full-JAR mismatch")
        result["baseline"]["qualification"]=review.details(data)
    path.unlink()
    review.emit_file("evidence/rb-generalization-ci-failure-review.json",review.canonical(result).decode()+"\n")
    print("CI_FAILURE_RESULT "+json.dumps({"actualFullProductionJarSha256":result["actualFullProductionJarSha256"],
        "baselineStatus":"PASS","nativeMatchingJournalMembers":len(result["native"]["memberEvidence"]),
        "clientLastCommand":result["client"]["lastCommands"][-1] if result["client"]["lastCommands"] else None,
        "clientResults":[{"id":r.get("id"),"status":r.get("status"),"reason":r.get("reason")} for r in result["client"]["results"].get("results",[])]},
        separators=(",",":")),flush=True)
if __name__=="__main__":main()
