#!/usr/bin/env python3
"""Validate one explicit consolidated Docker qualification request; ordinary pushes stay dormant."""
import argparse
import re
from common import CONTRACT, HERE, check, read


def validate(path):
    value = read(path)
    check(set(value) == {"stage", "campaign", "contract_sha256", "docs_candidate_sha", "nonce", "purpose", "reason"}, "unexpected request fields")
    check(isinstance(value["stage"], str) and re.fullmatch(r"C0[0-8]", value["stage"]), "invalid C stage")
    check(value["campaign"] == "parallel-c-cumulative-docker-v2", "wrong cumulative campaign")
    check(value["purpose"] == ("final-qualification" if value["stage"] == "C08" else "infrastructure-recovery"), "request purpose/stage mismatch")
    check(isinstance(value["reason"], str) and 20 <= len(value["reason"]) <= 512, "explicit recovery/qualification reason required")
    registry = read(HERE / "registries" / (value["stage"] + ".json"))
    check(registry["implementation"] == "IMPLEMENTED", "unimplemented stage cannot request qualification")
    check(value["contract_sha256"] == CONTRACT, "wrong contract freeze")
    check(isinstance(value["docs_candidate_sha"], str) and re.fullmatch(r"[a-f0-9]{40}", value["docs_candidate_sha"]), "frozen docs commit required")
    check(isinstance(value["nonce"], str) and re.fullmatch(r"[a-zA-Z0-9-]{16,80}", value["nonce"]), "fresh explicit candidate nonce required")
    return value


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__); p.add_argument("path"); a = p.parse_args()
    print(__import__("json").dumps(validate(a.path), sort_keys=True))
