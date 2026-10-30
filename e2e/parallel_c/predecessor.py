"""Require the exact measured C00 handoff, without resetting C01 to frozen B."""
from pathlib import Path
import hashlib
import json
import subprocess

from common import ROOT, CONTRACT, EvidenceError, check, git

PINS = {
    "source_sha": "33717878a818ea5518e94b840170766574efb8bb",
    "tree_sha": "822de859a0011f705430b537f0265e97874dd08c",
    "docs_sha": "d2ed91d0bcea7c2864e3cdc0511e9ecfdaeff3ab",
    "docs_tree": "23a1c6752f46662c06a28466b040f6548e173fc0",
    "jar_sha256": "713d0d8c1b99a77b0f098a00281c10a7d5836e8e396565b3ed861ba4c9843a5f",
}
FILES = {
    "stage-manifest.json": "61a2932d597a7aa09aec02a4f0f92f989581d9149548deb3dd819f3085daffd6",
    "evidence/C00-20261030/qualified.json": "607177c801513a75640941edb15fea13a493132984d74740b94ae3f2ae741f2b",
}


def validate_records(manifest, oracle):
    for record in (manifest, oracle):
        check(record["stage"] == "C00" and record["gate_status"] == "OWNED_GATES_PASS", "C00 must be locally qualified")
        for field in ("source_sha", "tree_sha", "jar_sha256"):
            check(record[field] == PINS[field], "C00 predecessor pin mismatch: " + field)
        check(record["contract_sha256"] == CONTRACT, "C00 predecessor contract mismatch")
        check(record["workflow_run"] is None and record["attempt"] is None, "unexpected C00 CI claim")
    for tier, count in (("unit", 2371), ("python", 29)):
        for metric in (manifest[tier], oracle["counts"][tier]):
            check(metric["planned"] == metric["executed"] == metric["passed"] == count
                  and metric["failed"] == metric["inconclusive"] == metric["refused_expected"] == 0,
                  "C00 predecessor metric mismatch: " + tier)
    check(oracle["native_executed"] == oracle["client_executed"] == 0, "unexpected C00 physical credit")


def verify_predecessor(docs_root):
    docs_root = Path(docs_root)
    for root, pin in ((ROOT, PINS["source_sha"]), (docs_root, PINS["docs_sha"])):
        check(subprocess.run(["git", "-C", str(root), "merge-base", "--is-ancestor", pin, "HEAD"], capture_output=True).returncode == 0,
              "missing qualified C00 ancestor: " + pin)
    check(git(ROOT, "rev-parse", PINS["source_sha"] + "^{tree}") == PINS["tree_sha"], "C00 source tree mismatch")
    check(git(docs_root, "rev-parse", PINS["docs_sha"] + "^{tree}") == PINS["docs_tree"], "C00 docs tree mismatch")
    records = []
    for path, expected in FILES.items():
        raw = subprocess.check_output(["git", "-C", str(docs_root), "show", PINS["docs_sha"] + ":docs/implementation/parallel-c/" + path])
        check(hashlib.sha256(raw).hexdigest() == expected, "C00 evidence digest mismatch: " + path)
        records.append(json.loads(raw))
    validate_records(*records)
    return {"status": "PASS", "stage": "C00", **PINS, "evidence": FILES,
            "scope": "PINNED_PREDECESSOR_ONLY; all C01 cumulative cases require a fresh candidate run"}
