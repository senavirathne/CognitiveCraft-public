import copy
import unittest
from interference_oracle import evaluate


def fixture():
    return dict(run="fixture:run", actor="fixture:actor", principal="fixture:A", interferer="fixture:B",
                twoRealClients=True, callsBefore={"generationCalls":1}, callsAfter={"generationCalls":1},
                preflight=dict(actor="fixture:actor", tick=10, available=1, captured={"id":"fixture:capture"},
                               sourceIdentity="fixture:chest", sourceFingerprint="old"),
                ack=dict(tick=20, nativeMutation=True, clientSynchronized=True,
                         sourceIdentity="fixture:chest", sourceFingerprint="new"),
                withdraw=dict(actor="fixture:actor", tick=30, captured={"id":"fixture:capture"},
                              sourceIdentity="fixture:chest", reason="STALE_OBSERVATION", inputCount=0),
                quantities=dict(sourceDelta=-1,playerBDelta=1,actorInputDelta=0,actorOutputDelta=0,
                                destinationDelta=0,creditedOutput=0))


class InterferenceOracleTests(unittest.TestCase):
    def test_witnessed_window_requires_both_independent_views_and_native_refusal(self):
        self.assertEqual("WITNESSED_SAFE_REFUSAL",evaluate(fixture())["status"])

    def test_late_same_tick_and_early_actions_are_inconclusive(self):
        for tick in (5,10,30,31):
            row=fixture();row["ack"]["tick"]=tick
            self.assertEqual("INCONCLUSIVE",evaluate(row)["status"])

    def test_scheduled_action_is_not_an_acknowledgement(self):
        for field in ("nativeMutation","clientSynchronized"):
            row=fixture();row["ack"][field]=False
            self.assertEqual("INCONCLUSIVE",evaluate(row)["status"])

    def test_foreign_or_corrupt_observer_evidence_is_rejected(self):
        for key,field,value in (("withdraw","actor","other"),("withdraw","captured",{}),
                                ("ack","sourceIdentity","replacement"),("ack","tick","20")):
            row=fixture();row[key][field]=value
            self.assertEqual("INVALID_EVIDENCE",evaluate(row)["status"])
        row=fixture();row.pop("preflight")
        self.assertEqual("INVALID_EVIDENCE",evaluate(row)["status"])

    def test_in_window_effect_or_credit_is_an_actual_failure(self):
        for key in fixture()["quantities"]:
            row=fixture();row["quantities"][key]+=1
            self.assertEqual("FAIL_GAMEPLAY",evaluate(row)["status"])
        row=fixture();row["withdraw"]["reason"]=None
        self.assertEqual("FAIL_GAMEPLAY",evaluate(row)["status"])

    def test_single_principal_and_model_regeneration_cannot_pass(self):
        row=fixture();row["interferer"]=row["principal"]
        self.assertEqual("INVALID_EVIDENCE",evaluate(row)["status"])
        row=fixture();row["callsAfter"]["generationCalls"]+=1
        self.assertEqual("FAIL_GAMEPLAY",evaluate(row)["status"])
