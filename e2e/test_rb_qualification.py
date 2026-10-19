"""Evidence integrity regressions using parser fixtures, never gameplay proof."""
import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import zlib

from rb_qualify import (MANIFEST, check_baseline, check_screenshots, passing_cases,
                        qualify, select_runs, unpack)
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
