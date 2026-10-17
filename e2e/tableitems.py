"""GP-RB-06A: actual learned table-item manufacture, clients and saved craft custody."""
from __future__ import annotations
import json,re,shutil,time,urllib.request

PLANK="minecraft:oak_planks";TABLE="minecraft:crafting_table"
SOURCE=[5,201,5];DEST=[9,201,7];SECOND=[15,201,9];SECOND_DEST=[17,201,7]

def inventory(h,player):
    return next(a["inventory"] for a in h.snapshot()["actors"] if a["uuid"]==h.entities[player]).copy()

def scenario(h):
    from suite import ROOT,UUID_PATTERN,DENIED_PATTERN,require,read,reconnect_players
    require(h.topology=="compose","Table production requires actual configured backends and network clients")
    private=h.root/"needle";shutil.copytree(ROOT/"build/needle",private,dirs_exist_ok=True)
    h.env.update(COGNITIVECRAFT_OLLAMA_MODEL="qwen3:4b-instruct-2507-q4_K_M",COGNITIVECRAFT_NEEDLE_DIR="/evidence/needle",COGNITIVECRAFT_E2E_SNAPSHOT_TICKS="1")
    h.compose("up","-d","ollama");h.compose("exec","-T","ollama","ollama","pull",h.env["COGNITIVECRAFT_OLLAMA_MODEL"])
    def tags():
        try:return json.load(urllib.request.urlopen("http://127.0.0.1:11434/api/tags",timeout=5))
        except OSError:return None
    models=h.wait(tags,"Actual table-production backend",60)
    req=urllib.request.Request("http://127.0.0.1:11434/api/generate",data=json.dumps({"model":h.env["COGNITIVECRAFT_OLLAMA_MODEL"],"stream":False,"keep_alive":"30m"}).encode(),headers={"Content-Type":"application/json"})
    with urllib.request.urlopen(req,timeout=120) as response:require(json.load(response)["done"],"Actual backend prewarm failed")
    h.fresh(two=True);h.enroll();h.enroll("PlayerB","Mira");h.release_gate();h.release_gate("PlayerB")
    h.console(["setblock "+" ".join(map(str,p))+" minecraft:chest" for p in (SOURCE,SECOND)])
    existing=[at for at,block in h.snapshot()["blocks"].items() if "minecraft:crafting_table" in block]
    if existing:h.console(["setblock "+at.replace(","," ")+" minecraft:air" for at in existing])
    h.wait(lambda:not any("minecraft:crafting_table" in b for b in h.snapshot()["blocks"].values()),"Bootstrap fixture without any table facility",20)
    catalog=h.command("PlayerA","/aivillage kernel catalog","catalog=")
    require(all(k+"=[]" in catalog for k in ("tableItemCatalog","oakCatalog","plankCatalog","axeCatalog")),"Table method or its producer/consumer was supplied as known")
    h.command("PlayerA","/aivillage kernel inference true","admission=true")
    def seed(q,source=SOURCE):
        require(1<=q<=4,"Bounded table fixture")
        old=h.container(source,TABLE)
        h.console(['data merge block '+" ".join(map(str,source))+' {Items:[{Slot:0b,id:"'+PLANK+'",count:'+str(4*q)+'}'+(',{Slot:1b,id:"'+TABLE+'",count:'+str(old)+'}' if old else '')+']}'])
        h.wait(lambda:h.container(source,PLANK)==4*q,"Real supplied table inputs",20)
    def submit(q,source=SOURCE,destination=DEST,player="PlayerA"):
        text="/aivillage kernel make-tables "+h.citizens[player]+" "+str(q)+" "+" ".join(map(str,source+destination))
        answer=h.command(player,text,"run="+UUID_PATTERN);require("accepted=true" in answer,"Table request rejected: "+answer)
        h.actions_active=True;return re.search("run=("+UUID_PATTERN+")",answer).group(1)
    def verify(rid,q,before,before_inventory,source,destination,player="PlayerA",admitted=False):
        view=h.terminal(player,rid,650 if admitted else 180)
        require(view.get("marker",{}).get("outcome")==("ADMITTED" if admitted else "SUCCEEDED"),"Actual table production failed: "+view["description"])
        rows=view.get("resourceReceipts",[])
        sums={s:sum(r["quantity"] for r in rows if r["stage"]==s) for s in ("WITHDRAW","CRAFT","DEPOSIT")}
        require(sums=={"WITHDRAW":4*q,"CRAFT":q,"DEPOSIT":q} and len(rows)==3*q,"Table manufacture lacks exact input/output lineage")
        require(h.container(source,PLANK)==0 and h.container(destination,TABLE)==before+q,"Actual table container conservation mismatch")
        require(inventory(h,player).get(TABLE,0)==before_inventory.get(TABLE,0) and inventory(h,player).get(PLANK,0)==before_inventory.get(PLANK,0),"Old actor items were credited or erased")
        require(all(r.get("transformation",{}).get("recipe",{}).get("key")==TABLE and r["transformation"]["recipe"]["inputs"]==4 and r["transformation"]["recipe"]["outputs"]==1 and r["transformation"].get("facility") is None for r in rows),"Wrong recipe or facility dependency")
        responsibility=view["responsibility"]
        require(responsibility["state"]=="SUCCEEDED" and not responsibility["children"] and not responsibility["dependencies"],"Stocked method required producer Jobs")
        require(not any("minecraft:crafting_table" in b for b in h.snapshot()["blocks"].values()),"Item manufacture placed a world facility")
        h.actions_active=False
        return {"run":rid,"artifact":view["marker"]["artifactSha256"],"quantity":q,"source":source,"destination":destination,"sums":sums,"receipts":rows,"responsibility":responsibility}
    seed(1);before=h.container(DEST,TABLE);before_inv=inventory(h,"PlayerA");calls=h.model_calls()
    acquired=verify(submit(1),1,before,before_inv,SOURCE,DEST,admitted=True)
    require(h.model_calls()["generationCalls"]>calls["generationCalls"],"Genuine table acquisition was not measured")
    h.command("PlayerA","/aivillage kernel status "+acquired["run"],"ADMITTED");h.screenshot("PlayerA","rb006a-genuine-table-item",chat=True)
    foreign=h.command("PlayerB","/aivillage kernel status "+acquired["run"],DENIED_PATTERN)
    h.command("PlayerB","/aivillage kernel cancel "+acquired["run"],DENIED_PATTERN)
    require(acquired["run"] not in h.suggestions("PlayerB","/aivillage kernel status "),"Foreign table Run suggestion leaked")
    h.screenshot("PlayerB","rb006a-private-denial",chat=True)
    # The original narrow phrase has omitted inputs: every bounded staging candidate
    # receives four genuine supplied planks, while prior table stock stays intact.
    boxes=[SOURCE,DEST,SECOND,SECOND_DEST,[18,201,10]]
    h.console(["item replace block "+" ".join(map(str,b))+" container.26 with minecraft:oak_planks 4" for b in boxes])
    h.wait(lambda:all(h.container(b,PLANK)>=4 for b in boxes),"Eligible inputs for original language phrase",20)
    counts={tuple(b):h.container(b,TABLE) for b in boxes};before_inv=inventory(h,"PlayerA");calls=h.model_calls();h.actions_active=True
    answer=h.command("PlayerA","/aivillage kernel ask Ada, make a crafting table and put it in the chest","interpretation="+UUID_PATTERN)
    ticket=re.search("interpretation=("+UUID_PATTERN+")",answer).group(1)
    interpreted=h.wait(lambda:(v if (v:=h.run_view("PlayerA",ticket)) and v["phase"] not in ("INTERPRETING","RESOLVING") else None),"Actual Needle table intent",100)
    require(interpreted["phase"]=="SUBMITTED","Actual table language refused: "+json.dumps(interpreted))
    view=h.terminal("PlayerA",interpreted["runId"],180);rows=view.get("resourceReceipts",[]);require(rows,"Language produced no real receipt")
    area=rows[0]["source"];source=[area[k] for k in ("minX","minY","minZ")];destination=[rows[-1]["destination"][k] for k in ("x","y","z")]
    # All table inputs are stackable and exact; leave any unconsumed old supplied
    # planks accounted for rather than using the independent zero-stock assertion.
    require(view.get("marker",{}).get("outcome")=="SUCCEEDED" and view["marker"]["artifactSha256"]==acquired["artifact"],"Language did not reuse the admitted independent method")
    require(h.container(destination,TABLE)==counts[tuple(destination)]+1 and sum(r["quantity"] for r in rows if r["stage"]=="WITHDRAW")==4,"Language item outcome/inputs mismatch")
    require(h.model_calls()["needleCalls"]>calls["needleCalls"] and h.model_calls()["generationCalls"]==calls["generationCalls"],"Language interpretation and known execution were mixed")
    language={"ticket":ticket,"source":source,"destination":destination,"view":view};h.actions_active=False;h.screenshot("PlayerA","rb006a-original-language",chat=True)
    h.command("PlayerA","/aivillage kernel inference false","admission=false")
    seed(4,SECOND);before=h.container(SECOND_DEST,TABLE);before_inv=inventory(h,"PlayerA");calls=h.model_calls()
    known=verify(submit(4,SECOND,SECOND_DEST),4,before,before_inv,SECOND,SECOND_DEST)
    require(known["artifact"]==acquired["artifact"] and h.model_calls()==calls,"Known fresh table bindings used inference")
    h.compose("stop","ollama");require(tags() is None,"Actual generation backend still available")
    cold=read(h.root/"server/process.json");h.env["COGNITIVECRAFT_OLLAMA_MODEL"]="";h.env["COGNITIVECRAFT_NEEDLE_DIR"]=""
    h.stop("server");h.start("server");reconnect_players(h,["PlayerA","PlayerB"]);warm=read(h.root/"server/process.json")
    require(warm["start"]>cold["start"] and warm["releaseSha256"]==cold["releaseSha256"],"Table reuse did not reopen the same release in a new JVM")
    seed(4,SECOND);before=h.container(SECOND_DEST,TABLE);before_inv=inventory(h,"PlayerB");calls=h.model_calls()
    offline=verify(submit(4,SECOND,SECOND_DEST,"PlayerB"),4,before,before_inv,SECOND,SECOND_DEST,"PlayerB")
    require(offline["artifact"]==acquired["artifact"] and h.model_calls()==calls,"Stopped-backend fresh actor failed immutable table reuse")
    # Bring Ada to staging by ordinary known production before freezing the save boundary.
    seed(1);before=h.container(SOURCE,TABLE);before_inv=inventory(h,"PlayerA")
    approach=verify(submit(1,SOURCE,SOURCE),1,before,before_inv,SOURCE,SOURCE)
    seed(4);h.console(["tick rate 20","tick freeze"]);before_inventory=inventory(h,"PlayerA");rid=submit(4,SOURCE,SECOND_DEST)
    boundary=None;deadline=time.monotonic()+300;steps=0
    while steps<=512 and time.monotonic()<deadline:
        view=h.run_view("PlayerA",rid)
        if view and view.get("phase")=="EXECUTING" and any(r["stage"]=="CRAFT" for r in view.get("resourceReceipts",[])):
            boundary=view;break
        if view and view.get("phase")=="TERMINAL":raise AssertionError("Table crash fixture stopped before capture: "+json.dumps(view))
        if steps==512:break
        tick=h.snapshot()["gameTick"];h.console(["tick step 1"],lifecycle=True)
        observed=h.wait(lambda:(s if (s:=h.snapshot())["gameTick"]>tick else None),"One vanilla craft-boundary tick",15)
        require(observed["gameTick"]==tick+1,"Craft boundary advanced multiple ticks");steps+=1
    require(boundary is not None,"Bounded table craft boundary missed")
    saved_inventory=inventory(h,"PlayerA");saved_containers=h.snapshot()["containers"]
    require(saved_inventory.get(TABLE,0)==before_inventory.get(TABLE,0)+1,"Craft boundary has no newly manufactured item in real custody")
    checkpoint=h.save_checkpoint();h.stop("server",hard=True);killed=h.world_hashes();h.actions_active=False
    h.start("server");h.console(["tick unfreeze","tick rate 20"]);reconnect_players(h,["PlayerA","PlayerB"])
    recovered=h.wait(lambda:h.run_view("PlayerA",rid),"Same-save interrupted table production",30)
    require("INTERRUPTED" in recovered["description"],"Uncertain table craft resumed")
    require(inventory(h,"PlayerA")==saved_inventory and h.snapshot()["containers"]==saved_containers,"Acknowledged save lost real table custody")
    h.quiet(80);require(inventory(h,"PlayerA")==saved_inventory and h.snapshot()["containers"]==saved_containers,"Restart replayed table manufacture or delivery")
    h.screenshot("PlayerA","rb006a-saved-craft-custody-no-replay",chat=True)
    return {"canonicalProduction":acquired,"actualLanguage":language,"knownBindings":[known],"offlineWarmReuse":offline,"foreignDenied":foreign,"models":models,
            "offlineProcessProof":{"cold":cold,"warm":warm,"sameRelease":True,"backendUnavailable":True},"crashApproach":approach,"killBoundary":boundary,"clockSteps":steps,
            "checkpoint":checkpoint,"killedWorldHashes":killed,"recovered":recovered,"savedInventory":saved_inventory,"noFacilityCreated":True}
