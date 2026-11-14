import unittest
from unittest.mock import Mock,patch
from cancellation import cancel

class CancellationTest(unittest.TestCase):
    def test_only_busy_refusals_retry_within_the_original_total_deadline(self):
        h=Mock();h.command.side_effect=['job=root cancellation=false reason=BUDGET_EXHAUSTED','job=root cancellation=true reason=null']
        with patch('cancellation.time.monotonic',side_effect=[100,101,108]):
            self.assertIn('cancellation=true',cancel(h,'PlayerA','root'))
        self.assertEqual([29,22],[c.args[3] for c in h.command.call_args_list])
        self.assertEqual(['/aivillage kernel job-cancel root']*2,[c.args[1] for c in h.command.call_args_list])
        self.assertEqual(1,h.event.call_count)

    def test_denial_is_not_retried(self):
        h=Mock();h.command.return_value='job=root cancellation=false reason=AUTHORITY_DENIED'
        with self.assertRaises(AssertionError):cancel(h,'PlayerA','root')
        self.assertEqual(1,h.command.call_count);h.event.assert_not_called()

    def test_busy_responses_cannot_restart_the_feedback_timeout(self):
        h=Mock();h.command.return_value='job=root cancellation=false reason=BUDGET_EXHAUSTED'
        with patch('cancellation.time.monotonic',side_effect=[100,101,131]),self.assertRaises(TimeoutError):cancel(h,'PlayerA','root')
        self.assertEqual(1,h.command.call_count)

if __name__=='__main__':unittest.main()
