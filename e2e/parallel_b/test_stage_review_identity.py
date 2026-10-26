import copy
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from parallel_b.stage_review import producer


class StageProducerTests(unittest.TestCase):
    def meta(self):return dict(sourceCommit='fixture:source',sourceTree='fixture:tree',dirty=False,
                              ci={'GITHUB_RUN_ID':'7','GITHUB_RUN_ATTEMPT':'2'})

    def test_same_source_older_attempt_cannot_qualify(self):
        meta=self.meta();producer(meta,'fixture:source','fixture:tree','7','2')
        meta['ci']['GITHUB_RUN_ATTEMPT']='1'
        with self.assertRaises(AssertionError):producer(meta,'fixture:source','fixture:tree','7','2')

    def test_missing_and_foreign_run_refuse_even_with_matching_source(self):
        for run in (None,'8'):
            with self.assertRaises(AssertionError):producer(self.meta(),'fixture:source','fixture:tree',run,'2')

    def test_dirty_and_foreign_tree_refuse(self):
        meta=self.meta();meta['dirty']=True
        with self.assertRaises(AssertionError):producer(meta,'fixture:source','fixture:tree','7','2')
        with self.assertRaises(AssertionError):producer(self.meta(),'fixture:source','fixture:other','7','2')
