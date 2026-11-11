import copy,unittest
from claims_cases import CASES,service
from review_claims_connected import denominator
from claims_connected import active_claims

class ClaimsEvidenceBounds(unittest.TestCase):
    def test_retired_foreign_history_is_not_a_current_claim(self):
        value={'leases':{'leases':[{'state':'RELEASED','owner':{'job':'old'}},{'state':'ACTIVE','owner':{'job':'new'}}]}}
        self.assertEqual(len(active_claims(value)),1);self.assertEqual(active_claims(value,'old'),[])
    def candidate(self):return {'plannedCases':list(CASES),'requiredCases':21,'cases':[{'id':n,'status':'PASS_NATIVE_CANDIDATE'} for n in CASES],
        'failures':[],'omittedTasks':0,'omittedTests':0,'status':'PASS_NATIVE_CANDIDATE_PENDING_REVIEW','a08Qualified':False}
    def test_all_twenty_one_cases_remain_required(self):
        self.assertEqual(len(CASES),21);self.assertEqual(len(set(CASES)),21);denominator(self.candidate())
    def test_missing_or_duplicate_native_case_cannot_pass(self):
        for mutate in (lambda v:v['cases'].pop(),lambda v:v['cases'].__setitem__(0,v['cases'][1])):
            value=self.candidate();mutate(value)
            with self.assertRaises(AssertionError):denominator(value)
    def test_missed_negative_window_cannot_be_relabelled_as_zero_effect_pass(self):
        value=self.candidate();value['cases'][5]['status']='INCONCLUSIVE_REQUIRED_NATIVE_WINDOW'
        with self.assertRaises(AssertionError):denominator(value)
    def test_collector_cannot_self_qualify_or_hide_a_failed_case(self):
        for key,item in (('a08Qualified',True),('failures',['full-return-retains-custody']),('omittedTests',1)):
            value=self.candidate();value[key]=item
            with self.assertRaises(AssertionError):denominator(value)
    def test_service_uses_actual_eligibility_and_grant_not_queue_order(self):
        rows=[{'eligibleTick':12,'grantedTick':17},{'eligibleTick':-1,'grantedTick':-1}]
        value=service(rows);self.assertEqual(value['sortedTicks'],[5]);self.assertEqual(value['samples'],1)
        with self.assertRaises(AssertionError):service([{'eligibleTick':17,'grantedTick':12}])

if __name__=='__main__':unittest.main()
