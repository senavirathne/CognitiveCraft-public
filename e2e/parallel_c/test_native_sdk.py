import hashlib
from pathlib import Path
import tempfile
import unittest

from common import EvidenceError
from native.compile import client_libraries


class NativeSdkTests(unittest.TestCase):
    def test_missing_required_library_is_rejected(self):
        version = {"libraries": [{"downloads": {"artifact": {"path": "required.jar", "sha1": "0" * 40}}}]}
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaisesRegex(EvidenceError, "missing required client SDK library"):
                client_libraries(version, Path(root))

    def test_changed_library_is_rejected(self):
        original = b"checksum fixture for SDK validation, not native evidence"
        version = {"libraries": [{"downloads": {"artifact": {"path": "required.jar", "sha1": hashlib.sha1(original).hexdigest()}}}]}
        with tempfile.TemporaryDirectory() as root:
            p = Path(root) / "libraries/required.jar"; p.parent.mkdir(); p.write_bytes(original)
            self.assertEqual([p], client_libraries(version, Path(root)))
            p.write_bytes(original + b" changed")
            with self.assertRaisesRegex(EvidenceError, "SDK library checksum"):
                client_libraries(version, Path(root))

    def test_non_linux_library_is_not_required(self):
        version = {"libraries": [{"rules": [{"action": "allow", "os": {"name": "windows"}}],
                                  "downloads": {"artifact": {"path": "windows.jar", "sha1": "0" * 40}}}]}
        with tempfile.TemporaryDirectory() as root:
            self.assertEqual([], client_libraries(version, Path(root)))
