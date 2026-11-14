import unittest
from performance_window import reached


class NativePerformanceClock(unittest.TestCase):
    def test_old_observer_counter_cannot_end_a_short_game_time_window(self):
        begin = {'startMillis':1000, 'startTick':21004}
        # The failed native attempt had only 393 captures. A separate observer
        # counter had advanced 400 relative to an already lagging snapshot.
        self.assertFalse(reached({'time':21000, 'tick':5000, 'gameTick':21397}, begin, 400))
        self.assertTrue(reached({'time':21400, 'tick':1, 'gameTick':21404}, begin, 400))

    def test_prior_process_frame_cannot_complete_a_new_measurement(self):
        self.assertFalse(reached({'time':999, 'tick':99999, 'gameTick':99999},
                                 {'startMillis':1000, 'startTick':100}, 400))


if __name__ == '__main__':
    unittest.main()
