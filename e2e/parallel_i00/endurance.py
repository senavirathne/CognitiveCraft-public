"""Drive 1,000 real, terminal activities in one Minecraft Docker world."""

import argparse
from contextlib import contextmanager
import gzip
from hashlib import sha256
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import traceback

from .endurance_plan import COUNT, LIMITS, LOG, METHOD, PLANK, RESTART_AFTER, SHARED, SHARED_TARGETS, SOURCE, TERMINAL, TEST_TARGETS, WAREHOUSE, workload
from .identity import PRODUCTION, ROOT, candidate, sha256 as file_digest, verify_metadata
from .gameplay import startup_failure

sys.path[:0] = [str(ROOT / "e2e/parallel_a"), str(ROOT / "e2e")]
from baseline import interruption, native_process, same_native_alive
from freeze_plan import CLIENT_IMAGE, JAVA_IMAGE
from generalization_support import coord
from jobs import JobHarness
from runtime import SDK
from suite import Harness, UUID_PATTERN, atomic, read, reconnect_players, require

COMPOSE = ("e2e/compose.yml", "e2e/parallel_a/compose-leases.yml", "e2e/parallel_a/compose-jobs.yml",
           "e2e/parallel_a/compose-freeze.yml", "e2e/parallel_i00/compose-endurance.yml")


def key(point):
    return ",".join(map(str, point))


class EnduranceHarness(JobHarness):
    def __init__(self, root):
        super().__init__(root)
        self.flags(True)
        self.env["COGNITIVECRAFT_E2E_SNAPSHOT_TICKS"] = "5"
        self.recreate = False

    def compose(self, *args):
        if args == ("build", "minecraft"):
            for image in (JAVA_IMAGE, CLIENT_IMAGE):
                subprocess.run(["docker", "pull", image], check=True, timeout=300)
        if args == ("up", "-d", "--no-deps", "minecraft") and self.recreate:
            args = ("up", "-d", "--no-deps", "--force-recreate", "minecraft")
            self.recreate = False
        command = ["docker", "compose", "-p", self.project]
        for path in COMPOSE:
            command += ["-f", str(ROOT / path)]
        completed = subprocess.run([*command, *args], cwd=ROOT, env=self.env, capture_output=True, text=True, timeout=450)
        with (self.root / "docker.log").open("a") as log:
            log.write(completed.stdout + completed.stderr)
        if completed.returncode:
            raise RuntimeError(f"Endurance Compose {args}: {completed.stderr[-2500:]}")
        return completed

    def stop(self, component="server", hard=False):
        super().stop(component, hard)
        if component == "server":
            self.recreate = True

    def start(self, component):
        super().start(component)
        if component != "server":
            return
        log = self.root / "server/game/logs/latest.log"

        def ready():
            if log.is_file() and (failure := startup_failure(log.read_text(errors="replace"))):
                raise RuntimeError(failure)
            state = self.snapshot()
            return state and not state.get("kernelLoading") and "brokerStats" in state

        self.wait(ready, "Ready native endurance Jobs owner and inference counters", 60)

    def observation(self):
        return read(self.root / "server/secondary-snapshot.json")

    def enroll(self, player="PlayerA", name="Ada"):
        if name != "Hana" or self.family != "pinned-original":
            return super().enroll(player, name)

        def companion():
            state = self.observation() or {}
            candidates = [actor for actor in state.get("actors", [])
                          if actor["uuid"] not in self.entities.values() and actor["alive"]
                          and not actor["baby"] and not actor["noAI"] and not actor["controlled"]
                          and 319 <= actor["pos"][0] < 341 and 63 <= actor["pos"][2] < 80]
            require(len(candidates) <= 1, "The endurance arena contains duplicate unclaimed workers")
            return candidates[0] if candidates else None

        def approached():
            actor = companion()
            if not actor:
                return None
            client = self.client_snapshot(player)
            if not client or not client["play"]:
                return None
            if math.dist(client["pos"], actor["pos"]) <= 2.7 and any(
                    entity["uuid"] == actor["uuid"] for entity in client.get("entities", [])):
                return actor
            self.walk(player, [math.floor(v) for v in actor["pos"]], reach=1.5)
            return None

        actor = self.wait(approached, "Connected player reaches the independently observed endurance worker", 60)
        self.event("i00-endurance-companion-enrollment", {"actor": actor, "player": player,
                   "playerPosition": self.client_snapshot(player)["pos"]})
        citizen = Harness.enroll(self, player, name)
        require(self.entities[player] == actor["uuid"], "Enrollment resolved to a different native worker")
        return citizen

    @contextmanager
    def pause_before_dispatch(self):
        offset = len(self.server_log())
        self.console(["tick freeze"], lifecycle=True)
        self.wait(lambda: "frozen" in self.server_log()[offset:].lower(), "Pause native dispatch at the original clock")
        try:
            ready = int(time.time() * 1000)
            frame = self.wait(lambda: state if (state := self.observation())["time"] >= ready else None,
                              "Native observer sampled the original frozen clock")
            yield frame["gameTick"]
        finally:
            self.console(["tick unfreeze"], lifecycle=True)

    def capture(self, directory, label):
        data = (self.root / "server/secondary-snapshot.json").read_bytes()
        state = json.loads(data)
        require(state.get("knownPlayers") and state.get("arenaLoaded"), "Dedicated native observer frame missing")
        target = directory / (label + ".json.gz")
        target.write_bytes(gzip.compress(data, compresslevel=3, mtime=0))
        return state, {"path": target.relative_to(self.root).as_posix(), "sha256": sha256(data).hexdigest(),
                       "bytes": len(data), "gameTick": state["gameTick"]}

    def directory_id(self):
        stat = (self.root / "server/game/world").stat()
        return [stat.st_dev, stat.st_ino]

    def model_calls(self):
        state = self.snapshot()
        return {"needleCalls": state["needleCalls"], "generationCalls": state["brokerStats"]["calls"]}

    def submit_activity(self, case):
        player = case["player"]
        text = (f"/aivillage kernel production-activity {self.citizens[player]} birch-planks {case['demand']} "
                + coord(case["source"] + case["destination"]))
        pattern = "activity=(" + UUID_PATTERN + ")"
        started = int(time.time() * 1000)
        feedback = self.command(player, text, pattern + "|accepted=false", 35)
        if "accepted=false reason=BUDGET_EXHAUSTED" in feedback:
            self.event("i00-zero-work-refusal", {"command": text, "feedback": feedback})
            self.stable()
            feedback = self.command(player, text, pattern + "|accepted=false", 35)
        match = re.search(pattern, feedback)
        require(match and "accepted=true" in feedback, f"Required admitted activity was pre-refused: {feedback}")
        return {"root": match[1], "command": text, "feedback": feedback, "player": player,
                "principal": self.players[player], "actor": self.entities[player], "citizen": self.citizens[player],
                "requestedMillis": started, "acceptedMillis": int(time.time() * 1000)}

    def observed_job(self, ticket, state):
        return state["knownPlayers"][ticket["player"]].get("jobs", {}).get(ticket["root"])

    def await_job(self, ticket, predicate, reason, timeout=45):
        def match():
            state = self.observation()
            job = self.observed_job(ticket, state)
            return (state, job) if job and predicate(state, job) else None
        return self.wait(match, reason, timeout)

    def reserve_stock(self):
        if self.container(SOURCE, LOG):
            return
        self.console(["item replace block " + coord(SOURCE) + " container.26 with " + LOG + " 64"])
        self.wait(lambda: self.container(SOURCE, LOG) == 64, "New 64-log supply independently observed")
        self.event("i00-external-resupply", {"point": list(SOURCE), "item": LOG, "count": 64,
                                            "gameTick": self.observation()["gameTick"]})

    def verify_prelude_custody(self):
        from .walkthrough import completed_admission_deposit

        admission = next(row for row in self.admissions if row["item"] == "minecraft:oak_log")
        job = admission["view"]["responsibility"]
        deposit = completed_admission_deposit(self.root, job["id"])
        require(deposit and deposit["intent"]["key"]["attempt"] == job["attempts"][-1]["id"],
                "The oak method admission has no original native deposit")
        save = self.save_checkpoint()
        cold = native_process(self)
        self.stop("server")
        self.start("server")
        reconnect_players(self, list(self.players))

        def verified():
            row = completed_admission_deposit(self.root, job["id"])
            return row if row and row["intent"] == deposit["intent"] and row["result"] == deposit["result"] \
                and (row.get("verification") or {}).get("outcome") == "SAVED_COMPLETE" else None

        actual = self.wait(verified, "Saved original oak admission checked in a replacement JVM", 65)
        warm = native_process(self)
        require(cold["namespaceProcess"]["start"] < warm["namespaceProcess"]["start"],
                "Admission custody requires a replacement native process")
        atomic(self.root / "prelude-custody.json", {"job": job["id"], "before": deposit, "verified": actual,
               "save": save, "cold": cold, "warm": warm})

    def enroll_pinned_worker(self):
        existing = {actor["uuid"] for actor in self.snapshot()["actors"]}
        self.console(["summon minecraft:villager 64.5 208 73.5 {PersistenceRequired:1b}",
                      "tp PlayerA 64.5 208 74.5"])
        work = self.wait(lambda: next((actor for actor in self.snapshot()["actors"]
                                       if actor["uuid"] not in existing and actor["alive"]
                                       and not actor["baby"] and not actor["noAI"]), None),
                         "Separate native actor for the original unresolved reference")
        self.enroll("PlayerA", "Reserve")
        require(self.entities["PlayerA"] == work["uuid"], "Unresolved reference reused a prelude actor")
        return work

    def prepare_continuous_world(self):
        from campaigns.crafting.oracle import candidates
        atomic(self.root / "oracle-candidates.json", candidates(SDK / "minecraft-client.jar"))
        self.bootstrap([PLANK, "minecraft:oak_log"])
        require(len(self.admissions) == 2 and all(row["status"] == "PASS" for row in self.admissions)
                and METHOD in self.hashes, "Valid native prelude did not admit the required saved methods")
        self.verify_prelude_custody()
        folder = self.root / "pinned-original"
        folder.mkdir()
        self.family = "I00-pinned-original"
        # A genuine retained original is deliberately outside the 1,000 count.
        pinned_worker = self.enroll_pinned_worker()
        original = interruption(self, False, folder)
        self.companion_region()
        require(original["beforeCustody"]["actorEntity"] == pinned_worker["uuid"]
                and pinned_worker["uuid"] not in self.entities.values(), "Measured work reused the unresolved actor")
        atomic(folder / "separate-worker.json", {"worker": pinned_worker, "measuredWorkers": self.entities.copy()})
        boxes = (SOURCE, *WAREHOUSE, *TEST_TARGETS, SHARED, *SHARED_TARGETS)
        self.console(["tp PlayerB 337.5 208 71.5", "spawnpoint PlayerB 337 208 71",
                      "tp " + self.entities["PlayerB"] + " 337.5 208 73.5", "give PlayerB " + LOG + " 1",
                      *["setblock " + coord(p) + " minecraft:chest" for p in boxes],
                      "item replace block " + coord(SHARED) + " container.26 with " + LOG + " 1"])
        self.wait(lambda: all(key(p) in self.observation()["containers"] for p in boxes), "Live warehouse loaded")
        self.reserve_stock()
        status = self.stable()
        pin = next((job for job in status["hot"]["jobs"] if job["id"] == original["job"]), None)
        leases = [row for row in status["hotLeases"]["leases"] if row["owner"]["job"] == original["job"]]
        require(pin and pin["state"] == "INTERRUPTED" and leases and all(row["state"] == "INTERRUPTED" for row in leases),
                "Unresolved original Job and native lease roots are required")
        return {"job": pin, "leases": leases, "directoryId": self.directory_id(), "world": status["hot"]["worldId"]}

    def observe_pin(self, original):
        status = self.stable()
        current = {"job": next((job for job in status["hot"]["jobs"] if job["id"] == original["job"]["id"]), None),
                   "leases": [row for row in status["hotLeases"]["leases"] if row["owner"]["job"] == original["job"]["id"]],
                   "directoryId": self.directory_id(), "world": status["hot"]["worldId"]}
        require(current == original, "Original unresolved references changed under unrelated compaction")
        return current

    def reopen(self, completed, pin):
        before, directory = native_process(self), self.directory_id()
        clients = {p: read(self.root / "clients" / p / "process.json") for p in self.players}
        counts = self.model_calls()
        save = self.save_checkpoint()
        self.stop("server")
        self.wait(lambda: not same_native_alive(before), "Original native JVM replaced", 30)
        disconnected = {p: self.action(p, "snapshot") for p in self.players}
        require(all(v["play"] is False for v in disconnected.values()), "Connected server loss not witnessed")
        self.start("server")
        reconnect_players(self, list(self.players))
        after = native_process(self)
        require(self.directory_id() == directory and (before["hostJvmPid"], before["startTicks"]) !=
                (after["hostJvmPid"], after["startTicks"]), "Restart did not retain the same world in a new JVM")
        require(all(read(self.root / "clients" / p / "process.json") == old for p, old in clients.items()),
                "Same-world restart replaced connected clients")
        return {"completed": completed, "before": before, "after": after, "clients": clients, "disconnected": disconnected,
                "beforeCalls": counts, "afterCalls": self.model_calls(), "worldSave": save, "pin": self.observe_pin(pin)}


def start_case(h, case):
    folder = h.root / "activities" / f"{case['index']:04d}"
    folder.mkdir(parents=True)
    _, before = h.capture(folder, "before")
    ticket = h.submit_activity(case)
    atomic(folder / "intake.json", ticket)
    h.actions_active = True
    return folder, ticket, before


def finish_case(h, case, writing, event=None):
    folder, ticket, before = writing
    trajectory = []
    def settled(frame, job):
        actor = next((a for a in frame["actors"] if a["uuid"] == ticket["actor"]), None)
        if actor and (not trajectory or trajectory[-1]["tick"] < frame["gameTick"]):
            trajectory.append({"tick": frame["gameTick"], "position": actor["pos"]})
        return job["state"] in TERMINAL and all(a.get("terminal") and a["uncertain"] is False for a in job["attempts"])
    frame, job = h.await_job(ticket, settled, "Original terminal Job and closed effects", 180)
    started = time.monotonic_ns()
    lookup = h.wait(lambda: h.lookup(ticket["root"], ticket["player"] == "PlayerB"), "Actual Job reader", 35)
    lookup_nanos = time.monotonic_ns() - started
    require(lookup.get("job") and all(job.get(k) == v for k, v in lookup["job"].items()), "Acknowledged Job body changed")
    _, after = h.capture(folder, "after")
    row = {"case": case, "ticket": ticket, "beforeFrame": before, "afterFrame": after,
           "terminalJob": job, "lookup": lookup, "lookupNanos": lookup_nanos, "trajectory": trajectory,
           "settledMillis": int(time.time() * 1000), "event": event, "directoryId": h.directory_id()}
    atomic(folder / "result.json", row)
    print("I00_NATIVE_ENDURANCE_ACTIVITY", json.dumps({"index": case["index"], "root": job["id"], "state": job["state"]}), flush=True)
    return row


def single_case(h, case):
    h.actions_active = False
    h.reserve_stock()
    event = None
    if case["role"] == "admitted-failure":
        h.walk("PlayerB", case["destination"], reach=2.6)
    if case["role"] in {"admitted-failure", "settled-cancellation"}:
        with h.pause_before_dispatch() as paused_tick:
            writing = start_case(h, case)
            folder, ticket, _ = writing
            if case["role"] == "settled-cancellation":
                event = {"cancellation": h.cancel_job(ticket["player"], ticket["root"])}
            else:
                h.await_job(ticket, lambda _, job: job["state"] not in TERMINAL and not job["attempts"],
                            "Acknowledged original failure candidate before dispatch")
                h.action("PlayerB", "break", pos=case["destination"])
                h.wait(lambda: "minecraft:air" in h.block(case["destination"]), "Native player breaks existing destination", 30)
                obstruction, cut = h.capture(folder, "destination-removed")
                require(obstruction["gameTick"] == paused_tick, "Activity clock advanced before the destination was removed")
                event = {"obstruction": h.client_snapshot("PlayerB"), "removedFrame": cut}
    else:
        writing = start_case(h, case)
        folder, ticket, _ = writing
    if case["role"] == "partial-custody":
        before = json.loads(gzip.decompress((h.root / writing[2]["path"]).read_bytes()))
        first = next(actor for actor in before["actors"] if actor["uuid"] == ticket["actor"])["inventory"].get(PLANK, 0)
        def partial(state, job):
            actor = next(a for a in state["actors"] if a["uuid"] == ticket["actor"])
            return job["state"] not in TERMINAL and actor["inventory"].get(PLANK, 0) == first + 4 \
                and state["containers"][key(case["destination"])].get(PLANK, 0) == 0
        _, open_job = h.await_job(ticket, partial, "Actual retained craft before its delivery", 110)
        _, cut = h.capture(folder, "partial-cut")
        event = {"partialCut": cut, "openJob": open_job, "cancellation": h.cancel_job(ticket["player"], ticket["root"])}
    result = finish_case(h, case, writing, event)
    h.actions_active = False
    return result


def contend(h, first, second):
    h.actions_active = False
    h.walk("PlayerB", list(SHARED), reach=2.5)
    a = start_case(h, first)
    h.await_job(a[1], lambda _, job: job["state"] == "ACTIVE" and job["attempts"],
                "First original owner reserved the scarce stock")
    b = start_case(h, second)
    def overlap():
        frame = h.observation()
        jobs = [h.observed_job(row[1], frame) for row in (a, b)]
        return jobs if all(job and job["state"] not in TERMINAL for job in jobs) else None
    owners = h.wait(overlap, "Actual overlapping activity owners under one physical stock constraint", 30)
    _, evidence = h.capture(a[0], "contended-owner")
    result = finish_case(h, first, a, {"overlap": evidence, "owners": owners})
    _, waiting = h.await_job(b[1], lambda frame, job: job["state"] == "WAITING" and
                             frame["containers"][key(SHARED)].get(LOG, 0) == 0, "Second eligible owner waits on empty stock")
    _, waiting_frame = h.capture(b[0], "eligible-stock-wait")
    h.action("PlayerB", "open", pos=list(SHARED))
    def donor():
        native = h.client_snapshot("PlayerB")
        return next((slot for slot in native["slots"] if native["menu"] > 0 and slot["playerInventory"]
                     and slot["item"] == LOG and slot["count"] == 1), None)
    slot = h.wait(donor, "Real connected donor stock")
    eligible = int(time.time() * 1000)
    h.action("PlayerB", "menu-click", slot=slot["slot"], quick=True)
    def moved_stock():
        frame = h.client_snapshot("PlayerB")
        return frame if not any(s["playerInventory"] and s["item"] == LOG for s in frame["slots"]) else None
    donated = h.wait(moved_stock, "Connected private stock leaves donor inventory")
    h.action("PlayerB", "close-menu")
    later = finish_case(h, second, b, {"overlap": evidence, "owners": owners, "waitingOwner": waiting,
                                      "waitingFrame": waiting_frame, "donorSlot": slot, "donated": donated,
                                      "eligibleMillis": eligible})
    h.actions_active = False
    return [result, later]


def run(root, source, jar):
    require(not root.exists(), "Endurance requires a fresh output directory")
    root.mkdir(parents=True)
    status = {"schema": 1, "status": "NOT_EXECUTED", "required": COUNT, "observed": 0, "restarts": [], "clientStock": []}
    h = None
    try:
        exact = candidate(source, jar)
        atomic(root / "frozen-endurance-workload.json", {"cases": workload(), "limits": LIMITS, "restartAfter": RESTART_AFTER})
        h = EnduranceHarness(root)
        verify_metadata(read(root / "metadata.json"), exact)
        status["initialPin"] = h.prepare_continuous_world()
        status["initialProcess"] = native_process(h)
        status["initialCalls"] = h.model_calls()
        cases = iter(workload())
        for case in cases:
            rows = contend(h, case, next(cases)) if case["role"] == "contention" else [single_case(h, case)]
            completed = rows[-1]["case"]["index"]
            status["observed"] = completed
            if completed in RESTART_AFTER:
                status["restarts"].append(h.reopen(completed, status["initialPin"]))
            atomic(root / "endurance.json", status)
        status["finalPin"] = h.observe_pin(status["initialPin"])
        status["finalStorage"] = h.stable()
        status["finalProcess"] = native_process(h)
        status["finalCalls"] = h.model_calls()
        for point in (*WAREHOUSE, *SHARED_TARGETS):
            status["clientStock"].append({"position": point, "planks": h.menu_count("PlayerA", point, PLANK)})
        early = read(root / "activities/0001/intake.json")["root"]
        status["replay"] = h.wait(lambda: h.lookup(early, replay=True), "Original archived Job exact replay", 40)
        status["foreignReplay"] = h.wait(lambda: h.lookup(early, foreign=True), "Foreign archived Job privacy", 40)
        status["finalSave"] = h.save_checkpoint()
        h.stop("server")
        status["stoppedWorld"] = h.world_hashes()
        status["status"] = "COLLECTED_PENDING_INDEPENDENT_REVIEW"
    except Exception as error:
        status.update(status="FAILED_OR_INCOMPLETE", reason=str(error), trace=traceback.format_exc())
        raise
    finally:
        if h is not None:
            h.finish()
        atomic(root / "endurance.json", status)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--source", default=os.environ.get("GITHUB_SHA"))
    parser.add_argument("--jar-sha256", default=os.environ.get("I00_JAR_SHA256"))
    args = parser.parse_args()
    run(args.root, args.source, args.jar_sha256)


if __name__ == "__main__":
    main()
