import unittest
from cut_window import idle_before_cut

class CutAdmissionWindow(unittest.TestCase):
    def status(self,hot=47,**updates):
        value={'ready':True,'jobs':{'hot':hot,'pending':False},'leases':{'pending':False}};value.update(updates);return value
    def test_below_target_and_acknowledged_can_trigger_another_native_job(self):
        value=self.status();self.assertIs(value,idle_before_cut(value))
    def test_target_crossed_before_pending_is_visible_still_waits_for_the_cut(self):
        # Native1d437 admitted an unnecessary next request in this publication gap.
        self.assertIsNone(idle_before_cut(self.status(48)))
        self.assertIsNone(idle_before_cut(self.status(49)))
    def test_pending_job_or_lease_acknowledgement_cannot_open_intake(self):
        self.assertIsNone(idle_before_cut(self.status(jobs={'hot':47,'pending':True})))
        self.assertIsNone(idle_before_cut(self.status(leases={'pending':True})))
    def test_missing_or_unready_state_is_not_admissibility_evidence(self):
        for value in (None,{},self.status(ready=False),self.status(jobs={'hot':47}),self.status(jobs={'hot':'47','pending':False})):
            self.assertIsNone(idle_before_cut(value))

if __name__=='__main__':unittest.main()
