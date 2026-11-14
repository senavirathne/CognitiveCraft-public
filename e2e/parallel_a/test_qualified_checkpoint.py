"""Metadata trust-boundary fixtures; zero native effects."""
import unittest
from qualified_checkpoint import checkpoint_identity,SOURCE,PREFIX,FAILURE
from continuation import CURRENT_PRODUCTION_SHA

class QualifiedInputIdentity(unittest.TestCase):
    def fixture(self):
        return {'sourceCommit':SOURCE,'dirty':False,'releaseSha256':CURRENT_PRODUCTION_SHA},{'status':'FAIL','reason':FAILURE,'cases':[{'id':name,'status':'PASS'} for name in PREFIX]}
    def test_only_the_exact_failed_source_with_completed_prefix_is_accepted(self):
        metadata,result=self.fixture();self.assertEqual(PREFIX,set(checkpoint_identity(metadata,result)))
    def test_dirty_changed_jar_or_another_source_is_rejected(self):
        for key,value in [('dirty',True),('releaseSha256','fixture:wrong'),('sourceCommit','fixture:wrong')]:
            metadata,result=self.fixture();metadata[key]=value
            with self.assertRaises(AssertionError):checkpoint_identity(metadata,result)
    def test_missing_or_failed_case_is_not_a_completed_prefix(self):
        metadata,result=self.fixture();result['cases'].pop()
        with self.assertRaises(AssertionError):checkpoint_identity(metadata,result)
        metadata,result=self.fixture();result['cases'][0]['status']='FAIL'
        with self.assertRaises(AssertionError):checkpoint_identity(metadata,result)
    def test_original_failure_is_retained_and_must_match(self):
        for key,value in [('status','PASS'),('reason','fixture:unknown failure')]:
            metadata,result=self.fixture();result[key]=value
            with self.assertRaises(AssertionError):checkpoint_identity(metadata,result)

if __name__=='__main__':unittest.main()
