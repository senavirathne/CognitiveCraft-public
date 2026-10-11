#!/usr/bin/env python3
"""Hash-checked official Minecraft/Fabric assets and isolated test-only JARs."""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get("COGNITIVECRAFT_E2E_CACHE", ROOT / "build/e2e-sdk")).resolve()
VERSION_URL = "https://piston-meta.mojang.com/v1/packages/702fe59163c6ee6578607daa85811d9bc9c7cc40/26.3.json"
VERSION_SHA1 = "702fe59163c6ee6578607daa85811d9bc9c7cc40"


def digest(path: Path, algorithm: str = "sha256") -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def acquire(url: str, path: Path, expected: str, algorithm: str = "sha1") -> Path:
    if path.exists() and digest(path, algorithm) == expected:
        return path
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + ".part")
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=90) as response, temp.open("wb") as out:
                shutil.copyfileobj(response, out)
            if digest(temp, algorithm) != expected:
                raise ValueError("Integrity mismatch: " + str(path))
            temp.replace(path)
            return path
        except Exception:
            if attempt == 2:
                raise
    raise AssertionError("unreachable")


def allowed(library: dict) -> bool:
    answer = not library.get("rules")
    for rule in library.get("rules", []):
        platform = rule.get("os", {})
        matches = platform.get("name", "linux") == "linux"
        matches &= platform.get("arch", "x86_64") in ("x86_64", "amd64")
        if matches:
            answer = rule["action"] == "allow"
    return answer


def prepare() -> None:
    version = json.loads(acquire(VERSION_URL, SDK / "version.json", VERSION_SHA1).read_text())
    downloads = []
    client = version["downloads"]["client"]
    downloads.append((client["url"], SDK / "minecraft-client.jar", client["sha1"]))
    for lib in version["libraries"]:
        if not allowed(lib):
            continue
        artifact = lib["downloads"].get("artifact")
        if artifact:
            downloads.append((artifact["url"], SDK / "libraries" / artifact["path"], artifact["sha1"]))
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(lambda args: acquire(*args), downloads))
    index = version["assetIndex"]
    asset_index = acquire(index["url"], SDK / "assets/indexes" / (index["id"] + ".json"), index["sha1"])
    objects = set(obj["hash"] for obj in json.loads(asset_index.read_text())["objects"].values())
    with ThreadPoolExecutor(max_workers=12) as pool:
        list(pool.map(lambda h: acquire("https://resources.download.minecraft.net/" + h[:2] + "/" + h,
                                      SDK / "assets/objects" / h[:2] / h, h), objects))
    (SDK / "natives").mkdir(exist_ok=True)
    for lib in sorted((SDK / "libraries").rglob("*.jar")):
        if "natives-linux" in lib.name:
            with ZipFile(lib) as archive:
                for item in archive.infolist():
                    if ".so" in Path(item.filename).name:
                        (SDK / "natives" / Path(item.filename).name).write_bytes(archive.read(item))
    spec = importlib.util.spec_from_file_location("cc_headless", ROOT / "scripts/headless.py")
    headless = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(headless)
    headless.prepare()
    (SDK / "integrity.json").write_text(json.dumps({
        "minecraft": "26.3", "versionSha1": VERSION_SHA1,
        "clientSha256": digest(SDK / "minecraft-client.jar"),
        "assetIndexSha1": index["sha1"], "assetObjects": len(objects),
        "libraries": [{"path": str(p.relative_to(SDK)), "sha256": digest(p)}
                      for p in sorted((SDK / "libraries").rglob("*.jar"))],
    }, indent=2) + "\n")
    print("Verified Minecraft 26.3 client,", len(objects), "assets and Fabric dependencies", flush=True)


def java(name: str) -> str:
    return str(Path(os.environ["JAVA_HOME"]) / "bin" / name) if os.environ.get("JAVA_HOME") else name


def compile_drivers() -> None:
    release = ROOT / "fabric/build/libs/ai-villages-0.1.0.jar"
    if not release.exists():
        raise RuntimeError("Build the production release JAR before the E2E drivers")
    libs = [SDK / "minecraft-client.jar", release,
            *sorted((SDK / "libraries").rglob("*.jar")),
            *sorted((ROOT / "build/server-sdk/libs").glob("*.jar"))]
    for kind in ("client", "observer"):
        out = ROOT / "build/e2e-driver" / kind
        if out.exists():
            shutil.rmtree(out)
        out.mkdir(parents=True)
        sources = sorted((ROOT / "e2e" / kind / "java").rglob("*.java"))
        args = ["--release", "25", "-encoding", "UTF-8", "-proc:none", "-d", str(out),
                "-cp", os.pathsep.join(map(str, libs)), *map(str, sources)]
        argfile = out.with_suffix(".args")
        argfile.write_text("\n".join('"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"' for s in args))
        subprocess.run([java("javac"), "@" + str(argfile)], check=True)
        shutil.copytree(ROOT / "e2e" / kind / "resources", out, dirs_exist_ok=True)
        subprocess.run([java("jar"), "--create", "--file", str(out.with_suffix(".jar")), "-C", str(out), "."], check=True)
    # Test instrumentation and seed knowledge must never enter the release.
    with ZipFile(release) as archive:
        assert not any("e2e" in x.lower() or "E2E" in x for x in archive.namelist())
    print("Compiled isolated client/observer JARs; release sha256=" + digest(release))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "compile"))
    args = parser.parse_args()
    prepare() if args.action == "prepare" else compile_drivers()
