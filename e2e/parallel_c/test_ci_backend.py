"""Model outage recovery pins and cache acceptance; no synthetic test gets genuine generation credit."""
import hashlib
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

from ci_backend import healthy_existing, props_match, verified_model
from live_provider import ENV


class BackendTests(unittest.TestCase):
    def test_model_cache_requires_gguf_bytes_digest_and_exact_length(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "model.gguf"; data = b"GGUFactual cache bytes"; path.write_bytes(data)
            sha = hashlib.sha256(data).hexdigest()
            self.assertTrue(verified_model(path, sha, len(data)))
            self.assertFalse(verified_model(path, "0" * 64, len(data)))
            self.assertFalse(verified_model(path, sha, len(data) + 1))
            path.write_bytes(b"text" + data[4:])
            self.assertFalse(verified_model(path, hashlib.sha256(path.read_bytes()).hexdigest()))
            self.assertFalse(verified_model(path.with_name("missing"), sha))

    def test_running_service_requires_exact_model_build_and_chat_template(self):
        props = dict(model_path="/models/pin.gguf", build_info="b1-abc", chat_template="actual template")
        self.assertTrue(props_match(props, "/models/pin.gguf", "b1-abc"))
        self.assertFalse(props_match(props, "/models/other.gguf", "b1-abc"))
        self.assertFalse(props_match(props, "/models/pin.gguf", "b2-def"))
        self.assertFalse(props_match(dict(props, chat_template=None), "/models/pin.gguf", "b1-abc"))

    def test_absent_unavailable_or_changed_backend_selects_managed_fallback(self):
        self.assertFalse(healthy_existing({}))
        values = dict(zip(ENV, ("http://127.0.0.1:8080", "pinned", "a" * 64, "build/parallel-c-cache/models/model.gguf", "/model.gguf", "b1-abc")))
        with patch("ci_backend.verified_model", return_value=False), patch("ci_backend.urllib.request.urlopen") as network:
            self.assertFalse(healthy_existing(values)); network.assert_not_called()
        with patch("ci_backend.verified_model", return_value=True), patch("ci_backend.urllib.request.urlopen", side_effect=OSError("offline")):
            self.assertFalse(healthy_existing(values))

    def test_matching_healthy_service_is_reused_without_creating_another_backend(self):
        import io
        response = io.BytesIO(b'{"model_path":"/model.gguf","build_info":"b1-abc","chat_template":"template"}')
        values = dict(zip(ENV, ("http://127.0.0.1:8080", "pinned", "a" * 64, "build/parallel-c-cache/models/model.gguf", "/model.gguf", "b1-abc")))
        with patch("ci_backend.verified_model", return_value=True), patch("ci_backend.urllib.request.urlopen", return_value=response):
            self.assertTrue(healthy_existing(values))
