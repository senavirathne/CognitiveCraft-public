#!/usr/bin/env python3
"""Reproducible server-only build and tests with JDK 25 and Python 3.11+.

Minecraft 26.3 ships unobfuscated classes, so this route needs no remapper.
Downloads stay under build/. No Minecraft or third-party code enters our JAR.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
SDK = ROOT / "build" / "server-sdk"
LOCK = json.loads((Path(__file__).parent / "dependencies.json").read_text())


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def download(item: dict, directory: Path) -> Path:
    destination = directory / item["file"]
    directory.mkdir(parents=True, exist_ok=True)
    if destination.exists() and digest(destination) == item["sha256"]:
        return destination
    temporary = destination.with_suffix(destination.suffix + ".part")
    print("Downloading", item["file"], flush=True)
    with urllib.request.urlopen(item["url"], timeout=90) as response:
        with temporary.open("wb") as output:
            shutil.copyfileobj(response, output)
    if digest(temporary) != item["sha256"]:
        temporary.unlink()
        raise RuntimeError("Dependency checksum mismatch: " + item["file"])
    temporary.replace(destination)
    return destination


def extract_checked(archive: ZipFile, source: str, destination: Path, sha: str) -> None:
    data = archive.read(source)
    if hashlib.sha256(data).hexdigest() != sha:
        raise RuntimeError("Bundled dependency checksum mismatch: " + source)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(data)


def prepare() -> list[Path]:
    jobs = [(item, SDK) for item in LOCK["downloads"]]
    jobs += [(item, SDK / "libs") for item in LOCK["loader_libraries"]]
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(lambda job: download(*job), jobs))
    runtime = [SDK / "minecraft-server.jar"]
    with ZipFile(SDK / "server26.jar") as archive:
        for line in archive.read("META-INF/versions.list").decode().splitlines():
            sha, version, source = line.split("\t")
            if version == LOCK["minecraft"]:
                extract_checked(archive, "META-INF/versions/" + source, runtime[0], sha)
        for line in archive.read("META-INF/libraries.list").decode().splitlines():
            sha, coordinate, source = line.split("\t")
            destination = SDK / "libs" / Path(source).name
            extract_checked(archive, "META-INF/libraries/" + source, destination, sha)
            runtime.append(destination)
    runtime += [SDK / "libs" / item["file"] for item in LOCK["loader_libraries"]]
    with ZipFile(SDK / "fabric-api.jar") as archive:
        for item in json.loads(archive.read("fabric.mod.json"))["jars"]:
            source = item["file"]
            (SDK / "libs" / Path(source).name).write_bytes(archive.read(source))
    shutil.copy2(SDK / "gametest.jar", SDK / "libs" / "gametest.jar")
    (SDK / "runtime-classpath.txt").write_text(os.pathsep.join(map(str, runtime)))
    return runtime


def executable(name: str) -> str:
    suffix = ".exe" if os.name == "nt" else ""
    java_home = os.environ.get("JAVA_HOME")
    result = str(Path(java_home) / "bin" / (name + suffix)) if java_home else shutil.which(name)
    if not result:
        raise RuntimeError("Install JDK 25 and set JAVA_HOME")
    return result


def run(arguments: list[str], cwd: Path = ROOT) -> None:
    subprocess.run(arguments, cwd=cwd, check=True)


def compile_sources(sources: list[Path], output: Path, classpath: list[Path]) -> None:
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    # Argument file handles spaces and large classpaths on Windows as well.
    args = ["--release", "25", "-encoding", "UTF-8", "-d", str(output),
            "-classpath", os.pathsep.join(map(str, classpath))]
    args += [str(source) for source in sources]
    argfile = output.parent / (output.name + "-javac.args")
    argfile.write_text("\n".join('"' + arg.replace("\\", "\\\\").replace('"', '\\"') + '"' for arg in args))
    run([executable("javac"), "@" + str(argfile)])


def copy_resources(source: Path, destination: Path) -> None:
    for item in source.rglob("*"):
        if item.is_file():
            target = destination / item.relative_to(source)
            target.parent.mkdir(parents=True, exist_ok=True)
            if item.name == "fabric.mod.json":
                target.write_text(item.read_text().replace("${version}", "0.1.0"))
            else:
                shutil.copy2(item, target)


def build() -> tuple[list[Path], Path]:
    runtime = prepare()
    classpath = [SDK / "minecraft-server.jar", *sorted((SDK / "libs").glob("*.jar"))]
    classes = ROOT / "build" / "headless" / "classes"
    sources = [source for module in ("core", "providers", "fabric")
               for source in (ROOT / module / "src/main/java").rglob("*.java")]
    compile_sources(sources, classes, classpath)
    copy_resources(ROOT / "fabric/src/main/resources", classes)
    libs = ROOT / "fabric/build/libs"
    libs.mkdir(parents=True, exist_ok=True)
    run([executable("jar"), "--create", "--file", str(libs / "ai-villages-0.1.0.jar"), "-C", str(classes), "."])
    test_classes = ROOT / "build/headless/gametest-classes"
    compile_sources(list((ROOT / "fabric/src/gameTest/java").rglob("*.java")), test_classes, [classes, *classpath])
    copy_resources(ROOT / "fabric/src/gameTest/resources", test_classes)
    run([executable("jar"), "--create", "--file", str(libs / "ai-villages-0.1.0-gametest.jar"), "-C", str(test_classes), "."])
    return runtime, classes


def unit_tests(classes: Path) -> None:
    junit = download(LOCK["junit"], SDK)
    classpath = [classes, junit, *sorted((SDK / "libs").glob("*.jar"))]
    test_classes = ROOT / "build/headless/test-classes"
    sources = [source for module in ("core", "providers")
               for source in (ROOT / module / "src/test/java").rglob("*.java")]
    compile_sources(sources, test_classes, classpath)
    run([executable("java"), "-cp", os.pathsep.join(map(str, [test_classes, *classpath])),
         "org.junit.platform.console.ConsoleLauncher", "execute",
         "--scan-class-path=" + str(test_classes), "--fail-if-no-tests",
         "--reports-dir=" + str(ROOT / "build/test-results/headless"), "--details=summary"])


def game_tests(runtime: list[Path]) -> None:
    # Fresh test world only; never touch a player's save or enable cloud providers.
    directory = ROOT / "build" / "gametest-run"
    if directory.exists():
        shutil.rmtree(directory)
    (directory / "mods").mkdir(parents=True)
    (directory / "server.properties").write_text("# Isolated GameTest world\nonline-mode=false\n")
    report = ROOT / "build/gametest-results.xml"
    report.unlink(missing_ok=True)
    for source in [SDK / "fabric-api.jar", SDK / "gametest.jar",
                   ROOT / "fabric/build/libs/ai-villages-0.1.0.jar",
                   ROOT / "fabric/build/libs/ai-villages-0.1.0-gametest.jar"]:
        shutil.copy2(source, directory / "mods" / source.name)
    run([executable("java"), "-Xmx2G", "-Dfabric-api.gametest",
         "-Dfabric-api.gametest.report-file=" + str(report),
         "-cp", os.pathsep.join(map(str, runtime)),
         "net.fabricmc.loader.impl.launch.knot.KnotServer", "nogui"], directory)
    cases = [case for case in ET.parse(report).getroot().iter("testcase")
             if case.get("name", "").startswith("ai_villages_tests:")]
    if not cases or any(case.find("failure") is not None or case.find("error") is not None for case in cases):
        raise RuntimeError("AI Villages GameTests did not all pass; inspect " + str(report))
    print(f"AI Villages: {len(cases)} Minecraft integration tests passed.", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "build", "test", "gametest"))
    action = parser.parse_args().action
    if action == "prepare":
        prepare()
        return
    runtime, classes = build()
    if action == "test":
        unit_tests(classes)
    if action in ("test", "gametest"):
        game_tests(runtime)


if __name__ == "__main__":
    main()
