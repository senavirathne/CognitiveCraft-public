"""Collector races supply no process or native execution credit."""
import json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
from client_campaign import DComponentCampaign

class ComponentCollectionTest(unittest.TestCase):
    def test_partial_process_record_waits_for_fresh_witness_with_original_deadline(self):
        with tempfile.TemporaryDirectory()as d:
            root=Path(d);h=DComponentCampaign(root,{});p=root/'server/process.json';p.parent.mkdir();old={'start':10}
            self.assertIsNone(h.fresh_process('server',old))
            for data in ('','{','{"start":11'):
                p.write_text(data);self.assertIsNone(h.fresh_process('server',old));self.assertEqual(p.read_text(),data)
            for start in (9,10):
                p.write_text(json.dumps({'start':start,'pid':123}));self.assertIsNone(h.fresh_process('server',old))
            current={'start':11,'pid':124};p.write_text(json.dumps(current));self.assertEqual(h.fresh_process('server',old),current)
            # Complete invalid records fail closed rather than being swallowed.
            p.write_text('{"start":11,"start":12}')
            with self.assertRaisesRegex(ValueError,'duplicate JSON key'):h.fresh_process('server',old)
            p.write_text('{}')
            with self.assertRaises(KeyError):h.fresh_process('server',old)
            p.write_text('{');h.deadline=1000
            with patch('client_runner.time.monotonic',side_effect=[0,0,181]),patch('client_runner.time.sleep'):
                with self.assertRaisesRegex(TimeoutError,'new actual native process server'):
                    h.wait(lambda:h.fresh_process('server',old),'new actual native process server',180)
            self.assertEqual(p.read_text(),'{')
