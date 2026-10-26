#!/usr/bin/env python3
"""B02 exact demand/surplus and capacity-negative connected-client qualification."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import traceback

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from suite import Harness,ROOT,UUID_PATTERN,atomic,read,require
from runtime import SDK,digest
from campaigns.crafting.oracle import candidates
from parallel_b.baseline import scenario,seed,submit,physical,inventory,coord,SOURCE,DESTINATION,LOW,HIGH,LOG,PLANK,OTHER
from parallel_b.guards import clean_owners,instruments,observer_inputs
from parallel_b.qualification import verify,load
from parallel_b.diagnostics import settled


def terminal_job(h,jid):
    def done():
        j=h.snapshot()["players"]["PlayerA"].get("jobs",{}).get(jid)
        return j if j and j["state"] in {"SUCCEEDED","FAILED","CANCELLED","INTERRUPTED"} else None
    return h.wait(done,"Exact-demand original Job terminal",240)


def extra_cases(h,artifact):
    h.wait(lambda:clean_owners(h.snapshot()["knownPlayers"]["PlayerA"]),"Prior quantity controls settled",40)
    h.console(['data merge block '+coord(SOURCE)+' {Items:[{Slot:26b,id:"minecraft:birch_log",count:1},'
               '{Slot:0b,id:"minecraft:cobblestone",count:2},{Slot:1b,id:"minecraft:birch_planks",count:11}]}'])
    h.wait(lambda:h.container(SOURCE,LOG)==1 and h.container(SOURCE,PLANK)==11,"Old stock and new input are independently observed")
    require(h.menu_count("PlayerA",SOURCE,PLANK)==11,"Client old-output stock differs")
    before=h.snapshot();held=inventory(h).get(PLANK,0);calls=h.model_calls()
    command="/aivillage kernel compose-birch-planks "+h.citizens["PlayerA"]+" 3 "+coord(SOURCE+SOURCE+LOW+HIGH)
    jid,feedback=submit(h,command,"job");root=terminal_job(h,jid)
    require(root["state"]=="SUCCEEDED" and root["fulfilled"]==3,"Same-container MAKE failed: "+json.dumps(root))
    require(h.container(SOURCE,LOG)==0 and h.container(SOURCE,OTHER)==2 and h.container(SOURCE,PLANK)==14
            and inventory(h).get(PLANK,0)==held+1,"Old final goods replaced production or new surplus was lost")
    require(root["preparation"]["method"]["sha256"]==artifact and h.model_calls()==calls,"Same-container bindings changed method or invoked inference")
    rows=[r for a in root["attempts"] for r in a.get("resourceReceipts",[])]
    sums={stage:sum(r["quantity"] for r in rows if r["stage"]==stage) for stage in ("WITHDRAW","CRAFT","DEPOSIT")}
    require(sums=={"WITHDRAW":1,"CRAFT":4,"DEPOSIT":3},"Same-container new-output attribution differs")
    h.actions_active=False
    h.screenshot("PlayerA","b02-same-container-old-output",chat=True)
    atomic(h.root/"same-container.json",{"command":command,"feedback":feedback,"before":before,"after":h.snapshot(),
           "root":root,"receiptQuantities":sums,"callsBefore":calls,"callsAfter":h.model_calls()})
    h.wait(lambda:clean_owners(h.snapshot()["knownPlayers"]["PlayerA"]),"Same-container owner settled",40)
    seed(h)
    stacks=[f'{{Slot:{i}b,id:"minecraft:cobblestone",count:64}}' for i in range(27)]
    h.console(['data merge block '+coord(DESTINATION)+' {Items:['+','.join(stacks)+']}'])
    h.wait(lambda:h.container(DESTINATION,OTHER)==27*64,"Full destination independently observed")
    require(h.menu_count("PlayerA",DESTINATION,OTHER)==27*64,"Client did not observe full destination")
    before=h.snapshot();stock=physical(h);calls=h.model_calls()
    command="/aivillage kernel make-birch-planks "+h.citizens["PlayerA"]+" 3 "+coord(SOURCE+DESTINATION)
    h.actions_active=True
    feedback=h.command("PlayerA",command,r"run=|accepted=false|Denied|unavailable",40)
    view=None
    if "accepted=true" in feedback:
        rid=re.search("run=("+UUID_PATTERN+")",feedback).group(1)
        view=h.terminal("PlayerA",rid,220)
        require(view.get("marker",{}).get("outcome")!="SUCCEEDED" and not view.get("resourceReceipts"),
                "Full destination admitted physical effects")
        require("TARGET_UNAVAILABLE" in view["description"],"Wrong negative refusal: "+view["description"])
    else:
        require("TARGET_UNAVAILABLE" in feedback,"Capacity negative refused for another cause: "+feedback)
    require(physical(h)==stock and h.model_calls()==calls,"Full destination changed physical stock or invoked inference")
    h.actions_active=False
    h.screenshot("PlayerA","b02-full-destination-refusal",chat=True)
    atomic(h.root/"capacity-negative.json",{"command":command,"feedback":feedback,"view":view,
           "before":before,"after":h.snapshot(),"status":"PASS_EXPECTED_REFUSAL","physicalDelta":{}})


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--root",type=Path,required=True);args=p.parse_args()
    h=Harness(args.root,"compose",None);h.project=os.environ.get('COGNITIVECRAFT_B_QUALIFICATION_PROJECT',"cc-production-b-"+read(h.root/"metadata.json")["fixtureInstance"][:8]);h.family="B02-QUANTITIES"
    result={"status":"NOT_EXECUTED"}
    expected={"sourceCommit":subprocess.check_output(["git","rev-parse","HEAD"],cwd=ROOT,text=True).strip(),
              "sourceTree":subprocess.check_output(["git","rev-parse","HEAD^{tree}"],cwd=ROOT,text=True).strip(),
              "sourceBranch":"impl/activity-production-planning","profile":"B02",
              "releaseSha256":digest(ROOT/"fabric/build/libs/ai-villages-0.1.0.jar"),
              "observerSha256":digest(ROOT/"build/e2e-driver/observer.jar"),
              "GITHUB_RUN_ID":os.environ["GITHUB_RUN_ID"],"GITHUB_RUN_ATTEMPT":os.environ["GITHUB_RUN_ATTEMPT"]}
    try:
        instruments(ROOT/"fabric/build/libs/ai-villages-0.1.0.jar",ROOT/"build/e2e-driver/observer.jar")
        atomic(h.root/"oracle-candidates.json",candidates(SDK/"minecraft-client.jar"));observer_inputs(h.root)
        h.compose("build","minecraft")
        result=scenario(h,corrected=True)
        extra_cases(h,result["artifact"])
        result['finalSettlement']=settled(h,fence=True)
    except Exception as error:
        result.update(status="FAIL",reason=str(error),trace=traceback.format_exc())
        atomic(h.root/"failure-snapshot.json",h.snapshot());raise
    finally:
        h.finish();atomic(h.root/"result.json",result)
        meta=read(h.root/"metadata.json");meta["profile"]="B02";atomic(h.root/"metadata.json",meta)
        files=[{"path":str(f.relative_to(h.root)),"sha256":digest(f),"bytes":f.stat().st_size}
               for f in sorted(h.root.rglob('*')) if f.is_file() and f.name!="manifest.json"]
        atomic(h.root/"manifest.json",{"source":meta,"files":files})
    expected["configurationSha256"]=digest(h.root/"configuration-manifest.json")
    q=verify(h.root,expected,required=["metadata.json","result.json","canonical.json","q3.json","q4.json",
             "same-container.json","capacity-negative.json","server/observations.jsonl","screenshots.json"],mandatory={
             "q3":lambda r:load(r,"q3.json")["root"]["state"]=="SUCCEEDED",
             "q4":lambda r:load(r,"q4.json")["root"]["state"]=="SUCCEEDED",
             "same-container":lambda r:load(r,"same-container.json")["root"]["fulfilled"]==3,
             "capacity-negative":lambda r:load(r,"capacity-negative.json")["status"]=="PASS_EXPECTED_REFUSAL"})
    atomic(h.root/"qualification.json",q)
    require(q["integrity"]=="PASS" and q["mandatoryGameplay"]=="PASS",json.dumps(q))
    print(json.dumps(q),flush=True)


if __name__=="__main__":main()
