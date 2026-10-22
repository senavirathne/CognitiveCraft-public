#!/usr/bin/env python3
"""Byte-pinned read-only review of Task 3 campaigns; never starts Minecraft or inference."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

API="https://api.github.com/repos/senavirathne/CognitiveCraft-public"
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl): return None

def request(url):
    return urllib.request.Request(url,headers={"Authorization":"Bearer "+os.environ["GH_TOKEN"],
        "Accept":"application/vnd.github+json"})
def metadata(artifact_id):
    with urllib.request.urlopen(request(API+f"/actions/artifacts/{artifact_id}"),timeout=60) as stream:
        return json.load(stream)
def download(meta,root):
    try: response=urllib.request.build_opener(NoRedirect).open(request(meta["archive_download_url"]),timeout=60)
    except urllib.error.HTTPError as error:
        if error.code!=302: raise
        response=urllib.request.urlopen(error.headers["Location"],timeout=60)
    path=root/(str(meta["id"])+".zip")
    with response,path.open("wb") as output:
        while block:=response.read(1024*1024):output.write(block)
    assert path.stat().st_size==meta["size_in_bytes"]
    assert "sha256:"+hashlib.file_digest(path.open("rb"),"sha256").hexdigest()==meta["digest"]
    return path
def read_json(z,suffix):
    matches=[p for p in z.namelist() if p.endswith(suffix)]
    assert len(matches)==1,(suffix,matches)
    return json.loads(z.read(matches[0]))
def review(meta,root):
    path=download(meta,root)
    out={"artifactId":meta["id"],"bytes":meta["size_in_bytes"],"digest":meta["digest"],
         "head":meta.get("workflow_run",{}).get("head_sha"),"name":meta["name"]}
    with zipfile.ZipFile(path) as z:
        paths=z.namelist()
        jars=[]
        for p in paths:
            if p.endswith("/ai-villages-0.1.0.jar"):
                raw=z.read(p);jars.append({"path":p,"bytes":len(raw),"sha256":hashlib.sha256(raw).hexdigest()})
        out["actualProductionCopies"]=jars
        if jars: assert len({j["sha256"] for j in jars})==1
        case_paths=[p for p in paths if p.endswith("dropped-log-results.json")]
        root_ids=set()
        if case_paths:
            data=json.loads(z.read(case_paths[0]));out["status"]=data["status"]
            out["metadata"]=data.get("metadata");out["failure"]=data.get("failure")
            if jars:assert data["metadata"]["releaseSha256"]==jars[0]["sha256"]
            out["admissions"]=[{"item":r["item"],"status":r["status"],"run":r.get("run"),
                "marker":r.get("view",{}).get("marker"),"callsBefore":r.get("callsBefore"),"callsAfter":r.get("callsAfter")}
                for r in data["independentAdmissions"]]
            out["cases"]=[]
            for case in data["cases"]:
                row={"id":case["id"],"status":case["status"]}
                e=case.get("evidence")
                view=e.get("view") if e else None
                if view is None and case.get("reason","").startswith("Physical execution did not succeed: "):
                    view=json.loads(case["reason"].split(": ",1)[1])
                if view:
                    root_ids.add(view["id"])
                    row.update(run=view["id"],marker=view["marker"],usage=view["responsibility"]["usage"],
                        job=view["responsibility"]["id"],
                        totals={s:sum(r["quantity"] for r in view["resourceReceipts"] if r["stage"]==s)
                                for s in ("HARVEST","PICKUP","DEPOSIT")},
                        receipts=view["resourceReceipts"])
                after=e["physicalAfter"] if e else case.get("physicalFailureState")
                if after:
                    row["physical"]={k:after.get(k) for k in ("gameTick","time","actors","containers","drops")}
                    row["physical"]["players"]={p:{k:v.get(k) for k in ("uuid","pos","assessmentInventory")}
                                                for p,v in after["players"].items()}
                row["frames"]=[]
                for frame in case.get("screenshots",[]):
                    candidates=[p for p in paths if p.endswith("/"+frame["path"]) or p==frame["path"]]
                    assert len(candidates)==1
                    raw=z.read(candidates[0]);assert hashlib.sha256(raw).hexdigest()==frame["sha256"]
                    assert len(raw)==frame["bytes"] and struct.unpack(">II",raw[16:24])==(800,600)
                    row["frames"].append({k:frame[k] for k in ("path","sha256","bytes","checkpoint","family")})
                    if case["id"]=="original-A02" and frame["checkpoint"]=="synchronized-container-7":
                        print("TASK3_NATIVE_FRAME "+json.dumps({"frame":row["frames"][-1],"base64":base64.b64encode(raw).decode()}),flush=True)
                out["cases"].append(row)
        out["leaseFiles"]=[]
        for p in paths:
            if "/data/cognitivecraft/leases/v1/" in p and p.endswith("/state.jsonl"):
                raw=z.read(p);lines=raw.splitlines(keepends=True);header=json.loads(lines[0])
                assert hashlib.sha256(b"".join(lines[1:])).hexdigest()==header["sha256"]
                jobs={c["job"] for c in out.get("cases",[]) if "job" in c}
                own=[json.loads(line) for line in lines[1:] if json.loads(line)["job"] in jobs]
                out["leaseFiles"].append({"path":p,"sha256":hashlib.sha256(raw).hexdigest(),"rows":own})
        out["diagnostics"]=[]
        for p in paths:
            if p.endswith("server/stdout.log"):
                for number,line in enumerate(z.read(p).decode("utf-8",errors="replace").splitlines(),1):
                    if ("LEASE_GATE" in line or "LOG_PICKUP" in line) and any(r in line for r in root_ids):
                        out["diagnostics"].append({"path":p,"line":number,"text":line})
        junit=[]
        for p in paths:
            if "/test-results/test/" in p and p.endswith(".xml"):
                tree=ET.fromstring(z.read(p))
                for i,c in enumerate(tree.iter("testcase")):
                    failed=any(c.find(t) is not None for t in ("failure","error","skipped"))
                    junit.append((p,i,failed))
                    if failed:
                        out.setdefault("moduleFailures",[]).append({"path":p,"name":c.get("name"),
                            "details":"".join(c.itertext())[:4000]})
                if "LeasedDropContinuityTest" in p:
                    out["leaseTestSystemOut"]="".join(tree.itertext())[-18000:]
        if junit:out["moduleCases"]={"total":len(junit),"nonpassing":sum(c[2] for c in junit)}
        phases=[]
        for suffix in ("dropped-logs.json","existing-oak-birch.json"):
            matching=[p for p in paths if p.endswith("/"+suffix)]
            if matching:
                phase=json.loads(z.read(matching[0]));phases.append(phase)
        out["nativePhases"]=phases
    (root/(str(meta["id"])+"-review.json")).write_text(json.dumps(out,indent=2)+"\n")
    print("TASK3_ARCHIVE_REVIEW "+json.dumps(out,separators=(",",":")),flush=True)
    return out

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,required=True)
    parser.add_argument("--historical",action="store_true")
    args=parser.parse_args();args.root.mkdir(parents=True,exist_ok=True)
    if args.historical:
        for ident,size,digest in ((12410460341,89972218,"a5fc4f065708cdc700c8618791d11ca84a71c7d9b34efd47962cd286fdf45cff"),
                                  (12409634116,198746,"f2325a5cd533a7665c4c00762fc89fbea24cbb54f7e3eaf9c0f84c6a28a48a8c")):
            m=metadata(ident);assert m["size_in_bytes"]==size and m["digest"]=="sha256:"+digest
            review(m,args.root)
        with urllib.request.urlopen(request(API+"/actions/runs/39742726356/artifacts"),timeout=60) as stream:
            recorded=json.load(stream)["artifacts"]
        for m in recorded:
            if m["name"]=="dropped-logs-connected-39742726356-1":
                assert m["workflow_run"]["head_sha"]=="df6b1e9398252478870a1a560154b572ff8fe0d2"
                review(m,args.root)
        return
    with urllib.request.urlopen(request(API+"/actions/runs/"+os.environ["GITHUB_RUN_ID"]+"/artifacts"),timeout=60) as stream:
        artifacts=json.load(stream)["artifacts"]
    suffix="-"+os.environ["GITHUB_RUN_ID"]+"-"+os.environ["GITHUB_RUN_ATTEMPT"]
    selected=[a for a in artifacts if a["name"] in ("dropped-logs-native"+suffix,"dropped-logs-connected"+suffix)]
    assert len(selected)==2
    results=[review(m,args.root) for m in selected]
    assert all(r["head"]==os.environ["GITHUB_SHA"] for r in results)
    client=next(r for r in results if r.get("cases"))
    native=next(r for r in results if r.get("moduleCases"))
    assert client["status"]=="PASS" and all(c["status"]=="PASS" for c in client["cases"])
    assert native["moduleCases"]["nonpassing"]==0 and len(native["nativePhases"])==2
    assert sum(len(p["caseNames"]) for p in native["nativePhases"])==9
    assert len(client["actualProductionCopies"])==4 and len(native["actualProductionCopies"])==1
    assert {j["sha256"] for r in results for j in r["actualProductionCopies"]}=={client["metadata"]["releaseSha256"]}
    print("TASK3_COMPLETE_ARCHIVE_REVIEW_PASS",os.environ["GITHUB_SHA"],flush=True)

if __name__=="__main__":main()
