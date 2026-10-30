"""Read the pinned Gradle binary store to disambiguate XML display names, without rerunning tests."""
from __future__ import annotations

import os
from pathlib import Path
import subprocess

from common import HERE, ROOT, check, digest, write


def prepare_reader(output):
    output = Path(output); output.mkdir(parents=True, exist_ok=True)
    home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    homes = list((home / "wrapper/dists/gradle-9.7.0-bin").glob("*/gradle-9.7.0"))
    check(len(homes) == 1, "exact installed Gradle 9.7.0 result reader required")
    libraries = sorted((homes[0] / "lib").glob("*.jar")) + sorted((homes[0] / "lib/plugins").glob("*.jar"))
    check(libraries, "missing Gradle result-reader libraries")
    source = HERE / "junit/GradleResults.java"
    classes = output / "classes"; classes.mkdir(exist_ok=True)
    java_home = Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None
    java = str(java_home / "bin/java") if java_home else "java"
    javac = str(java_home / "bin/javac") if java_home else "javac"
    classpath = os.pathsep.join(map(str, libraries))
    command = [javac, "--release", "25", "-encoding", "UTF-8", "-proc:none", "-cp", classpath, "-d", str(classes), str(source)]
    result = subprocess.run(command, capture_output=True, text=True, timeout=60)
    (output / "compiler.log").write_text(result.stdout + result.stderr)
    check(result.returncode == 0, "Gradle identity reader did not compile")
    metadata = {"gradle_version": "9.7.0", "source_sha256": digest(source), "command": command,
                "exit": result.returncode, "libraries": {str(p): digest(p) for p in libraries},
                "classes": {p.relative_to(output).as_posix(): digest(p) for p in classes.rglob("*.class")}}
    write(output / "reader.json", metadata)
    return java, os.pathsep.join([str(classes), classpath])


def export_binary(binary, output, reader):
    output = Path(output); output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w") as out:
        result = subprocess.run([reader[0], "-cp", reader[1], "dev.aivillages.e2e.parallelc.GradleResults", str(binary)],
                                stdout=out, stderr=subprocess.PIPE, text=True, timeout=60)
    output.with_suffix(".log").write_text(result.stderr)
    check(result.returncode == 0, "Gradle binary result export failed")
    return digest(output)
