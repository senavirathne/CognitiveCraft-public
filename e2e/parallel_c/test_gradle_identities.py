import json
from pathlib import Path
import tempfile
import unittest

from common import EvidenceError
from verify import xml_cases


class GradleIdentityTests(unittest.TestCase):
    def files(self, root, names, xml_count=2):
        root = Path(root)
        xml = root / "tests.xml"
        xml.write_text('<testsuite tests="' + str(xml_count) + '" failures="0" errors="0" skipped="0">'
                       + '<testcase classname="C" name="[1] BEFORE_STAGE"/>' * xml_count + '</testsuite>')
        identity = root / "identities.jsonl"
        rows = [{"id": 1, "parent": None, "name": "root", "display": "root", "class": None,
                 "outcome": "SUCCESS", "failures": 0, "start": 1, "end": 2}]
        rows += [{"id": i, "parent": 1, "name": name, "display": "[1] BEFORE_STAGE", "class": "C",
                  "outcome": "SUCCESS", "failures": 0, "start": 1, "end": 2} for i, name in enumerate(names, 2)]
        identity.write_text(''.join(json.dumps(row) + '\n' for row in rows))
        return xml, identity

    def test_distinct_methods_with_same_display_name_remain_distinct(self):
        with tempfile.TemporaryDirectory() as root:
            xml, identities = self.files(root, ["firstCut(Point)[1]", "secondCut(Point)[1]"])
            self.assertEqual({"C#firstCut(Point)[1]", "C#secondCut(Point)[1]"}, set(xml_cases([xml], [identities])))

    def test_duplicate_stable_method_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            xml, identities = self.files(root, ["firstCut(Point)[1]", "firstCut(Point)[1]"])
            with self.assertRaisesRegex(EvidenceError, "duplicate Gradle stable case"):
                xml_cases([xml], [identities])

    def test_extra_xml_row_cannot_inflate_binary_count(self):
        with tempfile.TemporaryDirectory() as root:
            xml, identities = self.files(root, ["firstCut(Point)[1]", "secondCut(Point)[1]"], 3)
            with self.assertRaisesRegex(EvidenceError, "reconciliation mismatch"):
                xml_cases([xml], [identities])
