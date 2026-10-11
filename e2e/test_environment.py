"""Archive integrity regressions; synthetic fixtures are never gameplay evidence."""
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

from environment import pack, restore
from runtime import digest, semantic_jar


class EnvironmentIntegrityTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.jar = self.root / "parser-fixture.jar"
        with ZipFile(self.jar, "w") as out:
            out.writestr("synthetic.class", b"parser fixture; not production")
        self.seed = self.root / "seed"
        world = self.seed / "game/world/level.dat"
        world.parent.mkdir(parents=True)
        world.write_bytes(b"synthetic parser fixture; not a Minecraft world")
        certificate = {"schema":1,"fixture":"F1","minecraft":"26.3",
            "producerCommit":"synthetic","producerJarSha256":digest(self.jar),
            "semanticJarSha256":semantic_jar(self.jar),
            "citizens":{"PlayerA":"synthetic-a","PlayerB":"synthetic-b"},
            "acquisition":{"artifact":"synthetic","generationCalls":1},
            "offlineChangedBindingReuse":{"artifact":"synthetic","generationCalls":0},
            "files":[{"path":"world/level.dat","sha256":digest(world)}]}
        (self.seed / "certificate.json").write_text(json.dumps(certificate))
        self.archive, self.manifest = self.root / "arena.zip", self.root / "arena.json"
        pack(self.seed,self.archive,self.manifest,self.jar,
             "https://github.com/senavirathne/CognitiveCraft-public/actions/runs/0")
        self.destination = self.root / "restored"

    def test_round_trip_and_refuse_existing_destination(self):
        restore(self.archive,self.manifest,self.destination,self.jar)
        self.assertEqual((self.seed / "certificate.json").read_bytes(),
                         (self.destination / "certificate.json").read_bytes())
        with self.assertRaisesRegex(ValueError,"new directory"):
            restore(self.archive,self.manifest,self.destination,self.jar)

    def test_changed_production_contents_require_new_acquisition(self):
        with ZipFile(self.jar,"a") as out:
            out.writestr("new.class",b"different implementation")
        with self.assertRaisesRegex(ValueError,"contents changed"):
            restore(self.archive,self.manifest,self.destination,self.jar)
        self.assertFalse(self.destination.exists())

    def test_archive_corruption_is_rejected(self):
        self.archive.write_bytes(self.archive.read_bytes() + b"changed")
        with self.assertRaisesRegex(ValueError,"archive integrity"):
            restore(self.archive,self.manifest,self.destination,self.jar)

    def test_traversal_is_rejected_even_with_matching_archive_digest(self):
        with ZipFile(self.archive,"a") as out:
            out.writestr("../escape",b"unsafe")
        record = json.loads(self.manifest.read_text())
        record.update(archiveBytes=self.archive.stat().st_size,archiveSha256=digest(self.archive))
        self.manifest.write_text(json.dumps(record))
        with self.assertRaisesRegex(ValueError,"Unsafe arena"):
            restore(self.archive,self.manifest,self.destination,self.jar)
        self.assertFalse((self.root / "escape").exists())

    def test_changed_saved_world_cannot_be_certified(self):
        (self.seed / "game/world/level.dat").write_bytes(b"changed saved file")
        with self.assertRaisesRegex(ValueError,"Saved-world integrity"):
            pack(self.seed,self.archive,self.manifest,self.jar,
                 "https://github.com/senavirathne/CognitiveCraft-public/actions/runs/0")
