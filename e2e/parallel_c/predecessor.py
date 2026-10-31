"""Require exact measured C00/C01 handoffs without discarding predecessor work."""
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
C01_PINS = {
    "source_sha": "0b08f14808cd72ee55eea6c7eacc98344629d549",
    "tree_sha": "ae26bf197649d9785a4a711dfd63294ad7488af3",
    "docs_sha": "12d758152161c45d22690afca7f2da097ee319ec",
    "docs_tree": "9d4cf62040fdfd4cdd0f7780d4153c48835900f5",
    "jar_sha256": "d1aa4112955b60fdadbdade9e9e18bc3002f743b8a8754eb4b4cc74ecbc9f18f",
}
C01_FILES = {
    "stage-manifest.json": "4ed4115cbfd93b025410e9201699a7a17b4141614273cc013c1dbdefa953e871",
    "evidence/C01-20261031/qualified.json": "6715f3bcfea2f99adede1463fca25143294b2a182e75a93077009f2e58eef5da",
}


def configuration(stage):
    check(stage in ("C00", "C01"), "predecessor stage has no qualified pin")
    return (PINS, FILES, 2371, 29) if stage == "C00" else (C01_PINS, C01_FILES, 2393, 40)


def validate_records(manifest, oracle, stage="C00"):
    pins, files, units, python = configuration(stage)
    for record in (manifest, oracle):
        check(record["stage"] == stage and record["gate_status"] == "OWNED_GATES_PASS", stage + " must be locally qualified")
        for field in ("source_sha", "tree_sha", "jar_sha256"):
            check(record[field] == pins[field], stage + " predecessor pin mismatch: " + field)
        check(record["contract_sha256"] == CONTRACT, stage + " predecessor contract mismatch")
        check(record["workflow_run"] is None and record["attempt"] is None, "unexpected predecessor CI claim")
    for tier, count in (("unit", units), ("python", python)):
        for metric in (manifest[tier], oracle["counts"][tier]):
            check(metric["planned"] == metric["executed"] == metric["passed"] == count
                  and metric["failed"] == metric["inconclusive"] == metric["refused_expected"] == 0,
                  stage + " predecessor metric mismatch: " + tier)
    check(oracle["native_executed"] == oracle["client_executed"] == 0, "unexpected predecessor physical credit")


def verify_predecessor(docs_root, stage="C00"):
    pins, files, units, python = configuration(stage)
    docs_root = Path(docs_root)
    for root, pin in ((ROOT, pins["source_sha"]), (docs_root, pins["docs_sha"])):
        check(subprocess.run(["git", "-C", str(root), "merge-base", "--is-ancestor", pin, "HEAD"], capture_output=True).returncode == 0,
              "missing qualified " + stage + " ancestor: " + pin)
    check(git(ROOT, "rev-parse", pins["source_sha"] + "^{tree}") == pins["tree_sha"], stage + " source tree mismatch")
    check(git(docs_root, "rev-parse", pins["docs_sha"] + "^{tree}") == pins["docs_tree"], stage + " docs tree mismatch")
    records = []
    for path, expected in files.items():
        raw = subprocess.check_output(["git", "-C", str(docs_root), "show", pins["docs_sha"] + ":docs/implementation/parallel-c/" + path])
        check(hashlib.sha256(raw).hexdigest() == expected, stage + " evidence digest mismatch: " + path)
        records.append(json.loads(raw))
    validate_records(*records, stage=stage)
    return {"status": "PASS", "stage": stage, **pins, "evidence": files,
            "scope": "PINNED_PREDECESSOR_ONLY; current-stage cumulative cases require a fresh candidate run"}
