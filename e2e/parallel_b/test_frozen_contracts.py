"""B00 integrity checks: definitions and diagnostic expectations are not gameplay."""
import hashlib
import json
from collections import Counter
from pathlib import Path
import unittest

ROOT = Path(__file__).parent


class FrozenContractTests(unittest.TestCase):
    def test_original_corpus_and_denominators_are_preserved(self):
        freeze = json.loads((ROOT / "freeze.json").read_bytes())
        cases = json.loads((ROOT / "frozen/original-schedule.json").read_bytes())
        self.assertEqual(334, len(cases))
        self.assertEqual(334, len({c["id"] for c in cases}))
        self.assertEqual({False:316, True:18}, dict(Counter(c["heldOut"] for c in cases)))
        self.assertEqual({20261024}, {c["seed"] for c in cases if not c["heldOut"]})
        self.assertEqual({3816727308372376789}, {c["seed"] for c in cases if c["heldOut"]})
        self.assertEqual({"scheduled":334,"attempted":34,"terminalObserved":33,"notExecuted":300,
                          "heldOutAttempted":0,"restartCasesAttempted":0}, freeze["baseline"])
        for name, expected in freeze["files"].items():
            data = (ROOT / name).read_bytes()
            self.assertEqual(expected, {"sha256":hashlib.sha256(data).hexdigest(),"bytes":len(data)}, name)

    def test_original_material_cases_have_exact_independent_arithmetic(self):
        freeze = json.loads((ROOT / "freeze.json").read_bytes())
        cases = {c["id"]: c for c in json.loads((ROOT / "frozen/original-schedule.json").read_bytes())}
        for expected in freeze["materialCases"]:
            case = cases[expected["id"]]
            self.assertEqual(expected["requested"], case["quantity"])
            self.assertEqual(expected["output"], case["output"])
            self.assertEqual(expected["physical"], expected["requested"] + expected["surplus"])
            self.assertEqual(0, expected["physical"] % 4)
            self.assertEqual(set(expected["input"]), {x for x in case["grid"] if x})

    def test_semantic_fixtures_are_canonical_and_synthetic(self):
        for path in (ROOT / "fixtures").glob("*.json"):
            data = path.read_bytes(); value = json.loads(data)
            self.assertEqual(data, json.dumps(value, sort_keys=True, separators=(",", ":")).encode())
            self.assertTrue(value["id"].startswith("fixture:"))
            self.assertEqual(1, value["schema"])
        q = json.loads((ROOT / "fixtures/q3-y4.json").read_bytes())
        demand = q["goal"]["requested"]; per = q["recipe"]["yield"]
        self.assertEqual(q["recipe"]["batches"], demand // per + int(demand % per != 0))
        self.assertEqual(q["recipe"]["physicalOutput"], q["proposal"]["goalAllocation"] + q["proposal"]["surplus"])
        observed = json.loads((ROOT / "fixtures/observation-not-proof.json").read_bytes())
        self.assertFalse(observed["host"]["saveConfirmed"])
        self.assertFalse(observed["expected"]["durableProof"])


if __name__ == "__main__":
    unittest.main()
