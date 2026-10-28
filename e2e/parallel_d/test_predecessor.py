import copy, unittest
from common import HERE,read
from prerequisites import measured

class PredecessorTest(unittest.TestCase):
    def test_wrong_source_or_unqualified_predecessor_refused(self):
        good=read(HERE/'predecessors/D00-qualified.json');measured(good)
        for field,value in [('source_sha','0'*40),('gate_status','INCONCLUSIVE'),('attempt',1)]:
            bad=copy.deepcopy(good);bad[field]=value
            with self.assertRaises(ValueError):measured(bad)
    def test_missing_or_inflated_predecessor_counters_refused(self):
        good=read(HERE/'predecessors/D00-qualified.json')
        for field,value in [('executed',58),('passed',58),('failed',1),('planned',60)]:
            bad=copy.deepcopy(good);bad['unit'][field]=value
            with self.assertRaises(ValueError):measured(bad)
