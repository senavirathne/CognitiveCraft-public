"""Supplementary D01 evidence; the original frozen 45-case corpus and verifiers stay intact."""
from __future__ import annotations

import json
import re


def language_evidence(h, prepare, verify, acquisition, typed_interface, models, needle):
    from suite import ROOT, UUID_PATTERN, require
    from oak import ITEM, DESTINATION
    corpus = json.loads((ROOT / "providers/src/test/resources/nlu/d01-requests.json").read_text())
    cases = {row["id"]: row for row in corpus}
    records = []

    def ask(row):
        answer = h.command("PlayerA", "/aivillage kernel ask "+row["text"], "interpretation="+UUID_PATTERN)
        ticket = re.search("interpretation=("+UUID_PATTERN+")", answer).group(1)
        view = h.wait(lambda: (v if (v := h.run_view("PlayerA", ticket)) and
                              v["phase"] not in ("INTERPRETING", "RESOLVING") else None),
                      "Actual D01 interpreter: "+row["id"], 40)
        h.event("d01-language-ticket", {"id": row["id"], "text": row["text"], "view": view})
        return ticket, view

    # A separate flat, supported fixture. No source/quantity/expectation in the frozen
    # historical assessment or the original RB-001 acceptance is modified.
    h.actions_active = False
    h.console(["fill 5 201 10 14 203 15 minecraft:air", "setblock 7 201 9 minecraft:barrel"])
    h.wait(lambda: "minecraft:barrel" in h.snapshot()["blocks"].get(",".join(map(str,DESTINATION)), ""),
           "D01 actual barrel fixture")
    prepare(7)
    for ident in ("D01-original", "D01-unbound-source", "D01-unbound-destination", "D01-negated",
                  "D01-unsupported-species", "D01-compound"):
        row = cases[ident]
        before = h.snapshot(); calls = h.model_calls(); h.actions_active = True
        ticket, view = ask(row)
        require(view["phase"] in ("CLARIFICATION", "REJECTED"), "Unsafe D01 dispatch: "+str(view))
        h.quiet(20)
        after = h.snapshot(); after_calls = h.model_calls()
        require(before["blocks"] == after["blocks"] and before["containers"] == after["containers"],
                "Refused D01 request changed physical resources")
        inventory = lambda state: {actor["uuid"]: actor.get("inventory", {}) for actor in state["actors"]}
        require(inventory(before) == inventory(after), "Refused D01 request changed NPC custody")
        require(after_calls["generationCalls"] == calls["generationCalls"], "Refused D01 request invoked research")
        require(after_calls["needleCalls"] == calls["needleCalls"]+1, "D01 did not use actual Needle once")
        records.append({"id": ident, "text": row["text"], "ticket": ticket, "view": view,
                        "physicalBefore": before, "physicalAfter": after,
                        "modelCallsBefore": calls, "modelCallsAfter": after_calls,
                        "physicalDelivery": "NOT_EXECUTED"})
        h.event("d01-safe-refusal", records[-1])
        h.actions_active = False

    deliveries = []
    for ident in ("D01-bring-nearest", "D01-fetch-coordinates", "D01-fetch-implicit-actor"):
        row = cases[ident]; quantity = row["amount"]
        source = prepare(quantity)
        before = h.container(DESTINATION, ITEM); calls = h.model_calls(); h.actions_active = True
        ticket, view = ask(row)
        require(view["phase"] == "SUBMITTED", "Supported D01 wording did not dispatch: "+str(view))
        result = verify(view["runId"], quantity, before, source)
        after_calls = h.model_calls()
        require(after_calls["generationCalls"] == calls["generationCalls"], "Known D01 delivery invoked generation")
        require(after_calls["needleCalls"] == calls["needleCalls"]+1, "D01 language extraction was not genuine")
        require(result["artifact"] == acquisition["artifact"], "D01 did not hand off to existing admitted oak delivery")
        result.update(id=ident, text=row["text"], ticket=ticket,
                      modelCallsBefore=calls, modelCallsAfter=after_calls)
        deliveries.append(result); h.event("d01-physical-delivery", result)
        h.screenshot("PlayerA", "d01-"+ident, chat=True)
        h.actions_active = False

    return {"canonicalAcquisition": acquisition, "typedInterface": typed_interface,
            "refusals": records, "supportedDeliveries": deliveries, "models": models, "needle": needle,
            "originalPhysicalDelivery": "NOT_EXECUTED_UNBOUND_DEMONSTRATIVE", "fullTaskPass": False,
            "scope": "Supplementary language/physical evidence; full original D01 exit gate remains unresolved"}
