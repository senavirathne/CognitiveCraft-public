"""Synthetic port-file regressions; these are never native execution evidence."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parent))
from contract_runner import check


class ContractAdapterTests(unittest.TestCase):
    def files(self,root):
        values={'metadata.json':{'sourceCommit':'fixture:source','releaseSha256':'fixture:jar','dirty':False},
                'production.json':{'originalOwner':'fixture:owner','privateOracleInputs':False,
                                   'witnessedCut':'after-native-before-publication','unknownEffectsReplayed':0},
                'before.json':{'source':1,'actor':0},'after.json':{'source':0,'actor':1},
                'measurements.json':{'automaticReplay':False,'sourceLogs':0,'actorLogs':1,
                                     'afterNative':{'source':0,'actor':1}}}
        for n,v in values.items():(root/n).write_text(json.dumps(v))

    def test_exact_native_port_quantities_are_required(self):
        with tempfile.TemporaryDirectory() as t:
            root=Path(t);self.files(root);c={'expected':{'sourceLogs':0,'actorLogs':1,'automaticReplay':False}}
            self.assertEqual('PASS',check(c,root,'fixture:source','fixture:jar')['status'])
            c['expected']['actorLogs']=2
            self.assertEqual('FAIL_GAMEPLAY',check(c,root,'fixture:source','fixture:jar')['status'])

    def test_foreign_identity_and_oracle_leakage_refuse(self):
        with tempfile.TemporaryDirectory() as t:
            root=Path(t);self.files(root);c={'expected':{'actorLogs':1}}
            with self.assertRaises(ValueError):check(c,root,'fixture:other','fixture:jar')
            p=root/'production.json';v=json.loads(p.read_text());v['privateOracleInputs']=True;p.write_text(json.dumps(v))
            with self.assertRaises(ValueError):check(c,root,'fixture:source','fixture:jar')

    def test_missed_cut_is_inconclusive(self):
        with tempfile.TemporaryDirectory() as t:
            root=Path(t);self.files(root)
            c={'cut':'after-certified-saves','expected':{'automaticReplay':False},'after':{'source':0,'actor':1}}
            self.assertEqual('INCONCLUSIVE',check(c,root,'fixture:source','fixture:jar')['status'])

    def test_save_completion_needs_causal_production_metadata(self):
        with tempfile.TemporaryDirectory() as t:
            root=Path(t);self.files(root);p=root/'production.json';v=json.loads(p.read_text());v['witnessedCut']='after-certified-saves';p.write_text(json.dumps(v))
            c={'cut':'after-certified-saves','expected':{'automaticReplay':False},'after':{'source':0,'actor':1}}
            self.assertEqual('FAIL_GAMEPLAY',check(c,root,'fixture:source','fixture:jar')['status'])
