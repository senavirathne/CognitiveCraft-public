"""Synthetic trace controls; matching a terminal is preparation evidence, not gameplay."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from parallel_b.interference_native import observed_preparation


class RuntimeWindowTests(unittest.TestCase):
    def trace(self,root,run='fixture:run',result=True):
        rows=[{'kind':'primitive-start','value':{'correlation':{'runId':run},'primitive':{'id':'cognitivecraft:observe_plank_inputs'}}},
              {'kind':'primitive-handle','value':{'handle':{'id':'fixture:h','runId':run}}},
              {'kind':'primitive-terminal','value':{'handle':{'id':'fixture:h','runId':run},'receipt':{'terminal':True,'result':{'value':result}}}}]
        (root/'server').mkdir();(root/'server/stdout.log').write_text('\n'.join('CRAFTING_PRIMITIVE_TRACE '+json.dumps(r) for r in rows))

    def test_only_original_run_successful_runtime_observation_opens_window(self):
        with tempfile.TemporaryDirectory() as t:
            root=Path(t);self.trace(root)
            self.assertIsNotNone(observed_preparation(root,'fixture:run'))
            self.assertIsNone(observed_preparation(root,'fixture:foreign'))

    def test_failed_preparation_does_not_open_window(self):
        with tempfile.TemporaryDirectory() as t:
            root=Path(t);self.trace(root,result=False)
            self.assertIsNone(observed_preparation(root,'fixture:run'))
