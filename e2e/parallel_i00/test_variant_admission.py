import json
from pathlib import Path
import tempfile
import unittest

from e2e.parallel_i00.gameplay import NEW_PLANKS, NativeGameplay, variant_input
from e2e.parallel_i00.variant_admission import verify_server_frames, yield_size


class VariantAdmissionTest(unittest.TestCase):
    def test_installed_family_uses_canonical_admission_ingredient(self):
        expected = {
            "acacia": "acacia_log", "bamboo": "bamboo_block", "cherry": "cherry_log",
            "crimson": "crimson_stem", "dark_oak": "dark_oak_log", "jungle": "jungle_log",
            "mangrove": "mangrove_log", "pale_oak": "pale_oak_log", "poplar": "poplar_log",
            "spruce": "spruce_log", "warped": "warped_stem",
        }
        self.assertEqual(set(NEW_PLANKS), {"minecraft:" + material + "_planks" for material in expected})
        game = NativeGameplay.__new__(NativeGameplay)
        game.citizens = {"PlayerA": "e4c105a4-f18b-482f-84ea-f04771b37fe2"}
        game.source, game.destination = [70, 208, 69], [77, 208, 71]
        for material, target in expected.items():
            output = "minecraft:" + material + "_planks"
            ingredient = "minecraft:" + target
            self.assertEqual(variant_input(output), ingredient)
            installed = {"ingredients": [["minecraft:stripped_" + target, ingredient]], "slots": [0]}
            self.assertEqual(game.admission_grid(output, installed), [ingredient])
            quantity = yield_size(output)
            self.assertEqual(quantity, 2 if material == "bamboo" else 4)
            self.assertEqual(game.admission_command_text({"output": output, "quantity": quantity}),
                             f"/aivillage kernel make-variant-planks {material} "
                             f"{game.citizens['PlayerA']} {quantity} 70 208 69 77 208 71")
            with self.assertRaises(AssertionError):
                game.admission_grid(output, {"ingredients": [[installed["ingredients"][0][0]]], "slots": [0]})

    def test_original_native_frames_required_and_changed_frames_refused(self):
        before = {"time": 1001, "tick": 5, "pid": 14, "containers": {"70,208,69": {"minecraft:bamboo_block": 2}}}
        after = {"time": 1002, "tick": 6, "pid": 14, "containers": {"70,208,69": {}}}
        with tempfile.TemporaryDirectory() as directory:
            original = Path(directory) / "observations.jsonl"
            original.write_text("".join(json.dumps(value) + "\n" for value in (before, after)))
            verify_server_frames(original, [before, after])
            with self.assertRaises(AssertionError):
                verify_server_frames(original, [{**after, "containers": {}}])
            with self.assertRaises(AssertionError):
                verify_server_frames(original, [{**after, "time": 1003}])


if __name__ == "__main__":
    unittest.main()
