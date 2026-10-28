from pathlib import Path
import tempfile
import unittest

from common import EvidenceError
from docs_check import check_links


class DocsTests(unittest.TestCase):
    def test_owned_link_and_fragment(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); owned = root / "docs/implementation/parallel-c"; owned.mkdir(parents=True)
            (owned / "a.md").write_text('[evidence](b.md#measured-results)\n')
            (owned / "b.md").write_text('# Measured results\n')
            self.assertEqual(1, check_links(root, "docs/implementation/parallel-c", "C00", False)["links"])
    def test_missing_fragment_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); (root / "owned").mkdir()
            (root / "owned/a.md").write_text('[missing](#not-a-heading)\n')
            with self.assertRaisesRegex(EvidenceError, "fragment"): check_links(root, "owned", "C00", False)
