"""Integrity-unit fixtures only; no fixture is published as a genuine model measurement."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('planning_structure', Path(__file__).with_name('planning-structure.py'))
STRUCTURE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(STRUCTURE)


class TransportIntegrityTest(unittest.TestCase):
    def fixture(self, candidate='{"schema":1,"status":"unsupported","steps":[],"deliveries":[]}', rejected=False):
        schema = {'type': 'object'}
        request = json.dumps({'model': 'test-fixture', 'stream': False,
                              'options': {'temperature': 0, 'num_predict': 768},
                              'messages': [{'role': 'user', 'content': 'bounded test fixture'}], 'format': schema})
        response = json.dumps({'model': 'test-fixture', 'done': True, 'done_reason': 'stop',
                               'prompt_eval_count': 10, 'eval_count': 20,
                               'message': {'role': 'assistant', 'content': candidate}})
        transport = {'request': request, 'requestSha256': STRUCTURE.sha(request),
                     'response': response, 'wireSha256': STRUCTURE.sha(response),
                     'wireBytes': len(response.encode()), 'httpStatus': 200, 'overflow': False,
                     'rawCandidate': candidate, 'rawCandidateSha256': STRUCTURE.sha(candidate),
                     'rawCandidateBytes': len(candidate.encode()), 'decodeCode': 'INVALID_JSON' if rejected else 'NONE'}
        attempt = {'transport': transport, 'inputBytes': len(request.encode()), 'outputBytes': len(candidate.encode()),
                   'prompt': 'bounded test fixture', 'constraintSha256': STRUCTURE.sha(json.dumps(schema, sort_keys=True, separators=(',', ':'))),
                   'promptTokens': 10, 'outputTokens': 20, 'outcome': 'MALFORMED' if rejected else 'CANDIDATE',
                   'candidate': 'unavailable' if rejected else candidate, 'valid': not rejected}
        return {'model': 'test-fixture', 'nativeEffects': 0, 'dedicatedModels': 0, 'calls': 1, 'repairs': 0, 'attempts': [attempt]}

    def test_retained_rejection_is_evidence_without_becoming_a_candidate(self):
        row = self.fixture('{"schema":', rejected=True)
        STRUCTURE.check_row(row)
        row['attempts'][0]['candidate'] = row['attempts'][0]['transport']['rawCandidate']
        with self.assertRaisesRegex(ValueError, 'Rejected bytes admitted'):
            STRUCTURE.check_row(row)

    def test_changed_wire_bytes_cannot_reuse_the_original_hash(self):
        row = self.fixture()
        row['attempts'][0]['transport']['response'] += ' '
        with self.assertRaisesRegex(ValueError, 'Response hash mismatch'):
            STRUCTURE.check_row(row)

    def test_silent_candidate_rewriting_is_rejected(self):
        row = self.fixture()
        row['attempts'][0]['candidate'] += ' '
        with self.assertRaisesRegex(ValueError, 'Rejected bytes promoted'):
            STRUCTURE.check_row(row)

    def test_budget_or_effect_changes_cannot_pass_transport_review(self):
        original = self.fixture()
        for change in ('third_call', 'effect', 'tokens'):
            row = copy.deepcopy(original)
            if change == 'third_call': row.update(calls=3, repairs=2, attempts=row['attempts'] * 3)
            if change == 'effect': row['nativeEffects'] = 1
            if change == 'tokens': row['attempts'][0]['outputTokens'] = 769
            with self.assertRaises(ValueError): STRUCTURE.check_row(row)


if __name__ == '__main__':
    unittest.main()
