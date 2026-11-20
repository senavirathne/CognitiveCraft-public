"""I00 player axe walkthrough across actual isolated Minecraft Docker worlds."""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import shutil
import traceback
import urllib.request
import uuid

from .gameplay import NativeGameplay, ROOT, point
from .native_evidence import TERMINAL, closed_job, item_at, job_at, stock_at, terminal_job, trace_offsets, uuid_field
from campaigns.crafting.generator import AXE, LOG, OAK, STICK, TABLE
from campaigns.crafting.harness import coord, fixture_observation
from campaigns.crafting.invariants import delta, physical_custody, totals
from campaigns.crafting.run import log_slices
from parallel_b.guards import clean_owners, clock_response
from suite import atomic, read, reconnect_players, require

PROFILE = "I00_PLAYER_AXE_WALKTHROUGH"
ASK = "/aivillage kernel ask Ada, make one wooden axe. Use the available resources, gather what is missing, and store the finished axe in this chest."
SOURCE, DESTINATION = [70, 208, 69], [77, 208, 71]
ACTOR_START = [70.5, 208, 68.5]
DEPOSIT_JOURNAL = "server/game/world/data/cognitivecraft/deposit-evidence/v1/state.jsonl"
LOGS = [[68, 208, 65], [69, 208, 65], [70, 208, 65]]
CUTS = ("after-wood-collection", "after-material-deposit", "after-table-placement", "after-craft", "at-final-delivery")
COHORTS = ("live", "saved-online", "saved-offline", "unsaved")
METHODS = {OAK, STICK, AXE, TABLE, "placement", "oak-acquisition"}
COLLECTED = "COLLECTED_PENDING_INDEPENDENT_REVIEW"


class MissedCut(Exception):
    pass


class WalkthroughHarness(NativeGameplay):
    def clock(self, command):
        require(command in {"tick rate 4", "tick rate 20", "tick freeze", "tick unfreeze"}, "Unsafe native clock operation")
        # The gameplay principal remains an ordinary player. Only the harness's
        # established server console sends the bounded vanilla clock command.
        log = self.root / "server/stdout.log"
        offset = log.stat().st_size
        control = {"id": str(uuid.uuid4()), "commands": [command]}
        atomic(self.root / "server/control.json", control)

        def acknowledgement():
            sent = read(self.root / "server/control-ack.json")
            if not sent or sent["id"] != control["id"]:
                return None
            with log.open("rb") as stream:
                stream.seek(offset)
                lines = stream.read().decode(errors="replace").splitlines()
            for line in lines:
                response = re.split(r"\]:\s+", line, maxsplit=1)[-1].strip()
                try:
                    if clock_response(command, response):
                        return response
                except ValueError:
                    continue
            return None

        response = self.wait(acknowledgement, "Actual vanilla server clock response: " + command, 30)
        self.event("verified-native-clock", {"command": command, "feedback": response, "control": control["id"]})
        return response


def cases(cohort):
    return ("sufficient-stock", "supported-shortage") if cohort == "live" else CUTS


def completed_admission_deposit(root, job):
    path = root / DEPOSIT_JOURNAL
    if not path.is_file():
        return None
    for line in path.read_text().splitlines()[1:]:
        entry = json.loads(line)
        key = entry["intent"]["key"]
        if key["root"] == key["step"] == job and (entry.get("result") or {}).get("live") == "COMPLETE":
            return entry
    return None


def launch_seed(root, required=METHODS):
    seed = NativeGameplay(root, profile=PROFILE)
    try:
        needle = ROOT / "build/needle"
        if (needle / "manifest.json").is_file():
            shutil.copytree(needle, root / "needle")
            seed.env["COGNITIVECRAFT_NEEDLE_DIR"] = "/evidence/needle"
        seed.bootstrap(required)
        require(required <= seed.artifacts.keys(), "Real method admission is incomplete")
        seed.wait(lambda: clean_owners(seed.snapshot().get("knownPlayers", {}).get("PlayerA", {})),
                  "Genuine prelude owners closed and certain", 35)
        save = seed.save_checkpoint()
        seed.stop("server")
        world = seed.world_hashes()
        require(world, "Genuine saved prelude has no Minecraft world")
        acquisition = next(row for row in seed.admissions if row["capability"] == "oak-acquisition")
        original = acquisition["result"]["views"][0]["responsibility"]
        deposit = completed_admission_deposit(root, original["id"])
        require(deposit and original["state"] == "SUCCEEDED"
                and deposit["intent"]["key"]["attempt"] == original["attempts"][-1]["id"],
                "The original admission has no closed Job and native deposit")
        atomic(root / "walkthrough-seed.json", {"worldHashes": world, "save": save, "subjects": seed.subjects,
               "artifacts": seed.artifacts, "acquisitionDeposit": deposit, "admissionsCountedAsWalkthrough": 0})
        return seed
    finally:
        seed.finish()


def backend(h):
    def available():
        try:
            with urllib.request.urlopen("http://127.0.0.1:11434/api/tags", timeout=2) as response:
                value = json.load(response)
        except OSError:
            return None
        return value if any(model["name"] == h.env["COGNITIVECRAFT_OLLAMA_MODEL"] for model in value.get("models", [])) else None
    evidence = h.wait(available, "Actual configured model available from the live backend", 50)
    h.event("i00-live-backend", evidence)
    return evidence


def boot_trial(root, seed, profile=PROFILE):
    h = WalkthroughHarness(root, profile=profile)
    # Each trial copies the admitted stopped world, then preserves that same
    # world's bytes across the measured cut. Only Docker and model cache reuse.
    h.project = seed.project
    for key in ("COGNITIVECRAFT_OLLAMA_MODEL", "COGNITIVECRAFT_NEEDLE_DIR"):
        h.env[key] = seed.env[key]
    try:
        if (seed.root / "needle").is_dir():
            shutil.copytree(seed.root / "needle", root / "needle")
        shutil.copytree(seed.root / "server/game/world", root / "server/game/world", ignore=shutil.ignore_patterns("session.lock"))
        seed_witness = read(seed.root / "walkthrough-seed.json")
        frozen = seed_witness["worldHashes"]
        require(h.world_hashes() == frozen, "Genuine stopped seed world changed during copy")
        atomic(root / "seed-continuation.json", {"worldHashes": frozen, "source": read(seed.root / "metadata.json"),
               "admissionsImportedAsI00": 0})
        h.subjects, h.artifacts = seed.subjects.copy(), seed.artifacts.copy()
        h.use_actor(0)
        h.compose("up", "-d", "ollama")
        backend(h)
        h.start("server")
        h.start("PlayerA")
        # Keep the saved villagers, storage and block untouched until the native
        # evidence owner reconciles the actual previous admission's saved cohort.
        original = seed_witness["acquisitionDeposit"]
        def verified_prelude():
            entry = completed_admission_deposit(root, original["intent"]["key"]["root"])
            return entry if (entry and entry["intent"] == original["intent"] and entry["result"] == original["result"]
                             and (entry.get("verification") or {}).get("outcome") == "SAVED_COMPLETE") else None
        settled = h.wait(verified_prelude, "Native verification of the original closed admission before fixture setup", 40)
        atomic(root / "prelude-custody-verification.json", settled)
        # Operator configuration is external to the copied world. Restore it
        # only for this pre-trial setting; prepare() removes it before the ask.
        h.console(["op PlayerA"])
        h.command("PlayerA", "/aivillage kernel inference true", "admission=true")
        return h
    except Exception:
        h.finish()
        raise


def prepare(h, shortage):
    h.start("PlayerB")
    h.console(["fill 63 208 63 84 211 78 minecraft:air", "fill 62 207 62 85 207 80 minecraft:stone",
               "kill @e[type=minecraft:item,x=63,y=207,z=63,dx=21,dy=5,dz=15]", "clear PlayerA",
               "tp PlayerA 77.5 208 74.5"])
    for _, entity in h.subjects:
        h.actor_stock({}, entity)
    h.stock(SOURCE, {} if shortage else {LOG: 3})
    h.stock(DESTINATION, {})
    expected = {",".join(map(str, pos)): LOG for pos in LOGS} if shortage else {}
    if expected:
        h.console(["setblock " + coord(pos) + " " + LOG for pos in LOGS])
    h.console(["gamemode survival PlayerA", "gamemode survival PlayerB",
               "tp PlayerB 80.5 208 73.5", "deop PlayerA", "deop PlayerB"])
    h.action("PlayerA", "look", pos=DESTINATION)
    _, client = fixture_observation(h, expected_tables=set(), expected_blocks=expected)
    initial = h.save_checkpoint()
    old_frame = h.snapshot()
    h.console(["tp " + h.entities["PlayerA"] + " " + coord(ACTOR_START)])

    def posed():
        frame = h.snapshot()
        if not frame or frame["pid"] != old_frame["pid"] or frame["tick"] <= old_frame["tick"]:
            return None
        actor = next((a for a in frame["actors"] if a["uuid"] == h.entities["PlayerA"]), None)
        return frame if (actor and actor["alive"] and not actor["noAI"]
                         and sum((a-b)**2 for a, b in zip(actor["pos"], ACTOR_START, strict=True)) <= 1) else None

    before = h.wait(posed, "Fresh native observation at the declared pre-request actor pose", 20)
    require(h.players["PlayerA"] != h.players["PlayerB"] and before["knownPlayers"]["PlayerB"]["online"],
            "Distinct connected principal required for private activity view")
    return before, client, initial


def request(h):
    h.actions_active = True
    feedback = h.command("PlayerA", ASK, r"interpretation=|job=|activity=|[Dd]enied|[Uu]navailable", 55)
    ticket = uuid_field(feedback, "interpretation")
    root = uuid_field(feedback, "job") or uuid_field(feedback, "activity")
    view = None
    if not root and ticket:
        def linked():
            value = h.run_view("PlayerA", ticket)
            return value if value and value["phase"] not in {"INTERPRETING", "RESOLVING"} else None
        view = h.wait(linked, "Actual interpretation reached its dispatch result", 90)
        root = view.get("runId") if view["phase"] == "SUBMITTED" else None
    return {"command": ASK, "feedback": feedback, "ticket": ticket, "interpretation": view, "root": root}


def public_views(h, root):
    command = "/aivillage kernel activity " + root
    owner = h.command("PlayerA", command, "activity=" + root, 45)
    foreign = h.command("PlayerB", command, r"[Dd]enied|[Uu]navailable|AUTHORITY_DENIED|not available", 30)
    require(root not in foreign and "principal=" not in foreign and "checkpoints=" not in foreign,
            "Private activity leaked to the unrelated principal")
    return {"command": command, "owner": owner, "foreign": foreign}


def resource_stages(job):
    result = Counter()
    for attempt in job["attempts"]:
        for receipt in attempt["resourceReceipts"]:
            result[receipt["stage"], receipt["item"]["id"]] += receipt["quantity"]
    return result


def tables(state):
    return {where for where, block in state["blocks"].items() if TABLE in block}


def table_binding(job):
    ref = job["request"]["request"]["arguments"]["table"]["value"]
    require(ref["dimension"] == "minecraft:overworld", "Activity selected an unobserved table dimension")
    return ",".join(map(str, point(ref)))


def witnessed(cut, job, state, actor):
    if not job or not job.get("activity") or job["state"] in TERMINAL:
        return False
    stages = resource_stages(job)
    placed, crafted, delivered = len(tables(state)), stages["CRAFT", AXE], item_at(state, DESTINATION, AXE)
    if cut == "after-wood-collection":
        return (stages["HARVEST", LOG] == stages["PICKUP", LOG] == 3 and stages["DEPOSIT", LOG] == 2
                and stock_at(state, actor).get(LOG) == 1 and not placed and not crafted)
    if cut == "after-material-deposit":
        return stages["DEPOSIT", LOG] >= 3 and not placed and not crafted
    if cut == "after-table-placement":
        attempt = next((a for a in job["attempts"] if any(r["stage"] == "PLACE" for r in a["resourceReceipts"])), None)
        return (tables(state) == {table_binding(job)} and stages["PLACE", TABLE] == 1 and not crafted and attempt
                and all(c["attempt"] != attempt["id"] for c in job["activity"]["checkpoints"]))
    if cut == "after-craft":
        return crafted == stock_at(state, actor).get(AXE, 0) == 1 and delivered == 0
    if cut == "at-final-delivery":
        return stages["DEPOSIT", AXE] == delivered == 1 and job["fulfilled"] == 0
    raise ValueError("Unknown native boundary")


def approaching_cut(cut, job, state, actor):
    if not job or not job.get("activity") or job["state"] in TERMINAL:
        return False
    stages = resource_stages(job)
    if cut in ("after-wood-collection", "after-material-deposit"):
        return stages["HARVEST", LOG] == 3 and stages["DEPOSIT", LOG] == 2
    if cut == "after-table-placement":
        return stages["CRAFT", TABLE] == 1 and not tables(state)
    if cut == "after-craft":
        return any(a["bound"]["request"]["capability"]["name"] == "cognitivecraft:produce_wooden_axes"
                   and not a.get("terminal") for a in job["attempts"])
    if cut == "at-final-delivery":
        worker = next((entry for entry in state["actors"] if entry["uuid"] == actor), None)
        return (stages["CRAFT", AXE] == 1 and stages["DEPOSIT", AXE] == 0 and worker
                and sum((x - (y + .5))**2 for x, y in zip(worker["pos"], DESTINATION, strict=True)) <= 36)
    raise ValueError("Unknown native boundary")


def stop_at_cut(h, cut, row, root_id, cohort):
    def at_cut():
        state = h.snapshot()
        job = job_at(state, "PlayerA", root_id)
        return (job, state) if witnessed(cut, job, state, row["actor"]) else None
    try:
        def approach():
            state = h.snapshot()
            job = job_at(state, "PlayerA", root_id)
            return state if approaching_cut(cut, job, state, row["actor"]) else None

        # Keep ordinary movement at 20 TPS. Slowing a full route would exceed an
        # unchanged action wall-clock limit before it reached the physical cut.
        arm = h.wait(approach, "Native physical predecessor to " + cut, 150)
        h.clock("tick rate 4")
        row["nativeCutArmedAt"] = {"tick": arm["tick"], "gameTick": arm["gameTick"]}
        h.wait(at_cut, "Actual reachable activity cut " + cut, 190)
    except TimeoutError as error:
        raise MissedCut(str(error)) from error
    h.clock("tick freeze")
    first = at_cut()
    if not first:
        raise MissedCut("Activity advanced before the acknowledged freeze")
    row["cut"] = {"name": cut, "job": first[0], "state": first[1], "client": h.client_snapshot("PlayerA")}
    h.screenshot("PlayerA", "native-cut-" + cut, chat=True)
    if cohort != "unsaved":
        row["afterCutSave"] = h.save_checkpoint()
    actual = at_cut()
    if not actual:
        raise MissedCut("Physical cut passed before the killed server JVM")
    row.update(killObservation={"job": actual[0], "state": actual[1]}, coldProcess=read(h.root / "server/process.json"),
               modelCallsAtCut=h.model_calls())
    atomic(h.root / "result.json", row)
    h.stop("server", hard=True)
    shutil.copytree(h.root / "server/game/world", h.root / "stopped-world", ignore=shutil.ignore_patterns("session.lock"))
    row["stoppedWorldHashes"] = h.world_hashes()
    if cohort == "saved-online":
        row["warmModelAvailable"] = backend(h)
    else:
        h.compose("stop", "ollama")
        h.assert_backend_stopped()
        row["warmModelUnavailable"] = True
    h.start("server")
    row["warmProcess"] = read(h.root / "server/process.json")
    require(row["warmProcess"]["start"] > row["coldProcess"]["start"] and
            row["warmProcess"]["releaseSha256"] == row["coldProcess"]["releaseSha256"], "Wrong restart JVM or release")
    reconnect_players(h, ["PlayerA", "PlayerB"])
    row["warmFirstJob"] = h.wait(lambda: h.job_view("PlayerA", root_id), "Original root visible after restart", 50)
    h.clock("tick unfreeze")
    h.clock("tick rate 20")


def assert_goal(case, row):
    job = row["originalJob"]
    require(job["state"] == "SUCCEEDED" and closed_job(job) and job["fulfilled"] == 1 and job.get("activity"),
            "Original integrated goal did not complete with a settled owner")
    require(job["id"] == row["request"]["root"] and not job.get("parent"), "Original root identity changed")
    arguments = job["request"]["request"]["arguments"]
    require(arguments["amount"]["value"] == 1 and arguments["actor"]["value"]["entityId"] == row["actor"]
            and job["request"]["context"]["principal"]["id"] == row["principal"], "Original goal, authorizer or worker changed")
    require(point(arguments["source"]["value"]) == SOURCE and point(arguments["destination"]["value"]) == DESTINATION
            and arguments["source"]["value"]["dimension"] == arguments["destination"]["value"]["dimension"] == "minecraft:overworld",
            "Actual player goal was grounded to different source or destination stock")
    before, after = row["before"], row["after"]
    require(row["clientMenuCount"] == item_at(after, DESTINATION, AXE) == 1 and item_at(before, DESTINATION, AXE) == 0,
            "Axe was not delivered to the exact pointed chest with actual client confirmation")
    site = table_binding(job)
    require(not tables(before) and "minecraft:air" in before["blocks"].get(site, "") and tables(after) == {site},
            "Originally empty grounded table site did not receive the sole native placement")
    require(delta(totals(before, row["clientBefore"]), totals(after, row["clientAfter"])) ==
            {LOG: -3, OAK: 3, STICK: 2, TABLE: 1, AXE: 1}, "Axe/table/missing-material custody does not reconcile")
    require(("principal=" + row["principal"]) in row["publicView"]["owner"] and
            row["citizen"] in row["publicView"]["owner"] and "remaining=0" in row["publicView"]["owner"],
            "Player view lacks original authorizer, worker or progress")
    effects = resource_stages(job)
    require(effects["CRAFT", AXE] == effects["DEPOSIT", AXE] == effects["PLACE", TABLE] == 1,
            "Original owner effects did not prove one placed table and one new axe")
    if case != "sufficient-stock":
        require(effects["HARVEST", LOG] == effects["PICKUP", LOG] == 3, "Shortage lacked actual eligible gathering")
    steps = job["activity"]["steps"]
    require(steps and any(s.get("choice") for s in steps) and all(s["artifact"]["sha256"] in row["knownPins"].values() for s in steps),
            "Actual recipe choice or previously admitted method pin is missing")
    receipts = [r["receiptId"] for a in job["attempts"] for r in a["resourceReceipts"]]
    require(len(set(receipts)) == len(receipts), "Duplicate native receipt reconciliation")


def external_removal(h, row, root_id):
    h.walk("PlayerA", DESTINATION)
    h.action("PlayerA", "open", pos=DESTINATION)
    def axe_slot():
        client = h.client_snapshot("PlayerA")
        return next((s for s in client.get("slots", []) if not s["playerInventory"] and s["item"] == AXE and s["count"] == 1), None)
    original = h.wait(axe_slot, "Synchronized sole delivered axe for external removal", 20)
    action = h.action("PlayerA", "menu-click", slot=original["slot"], quick=True)
    h.wait(lambda: h.container(DESTINATION, AXE) == 0, "Player physically removed one delivered axe", 20)
    client = h.client_snapshot("PlayerA")
    h.action("PlayerA", "close-menu")
    view = public_views(h, root_id)
    owner = h.job_view("PlayerA", root_id)
    require(owner["activity"]["checkpoints"] == row["originalJob"]["activity"]["checkpoints"] and owner["fulfilled"] == 1
            and "currentStock=0" in view["owner"], "Removed current stock erased or fabricated historical fulfillment")
    return {"id": "checkpoint-stock-removed", "originalRoot": root_id, "clientAction": action,
            "client": client, "job": owner, "view": view, "state": h.snapshot()}


def run_case(root, case, seed, cohort):
    root.mkdir(parents=True, exist_ok=False)
    cut = case if case in CUTS else None
    row = {"id": case, "cohort": cohort, "status": "NOT_EXECUTED_REQUIRED", "requiredCut": cut}
    h = None
    try:
        h = boot_trial(root, seed)
        h.family = case
        original_offsets = trace_offsets(root)
        before, client, initial = prepare(h, case != "sufficient-stock")
        row.update(before=before, clientBefore=client, actor=h.entities["PlayerA"], citizen=h.citizens["PlayerA"],
                   principal=h.players["PlayerA"], foreignPrincipal=h.players["PlayerB"], knownPins=h.artifacts,
                   modelCallsBefore={key: before[key] for key in ("needleCalls", "generationCalls")}, initialCheckpoint=initial)
        row["request"] = request(h)
        root_id = row["request"]["root"]
        require(root_id, "The ordinary player goal did not resolve to an activity owner")
        row["firstJob"] = h.wait(lambda: h.job_view("PlayerA", root_id), "Published original activity Job", 60)
        atomic(root / "result.json", row)
        if cut:
            stop_at_cut(h, cut, row, root_id, cohort)
        job = terminal_job(h, "PlayerA", root_id, 230)
        row.update(originalJob=job, after=h.snapshot(), clientAfter=h.client_snapshot("PlayerA"), modelCallsAfter=h.model_calls(),
                   publicView=public_views(h, root_id))
        if cohort == "unsaved" and job["state"] != "SUCCEEDED":
            require(job["state"] == "INTERRUPTED" and "next=RECOVER_OR_SETTLE" in row["publicView"]["owner"],
                    "Ambiguous unsaved case lacks a truthful scoped fence")
            h.quiet(20)
            row["afterFence"] = h.snapshot()
            require(physical_custody(row["after"]) == physical_custody(row["afterFence"]), "Uncertified cut permitted late mutation")
            row["status"] = "CONTAINMENT_ONLY"
        else:
            row["clientMenuCount"] = h.menu_count("PlayerA", DESTINATION, AXE)
            assert_goal(case, row)
            if case == "sufficient-stock":
                row["externalRemoval"] = external_removal(h, row, root_id)
            row["status"] = COLLECTED
        h.screenshot("PlayerA", "walkthrough-terminal", chat=True)
        h.screenshot("PlayerB", "foreign-private-view", chat=True)
    except MissedCut as error:
        row.update(status="INCONCLUSIVE_REQUIRED_NATIVE_WINDOW", reason=str(error))
    except Exception as error:
        row.update(status="FAIL_NATIVE", reason=str(error), trace=traceback.format_exc())
    finally:
        if h:
            row["screenshots"] = h.screenshots
            row["rawLogs"] = log_slices(root, root, original_offsets)
            h.finish()
        atomic(root / "result.json", row)
    return row


def run(root, cohort):
    root.mkdir(parents=True, exist_ok=False)
    selected = cases(cohort)
    result = {"profile": PROFILE, "cohort": cohort, "status": "FAIL_REQUIRED_CASES", "requiredCases": list(selected),
              "cases": {case: "NOT_EXECUTED_REQUIRED" for case in selected}}
    try:
        from campaigns.crafting.generator import BIRCH
        seed = launch_seed(root / "seed", METHODS | {BIRCH} if cohort == "live" else METHODS)
        for case in selected:
            row = run_case(root / "cases" / case, case, seed, cohort)
            result["cases"][case] = row["status"]
            atomic(root / "walkthrough.json", result)
            print("I00_WALKTHROUGH_CASE " + json.dumps({"id": case, "status": row["status"], "reason": row.get("reason")}), flush=True)
        allowed = {COLLECTED, "CONTAINMENT_ONLY"} if cohort == "unsaved" else {COLLECTED}
        if all(status in allowed for status in result["cases"].values()):
            result["status"] = COLLECTED
        if cohort == "live":
            from .joint_controls import run as run_joint_controls
            run_joint_controls(root / "joint", seed)
    except Exception as error:
        result.update(error=str(error), trace=traceback.format_exc())
    finally:
        atomic(root / "walkthrough.json", result)
    return result["status"] == COLLECTED


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--cohort", choices=COHORTS, required=True)
    args = parser.parse_args()
    if not run(args.root.resolve(), args.cohort):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
