"""Fixture-policy regressions only; synthetic rows are not gameplay evidence."""
import unittest
from unittest.mock import patch

from fixtures import wait_for_placement_clearance


class Harness:
    actions_active = False

    def __init__(self, *states):
        self.states = iter(states)
        self.wait_timeout = None

    def snapshot(self):
        return next(self.states)

    def wait(self, predicate, reason, timeout):
        self.wait_timeout = timeout
        while True:
            try:
                result = predicate()
            except StopIteration:
                raise TimeoutError(reason)
            if result:
                return result


def observed(value=True, tick=14):
    return {"gameTick": tick, "placementColumnsClear": {"16,201,11": value}}


class FixtureClearanceTests(unittest.TestCase):
    @patch("fixtures.time.monotonic", return_value=10)
    def test_waits_for_native_clearance_with_only_the_remaining_envelope(self, clock):
        h = Harness({}, observed(False), observed(True))
        proof = wait_for_placement_clearance(h, [16, 201, 11], 18)
        self.assertEqual(8, h.wait_timeout)
        self.assertEqual([16, 201, 11], proof["target"])
        self.assertTrue(proof["clear"])
        self.assertEqual(14, proof["gameTick"])

    @patch("fixtures.time.monotonic", return_value=10)
    def test_unknown_blocked_and_non_boolean_values_never_establish_clearance(self, clock):
        for state in ({}, observed(False), observed(1), observed("true")):
            with self.subTest(state=state), self.assertRaises(TimeoutError):
                wait_for_placement_clearance(Harness(state), [16, 201, 11], 18)

    def test_setup_cannot_wait_inside_an_active_gameplay_action(self):
        h = Harness(observed())
        h.actions_active = True
        with self.assertRaisesRegex(AssertionError, "before gameplay"):
            wait_for_placement_clearance(h, [16, 201, 11], 18)
        self.assertIsNone(h.wait_timeout)

    @patch("fixtures.time.monotonic", return_value=18)
    def test_exhausted_existing_envelope_cannot_start_another_wait(self, clock):
        h = Harness(observed())
        with self.assertRaisesRegex(TimeoutError, "deadline exhausted"):
            wait_for_placement_clearance(h, [16, 201, 11], 18)
        self.assertIsNone(h.wait_timeout)

    @patch("fixtures.time.monotonic", return_value=10)
    def test_unbounded_unknown_time_and_inexact_coordinate_are_rejected(self, clock):
        states = [{"gameTick": 14, "placementColumnsClear": {str(i): True for i in range(513)}},
                  observed(tick=None), observed(tick=-1)]
        for state in states:
            with self.subTest(state=state), self.assertRaises(ValueError):
                wait_for_placement_clearance(Harness(state), [16, 201, 11], 18)
        for target in ([16, 201], [16, 201, 11.0], "16,201,11"):
            with self.subTest(target=target), self.assertRaises(ValueError):
                wait_for_placement_clearance(Harness(observed()), target, 18)


if __name__ == "__main__":
    unittest.main()
