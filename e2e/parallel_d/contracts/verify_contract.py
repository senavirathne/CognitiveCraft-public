#!/usr/bin/env python3
"""Standalone validator for the exact JSON Schema subset used by the frozen design.
This checks contract shape/semantics and synthetic fixtures. It performs no gameplay."""
import argparse, hashlib, json, re, unicodedata
from pathlib import Path

def no_duplicates(pairs):
    out={}
    for k,v in pairs:
        if k in out: raise ValueError("duplicate key: "+k)
        out[k]=v
    return out
def read(p):
    return json.loads(p.read_text(encoding="utf-8"),object_pairs_hook=no_duplicates,
        parse_float=lambda s:(_ for _ in ()).throw(ValueError("floating number")))
def canon(x):
    def walk(v):
        if isinstance(v,str) and unicodedata.normalize("NFC",v)!=v: raise ValueError("not NFC")
        if type(v) is int and abs(v)>9007199254740991: raise ValueError("integer range")
        if isinstance(v,list):
            for z in v: walk(z)
        if isinstance(v,dict):
            for k,z in v.items(): walk(k);walk(z)
    walk(x)
    return (json.dumps(x,ensure_ascii=False,sort_keys=True,separators=(",",":"),allow_nan=False)+"\n").encode()
def shape(value,s,root):
    allowed={"$schema","$id","title","description","definitions","$ref","oneOf","type","const","enum","properties","required","additionalProperties","items","minItems","maxItems","minimum","maximum","minLength","maxLength","pattern"}
    if set(s)-allowed: raise ValueError("unsupported schema keyword")
    if "$ref" in s: return shape(value,root["definitions"][s["$ref"].split("/")[-1]],root)
    if "oneOf" in s:
        ok=0
        for choice in s["oneOf"]:
            try: shape(value,choice,root);ok+=1
            except ValueError: pass
        if ok!=1: raise ValueError("oneOf")
        return
    if "const" in s and (value!=s["const"] or type(value)!=type(s["const"])): raise ValueError("const")
    if "enum" in s and value not in s["enum"]: raise ValueError("enum")
    t=s.get("type")
    if t=="null" and value is not None: raise ValueError("null")
    if t=="integer":
        if type(value) is not int or not s.get("minimum",-9007199254740991)<=value<=s.get("maximum",9007199254740991): raise ValueError("integer")
    if t=="string":
        if not isinstance(value,str) or not s.get("minLength",0)<=len(value)<=s.get("maxLength",10**8): raise ValueError("string")
        if "pattern" in s and re.search(s["pattern"],value) is None: raise ValueError("pattern")
    if t=="object":
        if not isinstance(value,dict): raise ValueError("object")
        if not set(s.get("required",[]))<=set(value): raise ValueError("required")
        if s.get("additionalProperties") is False and set(value)-set(s["properties"]): raise ValueError("additional")
        for k,v in value.items():
            if k in s.get("properties",{}): shape(v,s["properties"][k],root)
    if t=="array":
        if not isinstance(value,list) or not s.get("minItems",0)<=len(value)<=s.get("maxItems",10**8): raise ValueError("array")
        for v in value: shape(v,s["items"],root)
def semantics(kind,b):
    if kind=="DialogueIntent":
        if b["action"] in ("CHAT","FORGET","COME","FOLLOW","STOP","STATUS") and (b["source"] is not None or b["destination"] is not None or b["items"] or b["label"] is not None or b["role"] is not None or b["requested_access"] is not None): raise ValueError("S16 unrelated fields")
        if b["action"]!="DELIVER" and b["items"]: raise ValueError("S16 items")
        if b["action"]!="SET_STORAGE_ACCESS" and b["requested_access"] is not None: raise ValueError("S16 access")
    if kind=="PlayerReplyIntent" and b["reply"]!="CHOOSE_TARGET" and b["selection"] is not None: raise ValueError("S16 reply selection")
    if kind=="StorageRoleAssignment" and b["beneficiary"]["kind"]=="PUBLIC": raise ValueError("S15 public default")
    if kind=="StorageRecord":
        if b["roles"]!=sorted(set(b["roles"])): raise ValueError("S11 role set")
        for grant in b["acl"]:
            if grant["permissions"]!=sorted(set(grant["permissions"])): raise ValueError("S11 permission set")
            if grant["subject"]["kind"]=="PUBLIC" and set(grant["permissions"])-{"INSPECT","DEPOSIT","WITHDRAW"}: raise ValueError("S04 public admin")
    if kind=="SelectionWitness" and not b["captured_tick"]<=b["expires_tick"]<=b["captured_tick"]+40: raise ValueError("S06 selection expiry")
    if kind=="BlockerEvidence":
        if b["requirements"] and (b["knowledge"]!="KNOWN" or b["visibility"]!="AUTHORIZED_FACTS" or b["continuation"]!="FEASIBLE_AFTER_ASSISTANCE" or b["code"]!="INSUFFICIENT_MATERIAL"): raise ValueError("S02")
        if b["visibility"]=="REDACTED" and (b["storage_id"] is not None or b["requirements"]): raise ValueError("S02 privacy")
    if kind=="AssistanceRequest":
        semantics("BlockerEvidence",b["blocker"])
        if b["expires_ms"]>b["activity"]["absolute_deadline_ms"] or b["blocker"]["activity"]!=b["activity"]: raise ValueError("S03")
    if kind=="StorageAccessDecision":
        if not b["evaluated_tick"]<=b["valid_through_tick"]<=b["evaluated_tick"]+2: raise ValueError("S04")
        if b["decision"]=="ALLOW" and b["reason"] is not None: raise ValueError("S04 reason")
    if kind=="PhysicalStorageBinding":
        m=b["members"]
        if len(m)!=(2 if b["adapter_id"]=="minecraft:chest_double/v1" else 1): raise ValueError("S05")
        if len({x["incarnation_id"] for x in m})!=len(m): raise ValueError("S05 duplicate")
        if m!=sorted(m,key=lambda x:(x["x"],x["y"],x["z"],x["incarnation_id"])): raise ValueError("S05 sort")
        if len(m)==2 and (m[0]["y"]!=m[1]["y"] or abs(m[0]["x"]-m[1]["x"])+abs(m[0]["z"]-m[1]["z"])!=1): raise ValueError("S05 adjacent")
        if b["adapter_id"]!="minecraft:barrel/v1" and b["facing"] in ("UP","DOWN"): raise ValueError("S05 facing")
        if len(m)==2 and ((b["facing"] in ("NORTH","SOUTH") and m[0]["x"]==m[1]["x"]) or (b["facing"] in ("EAST","WEST") and m[0]["z"]==m[1]["z"])): raise ValueError("S05 alignment")
        if hashlib.sha256(canon({k:b[k] for k in ("adapter_id","world_id","dimension","members","facing")})).hexdigest()!=b["topology_sha256"]: raise ValueError("S05 digest")
    if kind=="ResolvedEndpoint" and b["kind"]=="STORAGE":
        semantics("PhysicalStorageBinding",b["binding"]);semantics("StorageAccessDecision",b["access"])
        if b["binding"]["storage_id"]!=b["access"]["storage_id"] or b["binding"]["binding_generation"]!=b["access"]["binding_generation"] or b["binding"]["world_id"]!=b["access"]["scope"]["world_id"]: raise ValueError("S06")
    if kind=="MobilityGoal":
        if b["actor"]["world_id"]!=b["activity"]["scope"]["world_id"] or b["actor"]["citizen_id"]!=b["activity"]["responsible_citizen_id"]: raise ValueError("S07 activity actor")
        if b["mode"]=="COME_TO_PLAYER" and (b["captured_position"] is None or b["player_id"] is None or b["storage_target"] is not None): raise ValueError("S07 come")
        if b["mode"]=="FOLLOW_PLAYER" and (b["player_id"] is None or b["captured_position"] is not None or b["storage_target"] is not None): raise ValueError("S07 follow")
        if b["mode"]=="GO_NAMED_STORAGE" and (b["storage_target"] is None or b["storage_target"]["kind"]!="STORAGE" or b["player_id"] is not None or b["captured_position"] is not None): raise ValueError("S07 named")
        if b["follow_min_milliblocks"]>b["follow_max_milliblocks"]: raise ValueError("S07 range")
    if kind=="DeliveryGoal":
        coords=[]
        identities=[]
        for ep,perm in [(b["source"],"WITHDRAW"),(b["destination"],"DEPOSIT")]:
            semantics("ResolvedEndpoint",ep)
            if ep["kind"]=="STORAGE":
                x=ep["binding"];a=ep["access"]
                if a["scope"]!=b["activity"]["scope"]: raise ValueError("S08 scope")
                if a["operation"]!=perm or a["decision"]!="ALLOW" or x["status"]!="VERIFIED_LIVE": raise ValueError("S08 permission")
                coords.append((x["world_id"],x["dimension"]));identities.append(x["storage_id"])
            else:
                x=ep["citizen"]
                if ep["scope"]!=b["activity"]["scope"]: raise ValueError("S08 scope")
                if ep["permission"]!=perm or ep["access_decision"]!="ALLOW": raise ValueError("S08 recipient")
                coords.append((x["world_id"],x["dimension"]));identities.append(x["citizen_id"])
        if coords[0]!=coords[1] or coords[0]!=(b["carrier"]["world_id"],b["carrier"]["dimension"]) or identities[0]==identities[1]: raise ValueError("S08 identity")
        if b["carrier"]["world_id"]!=b["activity"]["scope"]["world_id"] or b["carrier"]["citizen_id"]!=b["activity"]["responsible_citizen_id"]: raise ValueError("S08 carrier")
        if b["source"]["kind"]=="CITIZEN" and b["source"]["citizen"]!=b["carrier"]: raise ValueError("S08 third-party donor")
    if kind=="CustodyCheckpoint":
        if b["tier"] in ("PARTICIPANT_SAVES_CONFIRMED","ORIGINAL_OWNER_SETTLED") and b["a_proof_reference"] is None: raise ValueError("S09 proof")
        if b["tier"] in ("PARTICIPANT_SAVES_CONFIRMED","ORIGINAL_OWNER_SETTLED") and any(p["save_evidence_id"] is None for p in b["participants"]): raise ValueError("S09 participant")
        if b["tier"]=="ORIGINAL_OWNER_SETTLED" and b["original_effect_outcome"]!="SETTLED": raise ValueError("S09 settled")
    if kind=="StageManifest":
        for name in ("unit","python","game_tests","client_e2e"):
            m=b[name]
            if m["executed"]!=m["passed"]+m["failed"]+m["refused_expected"]+m["inconclusive"] or m["executed"]>m["planned"]: raise ValueError("S14 counts")
        if b["gate_status"]=="BRANCH_READY":
            if any(b[k] is None for k in ("source_sha","tree_sha","jar_sha256","docs_sha","workflow_run","attempt")): raise ValueError("S14 pin")
            if b["regressions"] or any(b[n]["failed"] or b[n]["inconclusive"] or b[n]["executed"]!=b[n]["planned"] for n in ("unit","python","game_tests","client_e2e")): raise ValueError("S14 incomplete")
def contextual(kind,b,c):
    if kind=="ActivityResumeSignal":
        if "trusted_generation" in c and c["trusted_generation"]!=b["activity"]["execution_generation"]: return False
        if c.get("reobserved_requirements_satisfied") is False: return False
    if kind=="PlayerReplyIntent" and c.get("authenticated_principal")!=c.get("question_principal"): return False
    if kind=="ResolvedEndpoint" and b["kind"]=="STORAGE":
        if "current_incarnation" in c and c["current_incarnation"]!=b["binding"]["members"][0]["incarnation_id"]: return False
        if "current_acl_revision" in c and c["current_acl_revision"]!=b["access"]["acl_revision"]: return False
    if kind=="CustodyCheckpoint" and b["tier"] in ("PARTICIPANT_SAVES_CONFIRMED","ORIGINAL_OWNER_SETTLED") and c.get("a_proof_authenticated") is not True: return False
    if kind=="StorageSelector" and "eligible_registered_count" in c and c["eligible_registered_count"]!=1: return False
    return True
def verify(root):
    schema=read(root/"contract.schema.json");rows=[]
    for p in sorted((root/"fixtures").glob("*.json")):
        f=read(p)
        if p.read_bytes()!=canon(f): raise ValueError("noncanonical "+str(p))
        m=f["message"]
        try: shape(m,schema,schema)
        except ValueError: actual="INVALID_SCHEMA"
        else:
            try: semantics(m["kind"],m["body"])
            except ValueError: actual="INVALID_SEMANTICS"
            else: actual="VALID" if contextual(m["kind"],m["body"],f["context"]) else "REFUSED_EXPECTED"
        if actual!=f["expected"]: raise ValueError(f["id"]+": "+actual+" != "+f["expected"])
        rows.append({"id":f["id"],"expected":actual,"check":"PASS"})
    manifest=root/"SHA256SUMS.json"
    if manifest.exists():
        for name,h in read(manifest)["files"].items():
            if hashlib.sha256((root/name).read_bytes()).hexdigest()!=h: raise ValueError("hash mismatch "+name)
    return {"claim":"DESIGN_CONTRACT_VALIDATION_ONLY","fixture_count":len(rows),"passed":len(rows),"native_executions":0,"rows":rows}
if __name__=="__main__":
    a=argparse.ArgumentParser();a.add_argument("--contract",type=Path,required=True);a.add_argument("--output",type=Path)
    args=a.parse_args();result=verify(args.contract)
    if args.output: args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_bytes(canon(result))
    print(json.dumps({k:v for k,v in result.items() if k!="rows"},sort_keys=True))
