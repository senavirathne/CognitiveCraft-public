#!/usr/bin/env python3
"""Validate the explicit C08-only request before spending the final owner Actions run."""
import argparse
import re
from common import CONTRACT, check, read


def validate(path):
    value = read(path)
    check(set(value) == {"stage", "campaign", "contract_sha256", "docs_candidate_sha", "nonce"}, "unexpected request fields")
    check(value["stage"] == "C08" and value["campaign"] == "parallel-c-final-cumulative-v1", "only final C08 qualification may run")
    check(value["contract_sha256"] == CONTRACT, "wrong contract freeze")
    check(re.fullmatch(r"[a-f0-9]{40}", value["docs_candidate_sha"]), "frozen docs commit required")
    check(re.fullmatch(r"[a-zA-Z0-9-]{16,80}", value["nonce"]), "fresh explicit candidate nonce required")
    return value


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__); p.add_argument("path"); a = p.parse_args()
    print(__import__("json").dumps(validate(a.path), sort_keys=True))
