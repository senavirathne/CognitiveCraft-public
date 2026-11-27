import unittest
from unittest.mock import Mock, call, patch

from suite import Harness


class ModelPullTest(unittest.TestCase):
    MODEL = "qwen3:4b-instruct-2507-q4_K_M"

    def setUp(self):
        self.harness = Mock(spec=Harness)
        self.harness.env = {"COGNITIVECRAFT_OLLAMA_MODEL": self.MODEL}
        self.pull = call("exec", "-T", "ollama", "ollama", "pull", self.MODEL)

    @patch("suite.time.sleep")
    def test_retries_the_real_pull_with_the_original_model_on_registry_unavailability(self, sleep):
        self.harness.compose.side_effect = [
            RuntimeError("Error: pull model manifest: 503: upstream connect error"),
            RuntimeError("Error: 503:"),
            "native docker pull completed",
        ]
        result = Harness.pull_generation_model(self.harness)
        self.assertEqual("native docker pull completed", result)
        self.assertEqual([self.pull] * 3, self.harness.compose.call_args_list)
        self.assertEqual([call(12), call(30)], sleep.call_args_list)
        self.assertEqual(2, self.harness.event.call_count)

    @patch("suite.time.sleep")
    def test_reports_storage_or_permanent_registry_failure(self, sleep):
        self.harness.compose.side_effect = RuntimeError("Error: 503: registry unavailable")
        with self.assertRaisesRegex(RuntimeError, "503: registry unavailable"):
            Harness.pull_generation_model(self.harness)
        self.assertEqual([self.pull] * 4, self.harness.compose.call_args_list)
        self.assertEqual([call(12), call(30), call(60)], sleep.call_args_list)

        self.harness.compose.reset_mock()
        sleep.reset_mock()
        self.harness.compose.side_effect = RuntimeError("Error: no space left on device")
        with self.assertRaisesRegex(RuntimeError, "no space left on device"):
            Harness.pull_generation_model(self.harness)
        self.harness.compose.assert_called_once_with(*self.pull.args)
        sleep.assert_not_called()
