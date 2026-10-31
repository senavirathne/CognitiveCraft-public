import os
from pathlib import Path
import tempfile
import unittest
from common import EvidenceError, read
from dialogue_sequences import CAMPAIGN, check_frames, run


class DialogueSequenceTests(unittest.TestCase):
    def test_one_hundred_frozen_twenty_event_sequences_preserve_original_root_owner_and_budget(self):
        evidence=os.environ.get('COGNITIVECRAFT_C_PYTHON_EVIDENCE_ROOT')
        if evidence:
            result=run(Path(evidence)/'dialogue-sequences')
        else:
            with tempfile.TemporaryDirectory() as temp: result=run(Path(temp)/'dialogue-sequences')
        self.assertEqual((100,100,2000),(result['executed'],result['passed'],result['event_count']))

    def test_frozen_campaign_has_distinct_seeds_and_twenty_registered_events_each(self):
        campaign=read(CAMPAIGN); rows=campaign['sequences']; self.assertEqual(100,len(rows))
        self.assertEqual(list(range(20261027,20261127)),[r['seed'] for r in rows]); self.assertEqual(100,len({r['id'] for r in rows}))
        self.assertTrue(all(len(r['events'])==20 for r in rows)); self.assertEqual('C04',campaign['stage'])

    def test_a_summary_label_or_partial_campaign_cannot_replace_actual_sequence_frames(self):
        with self.assertRaises(EvidenceError): check_frames({'tier':'SYNTHETIC_DIALOGUE_SEQUENCES','status':'PASS','sequences':[]},read(CAMPAIGN))

    def test_unqualified_c03_cannot_be_promoted_to_a_qualified_c04_predecessor(self):
        from predecessor import validate_records
        forged={'stage':'C03','gate_status':'OWNED_GATES_PASS','source_sha':'7011a8dfca917f1e2f9b13e541cc542906061962'}
        with self.assertRaises(EvidenceError): validate_records(forged,forged,'C03')
