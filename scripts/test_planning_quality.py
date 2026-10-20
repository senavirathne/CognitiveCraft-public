import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('planning_quality', Path(__file__).with_name('planning-quality.py'))
QUALITY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(QUALITY)
DATASET = Path(__file__).resolve().parents[1] / 'providers/src/test/resources/planning/requests.json'


class MeasurementIntegrityTest(unittest.TestCase):
    def fixture(self, root):
        cases = json.loads(DATASET.read_text())['cases']
        for model in QUALITY.MODELS:
            for shard in range(4):
                rows = []
                for i in range(shard, 40, 4):
                    case = cases[i]
                    attempt = {'attempt': 1, 'role': 'PLANNING', 'valid': False, 'modelDigest': ('a' if model == QUALITY.MODELS[0] else 'd') * 64,
                               'inputBytes': 100, 'outputBytes': 50, 'latencyMillis': 30,
                               'prompt': 'same bounded request', 'promptSha256': hashlib.sha256(b'same bounded request').hexdigest()}
                    rows.append({'case': i, 'caseId': case['id'], 'category': case['category'], 'expectedStatus': case['expectedStatus'],
                                 'model': model, 'shard': shard, 'ciAttempt': 1, 'sourceCommit': 'b' * 40, 'sourceTree': 'c' * 40,
                                 'datasetSha256': hashlib.sha256(DATASET.read_bytes()).hexdigest(), 'firstPassValid': False,
                                 'valid': False, 'calls': 1, 'repairs': 0, 'attempts': [attempt], 'nativeEffects': 0,
                                 'dedicatedModels': 0, 'latencyMillis': 30})
                (root / f'{model.replace(":", "-")}-{shard}.jsonl').write_text(''.join(json.dumps(r) + '\n' for r in rows))

    def analyze(self, root):
        return QUALITY.analyze(root, DATASET, 'b' * 40, 'c' * 40)

    def mutate(self, root, action):
        path = sorted(root.glob('*.jsonl'))[0]
        rows = [json.loads(line) for line in path.read_text().splitlines()]
        action(rows)
        path.write_text(''.join(json.dumps(r) + '\n' for r in rows))

    def test_zero_valid_outputs_still_passes_complete_measurement_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.fixture(root); proof = self.analyze(root)
            self.assertEqual('PASS', proof['measurementStatus'])
            self.assertFalse(proof['qualityThresholdEnforced'])
            self.assertTrue(all(r['finalValid'] == 0 and r['total'] == 40 for r in proof['models'].values()))

    def test_missing_request_is_not_filled_by_another_model(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.fixture(root); self.mutate(root, lambda r: r.pop())
            with self.assertRaises(ValueError): self.analyze(root)

    def test_missing_actual_inference_bytes_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.fixture(root); self.mutate(root, lambda r: r[0]['attempts'][0].update(outputBytes=0))
            with self.assertRaises(ValueError): self.analyze(root)

    def test_third_attempt_cannot_reset_the_allowance(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.fixture(root)
            self.mutate(root, lambda r: r[0].update(attempts=r[0]['attempts'] * 3, calls=3, repairs=2))
            with self.assertRaises(ValueError): self.analyze(root)

    def test_comparison_rejects_different_initial_prompts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.fixture(root)
            self.mutate(root, lambda r: r[0]['attempts'][0].update(prompt='changed', promptSha256=hashlib.sha256(b'changed').hexdigest()))
            with self.assertRaises(ValueError): self.analyze(root)

    def test_aliases_of_the_same_model_cannot_count_as_two_comparison_arms(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.fixture(root)
            for path in root.glob('*.jsonl'):
                rows = [json.loads(line) for line in path.read_text().splitlines()]
                for row in rows:
                    for attempt in row['attempts']: attempt['modelDigest'] = 'a' * 64
                path.write_text(''.join(json.dumps(r) + '\n' for r in rows))
            with self.assertRaises(ValueError): self.analyze(root)

    def test_partial_latest_retry_cannot_borrow_old_missing_cases(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.fixture(root)
            path = sorted(root.glob('*.jsonl'))[0]; rows = [json.loads(l) for l in path.read_text().splitlines()][:3]
            for row in rows: row['ciAttempt'] = 2
            (root / 'partial-retry.jsonl').write_text(''.join(json.dumps(r) + '\n' for r in rows))
            with self.assertRaises(ValueError): self.analyze(root)


if __name__ == '__main__':
    unittest.main()
