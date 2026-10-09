#!/usr/bin/env python3
"""Real Minecraft client/server acceptance supervisor. No synthetic command players."""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import time
import traceback
import threading
import uuid
import xml.etree.ElementTree as ET

from runtime import ROOT, SDK, digest
from fixtures import ARENA, commands as arena_commands, write as write_fixture

FAMILIES = ["E2E-CONN-001", "E2E-CMD-001", "E2E-SUGGEST-001", "E2E-PHYSICAL-001",
            "E2E-LEGACY-001", "E2E-CANCEL-001", "E2E-MUTATION-001", "E2E-CLIENT-LOSS-001",
            "E2E-RECOVERY-001", "E2E-RECOVERY-002", "E2E-NLU-001", "E2E-AI-001", "E2E-LEASE-001"]
PROFILES = {"smoke": FAMILIES[:1], "deterministic": [x for x in FAMILIES if x not in
            ("E2E-CLIENT-LOSS-001", "E2E-RECOVERY-002", "E2E-NLU-001", "E2E-AI-001")],
            "ai": ["E2E-AI-001", "E2E-NLU-001"],
            "acquire": ["E2E-AI-001"], "nlu": ["E2E-NLU-001"],
            "resilience": ["E2E-CLIENT-LOSS-001", "E2E-RECOVERY-002"],
            "all": ["E2E-AI-001"] + [x for x in FAMILIES if x != "E2E-AI-001"]}
UUID_PATTERN = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"


class InfrastructureBlocked(RuntimeError):
    pass


def atomic(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value))
    temporary.replace(path)


def read(path: Path, default=None):
    try:
        return json.loads(path.read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return default


def require(condition, reason: str) -> None:
    if not condition:
        raise AssertionError(reason)


class Harness:
    def __init__(self, root: Path, topology: str, seed: Path | None):
        self.root = root.resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.topology = topology
        self.seed = seed
        self.processes = {}
        self.actions_active = False
        self.players = {}
        self.citizens = {}
        self.entities = {}
        self.results = []
        self.booted = set()
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0)); self.port = sock.getsockname()[1]
        self.env = {**os.environ, "E2E_RUN_DIR": str(self.root), "E2E_PORT": str(self.port),
                    "E2E_UID": str(os.getuid()), "E2E_GID": str(os.getgid())}
        metadata = {"schema": 1, "repository": "senavirathne/CognitiveCraft-public",
                    "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                    "sourceTree": subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], cwd=ROOT, text=True).strip(),
                    "dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT)),
                    "releaseSha256": digest(ROOT / "fabric/build/libs/ai-villages-0.1.0.jar"),
                    "fixtureInstance": str(uuid.uuid4()), "topology": topology, "port": self.port,
                    "minecraft": "26.3", "loader": "0.19.5", "fabricApi": "0.161.0+26.3",
                    "runtime": read(SDK / "integrity.json"), "started": time.time()}
        atomic(self.root / "metadata.json", metadata)
        self.sampling_stop = threading.Event()
        self.sampler = threading.Thread(target=self.sample_resources, name="e2e-resource-observer", daemon=True)
        self.sampler.start()

    def sample_resources(self):
        while not self.sampling_stop.is_set():
            try:
                data = {"time": time.time(), "hostLoad": os.getloadavg(),
                        "hostMemory": Path("/proc/meminfo").read_text()}
                if self.topology == "compose":
                    sampled = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{json .}}"],
                                             capture_output=True, text=True, timeout=10)
                    data["containers"] = [json.loads(line) for line in sampled.stdout.splitlines()]
                with (self.root / "resources.jsonl").open("a") as output:
                    output.write(json.dumps(data) + "\n")
            except Exception as error:
                self.event("resource-observation-error", str(error))
            self.sampling_stop.wait(10)

    def event(self, kind: str, data: object):
        with (self.root / "supervisor.jsonl").open("a") as out:
            out.write(json.dumps({"time": time.time(), "kind": kind, "data": data}) + "\n")

    def compose(self, *args):
        result = subprocess.run(["docker", "compose", "-f", str(ROOT / "e2e/compose.yml"), *args],
                                cwd=ROOT, env=self.env, text=True, capture_output=True)
        with (self.root / "docker.log").open("a") as output:
            output.write(result.stdout + result.stderr)
        if result.returncode:
            raise RuntimeError("Docker Compose " + " ".join(args) + ": " + result.stderr[-2000:])
        return result

    def start(self, component: str):
        started = time.time() * 1000
        if component != "server" and component in self.booted:
            self.action(component, "connect", address=f"127.0.0.1:{self.port}")
            state = self.wait(lambda: self.client_snapshot(component), "Fresh multiplayer world " + component, 100)
            require(state["uuid"] == self.players[component], "Reused client principal changed")
            return
        name = "minecraft" if component == "server" else "client-a" if component == "PlayerA" else "client-b"
        if self.topology == "compose":
            self.compose("up", "-d", "--no-deps", name)
        else:
            command = ["python3", str(ROOT / "e2e/launch.py"),
                       "server" if component == "server" else "client", "--root", str(self.root),
                       "--port", str(self.port)]
            if component != "server": command += ["--player", component]
            output = (self.root / (component + "-launcher.log")).open("a")
            self.processes[component] = subprocess.Popen(command, cwd=ROOT, env=self.env,
                                                        stdout=output, stderr=subprocess.STDOUT)
            output.close()
        self.event("start", {"component": component})
        self.booted.add(component)
        if component == "server":
            self.wait(lambda: (s if (s := self.snapshot()) and s["time"] >= started
                              and "Done (" in self.server_log() else None), "Dedicated server readiness", 100)
        else:
            self.wait(lambda: self.events(component) and any(x["kind"] == "driver" for x in self.events(component)),
                      "Client driver startup " + component, 120)
            self.wait(lambda: (s if (s := self.action(component, "snapshot"))["gameLoaded"]
                              and s["screen"] == "TitleScreen" else None), "Client resources/title ready", 100)
            self.action(component, "connect", address=f"127.0.0.1:{self.port}")
            state = self.wait(lambda: self.client_snapshot(component), "Real multiplayer play " + component, 100)
            require(not state["singleplayer"], "Integrated server cannot qualify")
            self.players[component] = state["uuid"]

    def server_log(self):
        path = self.root / "server/stdout.log"
        return path.read_text(errors="replace") if path.exists() else ""

    def wait(self, predicate, reason, timeout=40):
        deadline = time.monotonic() + timeout
        last = None
        while time.monotonic() < deadline:
            try:
                last = predicate()
                if last:
                    return last
            except (FileNotFoundError, json.JSONDecodeError, KeyError):
                pass
            for component, process in self.processes.items():
                if process.poll() is not None and component not in ("stopped",):
                    raise RuntimeError(f"Unexpected {component} launcher exit {process.returncode}: {reason}")
            time.sleep(0.1)
        raise TimeoutError(f"{reason}; last={str(last)[:600]}")

    def action(self, player, action, **params):
        request = {"id": str(uuid.uuid4()), "action": action, **params}
        box = self.root / "clients" / player
        atomic(box / "request.json", request)
        result = self.wait(lambda: (r if (r := read(box / "response.json")) and r["id"] == request["id"] else None),
                           f"Client action {player}: {action}", 60)["result"]
        if "error" in result:
            raise RuntimeError(result["error"])
        return result

    def client_snapshot(self, player):
        state = self.action(player, "snapshot")
        return state if state.get("play") else None

    def snapshot(self):
        return read(self.root / "server/snapshot.json")

    def events(self, player):
        path = self.root / "clients" / player / "events.jsonl"
        if not path.exists(): return []
        result = []
        for line in path.read_text().splitlines():
            try: result.append(json.loads(line))
            except json.JSONDecodeError: pass
        return result

    def command(self, player, text, expected, timeout=30):
        offset = len(self.events(player))
        self.action(player, "command", text=text)
        def feedback():
            return next((e["data"]["text"] for e in self.events(player)[offset:]
                         if e["kind"] == "feedback" and re.search(expected, e["data"]["text"])), None)
        response = self.wait(feedback, f"Feedback {player}: {text} expected={expected}", timeout)
        self.event("command", {"player": player, "text": text, "feedback": response})
        return response

    def suggestions(self, player, text, tab=False):
        offset = len(self.events(player))
        self.action(player, "tab" if tab else "suggest", text=text)
        def observed():
            events = self.events(player)[offset:]
            requests = [e["data"] for e in events if e["kind"] == "suggestion-request"
                        and e["data"]["text"].lstrip("/") == text.lstrip("/")]
            responses = [e["data"] for e in events if e["kind"] == "suggestion-response"]
            return next((r for r in reversed(responses) if any(q["transaction"] == r["transaction"] for q in requests)), None)
        result = self.wait(observed, "Fresh matching wire suggestion transaction " + text)
        require(result["start"] >= 0 and result["length"] >= 0, "Invalid suggestion replacement range")
        self.event("suggestions", {"player": player, "input": text, **result})
        return result["values"]

    def console(self, lines, lifecycle=False):
        if self.actions_active and not lifecycle:
            raise AssertionError("Administrative setup forbidden during gameplay under test")
        request = {"id": str(uuid.uuid4()), "commands": lines}
        atomic(self.root / "server/control.json", request)
        self.wait(lambda: (r if (r := read(self.root / "server/control-ack.json")) and r["id"] == request["id"] else None),
                  "Console setup delivery")
        self.event("console-lifecycle" if lifecycle else "fixture-setup", request)

    def setup(self, two=True, legacy=False):
        write_fixture(self.root, "F3" if legacy else "F0")
        self.console(arena_commands(two, legacy))
        self.console(["op PlayerA", "tp PlayerA 2.5 201 5.5 180 0", "spawnpoint PlayerA 2 201 5"] +
                     (["op PlayerB", "tp PlayerB 15.5 201 5.5 180 0", "spawnpoint PlayerB 15 201 5"] if two else []))
        self.wait(lambda: self.snapshot() and len(self.snapshot()["actors"]) == (2 if two else 1), "Contained adult villagers")
        state = self.snapshot()
        require(all("open=false" in state["blocks"][f"{x},201,4"] for x in ([2, 15] if two else [2])), "Closed pen gates")
        require(all(201 <= a["pos"][1] < 202 for a in state["actors"]), "Villagers safely on floating platform")
        require(all(a["alive"] and not a["baby"] and not a["noAI"] for a in state["actors"]), "Adult villagers must retain normal AI")
        for player, x in [("PlayerA", 2)] + ([("PlayerB", 15)] if two else []):
            self.wait(lambda: (s if (s := self.client_snapshot(player)) and
                              math.dist(s["pos"], [x+.5, 201, 5.5]) < .5 else None), "Synchronized arena spawn " + player)
            candidates = [a for a in state["actors"] if math.dist(a["pos"], [x+.5,201,5.5]) <= 6]
            require(len(candidates) == 1 and x < candidates[0]["pos"][0] < x+1,
                    "Exactly one eligible villager must remain in this player's enclosure")

    def enroll(self, player="PlayerA", name="Ada"):
        text = self.command(player, "/aivillage kernel enroll", "Kernel actor=" + UUID_PATTERN)
        citizen = re.search("actor=(" + UUID_PATTERN + ")", text).group(1)
        entity = re.search("entity=(" + UUID_PATTERN + ")", text).group(1)
        self.citizens[player] = citizen; self.entities[player] = entity
        self.wait(lambda: citizen in self.command(player, "/aivillage kernel citizens", "citizens="), "Durable citizen publication")
        require(citizen in self.suggestions(player, "/aivillage kernel name ", tab=True), "Tab omitted owned citizen")
        self.command(player, f"/aivillage kernel name {citizen} {name}", "name=" + re.escape(name))
        self.wait(lambda: name in self.command(player, "/aivillage kernel citizens", "citizens="), "Durable name publication")
        return citizen

    def release_gate(self, player="PlayerA"):
        self.action(player, "use", pos=ARENA["gateA" if player == "PlayerA" else "gateB"])
        x = 2 if player == "PlayerA" else 15
        self.wait(lambda: "open=true" in self.snapshot()["blocks"][f"{x},201,4"], "Player opened controlled exit")

    def disconnect(self, player):
        self.action(player, "disconnect")
        self.wait(lambda: player not in self.snapshot()["players"], "Server acknowledged disconnect")

    def stop(self, component="server", hard=False):
        box = self.root / ("server" if component == "server" else "clients/" + component)
        info = read(box / "process.json")
        if self.topology == "compose":
            name = "minecraft" if component == "server" else "client-a" if component == "PlayerA" else "client-b"
            self.compose("kill", "-s", "SIGKILL", name) if hard else self.compose("stop", name)
        elif component in self.processes:
            process = self.processes.pop(component)
            if hard:
                require(info and info["pid"] != os.getpid(), "Owned child JVM required for SIGKILL")
                # Validate direct parent recorded by this run's launcher before signalling.
                stat = Path(f"/proc/{info['pid']}/stat").read_text().split(") ", 1)[1].split()
                require(int(stat[1]) == process.pid, "Refusing to signal an unrelated JVM")
                os.kill(info["pid"], signal.SIGKILL)
            elif component == "server": self.console(["stop"], lifecycle=True)
            else: self.action(component, "exit")
            try: process.wait(timeout=45)
            except subprocess.TimeoutExpired:
                process.terminate(); process.wait(timeout=15)
        self.event("sigkill" if hard else "stop", {"component": component, "process": info})
        self.booted.discard(component)

    def fresh(self, two=False, known=False, legacy=False):
        self.actions_active = False
        if "server" in self.booted:
            self.stop("server")
            for player in self.booted:
                self.wait(lambda: not self.action(player, "snapshot")["play"], "Isolated world disconnect")
        game = self.root / "server/game"
        if game.exists():
            # Preserve each preceding isolated fixture/evidence rather than overwriting it.
            game.rename(self.root / ("saved-game-" + str(uuid.uuid4())[:8]))
        if known:
            if not self.seed or not (self.seed / "certificate.json").exists():
                raise InfrastructureBlocked("F1 requires a genuine admitted stopped-world certificate; run the ai profile first")
            certificate = read(self.seed / "certificate.json")
            require(certificate["semanticJarSha256"] == semantic_jar(), "F1 production code/primitive certificate mismatch")
            for item in certificate["files"]:
                require(digest(self.seed / "game" / item["path"]) == item["sha256"], "F1 saved-world integrity mismatch")
            shutil.copytree(self.seed / "game", game)
            self.citizens = certificate["citizens"].copy()
            self.entities = certificate["entities"].copy()
            write_fixture(self.root, "F1")
        else:
            self.citizens.clear(); self.entities.clear()
        self.start("server"); self.start("PlayerA")
        if two: self.start("PlayerB")
        if known:
            self.console(["op PlayerA", "tp PlayerA 2.5 201 5.5 180 0"] +
                         (["op PlayerB", "tp PlayerB 15.5 201 5.5 180 0"] if two else []))
            for player, x in [("PlayerA", 2)] + ([("PlayerB", 15)] if two else []):
                self.wait(lambda: math.dist(self.client_snapshot(player)["pos"], [x+.5,201,5.5]) < .5,
                          "Restored fixture synchronized spawn " + player)
            self.wait(lambda: self.citizens["PlayerA"] in self.command("PlayerA", "/aivillage kernel citizens", "citizens="), "Restored F1 identities")
        else:
            self.setup(two, legacy)

    def submit(self, player="PlayerA", amount=4, source=None, destination=None, queued=False, allow_rejection=False):
        source = source or ARENA["source"]; destination = destination or ARENA["destination"]
        prefix = "queue" if queued else "harvest"
        text = f"/aivillage kernel {prefix} {self.citizens[player]} {amount} " + " ".join(map(str, source + destination))
        answer = self.command(player, text, ("job=" if queued else "run=") + UUID_PATTERN)
        self.last_submission = answer
        if not allow_rejection: require("accepted=true" in answer, "Physical request rejected: " + answer)
        return re.search(("job=" if queued else "run=") + "(" + UUID_PATTERN + ")", answer).group(1)

    def run_view(self, player, run):
        return (self.snapshot() or {}).get("players", {}).get(player, {}).get("runs", {}).get(run)

    def terminal(self, player, run, timeout=130):
        return self.wait(lambda: (s if (s := self.run_view(player, run)) and s["phase"] == "TERMINAL" else None),
                         "Trusted terminal outcome " + run, timeout)

    def receipts(self, view, stage):
        return sum(r["wheat"] for r in view.get("receipts", []) if r["stage"] == stage)

    def container(self, destination, item="minecraft:wheat"):
        return self.snapshot()["containers"].get(",".join(map(str, destination)), {}).get(item, 0)

    def walk(self, player, target, reach=2.7):
        def arrived():
            state = self.client_snapshot(player)
            x, _, z = state["pos"]; dx = target[0] + .5 - x; dz = target[2] + .5 - z
            if math.hypot(dx, dz) <= reach:
                self.action(player, "stop-move"); return state
            self.action(player, "move", yaw=math.degrees(math.atan2(-dx, dz)), ticks=6)
            return None
        return self.wait(arrived, "Ordinary player walking " + player, 40)

    def menu_count(self, player, destination, item="minecraft:wheat"):
        expected = self.container(destination, item)
        self.walk(player, destination)
        self.action(player, "open", pos=destination)
        state = self.wait(lambda: (s if (s := self.client_snapshot(player))["menu"] > 0 and
                          sum(slot["count"] for slot in s["slots"] if not slot["playerInventory"] and
                              slot["item"] == item) == expected else None), "Client received synchronized container contents")
        result = sum(slot["count"] for slot in state["slots"] if not slot["playerInventory"] and slot["item"] == item)
        self.action(player, "close-menu")
        return result

    def quiet(self, tick_count=40):
        start = self.snapshot()["tick"]
        self.wait(lambda: self.snapshot()["tick"] >= start + tick_count, "Post-boundary effect fence", 20)

    def finish(self):
        self.sampling_stop.set(); self.sampler.join(timeout=12)
        for component in list(self.booted):
            with contextlib.suppress(Exception): self.stop(component)
        if self.topology == "compose":
            with contextlib.suppress(Exception): self.compose("down", "--remove-orphans")


def smoke(h: Harness):
    h.fresh()
    citizen = h.enroll()
    state = h.client_snapshot("PlayerA")
    require("kernel" in state["tree"] and "name" in state["tree"]["kernel"], "Received kernel command tree")
    h.command("PlayerA", "/aivillage kernel inference false", "admission=false")
    h.command("PlayerA", "/aivillage kernel catalog", "recentModelCalls=0")
    h.command("PlayerA", f"/aivillage kernel name {citizen} " + "x" * 49, "Name: REQUEST_INVALID")
    h.command("PlayerA", f"/aivillage kernel name {uuid.uuid4()} Unknown", "Name: AUTHORITY_DENIED")
    h.disconnect("PlayerA")
    h.action("PlayerA", "connect", address=f"127.0.0.1:{h.port}")
    require(h.wait(lambda: h.client_snapshot("PlayerA"), "Reconnect")["uuid"] == h.players["PlayerA"], "Reconnect identity changed")
    require("Ada" in h.command("PlayerA", "/aivillage kernel citizens", "citizens="), "Name lost on reconnect")
    h.disconnect("PlayerA")
    return {"citizen": citizen, "entity": h.entities["PlayerA"], "players": h.players, "modelCalls": 0}


def semantic_jar():
    from zipfile import ZipFile
    with ZipFile(ROOT / "fabric/build/libs/ai-villages-0.1.0.jar") as archive:
        checksum = hashlib.sha256()
        for name in sorted(archive.namelist()):
            if not name.endswith("/"):
                checksum.update(name.encode()); checksum.update(b"\0"); checksum.update(archive.read(name))
        return checksum.hexdigest()


def commands_and_permissions(h: Harness):
    h.fresh(); citizen = h.enroll()
    expected = {"enroll", "harvest", "queue", "job-status", "job-cancel", "status", "cancel",
                "inference", "catalog", "name", "citizens", "ask"}
    require(expected <= h.client_snapshot("PlayerA")["tree"]["kernel"].keys(), "Incomplete synchronized operator tree")
    h.console(["deop PlayerA"], lifecycle=True)
    normal = h.wait(lambda: (s if "enroll" not in (s := h.client_snapshot("PlayerA"))["tree"]["kernel"] else None), "Live deop command tree refresh")
    require("inference" not in normal["tree"]["kernel"], "Operator inference command remains after deop")
    require(expected - {"enroll", "inference"} <= normal["tree"]["kernel"].keys(), "Owned commands disappeared after deop")
    h.command("PlayerA", f"/aivillage kernel name {citizen} Áda 🌾", "name=Áda 🌾")
    invalid = f"/aivillage kernel harvest {citizen} 0 " + " ".join(map(str, ARENA["source"] + ARENA["destination"]))
    h.command("PlayerA", invalid, "accepted=false reason=REQUEST_INVALID")
    h.command("PlayerA", "/aivillage kernel name malformed Ada", "Invalid actor-id")
    h.console(["op PlayerA"], lifecycle=True)
    h.wait(lambda: "enroll" in h.client_snapshot("PlayerA")["tree"]["kernel"], "Live op tree refresh")
    require(h.snapshot()["generationCalls"] == 0, "Wire validation invoked generation")
    return {"operatorNodes": sorted(expected), "normalNodes": sorted(normal["tree"]["kernel"])}


def privacy(h: Harness):
    h.fresh(two=True); a = h.enroll(); b = h.enroll("PlayerB", "Mira")
    evidence = []
    for player, own, foreign in [("PlayerA", a, b), ("PlayerB", b, a)]:
        for command in ("name", "harvest", "queue"):
            ids = h.suggestions(player, f"/aivillage kernel {command} ")
            require(own in ids and foreign not in ids, "Cross-player citizen suggestion leak")
        h.command(player, f"/aivillage kernel name {foreign} Stolen", "Name: AUTHORITY_DENIED|[Aa]uthor|[Uu]nauthor")
        run = h.submit(player)
        # This F0 intentionally lacks knowledge. It proves private wire/control, not physical success.
        view = h.terminal(player, run)
        require(view.get("outcome", {}).get("status") != "SUCCEEDED", "Fresh model-free world fabricated knowledge")
        job = h.submit(player, queued=True)
        evidence.append({"player": player, "run": run, "job": job})
    for row in evidence:
        owner = row["player"]; foreign = "PlayerB" if owner == "PlayerA" else "PlayerA"
        require(row["run"] in h.suggestions(owner, "/aivillage kernel status "), "Owned retained run missing")
        require(row["run"] not in h.suggestions(foreign, "/aivillage kernel status "), "Foreign run suggested")
        require(row["job"] not in h.suggestions(foreign, "/aivillage kernel job-status "), "Foreign job suggested")
        h.command(foreign, "/aivillage kernel status " + row["run"], "[Uu]nauthor|[Aa]uthor|[Pp]rivate|Unknown|unknown|not found")
        h.command(foreign, "/aivillage kernel cancel " + row["run"], "[Uu]nauthor|[Aa]uthor|[Pp]rivate|Unknown|unknown|not found")
        h.command(foreign, "/aivillage kernel job-status " + row["job"], "[Uu]nauthor|[Aa]uthor|[Pp]rivate|Unknown|unknown|not found")
        h.command(foreign, "/aivillage kernel job-cancel " + row["job"], "[Uu]nauthor|[Aa]uthor|[Pp]rivate|Unknown|unknown|not found")
        h.command(owner, "/aivillage kernel job-cancel " + row["job"], "cancellation=")
    h.disconnect("PlayerB"); h.action("PlayerB", "connect", address=f"127.0.0.1:{h.port}")
    h.wait(lambda: h.client_snapshot("PlayerB"), "Fresh privacy reconnect")
    require(a not in h.suggestions("PlayerB", "/aivillage kernel name "), "Stale foreign suggestion after reconnect")
    return {"records": evidence, "operatorA": True, "operatorB": True, "generationCalls": h.snapshot()["generationCalls"]}


def delivery(h: Harness, player="PlayerA", amount=4, source=None, destination=None, acquisition=False, language=False):
    started = time.time() * 1000
    source = source or ARENA["source"]; destination = destination or ARENA["destination"]
    before = h.container(destination)
    actor_before = next(x["pos"] for x in h.snapshot()["actors"] if x["uuid"] == h.entities[player])
    calls_before = h.snapshot()["generationCalls"]
    h.actions_active = True
    ticket = None
    if language:
        message = f"Ada, harvest {amount} wheat from {','.join(map(str,source[:3]))} through {','.join(map(str,source[3:]))} and deliver it to the container at {','.join(map(str,destination))}."
        answer = h.command(player, "/aivillage kernel ask " + message, "interpretation=" + UUID_PATTERN)
        ticket = re.search("interpretation=(" + UUID_PATTERN + ")", answer).group(1)
        interpreted = h.wait(lambda: (v if (v := h.run_view(player, ticket)) and v["phase"] not in
                                ("INTERPRETING", "RESOLVING") else None), "Actual Needle acquisition request", 40)
        require(interpreted["phase"] == "SUBMITTED", "Needle did not submit the supported request: " + str(interpreted))
        run = interpreted["runId"]
    else:
        run = h.submit(player, amount, source, destination)
    view = h.terminal(player, run, 650 if acquisition else 140)
    h.event("physical-terminal", {"run": run, "view": view})
    h.command(player, "/aivillage kernel status " + run, "phase=TERMINAL")
    wanted = "ADMITTED" if acquisition else "SUCCEEDED"
    require(wanted in view["description"], "Requested physical outcome failed: " + view["description"] +
            " usage=" + str(view.get("responsibility", {}).get("usage")))
    quantities = {stage: h.receipts(view, stage) for stage in ("HARVEST", "PICKUP", "DEPOSIT")}
    require(all(v >= amount for v in quantities.values()), "Missing trusted physical stage receipts")
    require(quantities["DEPOSIT"] == amount and h.container(destination) == before + amount, "Incorrect or duplicated wheat delivery")
    require(len({r["receiptId"] for r in view["receipts"]}) == len(view["receipts"]), "Duplicate receipt identities")
    for receipt in view["receipts"]:
        require(receipt["actor"]["citizenId"] == h.citizens[player] and receipt["actor"]["entityId"] == h.entities[player],
                "Foreign actor credited to delivery")
        require([receipt["source"][key] for key in ("minX","minY","minZ","maxX","maxY","maxZ")] == source and
                [receipt["destination"][key] for key in ("x","y","z")] == destination, "Wrong source or destination receipt")
    movement = [a["pos"] for line in (h.root / "server/observations.jsonl").read_text().splitlines()
                for observation in [json.loads(line)] if observation["time"] >= started
                for a in observation["actors"] if a["uuid"] == h.entities[player]]
    require(any(math.dist(actor_before, pos) > .5 for pos in movement), "No observed physical villager travel")
    observed = h.menu_count(player, destination)
    require(observed == h.container(destination), "Connected client menu differs from authoritative chest")
    client = h.client_snapshot(player)
    current = h.snapshot()
    for x in range(source[0], source[3]+1):
        for z in range(source[2], source[5]+1):
            key = f"{x},{source[1]},{z}"
            require(client["blocks"][key] == current["blocks"][key], "Client crop synchronization differs")
    client_actor = next(e for e in client["entities"] if e["uuid"] == h.entities[player])
    server_actor = next(e for e in current["actors"] if e["uuid"] == h.entities[player])
    require(math.dist(client_actor["pos"], server_actor["pos"]) < 1, "Client villager position diverged")
    calls_after = current["generationCalls"]
    require(calls_after > calls_before if acquisition else calls_after == calls_before, "Wrong generation dependence")
    h.actions_active = False
    return {"run": run, "ticket": ticket, "artifact": view.get("marker", {}).get("artifactSha256"),
            "quantities": quantities, "clientWheat": observed, "generationCalls": calls_after - calls_before}


def physical(h: Harness):
    h.fresh(known=True); h.release_gate()
    result = delivery(h)
    # Invalid, immature and shortage variants use fresh isolated F1 copies.
    negatives = []
    for variant, source, amount in [("immature", [4,201,10,4,201,10], 1), ("shortage", ARENA["source"], 7),
                                   ("unloaded", [1000,201,1000,1001,201,1001], 4),
                                   ("full", ARENA["source"], 4), ("removed", ARENA["source"], 4),
                                   ("unavailable", ARENA["source"], 4), ("obstructed", ARENA["source"], 4)]:
        h.fresh(known=True); h.release_gate()
        if variant == "full":
            items = ",".join('{Slot:'+str(i)+'b,id:"minecraft:stone",count:64}' for i in range(27))
            h.console(['data merge block 9 201 7 {Items:['+items+']}'])
            h.wait(lambda: h.container(ARENA["destination"], "minecraft:stone") == 1728, "Full fixture verified")
        if variant == "removed":
            h.console(["setblock 9 201 7 minecraft:air"])
            h.wait(lambda: "air" in h.snapshot()["blocks"]["9,201,7"], "Absent destination fixture")
        if variant == "unavailable":
            h.console(["kill " + h.entities["PlayerA"]])
            h.wait(lambda: all(a["uuid"] != h.entities["PlayerA"] for a in h.snapshot()["actors"]), "Unavailable actor fixture")
        h.actions_active = True
        run = h.submit(amount=amount, source=source,
                       destination=ARENA["unreachableDestination"] if variant == "obstructed" else None, allow_rejection=True)
        if "accepted=false" in h.last_submission:
            description = h.last_submission
        else:
            view = h.terminal("PlayerA", run); description = view["description"]
        require("SUCCEEDED" not in description and h.container(ARENA["destination"]) == 0, "Negative request falsely delivered")
        require(h.snapshot()["generationCalls"] == 0, "Known environmental blocker invited inference")
        negatives.append({"variant": variant, "outcome": description}); h.actions_active = False
    return {"delivery": result, "negativeVariants": negatives}


def legacy(h: Harness):
    h.fresh(legacy=True)
    mature = [f"setblock {x} 200 {z} minecraft:farmland[moisture=7]" for x in range(4,8) for z in range(6,13)]
    mature += [f"setblock {x} 201 {z} minecraft:wheat[age=7]" for x in range(4,8) for z in range(6,13)]
    h.console(mature)
    h.command("PlayerA", "/aivillage enroll Baker", "Enrolled Baker")
    h.command("PlayerA", "/aivillage home Baker 9 201 7", "Work area set")
    require("Baker" in h.suggestions("PlayerA", "/aivillage status "), "Legacy name suggestion missing")
    h.release_gate(); h.actions_active = True
    h.command("PlayerA", "/aivillage food Baker", "food: Baker")
    h.wait(lambda: h.container(ARENA["destination"], "minecraft:bread") >= 8, "Real legacy harvest/craft/store bread", 180)
    amount = h.menu_count("PlayerA", ARENA["destination"], "minecraft:bread")
    require(amount >= 8, "Legacy bread not synchronized in client menu")
    h.command("PlayerA", "/aivillage status Baker", "Baker")
    h.command("PlayerA", "/aivillage stop Baker", "stop: Baker")
    h.actions_active = False
    return {"bread": amount, "contract": "legacy physical farming and fixed bread recipe"}


def cancellation(h: Harness):
    variants = []
    for partial in (False, True):
        h.fresh(known=True)
        source = ARENA["source"]
        amount = 6
        if partial:
            source = [5,201,6,8,201,9]; amount = 16
            setup = []
            for x in range(5,9):
                for z in range(6,10):
                    setup += [f"setblock {x} 200 {z} minecraft:farmland[moisture=7]",
                              f"setblock {x} 201 {z} minecraft:wheat[age=7]"]
            h.console(setup)
            h.wait(lambda: sum("wheat[age=7]" in h.snapshot()["blocks"][f"{x},201,{z}"]
                              for x in range(5,9) for z in range(6,10)) == 16, "Larger real partial-cancellation stock")
        h.release_gate(); h.actions_active = True
        run = h.submit(amount=amount, source=source)
        def boundary():
            view = h.run_view("PlayerA", run)
            if not view or view["phase"] == "TERMINAL": return None
            return view if (h.receipts(view, "DEPOSIT") > 0 if partial else view["phase"] == "EXECUTING") else None
        view = h.wait(boundary, "Committed partial cancellation boundary" if partial else "Travel cancellation boundary", 60)
        require(run in h.suggestions("PlayerA", "/aivillage kernel cancel "), "Active run missing from real cancel suggestions")
        h.command("PlayerA", "/aivillage kernel cancel " + run, "run=" + run)
        final = h.terminal("PlayerA", run)
        require("CANCELLED" in final["description"], "Network cancellation did not terminate correctly")
        count = h.container(ARENA["destination"]); receipts = final["receipts"]
        h.quiet()
        require(h.container(ARENA["destination"]) == count and h.run_view("PlayerA", run)["receipts"] == receipts, "Effects continued after cancellation")
        require(not next(a for a in h.snapshot()["actors"] if a["uuid"] == h.entities["PlayerA"])["controlled"], "Villager control not released")
        require(not partial or count > 0, "Committed partial progress disappeared")
        require(run in h.suggestions("PlayerA", "/aivillage kernel status ") and
                run not in h.suggestions("PlayerA", "/aivillage kernel cancel "), "Terminal control suggestions did not refresh")
        h.disconnect("PlayerA"); h.action("PlayerA", "connect", address=f"127.0.0.1:{h.port}")
        h.wait(lambda: h.client_snapshot("PlayerA"), "Cancelled run reconnect")
        h.command("PlayerA", "/aivillage kernel status " + run, "CANCELLED")
        require(h.menu_count("PlayerA", ARENA["destination"]) == count, "Cancelled stock disappeared from fresh client menu")
        variants.append({"partial": partial, "run": run, "conservedWheat": count}); h.actions_active = False
    return {"variants": variants}


def interference(h: Harness):
    variants = []
    for target in ([6,201,6], ARENA["destination"]):
        h.fresh(two=True, known=True)
        # B's setup position permits a genuine ordinary reach-bounded block break.
        h.console([f"tp PlayerB {target[0]+1.5} 201 {target[2]+1.5}"])
        h.release_gate(); h.actions_active = True
        run = h.submit(amount=6)
        h.wait(lambda: (v := h.run_view("PlayerA", run)) and v["phase"] == "EXECUTING", "Concurrent worker execution")
        h.action("PlayerB", "break", pos=target)
        key = ",".join(map(str, target))
        h.wait(lambda: "air" in h.snapshot()["blocks"][key], "Real Player B block break")
        final = h.terminal("PlayerA", run)
        require("SUCCEEDED" not in final["description"], "External player action falsely credited to villager")
        require(h.receipts(final, "DEPOSIT") < 6, "External action produced fictitious full delivery")
        variants.append({"target": target, "run": run, "outcome": final["description"]}); h.actions_active = False
    return {"variants": variants}


def queued_competition(h: Harness):
    h.fresh(two=True, known=True)
    h.console(["setblock 7 201 8 minecraft:air"])
    h.release_gate(); h.release_gate("PlayerB"); h.actions_active = True
    a = h.submit(amount=3, queued=True)
    b = h.submit("PlayerB", amount=3, destination=ARENA["destinationB"], queued=True)
    for player, own, foreign in [("PlayerA",a,b),("PlayerB",b,a)]:
        require(own in h.suggestions(player, "/aivillage kernel job-status ") and foreign not in
                h.suggestions(player, "/aivillage kernel job-status "), "Queued job packet privacy failed")
        h.command(player, "/aivillage kernel job-status " + own, "job=" + own)
    h.wait(lambda: h.container(ARENA["destination"]) + h.container(ARENA["destinationB"]) > 0, "Real competing dispatcher effects", 130)
    h.quiet(100)
    state = h.snapshot()
    jobs = [state["players"][p]["jobs"][j] for p,j in [("PlayerA",a),("PlayerB",b)]]
    total = h.container(ARENA["destination"]) + h.container(ARENA["destinationB"])
    require(total <= 5 and sum(j["fulfilled"] for j in jobs) <= 5, "Shared five-wheat pool duplicated")
    require(not all(j["fulfilled"] == 3 for j in jobs), "Both three-unit jobs claimed six from five")
    for p,j in [("PlayerA",a),("PlayerB",b)]:
        h.command(p, "/aivillage kernel job-cancel " + j, "cancellation=")
    h.quiet(); require(h.container(ARENA["destination"]) + h.container(ARENA["destinationB"]) == total, "Effects after queued cancellation")
    h.actions_active = False
    return {"jobs": [a,b], "delivered": total, "state": jobs}


def reconnect_players(h: Harness, players):
    for player in players:
        h.action(player, "connect", address=f"127.0.0.1:{h.port}")
        state = h.wait(lambda: h.client_snapshot(player), "Post-restart real play " + player, 100)
        require(state["uuid"] == h.players[player], "Restart changed controlling principal")
        require(h.citizens[player] in h.command(player, "/aivillage kernel citizens", "citizens="), "Restart lost citizen identity")


def graceful_recovery(h: Harness):
    h.fresh(two=True, known=True); h.release_gate(); h.actions_active = True
    run = h.submit(amount=6)
    h.wait(lambda: (v := h.run_view("PlayerA", run)) and v.get("receipts"), "Active real-effect restart boundary")
    h.stop("server")
    for player in ("PlayerA", "PlayerB"):
        h.wait(lambda: not h.action(player, "snapshot")["play"], "Server shutdown disconnect " + player)
    h.start("server"); reconnect_players(h, ["PlayerA", "PlayerB"])
    view = h.terminal("PlayerA", run)
    require("INTERRUPTED" in view["description"] or "CANCELLED" in view["description"], "Uncertain work resumed or falsely succeeded")
    stored = h.container(ARENA["destination"]); h.quiet()
    require(h.container(ARENA["destination"]) == stored, "Interrupted effects automatically replayed")
    require(h.citizens["PlayerA"] not in h.suggestions("PlayerB", "/aivillage kernel name "), "Recovery leaked ownership")
    h.actions_active = False
    result = delivery(h, amount=6, source=ARENA["reuseSource"], destination=ARENA["reuseDestination"])
    return {"interrupted": run, "conservedWheat": stored, "newExplicitWork": result}


def client_loss(h: Harness):
    h.fresh(two=True, known=True); h.release_gate(); h.actions_active = True
    server_pid = h.snapshot()["pid"]
    run = h.submit(amount=6)
    h.wait(lambda: (v := h.run_view("PlayerA", run)) and v["phase"] == "EXECUTING", "Active client-loss boundary")
    h.stop("PlayerA", hard=True)
    h.wait(lambda: "PlayerA" not in h.snapshot()["players"], "Server detected actual client JVM death")
    require(h.snapshot()["pid"] == server_pid and h.client_snapshot("PlayerB")["play"], "Client death terminated other participants")
    require(run not in h.suggestions("PlayerB", "/aivillage kernel status "), "Disconnected owner private run leaked")
    h.start("PlayerA")
    require(h.client_snapshot("PlayerA")["uuid"] == h.players["PlayerA"], "New JVM lost principal")
    final = h.terminal("PlayerA", run)
    require("SUCCEEDED" in final["description"], "Loaded owned work did not complete after client death")
    require(h.menu_count("PlayerA", ARENA["destination"]) == 6, "Reconnect menu missing physical progress")
    h.actions_active = False
    return {"run": run, "serverPidUnchanged": server_pid, "wheat": 6, "clientRestart": True}


def hard_recovery(h: Harness):
    h.fresh(two=True, known=True); h.release_gate()
    h.console(["save-all flush"], lifecycle=True)
    h.wait(lambda: "Saved the game" in h.server_log(), "Baseline vanilla save acknowledged")
    h.actions_active = True; run = h.submit(amount=6)
    boundary = h.wait(lambda: (v if (v := h.run_view("PlayerA", run)) and v.get("receipts")
                              and v["phase"] != "TERMINAL" else None), "Selected real-effect SIGKILL boundary")
    h.stop("server", hard=True)
    for player in ("PlayerA", "PlayerB"):
        h.wait(lambda: not h.action(player, "snapshot")["play"], "Real crash disconnect " + player, 50)
    h.start("server"); reconnect_players(h, ["PlayerA", "PlayerB"])
    view = h.terminal("PlayerA", run)
    require("SUCCEEDED" not in view["description"] and "INTERRUPTED" in view["description"], "Hard-killed uncertain attempt falsely completed")
    stock = h.container(ARENA["destination"])
    h.quiet()
    require(h.container(ARENA["destination"]) == stock and 0 <= stock <= 6, "Hard-kill conservation/replay failure")
    require(h.menu_count("PlayerA", ARENA["destination"]) == stock, "Crash recovery menu diverged")
    h.actions_active = False
    return {"run": run, "receiptCheckpoint": boundary["receipts"], "savedWorldWheat": stock,
            "outcome": view["description"], "atomicWorldModSavePromised": False}


def certify(h: Harness, acquisition, reuse):
    seed = h.seed or ROOT / "build/e2e-certified-seed"
    if seed.exists():
        require((seed / "certificate.json").exists(), "Refusing to overwrite an unrelated seed directory")
        seed.rename(seed.with_name(seed.name + "-previous-" + str(uuid.uuid4())[:8]))
    seed.mkdir(parents=True)
    # All gameplay has terminated. This reset creates the next fixture, not task success.
    restore = []
    for source in (ARENA["source"], ARENA["reuseSource"]):
        for x in range(source[0], source[3]+1):
            for z in range(source[2], source[5]+1):
                restore += [f"setblock {x} 200 {z} minecraft:farmland[moisture=7]", f"setblock {x} 201 {z} minecraft:wheat[age=7]"]
    for destination in (ARENA["destination"], ARENA["reuseDestination"], ARENA["destinationB"]):
        coord = " ".join(map(str,destination)); restore += [f"setblock {coord} minecraft:air", f"setblock {coord} minecraft:chest"]
    for player,x in [("PlayerA",2),("PlayerB",15)]:
        restore += [f"tp {h.entities[player]} {x+.5} 201 2.5", f"setblock {x} 201 4 minecraft:oak_fence_gate[facing=south,open=false]"]
    h.console(restore); h.console(["save-all flush"], lifecycle=True); h.stop("server")
    shutil.copytree(h.root / "server/game/world", seed / "game/world")
    files = [{"path": str(p.relative_to(seed / "game")), "sha256": digest(p)}
             for p in sorted((seed / "game").rglob("*")) if p.is_file() and p.name != "session.lock"]
    metadata = read(h.root / "metadata.json")
    certificate = {"schema":1, "producerCommit": metadata["sourceCommit"], "producerJarSha256": metadata["releaseSha256"],
                   "semanticJarSha256": semantic_jar(), "citizens": h.citizens, "entities": h.entities,
                   "acquisition": acquisition, "offlineChangedBindingReuse": reuse, "files": files,
                   "minecraft":"26.3", "fixture":"F1", "fixtureSetupReset": True}
    atomic(seed / "certificate.json", certificate); h.seed = seed
    return str(seed)


def acquire_real_skill(h: Harness):
    if h.topology != "compose":
        raise InfrastructureBlocked("Actual AI profile requires the owned Docker Ollama service")
    needle_assets = ROOT / "build/needle"
    if not (needle_assets / "manifest.json").exists():
        raise InfrastructureBlocked("Install the pinned actual Needle assets with tools/needle/install.py")
    private_needle = h.root / "needle"
    shutil.copytree(needle_assets, private_needle, dirs_exist_ok=True)
    h.env["COGNITIVECRAFT_OLLAMA_MODEL"] = "qwen3:4b-instruct-2507-q4_K_M"
    h.env["COGNITIVECRAFT_NEEDLE_DIR"] = "/evidence/needle"
    h.compose("--profile", "ai", "up", "-d", "ollama")
    h.compose("exec", "-T", "ollama", "ollama", "pull", h.env["COGNITIVECRAFT_OLLAMA_MODEL"])
    import urllib.request
    def tags():
        try:
            return json.load(urllib.request.urlopen("http://127.0.0.1:11434/api/tags", timeout=5))
        except OSError: return None
    descriptor = h.wait(tags, "Actual pinned local model available", 60)
    require(any(x["name"] == h.env["COGNITIVECRAFT_OLLAMA_MODEL"] for x in descriptor["models"]), "Wrong generation model descriptor")
    h.event("models", {"ollama": descriptor, "needle": read(private_needle / "manifest.json")})
    warm = urllib.request.Request("http://127.0.0.1:11434/api/generate", data=json.dumps({
            "model": h.env["COGNITIVECRAFT_OLLAMA_MODEL"], "stream": False, "keep_alive": "30m"}).encode(),
            headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(warm, timeout=120) as response:
        require(json.load(response)["done"], "Actual model prewarm failed")
    h.event("model-prewarm", {"done": True, "model": h.env["COGNITIVECRAFT_OLLAMA_MODEL"]})
    h.fresh(two=True); h.enroll(); h.enroll("PlayerB", "Mira"); h.release_gate()
    require("catalog=[]" in h.command("PlayerA", "/aivillage kernel catalog", "catalog="), "F2 contains pre-admitted knowledge")
    h.command("PlayerA", "/aivillage kernel inference true", "admission=true")
    acquisition = delivery(h, acquisition=True, language=True)
    require(acquisition["artifact"], "No durably admitted artifact identifier")
    require(h.snapshot()["brokerStats"], "Missing actual IMP-014 broker integration evidence")
    h.compose("stop", "ollama")
    h.env["COGNITIVECRAFT_OLLAMA_MODEL"] = ""; h.env["COGNITIVECRAFT_NEEDLE_DIR"] = ""
    h.stop("server"); h.start("server"); reconnect_players(h, ["PlayerA", "PlayerB"])
    h.command("PlayerA", "/aivillage kernel inference false", "admission=false")
    reuse = delivery(h, amount=6, source=ARENA["reuseSource"], destination=ARENA["reuseDestination"])
    require(reuse["artifact"] == acquisition["artifact"], "Changed bindings did not reuse exact admitted artifact")
    require(h.snapshot()["needleCalls"] == 0 and h.snapshot()["generationCalls"] == 0, "Model-free reuse called AI")
    seed = certify(h, acquisition, reuse)
    return {"acquisition": acquisition, "modelFreeReuse": reuse, "seed": seed, "models": descriptor}


def natural_language(h: Harness):
    if not h.seed:
        raise InfrastructureBlocked("NLU physical acceptance needs the F1 seed produced by genuine AI acceptance")
    assets = h.root / "needle"
    if not assets.exists():
        shutil.copytree(ROOT / "build/needle", assets)
    h.env["COGNITIVECRAFT_NEEDLE_DIR"] = "/evidence/needle" if h.topology == "compose" else str(assets)
    h.env["COGNITIVECRAFT_OLLAMA_MODEL"] = ""
    h.fresh(two=True, known=True)
    h.command("PlayerA", "/aivillage kernel inference true", "admission=true")
    def ask(message, expected):
        answer = h.command("PlayerA", "/aivillage kernel ask " + message, "interpretation=" + UUID_PATTERN)
        ticket = re.search("interpretation=(" + UUID_PATTERN + ")", answer).group(1)
        result = h.wait(lambda: (v if (v := h.run_view("PlayerA", ticket)) and v["phase"] not in
                                ("INTERPRETING", "RESOLVING") else None), "Real Needle interpretation", 40)
        require(result["phase"] in expected, "Unexpected Needle result: " + str(result))
        require(ticket in h.suggestions("PlayerA", "/aivillage kernel status ") and
                ticket not in h.suggestions("PlayerB", "/aivillage kernel status "), "Private interpretation ticket leaked")
        h.command("PlayerB", "/aivillage kernel status " + ticket, "[Aa]uthor|[Pp]rivate|Unknown|unknown|not found")
        return ticket, result
    naming = ask("I name you Ada", {"NAMED"})
    h.release_gate(); h.actions_active = True
    ticket, interpreted = ask("Ada, harvest 4 wheat", {"SUBMITTED"})
    run = interpreted["runId"]; view = h.terminal("PlayerA", run)
    require("SUCCEEDED" in view["description"] and h.container(ARENA["destination"]) == 4, "NLU nearest request did not physically complete")
    require(h.menu_count("PlayerA", ARENA["destination"]) == 4, "NLU client menu convergence failed")
    unsupported = ask("Build me a castle in the Nether.", {"CLARIFICATION", "REJECTED"})
    explicit = ask("Ada, harvest 4 wheat from the castle garden and deliver it to the king's vault.", {"CLARIFICATION", "REJECTED"})
    h.actions_active = False
    (assets / "needle").rename(assets / "needle.unavailable")
    try: outage = ask("Name this villager Ada.", {"UNAVAILABLE", "CLARIFICATION", "REJECTED"})
    finally: (assets / "needle.unavailable").rename(assets / "needle")
    require(h.snapshot()["needleCalls"] >= 4 and h.snapshot()["generationCalls"] == 0, "NLU profile called wrong backend")
    return {"naming": naming, "ticket": ticket, "run": run, "unsupported": unsupported,
            "explicitUnsupportedReference": explicit, "outage": outage, "needleCalls": h.snapshot()["needleCalls"]}


def report(h: Harness, profile: str):
    selected = set(PROFILES[profile])
    results = {r["id"]: r for r in h.results}
    for family in FAMILIES:
        if family not in results:
            results[family] = {"id": family, "status": "NOT_EXECUTED", "reason":
                               "Not reached after earlier failure" if family in selected else "Outside selected profile"}
    atomic(h.root / "results.json", {"schema": 1, "profile": profile, "results": list(results.values())})
    xml = ET.Element("testsuite", name="CognitiveCraft connected-client " + profile,
                     tests=str(len(selected)), failures=str(sum(results[x]["status"] != "PASS" for x in selected)), skipped="0")
    for family in selected:
        result = results[family]
        case = ET.SubElement(xml, "testcase", classname="real.minecraft.multiplayer", name=family,
                             time=str(result.get("duration", 0)))
        if result["status"] != "PASS":
            ET.SubElement(case, "failure", type=result["status"], message=result.get("reason", "Unexecuted mandatory family")).text = result.get("trace", "")
    ET.ElementTree(xml).write(h.root / "junit.xml", encoding="utf-8", xml_declaration=True)
    return all(results[x]["status"] == "PASS" for x in selected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=PROFILES, default="smoke")
    parser.add_argument("--topology", choices=("process", "compose"), default="compose")
    parser.add_argument("--root", type=Path, default=ROOT / "build/e2e/smoke")
    parser.add_argument("--seed", type=Path)
    args = parser.parse_args()
    h = Harness(args.root, args.topology, args.seed)
    registry = {"E2E-CONN-001": smoke, "E2E-CMD-001": commands_and_permissions,
                "E2E-SUGGEST-001": privacy, "E2E-PHYSICAL-001": physical, "E2E-LEGACY-001": legacy,
                "E2E-CANCEL-001": cancellation, "E2E-MUTATION-001": interference,
                "E2E-CLIENT-LOSS-001": client_loss, "E2E-RECOVERY-001": graceful_recovery,
                "E2E-RECOVERY-002": hard_recovery, "E2E-NLU-001": natural_language,
                "E2E-AI-001": acquire_real_skill, "E2E-LEASE-001": queued_competition}
    try:
        if args.topology == "compose":
            h.compose("build", "minecraft")
            h.event("docker-images", subprocess.check_output(["docker", "image", "inspect", "cognitivecraft-e2e:java25"], text=True))
        for family in PROFILES[args.profile]:
            started = time.monotonic()
            try:
                if family not in registry:
                    raise InfrastructureBlocked("Scenario driver not implemented: " + family)
                data = registry[family](h)
                h.results.append({"id": family, "status": "PASS", "duration": time.monotonic() - started, "evidence": data})
                print(family, "PASS", flush=True)
            except Exception as error:
                h.results.append({"id": family, "status": "BLOCKED_BY_TEST_INFRASTRUCTURE" if isinstance(error, InfrastructureBlocked) else "FAIL",
                                  "reason": str(error), "trace": traceback.format_exc(), "duration": time.monotonic() - started})
                print(family, h.results[-1]["status"], str(error), flush=True)
                break
    finally:
        h.finish()
        passed = report(h, args.profile)
    if not passed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
