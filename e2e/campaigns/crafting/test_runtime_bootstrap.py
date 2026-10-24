"""Guard actual observer startup contracts; synthetic inputs are not gameplay proof."""
from __future__ import annotations

import json
from pathlib import Path
import re
import sys
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

E2E = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(E2E))
from campaigns.crafting import harness
from suite import atomic
from campaigns.crafting.execution import fixture_observation


class ObserverBootstrapTests(unittest.TestCase):
    def test_stale_server_fixture_cannot_create_a_false_table_resource_delta(self):
        snapshots = iter([{"blocks": {}}, {"blocks": {"72,208,69": "Block{minecraft:crafting_table}"}}])

        class Observers:
            calls = 0
            def snapshot(self):
                self.calls += 1
                return next(snapshots)
            def client_snapshot(self, player):
                return {"blocks": {"72,208,69": "Block{minecraft:crafting_table}"}}
            def wait(self, predicate, reason, seconds):
                return predicate() or predicate()

        observer = Observers()
        before, client = fixture_observation(observer, {"72,208,69"})
        self.assertEqual(observer.calls, 2)
        self.assertEqual(before["blocks"], client["blocks"])

    def test_absent_facility_requires_both_observers_to_acknowledge_removal(self):
        class Observers:
            calls = 0
            def snapshot(self):
                return {"blocks": {}}
            def client_snapshot(self, player):
                self.calls += 1
                return {"blocks": {"old": "Block{minecraft:crafting_table}"}} if self.calls == 1 else {"blocks": {}}
            def wait(self, predicate, reason, seconds):
                return predicate() or predicate()

        observer = Observers()
        before, client = fixture_observation(observer, set())
        self.assertEqual(observer.calls, 2)
        self.assertEqual(before["blocks"], client["blocks"])

    def test_campaign_initialization_supplies_all_shared_observer_inputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sdk = root / "sdk"
            sdk.mkdir()
            # Only the official-data parser's input contract is exercised here.
            with ZipFile(sdk / "minecraft-client.jar", "w") as archive:
                for name in ("stick", "wooden_axe", "crafting_table", "bread",
                             "oak_planks", "birch_planks"):
                    archive.writestr("data/minecraft/recipe/" + name + ".json",
                                     json.dumps({"type": "minecraft:crafting_shaped",
                                                 "pattern": ["#"]}))

            def initialize_base(instance, directory, topology, seed):
                instance.root, instance.env = directory, {}
                atomic(directory / "metadata.json", {})

            with patch.object(harness.Harness, "__init__", initialize_base), \
                    patch.object(harness, "SDK", sdk), \
                    patch.object(harness.navigation, "LEVEL", harness.navigation.LEVEL):
                harness.CraftingHarness(root, "synthetic-frozen-baseline")

            language_file = root / "assessment-language.json"
            language = json.loads(language_file.read_text())
            self.assertEqual(len(language), 5)
            self.assertLessEqual(language_file.stat().st_size, 4096)
            self.assertTrue(all(isinstance(text, str) and text for text in language.values()))
            self.assertEqual(json.loads((root / "observation-area.json").read_text()),
                             harness.AREA)
            self.assertEqual(len(json.loads((root / "oracle-candidates.json").read_text())), 6)

    def test_reserved_mixin_namespace_excludes_normal_observer_classes(self):
        root = Path(__file__).parent / "observer"
        config = json.loads((root / "crafting-trace.mixins.json").read_text())
        mixin_classes = set()
        normal_classes = set()
        for path in (root / "java").rglob("*.java"):
            text = path.read_text()
            package = re.search(r"^package ([\w.]+);", text, re.MULTILINE).group(1)
            name = package + "." + path.stem
            is_mixin = "@Mixin(" in text
            inside_reserved = name.startswith(config["package"] + ".")
            self.assertEqual(inside_reserved, is_mixin, name)
            if is_mixin:
                mixin_classes.add(name.removeprefix(config["package"] + "."))
            else:
                normal_classes.add(path.stem)
        self.assertEqual(mixin_classes, set(config["mixins"]))
        self.assertTrue({"CraftingRecipeOracle", "CraftingPrimitiveTrace"} <= normal_classes)


if __name__ == "__main__":
    unittest.main()
