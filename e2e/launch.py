#!/usr/bin/env python3
"""Launch one independently controlled real Minecraft JVM, with installed binary mods."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time
import uuid

from runtime import ROOT, SDK, java, digest


def install_mods(directory: Path, observer: bool) -> None:
    mods = directory / "mods"
    mods.mkdir(parents=True, exist_ok=True)
    for source in (ROOT / "build/server-sdk/fabric-api.jar",
                   ROOT / "fabric/build/libs/ai-villages-0.1.0.jar",
                   ROOT / ("build/e2e-driver/observer.jar" if observer else "build/e2e-driver/client.jar")):
        shutil.copy2(source, mods / source.name)
    (directory / "config").mkdir(exist_ok=True)
    (directory / "config/ai-villages.json").write_text('{"providers":[]}\n')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("server", "client"))
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--player", default="PlayerA")
    parser.add_argument("--port", type=int, default=25565)
    args = parser.parse_args()
    root = args.root.resolve()
    os.environ["COGNITIVECRAFT_E2E_DIR"] = str(root)
    directory = root / ("server/game" if args.kind == "server" else "clients/" + args.player + "/game")
    directory.mkdir(parents=True, exist_ok=True)
    install_mods(directory, args.kind == "server")
    loader = json.loads((ROOT / "scripts/dependencies.json").read_text())["loader_libraries"]
    common = [ROOT / "build/server-sdk/libs" / item["file"] for item in loader]
    display = None
    if args.kind == "server":
        (directory / "eula.txt").write_text("# Isolated automated test server\neula=true\n")
        properties = directory / "server.properties"
        if not properties.exists():
            properties.write_text("online-mode=false\nenforce-secure-profile=false\nwhite-list=false\nenforce-whitelist=false\nserver-ip=127.0.0.1\n"
                                  f"server-port={args.port}\nspawn-protection=0\nview-distance=4\nsimulation-distance=4\n"
                                  "level-type=minecraft:flat\ngenerate-structures=false\ndifficulty=peaceful\n"
                                  "sync-chunk-writes=true\nmax-players=4\n")
        classpath = [ROOT / "build/server-sdk/minecraft-server.jar"]
        original = (ROOT / "build/server-sdk/runtime-classpath.txt").read_text().split(os.pathsep)
        classpath += [ROOT / "build/server-sdk/libs" / Path(p).name for p in original[1:]]
        command = [java("java"), "-Xms256M", "-Xmx1536M", "-Dcognitivecraft.navigation.trace=true",
                   "-cp", os.pathsep.join(map(str, classpath)),
                   "net.fabricmc.loader.impl.launch.knot.KnotServer", "nogui"]
        box = root / "server"
    else:
        os.environ["COGNITIVECRAFT_E2E_PLAYER"] = args.player
        os.environ.setdefault("LIBGL_ALWAYS_SOFTWARE", "1")
        os.environ.setdefault("SDL_VIDEO_DRIVER", "offscreen")
        os.environ.setdefault("XDG_CACHE_HOME", str(directory / "cache"))
        Path(os.environ["XDG_CACHE_HOME"]).mkdir(parents=True, exist_ok=True)
        if os.environ["SDL_VIDEO_DRIVER"] == "x11" and not os.environ.get("DISPLAY"):
            number = "97" if args.player == "PlayerA" else "98"
            display = subprocess.Popen(["Xvfb", ":" + number, "-ac", "-screen", "0", "800x600x24",
                                        "-nolisten", "unix", "-listen", "tcp", "+extension", "GLX", "+render", "-noreset"])
            os.environ["DISPLAY"] = "127.0.0.1:" + number
        (directory / "options.txt").write_text("renderDistance:3\nsimulationDistance:5\nmaxFps:30\n"
                                                "graphicsMode:0\nenableVsync:false\nfullscreen:false\n"
                                                "soundCategory_master:0.0\nautoJump:false\npauseOnLostFocus:false\n"
                                                "onboardAccessibility:false\n")
        identity = uuid.UUID(bytes=__import__("hashlib").md5(("OfflinePlayer:" + args.player).encode()).digest(), version=3)
        classpath = [SDK / "minecraft-client.jar", *sorted((SDK / "libraries").rglob("*.jar")), *common]
        command = [java("java"), "-Xms256M", "-Xmx1280M", "-Djava.library.path=" + str(SDK / "natives"),
                   "-Dorg.lwjgl.librarypath=" + str(SDK / "natives"), "-cp", os.pathsep.join(map(str, classpath)),
                   "net.fabricmc.loader.impl.launch.knot.KnotClient", "--username", args.player,
                   "--uuid", identity.hex, "--accessToken", "0", "--version", "26.3",
                   "--gameDir", str(directory), "--assetsDir", str(SDK / "assets"), "--assetIndex", "34",
                   "--width", "800", "--height", "600"]
        box = root / "clients" / args.player
    box.mkdir(parents=True, exist_ok=True)
    with (box / "stdout.log").open("a") as output:
        process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE, stdout=output,
                                   stderr=subprocess.STDOUT, text=True)
        (box / "process.json").write_text(json.dumps({"pid": process.pid, "start": time.time(),
                "kind": args.kind, "command": command, "releaseSha256": digest(ROOT / "fabric/build/libs/ai-villages-0.1.0.jar")}))
        stopping = False

        def stop(signum, frame):
            nonlocal stopping
            stopping = True
            if process.poll() is None:
                if args.kind == "server":
                    process.stdin.write("stop\n"); process.stdin.flush()
                else:
                    process.terminate()

        signal.signal(signal.SIGTERM, stop)
        signal.signal(signal.SIGINT, stop)
        previous = ""
        while process.poll() is None:
            control = box / "control.json"
            if args.kind == "server" and control.exists():
                try:
                    data = json.loads(control.read_text())
                    if data["id"] != previous:
                        previous = data["id"]
                        for line in data["commands"]:
                            if "\n" in line or len(line) > 8192:
                                raise ValueError("Invalid console command")
                            process.stdin.write(line + "\n")
                        process.stdin.flush()
                        (box / "control-ack.json").write_text(json.dumps({"id": previous, "sent": True}))
                except (json.JSONDecodeError, FileNotFoundError):
                    pass
            if stopping:
                try:
                    process.wait(timeout=25)
                except subprocess.TimeoutExpired:
                    process.kill()
            time.sleep(0.05)
        (box / "exit.json").write_text(json.dumps({"pid": process.pid, "time": time.time(), "exit": process.returncode}))
        if display is not None:
            display.terminate(); display.wait(timeout=10)
        if process.returncode != 0 and not stopping:
            raise SystemExit(process.returncode)


if __name__ == "__main__":
    main()
