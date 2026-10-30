"""Provenance for the frozen project's existing Loom/server-SDK build variants."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
from zipfile import ZipFile

from common import ROOT, check, digest, read


def server_sdk_profile(repo, server_jar, libraries):
    repo, server_jar, libraries = Path(repo), Path(server_jar).resolve(), Path(libraries).resolve()
    sdk = server_jar.parent
    check(server_jar.name == "minecraft-server.jar" and libraries == sdk / "libs", "existing server SDK paths required")
    lock_path = repo / "scripts/dependencies.json"
    lock = read(lock_path)
    check((lock["minecraft"], lock["loader"], lock["fabric_api"]) == ("26.3", "0.19.5", "0.161.0+26.3"), "frozen SDK versions required")
    artifacts = {}
    for item in lock["downloads"]:
        p = sdk / item["file"]
        check(p.is_file() and digest(p) == item["sha256"], "server SDK download checksum: " + item["file"])
        artifacts[item["file"]] = digest(p)
    expected_libs = {}
    with ZipFile(sdk / "server26.jar") as archive:
        versions = [row.split("\t") for row in archive.read("META-INF/versions.list").decode().splitlines()]
        matches = [row for row in versions if row[1] == lock["minecraft"]]
        check(len(matches) == 1 and server_jar.is_file() and digest(server_jar) == matches[0][0], "extracted server JAR checksum")
        for row in archive.read("META-INF/libraries.list").decode().splitlines():
            sha, _, path = row.split("\t")
            expected_libs[Path(path).name] = sha
    with ZipFile(sdk / "fabric-api.jar") as archive:
        for item in json.loads(archive.read("fabric.mod.json"))["jars"]:
            expected_libs[Path(item["file"]).name] = hashlib.sha256(archive.read(item["file"])).hexdigest()
    for item in lock["loader_libraries"]:
        expected_libs[item["file"]] = item["sha256"]
    expected_libs["gametest.jar"] = next(item["sha256"] for item in lock["downloads"] if item["file"] == "gametest.jar")
    actual = {p.name: digest(p) for p in libraries.glob("*.jar")}
    check(actual == expected_libs, "prepared server SDK library set/checksums differ from frozen archives")
    artifacts["minecraft-server.jar"] = digest(server_jar)
    artifacts.update({"libs/" + name: sha for name, sha in actual.items()})
    return {"mode": "existing-server-sdk", "sdk_root": str(sdk), "dependencies_lock_sha256": digest(lock_path),
            "artifacts": artifacts, "minecraft": lock["minecraft"], "loader": lock["loader"], "fabric_api": lock["fabric_api"]}


def capture_profile(repo):
    server = os.environ.get("ORG_GRADLE_PROJECT_serverJar")
    libs = os.environ.get("ORG_GRADLE_PROJECT_serverLibraries")
    check(bool(server) == bool(libs), "both existing server SDK project properties are required")
    if server:
        return server_sdk_profile(repo, server, libs)
    return {"mode": "loom", "dependencies_lock_sha256": digest(Path(repo) / "scripts/dependencies.json")}


def verify_profile(profile):
    check(isinstance(profile, dict) and profile.get("mode") in ("loom", "existing-server-sdk"), "missing or unknown build profile")
    check(profile.get("dependencies_lock_sha256") == digest(ROOT / "scripts/dependencies.json"), "build dependency lock changed")
    if profile["mode"] == "existing-server-sdk":
        check(profile.get("sdk_root") and profile.get("artifacts"), "missing server SDK provenance")
        sdk = Path(profile["sdk_root"])
        check(server_sdk_profile(ROOT, sdk / "minecraft-server.jar", sdk / "libs") == profile, "build SDK provenance mismatch")


def matching_profiles(baseline, candidate):
    check(baseline == candidate, "baseline and candidate build profiles/SDK hashes differ")
