import hashlib
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest

from common import EvidenceError
from ownership import ancestry, audit_trees, constant_pool


class OwnershipTests(unittest.TestCase):
    def test_one_byte_protected_edit_is_rejected(self):
        frozen, mutated = b"plugins {}\n", b"plugins []\n"
        mutated = frozen.replace(b"{", b"[", 1)
        self.assertEqual(1, sum(a != b for a, b in zip(frozen, mutated)))
        def blob(data): return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
        before = {"core/build.gradle": ("100644", blob(frozen))}
        after = {"core/build.gradle": ("100644", blob(mutated))}
        with self.assertRaisesRegex(EvidenceError, "protected baseline"):
            audit_trees(before, after)

    def test_protected_mode_edit_is_rejected(self):
        with self.assertRaisesRegex(EvidenceError, "protected baseline"):
            audit_trees({"gradlew": ("100755", "a" * 40)}, {"gradlew": ("100644", "a" * 40)})

    def test_allowed_addition_preserves_baseline(self):
        base = {"core/build.gradle": ("100644", "a" * 40)}
        audit_trees(base, {**base, "core/src/main/java/dev/aivillages/core/kernel/interaction/Test.java": ("100644", "b" * 40)})

    def test_unowned_addition_is_rejected(self):
        with self.assertRaisesRegex(EvidenceError, "unowned new"):
            audit_trees({}, {"core/src/main/java/dev/aivillages/core/kernel/Jobs.java": ("100644", "a" * 40)})

    def test_imported_peer_commit_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            def git(*args):
                return subprocess.check_output(["git", "-C", temp, *args], stderr=subprocess.DEVNULL, text=True).strip()
            git("init", "-q"); git("config", "user.name", "C ownership negative control")
            git("config", "user.email", "c-test@example.invalid")
            (root / "baseline").write_text("frozen\n"); git("add", "."); git("commit", "-qm", "base")
            base = git("rev-parse", "HEAD")
            git("switch", "-qc", "feature/storage-mobility-logistics")
            p = root / "e2e/parallel_c/imported.txt"; p.parent.mkdir(parents=True); p.write_text("same namespace cannot hide peer history")
            git("add", "."); git("commit", "-qm", "peer commit")
            peer = git("rev-parse", "HEAD")
            git("switch", "-qc", "feature/player-assisted-dialogue", base); git("cherry-pick", peer)
            with self.assertRaisesRegex(EvidenceError, "imported peer"):
                ancestry(root, base)

    def test_core_constant_pool_dependency_is_rejected(self):
        descriptor = b"(Lnet/minecraft/server/MinecraftServer;)V"
        data = b"\xca\xfe\xba\xbe" + struct.pack(">HHH", 0, 69, 2) + b"\x01" + struct.pack(">H", len(descriptor)) + descriptor
        with self.assertRaisesRegex(EvidenceError, "bytecode dependency"):
            constant_pool(data)
