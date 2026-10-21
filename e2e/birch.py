"""GP-RB-07: independently learned exact birch methods, stock-first Jobs and real clients."""
from __future__ import annotations
import json,re,shutil,time,urllib.request

LOG="minecraft:birch_log";PLANK="minecraft:birch_planks";OAK="minecraft:oak_log"
SRC=[5,201,5];DST=[9,201,7];SECOND=[15,201,9];SECOND_DEST=[17,201,7]
LO=[6,201,14];HI=[13,201,14];OTHER_LO=[14,201,11];OTHER_HI=[21,201,11]

def inventory(h,player):
    return next(a["inventory"] for a in h.snapshot()["actors"] if a["uuid"]==h.entities[player]).copy()

def scenario(h, *, d04_evidence=False):
    from suite import ROOT,UUID_PATTERN,DENIED_PATTERN,require,read,reconnect_players
    require(h.topology=="compose","Birch acceptance requires real network clients and configured model backends")
    shutil.copytree(ROOT/"build/needle",h.root/"needle",dirs_exist_ok=True)
    h.env.update(COGNITIVECRAFT_OLLAMA_MODEL="qwen3:4b-instruct-2507-q4_K_M",COGNITIVECRAFT_NEEDLE_DIR="/evidence/needle",COGNITIVECRAFT_E2E_SNAPSHOT_TICKS="1")
    h.compose("up","-d","ollama");h.compose("exec","-T","ollama","ollama","pull",h.env["COGNITIVECRAFT_OLLAMA_MODEL"])
    def tags():
        try:return json.load(urllib.request.urlopen("http://127.0.0.1:11434/api/tags",timeout=5))
        except OSError:return None
    models=h.wait(tags,"Actual configured birch backend",60)
    req=urllib.request.Request("http://127.0.0.1:11434/api/generate",data=json.dumps({"model":h.env["COGNITIVECRAFT_OLLAMA_MODEL"],"stream":False,"keep_alive":"30m"}).encode(),headers={"Content-Type":"application/json"})
    with urllib.request.urlopen(req,timeout=120) as response:require(json.load(response)["done"],"Genuine model prewarm failed")
    h.fresh(two=True);h.enroll();h.enroll("PlayerB","Mira");h.release_gate();h.release_gate("PlayerB")
    h.console(["gamerule minecraft:block_drops true",*["setblock "+" ".join(map(str,p))+" minecraft:chest" for p in (SRC,SECOND)]])
    catalog=h.command("PlayerA","/aivillage kernel catalog","catalog=")
    require(all(k+"=[]" in catalog for k in ("birchCatalog","birchPlankCatalog","oakCatalog","plankCatalog")),"Birch or oak executable methods were supplied as known")
    hashes={};admissions=[];known=[];client_containers=[]
    def client_container(player,destination,item):
        expected=h.container(destination,item);actual=h.menu_count(player,destination,item)
        require(actual==expected,"Connected client did not receive exact birch contents")
        client_containers.append({"player":player,"destination":destination,"item":item,"expected":expected,"actual":actual})
    def logs(q,lo=LO,hi=HI):
        h.console([f"setblock {x} {lo[1]} {lo[2]} "+("minecraft:birch_log" if x<lo[0]+q else "minecraft:air") for x in range(lo[0],hi[0]+1)])
        h.wait(lambda:all("minecraft:birch_log" in h.snapshot()["blocks"].get(f"{x},{lo[1]},{lo[2]}","") for x in range(lo[0],lo[0]+q)),"Loaded exact birch source",20)
    def seed(q,source=SRC):
        values=[f'{{Slot:{i}b,id:"{item}",count:{amount}}}' for i,(item,amount) in enumerate(((LOG,q),(OAK,64),(PLANK,11))) if amount]
        h.console(["data merge block "+" ".join(map(str,source))+" {Items:["+",".join(values)+"]}"])
        h.wait(lambda:h.container(source,LOG)==q and h.container(source,OAK)==64 and h.container(source,PLANK)==11,"Actual species-specific input stock",20)
    def submit(text,player="PlayerA",prefix="run"):
        response=h.command(player,text,prefix+"="+UUID_PATTERN)
        require("accepted=true" in response,"Birch submission refused: "+response);h.actions_active=True
        return re.search(prefix+"=("+UUID_PATTERN+")",response).group(1)
    def gather(q,lo=LO,hi=HI,destination=SRC,player="PlayerA"):
        logs(q,lo,hi)
        return submit("/aivillage kernel gather-birch "+h.citizens[player]+" "+str(q)+" "+" ".join(map(str,lo+hi+destination)),player)
    def make(q,source=SRC,destination=DST,player="PlayerA"):
        return submit("/aivillage kernel make-birch-planks "+h.citizens[player]+" "+str(q)+" "+" ".join(map(str,source+destination)),player)
    def verify(rid,q,before,source,destination,player="PlayerA",acquired=False,craft=False,before_inventory=None):
        view=h.terminal(player,rid,650 if acquired else 220)
        require(view.get("marker",{}).get("outcome")==("ADMITTED" if acquired else "SUCCEEDED"),"Birch physical completion failed: "+view["description"])
        rows=view.get("resourceReceipts",[]);require(rows and not view.get("receipts"),"Birch was relabeled as crop evidence")
        stages=("WITHDRAW","CRAFT","DEPOSIT") if craft else ("HARVEST","PICKUP","DEPOSIT")
        sums={s:sum(r["quantity"] for r in rows if r["stage"]==s) for s in stages}
        batches=(q+3)//4
        require(sums==({"WITHDRAW":batches,"CRAFT":4*batches,"DEPOSIT":q} if craft else {s:q for s in stages}),"Birch causal conservation mismatch")
        cap="cognitivecraft:produce_birch_planks" if craft else "cognitivecraft:deliver_birch_logs"
        artifact=view["marker"]["artifactSha256"]
        if acquired:hashes[cap]=artifact
        else:require(artifact==hashes[cap],"Known birch bindings changed immutable method")
        for r in rows:
            require(r["item"]=={"id":LOG if r["stage"]=="WITHDRAW" or not craft else PLANK,"components":"default"},"Foreign resource/component receipt")
            require(r["actor"]["entityId"]==h.entities[player],"Changed controlled citizen in receipt")
            require([r["destination"][k] for k in ("x","y","z")]==destination,"Changed physical destination")
        item=PLANK if craft else LOG
        require(h.container(destination,item)==before+q,"Actual synchronized destination mismatch")
        if craft:
            require(h.container(source,LOG)==0 and h.container(source,OAK)==64 and h.container(source,PLANK)==11+(q if source==destination else 0),"Oak stock or old birch output substituted")
            if before_inventory is not None:require(inventory(h,player).get(PLANK,0)==before_inventory.get(PLANK,0)+4*batches-q,"Real birch surplus lost")
        require(view["responsibility"]["state"]=="SUCCEEDED" and not view["responsibility"]["dependencies"] and not view["responsibility"]["children"],"Independent method gained an orchestration dependency")
        h.actions_active=False
        return {"run":rid,"artifact":artifact,"amount":q,"source":source,"destination":destination,"actor":h.entities[player],"quantities":sums,"view":view}
    h.command("PlayerA","/aivillage kernel inference true","admission=true")
    if not d04_evidence:
        before=h.container(SRC,LOG);rid=gather(4)
        admissions.append(verify(rid,4,before,LO+HI,SRC,acquired=True));h.command("PlayerA","/aivillage kernel status "+rid,"phase=TERMINAL");h.screenshot("PlayerA","rb007-genuine-birch-harvest",chat=True)
        client_container("PlayerA",SRC,LOG)
    else:
        h.console([f'data merge entity {h.entities["PlayerA"]} {{Inventory:[{{id:"minecraft:oak_log",count:3}},{{id:"minecraft:birch_planks",count:7}}]}}'])
        h.wait(lambda:inventory(h,"PlayerA").get(PLANK)==7 and inventory(h,"PlayerA").get(OAK)==3,"D04 old NPC stock")
    seed(2);before=h.container(DST,PLANK);inv=inventory(h,"PlayerA");rid=make(5)
    admissions.append(verify(rid,5,before,SRC,DST,acquired=True,craft=True,before_inventory=inv));h.screenshot("PlayerA","rb007-genuine-birch-crafting",chat=True)
    client_container("PlayerA",DST,PLANK)
    foreign=h.command("PlayerB","/aivillage kernel status "+rid,DENIED_PATTERN)
    foreign_cancel=h.command("PlayerB","/aivillage kernel cancel "+rid,DENIED_PATTERN)
    require(rid not in h.suggestions("PlayerB","/aivillage kernel status "),"Foreign birch run UUID leaked")
    if d04_evidence:
        from d04 import language_evidence
        return language_evidence(h,seed,verify,admissions[-1],models,read(h.root/"needle/manifest.json"))
    h.command("PlayerA","/aivillage kernel inference false","admission=false")
    for q in (1,4,8,64):
        seed((q+3)//4);before=h.container(DST,PLANK);inv=inventory(h,"PlayerA");calls=h.model_calls()
        result=verify(make(q),q,before,SRC,DST,craft=True,before_inventory=inv);require(h.model_calls()==calls,"Known birch craft invoked inference");known.append(result)
    # Real language uses the same admitted methods with original explicit references.
    h.command("PlayerA","/aivillage kernel inference true","admission=true")
    language=[]
    for text,q,craft in (("Ada, gather 1 birch log from 6,201,14 through 13,201,14 and deliver it to the chest at 5,201,5.",1,False),
                         ("Ada, make 4 birch planks from the chest at 5,201,5 and store them in the chest at 9,201,7.",4,True)):
        if craft:seed(1)
        else:logs(1)
        destination=DST if craft else SRC;before=h.container(destination,PLANK if craft else LOG);inv=inventory(h,"PlayerA");calls=h.model_calls()
        answer=h.command("PlayerA","/aivillage kernel ask "+text,"interpretation="+UUID_PATTERN);ticket=re.search("interpretation=("+UUID_PATTERN+")",answer).group(1)
        bound=h.wait(lambda:(v if (v:=h.run_view("PlayerA",ticket)) and v["phase"] not in ("INTERPRETING","RESOLVING") else None),"Genuine birch language",120);require(bound.get("phase")=="SUBMITTED","Genuine birch language did not bind: "+json.dumps(bound))
        h.actions_active=True;result=verify(bound["runId"],q,before,SRC if craft else LO+HI,destination,craft=craft,before_inventory=inv)
        require(h.model_calls()["needleCalls"]>calls["needleCalls"] and h.model_calls()["generationCalls"]==calls["generationCalls"],"Language and known execution inference were conflated")
        language.append({"ticket":ticket,"binding":bound,"result":result})
    h.screenshot("PlayerA","rb007-original-birch-language",chat=True)
    h.command("PlayerA","/aivillage kernel inference false","admission=false")
    def job(jid,player="PlayerA"):return h.snapshot()["players"].get(player,{}).get("jobs",{}).get(jid)
    def compose(supplied,player="PlayerA",source=SRC,destination=DST,lo=LO,hi=HI):
        seed(supplied,source);logs(2-supplied,lo,hi)
        return submit("/aivillage kernel compose-birch-planks "+h.citizens[player]+" 8 "+" ".join(map(str,source+destination+lo+hi)),player,"job")
    def complete(jid,before,deficit,player="PlayerA",source=SRC,destination=DST):
        def finished():
            j=job(jid,player)
            if j and j["state"] in ("FAILED","CANCELLED","INTERRUPTED"):raise AssertionError("Birch graph stopped: "+json.dumps(j))
            return j if j and j["state"]=="SUCCEEDED" else None
        root=h.wait(finished,"Actual birch prerequisite graph",240);state=h.snapshot()["players"][player]["jobs"];graph=[root,*[state[c] for c in root["children"]]]
        require(root["fulfilled"]==8 and len(root["children"])==(1 if deficit else 0),"Incorrect birch dependency graph")
        for node in graph:
            cap=node["request"]["request"]["capability"]["name"]
            require(cap in hashes and node["preparation"]["method"]["sha256"]==hashes[cap] and node["state"]=="SUCCEEDED","Graph substituted a foreign or unadmitted method")
        harvested=sum(r["quantity"] for n in graph for a in n["attempts"] for r in a.get("resourceReceipts",[]) if r["stage"]=="HARVEST")
        require(harvested==deficit and h.container(destination,PLANK)==before+8 and h.container(source,LOG)==0 and h.container(source,OAK)==64 and h.container(source,PLANK)==11,"Birch graph actual deficit/conservation mismatch")
        h.actions_active=False;return {"job":jid,"root":root,"graph":graph,"harvested":harvested,"source":source,"destination":destination}
    graphs=[]
    for supplied in (2,1,0):
        before=h.container(DST,PLANK);calls=h.model_calls();jid=compose(supplied);graphs.append(complete(jid,before,2-supplied));require(h.model_calls()==calls,"Known birch graph used a model")
    h.command("PlayerA","/aivillage kernel job-status "+jid,"state=SUCCEEDED");h.screenshot("PlayerA","rb007-exact-shortfall-job-graph",chat=True)
    foreign_job=h.command("PlayerB","/aivillage kernel job-status "+jid,DENIED_PATTERN)
    foreign_job_cancel=h.command("PlayerB","/aivillage kernel job-cancel "+jid,DENIED_PATTERN)
    require(jid not in h.suggestions("PlayerB","/aivillage kernel job-status "),"Foreign birch Job UUID leaked")
    # Stop the actual generator and reopen the same save in an independent JVM.
    h.compose("stop","ollama");require(tags() is None,"Actual external backend still running")
    cold=read(h.root/"server/process.json");h.env["COGNITIVECRAFT_OLLAMA_MODEL"]="";h.env["COGNITIVECRAFT_NEEDLE_DIR"]=""
    h.stop("server");h.start("server");reconnect_players(h,["PlayerA","PlayerB"]);warm=read(h.root/"server/process.json")
    require(warm["start"]>cold["start"] and warm["releaseSha256"]==cold["releaseSha256"],"Offline reuse did not use a separate JVM and the same production release")
    offline=[];calls=h.model_calls();before=h.container(SECOND,LOG)
    offline.append(verify(gather(7,OTHER_LO,OTHER_HI,SECOND,"PlayerB"),7,before,OTHER_LO+OTHER_HI,SECOND,"PlayerB"))
    client_container("PlayerB",SECOND,LOG)
    seed(1,SECOND);before=h.container(SECOND_DEST,PLANK);inv=inventory(h,"PlayerB")
    offline.append(verify(make(4,SECOND,SECOND_DEST,"PlayerB"),4,before,SECOND,SECOND_DEST,"PlayerB",craft=True,before_inventory=inv))
    client_container("PlayerB",SECOND_DEST,PLANK)
    before=h.container(SECOND_DEST,PLANK);offline_graph=complete(compose(0,"PlayerB",SECOND,SECOND_DEST,OTHER_LO,OTHER_HI),before,2,"PlayerB",SECOND,SECOND_DEST)
    require(h.model_calls()==calls,"Backend-stopped reuse invoked inference");h.screenshot("PlayerB","rb007-offline-fresh-citizen-and-bindings",chat=True)
    # Cancellation keeps the real first harvest and fences subsequent effects.
    rid=gather(8);boundary=h.wait(lambda:(v if (v:=h.run_view("PlayerA",rid)) and any(r["stage"]=="HARVEST" for r in v.get("resourceReceipts",[])) else None),"Actual birch cancellation boundary",90)
    h.command("PlayerA","/aivillage kernel cancel "+rid,"run="+rid);cancelled=h.terminal("PlayerA",rid,90);h.actions_active=False
    require("CANCELLED" in cancelled["description"],"Birch cancellation did not terminate")
    conserved=h.container(SRC,LOG);h.quiet(60);require(h.container(SRC,LOG)==conserved,"Late cancelled birch work deposited output")
    # Bring the worker to staging by ordinary known crafting before the exact craft/save kill boundary.
    seed(1);before=h.container(SRC,PLANK);inv=inventory(h,"PlayerA");approach=verify(make(4,SRC,SRC),4,before,SRC,SRC,craft=True,before_inventory=inv)
    seed(16);h.console(["tick rate 20","tick freeze"]);before_inventory=inventory(h,"PlayerA");rid=make(64,SRC,SECOND_DEST)
    kill=None;deadline=time.monotonic()+300;steps=0
    while steps<=512 and time.monotonic()<deadline:
        view=h.run_view("PlayerA",rid)
        if view and view.get("phase")=="EXECUTING" and any(r["stage"]=="CRAFT" for r in view.get("resourceReceipts",[])):kill=view;break
        if view and view.get("phase")=="TERMINAL":raise AssertionError("Birch crash fixture stopped early: "+json.dumps(view))
        if steps==512:break
        tick=h.snapshot()["gameTick"];h.console(["tick step 1"],lifecycle=True)
        observed=h.wait(lambda:(s if (s:=h.snapshot())["gameTick"]>tick else None),"One vanilla birch craft-boundary tick",15);require(observed["gameTick"]==tick+1,"Craft boundary advanced more than one tick");steps+=1
    require(kill is not None,"Bounded birch craft boundary missed")
    saved_inventory=inventory(h,"PlayerA");saved_containers=h.snapshot()["containers"]
    require(saved_inventory.get(PLANK,0)==before_inventory.get(PLANK,0)+4,"Actual four-plank batch absent from saved custody")
    checkpoint=h.save_checkpoint();h.stop("server",hard=True);killed=h.world_hashes();h.actions_active=False
    h.start("server");h.console(["tick unfreeze","tick rate 20"]);reconnect_players(h,["PlayerA","PlayerB"])
    recovered=h.wait(lambda:h.run_view("PlayerA",rid),"Interrupted same-save birch attempt",30)
    require("INTERRUPTED" in recovered["description"],"Uncertain birch craft resumed")
    require(inventory(h,"PlayerA")==saved_inventory and h.snapshot()["containers"]==saved_containers,"Acknowledged birch save changed physical custody")
    h.quiet(80);require(inventory(h,"PlayerA")==saved_inventory and h.snapshot()["containers"]==saved_containers,"Restart replayed birch manufacture or deposit")
    h.screenshot("PlayerA","rb007-saved-birch-custody-no-replay",chat=True)
    return {"independentAdmissions":admissions,"artifacts":hashes,"knownBindings":known,"actualLanguage":language,"composition":graphs,"foreignDenied":foreign,"foreignCancelDenied":foreign_cancel,"foreignJobDenied":foreign_job,"foreignJobCancelDenied":foreign_job_cancel,"clientContainers":client_containers,"models":models,
            "offlineWarmReuse":offline,"offlineGraph":offline_graph,"offlineProcessProof":{"cold":cold,"warm":warm,"sameRelease":True,"backendUnavailable":True},"cancelBoundary":boundary,"cancelled":cancelled,
            "crashApproach":approach,"killBoundary":kill,"clockSteps":steps,"checkpoint":checkpoint,"killedWorldHashes":killed,"recovered":recovered,"savedInventory":saved_inventory}
