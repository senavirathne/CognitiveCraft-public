from pathlib import Path
import subprocess
import tempfile
import unittest

from common import EvidenceError
from docs_check import check_links


class DocsTests(unittest.TestCase):
    def test_missing_untracked_target_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); (root / "owned").mkdir()
            (root / "owned/a.md").write_text('[missing](absent.zip)\n')
            with self.assertRaisesRegex(EvidenceError, "broken owned link"): check_links(root, "owned", "C01", False)

    def test_sparse_target_must_be_an_actual_committed_blob(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); (root / "owned").mkdir()
            (root / "owned/a.md").write_text('[archive](raw.zip)\n')
            archive = root / "owned/raw.zip"; archive.write_bytes(b'synthetic link fixture, not qualification evidence')
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            subprocess.run(["git", "-C", str(root), "add", "."], check=True)
            subprocess.run(["git", "-C", str(root), "-c", "user.name=C fixture", "-c", "user.email=c@example.invalid", "commit", "-qm", "synthetic sparse link"], check=True)
            archive.unlink()
            with self.assertRaisesRegex(EvidenceError, "broken owned link"): check_links(root, "owned", "C01", False)
            subprocess.run(["git", "-C", str(root), "update-index", "--skip-worktree", "owned/raw.zip"], check=True)
            result = check_links(root, "owned", "C01", False)
            self.assertEqual(["owned/raw.zip"], result["sparse_targets"])
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
