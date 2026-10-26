"""Adversarial collection checks; these synthetic records are not native evidence."""
import copy
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from artifact_selection import PROFILES, select, SelectionError
from parallel_b.guards import clean_owners, settled_owner, stopped_process, clock_response, observer_inputs
from parallel_b.qualification import verify


class OwnerAndClockTests(unittest.TestCase):
    def test_interrupted_cancelled_ack_and_uncertain_terminal_are_not_settled(self):
        owner = {"state":"INTERRUPTED", "attempts":[{"terminal":None,"uncertain":True}]}
        for state in ("INTERRUPTED", "CANCELLING", "ACTIVE", "FAILED", "CANCELLED", "SUCCEEDED"):
            owner["state"] = state
            self.assertFalse(settled_owner(owner), state)
        owner.update(state="CANCELLED", attempts=[{"terminal":{"reason":"CANCELLED"},"uncertain":False}])
        self.assertTrue(settled_owner(owner))
        self.assertTrue(clean_owners({"jobs":{"one":owner},"runs":{}}))
        owner["attempts"][0]["uncertain"] = True
        self.assertFalse(clean_owners({"jobs":{},"runs":{"direct":{"phase":"TERMINAL","responsibility":owner}}}))
        self.assertFalse(clean_owners({"jobs":{},"runs":{"direct":{"phase":"TERMINAL"}}}))
        self.assertFalse(clean_owners({}))

    def test_rejected_clock_or_mailbox_delivery_cannot_count_as_execution(self):
        for value in ("sent=true", "Incorrect argument for command", "Unknown command", "The game is unfrozen"):
            with self.assertRaises(ValueError): clock_response("tick freeze", value)
        self.assertTrue(clock_response("tick freeze", "The game is now frozen"))
        self.assertTrue(clock_response("tick unfreeze", "The game is now unfrozen"))

    def test_kill_requires_native_process_finished_state_and_sigkill_exit(self):
        state = {"Running":False,"Pid":0,"ExitCode":137,"FinishedAt":"2026-10-26T00:00:00Z"}
        self.assertTrue(stopped_process(state,hard=True))
        for field, value in (("Running",True),("Pid",17),("ExitCode",0),("FinishedAt","0001-01-01T00:00:00Z")):
            changed = {**state,field:value}
            with self.assertRaises(ValueError):stopped_process(changed,hard=True)
        with self.assertRaises(ValueError):stopped_process({"sent":True},hard=True)

    def test_observer_required_inputs_fail_before_attempts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError):observer_inputs(root)
            (root/"oracle-candidates.json").write_text('[{"id":"fixture:recipe"}]')
            self.assertTrue(observer_inputs(root))
            (root/"observation-area.json").write_text('{}')
            with self.assertRaises(ValueError):observer_inputs(root)


class QualificationTests(unittest.TestCase):
    def scene(self, root):
        def write(name, value):
            (root/name).write_text(json.dumps(value))
        write("configuration-manifest.json", {"files":[]})
        expected = {"sourceCommit":"a"*40,"sourceTree":"b"*40,"releaseSha256":"c"*64,
                    "sourceBranch":"impl/activity-production-planning","profile":"B01",
                    "GITHUB_RUN_ID":"17","GITHUB_RUN_ATTEMPT":"2","observerSha256":"d"*64,
                    "configurationSha256":hashlib.sha256((root/"configuration-manifest.json").read_bytes()).hexdigest()}
        meta = {k:expected[k] for k in ("sourceCommit","sourceTree","releaseSha256","sourceBranch","profile")}
        meta.update(dirty=False,ci={"GITHUB_RUN_ID":"17","GITHUB_RUN_ATTEMPT":"2"},
                    binaries={"production":{"sha256":"c"*64},"observer":{"sha256":"d"*64}})
        write("metadata.json",meta);write("outcome.json",{"status":"FAIL_GAMEPLAY"})
        self.manifest(root,meta)
        return expected

    def manifest(self,root,meta):
        rows=[{"path":p.name,"sha256":hashlib.sha256(p.read_bytes()).hexdigest(),"bytes":p.stat().st_size}
              for p in sorted(root.iterdir()) if p.name!="manifest.json"]
        (root/"manifest.json").write_text(json.dumps({"source":meta,"files":rows}))

    def test_intact_failure_keeps_integrity_pass_and_gameplay_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);expected=self.scene(root)
            result=verify(root,expected,required=["outcome.json"],mandatory={"craft":lambda r:False})
            self.assertEqual(("PASS","FAIL"),(result["integrity"],result["mandatoryGameplay"]))
            (root/"outcome.json").unlink()
            self.assertEqual("MISSING",verify(root,expected,required=["outcome.json"],mandatory={})["code"])

    def test_each_identity_refusal_is_distinct_without_old_success_fallback(self):
        changes=[("sourceCommit","f"*40,"FOREIGN_SOURCE"),("sourceTree","f"*40,"FOREIGN_TREE"),
                 ("releaseSha256","f"*64,"WRONG_BINARY"),("profile","lifecycle","WRONG_PROFILE")]
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);expected=self.scene(root)
            for field,value,code in changes:
                changed={**expected,field:value}
                self.assertEqual(code,verify(root,changed,required=[],mandatory={})["code"])
            self.assertEqual("STALE_ATTEMPT",verify(root,{**expected,"GITHUB_RUN_ATTEMPT":"1"},required=[],mandatory={})["code"])
            self.assertEqual("WRONG_CONFIG",verify(root,{**expected,"configurationSha256":"e"*64},required=[],mandatory={})["code"])
            self.assertEqual("WRONG_INSTRUMENT",verify(root,{**expected,"observerSha256":"e"*64},required=[],mandatory={})["code"])

    def test_dropped_mislabeled_corrupted_and_foreign_witnesses_refuse(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);expected=self.scene(root)
            manifest=json.loads((root/"manifest.json").read_text());saved=copy.deepcopy(manifest)
            manifest["files"]=[f for f in manifest["files"] if f["path"]!="outcome.json"]
            (root/"manifest.json").write_text(json.dumps(manifest))
            self.assertEqual("MISSING",verify(root,expected,required=["outcome.json"],mandatory={})["code"])
            (root/"manifest.json").write_text(json.dumps(saved));(root/"outcome.json").write_text('corruption')
            self.assertEqual("CORRUPT",verify(root,expected,required=["outcome.json"],mandatory={})["code"])
            saved["files"][0]["path"]="../foreign.json";(root/"manifest.json").write_text(json.dumps(saved))
            self.assertEqual("INVALID_PATH",verify(root,expected,required=[],mandatory={})["code"])

    def test_lifecycle_is_required_and_newest_failure_is_retained(self):
        self.assertIn("lifecycle",PROFILES)
        names=["profile-"+p for p in PROFILES]
        rows=[{"id":i+1,"name":n,"expired":False,"workflow_run":{"id":17,"head_sha":"a"*40}}
              for i,n in enumerate(names)]
        rows.append({**rows[-1],"id":99,"recordedResult":"FAIL"})
        inventory={"total_count":len(rows),"artifacts":rows}
        self.assertEqual("FAIL",select(inventory,names,17,"a"*40)[-1]["recordedResult"])
        rows[-1]["expired"]=True
        with self.assertRaises(SelectionError) as result:select(inventory,names,17,"a"*40)
        self.assertEqual("EXPIRED",result.exception.code)


if __name__=="__main__":unittest.main()
