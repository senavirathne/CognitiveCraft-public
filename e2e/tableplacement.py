"""GP-RB-06B: native supplied-item placement, owned facility graphs and abrupt same-save recovery."""
from __future__ import annotations
import json,re,time,urllib.request
SRC=[5,201,5];DST=[9,201,7];LEARN_TABLE=[7,201,9];LEARN_SITE=[11,201,9]
SECOND=[15,201,9];SECOND_DEST=[17,201,7];SITE=[15,201,11];NEXT=[16,201,11];RACE=[17,201,11];WARM=[18,201,11]
OAK_MIN=[14,201,3];OAK_MAX=[17,201,3]
LOG="minecraft:oak_log";PLANK="minecraft:oak_planks";STICK="minecraft:stick";TABLE="minecraft:crafting_table";AXE="minecraft:wooden_axe"

def scenario(h):
    from suite import UUID_PATTERN,DENIED_PATTERN,require,read,reconnect_players
    require(h.topology=="compose","Facility acceptance requires configured backend and real network clients")
    h.env.update(COGNITIVECRAFT_OLLAMA_MODEL="qwen3:4b-instruct-2507-q4_K_M",COGNITIVECRAFT_NEEDLE_DIR="",COGNITIVECRAFT_E2E_SNAPSHOT_TICKS="1")
    h.compose("up","-d","ollama");h.compose("exec","-T","ollama","ollama","pull",h.env["COGNITIVECRAFT_OLLAMA_MODEL"])
    def tags():
        try:return json.load(urllib.request.urlopen("http://127.0.0.1:11434/api/tags",timeout=5))
        except OSError:return None
    models=h.wait(tags,"Actual placement generation backend",60)
    req=urllib.request.Request("http://127.0.0.1:11434/api/generate",data=json.dumps({"model":h.env["COGNITIVECRAFT_OLLAMA_MODEL"],"stream":False,"keep_alive":"30m"}).encode(),headers={"Content-Type":"application/json"})
    with urllib.request.urlopen(req,timeout=120) as response:require(json.load(response)["done"],"Actual backend prewarm failed")
    h.fresh(two=True);h.enroll();h.enroll("PlayerB","Mira");h.release_gate();h.release_gate("PlayerB")
    def coord(at):return " ".join(map(str,at))
    h.console(["setblock "+coord(b)+" minecraft:chest" for b in (SRC,SECOND)]+["setblock "+coord(LEARN_TABLE)+" minecraft:crafting_table"]+["setblock "+coord(p)+" minecraft:air" for p in (LEARN_SITE,SITE,NEXT,RACE,WARM)])
    def seed(p=0,s=0,t=0,l=0,source=SRC):
        pairs=((LOG,l),(PLANK,p),(STICK,s),(TABLE,t))
        stacks=[f'{{Slot:{i}b,id:"{item}",count:{q}}}' for i,(item,q) in enumerate(pairs) if q]
        h.console(["data merge block "+coord(source)+" {Items:["+",".join(stacks)+"]}"])
        h.wait(lambda:all(h.container(source,item)==q for item,q in pairs),"Actual native placement input stock",20)
    def logs():h.console([f"setblock {x} 201 3 minecraft:oak_log" for x in range(14,18)])
    def block(at):return h.snapshot()["blocks"][",".join(map(str,at))]
    def inv(player):return next(a["inventory"] for a in h.snapshot()["actors"] if a["uuid"]==h.entities[player]).copy()
    def run(command,player="PlayerA"):
        answer=h.command(player,command,"run="+UUID_PATTERN);require("accepted=true" in answer,"Independent method request refused: "+answer)
        h.actions_active=True;return re.search("run=("+UUID_PATTERN+")",answer).group(1)
    def place(source,target,player="PlayerA"):return run("/aivillage kernel place-table "+h.citizens[player]+" "+coord(source+target),player)
    def complete_placement(rid,source,target,before,before_inv,player="PlayerA",admitted=False):
        view=h.terminal(player,rid,650 if admitted else 180);require(view.get("marker",{}).get("outcome")==("ADMITTED" if admitted else "SUCCEEDED"),"Actual placement failed: "+view["description"])
        rows=view.get("resourceReceipts",[]);require([r["stage"] for r in rows]==["WITHDRAW","PLACE"] and all(r["quantity"]==1 and r["item"]["id"]==TABLE and r.get("placement") for r in rows),"Missing attributable consumed-item placement lineage")
        require(h.container(source,TABLE)==before-1 and inv(player).get(TABLE,0)==before_inv.get(TABLE,0) and "minecraft:crafting_table" in block(target),"Actual item/block conservation failed")
        require(rows[0]["batchId"]==rows[1]["batchId"] and rows[0]["placement"]["custodyAfter"]==rows[1]["placement"]["custodyBefore"],"Placement custody chain changed")
        require([rows[1]["placement"]["target"][k] for k in ("x","y","z")]==target and rows[1]["placement"]["facilityIdentity"],"Placement did not identify the exact approved current facility")
        h.actions_active=False;return {"run":rid,"artifact":view["marker"]["artifactSha256"],"view":view,"source":source,"target":target}
    hashes={};acquisitions=[];h.command("PlayerA","/aivillage kernel inference true","admission=true")
    logs();seed()
    learning=[("cognitivecraft:deliver_oak_logs",lambda:"/aivillage kernel gather-oak "+h.citizens["PlayerA"]+" 1 "+coord(OAK_MIN+OAK_MAX+SRC)),
              ("cognitivecraft:produce_oak_planks",lambda:"/aivillage kernel make-planks "+h.citizens["PlayerA"]+" 4 "+coord(SRC+DST)),
              ("cognitivecraft:produce_sticks",lambda:"/aivillage kernel make-sticks "+h.citizens["PlayerA"]+" 4 "+coord(SRC+DST)),
              ("cognitivecraft:produce_crafting_tables",lambda:"/aivillage kernel make-tables "+h.citizens["PlayerA"]+" 1 "+coord(SRC+DST)),
              ("cognitivecraft:place_crafting_table",lambda:"/aivillage kernel place-table "+h.citizens["PlayerA"]+" "+coord(SRC+LEARN_SITE)),
              ("cognitivecraft:produce_wooden_axes",lambda:"/aivillage kernel make-axes "+h.citizens["PlayerA"]+" 1 "+coord(SRC+DST+LEARN_TABLE))]
    for i,(cap,command) in enumerate(learning):
        if i==1:seed(l=1)
        elif i==2:seed(p=2)
        elif i==3:seed(p=4)
        elif i==4:seed(t=1)
        elif i==5:seed(p=3,s=2)
        before=h.model_calls();old=h.container(SRC,TABLE);old_inv=inv("PlayerA");rid=run(command())
        if i==4:
            direct=complete_placement(rid,SRC,LEARN_SITE,old,old_inv,admitted=True);view=direct["view"]
        else:
            view=h.terminal("PlayerA",rid,650);require(view.get("marker",{}).get("outcome")=="ADMITTED","Independent actual admission failed: "+view["description"]);h.actions_active=False
        require(view.get("resourceReceipts") and view["responsibility"]["state"]=="SUCCEEDED" and not view["responsibility"]["children"],"Independent method borrowed a producer")
        require(h.model_calls()["generationCalls"]>before["generationCalls"],"Genuine independent method was not measured")
        hashes[cap]=view["marker"]["artifactSha256"];acquisitions.append(view)
    h.command("PlayerA","/aivillage kernel status "+direct["run"],"ADMITTED");h.screenshot("PlayerA","rb006b-genuine-placement",chat=True)
    h.command("PlayerA","/aivillage kernel inference false","admission=false")
    # A new source, exact approved site and existing physical item need only the placement method.
    seed(t=1,source=SECOND);before=inv("PlayerA");calls=h.model_calls();placed=complete_placement(place(SECOND,SITE),SECOND,SITE,1,before)
    require(placed["artifact"]==hashes["cognitivecraft:place_crafting_table"] and h.model_calls()==calls,"Known fresh placement used inference")
    h.screenshot("PlayerA","rb006b-fresh-approved-table",chat=True)
    def submit_goal(target,player="PlayerA"):
        command="/aivillage kernel provision-axe "+h.citizens[player]+" "+coord(SECOND+SECOND_DEST+target+OAK_MIN+OAK_MAX)
        answer=h.command(player,command,"job="+UUID_PATTERN)
        if "accepted=false reason=BUDGET_EXHAUSTED" in answer:
            def ack():
                nonlocal answer
                answer=h.command(player,command,"job="+UUID_PATTERN)
                return answer if "accepted=false reason=BUDGET_EXHAUSTED" not in answer else None
            h.wait(ack,"Actual facility root publication acknowledgement",10)
        require("accepted=true" in answer,"Facility goal refused: "+answer);h.actions_active=True;return re.search("job=("+UUID_PATTERN+")",answer).group(1)
    def graph(jid,player):
        root=h.job_view(player,jid)
        if not root:return None
        found=[];queue=[root];seen=set()
        while queue:
            j=queue.pop(0)
            if j["id"] in seen:continue
            seen.add(j["id"]);found.append(j)
            for child in j["children"]:
                item=h.job_view(player,child)
                if not item:return None
                queue.append(item)
        return found
    def finish_goal(jid,target,before,expect_tables,expect_placements,expect_logs,player="PlayerA",surplus=(0,0)):
        def result():
            rows=graph(jid,player)
            if not rows:return None
            for j in rows:
                if j["state"] in ("FAILED","CANCELLED","INTERRUPTED"):raise AssertionError("Actual facility prerequisite stopped: "+json.dumps(j))
            return rows if rows[0]["state"]=="SUCCEEDED" else None
        try:
            rows=h.wait(result,"Actual owned table/placement/axe Jobs",240)
        except TimeoutError:
            # Read-only snapshot: retain the original failure, but expose the
            # exact unfinished dependency and item custody at the timeout.
            try:
                current=graph(jid,player) or []
                state=h.snapshot()
                compact=[]
                for job in current:
                    compact.append({
                        "id":job["id"],"state":job["state"],
                        "reason":job.get("reason"),"fulfilled":job.get("fulfilled"),
                        "capability":job["request"]["request"]["capability"]["name"],
                        "dependencies":job.get("dependencies",[]),
                        "attempts":[{
                            "terminal":a.get("terminal"),
                            "usage":a.get("usage"),
                            "stages":[(r["stage"],r["item"]["id"],r["quantity"])
                                      for r in a.get("resourceReceipts",[])]
                        } for a in job.get("attempts",[])]
                    })
                traces=[line for line in h.server_log().splitlines()
                        if "CognitiveCraft MOVE" in line or "_EFFECT" in line]
                diagnostic={"job":jid,"player":player,"site":target,
                            "gameTick":state.get("gameTick"),
                            "actors":state.get("actors"),"containers":state.get("containers"),
                            "jobs":compact,"movementTrace":traces[-24:]}
                print("RB006B_TIMEOUT_DIAGNOSTIC "+json.dumps(diagnostic,sort_keys=True,default=str),flush=True)
            except Exception as observation_error:
                print("RB006B_TIMEOUT_DIAGNOSTIC_UNAVAILABLE "+
                      repr(observation_error),flush=True)
            raise
        root=rows[0]
        require(root["fulfilled"]==1 and h.container(SECOND_DEST,AXE)>=before+1 and "minecraft:crafting_table" in block(target),"ENSURE did not leave a real facility and new axe")
        count=lambda cap:sum(j["request"]["request"]["capability"]["name"]==cap for j in rows)
        require(count("cognitivecraft:produce_crafting_tables")==expect_tables and count("cognitivecraft:place_crafting_table")==expect_placements,"Unnecessary table production or duplicate placement")
        harvested=sum(r["quantity"] for j in rows for a in j["attempts"] for r in a.get("resourceReceipts",[]) if r["stage"]=="HARVEST")
        require(harvested==expect_logs,"Facility/tool costs did not conserve real logs")
        for j in rows:require(j["preparation"]["method"]["sha256"]==hashes[j["request"]["request"]["capability"]["name"]],"An independent method changed during provisioning")
        if surplus is not None:require((h.container(SECOND,PLANK),h.container(SECOND,STICK))==surplus,"Incorrect physical facility/axe surplus")
        h.actions_active=False;return {"job":jid,"root":root,"graph":rows,"harvested":harvested,"tableJobs":expect_tables,"placements":expect_placements,"target":target}
    seed(p=3,s=2,source=SECOND);calls=h.model_calls();before=h.container(SECOND_DEST,AXE)
    existing=finish_goal(submit_goal(SITE),SITE,before,0,0,0);require(h.model_calls()==calls,"Existing-facility graph used inference")
    seed(p=3,s=2,t=1,source=SECOND);before=h.container(SECOND_DEST,AXE);calls=h.model_calls()
    supplied=finish_goal(submit_goal(NEXT),NEXT,before,0,1,0);require(h.model_calls()==calls and h.container(SECOND,TABLE)==0,"Supplied item was not consumed exactly once")
    h.console(["setblock "+coord(NEXT)+" minecraft:air"]);seed(source=SECOND);logs();before=h.container(SECOND_DEST,AXE);calls=h.model_calls()
    empty=finish_goal(submit_goal(NEXT),NEXT,before,1,1,3,surplus=(3,2));require(h.model_calls()==calls,"Known empty-stock facility graph invoked inference")
    h.command("PlayerA","/aivillage kernel job-status "+empty["job"],"state=SUCCEEDED");h.screenshot("PlayerA","rb006b-three-logs-table-and-axe",chat=True)
    foreign=h.command("PlayerB","/aivillage kernel job-status "+empty["job"],DENIED_PATTERN);h.command("PlayerB","/aivillage kernel job-cancel "+empty["job"],DENIED_PATTERN)
    require(empty["job"] not in h.suggestions("PlayerB","/aivillage kernel job-status "),"Foreign facility root appeared in suggestions");h.screenshot("PlayerB","rb006b-private-denial",chat=True)
    # Two original scopes request one missing approved facility. Only one placement may occur.
    seed(p=6,s=4,t=2,source=SECOND);before=h.container(SECOND_DEST,AXE);calls=h.model_calls();first=submit_goal(RACE);second=submit_goal(RACE,"PlayerB")
    def stopped_before_axe(rows):
        root=rows[0];attempts=root["attempts"]
        return (root["state"]=="WAITING" and root.get("reason")=="TARGET_UNAVAILABLE" and root["fulfilled"]==0
                and root["dependencies"] and attempts and all(j["state"]=="SUCCEEDED" for j in rows[1:])
                and any(j["request"]["request"]["capability"]["name"]=="cognitivecraft:place_crafting_table" for j in rows[1:])
                and all((a.get("terminal") or {}).get("status")=="BLOCKED" and a["terminal"].get("reason")=="TARGET_UNAVAILABLE"
                        and not a["effects"] and not a.get("resourceReceipts") and not a.get("receipts") and not a.get("uncertain") for a in attempts))
    def both():
        a=graph(first,"PlayerA");b=graph(second,"PlayerB")
        if not a or not b:return None
        if any(j["state"] in ("FAILED","CANCELLED","INTERRUPTED") for j in a+b):raise AssertionError("Competing facility roots failed: "+json.dumps(a+b))
        return (a,b) if (any(rows[0]["state"]=="SUCCEEDED" for rows in (a,b))
                        and all(rows[0]["state"]=="SUCCEEDED" or stopped_before_axe(rows) for rows in (a,b))) else None
    race=h.wait(both,"Two original scopes share one facility or report an explicit zero-effect workspace blocker",240)
    place_receipts=[r for rows in race for j in rows for a in j["attempts"] for r in a.get("resourceReceipts",[]) if r["stage"]=="PLACE"]
    completed_axes=sum(rows[0]["state"]=="SUCCEEDED" for rows in race)
    require(len(place_receipts)==1 and h.container(SECOND,TABLE)==1 and h.container(SECOND_DEST,AXE)==before+completed_axes and h.model_calls()==calls,"Competing scopes placed or consumed twice")
    require((h.container(SECOND,PLANK),h.container(SECOND,STICK))==(6-3*completed_axes,4-2*completed_axes),"Competing scopes lost or duplicated recipe inputs")
    race_cancellations=[]
    for player,rows in zip(("PlayerA","PlayerB"),race):
        if rows[0]["state"]=="SUCCEEDED":continue
        require(stopped_before_axe(rows),"Unclassified competing-root result")
        jid=rows[0]["id"];h.cancel_job(player,jid)
        stopped=h.wait(lambda:(j if (j:=h.job_view(player,jid)) and j["state"]=="CANCELLED" else None),"Owner cancels the zero-effect blocked axe root",60)
        require(stopped["fulfilled"]==0 and all(not a["effects"] and not a.get("resourceReceipts") for a in stopped["attempts"]),"Cancelled contention root acquired axe credit or effects")
        race_cancellations.append({"player":player,"job":jid,"view":stopped})
    race_stock=h.snapshot()["containers"];race_block=block(RACE);h.quiet(80)
    require(h.snapshot()["containers"]==race_stock and block(RACE)==race_block and "minecraft:crafting_table" in race_block,"Contention cancellation changed completed placement or retained stock")
    h.actions_active=False
    # Cancellation retains actual partial effects and prevents later work; it never compensates items.
    h.console(["setblock "+coord(NEXT)+" minecraft:air"]);seed(p=3,s=2,t=1,source=SECOND);cancel_id=submit_goal(NEXT);h.cancel_job("PlayerA",cancel_id)
    cancelled=h.wait(lambda:(j if (j:=h.job_view("PlayerA",cancel_id)) and j["state"]=="CANCELLED" else None),"Actual facility cancellation",60)
    conserved=h.snapshot()["containers"];state=block(NEXT);h.quiet(80);require(h.snapshot()["containers"]==conserved and block(NEXT)==state,"Cancelled facility graph continued effects");h.actions_active=False
    # Stop the real backend, reopen the same release, then use a fresh citizen and approved site.
    cold=read(h.root/"server/process.json");h.compose("stop","ollama");require(tags() is None,"Actual generation backend still responds")
    h.env["COGNITIVECRAFT_OLLAMA_MODEL"]="";h.stop("server");h.start("server");reconnect_players(h,["PlayerA","PlayerB"]);warm=read(h.root/"server/process.json")
    require(warm["start"]>cold["start"] and warm["releaseSha256"]==cold["releaseSha256"],"Offline facility reuse did not reopen the same release in a new JVM")
    seed(source=SECOND);logs();before=h.container(SECOND_DEST,AXE);calls=h.model_calls()
    offline=finish_goal(submit_goal(WARM,"PlayerB"),WARM,before,1,1,3,"PlayerB",surplus=(3,2));require(h.model_calls()==calls,"Offline fresh-worker facility graph used inference")
    h.screenshot("PlayerB","rb006b-offline-fresh-worker-facility",chat=True)
    # Complete ordinary manufacture and placement before resetting this fixture site.
    # The citizen then starts within the source/site interaction area without teleporting.
    h.console(["setblock "+coord(NEXT)+" minecraft:air"]);seed(p=8,source=SECOND)
    approach_id=run("/aivillage kernel make-tables "+h.citizens["PlayerB"]+" 2 "+coord(SECOND+SECOND),"PlayerB");approach=h.terminal("PlayerB",approach_id,180)
    require(approach["marker"]["outcome"]=="SUCCEEDED" and h.container(SECOND,TABLE)==2,"Normal approach did not produce the actual crash-fixture items");h.actions_active=False
    approach_inv=inv("PlayerB");approach_run=place(SECOND,NEXT,"PlayerB")
    placement_approach=complete_placement(approach_run,SECOND,NEXT,2,approach_inv,"PlayerB")
    h.console(["setblock "+coord(NEXT)+" minecraft:air","tick rate 1"])
    h.wait(lambda:"minecraft:air" in block(NEXT),"Reset completed approved-site fixture before slow-clock capture",20)
    # A frozen world still allows pure controller slices at normal server speed.
    # Slow the vanilla server clock instead, preserving the actual learned method,
    # all production limits and the bounded unfinished-after-PLACE requirement.
    old_inv=inv("PlayerB");start_tick=h.snapshot()["gameTick"];rid=place(SECOND,NEXT,"PlayerB");boundary=None;steps=0;deadline=time.monotonic()+300
    while steps<=512 and time.monotonic()<deadline:
        state=h.snapshot();steps=state["gameTick"]-start_tick;view=h.run_view("PlayerB",rid)
        if view and view.get("phase")=="EXECUTING" and any(r["stage"]=="PLACE" for r in view.get("resourceReceipts",[])):
            boundary=view;break
        if view and view.get("phase")=="TERMINAL":raise AssertionError("Placement boundary completed before capture: "+json.dumps(view))
        if steps==512:break
        tick=state["gameTick"];h.wait(lambda:(s if (s:=h.snapshot())["gameTick"]>tick else None),"Next actual slow-clock placement tick",15)
    require(boundary is not None and "minecraft:crafting_table" in block(NEXT) and h.container(SECOND,TABLE)==0 and inv("PlayerB").get(TABLE,0)==old_inv.get(TABLE,0),"No real consumed-item placement at bounded crash boundary")
    saved_inv=inv("PlayerB");saved_containers=h.snapshot()["containers"];saved_block=block(NEXT);checkpoint=h.save_checkpoint();h.stop("server",hard=True);killed=h.world_hashes();h.actions_active=False
    h.start("server");h.console(["tick unfreeze","tick rate 20"]);reconnect_players(h,["PlayerA","PlayerB"])
    recovered=h.wait(lambda:h.run_view("PlayerB",rid),"Same-save interrupted placed facility",30);require("INTERRUPTED" in recovered["description"],"Uncertain placement resumed after restart")
    require(inv("PlayerB")==saved_inv and h.snapshot()["containers"]==saved_containers and block(NEXT)==saved_block,"Saved actual placement was lost, duplicated or refunded")
    h.quiet(80);require(inv("PlayerB")==saved_inv and h.snapshot()["containers"]==saved_containers and block(NEXT)==saved_block,"Placement replayed after process restart")
    h.screenshot("PlayerB","rb006b-saved-placement-no-replay",chat=True)
    return {"independentAcquisitions":acquisitions,"artifacts":hashes,"placement":placed,"existingFacility":existing,"existingItem":supplied,"emptyProvision":empty,"foreignDenied":foreign,"competingRoots":race,"raceCompletedAxes":completed_axes,"raceCancellations":race_cancellations,"raceRetainedStock":race_stock,"cancelled":cancelled,
            "offlineWarmReuse":offline,"models":models,"offlineProcessProof":{"cold":cold,"warm":warm,"sameRelease":True,"backendUnavailable":True},"crashApproach":approach,"crashPlacementApproach":placement_approach,"killBoundary":boundary,"clockMode":"vanilla-1-tps","clockSteps":steps,"checkpoint":checkpoint,"killedWorldHashes":killed,"recovered":recovered,"savedInventory":saved_inv,"savedBlock":saved_block}
