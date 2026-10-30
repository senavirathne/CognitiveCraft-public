#!/usr/bin/env python3
"""Compile C's isolated fixture against only the verified existing 26.3 SDK/release."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from common import HERE, JAR, ROOT, EvidenceError, check, digest, environment, read, write
from build_profile import server_sdk_profile

APIS = [
    {"class": "net.minecraft.client.multiplayer.ClientPacketListener", "symbol": "sendChat(java.lang.String)",
     "baseline_path": "e2e/client/java/dev/aivillages/e2e/ClientDriver.java", "baseline_verified": False},
    {"class": "net.minecraft.client.multiplayer.ClientPacketListener", "symbol": "handleSystemChat(net.minecraft.network.protocol.game.ClientboundSystemChatPacket)",
     "baseline_path": "e2e/client/java/dev/aivillages/e2e/mixin/PacketTraceMixin.java", "baseline_verified": True},
    {"class": "net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents", "symbol": "END_CLIENT_TICK",
     "baseline_path": "e2e/client/java/dev/aivillages/e2e/ClientDriver.java", "baseline_verified": True},
]


def sha1(path):
    return hashlib.sha1(Path(path).read_bytes()).hexdigest()


def client_libraries(version, sdk):
    spec = importlib.util.spec_from_file_location("c_existing_runtime", ROOT / "e2e/runtime.py")
    runtime = importlib.util.module_from_spec(spec); spec.loader.exec_module(runtime)
    libraries = []
    for lib in version["libraries"]:
        item = lib.get("downloads", {}).get("artifact")
        if item and runtime.allowed(lib):
            p = sdk / "libraries" / item["path"]
            check(p.is_file(), "missing required client SDK library: " + item["path"])
            check(sha1(p) == item["sha1"], "SDK library checksum: " + str(p))
            libraries.append(p)
    return libraries


def prepared_classpath(sdk, release):
    artifacts = {}
    check(sha1(sdk / "version.json") == "702fe59163c6ee6578607daa85811d9bc9c7cc40", "exact 26.3 version metadata required")
    version = read(sdk / "version.json")
    check(sha1(sdk / "minecraft-client.jar") == version["downloads"]["client"]["sha1"], "client SDK checksum")
    client = [sdk / "minecraft-client.jar", *client_libraries(version, sdk)]
    server = ROOT / "build/server-sdk"
    server_profile = server_sdk_profile(ROOT, server / "minecraft-server.jar", server / "libs")
    libs = sorted((server / "libs").glob("*.jar"))
    artifacts.update({str(server / path): sha for path, sha in server_profile["artifacts"].items()})
    for p in [sdk / "version.json", *client, *libs, release]: artifacts[str(p)] = digest(p)
    return [*client, release, *libs], artifacts


def compile_fixture(output, release=JAR):
    output, release = Path(output).resolve(), Path(release).resolve(); output.mkdir(parents=True, exist_ok=True)
    env = environment(); sdk = Path(env["sdk"])
    javac = str(Path(os.environ["JAVA_HOME"]) / "bin/javac") if os.environ.get("JAVA_HOME") else "javac"
    javap = str(Path(os.environ["JAVA_HOME"]) / "bin/javap") if os.environ.get("JAVA_HOME") else "javap"
    record = {"exit": None, "jar_sha256": digest(release) if release.is_file() else None,
              "environment": env, "sdk_artifacts": {}, "api_discovery": [], "classes": {}, "command": [], "blocked": []}
    for api in APIS: record["api_discovery"].append({**api, "sdk_verified": False})
    try:
        check(release.is_file(), "missing release: fabric/build/libs/ai-villages-0.1.0.jar")
        libs, record["sdk_artifacts"] = prepared_classpath(sdk, release)
        classes = output / "classes"; classes.mkdir(exist_ok=False)
        cp = os.pathsep.join(map(str, libs))
        for i, api in enumerate(record["api_discovery"]):
            result = subprocess.run([javap, "-p", "-classpath", cp, api["class"]], capture_output=True, text=True, timeout=30)
            (output / f"api-{i}.log").write_text(result.stdout + result.stderr)
            check(result.returncode == 0 and api["symbol"] in result.stdout, "unverified symbol: " + api["class"] + "." + api["symbol"])
            api["sdk_verified"] = True; api["evidence"] = f"api-{i}.log"
        sources = sorted((HERE / "native/java").rglob("*.java"))
        command = [javac, "--release", "25", "-encoding", "UTF-8", "-proc:none", "-d", str(classes), "-cp", cp, *map(str, sources)]
        record["command"] = command
        result = subprocess.run(command, capture_output=True, text=True, timeout=120)
        record["exit"] = result.returncode; (output / "compiler.log").write_text(result.stdout + result.stderr)
        check(result.returncode == 0, "native fixture javac gate failed; see compiler.log")
        record["classes"] = {p.relative_to(output).as_posix(): digest(p) for p in classes.rglob("*.class")}
        check(record["classes"], "native compiler emitted no classes")
        with ZipFile(output / "chat-fixture.jar", "w") as jar:
            for base in (classes, HERE / "native/resources"):
                for path in sorted(base.rglob("*")):
                    if path.is_file(): jar.write(path, path.relative_to(base).as_posix())
        record["fixture_sha256"] = digest(output / "chat-fixture.jar")
    except (OSError, EvidenceError, subprocess.TimeoutExpired) as failure:
        record["blocked"].append(str(failure))
        record["exit"] = record["exit"] if record["exit"] not in (None, 0) else 2
        if not (output / "compiler.log").exists(): (output / "compiler.log").write_text("BLOCKED_DEPENDENCY: " + str(failure) + "\n")
    write(output / "compile.json", record)
    return record


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__); p.add_argument("--jar", type=Path, default=JAR)
    p.add_argument("--root", type=Path, required=True); a = p.parse_args()
    r = compile_fixture(a.root, a.jar); print(__import__("json").dumps({"exit": r["exit"], "blocked": r["blocked"]}))
    raise SystemExit(0 if r["exit"] == 0 else 2)
