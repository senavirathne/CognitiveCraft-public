"""GP-E2E-029. Real clients, ordinary chunk lifecycle/death, partial effects and restart."""
from __future__ import annotations


def scenario(h):
    from suite import ARENA, DENIED_PATTERN, require, reconnect_players
    h.fresh(two=True, known=True)
    a, b = h.citizens["PlayerA"], h.citizens["PlayerB"]
    entity = h.entities["PlayerA"]
    h.command("PlayerA", "/aivillage kernel inference false", "admission=false")
    baseline = h.model_calls()

    def citizen(player, identifier):
        return (h.snapshot() or {}).get("knownPlayers", {}).get(player, {}).get("lifecycle", {}).get(identifier)

    before = h.wait(lambda: (c if (c := citizen("PlayerA", a)) and c["availability"] == "LOADED" else None),
                    "Verified pre-unload binding")
    # Disposable setup moves the spawn ticket away. The clients then leave normally;
    # production reconciliation only observes the resulting native unload callbacks.
    h.console(["fill 9996 200 9996 10004 200 10004 minecraft:stone", "setworldspawn 10000 201 10000"])
    for player in ("PlayerA", "PlayerB"):
        h.command(player, "/tp 10000 201 10000", "Teleported")
    unloaded = h.wait(lambda: (c if (c := citizen("PlayerA", a)) and c["availability"] == "UNLOADED"
                              and c["lifecycle"]["event"] == "UNLOAD" and c["lifecycle"]["acknowledged"] == 7 else None),
                      "Actual arena chunk unload", 100)
    require(unloaded["lifecycle"]["event"] == "UNLOAD", "Chunk absence inferred death")
    for player, pos in (("PlayerA", "2.5 201 5.5"), ("PlayerB", "15.5 201 5.5")):
        h.command(player, "/tp " + pos, "Teleported")
    reloaded = h.wait(lambda: (c if (c := citizen("PlayerA", a)) and c["availability"] == "LOADED"
                              and c["lifecycle"]["event"] == "LOAD"
                              and c["lifecycle"]["generation"] == before["lifecycle"]["generation"] + 1 else None),
                      "Same serialized native entity returned", 70)
    h.wait(lambda: (c if (c := citizen("PlayerB", b)) and c["availability"] == "LOADED"
                    and c["lifecycle"]["event"] == "LOAD" else None), "Independent citizen verified after reload")
    require(reloaded["actor"] == before["actor"] and reloaded["owner"] == before["owner"]
            and reloaded["displayName"] == before["displayName"]
            and reloaded["lifecycle"]["generation"] == before["lifecycle"]["generation"] + 1,
            "Unload/reload changed identity, name, scope or generation")
    h.screenshot("PlayerA", "same-citizen-after-chunk-reload", chat=True)

    # Independent supplied field for B, prepared before either task under test.
    source_b = [14, 201, 7, 15, 201, 8]
    h.console([line for x in (14, 15) for z in (7, 8) for line in
               (f"setblock {x} 200 {z} minecraft:farmland[moisture=7]",
                f"setblock {x} 201 {z} minecraft:wheat[age=7]")])
    h.release_gate(); h.release_gate("PlayerB")
    initial = h.container(ARENA["destination"])
    h.actions_active = True
    run = h.submit(amount=6)
    h.wait(lambda: (v if (v := h.run_view("PlayerA", run)) and v["phase"] == "EXECUTING" else None),
           "Known running actor", 70)
    other_job = h.submit("PlayerB", amount=2, source=source_b, destination=ARENA["destinationB"], queued=True)
    partial = h.wait(lambda: (v if (v := h.run_view("PlayerA", run)) and v["phase"] != "TERMINAL"
                             and h.receipts(v, "DEPOSIT") > 0 else None), "Actual partial deposit before death", 80)
    # A vanilla player command supplies the actual engine death; no observer mutation.
    h.command("PlayerA", "/kill " + entity, "Killed")
    terminal = h.terminal("PlayerA", run)
    require(terminal.get("marker", {}).get("outcome") == "INTERRUPTED", "Citizen loss became success or cancellation")
    stored = h.container(ARENA["destination"]) - initial
    require(0 < stored < 6 and h.receipts(terminal, "DEPOSIT") == stored, "Partial physical delivery was lost or invented")
    job = terminal["responsibility"]
    require(job["state"] == "INTERRUPTED" and job["attempts"][-1]["uncertain"], "Uncertain responsibility became reusable")
    dead = h.wait(lambda: (c if (c := citizen("PlayerA", a)) and c["lifecycle"]["event"] == "DEATH"
                           and c["lifecycle"]["acknowledged"] == 7 else None), "Durable death cleanup")
    require(dead["actor"] == before["actor"] and dead["owner"] == before["owner"], "Death transferred identity or scope")
    h.wait(lambda: not any(l["state"] == "ACTIVE" for l in h.snapshot()["knownPlayers"]["PlayerA"].get("leases", [])),
           "Only affected holder's claims were released")
    foreign = h.wait(lambda: (j if (j := h.job_view("PlayerB", other_job)) and j["state"] == "SUCCEEDED" else None),
                     "Independent other-owner physical work", 150)
    require(foreign["fulfilled"] == 2 and h.menu_count("PlayerB", ARENA["destinationB"]) == 2,
            "Other worker/owner lost its valid progress")
    h.command("PlayerB", "/aivillage kernel status " + run, DENIED_PATTERN)
    require(a not in h.suggestions("PlayerB", "/aivillage kernel name "), "Dead identity leaked to another principal")
    receipts = terminal["receipts"]
    h.quiet(40)
    require(h.container(ARENA["destination"]) == initial + stored and h.run_view("PlayerA", run)["receipts"] == receipts,
            "Late effects or completion appeared after loss")
    h.actions_active = False
    h.screenshot("PlayerA", "interrupted-partial-delivery-after-death", chat=True)
    roster = h.snapshot()["knownPlayers"]["PlayerA"]["citizens"]
    h.console(['summon minecraft:villager 2.5 201 2.5 {PersistenceRequired:1b,CustomName:"Ada"}'])
    h.wait(lambda: any(actor["uuid"] != entity and actor.get("name") == "Ada"
                       for actor in h.snapshot()["actors"]), "Actual unrelated same-name successor")
    h.quiet(20)
    require(h.snapshot()["knownPlayers"]["PlayerA"]["citizens"] == roster
            and citizen("PlayerA", a)["lifecycle"]["event"] == "DEATH", "Same-name successor inherited a citizen")

    checkpoint = h.save_checkpoint()
    h.stop("server", hard=True)
    for player in ("PlayerA", "PlayerB"):
        h.wait(lambda: not h.action(player, "snapshot")["play"], "Crash disconnected " + player)
    h.start("server"); reconnect_players(h, ("PlayerA", "PlayerB"))
    recovered = h.wait(lambda: (c if (c := citizen("PlayerA", a)) and c["lifecycle"]["event"] == "DEATH" else None),
                       "Retained terminal identity after abrupt restart")
    require(recovered["availability"] == "UNAVAILABLE" and recovered["actor"] == before["actor"], "Crash revived a terminal binding")
    h.quiet(40)
    require(h.container(ARENA["destination"]) == initial + stored and h.model_calls() == baseline,
            "Restart replayed partial work or called a model")
    require(h.menu_count("PlayerA", ARENA["destination"]) == initial + stored, "Restarted client material state diverged")
    h.screenshot("PlayerA", "terminal-citizen-and-materials-after-restart", chat=True)
    return {"citizen": a, "entity": entity, "run": run, "otherJob": other_job, "partial": partial,
            "terminal": terminal, "stored": stored, "checkpoint": checkpoint, "modelCalls": 0,
            "variants": {name: {"status": "PASS"} for name in
                         ("unload_reload", "partial_death", "scope_isolation", "restart", "unsupported_successor")}}
