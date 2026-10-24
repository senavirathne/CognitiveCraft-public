#!/usr/bin/env python3
"""Byte-pinned read-only review of Task 3 campaigns; never starts Minecraft or inference."""
import argparse
import importlib.util
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import re
import struct
import subprocess
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
def review(meta,root,emit_frame=False):
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
                    if emit_frame and case["id"]=="original-A02" and frame["checkpoint"]=="synchronized-container-7":
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
                phase=json.loads(z.read(matching[0]))
                prefix=matching[0].rsplit("/",1)[0]+"/"
                label=suffix[:-5]
                xml_path=prefix+label+"-gametest-results.xml";log_path=prefix+label+"-latest.log"
                assert hashlib.sha256(z.read(xml_path)).hexdigest()==phase["xmlSha256"]
                assert hashlib.sha256(z.read(log_path)).hexdigest()==phase["logSha256"]
                actual=[c for c in ET.fromstring(z.read(xml_path)).iter("testcase")
                        if c.get("name","").startswith("ai_villages_tests:")]
                assert [c.get("name") for c in actual]==phase["caseNames"]
                assert all(c.find(t) is None for c in actual for t in ("failure","error","skipped"))
                phase["actualXmlAndLogReverified"]=True
                phases.append(phase)
        out["nativePhases"]=phases
    (root/(str(meta["id"])+"-review.json")).write_text(json.dumps(out,indent=2)+"\n")
    print("TASK3_ARCHIVE_REVIEW "+json.dumps(out,separators=(",",":")),flush=True)
    return out

def current_artifacts(artifacts,run_id,attempt,head):
    """Keep successful producer jobs when Actions retries only failed jobs."""
    selected=[]
    for kind in ("native","connected"):
        prefix=f"dropped-logs-{kind}-{run_id}-"
        candidates=[]
        for artifact in artifacts:
            name=artifact["name"]
            if not name.startswith(prefix) or not name[len(prefix):].isdigit():continue
            number=int(name[len(prefix):])
            if number<1 or number>attempt or artifact.get("expired"):continue
            if artifact.get("workflow_run",{}).get("head_sha")!=head:continue
            candidates.append((number,artifact))
        assert candidates,(kind,run_id,head)
        latest=max(number for number,_ in candidates)
        matches=[artifact for number,artifact in candidates if number==latest]
        assert len(matches)==1,(kind,latest)
        selected.append(matches[0])
    return selected

def junit_inventory(path):
    suites={};nonpassing=0
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if "/test-results/test/" not in name or not name.endswith(".xml"):continue
            tree=ET.fromstring(archive.read(name));cases=list(tree.iter("testcase"))
            module=name.split("/",1)[0];key=module+":"+tree.get("name",Path(name).name)
            assert key not in suites,key
            suites[key]=len(cases)
            nonpassing+=sum(any(case.find(tag) is not None for tag in ("failure","error","skipped")) for case in cases)
    assert suites and {key.split(":",1)[0] for key in suites}=={"core","providers","fabric"}
    assert nonpassing==0,nonpassing
    return suites

def validate_qualified_source(record,manifest,head,root,paths,jar_sha,corpus_sha,historical_sha):
    """A verification-only descendant may reuse a qualified binary; changed production may not."""
    def git(*args):return subprocess.check_output(["git",*args],cwd=root,text=True).strip()
    assert record["profile"]=="all-published-branches-integration-v1"
    assert git("rev-parse","HEAD")==head
    assert not git("status","--porcelain","--untracked-files=normal")
    subprocess.run(["git","merge-base","--is-ancestor",record["sourceCommit"],head],cwd=root,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    assert git("rev-parse",record["sourceCommit"]+"^{tree}")==record["sourceTree"]
    assert record["requiredHeads"]==manifest["requiredHeads"] and record["startingMain"]==manifest["startingMain"]
    for ancestor in [manifest["startingMain"],*(row["sha"] for row in manifest["requiredHeads"])]:
        subprocess.run(["git","merge-base","--is-ancestor",ancestor,head],cwd=root,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    assert record["corpusSha256"]==corpus_sha
    assert record["historicalBaselineFileSha256"]==historical_sha
    assert record["releaseJarSha256"]==jar_sha
    assert record["productionFileCount"]==len(paths)
    assert [row["path"] for row in record["productionFiles"]]==sorted(paths)
    for row in record["productionFiles"]:
        assert hashlib.sha256((root/row["path"]).read_bytes()).hexdigest()==row["sha256"],row["path"]
        assert git("hash-object",row["path"])==row["gitBlob"],row["path"]
    # Do not borrow regression results across changed Java tests or frozen instruments.
    changed=git("diff","--name-only",record["sourceCommit"],head).splitlines()
    protected=("core/src/test/","providers/src/test/","fabric/src/test/","fabric/src/gameTest/","e2e/","tools/needle/")
    assert not any(path.startswith(protected) for path in changed),changed
    return {"profile":record["profile"],"qualificationSourceCommit":record["sourceCommit"],"reviewedHead":head,
            "qualificationSourceTree":record["sourceTree"],"productionFilesVerified":len(paths),
            "releaseJarSha256":jar_sha,"frozenCorpusSha256":corpus_sha,"productionUnchanged":True}

def integrated_qualification(root,head,native,current_jar):
    repository=Path(__file__).resolve().parents[1]
    spec=importlib.util.spec_from_file_location("integration_proof",repository/"scripts/verify-integration.py")
    verifier=importlib.util.module_from_spec(spec);spec.loader.exec_module(verifier)
    manifest=json.loads((repository/".github/implementation/all-branches-source.json").read_text())
    corpus=hashlib.sha256((repository/"e2e/generalization/cases.json").read_bytes()).hexdigest()
    historical=hashlib.sha256((repository/"e2e/generalization/baseline.json").read_bytes()).hexdigest()
    jar_sha=hashlib.sha256(current_jar).hexdigest();errors=[]
    ancestors=subprocess.check_output(["git","rev-list","--max-count=64",head],cwd=repository,text=True).splitlines()
    for ancestor in ancestors:
        with urllib.request.urlopen(request(API+f"/actions/runs?head_sha={ancestor}&per_page=100"),timeout=60) as stream:
            runs=json.load(stream)["workflow_runs"]
        for run in runs:
            if run["path"]!=".github/workflows/integration-qualification.yml" or run["conclusion"]!="success":continue
            with urllib.request.urlopen(request(API+f"/actions/runs/{run['id']}/artifacts?per_page=100"),timeout=60) as stream:
                artifacts=json.load(stream)["artifacts"]
            name=f"rb-integration-binary-{run['id']}-{run['run_attempt']}"
            candidates=[artifact for artifact in artifacts if artifact["name"]==name and not artifact["expired"]]
            if len(candidates)!=1:continue
            artifact=candidates[0];path=download(artifact,root)
            with zipfile.ZipFile(path) as archive:
                record=read_json(archive,"integration-baseline.json")
                jars=[name for name in archive.namelist() if name.endswith("/ai-villages-0.1.0.jar")]
                assert len(jars)==1 and hashlib.sha256(archive.read(jars[0])).hexdigest()==record["releaseJarSha256"]
            try:
                assert record["sourceCommit"]==ancestor==artifact["workflow_run"]["head_sha"]
                proof=validate_qualified_source(record,manifest,head,repository,verifier.production_paths(),jar_sha,corpus,historical)
                expected=junit_inventory(path);actual=junit_inventory(root/(str(native["artifactId"])+".zip"))
                assert actual==expected,"Qualified and native module suite/case inventories differ"
            except (AssertionError,subprocess.CalledProcessError) as error:
                errors.append(str(error));continue
            proof.update(qualificationRun=run["id"],qualificationArtifact=artifact["id"],
                    qualificationArtifactDigest=artifact["digest"],moduleSuitesVerified=len(expected),moduleCasesVerified=sum(expected.values()))
            (root/"integration-production-qualification.json").write_text(json.dumps(proof,indent=2)+"\n")
            return proof
    raise AssertionError("No successful combined qualification matches this production binary and source inventory: "+repr(errors))

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,required=True)
    parser.add_argument("--historical",action="store_true")
    parser.add_argument("--integrated",action="store_true")
    args=parser.parse_args();args.root.mkdir(parents=True,exist_ok=True)
    if args.historical:
        for ident,size,digest in ((12410460341,89972218,"a5fc4f065708cdc700c8618791d11ca84a71c7d9b34efd47962cd286fdf45cff"),
                                  (12409634116,198746,"f2325a5cd533a7665c4c00762fc89fbea24cbb54f7e3eaf9c0f84c6a28a48a8c"),
                                  (12411384329,149549405,"e66996a65fb3ed827b882720b77fe71a1a8eb96f554691f10b2836a81e258b4e"),
                                  (12410997027,3819622,"f70bf5a02a59c5257ffa0e451ca068ba818d3b40e4066fe487cd318822bda8ff")):
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
    expected_head=os.environ.get("EXPECTED_HEAD_SHA",os.environ["GITHUB_SHA"])
    selected=current_artifacts(artifacts,os.environ["GITHUB_RUN_ID"],int(os.environ["GITHUB_RUN_ATTEMPT"]),expected_head)
    results=[review(m,args.root,True) for m in selected]
    assert all(r["head"]==expected_head for r in results)
    client=next(r for r in results if r.get("cases"))
    native=next(r for r in results if r.get("moduleCases"))
    assert client["metadata"]["sourceCommit"]==expected_head
    assert client["metadata"]["reviewedHeadCommit"]==expected_head
    assert client["status"]=="PASS" and all(c["status"]=="PASS" for c in client["cases"])
    assert native["moduleCases"]["nonpassing"]==0 and len(native["nativePhases"])==2
    assert sum(len(p["caseNames"]) for p in native["nativePhases"])==9
    assert len(client["actualProductionCopies"])==4 and len(native["actualProductionCopies"])==1
    assert {j["sha256"] for r in results for j in r["actualProductionCopies"]}=={client["metadata"]["releaseSha256"]}
    def jar_bytes(artifact_id):
        with zipfile.ZipFile(args.root/(str(artifact_id)+".zip")) as z:
            p=next(p for p in z.namelist() if p.endswith("/ai-villages-0.1.0.jar"))
            return z.read(p)
    def members(raw):
        with zipfile.ZipFile(io.BytesIO(raw)) as z:
            return {p:hashlib.sha256(z.read(p)).hexdigest() for p in z.namelist()}
    current_jar=jar_bytes(native["artifactId"]);current_members=members(current_jar)
    qualification=integrated_qualification(args.root,expected_head,native,current_jar) if args.integrated else None
    diffs=[]
    for previous_id,expected in ((12410997027,{"dev/aivillages/fabric/FabricGatewayWorld.class"}),
            (12410672066,{"dev/aivillages/fabric/FabricGatewayWorld.class","dev/aivillages/core/kernel/LeasedGateway.class"})):
        previous=members(jar_bytes(previous_id))
        changed=sorted(p for p in previous.keys()&current_members.keys() if previous[p]!=current_members[p])
        added=sorted(current_members.keys()-previous.keys());removed=sorted(previous.keys()-current_members.keys())
        if qualification is None:
            assert previous.keys()==current_members.keys()
            assert set(changed)==expected,changed
        diffs.append({"previousArtifactId":previous_id,"currentNativeArtifactId":native["artifactId"],
                      "changedMembers":changed,"unchangedMembers":len(previous)-len(changed)-len(removed),
                      "added":len(added),"removed":len(removed),"addedMembers":added,"removedMembers":removed,
                      "qualification":qualification})
    (args.root/"production-member-comparison.json").write_text(json.dumps(diffs,indent=2)+"\n")
    print("TASK3_BINARY_MEMBER_REVIEW "+json.dumps(diffs,separators=(",",":")),flush=True)
    print("TASK3_COMPLETE_ARCHIVE_REVIEW_PASS",expected_head,flush=True)

if __name__=="__main__":main()
