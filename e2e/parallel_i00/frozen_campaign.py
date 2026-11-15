"""I00 denominators from the immutable Series B handoff artifacts."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

from e2e.parallel_b import campaign_contracts
from e2e.parallel_b.heldout_bindings import resolve

ROOT = Path(__file__).resolve().parents[2]
B = ROOT / "e2e/parallel_b"
ORIGINAL_PATH = B / "frozen/original-schedule.json"
HELD_OUT_PATH = B / "held-out-plan-branch-ready.json"
ORIGINAL_SHA256 = "6482ffa30529e5be2f7856657c695c468489f5ff237e15726c5cb23b08f9bace"
HELD_OUT_SHA256 = "06e288de8ce1cb97617d672ba09cf22c9a734fef8443f3595eff5d00d626ec84"
ORIGINAL_COUNT = 334
HELD_OUT_COUNT = 20


def checked_json(path, expected):
    body = path.read_bytes()
    if hashlib.sha256(body).hexdigest() != expected:
        raise ValueError(f"Frozen file changed: {path.relative_to(ROOT)}")
    return json.loads(body)


def original():
    cases = checked_json(ORIGINAL_PATH, ORIGINAL_SHA256)
    if (len(cases) != ORIGINAL_COUNT or len({case["id"] for case in cases}) != ORIGINAL_COUNT
            or sum(case["heldOut"] for case in cases) != 18
            or sum(case.get("missingAdapter") is True for case in cases) != 42):
        raise ValueError("Frozen original development, held-out or historical-gap denominator changed")
    # The frozen files are reviewed separately. The executing arithmetic must
    # still be those same programs, not lookalike copies with weakened checks.
    for name in ("generator", "invariants", "oracle", "recipe_model"):
        frozen = B / f"frozen/original-{name}.py.txt"
        expected = json.loads((B / "freeze.json").read_text())["files"][f"frozen/original-{name}.py.txt"]["sha256"]
        live = ROOT / f"e2e/campaigns/crafting/{name}.py"
        if any(hashlib.sha256(path.read_bytes()).hexdigest() != expected for path in (frozen, live)):
            raise ValueError(f"Original {name} assertion program changed")
    campaign_contracts.validate(json.loads((B / "qualification-specs.json").read_text()))
    return cases


def held_out():
    plan = checked_json(HELD_OUT_PATH, HELD_OUT_SHA256)
    cases = plan["cases"]
    if (len(cases) != HELD_OUT_COUNT or len({case["id"] for case in cases}) != HELD_OUT_COUNT
            or plan["nativeExecuted"] != 0 or not plan["entropyRunAfterFreeze"]
            or plan["assertionSpecSha256"] != campaign_contracts.file_sha(B / "qualification-specs.json")
            or plan["generatorSha256"] != campaign_contracts.file_sha(B / "campaign_contracts.py")):
        raise ValueError("Series B held-out definition/provenance changed")
    regenerated = campaign_contracts.held_out(plan["seed"])
    for expected, case in zip(regenerated, cases, strict=True):
        # B08 enriched the frozen plan with native binding descriptions. Those
        # values must still resolve from the same exact aliases and seed.
        if any(case.get(name) != expected[name] for name in ("id", "seed", "axis", "bindings", "expected", "status")):
            raise ValueError(f"Held-out seed projection changed: {case['id']}")
        if case.get("nativeBindings") != resolve(case["bindings"]):
            raise ValueError(f"Held-out native binding changed: {case['id']}")
    return plan


def shard(cases, index, count):
    if not 1 <= count <= len(cases) or not 0 <= index < count:
        raise ValueError("Invalid frozen-campaign shard")
    return cases[index::count]
