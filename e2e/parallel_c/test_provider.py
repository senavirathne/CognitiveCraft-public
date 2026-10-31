"""Actual Java wrapper executions with controlled SPI doubles; no genuine-model or world credit."""
import os
from pathlib import Path
import subprocess
import unittest

from common import ROOT, EvidenceError, identity, read, write
from predecessor import validate_records


class ProviderTests(unittest.TestCase):
    def probe(self, scenario):
        java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
        classpath = os.pathsep.join(str(ROOT / path) for path in ('core/build/classes/java/main', 'providers/build/classes/java/main', 'providers/build/classes/java/test'))
        command = [java, '-cp', classpath, 'dev.aivillages.providers.interaction.ConversationProbe', scenario]
        result = subprocess.run(command, capture_output=True, text=True, timeout=20)
        evidence = os.environ.get('COGNITIVECRAFT_C_PYTHON_EVIDENCE_ROOT')
        if evidence:
            folder = Path(evidence)/'provider-probes'/scenario; folder.mkdir(parents=True, exist_ok=False)
            (folder/'stdout.json').write_text(result.stdout); (folder/'stderr.log').write_text(result.stderr)
            write(folder/'process.json', {**identity(), 'command': command, 'exit': result.returncode, 'tier': 'SYNTHETIC_PROVIDER'})
        self.assertEqual(0, result.returncode, result.stderr)
        import json
        row = json.loads(result.stdout)
        self.assertEqual('SYNTHETIC_PROVIDER', row['tier']); self.assertEqual(1, row['completions'])
        self.assertEqual(0, row['effect_calls']); self.assertEqual('NO_EFFECT_INTERFACE_EXPOSED', row['effect_scope'])
        self.assertLessEqual(row['provider_calls'], 2); self.assertLessEqual(row['reserved_input_tokens'], 2048); self.assertLessEqual(row['reserved_output_tokens'], 256)
        self.assertIsNone(row['actual_input_tokens']); self.assertIsNone(row['actual_output_tokens']); self.assertFalse(row['compute_termination_confirmed'])
        return row

    def test_timeout_chooses_one_offline_fallback_and_discards_late_completion(self):
        row = self.probe('timeout'); self.assertEqual('TIMEOUT', row['reason']); self.assertEqual('Hello.', row['text']); self.assertEqual(1, row['provider_calls'])

    def test_invalid_json_exhausts_one_correction_then_falls_back(self):
        row = self.probe('invalid'); self.assertEqual('INVALID_RESPONSE', row['reason']); self.assertEqual('Hello.', row['text']); self.assertEqual(2, row['provider_calls'])

    def test_injection_and_finished_action_claims_never_become_commands(self):
        row = self.probe('injection'); self.assertEqual('INVALID_RESPONSE', row['reason']); self.assertEqual('Hello.', row['text']); self.assertNotIn('diamond', row['text'])

    def test_late_session_completion_is_not_deliverable(self):
        row = self.probe('late'); self.assertEqual('STALE', row['reason']); self.assertFalse(row['deliverable']); self.assertEqual('', row['text'])

    def test_current_valid_candidate_passes_with_synthetic_usage_uncredited(self):
        row = self.probe('valid'); self.assertEqual('MODEL', row['reason']); self.assertTrue(row['deliverable']); self.assertEqual(1, row['provider_calls'])

    def test_offline_fallback_is_independent_of_optional_model_availability(self):
        row = self.probe('offline'); self.assertEqual('OFFLINE', row['reason']); self.assertEqual('Hello.', row['text']); self.assertEqual(0, row['provider_calls'])

    def test_unqualified_c02_cannot_be_forged_into_a_qualified_predecessor(self):
        forged = {'stage': 'C02', 'gate_status': 'OWNED_GATES_PASS', 'source_sha': '1586b72994ce7c2768a699e2e68688faf6376539'}
        with self.assertRaises(EvidenceError): validate_records(forged, forged, 'C02')

    def test_missing_live_configuration_has_zero_genuine_execution_credit(self):
        import tempfile
        from unittest.mock import patch
        import live_provider
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {key: '' for key in live_provider.ENV}):
            result = live_provider.run(Path(temp)/'live')
            self.assertEqual('BLOCKED_DEPENDENCY', result['status']); self.assertEqual(6, result['planned'])
            self.assertEqual(0, result['executed']); self.assertEqual(0, result['passed'])

    def test_a_live_pass_label_cannot_replace_actual_model_and_driver_evidence(self):
        import tempfile
        import live_provider
        from common import write
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp); write(folder/'live.json', {'status': 'PASS', 'model_file': 'outside-the-model-cache.gguf'})
            with self.assertRaises((EvidenceError, OSError)): live_provider.verify(folder, {'provider_cases': []})
