import json
import tempfile
from pathlib import Path
import unittest

from common import CONTRACT, EvidenceError, workflow_context
from qualification_request import validate


class CiTests(unittest.TestCase):
    def request(self, **changes):
        value = {"stage": "C02", "campaign": "parallel-c-cumulative-docker-v2", "contract_sha256": CONTRACT,
                 "docs_candidate_sha": "75c9044ca24fafb3f322615e2104b1c195c22126", "nonce": "c02-infrastructure-recovery-20261031",
                 "purpose": "infrastructure-recovery", "reason": "Resume all cumulative gates after execution workspace disconnection."}
        value.update(changes)
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "request.json"; path.write_text(json.dumps(value)); return validate(path)

    def test_explicit_recovery_can_schedule_complete_implemented_stage(self):
        result = self.request(); self.assertEqual("C02", result["stage"]); self.assertEqual("infrastructure-recovery", result["purpose"])

    def test_unimplemented_stage_or_wrong_purpose_cannot_spend_qualification_run(self):
        for change in ({"stage": "C05"}, {"purpose": "final-qualification"}, {"stage": "J00"}, {"stage": "C08", "purpose": "final-qualification"}):
            with self.assertRaises(EvidenceError): self.request(**change)

    def test_wrong_contract_unpinned_docs_or_extra_authority_fields_are_rejected(self):
        for change in ({"contract_sha256": "0" * 64}, {"docs_candidate_sha": "main"}, {"original_root_id": "forged"}, {"reason": ""}):
            with self.assertRaises(EvidenceError): self.request(**change)

    def test_ci_identity_records_real_run_attempt_and_repository(self):
        value = workflow_context({"GITHUB_ACTIONS": "true", "GITHUB_RUN_ID": "123456", "GITHUB_RUN_ATTEMPT": "2", "GITHUB_REPOSITORY": "senavirathne/CognitiveCraft-public"})
        self.assertEqual((123456, 2), (value["workflow_run"], value["attempt"]))
        self.assertTrue(value["workflow_url"].endswith("/123456/attempts/2"))

    def test_missing_or_spoofed_ci_identity_is_rejected(self):
        for env in ({"GITHUB_ACTIONS": "true"}, {"GITHUB_RUN_ID": "123"}, {"GITHUB_RUN_ID": "bad", "GITHUB_RUN_ATTEMPT": "1"},
                    {"GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1", "GITHUB_REPOSITORY": "someone/else"}):
            with self.assertRaises(EvidenceError): workflow_context(env)

    def test_local_identity_remains_uncredited_as_github_actions(self):
        self.assertEqual({"workflow_run": None, "attempt": None, "workflow_url": None}, workflow_context({}))

    def test_only_explicit_pinned_model_configuration_can_reuse_host_network(self):
        from ci_host import shared_model_configuration
        from live_provider import ENV
        self.assertEqual(({}, False, None), shared_model_configuration(dict.fromkeys(ENV, '')))
        values = dict(zip(ENV, ('http://127.0.0.1:8080', 'qwen', 'a'*64, 'build/e2e-sdk/models/qwen.gguf', '/models/qwen.gguf', 'b1-abc')))
        self.assertEqual((values, True, None), shared_model_configuration(values))
        remote = dict(values, COGNITIVECRAFT_C_MODEL_URL='https://model.example.com')
        self.assertEqual((remote, False, None), shared_model_configuration(remote))

    def test_model_url_credentials_bad_urls_and_unowned_files_are_not_forwarded(self):
        from ci_host import shared_model_configuration
        from live_provider import ENV
        values = dict(zip(ENV, ('http://127.0.0.1:8080', 'qwen', 'a'*64, 'build/e2e-sdk/models/qwen.gguf', '/models/qwen.gguf', 'b1-abc')))
        for changes in ({ENV[0]:'https://user:secret@model.example.com'}, {ENV[0]:'http://['}, {ENV[3]:'/etc/passwd'}, {ENV[3]:'build/e2e-sdk/models/../../private'}, {ENV[2]:''}):
            accepted, host_network, error = shared_model_configuration(dict(values, **changes))
            self.assertEqual({}, accepted); self.assertFalse(host_network); self.assertIsNotNone(error)
