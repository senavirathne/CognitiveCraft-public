#!/usr/bin/env python3
"""Real Minecraft client/server acceptance supervisor. No synthetic command players."""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import time
import traceback
import uuid
import xml.etree.ElementTree as ET

from runtime import ROOT, SDK, digest
from fixtures import ARENA, commands as arena_commands, write as write_fixture

FAMILIES = ["E2E-CONN-001", "E2E-CMD-001", "E2E-SUGGEST-001", "E2E-PHYSICAL-001",
            "E2E-LEGACY-001", "E2E-CANCEL-001", "E2E-MUTATION-001", "E2E-CLIENT-LOSS-001",
            "E2E-RECOVERY-001", "E2E-RECOVERY-002", "E2E-NLU-001", "E2E-AI-001", "E2E-LEASE-001"]
PROFILES = {"smoke": FAMILIES[:1], "deterministic": [x for x in FAMILIES if x not in
            ("E2E-CLIENT-LOSS-001", "E2E-RECOVERY-002", "E2E-NLU-001", "E2E-AI-001")],
            "ai": ["E2E-NLU-001", "E2E-AI-001"],
            "resilience": ["E2E-CLIENT-LOSS-001", "E2E-RECOVERY-002"], "all": FAMILIES}
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
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0)); self.port = sock.getsockname()[1]
        self.env = {**os.environ, "E2E_RUN_DIR": str(self.root), "E2E_PORT": str(self.port)}
        metadata = {"schema": 1, "repository": "senavirathne/CognitiveCraft-public",
                    "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                    "sourceTree": subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], cwd=ROOT, text=True).strip(),
                    "dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT)),
                    "releaseSha256": digest(ROOT / "fabric/build/libs/ai-villages-0.1.0.jar"),
                    "fixtureInstance": str(uuid.uuid4()), "topology": topology, "port": self.port,
                    "minecraft": "26.3", "loader": "0.19.5", "fabricApi": "0.161.0+26.3",
                    "runtime": read(SDK / "integrity.json"), "started": time.time()}
        atomic(self.root / "metadata.json", metadata)

    def event(self, kind: str, data: object):
        with (self.root / "supervisor.jsonl").open("a") as out:
            out.write(json.dumps({"time": time.time(), "kind": kind, "data": data}) + "\n")

    def compose(self, *args):
        return subprocess.run(["docker", "compose", "-f", str(ROOT / "e2e/compose.yml"), *args],
                              cwd=ROOT, env=self.env, check=True, text=True, capture_output=True)

    def start(self, component: str):
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
        if component == "server":
            self.wait(lambda: "Done (" in self.server_log() and self.snapshot(), "Dedicated server readiness", 100)
        else:
            self.wait(lambda: self.events(component) and any(x["kind"] == "driver" for x in self.events(component)),
                      "Client driver startup " + component, 120)
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
                           f"Client action {player}: {action}", 20)["result"]
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

    def finish(self):
        for component in list(self.processes):
            with contextlib.suppress(Exception): self.stop(component)
        if self.topology == "compose":
            with contextlib.suppress(Exception): self.compose("down", "--remove-orphans")


def smoke(h: Harness):
    h.start("server"); h.start("PlayerA"); h.setup(two=False)
    citizen = h.enroll()
    state = h.client_snapshot("PlayerA")
    require("kernel" in state["tree"] and "name" in state["tree"]["kernel"], "Received kernel command tree")
    h.command("PlayerA", "/aivillage kernel inference false", "admission=false")
    h.command("PlayerA", "/aivillage kernel catalog", "recentModelCalls=0")
    h.command("PlayerA", f"/aivillage kernel name {citizen} " + "x" * 49, "Name: REQUEST_INVALID")
    h.command("PlayerA", f"/aivillage kernel name {uuid.uuid4()} Unknown", "Name:|not found|Unknown|unknown|Kernel:")
    h.disconnect("PlayerA")
    h.action("PlayerA", "connect", address=f"127.0.0.1:{h.port}")
    require(h.wait(lambda: h.client_snapshot("PlayerA"), "Reconnect")["uuid"] == h.players["PlayerA"], "Reconnect identity changed")
    require("Ada" in h.command("PlayerA", "/aivillage kernel citizens", "citizens="), "Name lost on reconnect")
    h.disconnect("PlayerA")
    return {"citizen": citizen, "entity": h.entities["PlayerA"], "players": h.players, "modelCalls": 0}


def report(h: Harness, profile: str):
    selected = set(PROFILES[profile])
    results = {r["id"]: r for r in h.results}
    for family in FAMILIES:
        if family not in results:
            results[family] = {"id": family, "status": "NOT_EXECUTED", "reason": "Outside selected profile"}
    atomic(h.root / "results.json", {"schema": 1, "profile": profile, "results": list(results.values())})
    xml = ET.Element("testsuite", name="CognitiveCraft connected-client " + profile,
                     tests=str(len(selected)), failures=str(sum(r["status"] != "PASS" for r in h.results)), skipped="0")
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
    registry = {"E2E-CONN-001": smoke}
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
