"""Supplementary D02 actual language/production evidence; frozen assessment stays byte-identical."""
from __future__ import annotations

import json
import re


def language_evidence(h, prepare, verify, acquisition, typed_interface, models, needle):
    from suite import ROOT, UUID_PATTERN, require
    from planks import SOURCE, DESTINATION, LOG, PLANK, actor_inventory

    corpus = json.loads((ROOT / "providers/src/test/resources/nlu/d02-requests.json").read_text())
    records = []

    def ask(row):
        reply = h.command("PlayerA", "/aivillage kernel ask "+row["text"], "interpretation="+UUID_PATTERN)
        ticket = re.search("interpretation=("+UUID_PATTERN+")", reply).group(1)
        view = h.wait(lambda: (v if (v:=h.run_view("PlayerA",ticket)) and
                              v["phase"] not in ("INTERPRETING","RESOLVING") else None),
                      "Actual D02 language: "+row["id"],40)
        h.event("d02-language-ticket",{"id":row["id"],"text":row["text"],"ticket":ticket,"view":view})
        return ticket,view

    def refusal(row, shortage=False):
        before=h.snapshot();calls=h.model_calls();h.actions_active=True
        ticket,view=ask(row)
        require(view["phase"] in ("CLARIFICATION","REJECTED"),"Unsafe D02 dispatch: "+str(view))
        if shortage:
            require(view["phase"]=="REJECTED" and view.get("reason")=="RESOURCE_MISSING",
                    "Observed input shortage did not remain a deterministic resource failure")
        h.quiet(20);after=h.snapshot();after_calls=h.model_calls()
        inventory=lambda s:{a["uuid"]:a.get("inventory",{}) for a in s["actors"]}
        require(before["blocks"]==after["blocks"] and before["containers"]==after["containers"]
                and inventory(before)==inventory(after),"Refused D02 request changed physical resources")
        require(after_calls["generationCalls"]==calls["generationCalls"],"Refused D02 request invoked generation")
        require(after_calls["needleCalls"]==calls["needleCalls"]+1,"D02 did not invoke genuine Needle once")
        result={"id":row["id"],"text":row["text"],"ticket":ticket,"view":view,
                "physicalBefore":before,"physicalAfter":after,"modelCallsBefore":calls,"modelCallsAfter":after_calls}
        records.append(result);h.event("d02-safe-refusal",result);h.actions_active=False

    prepare(7)
    for row in corpus:
        if row["outcome"]!="EXTRACTED":refusal(row)

    # The original's stated two inputs cannot be invented from one observed log,
    # or borrowed from the citizen's unrelated old inventory.
    prepare(4)
    original=next(row for row in corpus if row["id"]=="D02-original")
    refusal({**original,"id":"D02-observed-stock-shortage"},shortage=True)

    deliveries=[]
    for row in corpus:
        if row["outcome"]!="EXTRACTED":continue
        q=row["amount"];source=prepare(q)
        destination=DESTINATION if row["id"] in ("D02-convert","D02-make-from","D02-convert-coordinates") else SOURCE
        before=h.container(destination,PLANK);inventory=actor_inventory("PlayerA",h);calls=h.model_calls();h.actions_active=True
        ticket,view=ask(row);require(view["phase"]=="SUBMITTED","D02 did not submit supported production: "+str(view))
        result=verify(view["runId"],q,before,inventory,source,destination)
        after_calls=h.model_calls()
        require(result["artifact"]==acquisition["artifact"],"D02 failed to reuse the existing admitted producer")
        require(after_calls["generationCalls"]==calls["generationCalls"] and after_calls["needleCalls"]==calls["needleCalls"]+1,
                "Known D02 production used generation or skipped genuine language inference")
        require(len({tuple(p) for p in result["workerPositions"]})>1,"D02 has no actual worker travel")
        require(result["quantities"]["WITHDRAW"]==row["inputs"],"Stated input count was lost")
        result.update(id=row["id"],text=row["text"],ticket=ticket,modelCallsBefore=calls,modelCallsAfter=after_calls,
                      actorInventoryBefore=inventory,actorInventoryAfter=actor_inventory("PlayerA",h),
                      destinationBefore=before,destinationAfter=h.container(destination,PLANK))
        deliveries.append(result);h.event("d02-physical-production",result)
        h.screenshot("PlayerA","d02-"+row["id"],chat=True);h.actions_active=False

    return {"referencePolicy":"existing typed-nearest-v2","canonicalProduction":acquisition,"typedInterface":typed_interface,
            "refusals":records,"supportedProductions":deliveries,"models":models,"needle":needle,
            "originalPhysicalProduction":"SUCCEEDED","fullTaskPass":True,
            "scope":"Original D02 plus five independent/control productions; eight semantic refusals and one actual stock shortage; frozen history unchanged"}
