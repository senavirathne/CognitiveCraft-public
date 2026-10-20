#!/usr/bin/env python3
"""Read-only independent review of the frozen, completed 45-case source Actions run.
Does not import production, start Minecraft, invoke models, or change classifications.
"""
import base64
from collections import Counter
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import urllib.parse
import urllib.request
import zipfile

REPO = "senavirathne/CognitiveCraft-public"
HEAD = "bbaa43b6220f7086267c84c96a085d00a7eb740a"
RUN = 39434946667
JAR = "acc986c2b545735d8c2c6fc9c99ae59ad0291a35b7d134b3681436f5418316f0"
CORPUS = "ee2e323d2c329a4f6040c3b96ffe880a12a3d33a3b8954a7174dc32766eb8bc5"
PINS = {
 "oak": (12272292483,106302942,"91de8917c93c4738eb3d6db2744ef0a502cd018edb7f4ab0c0b0efcb5e367050"),
 "craft": (12273369048,279785664,"f284049ed83bc34d9f7ed9387cc05b5b8f2dcdb8d7d2615f589eafc7211bfb97"),
 "facility": (12273317849,135691905,"1754dd140f1331c864bca86c607b6cd275316a65396dc8da666ad879e0bbfe78"),
 "birch": (12273490058,108710655,"4443b21ba4b111fdc853552a2675da00fb6b99f94808d64f04cb979af9252a89"),
 "blueprint": (12273915011,98967924,"8581f4b3f7ef6c80a655c64dbd00289c415028503736b0924e02942c487596d1")
}
ITEMS = {"minecraft:"+s for s in ("oak_log","oak_planks","birch_log","birch_planks","stick","wooden_axe","crafting_table")}
RECIPES = {
 "minecraft:oak_planks": (4, {"minecraft:oak_log":1}),
 "minecraft:birch_planks": (4, {"minecraft:birch_log":1}),
 "minecraft:stick": (4, {"minecraft:oak_planks":2}),
 "minecraft:wooden_axe": (1, {"minecraft:oak_planks":3,"minecraft:stick":2}),
 "minecraft:crafting_table": (1, {"minecraft:oak_planks":4})
}
OUT = Path("build/rb-independent-review")
OUT.mkdir(parents=True, exist_ok=True)

def require(ok, why):
    if not ok:
        raise ValueError(why)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def canonical(value):
    return json.dumps(value,sort_keys=True,separators=(",",":"),ensure_ascii=False).encode()

def object_sha(value):
    return sha(canonical(value))

class Redirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,url):
        other=super().redirect_request(req,fp,code,msg,headers,url)
        if urllib.parse.urlparse(req.full_url).netloc != urllib.parse.urlparse(url).netloc:
            other.remove_header("Authorization")
        return other

def download(group,pin):
    ident,size,digest=pin
    path=OUT/(str(ident)+".zip")
    req=urllib.request.Request(f"https://api.github.com/repos/{REPO}/actions/artifacts/{ident}/zip",
        headers={"Authorization":"Bearer "+os.environ["GITHUB_TOKEN"],"Accept":"application/vnd.github+json"})
    with urllib.request.build_opener(Redirect()).open(req,timeout=60) as source,path.open("wb") as target:
        while data:=source.read(1024*1024):
            target.write(data)
            require(target.tell()<=400_000_000,"Archive download bound")
    require(path.stat().st_size==size and sha(path.read_bytes())==digest,"Original archive size/hash differs: "+group)
    return path

def member(archive,suffix):
    names=[n for n in archive.namelist() if n.endswith(suffix)]
    require(len(names)==1,"Missing/ambiguous original member: "+suffix)
    return names[0]

def lines(archive,name):
    with archive.open(name) as source:
        for raw in source:
            if not raw.endswith(b"\n"):
                # A killed writer may leave an uncommitted tail. Completed malformed lines still fail.
                continue
            if raw.strip():
                yield json.loads(raw)

def block_item(state):
    match=re.search(r"minecraft:([a-z0-9_]+)",state)
    item="minecraft:"+match.group(1) if match else None
    return item if item in ITEMS else None

def physical(state):
    require(state and "containers" in state and "actors" in state and "knownPlayers" in state and "drops" in state,
        "Incomplete authoritative physical observation")
    players={}
    for name,p in state["knownPlayers"].items():
        require("assessmentInventory" in p,"Missing authoritative player inventory: "+name)
        players[name]={k:p[k] for k in ("uuid","online","pos","assessmentInventory") if k in p}
    return {"time":state.get("time"),"gameTick":state.get("gameTick"),"tick":state.get("tick"),
        "canonicalOriginalSha256":object_sha(state),"containers":state["containers"],
        "actors":[{k:a[k] for k in ("uuid","pos","alive","baby","noAI","inventory") if k in a} for a in state["actors"]],
        "players":players,"drops":state["drops"],
        "resourceBlocks":{p:s for p,s in state.get("blocks",{}).items() if block_item(s)},
        "modelCalls":{k:state.get(k) for k in ("needleCalls","generationCalls")}}

def totals(state):
    p=physical(state); result=Counter()
    for stock in p["containers"].values():
        result.update(stock)
    for actor in p["actors"]:
        result.update(actor["inventory"])
    for player in p["players"].values():
        result.update(player["assessmentInventory"])
    for drop in p["drops"]:
        result[drop["item"]]+=drop["count"]
    for block in p["resourceBlocks"].values():
        result[block_item(block)]+=1
    return dict(sorted((i,q) for i,q in result.items() if q))

def all_receipts(value,result=None):
    result={} if result is None else result
    if isinstance(value,dict):
        if "receiptId" in value and "stage" in value and "quantity" in value:
            previous=result.get(value["receiptId"])
            require(previous is None or previous==value,"Conflicting duplicate resource receipt")
            result[value["receiptId"]]=value
        for v in value.values():
            all_receipts(v,result)
    elif isinstance(value,list):
        for v in value:
            all_receipts(v,result)
    return result

def delta(receipts):
    change=Counter()
    for r in receipts:
        if r["stage"]!="CRAFT":
            continue
        item=r["item"]["id"]; quantity=r["quantity"]
        require(item in RECIPES and isinstance(quantity,int) and quantity>0,"Unknown/invalid craft receipt")
        batch,inputs=RECIPES[item]
        require(quantity%batch==0,"Nonintegral recipe output batch")
        change[item]+=quantity
        for material,count in inputs.items():
            change[material]-=count*(quantity//batch)
    return dict(change)

def reconcile(ident,label,before,after,witness):
    old=all_receipts(before); seen=all_receipts([after,witness])
    fresh=[r for key,r in sorted(seen.items()) if key not in old]
    initial=totals(before); final=totals(after); change=delta(fresh)
    expected={item:initial.get(item,0)+change.get(item,0) for item in set(initial)|set(final)|set(change)}
    differences={item:final.get(item,0)-q for item,q in sorted(expected.items()) if final.get(item,0)!=q}
    return {"scenario":ident,"boundary":label,"status":"PASS" if not differences else "FAIL",
        "before":physical(before),"after":physical(after),"beforeTotals":initial,
        "recipeDelta":change,"expectedAfterTotals":{i:q for i,q in sorted(expected.items()) if q},
        "actualAfterTotals":final,"differences":differences,"freshReceipts":fresh}

def client(value):
    return {"canonicalOriginalSha256":object_sha(value),**{k:value[k] for k in
        ("uuid","play","singleplayer","pos","entities","blocks","slots","menu") if k in value}}

def task_times(archive,root,rows):
    # Genuine client action is timestamped before its server-bound command.
    starts=sorted((r["started"]*1000,r["id"]) for r in rows)
    requested={}
    for player in ("PlayerA","PlayerB"):
        name=member(archive,root+"/clients/"+player+"/events.jsonl")
        for event in lines(archive,name):
            data=event.get("data",{})
            if event.get("kind")!="action" or data.get("action")!="command":
                continue
            text=data.get("text","")
            if not re.match(r"/?aivillage kernel (?:gather-oak|gather-birch|make-planks|make-birch-planks|make-sticks|make-axes|make-tables|place-table|compose-axes|provision-axe|blueprint-materials stage|ask) ",text):
                continue
            t=event["time"]
            eligible=[p for p in starts if p[0]<=t]
            if eligible:
                ident=eligible[-1][1]
                requested[ident]=min(requested.get(ident,t),t)
    return requested

def fallback_states(archive,root,requested):
    selected={}
    name=member(archive,root+"/server/observations.jsonl")
    # One pass through immutable original server observations; never reconstruct a state.
    for state in lines(archive,name):
        timestamp=state["time"]
        for ident,target in requested.items():
            if target-2000<=timestamp<=target and (ident not in selected or selected[ident]["time"]<timestamp):
                selected[ident]=state
    return selected

def details(value):
    # Keep bound arguments, source stock, method hashes and outcomes; omit repeated full world dumps.
    if isinstance(value,list):
        return [details(v) for v in value]
    if not isinstance(value,dict):
        return value
    if "containers" in value and "knownPlayers" in value and "actors" in value:
        return {"physicalObservationSha256":object_sha(value),"time":value.get("time"),"gameTick":value.get("gameTick")}
    if "play" in value and "singleplayer" in value and "blocks" in value:
        return client(value)
    return {k:details(v) for k,v in value.items() if k not in ("commandTree","standable")}

def emit_file(path,content,encoding="utf-8"):
    destination=OUT/path
    destination.parent.mkdir(parents=True,exist_ok=True)
    if encoding=="utf-8":
        data=content.encode()
        destination.write_bytes(data)
    else:
        data=content
        destination.write_bytes(data)
        content=base64.b64encode(data).decode()
    # This bounded evidence transport contains only original public game observations.
    print("REVIEW_FILE "+json.dumps({"path":path,"encoding":encoding,"sha256":sha(data),"content":content},
        separators=(",",":"),ensure_ascii=False),flush=True)

def main():
    corpus_path=Path("e2e/generalization/cases.json")
    require(sha(corpus_path.read_bytes())==CORPUS,"Frozen corpus changed")
    corpus=json.loads(corpus_path.read_text()); specs={r["id"]:r for r in corpus["scenarios"]}
    physical_rows=[]; conserved=[]; groups=[]; captures=[]; seen=set()
    wanted={"A09":"synchronized-container-2","A13":"placed-table-world-view",
            "A15":"synchronized-container-5","A17":"synchronized-container-7"}
    for group,pin in PINS.items():
        path=download(group,pin)
        with zipfile.ZipFile(path) as archive:
            infos=archive.infolist()
            require(len(infos)<50000 and len({i.filename for i in infos})==len(infos),"Archive member bound/duplicates")
            for info in infos:
                p=PurePosixPath(info.filename)
                require(not p.is_absolute() and ".." not in p.parts and "\\" not in info.filename and
                    not stat.S_ISLNK(info.external_attr>>16),"Unsafe original ZIP path")
            filename=member(archive,"/generalization-results.json")
            root=filename.rsplit("/",1)[0]
            raw=archive.read(filename); document=json.loads(raw)
            require(document["group"]==group and document["measurementStatus"]=="COMPLETE","Wrong/incomplete original group")
            meta=document["metadata"]
            require(meta["sourceCommit"]==HEAD and meta["releaseSha256"]==JAR and meta.get("dirty") is False,
                "Original group identity differs")
            jars=[i for i in infos if i.filename.endswith("/mods/ai-villages-0.1.0.jar")]
            for info in jars:
                require(sha(archive.read(info))==JAR,"Original runtime production JAR differs")
            require(jars,"No actual runtime production JAR")
            rows=document["scenarios"]; fallback_ids={r["id"] for r in rows if r["id"] in ("A01","A02","C01","C02","C03","C04","C05")}
            times=task_times(archive,root,rows)
            requested={i:times[i] for i in fallback_ids if i in times}
            fallback=fallback_states(archive,root,requested)
            groupinfo={"group":group,"artifactId":pin[0],"archiveBytes":pin[1],"archiveSha256":pin[2],
                "originalResultsMember":filename,"originalResultsSha256":sha(raw),"metadata":meta,
                "actualRuntimeJarCopiesVerified":len(jars),"independentAdmissions":details(document.get("independentAdmissions")),
                "admittedArtifacts":document.get("admittedArtifacts"),"setupCommandErrors":[]}
            for info in infos:
                if re.search(r"/server/(?:stdout|stderr)\.log$",info.filename):
                    data=archive.read(info).decode(errors="replace").splitlines()
                    for index,line in enumerate(data):
                        if "Incorrect argument for command" in line:
                            groupinfo["setupCommandErrors"].append({"member":info.filename,"lines":data[max(0,index-1):index+3]})
            groups.append(groupinfo)
            for row in rows:
                ident=row["id"]
                require(ident not in seen and row["group"]==group,"Duplicate/cross-group scenario")
                seen.add(ident)
                for key in ("id","group","category","requirement","expected","composition"):
                    require(row[key]==specs[ident][key],"Frozen scenario differs")
                evidence=row.get("evidence",{})
                after=row["physicalFinalState"]
                clients=row["connectedClientObservations"]
                require(set(clients)=={"PlayerA","PlayerB"},"Missing original connected client")
                require(all(clients[p].get("play") and not clients[p].get("singleplayer") and
                    clients[p]["uuid"]==after["knownPlayers"][p]["uuid"] for p in clients),"Client/server UUID mismatch")
                before=evidence.get("before") or evidence.get("physicalBeforeRequest") or fallback.get(ident)
                record={"id":ident,"group":group,"status":row["status"],"reason":row.get("reason"),
                    "originalRecordCanonicalSha256":object_sha(row),"before":physical(before) if before else None,
                    "final":physical(after),"afterCleanup":physical(row["physicalAfterCleanup"]) if row.get("physicalAfterCleanup") else None,
                    "connectedClients":{p:client(v) for p,v in clients.items()},
                    "evidence":details(evidence),"screenshots":row.get("screenshots",[]),
                    "rawLanguage":after.get("assessmentLanguage")}
                for capture in row.get("screenshots",[]):
                    data=archive.read(root+"/"+capture["path"])
                    require(data[:8]==b"\x89PNG\r\n\x1a\n" and sha(data)==capture["sha256"] and len(data)==capture["bytes"],
                        "Original native frame bytes differ")
                    require(capture["sourceCommit"]==HEAD and capture["releaseSha256"]==JAR and capture["nativeClientFrame"] is True,
                        "Frame identity differs")
                    captures.append(capture)
                    if wanted.get(ident)==capture.get("checkpoint"):
                        name=f"docs/e2e/assets/rb-generalization/{ident}-{capture['checkpoint']}.png"
                        emit_file(name,data,"base64")
                        record["retainedOriginalFrame"]=name
                physical_rows.append(record)
                if row["category"] not in ("parameter","dynamic") and ident not in ("D03","D04"):
                    continue
                if ident in ("D03","D04") and row["status"]=="FAIL":
                    conserved.append({"scenario":ident,"status":"EXCLUDED_NO_NATIVE_EXECUTION","reason":"Exact paraphrase clarified before native gameplay; no physical transformation was requested."})
                    continue
                try:
                    if ident=="C10":
                        checks=[reconcile(ident,name,pair["before"],pair["physicalAfter"],pair)
                            for name,pair in ((n,evidence[n]) for n in ("firstRequest","secondRequest"))]
                    else:
                        require(before is not None,"No original pre-request observation in the two-second window")
                        checks=[reconcile(ident,"native-request",before,after,evidence)]
                    conserved.append({"scenario":ident,"status":"PASS" if all(c["status"]=="PASS" for c in checks) else "FAIL","checks":checks})
                except (ValueError,KeyError,TypeError) as error:
                    conserved.append({"scenario":ident,"status":"INCONCLUSIVE","reason":str(error)})
        path.unlink()
    require(seen==set(specs) and len(physical_rows)==45,"All 45 original scenarios required")
    require(len(captures)==65,"Original case frame count differs")
    physical_report={"schema":1,"assessmentCommit":HEAD,"assessmentRun":RUN,"productionBaseline":corpus["productionBaseline"],
        "releaseJarSha256":JAR,"corpusSha256":CORPUS,"reviewType":"Read-only independent original-artifact reconciliation",
        "groups":groups,"verifiedNativeFrames":len(captures),"scenarios":sorted(physical_rows,key=lambda r:r["id"])}
    counts=Counter(r["status"] for r in conserved)
    conservation={"schema":1,"assessmentCommit":HEAD,"assessmentRun":RUN,"requestedDenominator":30,
        "evaluableDenominator":counts["PASS"]+counts["FAIL"],"correct":counts["PASS"],"classifications":dict(counts),
        "rule":"Containers + authoritative player inventories + NPC custody + drops + resource world blocks, adjusted only by deduplicated fresh installed-recipe CRAFT receipts. Moves, harvest and placement conserve total physical units.",
        "scope":"Original observed case and cleanup boundaries, not indefinite post-despawn conservation; C10 contains two independently reset request boundaries.",
        "scenarios":sorted(conserved,key=lambda r:r["scenario"])}
    emit_file("evidence/rb-generalization-physical-evidence.json",canonical(physical_report).decode()+"\n")
    emit_file("evidence/rb-generalization-conservation.json",canonical(conservation).decode()+"\n")
    print("REVIEW_RESULT "+json.dumps({"scenarioCount":45,"nativeFrames":len(captures),"conservation":{k:v for k,v in conservation.items() if k!="scenarios"},
        "groups":[{"group":g["group"],"runtimeJarCopies":g["actualRuntimeJarCopiesVerified"],"setupErrors":g["setupCommandErrors"]} for g in groups]},
        separators=(",",":")),flush=True)
    require(len(conserved)==30,"Fixed conservation requested denominator changed")
    require(not counts["FAIL"] and not counts["INCONCLUSIVE"],"Independent conservation discrepancies remain visible; inspect original evidence")

if __name__=="__main__":
    main()
