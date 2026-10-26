#!/usr/bin/env python3
"""B01 real client/instrument/collector gate using the stopped B00 world.

Reuse is allowed only for the identical production JAR and admitted method. No
seeded executable, fake certificate, new model call or production hook is used.
"""
from __future__ import annotations
import argparse
import copy
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import traceback

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from suite import Harness, ROOT, UUID_PATTERN, atomic, read, require
from runtime import SDK, digest
from campaigns.crafting.oracle import candidates
from parallel_b.baseline import seed, coord, SOURCE, DESTINATION, LOG, PLANK, OTHER
from parallel_b.guards import clean_owners, instruments, observer_inputs, verified_clock
from parallel_b.qualification import verify, load


def collector(root, expected):
    required = ["metadata.json","configuration-manifest.json","result.json","canonical.json",
                "oracle-recipes.json","screenshots.json","clients/PlayerA/events.jsonl",
                "server/observations.jsonl","supervisor.jsonl","kill-witness.json"]
    mandatory = {
        "canonical":lambda r:load(r,"canonical.json")["view"]["marker"]["outcome"]=="SUCCEEDED",
        "no-model":lambda r:load(r,"canonical.json")["callsBefore"]==load(r,"canonical.json")["callsAfter"],
        "actual-kill":lambda r:load(r,"kill-witness.json")["processExitObserved"] is True,
        "scenario":lambda r:load(r,"result.json")["status"]=="PASS"}
    return verify(root,expected,required=required,mandatory=mandatory)


def negative_collectors(root,expected):
    rows=[]
    for label,code in (("missing","MISSING"),("stale","STALE_ATTEMPT"),("binary","WRONG_BINARY"),
                       ("dropped-observer","MISSING"),("mislabeled-observer","MISSING")):
        with tempfile.TemporaryDirectory(prefix="cc-b01-negative-") as temporary:
            copy_root=Path(temporary)/"evidence";shutil.copytree(root,copy_root)
            wanted=copy.deepcopy(expected)
            if label=="missing":(copy_root/"canonical.json").unlink()
            elif label=="stale":wanted["GITHUB_RUN_ATTEMPT"]=str(int(expected["GITHUB_RUN_ATTEMPT"])+1)
            elif label=="binary":wanted["releaseSha256"]="0"*64
            elif label=="dropped-observer":(copy_root/"server/observations.jsonl").unlink()
            else:
                p=copy_root/"manifest.json";m=read(p)
                m["files"]=[x for x in m["files"] if x["path"]!="server/observations.jsonl"]
                atomic(p,m)
            result=collector(copy_root,wanted)
            require(result.get("code")==code and result["integrity"]=="FAIL" and result["mandatoryGameplay"]=="NOT_QUALIFIED",
                    "Negative collector accepted/misclassified "+label+": "+json.dumps(result))
            rows.append({"mutation":label,"expected":code,"actual":result})
    return rows


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,required=True)
    parser.add_argument("--seed",type=Path,required=True)
    args=parser.parse_args()
    original=args.seed/"build/e2e/parallel-b/B00"
    seed_metadata=read(original/"metadata.json")
    production=ROOT/"fabric/build/libs/ai-villages-0.1.0.jar"
    observer=ROOT/"build/e2e-driver/observer.jar"
    require(seed_metadata and digest(production)==seed_metadata["releaseSha256"],
            "Saved method reuse requires the exact previously qualified production binary")
    instruments(production,observer)
    h=Harness(args.root,"compose",None)
    h.project="cc-production-b-"+read(h.root/"metadata.json")["fixtureInstance"][:8]
    h.family="B01-QUALIFICATION"
    expected={"sourceCommit":subprocess.check_output(["git","rev-parse","HEAD"],cwd=ROOT,text=True).strip(),
              "sourceTree":subprocess.check_output(["git","rev-parse","HEAD^{tree}"],cwd=ROOT,text=True).strip(),
              "sourceBranch":"impl/activity-production-planning","releaseSha256":digest(production),
              "observerSha256":digest(observer),"profile":"B01",
              "GITHUB_RUN_ID":os.environ["GITHUB_RUN_ID"],"GITHUB_RUN_ATTEMPT":os.environ["GITHUB_RUN_ATTEMPT"]}
    result={"status":"NOT_EXECUTED","nativeGameplayAttempts":0}
    try:
        game=h.root/"server/game";shutil.copytree(original/"server/game",game)
        props=game/"server.properties"
        props.write_text(re.sub(r"(?m)^server-port=.*$","server-port="+str(h.port),props.read_text()))
        # Test-only required oracle input, before any server start or attempted case.
        atomic(h.root/"oracle-candidates.json",candidates(SDK/"minecraft-client.jar"))
        observer_inputs(h.root)
        old=read(original/"q4.json")
        actor=old["root"]["request"]["request"]["arguments"]["actor"]["value"]
        h.citizens["PlayerA"]=actor["citizenId"];h.entities["PlayerA"]=actor["entityId"]
        h.env["COGNITIVECRAFT_E2E_SNAPSHOT_TICKS"]="1"
        h.compose("build","minecraft");h.start("server");h.start("PlayerA")
        observed=h.wait(lambda:read(h.root/"oracle-recipes.json"),"Actually loaded read-only recipe oracle",30)
        require(observed["serverPid"]==read(h.root/"server/process.json")["pid"] and observed["worldEffects"]==0,
                "Oracle export belongs to another server or performed effects")
        h.wait(lambda:clean_owners(h.snapshot().get("knownPlayers",{}).get("PlayerA",{})),
               "Prior saved owners are actually settled",40)
        h.command("PlayerA","/aivillage kernel inference false","admission=false")
        seed(h);before=h.snapshot();calls=h.model_calls();before_planks=h.container(DESTINATION,PLANK)
        command="/aivillage kernel make-birch-planks "+h.citizens["PlayerA"]+" 4 "+coord(SOURCE+DESTINATION)
        h.actions_active=True
        feedback=h.command("PlayerA",command,"run="+UUID_PATTERN)
        require("accepted=true" in feedback,"Supported baseline craft was refused: "+feedback)
        result["nativeGameplayAttempts"]=1
        rid=re.search("run=("+UUID_PATTERN+")",feedback).group(1)
        view=h.terminal("PlayerA",rid,220)
        require(view.get("marker",{}).get("outcome")=="SUCCEEDED","Actual canonical craft failed: "+view["description"])
        require(view["marker"]["artifactSha256"]==old["root"]["preparation"]["method"]["sha256"],"Saved method was regenerated")
        require(h.container(SOURCE,LOG)==0 and h.container(SOURCE,OTHER)==2 and h.container(DESTINATION,PLANK)==before_planks+4,
                "Canonical physical input/output or unrelated stock differs")
        require(h.model_calls()==calls,"Known saved method invoked a model")
        h.wait(lambda:clean_owners(h.snapshot().get("knownPlayers",{}).get("PlayerA",{})),"Actual craft owner settlement",40)
        h.actions_active=False
        require(h.menu_count("PlayerA",DESTINATION,PLANK)==before_planks+4,"Independent client contents differ")
        h.screenshot("PlayerA","b01-native-canonical",chat=True)
        atomic(h.root/"canonical.json",{"command":command,"feedback":feedback,"before":before,"after":h.snapshot(),
                "view":view,"callsBefore":calls,"callsAfter":h.model_calls()})
        verified_clock(h,"tick freeze");verified_clock(h,"tick unfreeze")
        rejection=h.command("PlayerA","/tick definitely_invalid",r"(?i)unknown|incorrect|incomplete|expected",30)
        atomic(h.root/"rejected-clock.json",{"feedback":rejection,"isKillWitness":False})
        cold=read(h.root/"server/process.json")
        h.stop("server",hard=True)
        require(not h.client_snapshot("PlayerA")["play"],"Client still observes a connected server after actual SIGKILL")
        events=[json.loads(l) for l in (h.root/"supervisor.jsonl").read_text().splitlines()]
        stopped=[e for e in events if e["kind"]=="process-stop-witness" and e["data"]["component"]=="server" and e["data"]["hard"]]
        require(len(stopped)==1,"Missing/duplicate independent SIGKILL witness")
        atomic(h.root/"kill-witness.json",{"process":cold,"processExitObserved":True,"disconnectionObserved":True,
                "nativeInspection":stopped[0],"recoveryClaim":False})
        result.update(status="PASS",meaning="Native craft and repaired observer/kill/collection path; no recovery claim")
    except Exception as error:
        result.update(status="FAIL",reason=str(error),trace=traceback.format_exc())
        atomic(h.root/"failure-snapshot.json",h.snapshot())
        raise
    finally:
        h.finish();atomic(h.root/"result.json",result)
        metadata=read(h.root/"metadata.json");metadata["profile"]="B01"
        metadata["savedMethodSource"]={"commit":seed_metadata["sourceCommit"],"releaseSha256":seed_metadata["releaseSha256"],"run":40257555612}
        atomic(h.root/"metadata.json",metadata)
        files=[{"path":str(p.relative_to(h.root)),"sha256":digest(p),"bytes":p.stat().st_size}
               for p in sorted(h.root.rglob("*")) if p.is_file() and p.name!="manifest.json"]
        atomic(h.root/"manifest.json",{"source":metadata,"files":files})
    expected["configurationSha256"]=digest(h.root/"configuration-manifest.json")
    qualification=collector(h.root,expected)
    require(qualification["integrity"]=="PASS" and qualification["mandatoryGameplay"]=="PASS",json.dumps(qualification))
    negatives=negative_collectors(h.root,expected)
    atomic(h.root/"qualification.json",{"expected":expected,"native":qualification,"negativeCollectors":negatives,
           "syntheticOwnerAndSelectorGates":"Reported separately in unittest output; not native gameplay"})
    print(json.dumps({"B01":qualification,"negativeCollectorCount":len(negatives)}),flush=True)


if __name__=="__main__":main()
