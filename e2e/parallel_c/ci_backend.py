#!/usr/bin/env python3
"""Own one real, pinned model container per Actions attempt; preserve every failed bootstrap."""
import gzip
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request

from common import ROOT, check, digest, read, write
from live_provider import ENV

LOCK = ROOT / "e2e/parallel_c/ci/model-lock.json"
DOCKERFILE = ROOT / "e2e/parallel_c/ci/ModelDockerfile"


def verified_model(path, expected_sha, expected_size=None):
    if not path.is_file() or expected_size is not None and path.stat().st_size != expected_size:
        return False
    with path.open("rb") as handle:
        if handle.read(4) != b"GGUF": return False
    return digest(path) == expected_sha


def props_match(props, server_path, build):
    return isinstance(props, dict) and props.get("model_path") == server_path and props.get("build_info") == build and bool(props.get("chat_template"))


def healthy_existing(values):
    """Missing, stale or unreachable configuration activates the pinned CI backend instead."""
    if not values: return False
    if not verified_model(ROOT / values[ENV[3]], values[ENV[2]]): return False
    try:
        with urllib.request.urlopen(values[ENV[0]].rstrip("/") + "/props", timeout=5) as response:
            props = json.loads(response.read(262144))
        return props_match(props, values[ENV[4]], values[ENV[5]])
    except (OSError, ValueError): return False


class Backend:
    def __init__(self, folder, cache, runtime_image, context):
        self.folder = folder / "backend"; self.folder.mkdir(parents=True, exist_ok=True)
        self.cache = cache; self.runtime_image = runtime_image; self.lock = read(LOCK)
        self.name = f"cc-c-{context['workflow_run']}-{context['attempt']}-model"
        self.started = False
        self.report = {"status": "STARTING", "profile": "PINNED_CI_BACKEND", "started_ns": time.time_ns(),
                       "container_name": self.name, "runtime_image_id": runtime_image, "lock": self.lock,
                       "lock_sha256": digest(LOCK), "dockerfile_sha256": digest(DOCKERFILE), "commands": []}
        self.save()

    def save(self): write(self.folder / "backend.json", self.report)

    def run(self, phase, command, timeout=900):
        log = self.folder / (phase + ".log")
        record = {"phase": phase, "command": command, "started_ns": time.time_ns(), "exit": None}
        print("model backend: " + phase, flush=True)
        with log.open("w") as out:
            try: record["exit"] = subprocess.run(command, cwd=ROOT, stdout=out, stderr=subprocess.STDOUT, timeout=timeout).returncode
            except (OSError, subprocess.TimeoutExpired) as failure: out.write("\n" + str(failure) + "\n")
        record.update(ended_ns=time.time_ns(), log_sha256=digest(log)); self.report["commands"].append(record); self.save()
        check(record["exit"] == 0, "pinned backend phase failed: " + phase)

    def download(self):
        model = self.cache / "models" / self.lock["model_file"]; model.parent.mkdir(parents=True, exist_ok=True)
        if verified_model(model, self.lock["model_sha256"], self.lock["model_size"]):
            self.report["model_cache_hit"] = True
            return model
        self.report["model_cache_hit"] = False
        partial = model.with_suffix(".partial")
        for attempt in range(1, 4):
            try:
                with urllib.request.urlopen(self.lock["model_url"], timeout=120) as source, partial.open("wb") as out:
                    total = 0
                    while block := source.read(1024 * 1024):
                        total += len(block); check(total <= self.lock["model_size"], "model exceeds pinned length"); out.write(block)
                check(verified_model(partial, self.lock["model_sha256"], self.lock["model_size"]), "downloaded GGUF differs from immutable model pin")
                partial.replace(model); return model
            except (OSError, ValueError) as failure:
                self.report.setdefault("download_failures", []).append({"attempt": attempt, "type": type(failure).__name__})
                partial.unlink(missing_ok=True); self.save()
                if attempt == 3: raise ValueError("pinned model download failed after three attempts") from failure
        raise AssertionError("download exhausted")

    def image(self):
        expected = {"runtime_image_id": self.runtime_image, "lock_sha256": digest(LOCK), "dockerfile_sha256": digest(DOCKERFILE)}
        archive = self.cache / "model-image.tar.gz"; meta = self.cache / "model-image.json"
        tag = "cognitivecraft-c-model:pinned"
        reused = False
        try:
            saved = read(meta)
            if saved["expected"] == expected and archive.is_file() and digest(archive) == saved["archive_sha256"]:
                self.run("load-model-image", ["docker", "load", "--input", str(archive)])
                inspected = json.loads(subprocess.check_output(["docker", "image", "inspect", tag], text=True))[0]
                reused = inspected["Id"] == saved["image_id"]
        except (OSError, ValueError, KeyError, subprocess.SubprocessError):
            self.report["model_image_cache_rejected"] = True
        if not reused:
            self.run("build-model-image", ["docker", "build", "--build-arg", "C_RUNTIME=" + self.runtime_image,
                     "--build-arg", "LLAMA_COMMIT=" + self.lock["llama_commit"], "-f", str(DOCKERFILE), "-t", tag, str(DOCKERFILE.parent)], 1200)
            inspected = json.loads(subprocess.check_output(["docker", "image", "inspect", tag], text=True))[0]
            partial = archive.with_suffix(".partial")
            with partial.open("wb") as out:
                saver = subprocess.Popen(["docker", "save", tag], stdout=subprocess.PIPE)
                with gzip.GzipFile(fileobj=out, mode="wb", compresslevel=1) as compressed:
                    while block := saver.stdout.read(1024 * 1024): compressed.write(block)
                check(saver.wait() == 0, "model image cache serialization failed")
            partial.replace(archive)
            write(meta, {"expected": expected, "image_id": inspected["Id"], "archive_sha256": digest(archive)})
        self.report.update(image_id=inspected["Id"], image_cache_hit=reused); self.save()
        return inspected["Id"]

    def start(self):
        model = self.download(); image = self.image()
        args = ["--model", self.lock["server_model_path"], "--alias", self.lock["alias"], "--host", "0.0.0.0", "--port", "8080",
                "--ctx-size", "4096", "--parallel", "2", "--threads", "2", "--threads-batch", "2", "--seed", "20261027",
                "--jinja", "--reasoning", "off", "--chat-template-kwargs", '{"enable_thinking":false}']
        self.report.update(server_args=args, model_sha256=digest(model), model_size=model.stat().st_size); self.save()
        # Only the qualification container shares this container's loopback network; no host port or Docker socket is exposed.
        self.run("start-model", ["docker", "run", "--detach", "--name", self.name, "--init", "--user", f"{os.getuid()}:{os.getgid()}",
                  "--read-only", "--tmpfs", "/tmp:rw,noexec,nosuid,size=128m", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                  "--memory", "2g", "--cpus", "2", "--volume", str(model) + ":" + self.lock["server_model_path"] + ":ro", image, *args], 60)
        self.started = True
        probe = ("import json,urllib.request; "
                 "r=urllib.request.urlopen('http://127.0.0.1:8080/health',timeout=2); "
                 "p=urllib.request.urlopen('http://127.0.0.1:8080/props',timeout=2); print(p.read().decode())")
        deadline = time.monotonic() + 180; readiness = []
        while time.monotonic() < deadline:
            running = subprocess.run(["docker", "inspect", "--format", "{{.State.Running}}", self.name], capture_output=True, text=True, timeout=10)
            if running.returncode != 0 or running.stdout.strip() != "true": break
            result = subprocess.run(["docker", "exec", self.name, "python3", "-c", probe], capture_output=True, text=True, timeout=10)
            readiness.append({"elapsed_ms": round((180 - max(0, deadline - time.monotonic())) * 1000), "exit": result.returncode})
            if result.returncode == 0:
                props = json.loads(result.stdout)
                check(props_match(props, self.lock["server_model_path"], self.lock["build"]), "running server properties differ from build/model pins")
                write(self.folder / "props.json", props)
                binary = subprocess.check_output(["docker", "exec", self.name, "sha256sum", "/opt/llama-build/bin/llama-server"], text=True, timeout=10).split()[0]
                self.report.update(status="READY", readiness=readiness, server_binary_sha256=binary, ready_ns=time.time_ns()); self.save()
                return dict(zip(ENV, ["http://127.0.0.1:8080", self.lock["alias"], self.lock["model_sha256"], model.relative_to(ROOT).as_posix(), self.lock["server_model_path"], self.lock["build"]]))
            time.sleep(2)
        self.report["readiness"] = readiness; self.save()
        raise ValueError("pinned model did not become healthy within 180 seconds")

    def fail(self, failure):
        self.report.update(status="BLOCKED_DEPENDENCY", error=str(failure)); self.save()

    def close(self):
        # Record crashes and OOMs even if readiness failed. Delete only this attempt's explicitly owned name.
        inspected = subprocess.run(["docker", "inspect", self.name], capture_output=True, text=True, timeout=20)
        if inspected.returncode == 0:
            write(self.folder / "container-inspect.json", json.loads(inspected.stdout))
            with (self.folder / "server.log").open("w") as out:
                subprocess.run(["docker", "logs", "--timestamps", self.name], stdout=out, stderr=subprocess.STDOUT, timeout=20)
            subprocess.run(["docker", "rm", "--force", self.name], capture_output=True, timeout=20)
        self.report["ended_ns"] = time.time_ns(); self.save()
