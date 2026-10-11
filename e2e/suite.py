#!/usr/bin/env python3
"""Real Minecraft client/server acceptance supervisor. No synthetic command players."""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import json
import math
import os
import platform
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
DENIED_PATTERN = r"(?i)unauthor|author|private|unknown|not found|owner required|another principal|unavailable in your scope"


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
                    "E2E_UID": str(os.getuid()), "E2E_GID": str(os.getgid()),
                    "COGNITIVECRAFT_OLLAMA_MODEL": "", "COGNITIVECRAFT_NEEDLE_DIR": ""}
        metadata = {"schema": 1, "repository": "senavirathne/CognitiveCraft-public",
                    "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                    "sourceTree": subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], cwd=ROOT, text=True).strip(),
                    "dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT)),
                    "releaseSha256": digest(ROOT / "fabric/build/libs/ai-villages-0.1.0.jar"),
                    "fixtureInstance": str(uuid.uuid4()), "topology": topology, "port": self.port,
                    "minecraft": "26.3", "loader": "0.19.5", "fabricApi": "0.161.0+26.3",
                    "runtime": read(SDK / "integrity.json"), "started": time.time()}
        metadata["sourceBranch"] = os.environ.get("GITHUB_HEAD_REF") or os.environ.get("GITHUB_REF_NAME") or subprocess.check_output(
                ["git","rev-parse","--abbrev-ref","HEAD"],cwd=ROOT,text=True).strip()
        metadata["ci"] = {key:os.environ[key] for key in ("GITHUB_WORKFLOW","GITHUB_WORKFLOW_REF",
                "GITHUB_RUN_ID","GITHUB_RUN_ATTEMPT","GITHUB_JOB","GITHUB_EVENT_NAME","GITHUB_REF",
                "GITHUB_HEAD_REF","GITHUB_BASE_REF","GITHUB_SHA","RUNNER_OS","RUNNER_ARCH","ImageOS","ImageVersion")
                if key in os.environ}
        metadata["host"] = {"platform":platform.platform(),"python":platform.python_version()}
        metadata["toolchain"] = {"loom":re.search(r"fabric-loom' version '([^']+)'",(ROOT/"build.gradle").read_text()).group(1),
                "gradle":re.search(r"gradle-([0-9.]+)-bin",(ROOT/"gradle/wrapper/gradle-wrapper.properties").read_text()).group(1)}
        metadata["binaries"] = {name:{"path":str(path),"sha256":digest(path),"bytes":path.stat().st_size}
                for name,path in {"production":ROOT/"fabric/build/libs/ai-villages-0.1.0.jar",
                                  "clientDriver":ROOT/"build/e2e-driver/client.jar",
                                  "observer":ROOT/"build/e2e-driver/observer.jar",
                                  "officialClient":SDK/"minecraft-client.jar",
                                  "officialServer":ROOT/"build/server-sdk/minecraft-server.jar"}.items()}
        atomic(self.root / "metadata.json", metadata)
        self.project = "cognitivecraft-e2e-" + metadata["fixtureInstance"][:8]
        self.sampling_stop = threading.Event()
        self.sampler = threading.Thread(target=self.sample_resources, name="e2e-resource-observer", daemon=True)
        self.sampler.start()

    def sample_resources(self):
        while not self.sampling_stop.is_set():
            try:
                data = {"time": time.time(), "hostLoad": os.getloadavg(),
                        "hostMemory": Path("/proc/meminfo").read_text()}
                if self.topology == "compose":
                    identifiers = subprocess.check_output(["docker", "ps", "--filter", "label=com.docker.compose.project=" + self.project,
                                                           "--format", "{{.ID}}"], text=True).split()
                    if identifiers:
                        sampled = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{json .}}", *identifiers],
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
        result = subprocess.run(["docker", "compose", "-p", self.project, "-f", str(ROOT / "e2e/compose.yml"), *args],
                                cwd=ROOT, env=self.env, text=True, capture_output=True)
        with (self.root / "docker.log").open("a") as output:
            output.write(result.stdout + result.stderr)
        if result.returncode:
            raise RuntimeError("Docker Compose " + " ".join(args) + ": " + result.stderr[-2000:])
        return result

    def start(self, component: str):
        started = time.time() * 1000
        previous_controls = {name:digest(self.root/"server"/name) for name in ("control.json","control-ack.json")
                             if component == "server" and (self.root/"server"/name).exists()}
        if component != "server" and component in self.booted:
            self.action(component, "connect", address=f"127.0.0.1:{self.port}")
            state = self.wait(lambda: self.client_snapshot(component), "Fresh multiplayer world " + component, 100)
            require(state["uuid"] == self.players[component], "Reused client principal changed")
            return
        if component != "server":
            for filename in ("request.json", "response.json"):
                mailbox = self.root / "clients" / component / filename
                if mailbox.exists():
                    mailbox.rename(mailbox.with_name(filename + "-previous-" + str(uuid.uuid4())[:8]))
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
        if self.topology == "compose":
            identifier = self.compose("ps","-q",name).stdout.strip()
            info = json.loads(subprocess.check_output(["docker","inspect",identifier],text=True))[0]
            require(info["Config"]["Labels"]["com.docker.compose.project"] == self.project,
                    "Container identity is outside this fixture")
            self.event("container-runtime", {"component":component,"id":info["Id"],"imageId":info["Image"],
                    "imageReference":info["Config"]["Image"],"user":info["Config"]["User"],
                    "command":info["Config"]["Cmd"],"hostPid":info["State"]["Pid"],
                    "startedAt":info["State"]["StartedAt"],"memoryLimit":info["HostConfig"]["Memory"],
                    "networkMode":info["HostConfig"]["NetworkMode"]})
        if component == "server":
            self.wait(lambda: (s if (s := self.snapshot()) and s["time"] >= started
                              and "Done (" in self.server_log() else None), "Dedicated server readiness", 100)
            runtime = self.wait(lambda: (p if (p := read(self.root/"server/process.json")) and p["start"]*1000 >= started
                                        else None), "Current server process evidence")
            retired = {Path(p["path"]).name.split("-previous-")[0]:p["sha256"]
                       for p in runtime["retiredControls"]}
            require(retired == previous_controls and not (self.root/"server/control.json").exists(),
                    "Prior console mailbox was not retired before server restart")
            self.event("console-mailbox-retired", {"expected":previous_controls,"retired":runtime["retiredControls"]})
        else:
            self.wait(lambda: self.events(component) and any(x["kind"] == "driver" and x["time"] >= started
                                                            for x in self.events(component)),
                      "Client driver startup " + component, 120)
            self.wait(lambda: (s if (s := self.action(component, "snapshot"))["gameLoaded"]
                              and s["screen"] == "TitleScreen" else None), "Client resources/title ready", 100)
            self.action(component, "connect", address=f"127.0.0.1:{self.port}")
            state = self.wait(lambda: self.client_snapshot(component), "Real multiplayer play " + component, 100)
            require(not state["singleplayer"], "Integrated server cannot qualify")
            if component in self.players:
                require(state["uuid"] == self.players[component], "Restarted client principal changed")
            self.wait(lambda: self.snapshot()["players"].get(component,{}).get("uuid") == state["uuid"],
                      "Independent server/client login identity " + component)
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

    def model_calls(self):
        state = self.snapshot()
        return {key: state[key] for key in ("needleCalls", "generationCalls")}

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
        foreign_offsets = {other: len(self.events(other)) for other in self.players if other != player}
        self.action(player, "command", text=text)
        def feedback():
            return next((e["data"]["text"] for e in self.events(player)[offset:]
                         if e["kind"] == "feedback" and re.search(expected, e["data"]["text"])), None)
        response = self.wait(feedback, f"Feedback {player}: {text} expected={expected}", timeout)
        if text.startswith("/aivillage kernel "):
            for other, start in foreign_offsets.items():
                require(not any(e["kind"] == "feedback" and e["data"]["text"] == response
                                for e in self.events(other)[start:]), "Private kernel feedback reached " + other)
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
        if lifecycle:
            require(all(re.fullmatch(r"save-all(?: flush)?|stop|(?:op|deop) Player[AB]", line) for line in lines),
                    "Only normal save, stop and permission lifecycle commands are permitted")
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
        self.wait(lambda: any(c["actor"]["citizenId"] == citizen and c["availability"] == "LOADED"
                  for c in self.snapshot()["players"][player]["citizens"]["citizens"]), "Published loaded citizen availability")
        require(citizen in self.suggestions(player, "/aivillage kernel name ", tab=True), "Tab omitted owned citizen")
        if name is not None:
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
            self.event("certified-fixture-source", {"certificateSha256":digest(self.seed/"certificate.json"),
                    "producerCommit":certificate["producerCommit"],"producerJarSha256":certificate["producerJarSha256"],
                    "semanticJarSha256":certificate["semanticJarSha256"],"artifact":certificate["acquisition"]["artifact"],
                    "verifiedFiles":len(certificate["files"])})
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
        self.model_baseline = self.model_calls()
        self.event("fixture-model-counters", self.model_baseline)
        self.event("fixture-world-scopes", {p:v["scope"] for p,v in self.snapshot()["players"].items()})

    def submit(self, player="PlayerA", amount=4, source=None, destination=None, queued=False, allow_rejection=False):
        source = source or ARENA["source"]; destination = destination or ARENA["destination"]
        prefix = "queue" if queued else "harvest"
        text = f"/aivillage kernel {prefix} {self.citizens[player]} {amount} " + " ".join(map(str, source + destination))
        answer = self.command(player, text, ("job=" if queued else "run=") + UUID_PATTERN)
        if not allow_rejection and "accepted=false reason=BUDGET_EXHAUSTED" in answer:
            # The durable owner admits one asynchronous publication at a time. A rejected
            # submission created no job/effects; retry the actual player command within a bound.
            def admitted():
                nonlocal answer
                answer = self.command(player, text, ("job=" if queued else "run=") + UUID_PATTERN)
                return answer if "accepted=false reason=BUDGET_EXHAUSTED" not in answer else None
            self.wait(admitted, "Durable submission publication became available", 30)
        self.last_submission = answer
        if not allow_rejection: require("accepted=true" in answer, "Physical request rejected: " + answer)
        return re.search(("job=" if queued else "run=") + "(" + UUID_PATTERN + ")", answer).group(1)

    def cancel_job(self, player, job):
        text = "/aivillage kernel job-cancel " + job
        answer = self.command(player, text, "cancellation=")
        if "cancellation=false reason=BUDGET_EXHAUSTED" in answer:
            def acknowledged():
                nonlocal answer
                answer = self.command(player, text, "cancellation=")
                return answer if "cancellation=false reason=BUDGET_EXHAUSTED" not in answer else None
            self.wait(acknowledged, "Completed job publication admitted the real cancellation", 10)
        require("cancellation=true" in answer, "Owner job cancellation rejected: " + answer)
        return answer

    def run_view(self, player, run):
        return (self.snapshot() or {}).get("knownPlayers", {}).get(player, {}).get("runs", {}).get(run)

    def job_view(self, player, job):
        return (self.snapshot() or {}).get("knownPlayers", {}).get(player, {}).get("jobs", {}).get(job)

    @contextlib.contextmanager
    def paused_needle(self):
        """SIGSTOP only the genuine native child of this run, for at most 3.5 s."""
        if self.topology == "compose":
            container = self.compose("ps", "-q", "minecraft").stdout.strip()
            details = json.loads(subprocess.check_output(["docker", "inspect", container], text=True))[0]
            require(details["Config"]["Labels"]["com.docker.compose.project"] == self.project, "Owned server required")
            parent = details["State"]["Pid"]
        else:
            parent = self.processes["server"].pid
        resume = threading.Event(); caught = threading.Event(); result = {}

        def owned(pid):
            for _ in range(8):
                fields = Path(f"/proc/{pid}/stat").read_text().split(") ",1)[1].split()
                pid = int(fields[1])
                if pid == parent: return True
                if pid <= 1: return False
            return False

        def watch():
            limit = time.monotonic() + 8
            while time.monotonic() < limit and not resume.is_set():
                for candidate in Path("/proc").iterdir():
                    if not candidate.name.isdigit(): continue
                    try:
                        executable = (candidate / "cmdline").read_bytes().split(b"\0",1)[0]
                        if not executable.endswith(b"/needle") or not owned(int(candidate.name)): continue
                        pid = int(candidate.name)
                        stamp = (candidate / "stat").read_text().split(") ",1)[1].split()[19]
                        started = time.monotonic(); os.kill(pid, signal.SIGSTOP)
                        result.update({"hostPid":pid,"startTicks":stamp,"started":time.time(),
                                       "runnerSha256":digest(self.root / "needle/needle")})
                        self.event("needle-sigstop",result); caught.set()
                        resume.wait(3.5)
                        try:
                            current = (candidate / "stat").read_text().split(") ",1)[1].split()[19]
                            if current == stamp: os.kill(pid,signal.SIGCONT)
                        except ProcessLookupError: pass
                        except FileNotFoundError: pass
                        result["heldSeconds"] = time.monotonic() - started
                        self.event("needle-resume",result); return
                    except (FileNotFoundError,ProcessLookupError,PermissionError): continue
                resume.wait(.01)
        watcher = threading.Thread(target=watch, name="owned-native-needle-fence", daemon=True)
        watcher.start()
        try:
            yield caught, resume, result
        finally:
            resume.set(); watcher.join(timeout=5)
            require(caught.is_set(), "Actual owned Needle process was not observed and paused")
            require(result.get("heldSeconds",4) < 4, "Native availability fence exceeded four seconds")

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

    def prepare_reuse_actor(self):
        # The preceding completed scenario may leave naturally trampled crops/drops.
        # Construct the second request's input fixture before any new work begins.
        require(self.container(ARENA["reuseDestination"]) == 0, "New request destination already contains wheat")
        self.event("reuse-fixture-before", {"drops": self.snapshot().get("drops", []),
                   "source": ARENA["reuseSource"], "destination": ARENA["reuseDestination"]})
        setup = ["kill @e[type=minecraft:item,x=5,y=199,z=9,dx=4,dy=5,dz=4]"]
        for x in (6,7):
            for z in (10,11,12):
                setup += [f"setblock {x} 200 {z} minecraft:farmland[moisture=7]",
                          f"setblock {x} 201 {z} minecraft:wheat[age=7]"]
        setup += ["tp " + self.entities["PlayerA"] + " 6.5 201 9.5"]
        self.console(setup)
        self.wait(lambda: math.dist(next(a["pos"] for a in self.snapshot()["actors"] if a["uuid"] ==
                         self.entities["PlayerA"]), [6.5,201,9.5]) < .8, "Prepared changed-binding actor position")
        self.wait(lambda: all("minecraft:wheat" in self.snapshot()["blocks"][f"{x},201,{z}"] and
                              "age=7" in self.snapshot()["blocks"][f"{x},201,{z}"] for x in (6,7) for z in (10,11,12))
                  and not any(5 <= d["pos"][0] <= 9 and 9 <= d["pos"][2] <= 13 for d in self.snapshot()["drops"]),
                  "Six mature input crops and no pre-existing loose drops")
        self.event("reuse-fixture-ready", {"matureWheat": 6, "preExistingDrops": 0,
                   "destinationWheat": self.container(ARENA["reuseDestination"])})

    def world_hashes(self):
        world = self.root / "server/game/world"
        return [{"path": str(p.relative_to(world)), "sha256": digest(p)}
                for p in sorted(world.rglob("*")) if p.is_file() and p.name != "session.lock"]

    def save_checkpoint(self):
        before = self.snapshot()["containers"]
        offset = len(self.server_log())
        self.console(["save-all flush"], lifecycle=True)
        self.wait(lambda: "Saved the game" in self.server_log()[offset:], "Fresh vanilla save acknowledged")
        value = {"tick": self.snapshot()["tick"], "containersBeforeSave": before, "files": self.world_hashes()}
        self.event("acknowledged-world-checkpoint", value)
        return value

    def finish(self):
        self.sampling_stop.set(); self.sampler.join(timeout=12)
        for component in list(self.booted):
            with contextlib.suppress(Exception): self.stop(component)
        configuration = [{"path":str(path.relative_to(self.root)),"sha256":digest(path),"bytes":path.stat().st_size}
                for name in ("server.properties","options.txt","ai-villages.json")
                for path in sorted(self.root.rglob(name)) if path.is_file()]
        atomic(self.root/"configuration-manifest.json",{"schema":1,"files":configuration})
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
    restarted = h.profile != "smoke"
    if restarted:
        server_identity = read(h.root / "server/process.json")
        old_client = read(h.root / "clients/PlayerA/process.json")
        h.stop("PlayerA"); h.start("PlayerA")
        require(read(h.root / "server/process.json") == server_identity, "Client restart changed server JVM")
        require(read(h.root / "clients/PlayerA/process.json")["start"] > old_client["start"], "Client JVM was not restarted")
        require(h.client_snapshot("PlayerA")["uuid"] == h.players["PlayerA"], "Restart changed client principal")
        require("Ada" in h.command("PlayerA", "/aivillage kernel citizens", "citizens="), "Client restart lost identity")
        require(len(h.snapshot()["players"]["PlayerA"]["citizens"]["citizens"]) == 1, "Client restart duplicated citizen")
    h.disconnect("PlayerA")
    return {"citizen": citizen, "entity": h.entities["PlayerA"], "players": h.players,
            "clientJvmRestart": restarted, "modelCalls": 0}


def semantic_jar():
    from zipfile import ZipFile
    with ZipFile(ROOT / "fabric/build/libs/ai-villages-0.1.0.jar") as archive:
        checksum = hashlib.sha256()
        for name in sorted(archive.namelist()):
            if not name.endswith("/"):
                checksum.update(name.encode()); checksum.update(b"\0"); checksum.update(archive.read(name))
        return checksum.hexdigest()


def commands_and_permissions(h: Harness):
    h.fresh(two=True); citizen = h.enroll(); h.enroll("PlayerB", "Mira")
    expected = {"enroll", "harvest", "queue", "job-status", "job-cancel", "status", "cancel",
                "inference", "catalog", "name", "citizens", "ask"}
    require(expected <= h.client_snapshot("PlayerA")["tree"]["kernel"].keys(), "Incomplete synchronized operator tree")
    h.console(["deop PlayerA"], lifecycle=True)
    normal = h.wait(lambda: (s if "enroll" not in (s := h.client_snapshot("PlayerA"))["tree"]["kernel"] else None), "Live deop command tree refresh")
    require("inference" not in normal["tree"]["kernel"], "Operator inference command remains after deop")
    require(expected <= h.client_snapshot("PlayerB")["tree"]["kernel"].keys(), "A's deop changed B's command permissions")
    require(expected - {"enroll", "inference"} <= normal["tree"]["kernel"].keys(), "Owned commands disappeared after deop")
    h.command("PlayerA", f"/aivillage kernel name {citizen} Áda 森", "name=Áda 森")
    h.command("PlayerA", f"/aivillage kernel name {citizen} Áda 🌾", "Name: REQUEST_INVALID")
    invalid = f"/aivillage kernel harvest {citizen} 0 " + " ".join(map(str, ARENA["source"] + ARENA["destination"]))
    h.command("PlayerA", invalid, "accepted=false reason=REQUEST_INVALID")
    for source in ([7,201,8,6,201,6], [-1,201,-1,20,201,14]):
        h.command("PlayerA", f"/aivillage kernel harvest {citizen} 4 " +
                  " ".join(map(str,source + ARENA["destination"])), "accepted=false reason=REQUEST_INVALID")
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
        h.command(player, f"/aivillage kernel harvest {foreign} 4 " + " ".join(map(str,ARENA["source"] + ARENA["destination"])),
                  "another principal|[Aa]uthor|[Uu]nauthor")
        run = h.submit(player)
        # This F0 intentionally lacks knowledge. It proves private wire/control, not physical success.
        view = h.terminal(player, run)
        require(view.get("outcome", {}).get("status") != "SUCCEEDED", "Fresh model-free world fabricated knowledge")
        job = h.submit(player, queued=True)
        evidence.append({"player": player, "run": run, "job": job})
    for row in evidence:
        owner = row["player"]; foreign = "PlayerB" if owner == "PlayerA" else "PlayerA"
        require(row["run"] in h.suggestions(owner, "/aivillage kernel status "), "Owned retained run missing")
        require(row["job"] in h.suggestions(owner, "/aivillage kernel job-status "), "Owned queued job missing")
        require(row["run"] not in h.suggestions(foreign, "/aivillage kernel status "), "Foreign run suggested")
        require(row["job"] not in h.suggestions(foreign, "/aivillage kernel job-status "), "Foreign job suggested")
        h.command(foreign, "/aivillage kernel status " + row["run"], DENIED_PATTERN)
        h.command(foreign, "/aivillage kernel cancel " + row["run"], DENIED_PATTERN)
        h.command(foreign, "/aivillage kernel job-status " + row["job"], DENIED_PATTERN)
        h.command(foreign, "/aivillage kernel job-cancel " + row["job"], DENIED_PATTERN)
        h.cancel_job(owner, row["job"])
        h.wait(lambda: h.job_view(owner,row["job"])["state"] == "CANCELLED", "Owner queue cancellation published")
        require(row["job"] not in h.suggestions(owner, "/aivillage kernel job-cancel "),
                "Cancelled queued job remained controllable in suggestions")
    h.disconnect("PlayerB"); h.action("PlayerB", "connect", address=f"127.0.0.1:{h.port}")
    h.wait(lambda: h.client_snapshot("PlayerB"), "Fresh privacy reconnect")
    require(a not in h.suggestions("PlayerB", "/aivillage kernel name "), "Stale foreign suggestion after reconnect")
    h.quiet(20)
    for row in evidence:
        foreign = "PlayerB" if row["player"] == "PlayerA" else "PlayerA"
        require(not any(e["kind"] == "feedback" and any(identifier in e["data"]["text"]
                        for identifier in (row["run"],row["job"],h.citizens[row["player"]]))
                        for e in h.events(foreign)), "Foreign client received private kernel record feedback")
    return {"records": evidence, "operatorA": True, "operatorB": True, "generationCalls": h.snapshot()["generationCalls"]}


def delivery(h: Harness, player="PlayerA", amount=4, source=None, destination=None, acquisition=False, language=False):
    started = time.time() * 1000
    source = source or ARENA["source"]; destination = destination or ARENA["destination"]
    before = h.container(destination)
    actor_before = next(x["pos"] for x in h.snapshot()["actors"] if x["uuid"] == h.entities[player])
    calls_before = h.snapshot()["generationCalls"]
    needle_before = h.snapshot()["needleCalls"]
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
    relevant = [e for e in h.events(player) if e["time"] >= started]
    wheat = {e["data"]["networkId"]: e["data"] for e in relevant if e["kind"] == "item-state" and
             e["data"]["item"] == "minecraft:wheat" and source[0]-1 <= e["data"]["pos"][0] <= source[3]+2 and
             source[2]-1 <= e["data"]["pos"][2] <= source[5]+2}
    removed = {item for e in relevant if e["kind"] == "entity-remove" for item in e["data"]["networkIds"]}
    require(sum(item["count"] for item in wheat.values()) >= amount and wheat.keys() <= removed,
            "Missing synchronized task wheat drop creation/removal")
    require(h.menu_count(player, destination) == observed, "Fresh reopened menu changed terminal quantity")
    calls_after = current["generationCalls"]
    require(calls_after > calls_before if acquisition else calls_after == calls_before, "Wrong generation dependence")
    if not language:
        require(current["needleCalls"] == needle_before, "Fully bound structured execution called Needle")
    h.actions_active = False
    return {"run": run, "ticket": ticket, "artifact": view.get("marker", {}).get("artifactSha256"),
            "quantities": quantities, "clientWheat": observed, "observedDrops": list(wheat.values()),
            "generationCalls": calls_after - calls_before}


def physical(h: Harness):
    h.fresh(known=True); h.release_gate()
    result = delivery(h)
    h.disconnect("PlayerA"); h.action("PlayerA", "connect", address=f"127.0.0.1:{h.port}")
    h.wait(lambda: h.client_snapshot("PlayerA"), "Post-delivery fresh client synchronization")
    require(h.menu_count("PlayerA", ARENA["destination"]) == 4, "Delivery lost on real reconnect")
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
        view = None
        if "accepted=false" in h.last_submission:
            description = h.last_submission
        else:
            view = h.terminal("PlayerA", run); description = view["description"]
        actual = h.container(ARENA["destination"])
        require("SUCCEEDED" not in description, "Negative request falsely succeeded: " + variant + " " + description)
        if variant == "shortage":
            require(actual < amount and actual == (h.receipts(view,"DEPOSIT") if view else 0) and actual <= 6,
                    "Shortage partial progress exceeded physical stock or qualified receipts")
            require(h.menu_count("PlayerA",ARENA["destination"]) == actual,
                    "Shortage partial progress differs from real client contents")
        else:
            require(actual == 0, "Blocked request delivered unexpected stock: " + variant)
        require(h.model_calls() == h.model_baseline, "Known environmental blocker invited inference")
        negatives.append({"variant": variant, "outcome": description, "qualifiedPartialWheat": actual}); h.actions_active = False
    return {"delivery": result, "negativeVariants": negatives}


def legacy(h: Harness):
    h.fresh(two=True, legacy=True)
    mature = [f"setblock {x} 200 {z} minecraft:farmland[moisture=7]" for x in range(4,8) for z in range(6,13)]
    mature += [f"setblock {x} 201 {z} minecraft:wheat[age=7]" for x in range(4,8) for z in range(6,13)]
    h.console(mature)
    h.command("PlayerA", "/aivillage enroll Baker", "Enrolled Baker")
    entity = h.wait(lambda: next((e for e in h.client_snapshot("PlayerA")["entities"] if e["name"] == "Baker"), None),
                    "Legacy custom name synchronized")
    initial_position = entity["pos"]
    h.command("PlayerA", "/aivillage home Baker 9 201 7", "Work area set")
    for node in ("food", "status", "home"):
        require("Baker" in h.suggestions("PlayerA", "/aivillage " + node + " "), "Legacy name suggestion missing")
    h.console(["deop PlayerA", "deop PlayerB"], lifecycle=True)
    h.wait(lambda: "enroll" not in h.client_snapshot("PlayerB")["tree"], "Legacy observer is not an operator")
    require("Baker" not in h.suggestions("PlayerB", "/aivillage status "), "Legacy nonowner enumeration")
    h.command("PlayerB", "/aivillage status Baker", "Only the owner or an operator")
    h.release_gate(); h.actions_active = True
    h.command("PlayerA", "/aivillage food Baker", "food: Baker")
    h.wait(lambda: h.container(ARENA["destination"], "minecraft:bread") >= 8, "Real legacy harvest/craft/store bread", 120)
    amount = h.menu_count("PlayerA", ARENA["destination"], "minecraft:bread")
    require(amount >= 8, "Legacy bread not synchronized in client menu")
    h.command("PlayerA", "/aivillage status Baker", "Baker")
    h.command("PlayerA", "/aivillage stop Baker", "stop: Baker")
    actor = next(a for a in h.snapshot()["actors"] if a["uuid"] == entity["uuid"])
    require(math.dist(initial_position, actor["pos"]) > .5, "Legacy worker did not physically move")
    require(any("age=0" in state for key,state in h.snapshot()["blocks"].items()
                if key.startswith(("4,201,", "5,201,", "6,201,", "7,201,"))), "Legacy crops were not replanted")
    require(math.dist(h.client_snapshot("PlayerB")["pos"],actor["pos"]) < 32, "Nearby speech fixture is outside radius")
    offset = len(h.events("PlayerB")); tick = h.snapshot()["tick"]
    reply = h.command("PlayerA", "/aivillage say Baker Hello nearby observer.", r"\[Baker\] I'm available")
    h.wait(lambda: any(e["kind"] == "feedback" and e["data"]["text"] == reply for e in h.events("PlayerB")[offset:]),
           "Nearby nonowner received public villager speech")
    h.walk("PlayerB", [60,201,6], reach=1)
    actor = next(a for a in h.snapshot()["actors"] if a["uuid"] == entity["uuid"])
    distance = math.dist(h.client_snapshot("PlayerB")["pos"],actor["pos"])
    require(distance > 32, "Outside speech fixture remained within radius")
    h.wait(lambda: h.snapshot()["tick"] >= tick + 100, "Existing legacy dialogue cooldown", 20)
    offset = len(h.events("PlayerB"))
    h.command("PlayerA", "/aivillage say Baker Hello distant observer.", r"\[Baker\] I'm available")
    h.quiet()
    require(not any(e["kind"] == "feedback" and e["data"]["text"].startswith("[Baker]")
                    for e in h.events("PlayerB")[offset:]), "Outside-radius player received villager speech")
    require(h.model_calls() == h.model_baseline, "Legacy workflow called a kernel model")
    h.actions_active = False
    return {"bread": amount, "entity": entity["uuid"], "nearbySpeech": reply, "outsideSpeechDistance": distance,
            "contract": "legacy physical farming and fixed bread recipe"}


def cancellation(h: Harness):
    variants = []
    for partial in (False, True):
        h.fresh(known=True)
        source = ARENA["source"]
        amount = 6
        if partial:
            source = [5,201,6,7,201,9]; amount = 12
            setup = []
            for x in range(5,8):
                for z in range(6,10):
                    setup += [f"setblock {x} 200 {z} minecraft:farmland[moisture=7]",
                              f"setblock {x} 201 {z} minecraft:wheat[age=7]"]
            h.console(setup)
            h.wait(lambda: sum("minecraft:wheat" in h.snapshot()["blocks"][f"{x},201,{z}"] and
                              "age=7" in h.snapshot()["blocks"][f"{x},201,{z}"]
                              for x in range(5,8) for z in range(6,10)) == 12, "Larger real partial-cancellation stock")
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
    for variant, target in [("crop-break",[6,201,6]), ("destination-fill",ARENA["destination"]),
                            ("destination-break",ARENA["destination"])]:
        h.fresh(two=True, known=True)
        # B's setup position permits a genuine ordinary reach-bounded block break.
        h.console([f"tp PlayerB {target[0]+1.5} 201 {target[2]+1.5}"])
        if variant == "destination-fill":
            h.console(["give PlayerB minecraft:stone 1728"])
            h.action("PlayerB", "open", pos=target)
            menu = h.wait(lambda: (s if (s := h.client_snapshot("PlayerB"))["menu"] > 0 and
                                  sum(slot["count"] for slot in s["slots"] if slot["playerInventory"] and
                                      slot["item"] == "minecraft:stone") == 1728 else None), "Real B menu and setup stock")
        h.release_gate(); h.actions_active = True
        run = h.submit(amount=6)
        h.wait(lambda: (v := h.run_view("PlayerA", run)) and v["phase"] == "EXECUTING", "Concurrent worker execution")
        key = ",".join(map(str, target))
        if variant == "destination-fill":
            for slot in menu["slots"]:
                if slot["playerInventory"] and slot["item"] == "minecraft:stone":
                    h.action("PlayerB", "menu-click", slot=slot["slot"], quick=True)
            h.wait(lambda: h.container(target,"minecraft:stone") == 1728, "Ordinary B menu transfers filled destination")
            h.action("PlayerB", "close-menu")
        else:
            h.action("PlayerB", "break", pos=target)
            h.wait(lambda: "air" in h.snapshot()["blocks"][key], "Real Player B block break")
        final = h.terminal("PlayerA", run)
        require("SUCCEEDED" not in final["description"], "External player action falsely credited to villager")
        require(h.receipts(final, "DEPOSIT") < 6, "External action produced fictitious full delivery")
        variants.append({"variant": variant, "target": target, "run": run, "outcome": final["description"]}); h.actions_active = False
    return {"variants": variants}


def queued_competition(h: Harness):
    h.fresh(two=True, known=True)
    h.console(["setblock 7 201 8 minecraft:air"])
    h.release_gate(); h.release_gate("PlayerB"); h.actions_active = True
    a = h.submit(amount=3, queued=True)
    h.wait(lambda: (j := h.job_view("PlayerA",a)) and j["state"] == "ACTIVE", "Actual overlapping A assignment")
    b = h.submit("PlayerB", amount=3, destination=ARENA["destinationB"], queued=True)
    overlap = h.wait(lambda: (s if (s := h.snapshot())["players"]["PlayerB"]["jobs"].get(b) else None),
                     "Second queued responsibility publication")
    require(overlap["players"]["PlayerA"]["jobs"][a]["state"] == "ACTIVE",
            "Second player submission was starved until the first assignment ended")
    h.event("queued-overlap", {"tick":overlap["tick"],"jobs":{
            p:overlap["players"][p]["jobs"][j] for p,j in (("PlayerA",a),("PlayerB",b))}})
    for player, own, foreign in [("PlayerA",a,b),("PlayerB",b,a)]:
        require(own in h.suggestions(player, "/aivillage kernel job-status ") and foreign not in
                h.suggestions(player, "/aivillage kernel job-status "), "Queued job packet privacy failed")
        h.command(player, "/aivillage kernel job-status " + own, "job=" + own)
    contenders = [("PlayerA",a,ARENA["destination"]),("PlayerB",b,ARENA["destinationB"])]
    def partial_progress():
        for player,job,destination in contenders:
            view = h.job_view(player,job)
            if view and view["state"] == "ACTIVE" and 0 < view["fulfilled"] < 3:
                return player,job,destination,view
    owner,cancelled,destination,boundary = h.wait(partial_progress, "Competing job committed partial cancellation boundary", 130)
    h.cancel_job(owner, cancelled)
    h.wait(lambda: h.job_view(owner,cancelled)["state"] == "CANCELLED", "Queued partial cancellation cessation")
    conserved = h.container(destination)
    other,other_job,_ = next(row for row in contenders if row[0] != owner)
    h.wait(lambda: (j := h.job_view(other,other_job)) and (j["state"] in ("SUCCEEDED","FAILED","INTERRUPTED") or
                    j["state"] == "WAITING" and j.get("reason") in
                    ("RESOURCE_MISSING","TARGET_UNAVAILABLE","STALE_OBSERVATION") or
                    j["state"] == "WAITING" and j.get("reason") == "BUDGET_EXHAUSTED" and
                    j["usage"].get("TRAVEL_BLOCKS",0) == 32 and j["attempts"][-1].get("terminal") and
                    not j["attempts"][-1]["uncertain"]),
                    "Other scoped queued job bounded physical outcome", 130)
    h.quiet(40)
    state = h.snapshot()
    jobs = [state["players"][p]["jobs"][j] for p,j in [("PlayerA",a),("PlayerB",b)]]
    total = h.container(ARENA["destination"]) + h.container(ARENA["destinationB"])
    custody = {"delivered":total,
               "mature":sum("minecraft:wheat" in state["blocks"][f"{x},201,{z}"] and
                            "age=7" in state["blocks"][f"{x},201,{z}"] for x in (6,7) for z in (6,7,8)),
               "held":sum(a["inventory"].get("minecraft:wheat",0) for a in state["actors"]),
               "loose":sum(d["count"] for d in state["drops"] if d["item"] == "minecraft:wheat"),
               "players":sum(player_inventory(h,p,"minecraft:wheat") for p in ("PlayerA","PlayerB"))}
    require(sum(custody.values()) == 5,"Shared physical wheat was lost or duplicated: " + str(custody))
    require(total <= 5 and sum(j["fulfilled"] for j in jobs) <= 5, "Shared five-wheat pool duplicated")
    require(not all(j["fulfilled"] == 3 for j in jobs), "Both three-unit jobs claimed six from five")
    require(h.container(destination) == conserved, "Cancelled job created more effects")
    for player,job in zip(("PlayerA","PlayerB"),jobs):
        for attempt in job["attempts"]:
            require(attempt["worker"]["citizenId"] == h.citizens[player] and
                    attempt["bound"]["context"]["principal"]["id"] == h.players[player], "Dispatcher recruited a foreign worker")
    for player,destination,job in [("PlayerA",ARENA["destination"],jobs[0]),("PlayerB",ARENA["destinationB"],jobs[1])]:
        require(h.menu_count(player,destination) == job["fulfilled"], "Queued client contents disagree with qualified progress")
    for p,j in [("PlayerA",a),("PlayerB",b)]:
        h.cancel_job(p, j)
    h.quiet(); require(h.container(ARENA["destination"]) + h.container(ARENA["destinationB"]) == total, "Effects after queued cancellation")
    h.actions_active = False
    competition = {"jobs": [a,b], "delivered": total,"custody":custody,"state": jobs,"overlapTick":overlap["tick"],
                   "cancelledOwner": owner, "partialCancellation": boundary}
    h.fresh(two=True, known=True); h.release_gate(); h.actions_active = True
    multiple = [h.submit(amount=2,queued=True), h.submit(amount=2,queued=True,
                source=ARENA["reuseSource"],destination=ARENA["reuseDestination"])]
    h.wait(lambda: all((view := h.job_view("PlayerA",j)) and view["state"] == "SUCCEEDED" for j in multiple),
           "Two durable jobs dispatched to eligible owned worker", 160)
    records = [h.job_view("PlayerA",j) for j in multiple]
    require(all(a["worker"]["citizenId"] == h.citizens["PlayerA"] for j in records for a in j["attempts"]), "Multiple-job foreign worker recruitment")
    require(h.menu_count("PlayerA",ARENA["destination"]) == 2 and
            h.menu_count("PlayerA",ARENA["reuseDestination"]) == 2, "Multiple jobs failed real client delivery")
    require(h.model_calls() == h.model_baseline, "Known dispatcher called models")
    h.actions_active = False
    return {"competition": competition, "multipleJobs": records}


def reconnect_players(h: Harness, players):
    for player in players:
        h.action(player, "connect", address=f"127.0.0.1:{h.port}")
        state = h.wait(lambda: h.client_snapshot(player), "Post-restart real play " + player, 100)
        require(state["uuid"] == h.players[player], "Restart changed controlling principal")
        require(h.citizens[player] in h.command(player, "/aivillage kernel citizens", "citizens="), "Restart lost citizen identity")


def player_inventory(h: Harness, player, item):
    return sum(slot["count"] for slot in h.client_snapshot(player)["slots"]
               if slot["playerInventory"] and slot["item"] == item)


def recover_loose_wheat(h: Harness):
    loose = [d for d in h.snapshot()["drops"] if d["item"] == "minecraft:wheat"]
    before = player_inventory(h,"PlayerA","minecraft:wheat")
    for drop in loose:
        h.walk("PlayerA",[math.floor(v) for v in drop["pos"]],reach=.6)
        h.wait(lambda: not any(d["uuid"] == drop["uuid"] for d in h.snapshot()["drops"]),
               "Real player recovered loose interrupted wheat")
    after = before
    if loose:
        after = h.wait(lambda: (count if (count := player_inventory(h,"PlayerA","minecraft:wheat"))
                       == before + sum(d["count"] for d in loose) else None),
                       "Client received conserved interrupted-drop inventory")
    return {"drops":loose,"playerBefore":before,"playerAfter":after}


def remaining_wheat(h: Harness, delivered):
    blocks = h.snapshot()["blocks"]
    mature = sum("minecraft:wheat" in blocks[f"{x},201,{z}"] and "age=7" in blocks[f"{x},201,{z}"]
                 for x in (6,7) for z in (6,7,8))
    remaining = min(6-delivered,mature)
    require(remaining > 0,"Selected restart left no physical remaining work")
    return mature,remaining


def new_recovery_worker(h: Harness, source=None, destination=None):
    """Keep the uncertain worker fenced; enroll a new eligible worker through the real client."""
    original = {"citizen": h.citizens["PlayerA"], "entity": h.entities["PlayerA"]}
    h.actions_active = True
    run = h.submit(amount=1, source=source, destination=destination)
    fenced = h.terminal("PlayerA",run)
    require("BLOCKED reason=ACTOR_UNAVAILABLE" in fenced["description"] and not fenced["receipts"],
            "Unreconciled original worker lost its conservative assignment fence")
    h.command("PlayerA", "/aivillage kernel status " + run, "BLOCKED reason=ACTOR_UNAVAILABLE")
    h.actions_active = False
    before = {a["uuid"] for a in h.snapshot()["actors"]}
    retained = next(a["inventory"] for a in h.snapshot()["actors"] if a["uuid"] == original["entity"])
    # The old task is inactive and its reservation stays uncertain. Preserve its
    # inventory in the separate closed pen before preparing the next explicit task.
    h.console([f"tp {original['entity']} 15.3 201 2.5",
               "setblock 15 201 4 minecraft:oak_fence_gate[facing=south,open=false]"])
    isolated = h.wait(lambda: next((a for a in h.snapshot()["actors"] if a["uuid"] == original["entity"] and
                                   14 < a["pos"][0] < 17 and 1 < a["pos"][2] < 4),None),
                      "Inactive uncertain worker safely isolated for new explicit work")
    require(isolated["inventory"] == retained,"Recovery preparation changed the old worker's physical inventory")
    settled_loose = recover_loose_wheat(h)
    h.console(["setblock 2 201 4 minecraft:oak_fence_gate[facing=south,open=false]",
               "tp PlayerA 2.5 201 5.5 180 0",
               "summon minecraft:villager 2.5 201 2.5 {PersistenceRequired:1b}"])
    spawned = h.wait(lambda: next((a for a in h.snapshot()["actors"] if a["uuid"] not in before), None),
                     "Next explicit request's unclaimed adult fixture")
    h.wait(lambda: math.dist(h.client_snapshot("PlayerA")["pos"],[2.5,201,5.5]) < .5,
           "Recovery enrollment client synchronized")
    citizen = h.enroll(name="RecoveryAda")
    require(h.entities["PlayerA"] == spawned["uuid"] and citizen != original["citizen"],
            "Recovery worker was not independently enrolled")
    require(any(c["actor"]["citizenId"] == original["citizen"] and c["actor"]["entityId"] == original["entity"]
                for c in h.snapshot()["players"]["PlayerA"]["citizens"]["citizens"]),
            "Enrollment changed the original recovered identity")
    h.release_gate()
    return {"original": original, "fencedRequest": run, "fencedOutcome": fenced["description"],
            "newCitizen": citizen, "newEntity": spawned["uuid"],"retainedInventory":retained,
            "settledLooseBeforeEnrollment":settled_loose}


def graceful_recovery(h: Harness):
    h.fresh(two=True, known=True); h.release_gate(); h.actions_active = True
    job = h.submit(amount=6, queued=True)
    checkpoint = h.wait(lambda: (j if (j := h.job_view("PlayerA",job)) and j["state"] == "ACTIVE" and
                                0 < j["fulfilled"] < 6 else None), "Active queued job partial restart boundary", 130)
    h.stop("server")
    for player in ("PlayerA", "PlayerB"):
        h.wait(lambda: not h.action(player, "snapshot")["play"], "Server shutdown disconnect " + player)
    h.start("server"); reconnect_players(h, ["PlayerA", "PlayerB"])
    view = h.wait(lambda: (j if (j := h.job_view("PlayerA",job)) and j["state"] == "INTERRUPTED" else None), "Conservative recovered queued assignment")
    h.command("PlayerA", "/aivillage kernel job-status " + job, "state=INTERRUPTED")
    require(job in h.suggestions("PlayerA", "/aivillage kernel job-status ") and
            job not in h.suggestions("PlayerB", "/aivillage kernel job-status "), "Recovered queued job packet privacy")
    require(view["attempts"][-1]["uncertain"], "Recovery hid assignment uncertainty")
    stored = h.container(ARENA["destination"]); h.quiet()
    require(h.container(ARENA["destination"]) == stored, "Interrupted effects automatically replayed")
    require(h.citizens["PlayerA"] not in h.suggestions("PlayerB", "/aivillage kernel name "), "Recovery leaked ownership")
    require(h.menu_count("PlayerA",ARENA["destination"]) == stored,"Graceful restart menu lost committed progress")
    h.actions_active = False
    loose = recover_loose_wheat(h)
    worker = new_recovery_worker(h)
    mature,remaining = remaining_wheat(h,stored)
    result = delivery(h,amount=remaining)
    require(h.container(ARENA["destination"]) == stored + remaining,"Explicit graceful recovery duplicated delivery")
    return {"interruptedJob": job, "checkpoint": checkpoint, "recovered": view,
            "conservedWheat": stored, "workerFence": worker, "recoveredLooseWheat":loose,
            "remainingMatureStock":mature,"newExplicitRemainingWork": result}


def client_loss(h: Harness):
    h.fresh(two=True, known=True); h.release_gate(); h.actions_active = True
    server_pid = h.snapshot()["pid"]
    run = h.submit(amount=4)
    h.wait(lambda: (v := h.run_view("PlayerA", run)) and v["phase"] == "EXECUTING", "Active client-loss boundary")
    h.stop("PlayerA", hard=True)
    h.wait(lambda: "PlayerA" not in h.snapshot()["players"], "Server detected actual client JVM death")
    require(h.snapshot()["pid"] == server_pid and h.client_snapshot("PlayerB")["play"], "Client death terminated other participants")
    require(run not in h.suggestions("PlayerB", "/aivillage kernel status "), "Disconnected owner private run leaked")
    h.start("PlayerA")
    require(h.client_snapshot("PlayerA")["uuid"] == h.players["PlayerA"], "New JVM lost principal")
    final = h.terminal("PlayerA", run)
    require("SUCCEEDED" in final["description"], "Loaded owned work did not complete after client death")
    require(h.menu_count("PlayerA", ARENA["destination"]) == 4, "Reconnect menu missing physical progress")
    h.actions_active = False
    loaded = {"run": run, "serverPidUnchanged": server_pid, "wheat": 4, "clientRestart": True}
    h.fresh(known=True); h.release_gate(); h.actions_active = True
    server_pid = h.snapshot()["pid"]
    run = h.submit(amount=4)
    h.wait(lambda: (v := h.run_view("PlayerA",run)) and v["phase"] == "EXECUTING", "Single-client active loss boundary")
    h.stop("PlayerA",hard=True)
    h.wait(lambda: not h.snapshot()["players"], "No player keeps the isolated arena loaded")
    unloaded = h.wait(lambda: (s if (s := h.snapshot()) and not s["arenaTicking"] and
                        any(c["actor"]["citizenId"] == h.citizens["PlayerA"] and c["availability"] == "UNLOADED"
                            for c in s["knownPlayers"]["PlayerA"]["citizens"]["citizens"]) else None),
                      "Actual player-driven actor/chunk unload", 50)
    at_unload = h.run_view("PlayerA",run)
    h.quiet(20)
    require(h.snapshot()["pid"] == server_pid and h.run_view("PlayerA",run)["receipts"] == at_unload["receipts"],
            "Unloaded work committed additional effects or killed the server")
    h.start("PlayerA")
    restored = h.terminal("PlayerA",run)
    actual = h.menu_count("PlayerA",ARENA["destination"])
    require(actual == h.receipts(restored,"DEPOSIT") and 0 <= actual <= 4, "Unload/reconnect invented physical delivery")
    require("SUCCEEDED" not in restored["description"] or actual == 4, "Unloaded task falsely succeeded")
    h.actions_active = False
    return {"loaded":loaded,"unloaded":{"run":run,"unloadTick":unloaded["tick"],"atUnload":at_unload,
                                         "restored":restored,"wheat":actual,"serverPidUnchanged":server_pid}}


def hard_recovery(h: Harness):
    h.fresh(two=True, known=True); h.release_gate()
    baseline = h.save_checkpoint()
    h.actions_active = True; run = h.submit(amount=6)
    boundary = h.wait(lambda: (v if (v := h.run_view("PlayerA", run)) and 0 < h.receipts(v,"DEPOSIT") < 6
                              and v["phase"] != "TERMINAL" else None), "Selected partial-deposit SIGKILL boundary")
    checkpoint = h.save_checkpoint()
    require(h.run_view("PlayerA",run)["phase"] != "TERMINAL", "Work completed before selected crash")
    h.stop("server", hard=True)
    stopped_hashes = h.world_hashes()
    for player in ("PlayerA", "PlayerB"):
        h.wait(lambda: not h.action(player, "snapshot")["play"], "Real crash disconnect " + player, 50)
    h.start("server"); reconnect_players(h, ["PlayerA", "PlayerB"])
    view = h.terminal("PlayerA", run)
    require("SUCCEEDED" not in view["description"] and "INTERRUPTED" in view["description"], "Hard-killed uncertain attempt falsely completed")
    stock = h.container(ARENA["destination"])
    require(stock >= checkpoint["containersBeforeSave"].get("9,201,7",{}).get("minecraft:wheat",0),
            "Acknowledged saved deposit was lost after SIGKILL")
    h.quiet()
    require(h.container(ARENA["destination"]) == stock and 0 <= stock <= 6, "Hard-kill conservation/replay failure")
    require(h.menu_count("PlayerA", ARENA["destination"]) == stock, "Crash recovery menu diverged")
    h.actions_active = False
    # Ordinary player pickup reconciles unqualified crash drops without erasing stock.
    loose = recover_loose_wheat(h)
    worker = new_recovery_worker(h)
    mature,remaining = remaining_wheat(h,stock)
    explicit = delivery(h, amount=remaining)
    require(h.container(ARENA["destination"]) == stock + remaining, "Explicit crash recovery duplicated delivery")
    return {"run": run, "receiptCheckpoint": boundary["receipts"], "savedWorldWheat": stock,
            "baseline": baseline, "checkpoint": checkpoint, "killedWorldHashes": stopped_hashes,
            "remainingMatureStock": mature, "workerFence": worker,
            "recoveredLooseWheat":loose,
            "explicitRemainingWork": explicit,
            "outcome": view["description"], "atomicWorldModSavePromised": False}


def certify(h: Harness, acquisition, reuse):
    seed = h.seed or ROOT / "build/e2e-certified-seed"
    if seed.exists():
        require((seed / "certificate.json").exists(), "Refusing to overwrite an unrelated seed directory")
        seed.rename(seed.with_name(seed.name + "-previous-" + str(uuid.uuid4())[:8]))
    seed.mkdir(parents=True)
    # All gameplay has terminated. This reset creates the next fixture, not task success.
    restore = ["kill @e[type=minecraft:item,x=-3,y=199,z=-3,dx=26,dy=8,dz=20]"]
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
    broker = h.snapshot()["brokerStats"]
    require(broker["calls"] > 0 and broker["dispatched"] > 0 and broker["outputBytes"] > 0,
            "Genuine inference did not produce measured IMP-014 broker work")
    acquisition["brokerStats"] = broker
    h.compose("stop", "ollama")
    def unavailable():
        try:
            urllib.request.urlopen("http://127.0.0.1:11434/api/tags", timeout=2).close()
            return False
        except OSError:
            return True
    h.wait(unavailable, "Stopped actual generation endpoint", 20)
    h.env["COGNITIVECRAFT_OLLAMA_MODEL"] = ""; h.env["COGNITIVECRAFT_NEEDLE_DIR"] = ""
    h.stop("server")
    (private_needle / "needle").rename(private_needle / "needle.offline")
    h.event("offline-backends", {"generationEndpointUnavailable": True, "needleRunnerUnavailable": True})
    h.start("server"); reconnect_players(h, ["PlayerA", "PlayerB"])
    h.command("PlayerA", "/aivillage kernel inference false", "admission=false")
    offline_calls = h.model_calls()
    h.event("offline-reuse-model-counters", offline_calls)
    # New request setup: leave the stable, saved citizen beside the distinct field,
    # within the unchanged 256-observation/32-travel allowance. No work has started.
    h.prepare_reuse_actor()
    reuse = delivery(h, amount=6, source=ARENA["reuseSource"], destination=ARENA["reuseDestination"])
    require(reuse["artifact"] == acquisition["artifact"], "Changed bindings did not reuse exact admitted artifact")
    require(h.model_calls() == offline_calls, "Model-free reuse called AI")
    seed = certify(h, acquisition, reuse)
    return {"acquisition": acquisition, "modelFreeReuse": reuse, "seed": seed, "models": descriptor}


def natural_language(h: Harness):
    if not h.seed:
        raise InfrastructureBlocked("NLU physical acceptance needs the F1 seed produced by genuine AI acceptance")
    assets = h.root / "needle"
    shutil.copytree(ROOT / "build/needle", assets, dirs_exist_ok=True)
    h.env["COGNITIVECRAFT_NEEDLE_DIR"] = "/evidence/needle" if h.topology == "compose" else str(assets)
    h.env["COGNITIVECRAFT_OLLAMA_MODEL"] = ""
    h.fresh(two=True, known=True)
    archived_citizen = h.citizens["PlayerA"]; archived_entity = h.entities["PlayerA"]
    h.command("PlayerA", f"/aivillage kernel name {archived_citizen} Archive Ada", "name=Archive Ada")
    h.console([f"tp {archived_entity} 15.3 201 2.5",
               "summon minecraft:villager 2.5 201 2.5 {PersistenceRequired:1b}"])
    h.wait(lambda: len(h.snapshot()["actors"]) == 3, "Isolated unnamed language citizen setup")
    h.enroll(name=None)
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
    require("Ada" in h.command("PlayerA", "/aivillage kernel citizens", "citizens="), "Needle name was not durably published")
    h.release_gate(); h.actions_active = True
    ticket, interpreted = ask("Ada, harvest 4 wheat", {"SUBMITTED"})
    run = interpreted["runId"]; view = h.terminal("PlayerA", run)
    destination = ARENA["reuseDestination"] # nearest to source anchor (6,201,7), deterministic current policy
    require("SUCCEEDED" in view["description"] and h.container(destination) == 4, "NLU nearest request did not physically complete")
    require(all([r["destination"][key] for key in ("x","y","z")] == destination for r in view["receipts"]), "NLU invented a destination")
    require(h.menu_count("PlayerA", destination) == 4, "NLU client menu convergence failed")
    h.actions_active = False
    primary_citizen = h.citizens["PlayerA"]; primary_entity = h.entities["PlayerA"]
    h.console(["summon minecraft:villager 2.5 201 2.5 {PersistenceRequired:1b}"])
    h.walk("PlayerA",ARENA["gateA"])
    duplicate = h.enroll(name="Ada")
    h.citizens["PlayerA"] = primary_citizen; h.entities["PlayerA"] = primary_entity
    ambiguous = ask("Ada, harvest 4 wheat", {"CLARIFICATION"})
    require(set(ambiguous[1]["candidates"]) == {primary_citizen,duplicate}, "Ambiguous naming leaked or invented candidates")
    h.command("PlayerA",f"/aivillage kernel name {duplicate} Mira", "name=Mira")
    unsupported = ask("Build me a castle in the Nether.", {"CLARIFICATION", "REJECTED"})
    explicit = ask("Ada, harvest 4 wheat from the castle garden and deliver it to the king's vault.", {"CLARIFICATION", "REJECTED"})
    pending_message = "Ada, harvest 4 wheat from 6,201,10 through 7,201,12 and deliver it to the container at 9,201,7."
    before = h.container(ARENA["destination"])
    h.actions_active = True
    with h.paused_needle() as (caught,resume,pause):
        answer = h.command("PlayerA", "/aivillage kernel ask " + pending_message, "interpretation=" + UUID_PATTERN)
        cancelled_ticket = re.search("interpretation=(" + UUID_PATTERN + ")", answer).group(1)
        h.wait(lambda: caught.is_set(), "Observed genuine native Needle pause", 4)
        require(cancelled_ticket in h.suggestions("PlayerA", "/aivillage kernel cancel ") and
                cancelled_ticket not in h.suggestions("PlayerB", "/aivillage kernel status "), "Pending private ticket leaked")
        foreign = h.command("PlayerB", "/aivillage kernel ask I name you Mira", "interpretation=" + UUID_PATTERN)
        foreign_ticket = re.search("interpretation=(" + UUID_PATTERN + ")", foreign).group(1)
        require(foreign_ticket in h.suggestions("PlayerB", "/aivillage kernel status "), "B's unavailable private ticket disappeared")
        h.command("PlayerB", "/aivillage kernel cancel " + cancelled_ticket, "[Aa]uthor|[Pp]rivate|Unknown|unknown|not found")
        h.command("PlayerA", "/aivillage kernel cancel " + cancelled_ticket, "phase=CANCELLED")
    h.quiet()
    require(h.run_view("PlayerA",cancelled_ticket)["phase"] == "CANCELLED" and
            not h.run_view("PlayerA",cancelled_ticket).get("runId") and h.container(ARENA["destination"]) == before,
            "Late cancelled interpretation created effects")
    with h.paused_needle() as (caught,resume,removed_pause):
        answer = h.command("PlayerA", "/aivillage kernel ask " + pending_message, "interpretation=" + UUID_PATTERN)
        removed_ticket = re.search("interpretation=(" + UUID_PATTERN + ")", answer).group(1)
        h.wait(lambda: caught.is_set(), "Removed-caller actual native pause", 4)
        h.disconnect("PlayerA")
        pending = h.run_view("PlayerA",removed_ticket)
        require(not resume.is_set() and pending["phase"] == "INTERPRETING" and not pending.get("runId"),
                "Server-side caller removal did not precede the held native interpretation")
        h.event("pending-caller-removed-native-held", {"ticket":removed_ticket,"pending":pending,
                "serverPlayers":list(h.snapshot()["players"]),"nativePaused":True})
        resume.set()
    h.action("PlayerA", "connect", address=f"127.0.0.1:{h.port}")
    h.wait(lambda: h.client_snapshot("PlayerA"), "Pending-language fresh caller connection")
    removed = h.wait(lambda: (v if (v := h.run_view("PlayerA",removed_ticket)) and v["phase"] not in
                        ("INTERPRETING","RESOLVING") else None), "Removed caller response fenced")
    require(not removed.get("runId") and removed.get("reason") == "STALE_OBSERVATION", "Removed caller used a stale world anchor")
    require(h.container(ARENA["destination"]) == before, "Removed caller's late result performed work")
    h.actions_active = False
    (assets / "needle").rename(assets / "needle.unavailable")
    try: outage = ask("Ada, harvest 4 wheat", {"UNAVAILABLE"})
    finally: (assets / "needle.unavailable").rename(assets / "needle")
    require(h.snapshot()["needleCalls"] >= h.model_baseline["needleCalls"] + 4 and
            h.snapshot()["generationCalls"] == h.model_baseline["generationCalls"], "NLU profile called wrong backend")
    h.command("PlayerA", "/aivillage kernel inference false", "admission=false")
    gated = ask("Ada, harvest 4 wheat", {"UNAVAILABLE"})
    return {"naming": naming, "ticket": ticket, "run": run, "unsupported": unsupported,
            "explicitUnsupportedReference": explicit, "ambiguous":ambiguous,"pendingCancelled":cancelled_ticket,
            "cancelPause":pause,"removedCaller":removed,"removedPause":removed_pause,"gateDisabled":gated,
            "outage": outage, "needleCalls": h.snapshot()["needleCalls"]}


def report(h: Harness, profile: str):
    selected = set(PROFILES[profile])
    results = {r["id"]: r for r in h.results}
    for family in FAMILIES:
        if family not in results:
            results[family] = {"id": family, "status": "NOT_EXECUTED", "reason":
                               "Not reached after earlier failure" if family in selected else "Outside selected profile"}
    atomic(h.root / "results.json", {"schema": 1, "profile": profile,"envelope":"metadata.json",
           "configurationManifest":"configuration-manifest.json","trace":"supervisor.jsonl","results":list(results.values())})
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
    h.profile = args.profile
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
            h.event("family-start", {"id": family})
            try:
                if family not in registry:
                    raise InfrastructureBlocked("Scenario driver not implemented: " + family)
                data = registry[family](h)
                h.results.append({"id": family, "status": "PASS", "duration": time.monotonic() - started, "evidence": data})
                print(family, "PASS", flush=True)
            except Exception as error:
                h.results.append({"id": family, "status": "BLOCKED_BY_TEST_INFRASTRUCTURE" if isinstance(error, InfrastructureBlocked) else "FAIL",
                                  "reason": str(error), "trace": traceback.format_exc(), "duration": time.monotonic() - started})
                atomic(h.root / "cases" / family / "failure.json", {"family": family, "reason": str(error),
                       "snapshot": h.snapshot(), "world": str(h.root / "server/game/world"),
                       "clients": {p: read(h.root / "clients" / p / "response.json") for p in h.players}})
                print(family, h.results[-1]["status"], str(error), flush=True)
                if family == "E2E-AI-001":
                    break # Genuine certification is the dependency for later physical fixtures.
            h.event("family-result", h.results[-1])
    finally:
        h.finish()
        passed = report(h, args.profile)
    if not passed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
