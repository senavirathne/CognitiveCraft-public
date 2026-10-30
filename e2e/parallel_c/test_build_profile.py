import unittest

from build_profile import matching_profiles, verify_profile
from common import EvidenceError, ROOT, digest


class BuildProfileTests(unittest.TestCase):
    def test_unknown_profile_is_rejected(self):
        for profile in (None, {}, {"mode": "unchecked-build"}):
            with self.assertRaisesRegex(EvidenceError, "missing or unknown build profile"):
                verify_profile(profile)

    def test_missing_sdk_provenance_is_rejected(self):
        profile = {"mode": "existing-server-sdk", "dependencies_lock_sha256": digest(ROOT / "scripts/dependencies.json")}
        with self.assertRaisesRegex(EvidenceError, "missing server SDK provenance"):
            verify_profile(profile)

    def test_different_baseline_and_candidate_inputs_are_rejected(self):
        baseline = {"mode": "existing-server-sdk", "artifacts": {"minecraft-server.jar": "a" * 64}}
        matching_profiles(baseline, dict(baseline))
        for candidate in ({"mode": "loom"}, {"mode": "existing-server-sdk", "artifacts": {"minecraft-server.jar": "b" * 64}}):
            with self.assertRaisesRegex(EvidenceError, "profiles/SDK hashes differ"):
                matching_profiles(baseline, candidate)
