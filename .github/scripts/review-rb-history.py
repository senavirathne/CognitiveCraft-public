#!/usr/bin/env python3
"""Retain original failed assessment records without re-executing or replacing scores."""
import importlib.util
import json
from collections import Counter
from pathlib import Path
import zipfile

spec=importlib.util.spec_from_file_location("review",Path(__file__).with_name("review-rb-assessment.py"))
review=importlib.util.module_from_spec(spec)
spec.loader.exec_module(review)
CAMPAIGNS=json.loads("[{\"run\":39422352087,\"head\":\"286576a4ddfed4df464f04d1bd67742fec51317a\",\"raw\":{\"PASS\":0,\"FAIL\":45,\"UNSUPPORTED\":0},\"interpretation\":\"All 45 raw FAIL records are independently classified BLOCKED/test infrastructure: inherited seed attribute hid the fixture helper before gameplay; zero admissions.\",\"aggregate\":{\"id\":12265347119,\"name\":\"rb-generalization-assessment-39422352087-1\",\"size_in_bytes\":322251,\"url\":\"https://api.github.com/repos/senavirathne/CognitiveCraft-public/actions/artifacts/12265347119\",\"archive_download_url\":\"https://api.github.com/repos/senavirathne/CognitiveCraft-public/actions/artifacts/12265347119/zip\",\"expired\":false,\"created_at\":\"2026-10-20T12:46:13Z\",\"expires_at\":\"2026-11-19T12:46:13Z\",\"updated_at\":\"2026-10-20T12:46:13Z\",\"digest\":\"sha256:3355a4e0a0dc63b919ae7778dbcddba1a3e5db708965be9aebd2366fa0cdc2ce\",\"workflow_run\":{\"id\":39422352087,\"repository_id\":1405512338,\"head_repository_id\":1405512338,\"head_branch\":\"work/rb-gameplay-generalization\",\"head_sha\":\"286576a4ddfed4df464f04d1bd67742fec51317a\"}},\"archives\":[{\"id\":12265347119,\"name\":\"rb-generalization-assessment-39422352087-1\",\"bytes\":322251,\"sha256\":\"3355a4e0a0dc63b919ae7778dbcddba1a3e5db708965be9aebd2366fa0cdc2ce\",\"head\":\"286576a4ddfed4df464f04d1bd67742fec51317a\"},{\"id\":12264842153,\"name\":\"rb-generalization-facility-39422352087-1\",\"bytes\":61698099,\"sha256\":\"5761c92daf9c735306ef24c12d24554a47b6ba4cb833d0d2fc6e22dd041ec7c7\",\"head\":\"286576a4ddfed4df464f04d1bd67742fec51317a\"},{\"id\":12264536111,\"name\":\"rb-generalization-binary-39422352087-1\",\"bytes\":2064232,\"sha256\":\"07a023b582ea87c2a25962d6d024a01be06fe1ebe40634610df34bfe32f3d48c\",\"head\":\"286576a4ddfed4df464f04d1bd67742fec51317a\"},{\"id\":12264188398,\"name\":\"rb-generalization-blueprint-39422352087-1\",\"bytes\":61789548,\"sha256\":\"b6f69747737523a61265a3c23cc2d27ba7e957d90d7b7ad5c26aaab8f7c75849\",\"head\":\"286576a4ddfed4df464f04d1bd67742fec51317a\"},{\"id\":12263943587,\"name\":\"rb-generalization-oak-39422352087-1\",\"bytes\":63285107,\"sha256\":\"3666ca3818904de39a0ca86bc8668e19fe0a340cf1b748955c527aefcf54b8b1\",\"head\":\"286576a4ddfed4df464f04d1bd67742fec51317a\"},{\"id\":12263814421,\"name\":\"rb-generalization-birch-39422352087-1\",\"bytes\":61721375,\"sha256\":\"05d75126f210793ca45f4145cd0b12263918dc3124374d73d2efba820d5d05db\",\"head\":\"286576a4ddfed4df464f04d1bd67742fec51317a\"},{\"id\":12263739000,\"name\":\"rb-generalization-craft-39422352087-1\",\"bytes\":66189520,\"sha256\":\"a09d72b002514984b0d1afc69d610c633d31de7d90d36b53c6c727b543b66b24\",\"head\":\"286576a4ddfed4df464f04d1bd67742fec51317a\"}]},{\"run\":39423753601,\"head\":\"e887ff1ffeac3912abce9edb0ace0a4512afb606\",\"raw\":{\"PASS\":27,\"FAIL\":11,\"UNSUPPORTED\":7},\"interpretation\":\"Preserve raw outcomes. Wrong-material safe refusals, stale carrot fixture comparison, intake publication and post-crash fencing were assessment limitations; generic language clarification was not semantic recognition. This campaign is not the primary score.\",\"aggregate\":{\"id\":12267831982,\"name\":\"rb-generalization-assessment-39423753601-1\",\"size_in_bytes\":3172092,\"url\":\"https://api.github.com/repos/senavirathne/CognitiveCraft-public/actions/artifacts/12267831982\",\"archive_download_url\":\"https://api.github.com/repos/senavirathne/CognitiveCraft-public/actions/artifacts/12267831982/zip\",\"expired\":false,\"created_at\":\"2026-10-20T13:27:43Z\",\"expires_at\":\"2026-11-19T13:27:42Z\",\"updated_at\":\"2026-10-20T13:27:43Z\",\"digest\":\"sha256:4ce389df3966b40cb23eb49036f0b87484d5c230b1fbeef178cd3e25a6886935\",\"workflow_run\":{\"id\":39423753601,\"repository_id\":1405512338,\"head_repository_id\":1405512338,\"head_branch\":\"work/rb-gameplay-generalization\",\"head_sha\":\"e887ff1ffeac3912abce9edb0ace0a4512afb606\"}},\"archives\":[{\"id\":12267831982,\"name\":\"rb-generalization-assessment-39423753601-1\",\"bytes\":3172092,\"sha256\":\"4ce389df3966b40cb23eb49036f0b87484d5c230b1fbeef178cd3e25a6886935\",\"head\":\"e887ff1ffeac3912abce9edb0ace0a4512afb606\"},{\"id\":12267218855,\"name\":\"rb-generalization-blueprint-39423753601-1\",\"bytes\":113880765,\"sha256\":\"a6f2f8d9343f51600d8964d81f41e0eb9b93180dd2e2efcfc5c3a4fbc3cff712\",\"head\":\"e887ff1ffeac3912abce9edb0ace0a4512afb606\"},{\"id\":12266961968,\"name\":\"rb-generalization-oak-39423753601-1\",\"bytes\":103887091,\"sha256\":\"7e39129c875afaffd145d7eedf7976e1ae86c70a1134bfea109510753eaab5f4\",\"head\":\"e887ff1ffeac3912abce9edb0ace0a4512afb606\"},{\"id\":12266862617,\"name\":\"rb-generalization-craft-39423753601-1\",\"bytes\":195021578,\"sha256\":\"f0008ad02c70c9c56c8bc35c118b632e98ae3b3152c41d8bf5aab79aaab86d0b\",\"head\":\"e887ff1ffeac3912abce9edb0ace0a4512afb606\"},{\"id\":12266798718,\"name\":\"rb-generalization-birch-39423753601-1\",\"bytes\":105468104,\"sha256\":\"6308100a0d88b0b4dc1e7defbd6bd08d958255bd95a8756aeb2316c1534aa022\",\"head\":\"e887ff1ffeac3912abce9edb0ace0a4512afb606\"},{\"id\":12265583509,\"name\":\"rb-generalization-facility-39423753601-1\",\"bytes\":117313929,\"sha256\":\"cb56d8d44bb27aa18f9058a348c27fcb87d8c74a54907aad7fc002992a661de6\",\"head\":\"e887ff1ffeac3912abce9edb0ace0a4512afb606\"},{\"id\":12265451648,\"name\":\"rb-generalization-binary-39423753601-1\",\"bytes\":2065320,\"sha256\":\"619174dba419d597c1210d4f515ea56c4019ab86ac04b679042c75fe192c5445\",\"head\":\"e887ff1ffeac3912abce9edb0ace0a4512afb606\"}]},{\"run\":39427828550,\"head\":\"31f77e2fb3d6a6e8baa633486d0ae524985eb674\",\"raw\":{\"PASS\":25,\"FAIL\":9,\"UNSUPPORTED\":11},\"interpretation\":\"Preserve raw outcomes. The stale carrot comparison and C10 after the intentionally fenced crash worker remained harness issues. Raw C07 PASS did not demonstrate positive contending production. Earlier independent conservation was 27/27 evaluable of 30 requested, excluding two NL non-executions and inconclusive C10.\",\"aggregate\":{\"id\":12270190979,\"name\":\"rb-generalization-assessment-39427828550-1\",\"size_in_bytes\":9523160,\"url\":\"https://api.github.com/repos/senavirathne/CognitiveCraft-public/actions/artifacts/12270190979\",\"archive_download_url\":\"https://api.github.com/repos/senavirathne/CognitiveCraft-public/actions/artifacts/12270190979/zip\",\"expired\":false,\"created_at\":\"2026-10-20T13:59:48Z\",\"expires_at\":\"2026-11-19T13:59:45Z\",\"updated_at\":\"2026-10-20T13:59:48Z\",\"digest\":\"sha256:7d9a518498a3db68b297dc800de540c09eddeb38534a4c59b6a3e18fd6eef076\",\"workflow_run\":{\"id\":39427828550,\"repository_id\":1405512338,\"head_repository_id\":1405512338,\"head_branch\":\"work/rb-gameplay-generalization\",\"head_sha\":\"31f77e2fb3d6a6e8baa633486d0ae524985eb674\"}},\"archives\":[{\"id\":12270190979,\"name\":\"rb-generalization-assessment-39427828550-1\",\"bytes\":9523160,\"sha256\":\"7d9a518498a3db68b297dc800de540c09eddeb38534a4c59b6a3e18fd6eef076\",\"head\":\"31f77e2fb3d6a6e8baa633486d0ae524985eb674\"},{\"id\":12270125555,\"name\":\"rb-generalization-blueprint-39427828550-1\",\"bytes\":110555042,\"sha256\":\"bd304ec4b4561a11f2233b21a143d02a11df694dfde59ca35e5b9ffb736687ed\",\"head\":\"31f77e2fb3d6a6e8baa633486d0ae524985eb674\"},{\"id\":12269428023,\"name\":\"rb-generalization-birch-39427828550-1\",\"bytes\":109045605,\"sha256\":\"9c9d3e96a72a4b19aaa3607236ef90bfe369918ed5e014a2d8c01226b586174d\",\"head\":\"31f77e2fb3d6a6e8baa633486d0ae524985eb674\"},{\"id\":12269362404,\"name\":\"rb-generalization-craft-39427828550-1\",\"bytes\":248806527,\"sha256\":\"80df7cb7fa4012f6116e2d79410fbf0e7ddde03d81c1f5fcd8311ebe0fb06084\",\"head\":\"31f77e2fb3d6a6e8baa633486d0ae524985eb674\"},{\"id\":12268488099,\"name\":\"rb-generalization-facility-39427828550-1\",\"bytes\":135569739,\"sha256\":\"9961317b5620019919071ce453ccb051bc22f84aae8890f68ef907d7e3df394c\",\"head\":\"31f77e2fb3d6a6e8baa633486d0ae524985eb674\"},{\"id\":12268212417,\"name\":\"rb-generalization-oak-39427828550-1\",\"bytes\":96924693,\"sha256\":\"e17f6617b3297712886a7d298500a36408da8d14b42d9e7a55700b2cf804e95e\",\"head\":\"31f77e2fb3d6a6e8baa633486d0ae524985eb674\"},{\"id\":12268052250,\"name\":\"rb-generalization-binary-39427828550-1\",\"bytes\":2066968,\"sha256\":\"dcf789ed7df35384050bc204b7be90d30a930d557a2974b9a019c58435d34e0e\",\"head\":\"31f77e2fb3d6a6e8baa633486d0ae524985eb674\"}]}]")

def observed(state):
    if not isinstance(state,dict):
        return None
    return {"canonicalOriginalSha256":review.object_sha(state),
        **{k:state[k] for k in ("time","gameTick","tick","containers","actors","drops") if k in state},
        "resourceBlocks":{p:s for p,s in state.get("blocks",{}).items() if review.block_item(s)},
        "knownPlayers":{p:{k:v[k] for k in ("uuid","online","pos","assessmentInventory") if k in v}
            for p,v in state.get("knownPlayers",{}).items()}}

def main():
    campaigns=[]
    for campaign in CAMPAIGNS:
        aggregate=campaign["aggregate"]
        pin=(aggregate["id"],aggregate["size_in_bytes"],aggregate["digest"].removeprefix("sha256:"))
        archive_path=review.download("historical-"+str(campaign["run"]),pin)
        with zipfile.ZipFile(archive_path) as archive:
            names=archive.namelist()
            review.require(len(names)<50000 and len(set(names))==len(names),"Historical archive member bounds")
            name=review.member(archive,"generalization-assessment.json")
            info=archive.getinfo(name)
            review.require(info.file_size<300_000_000,"Historical JSON expanded bound")
            data=archive.read(name); original=json.loads(data)
        archive_path.unlink()
        review.require(original["assessmentCommit"]==campaign["head"],"Historical source identity differs")
        rows=original["scenarios"]
        review.require(len(rows)==45 and len({r["id"] for r in rows})==45,"All original historical records required")
        counts=dict(Counter(r["status"] for r in rows))
        review.require(all(counts.get(k,0)==v for k,v in campaign["raw"].items()),"Original historical counts differ")
        retained=[]
        for row in rows:
            record={k:row[k] for k in ("id","group","category","requirement","expected","composition","status","reason","classification","trace","durationSeconds","screenshots") if k in row}
            record["originalRecordCanonicalSha256"]=review.object_sha(row)
            if row["status"] in ("FAIL","BLOCKED","INCONCLUSIVE") or row["id"] in ("C07","C09","C10"):
                record["originalEvidence"]=review.details(row.get("evidence",{}))
                for key in ("physicalFailureState","physicalFinalState","physicalAfterCleanup"):
                    if row.get(key):
                        record[key]=observed(row[key])
            retained.append(record)
        campaigns.append({"run":campaign["run"],"sourceHead":campaign["head"],"primaryScore":False,
            "originalAggregate":{"artifact":pin[0],"bytes":pin[1],"sha256":pin[2],"member":name,"originalJsonSha256":review.sha(data)},
            "originalRawCounts":counts,"independentInterpretation":campaign["interpretation"],
            "originalArchiveIdentities":campaign["archives"],"originalScenarios":retained})
    result={"schema":1,"primaryScoreRun":39434946667,"historicalCampaigns":campaigns,
        "rule":"Original raw records and failures remain separate from independent interpretation and the primary measurement; no score pooling.",
        "scope":"Compact durable record of all 135 historical classifications, original failure reasons/traces and available physical/effect witnesses. Full original archives retain separate identities."}
    content=review.canonical(result).decode()+"\n"
    review.require(len(content)<6_000_000,"Historical compact evidence bound")
    review.emit_file("evidence/rb-generalization-attempt-history.json",content)
    print("HISTORY_RESULT "+json.dumps({"campaigns":len(campaigns),"originalRecords":sum(len(c["originalScenarios"]) for c in campaigns),
        "rawFailRecordsRetained":sum(c["originalRawCounts"].get("FAIL",0) for c in campaigns),"bytes":len(content),
        "sha256":review.sha(content.encode())},separators=(",",":")),flush=True)

if __name__=="__main__":
    main()
