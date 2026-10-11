"""Parser/gate regressions only; these synthetic reports are never gameplay evidence."""
import contextlib
import io
import hashlib
import json
from pathlib import Path
import tempfile
import struct
import unittest
import zlib
import xml.etree.ElementTree as ET

from qualify import qualify
from suite import FAMILIES


class QualificationTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        coverage = json.loads(Path(__file__).with_name("coverage.json").read_text())
        variants = {v:{"status":"PASS"} for u in coverage["units"] for v in u.get("requiredVariants",[])}
        self.results = {"profile":"all","results":[{"id":f,"status":"PASS","evidence":{"variants":variants}}
                                                    for f in FAMILIES]}
        for row in self.results["results"]:
            if row["id"] == "E2E-AI-001": row["evidence"].update({"acquisition":{"generationCalls":1},
                    "modelFreeReuse":{"generationCalls":0},"models":{"syntheticParserFixture":True}})
            if row["id"] == "E2E-NLU-001": row["evidence"]["needleCalls"]=3
        self.write("results.json",self.results)
        self.metadata = {"sourceCommit":"tested","reviewedHeadCommit":"head","sourceTree":"tree",
                         "sourceBranch":"review","releaseSha256":"jar","dirty":False,
                         "binaries":{n:{"sha256":"jar","bytes":1} for n in
                            ("production","clientDriver","observer","officialClient","officialServer")}}
        self.write("metadata.json",self.metadata)
        # Synthetic PNG is a parser fixture only, never an executed gameplay artifact.
        def chunk(kind,data):
            return struct.pack(">I",len(data))+kind+data+struct.pack(">I",zlib.crc32(kind+data))
        png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR",struct.pack(">IIBBBBB",800,600,8,2,0,0,0)) + \
                chunk(b"IDAT",zlib.compress((b"\x00"+b"\xff"*2400)*600)) + chunk(b"IEND",b"")
        (self.root/"parser-fixture.png").write_bytes(png)
        for row in self.results["results"]:
            row["screenshots"]=[{"path":"parser-fixture.png","sha256":hashlib.sha256(png).hexdigest(),
                "family":row["id"],"nativeClientFrame":True,"sourceCommit":"tested","releaseSha256":"jar"}]
        self.write("results.json",self.results)
        self.owners = {"headCommit":"head","runs":[{"workflow":w,"status":"PASS","liveAI":{"status":"PASS"}}
                       for w in coverage["ownerWorkflows"]]}
        self.write("owner-evidence.json",self.owners)
        for suite in {t["suite"] for u in coverage["units"] for t in u["componentTests"]}:
            xml = ET.Element("testsuite");ET.SubElement(xml,"testcase",name="synthetic-parser-fixture")
            ET.ElementTree(xml).write(self.root / ("TEST-"+suite+".xml"))

    def write(self,path,value):
        (self.root/path).write_text(json.dumps(value))

    def result(self):
        with contextlib.redirect_stdout(io.StringIO()):
            accepted = qualify(self.root)
        return accepted,json.loads((self.root/"qualification.json").read_text())

    def test_internal_owner_success_never_becomes_network_pass(self):
        accepted,proof=self.result();self.assertTrue(accepted)
        units={u["unit"]:u for u in proof["implementationCoverage"]}
        for name in ("IMP-018","IMP-019"):
            self.assertEqual("PASS",units[name]["unit_result"])
            self.assertEqual("NOT_CLIENT_EXPOSED",units[name]["network_E2E_result"])
        self.assertEqual("BLOCKED_BY_IMPLEMENTATION",units["IMP-029"]["network_E2E_result"])

    def test_missing_network_family_cannot_pass_on_all_green_owner_workflows(self):
        self.results["results"].pop();self.write("results.json",self.results)
        self.assertFalse(self.result()[0])

    def test_skipped_component_case_fails_qualification(self):
        path=next(self.root.glob("TEST-*.xml"));xml=ET.parse(path)
        ET.SubElement(next(xml.getroot().iter("testcase")),"skipped");xml.write(path)
        self.assertFalse(self.result()[0])

    def test_duplicate_family_cannot_mask_missing_proof(self):
        self.results["results"].append(self.results["results"][0]);self.write("results.json",self.results)
        self.assertFalse(self.result()[0])

    def test_obsolete_owner_head_cannot_qualify_current_binary(self):
        self.owners["headCommit"]="obsolete";self.write("owner-evidence.json",self.owners)
        self.assertFalse(self.result()[0])

    def test_missing_later_unit_variant_fails_baseline_family_success(self):
        for row in self.results["results"]:row["evidence"]["variants"].pop("GP-E2E-15",None)
        self.write("results.json",self.results)
        self.assertFalse(self.result()[0])

    def test_missing_native_screenshot_cannot_qualify_passing_assertions(self):
        self.results["results"][0]["screenshots"]=[];self.write("results.json",self.results)
        self.assertFalse(self.result()[0])

    def test_changed_png_fails_hash_check(self):
        path=self.root/"parser-fixture.png";path.write_bytes(path.read_bytes()+b"changed")
        self.assertFalse(self.result()[0])

    def test_obsolete_screenshot_source_cannot_qualify(self):
        self.results["results"][0]["screenshots"][0]["sourceCommit"]="old";self.write("results.json",self.results)
        self.assertFalse(self.result()[0])

    def test_green_family_without_actual_generation_measurement_fails(self):
        for row in self.results["results"]:
            if row["id"] == "E2E-AI-001": row["evidence"]["acquisition"]["generationCalls"]=0
        self.write("results.json",self.results)
        self.assertFalse(self.result()[0])

    def test_inconsistent_release_envelope_fails(self):
        second=self.root/"second";second.mkdir()
        (second/"results.json").write_text(json.dumps(self.results))
        self.metadata["releaseSha256"]="other"
        (second/"metadata.json").write_text(json.dumps(self.metadata))
        self.assertFalse(self.result()[0])


if __name__ == "__main__":
    unittest.main()
