"""Evidence integrity regressions using parser fixtures, never gameplay proof."""
import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import zlib

from rb_qualify import (MANIFEST, check_baseline, check_screenshots, evidence_member, passing_cases,
                        latest_jobs, qualify, select_runs, selected_native_cases, unpack)
from suite import FAMILIES, PROFILES


class ResourceQualificationTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.manifest = json.loads(MANIFEST.read_text())
        self.baseline = {"status": "PASS", "consistentExactBinary": True, "ownerSourceConsistency": True,
            "families": {f: {"status": "PASS"} for f in FAMILIES}, "identities": [{"sourceCommit": "head",
            "sourceTree": "tree", "reviewedHeadCommit": "head", "releaseSha256": "jar", "dirty": False}]}

    def test_resource_bindings_use_existing_distinct_profiles_and_owner_verifiers(self):
        self.assertEqual(9, len(self.manifest["units"]))
        self.assertEqual(9, len({u["ownerWorkflow"] for u in self.manifest["units"]}))
        for unit in self.manifest["units"]:
            self.assertEqual([unit["family"]], PROFILES[unit["profile"]])
        self.assertFalse(self.manifest["conditionalLifecycle"]["requiredForResourceCampaign"])

    def test_other_commit_or_other_event_owner_cannot_substitute(self):
        run = {"id": 1, "path": ".github/workflows/oak-log-acquisition.yml", "head_sha": "head", "event": "pull_request"}
        choices = [run, {**run, "id": 2, "head_sha": "old"}, {**run, "id": 3, "event": "push"}]
        selected = select_runs(choices, ["oak-log-acquisition.yml"], "head", "pull_request")
        self.assertEqual(1, selected["oak-log-acquisition.yml"]["id"])

    def test_partial_retry_preserves_other_success_and_retains_no_stale_failure_as_current(self):
        history = [{"id": 1, "name": "native", "run_attempt": 1, "conclusion": "failure"},
                   {"id": 2, "name": "client", "run_attempt": 1, "conclusion": "success"},
                   {"id": 3, "name": "native", "run_attempt": 2, "conclusion": "success"}]
        selected = {j["name"]: j for j in latest_jobs(history)}
        self.assertEqual(2, len(selected))
        self.assertEqual(3, selected["native"]["id"])
        self.assertEqual(2, selected["client"]["id"])
        self.assertTrue(all(j["conclusion"] == "success" for j in selected.values()))

    def test_shared_baseline_rejects_stale_source_dirty_checkout_and_mixed_jars(self):
        for key, value in (("sourceCommit", "old"), ("sourceTree", "old"), ("reviewedHeadCommit", "old"), ("dirty", True)):
            baseline = copy.deepcopy(self.baseline)
            baseline["identities"][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                check_baseline(baseline, "head", "tree")
        self.baseline["identities"].append({**self.baseline["identities"][0], "releaseSha256": "other"})
        with self.assertRaises(ValueError):
            check_baseline(self.baseline, "head", "tree")

    def test_wrong_thirteen_family_names_do_not_qualify(self):
        self.baseline["families"].pop(FAMILIES[0])
        self.baseline["families"]["GP-RB-01"] = {"status": "PASS"}
        with self.assertRaises(ValueError):
            check_baseline(self.baseline, "head", "tree")

    def test_green_baseline_and_eight_resources_cannot_hide_missing_ninth(self):
        (self.root / "baseline").mkdir()
        (self.root / "baseline/qualification.json").write_text(json.dumps(self.baseline))
        owners = {"headCommit": "head", "runs": [{"unit": u["unit"]} for u in self.manifest["units"][:-1]]}
        # Stub only expensive artifact inspection; coverage integrity must still reject the omission.
        with patch("rb_qualify.check_unit", side_effect=lambda root, unit, *args: {"unit": unit["unit"], "status": "PASS"}), \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertFalse(qualify(self.root, self.manifest, owners, "head", "tree"))
        self.assertEqual("FAIL", json.loads((self.root / "rb-series-qualification.json").read_text())["status"])

    def test_skipped_native_case_is_not_a_pass(self):
        xml = self.root / "native.xml"
        xml.write_text('<testsuite><testcase name="physical"><skipped/></testcase></testsuite>')
        with self.assertRaises(ValueError):
            passing_cases(xml)

    def test_expanded_native_matrix_requires_every_unique_selected_case(self):
        xml = self.root / "placement.xml"
        phase = {"className": "placement_effect_game_tests", "cases": 5}
        names = ["ai_villages_tests:placement_effect_game_tests_" + str(i) for i in range(5)]
        def write(names, skipped=False):
            xml.write_text('<testsuite>' + ''.join('<testcase name="' + n + '">' +
                ('<skipped/>' if skipped and i == 0 else '') + '</testcase>' for i,n in enumerate(names)) + '</testsuite>')
        write(names)
        self.assertEqual(5, len(selected_native_cases(xml, phase)))
        for invalid in (names[:-1], names + [names[0] + 'extra'], names[:-1] + [names[0]], names[:-1] + ['ai_villages_tests:other_class']):
            write(invalid)
            with self.assertRaises(ValueError):
                selected_native_cases(xml, phase)
        write(names, skipped=True)
        with self.assertRaises(ValueError):
            selected_native_cases(xml, phase)

    def test_declared_native_owners_cannot_be_replaced_by_equal_count_foreign_cases(self):
        xml = self.root / "declared.xml"
        names = ["ai_villages_tests:placement_owner", "ai_villages_tests:recipe_owner"]
        phase = {"className": "", "cases": 2, "caseNames": names}
        def write(values):
            xml.write_text('<testsuite>' + ''.join('<testcase name="' + n + '"/>' for n in values) + '</testsuite>')
        write(names)
        self.assertEqual(2, len(selected_native_cases(xml, phase)))
        write([names[0], "ai_villages_tests:foreign_owner"])
        with self.assertRaises(ValueError):
            selected_native_cases(xml, phase)
        write(names)
        with self.assertRaises(ValueError):
            selected_native_cases(xml, {**phase, "caseNames": [names[0], names[0]]})

    def test_other_native_phases_still_require_one_case(self):
        xml = self.root / "native.xml"
        xml.write_text('<testsuite><testcase name="ai_villages_tests:owner_a"/><testcase name="ai_villages_tests:owner_b"/></testsuite>')
        with self.assertRaises(ValueError):
            selected_native_cases(xml, {"className": "owner"})

    def test_archive_cannot_escape_import_directory(self):
        archive = self.root / "unsafe.zip"
        with zipfile.ZipFile(archive, "w") as output:
            output.writestr("../escaped.json", "{}")
        with self.assertRaises(ValueError):
            unpack(archive, self.root / "import")
        self.assertFalse((self.root / "escaped.json").exists())

    def test_old_extracted_files_cannot_supply_missing_new_evidence(self):
        archive = self.root / "new.zip"
        with zipfile.ZipFile(archive, "w") as output:
            output.writestr("identity.json", "{}")
        destination = self.root / "import"
        destination.mkdir()
        (destination / "old-results.json").write_text('{"status":"PASS"}')
        with self.assertRaises(ValueError):
            unpack(archive, destination)

    def test_large_raw_observation_trace_does_not_displace_required_client_evidence(self):
        archive = self.root / "client.zip"
        prefix = "build/e2e/rb005/"
        required = [prefix + name for name in ("metadata.json", "results.json", "junit.xml",
                    "clients/PlayerA/screenshots/original.png", "clients/PlayerB/screenshots/original.png")]
        required.append("fabric/build/libs/ai-villages-0.1.0.jar")
        trace = prefix + "server/observations.jsonl"
        with zipfile.ZipFile(archive, "w") as output:
            for name in required:
                output.writestr(name, b"retained")
            output.writestr(trace, b"x" * 2000)
        destination = self.root / "import"
        with patch("rb_qualify.MAX_IMPORTED_BYTES", 1000):
            unpack(archive, destination, lambda path: evidence_member(path, "rb005", "client"))
        self.assertTrue(all((destination / name).read_bytes() == b"retained" for name in required))
        self.assertFalse((destination / trace).exists())
        with zipfile.ZipFile(archive) as source:
            self.assertEqual(2000, len(source.read(trace)))

    def test_selected_evidence_still_obeys_expansion_bound(self):
        archive = self.root / "oversized.zip"
        with zipfile.ZipFile(archive, "w") as output:
            output.writestr("build/e2e/rb005/results.json", b"x" * 1001)
        with patch("rb_qualify.MAX_IMPORTED_BYTES", 1000), self.assertRaises(ValueError):
            unpack(archive, self.root / "import", lambda path: evidence_member(path, "rb005", "client"))

    def test_native_import_retains_reports_and_original_phase_evidence(self):
        for path in ("build/rb005-evidence/genuine-cold.log", "build/rb005-evidence/identity.json",
                     "core/build/test-results/test/TEST-owner.xml", "providers/build/test-results/test/TEST-owner.xml",
                     "fabric/build/test-results/test/TEST-owner.xml", "fabric/build/libs/ai-villages-0.1.0.jar"):
            self.assertTrue(evidence_member(PurePosixPath(path), "rb005", "native"))
        for path in ("fabric/build/gametest-run/world/region/r.0.0.mca", "build/rb004-evidence/identity.json"):
            self.assertFalse(evidence_member(PurePosixPath(path), "rb005", "native"))

    def test_resource_capture_requires_actual_bytes_and_current_source(self):
        def chunk(kind, data):
            return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
        png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 800, 600, 8, 2, 0, 0, 0)) + \
            chunk(b"IDAT", zlib.compress((b"\x00" + b"\xff" * 2400) * 600)) + chunk(b"IEND", b"")
        (self.root / "parser-fixture.png").write_bytes(png)
        capture = {"path": "parser-fixture.png", "family": "GP-RB-08", "sourceCommit": "head",
                   "releaseSha256": "jar", "nativeClientFrame": True, "sha256": hashlib.sha256(png).hexdigest()}
        row = {"id": "GP-RB-08", "screenshots": [capture]}
        metadata = {"sourceCommit": "head", "releaseSha256": "jar"}
        self.assertEqual(1, len(check_screenshots(self.root, row, metadata)))
        capture["sourceCommit"] = "old"
        with self.assertRaises(ValueError):
            check_screenshots(self.root, row, metadata)
        capture["sourceCommit"] = "head"
        (self.root / "parser-fixture.png").write_bytes(png + b"modified")
        with self.assertRaises(ValueError):
            check_screenshots(self.root, row, metadata)


if __name__ == "__main__":
    unittest.main()
