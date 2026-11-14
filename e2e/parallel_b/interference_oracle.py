"""Independent evidence oracle. Client scheduling and native timing are separate facts."""
from __future__ import annotations


def evaluate(row):
    def result(status, reason):
        return {"status": status, "reason": reason, "nativeGameplayPass": status == "WITNESSED_SAFE_REFUSAL"}
    required = ("run", "actor", "principal", "interferer", "preflight", "ack", "withdraw", "quantities")
    if any(k not in row for k in required):
        return result("INVALID_EVIDENCE", "Missing independent witness")
    p, ack, w, q = (row[k] for k in ("preflight", "ack", "withdraw", "quantities"))
    if row["principal"] == row["interferer"] or not row.get("twoRealClients"):
        return result("INVALID_EVIDENCE", "Two distinct connected principals required")
    if not ack.get("nativeMutation") or not ack.get("clientSynchronized"):
        return result("INCONCLUSIVE", "Scheduled player action lacks independent native acknowledgement")
    if (p.get("actor") != row["actor"] or w.get("actor") != row["actor"] or
            not p.get("available", 0) or p.get("captured") != w.get("captured") or
            p.get("sourceIdentity") != ack.get("sourceIdentity") or
            p.get("sourceIdentity") != w.get("sourceIdentity") or
            p.get("sourceFingerprint") == ack.get("sourceFingerprint")):
        return result("INVALID_EVIDENCE", "Foreign actor, replaced source or unmatched captured preflight")
    if any(type(x.get("tick")) is not int for x in (p, ack, w)):
        return result("INVALID_EVIDENCE", "Native game ticks required")
    if not p["tick"] < ack["tick"] < w["tick"]:
        return result("INCONCLUSIVE", "Mutation was not witnessed strictly inside preflight-to-final-recheck")
    if q != {"sourceDelta": -1, "playerBDelta": 1, "actorInputDelta": 0,
             "actorOutputDelta": 0, "destinationDelta": 0, "creditedOutput": 0}:
        return result("FAIL_GAMEPLAY", "Independent participant conservation or root attribution failed")
    if w.get("reason") != "STALE_OBSERVATION" or w.get("inputCount") != 0:
        return result("FAIL_GAMEPLAY", "Final native withdrawal did not reject stale stock without effects")
    if row.get("callsBefore") != row.get("callsAfter"):
        return result("FAIL_GAMEPLAY", "Known method invoked model generation")
    return result("WITNESSED_SAFE_REFUSAL", "Normal PlayerB transfer acknowledged before actual final withdrawal recheck")
