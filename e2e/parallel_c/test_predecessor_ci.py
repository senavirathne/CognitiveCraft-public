import unittest
from common import EvidenceError
from predecessor import validate_execution


class PredecessorCiTests(unittest.TestCase):
    def test_ci_predecessor_requires_exact_run_attempt_and_docker_image(self):
        pins=dict(workflow_run=123,attempt=2,container_image_id='sha256:'+'a'*64)
        validate_execution(dict(pins),pins)
        for change in (dict(workflow_run=None),dict(attempt=1),dict(container_image_id='sha256:'+'b'*64)):
            with self.assertRaises(EvidenceError):validate_execution(dict(pins,**change),pins)

    def test_local_predecessor_cannot_gain_fabricated_ci_credit(self):
        validate_execution(dict(workflow_run=None,attempt=None),{})
        with self.assertRaises(EvidenceError):validate_execution(dict(workflow_run=123,attempt=1),{})
