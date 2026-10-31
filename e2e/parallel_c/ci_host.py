#!/usr/bin/env python3
"""Run the consolidated C schedule in the existing E2E Docker stack on a GitHub runner."""
import gzip
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.parse

from common import ROOT, check, digest, identity, workflow_context, write


def shared_model_configuration(values):
    """Return only credential-free, explicitly configured existing-backend inputs."""
    if not any(values.values()): return {}, False, None
    try:
        from live_provider import ENV as keys
        check(set(values) == set(keys) and all(isinstance(v, str) and 0 < len(v) <= 512 and not any(ord(c) < 32 for c in v) for v in values.values()), 'model configuration shape')
        uri = urllib.parse.urlsplit(values[keys[0]]); model_file = Path(values[keys[3]])
        check(not uri.username and not uri.password and not uri.query and not uri.fragment and uri.path in ('', '/'), 'credential-free backend URL')
        check(uri.scheme == 'https' or uri.scheme == 'http' and uri.hostname in ('localhost', '127.0.0.1', '::1'), 'configured HTTPS or loopback service')
        check(uri.hostname is not None and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}', values[keys[1]]) and re.fullmatch(r'[0-9a-f]{64}', values[keys[2]]), 'pinned model alias/digest')
        check(not model_file.is_absolute() and '..' not in model_file.parts and (model_file.is_relative_to('build/e2e-sdk/models') or model_file.is_relative_to('build/parallel-c-cache/models')), 'existing model-cache file')
        return dict(values), uri.hostname in ('localhost', '127.0.0.1', '::1'), None
    except (ValueError, TypeError): return {}, False, 'invalid configured backend; no credentials forwarded'


def main():
    context = workflow_context(); check(context["workflow_run"] is not None, "host CI identity required")
    folder = ROOT / "build/e2e/parallel-c"; folder.mkdir(parents=True, exist_ok=True)
    cache = ROOT / "build/parallel-c-cache"; cache.mkdir(parents=True, exist_ok=True)
    expected = {"base_dockerfile": digest(ROOT / "e2e/Dockerfile"), "ci_dockerfile": digest(ROOT / "e2e/parallel_c/ci/Dockerfile"),
                "uid": os.getuid(), "gid": os.getgid()}
    report = {**identity(), **context, "expected": expected, "started_ns": time.time_ns(), "commands": [], "image": None}

    def run(name, command, timeout=900):
        path = folder / ("host-" + name + ".log")
        with path.open("w") as log:
            completed = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
        report["commands"].append({"name": name, "command": command, "exit": completed.returncode, "log_sha256": digest(path)})
        write(folder / "ci-host.json", report)
        check(completed.returncode == 0, "Docker host phase failed: " + name)

    meta, archive = cache / "runtime-image.json", cache / "runtime-image.tar.gz"
    reused = False
    if meta.is_file() and archive.is_file():
        saved = json.loads(meta.read_text())
        if saved.get("expected") == expected and saved.get("archive_sha256") == digest(archive):
            run("load-image", ["docker", "load", "--input", str(archive)])
            found = subprocess.check_output(["docker", "image", "inspect", "cognitivecraft-c-ci:java25"], text=True)
            reused = json.loads(found)[0]["Id"] == saved["image_id"]
    if not reused:
        run("base-image", ["docker", "pull", "eclipse-temurin:25-jdk-noble"])
        base = json.loads(subprocess.check_output(["docker", "image", "inspect", "eclipse-temurin:25-jdk-noble"], text=True))[0]
        report["base_image"] = {"id": base["Id"], "repo_digests": base["RepoDigests"]}
        run("existing-e2e-image", ["docker", "build", "--build-arg", "E2E_UID=" + str(os.getuid()), "--build-arg", "E2E_GID=" + str(os.getgid()),
                                   "-f", "e2e/Dockerfile", "-t", "cognitivecraft-e2e:java25", "e2e"])
        run("ci-prerequisites", ["docker", "build", "--build-arg", "C_RUNTIME=cognitivecraft-e2e:java25", "-t", "cognitivecraft-c-ci:java25", "e2e/parallel_c/ci"])
        # Cache the existing runtime layers, never mutable worlds or another track's containers.
        temporary = archive.with_suffix(".tmp")
        with temporary.open("wb") as out:
            saver = subprocess.Popen(["docker", "save", "cognitivecraft-c-ci:java25", "cognitivecraft-e2e:java25", "eclipse-temurin:25-jdk-noble"], stdout=subprocess.PIPE)
            with gzip.GzipFile(fileobj=out, mode="wb", compresslevel=1) as compressed:
                while data := saver.stdout.read(1024 * 1024): compressed.write(data)
            check(saver.wait() == 0, "runtime layer archive failed")
        temporary.replace(archive)
    image = json.loads(subprocess.check_output(["docker", "image", "inspect", "cognitivecraft-c-ci:java25"], text=True))[0]
    check(re.fullmatch(r"sha256:[0-9a-f]{64}", image["Id"]), "exact inspected image ID required")
    report.update(image=image, reused=reused)
    if not reused:
        write(meta, {"expected": expected, "image_id": image["Id"], "archive_sha256": digest(archive), "base_image": report["base_image"]})
    else: report["base_image"] = saved["base_image"]
    write(folder / "ci-host.json", report)
    docs = ROOT / "build/CognitiveCraft-Documentation-C"; docs.mkdir(parents=True, exist_ok=True)
    name = f"cc-c-{context['workflow_run']}-{context['attempt']}-qualification"
    command = ["docker", "run", "--name", name, "--init", "--user", str(os.getuid()) + ":" + str(os.getgid()),
               "--workdir", "/work/source", "--volume", str(ROOT) + ":/work/source:rw",
               "--volume", str(docs) + ":/work/CognitiveCraft-Documentation-C:ro",
               "--env", "GRADLE_USER_HOME=/work/source/build/parallel-c-cache/gradle",
               "--env", "COGNITIVECRAFT_C_IMAGE_ID=" + image["Id"]]
    for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_SHA", "GITHUB_REPOSITORY", "GITHUB_ACTIONS"):
        command += ["--env", key + "=" + os.environ[key]]
    # Optional existing shared backend only. Do not forward URL credentials or arbitrary host-file paths.
    from live_provider import ENV as model_keys
    model_values = {key: os.environ.get(key, "") for key in model_keys}
    model_values, host_network, model_error = shared_model_configuration(model_values)
    for key, value in model_values.items(): command += ["--env", key + "=" + value]
    if host_network: command += ["--network", "host"]
    if model_error: command += ["--env", "COGNITIVECRAFT_C_MODEL_CONFIG_ERROR=" + model_error]
    command += [image["Id"], "--request", "e2e/parallel_c/qualification-request.json"]
    try:
        with (folder / "container.log").open("w") as log:
            result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=5100)
        report["container_exit"] = result.returncode
        return result.returncode
    finally:
        inspected = subprocess.run(["docker", "inspect", name], capture_output=True, text=True)
        if inspected.returncode == 0: write(folder / "container-inspect.json", json.loads(inspected.stdout))
        report["ended_ns"] = time.time_ns(); write(folder / "ci-host.json", report)
        subprocess.run(["docker", "rm", "--force", name], capture_output=True)


if __name__ == "__main__":
    raise SystemExit(main())
