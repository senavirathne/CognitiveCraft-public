"""GP-RB-COMPOSE-01: real connected clients, independently acquired methods and Job receipt graphs."""
from __future__ import annotations
import json,re,shutil,urllib.request
SRC=[5,201,5];DST=[9,201,7];TABLE=[7,201,9];OAK_MIN=[4,201,3];OAK_MAX=[7,201,3]
LOG="minecraft:oak_log";PLANK="minecraft:oak_planks";STICK="minecraft:stick";AXE="minecraft:wooden_axe"

def scenario(h):
    from suite import ROOT,UUID_PATTERN,DENIED_PATTERN,require,read,reconnect_players
    require(h.topology=="compose","Composition requires actual configured backends and real network clients")
    private=h.root/"needle";shutil.copytree(ROOT/"build/needle",private,dirs_exist_ok=True)
    h.env["COGNITIVECRAFT_OLLAMA_MODEL"]="qwen3:4b-instruct-2507-q4_K_M";h.env["COGNITIVECRAFT_NEEDLE_DIR"]="/evidence/needle"
    h.compose("up","-d","ollama");h.compose("exec","-T","ollama","ollama","pull",h.env["COGNITIVECRAFT_OLLAMA_MODEL"])
    def tags():
        try:return json.load(urllib.request.urlopen("http://127.0.0.1:11434/api/tags",timeout=5))
        except OSError:return None
    models=h.wait(tags,"Configured independent acquisition model",60)
    req=urllib.request.Request("http://127.0.0.1:11434/api/generate",data=json.dumps({"model":h.env["COGNITIVECRAFT_OLLAMA_MODEL"],"stream":False,"keep_alive":"30m"}).encode(),headers={"Content-Type":"application/json"})
    with urllib.request.urlopen(req,timeout=120) as response:require(json.load(response)["done"],"Model prewarm failed")
    h.fresh(two=True);h.enroll();h.enroll("PlayerB","Mira");h.release_gate();h.release_gate("PlayerB")
    h.console(["setblock "+" ".join(map(str,TABLE))+" minecraft:crafting_table","setblock "+" ".join(map(str,SRC))+" minecraft:chest","setblock 15 201 9 minecraft:chest"])
    def logs(minimum=OAK_MIN,maximum=OAK_MAX):
        h.console([f"setblock {x} {minimum[1]} {minimum[2]} minecraft:oak_log" for x in range(minimum[0],maximum[0]+1)])
    def seed(l=0,p=0,s=0,source=SRC):
        stacks=[f'{{Slot:{i}b,id:"{item}",count:{q}}}' for i,(item,q) in enumerate(((LOG,l),(PLANK,p),(STICK,s))) if q]
        h.console(["data merge block "+" ".join(map(str,source))+" {Items:["+",".join(stacks)+"]}"])
        h.wait(lambda:all(h.container(source,item)==q for item,q in ((LOG,l),(PLANK,p),(STICK,s))),"Actual eligible supplied stock",20)
    def run(command):
        response=h.command("PlayerA",command,"run="+UUID_PATTERN)
        require("accepted=true" in response,"Independent producer rejected: "+response)
        return re.search("run=("+UUID_PATTERN+")",response).group(1)
    h.command("PlayerA","/aivillage kernel inference true","admission=true")
    hashes={};acquisitions=[]
    logs();seed()
    commands=[
        (LOG,lambda:"/aivillage kernel gather-oak "+h.citizens["PlayerA"]+" 1 "+" ".join(map(str,OAK_MIN+OAK_MAX+SRC))),
        (PLANK,lambda:"/aivillage kernel make-planks "+h.citizens["PlayerA"]+" 4 "+" ".join(map(str,SRC+DST))),
        (STICK,lambda:"/aivillage kernel make-sticks "+h.citizens["PlayerA"]+" 4 "+" ".join(map(str,SRC+DST))),
        (AXE,lambda:"/aivillage kernel make-axes "+h.citizens["PlayerA"]+" 1 "+" ".join(map(str,SRC+DST+TABLE)))]
    for index,(item,command) in enumerate(commands):
        if index==1:seed(1,0,0)
        elif index==2:seed(0,2,0)
        elif index==3:seed(0,3,2)
        rid=run(command());view=h.terminal("PlayerA",rid,650)
        require(view.get("marker",{}).get("outcome")=="ADMITTED","Genuine independent admission failed: "+view["description"])
        require(view.get("resourceReceipts"),"No native resource effects in admission")
        hashes[item]=view["marker"]["artifactSha256"];acquisitions.append(view)
        h.screenshot("PlayerA","rb005-independent-"+item.split(":")[1],chat=True)
    h.command("PlayerA","/aivillage kernel inference false","admission=false")
    def job(jid,player="PlayerA"):
        state=h.snapshot()
        return state["players"].get(player,{}).get("jobs",{}).get(jid)
    def compose(q,l,p,s,source=SRC,destination=DST,table=TABLE,player="PlayerA"):
        oak_min,oak_max=(OAK_MIN,OAK_MAX) if player=="PlayerA" else ([14,201,3],[17,201,3])
        logs(oak_min,oak_max);seed(l,p,s,source);h.actions_active=True
        response=h.command(player,"/aivillage kernel compose-axes "+h.citizens[player]+" "+str(q)+" "+" ".join(map(str,source+destination+table+oak_min+oak_max)),"job="+UUID_PATTERN)
        require("accepted=true" in response,"Canonical MAKE goal rejected: "+response)
        return re.search("job=("+UUID_PATTERN+")",response).group(1)
    def complete(jid,q,before,source=SRC,destination=DST,player="PlayerA"):
        def result():
            current=job(jid,player)
            if current and current["state"] in ("FAILED","CANCELLED","INTERRUPTED"):raise AssertionError("Composed Job stopped: "+json.dumps(current))
            return current if current and current["state"]=="SUCCEEDED" else None
        root=h.wait(result,"Native prerequisite chain "+jid,220)
        require(root["fulfilled"]==q and h.container(destination,AXE)==before+q,"MAKE credited old output or lost new axes")
        state=h.snapshot()["players"][player]["jobs"];graph=[root,*[state[c] for c in root["children"]]]
        mapping={"cognitivecraft:deliver_oak_logs":LOG,"cognitivecraft:produce_oak_planks":PLANK,"cognitivecraft:produce_sticks":STICK,"cognitivecraft:produce_wooden_axes":AXE}
        for node in graph:
            cap=node["request"]["request"]["capability"]["name"]
            require(node["preparation"]["method"]["sha256"]==hashes[mapping[cap]],"Independent executable method changed in composition")
            require(node["state"]=="SUCCEEDED" and node["attempts"],"Dependency bypassed normal dispatch")
        return {"job":jid,"root":root,"graph":graph,"source":source,"destination":destination}
    rows=[]
    fixtures=[(1,0,3,2,0,0,0,0),(1,0,5,0,0,0,2,0),(1,1,2,0,0,1,2,0),(1,2,0,0,0,3,2,0),(1,0,0,0,0,3,2,2),(1,2,0,2,1,1,0,0),(4,0,0,0,0,0,0,4)]
    for index,(q,l,p,s,fl,fp,fs,harvest) in enumerate(fixtures):
        before=h.container(DST,AXE);calls=h.model_calls();jid=compose(q,l,p,s);data=complete(jid,q,before)
        require([h.container(SRC,item) for item in (LOG,PLANK,STICK)]==[fl,fp,fs],"Golden native surplus mismatch")
        actual=sum(r["quantity"] for n in data["graph"] for a in n["attempts"] for r in a.get("resourceReceipts",[]) if r["stage"]=="HARVEST")
        require(actual==harvest and h.model_calls()==calls,"Known chain overproduced or invoked inference")
        data.update({"fixture":index,"quantity":q,"harvested":actual,"surplus":[fl,fp,fs],"modelCalls":h.model_calls()});rows.append(data)
    h.command("PlayerA","/aivillage kernel job-status "+rows[-1]["job"],"state=SUCCEEDED")
    h.screenshot("PlayerA","rb005-golden-job-status",chat=True)
    require(h.menu_count("PlayerA",DST,AXE)==h.container(DST,AXE),"Real destination menu differs from physical snapshot")
    foreign=h.command("PlayerB","/aivillage kernel job-status "+rows[-1]["job"],DENIED_PATTERN)
    require(rows[-1]["job"] not in h.suggestions("PlayerB","/aivillage kernel job-status "),"Foreign Job suggestion leaked")
    h.screenshot("PlayerB","rb005-private-denial",chat=True)
    logs();seed();before_language=h.container(DST,AXE);language_before=h.model_calls()
    h.console(['data remove block '+" ".join(map(str,DST))+' Items[{id:"minecraft:oak_planks"}]','data remove block '+" ".join(map(str,DST))+' Items[{id:"minecraft:stick"}]'])
    before_destinations={tuple(SRC):h.container(SRC,AXE),tuple(DST):h.container(DST,AXE)}
    answer=h.command("PlayerA","/aivillage kernel ask Ada, make me a wooden axe and store it","interpretation=("+UUID_PATTERN+")")
    ticket=re.search("interpretation=("+UUID_PATTERN+")",answer).group(1)
    interpreted=h.wait(lambda:(v if (v:=h.run_view("PlayerA",ticket)) and v.get("phase")=="SUBMITTED" else None),"Actual Needle MAKE interpretation",100)
    language_job=job(interpreted["runId"])
    language_job=h.wait(lambda:job(interpreted["runId"]),"Language-created normal Job",30)
    source=[language_job["request"]["request"]["arguments"]["source"]["value"][k] for k in ("x","y","z")]
    destination=[language_job["request"]["request"]["arguments"]["destination"]["value"][k] for k in ("x","y","z")]
    before=before_destinations.get(tuple(destination),0)
    language=complete(interpreted["runId"],1,before,source,destination)
    require(h.model_calls()["needleCalls"]>language_before["needleCalls"] and h.model_calls()["generationCalls"]==language_before["generationCalls"],"Interpretation and known execution were not separately measured")
    h.screenshot("PlayerA","rb005-actual-language",chat=True)
    checkpoint=h.snapshot();cold=read(h.root/"server/process.json");release=cold["releaseSha256"]
    h.stop("server");h.compose("stop","ollama");h.env["COGNITIVECRAFT_OLLAMA_MODEL"]="";h.env["COGNITIVECRAFT_NEEDLE_DIR"]=""
    require(tags() is None,"External generation backend was not actually stopped")
    h.start("server");reconnect_players(h,["PlayerA","PlayerB"])
    warm=read(h.root/"server/process.json");require(warm["start"]>cold["start"],"Known reuse did not use a separate JVM")
    require(release==warm["releaseSha256"],"Offline reuse changed release")
    # A fresh citizen and native table/container bindings, using the established spare citizen fixture.
    h.console(["setblock 15 201 11 minecraft:crafting_table"])
    h.command("PlayerB","/aivillage kernel inference false","admission=false")
    fresh_source=[15,201,9];fresh_dest=[17,201,7];before=h.container(fresh_dest,AXE);calls=h.model_calls()
    offline_id=compose(4,0,0,0,source=fresh_source,destination=fresh_dest,table=[15,201,11],player="PlayerB");offline=complete(offline_id,4,before,source=fresh_source,destination=fresh_dest,player="PlayerB")
    require(offline["root"]["request"]["request"]["arguments"]["actor"] != rows[0]["root"]["request"]["request"]["arguments"]["actor"],"Offline bindings retained original actor")
    require(h.model_calls()==calls,"Stopped-backend known execution invoked inference")
    # Cancel an owned graph while it waits for real intermediate production.
    cancel_id=compose(4,0,0,0);h.cancel_job("PlayerA",cancel_id)
    cancelled=h.wait(lambda:(j if (j:=job(cancel_id)) and j["state"]=="CANCELLED" else None),"Owned graph cancellation",60)
    # Kill at an actual intermediate DEPOSIT while its terminal/link acknowledgement is outstanding.
    checkpoint=h.save_checkpoint();crash_id=compose(4,0,0,0)
    def intermediate():
        state=h.snapshot()["players"]["PlayerA"]["jobs"];root=state.get(crash_id)
        if not root:return None
        for cid in root["children"]:
            child=state[cid]
            if child["request"]["request"]["capability"]["name"]=="cognitivecraft:produce_oak_planks" and child["state"]=="ACTIVE" and any(r["stage"]=="DEPOSIT" for a in child["attempts"] for r in a.get("resourceReceipts",[])):
                return {"root":root,"intermediate":child,"containers":h.snapshot()["containers"]}
        return None
    boundary=h.wait(intermediate,"Intermediate production before linkage acknowledgement",60)
    h.stop("server",hard=True);killed=h.world_hashes();h.actions_active=False
    h.start("server");reconnect_players(h,["PlayerA","PlayerB"])
    recovered=h.wait(lambda:job(crash_id),"Preserved interrupted graph after SIGKILL",30)
    require(recovered["state"]=="INTERRUPTED","Uncertain prepared chain resumed")
    stable=h.snapshot()["containers"];h.quiet(80);require(h.snapshot()["containers"]==stable,"Restart replayed uncertain crafting/consumption")
    h.command("PlayerA","/aivillage kernel job-status "+crash_id,"state=INTERRUPTED");h.screenshot("PlayerA","rb005-interrupted-no-replay",chat=True)
    return {"independentAcquisitions":acquisitions,"artifacts":hashes,"goldenRows":rows,"actualLanguage":language,"foreignDenied":foreign,
            "offlineWarmReuse":offline,"offlineProcessProof":{"cold":cold,"warm":warm,"sameRelease":True,"backendUnavailable":True},
            "cancelled":cancelled,"killBoundary":boundary,"recovered":recovered,"checkpoint":checkpoint,"killedWorldHashes":killed,"models":models}
