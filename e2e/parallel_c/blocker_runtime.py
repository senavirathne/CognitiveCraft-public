"""Locate/hash the existing frozen core Gson dependency for the Java subprocess oracle."""
import hashlib
import os
from pathlib import Path
import re

from common import ROOT, check, digest


def dependency():
    build = ROOT / "core/build.gradle"
    match = re.search(r"api 'com\.google\.code\.gson:gson:([0-9.]+)'", build.read_text())
    check(match is not None, "frozen core Gson declaration unavailable")
    version = match.group(1)
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    jars = list((cache / "caches/modules-2/files-2.1/com.google.code.gson/gson" / version).glob("*/gson-" + version + ".jar"))
    check(len(jars) == 1, "exact existing core Gson artifact required")
    jar = jars[0].resolve()
    check(hashlib.sha1(jar.read_bytes()).hexdigest() == jar.parent.name, "Gradle content-addressed Gson cache mismatch")
    return {"path": str(jar), "sha256": digest(jar), "coordinate": "com.google.code.gson:gson:" + version,
            "frozen_build_sha256": digest(build), "scope": "EXISTING_CORE_DEPENDENCY_ONLY"}
