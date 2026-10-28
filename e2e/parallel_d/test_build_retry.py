import unittest
from build import dependency_failure

class BuildRetryTest(unittest.TestCase):
    def test_only_pre_task_dependency_failures_can_retry(self):
        self.assertTrue(dependency_failure("Could not download mercury-0.4.3.jar: Read timed out"))
        self.assertTrue(dependency_failure("Could not find com.google.code.gson:gson:2.14.0"))
        self.assertFalse(dependency_failure("syntax error: cannot find symbol"))
    def test_started_tasks_or_test_failures_are_never_retried(self):
        self.assertFalse(dependency_failure("> Task :core:test FAILED\nCould not resolve assertion failure"))
        self.assertFalse(dependency_failure("> Task :core:compileJava FAILED\nCould not download dependency"))
