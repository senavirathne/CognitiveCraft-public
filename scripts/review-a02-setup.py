#!/usr/bin/env python3
"""Read-only diagnosis of the first Task 3 setup failure; no gameplay retry."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import urllib.error
import urllib.request
import zipfile

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,required=True);args=parser.parse_args();args.root.mkdir(parents=True,exist_ok=True)
    request=urllib.request.Request("https://api.github.com/repos/senavirathne/CognitiveCraft-public/actions/artifacts/12408508550/zip",
        headers={"Authorization":"Bearer "+os.environ["GH_TOKEN"],"Accept":"application/vnd.github+json"})
    try:
        response=urllib.request.build_opener(NoRedirect).open(request,timeout=60)
    except urllib.error.HTTPError as error:
        if error.code!=302: raise
        response=urllib.request.urlopen(error.headers["Location"],timeout=60)
    archive=args.root/"first-setup-failure.zip"
    with response,archive.open("wb") as output:
        while block:=response.read(1024*1024): output.write(block)
    expected="2d45b125f94b3f5450901854e3d5a12f75e5035e587c7c431d198d1309d6a2eb"
    assert archive.stat().st_size==65129873 and hashlib.file_digest(archive.open("rb"),"sha256").hexdigest()==expected
    result={"archiveId":12408508550,"archiveBytes":65129873,"archiveSha256":expected,"run":39739824004,"attempt":1}
    with zipfile.ZipFile(archive) as z:
        paths=z.namelist()
        result_path=next(p for p in paths if p.endswith("dropped-log-results.json"))
        data=json.loads(z.read(result_path))
        result["caseStatus"]=data["cases"]
        result["failure"]=data.get("failure");result["metadata"]=data.get("metadata")
        jars=[]
        for path in paths:
            if path.endswith("/ai-villages-0.1.0.jar"):
                contents=z.read(path);sha=hashlib.sha256(contents).hexdigest()
                assert sha=="ae11775bec381cf806995ceffd4ae0508fd870a1f8f9c300ed21b7d4f5c99206"
                jars.append({"path":path,"sha256":sha,"bytes":len(contents)})
        result["actualProductionCopies"]=jars
        snapshots=[p for p in paths if p.endswith("server/snapshot.json")]
        if snapshots:
            state=json.loads(z.read(snapshots[0]))
            result["finalServer"]={k:state.get(k) for k in ("gameTick","generationCalls","needleCalls","actors")}
            result["finalServer"]["players"]={p:{k:v.get(k) for k in ("uuid","pos","citizens","online")}
                    for p,v in state.get("knownPlayers",{}).items()}
        result["logs"]={}
        for path in paths:
            if path.endswith("stdout.log"):
                rows=z.read(path).decode("utf-8",errors="replace").splitlines()
                selected=[{"line":i,"text":l[:2000]} for i,l in enumerate(rows,1)
                          if any(s in l.lower() for s in ("enroll","playerb","exception","timed out","can't keep up"))]
                result["logs"][path]=selected[-25:]
            if path.endswith("events.jsonl"):
                events=[json.loads(line) for line in z.read(path).splitlines()]
                result["logs"][path]=[e for e in events if e.get("kind") in ("feedback","disconnect","connected","command")][-25:]
    (args.root/"first-setup-review.json").write_text(json.dumps(result,indent=2)+"\n")
    print("TASK3_SETUP_REVIEW "+json.dumps(result,separators=(",",":")),flush=True)

if __name__=="__main__": main()
