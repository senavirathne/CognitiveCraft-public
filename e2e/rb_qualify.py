#!/usr/bin/env python3
"""Apply the shared real-client evidence rules to the existing RB owner campaigns."""
import argparse
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from suite import FAMILIES

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = Path(__file__).with_name("rb-coverage.json")
MAX_IMPORTED_BYTES = 2_000_000_000


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return json.loads(path.read_text())


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


class ArtifactRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if urllib.parse.urlparse(req.full_url).netloc != urllib.parse.urlparse(newurl).netloc:
            redirected.remove_header("Authorization")
        return redirected


def request(path, binary=False):
    url = "https://api.github.com/repos/" + os.environ["GITHUB_REPOSITORY"] + path
    req = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "Authorization": "Bearer " + os.environ["GITHUB_TOKEN"],
        "X-GitHub-Api-Version": "2022-11-28"})
    response = urllib.request.build_opener(ArtifactRedirect()).open(req, timeout=60)
    if binary:
        return response
    with response:
        return json.load(response)


def pages(path, key):
    rows = []
    for page in range(1, 21):
        data = request(path + ("&" if "?" in path else "?") + f"per_page=100&page={page}")[key]
        rows.extend(data)
        if len(data) < 100:
            return rows
    raise ValueError("Evidence pagination exceeded its 2,000-row bound")


def select_runs(runs, workflows, head, event):
    selected = {}
    for workflow in workflows:
        choices = [run for run in runs if run.get("head_sha") == head and
                   run.get("event") == event and run.get("path", "").split("/")[-1] == workflow]
        if choices:
            selected[workflow] = max(choices, key=lambda run: run["id"])
    return selected


def latest_jobs(history):
    """A failed-job retry may inherit the other successful job from an earlier attempt."""
    selected = {}
    for job in history:
        previous = selected.get(job["name"])
        key = lambda value: (value.get("run_attempt", 1), value["id"])
        if previous is None or key(job) > key(previous):
            selected[job["name"]] = job
    return list(selected.values())


def evidence_member(path, profile, layer):
    """Import verifier inputs; complete raw worlds/traces remain in the hashed owner archive."""
    if path == PurePosixPath("fabric/build/libs/ai-villages-0.1.0.jar"):
        return True
    if layer == "native":
        return path.is_relative_to(f"build/{profile}-evidence") or any(
            path.is_relative_to(f"{module}/build/test-results/test")
            for module in ("core", "providers", "fabric"))
    folder = PurePosixPath(f"build/e2e/{profile}")
    if not path.is_relative_to(folder):
        return False
    relative = path.relative_to(folder)
    return str(relative) in ("metadata.json", "results.json", "junit.xml") or (
        len(relative.parts) == 4 and relative.parts[0] == "clients" and
        relative.parts[2] == "screenshots" and relative.suffix == ".png")


def unpack(archive, destination, include=None):
    """Treat downloaded ZIP paths as untrusted; bound both count and expanded size."""
    require(not destination.exists() or not any(destination.iterdir()),
            "Artifact extraction requires an empty directory; stale files cannot supply missing evidence")
    with zipfile.ZipFile(archive) as source:
        entries = source.infolist()
        require(len(entries) <= 50000, "Artifact exceeds extraction bounds")
        for entry in entries:
            path = PurePosixPath(entry.filename)
            require(not path.is_absolute() and ".." not in path.parts and "\\" not in entry.filename
                    and not stat.S_ISLNK(entry.external_attr >> 16), "Unsafe artifact path")
        selected = [entry for entry in entries if include is None or include(PurePosixPath(entry.filename))]
        require(sum(e.file_size for e in selected) <= MAX_IMPORTED_BYTES, "Artifact exceeds extraction bounds")
        source.extractall(destination, members=selected)


def download(artifact, destination, include=None):
    require(not artifact.get("expired"), "Required artifact has expired")
    destination.mkdir(parents=True, exist_ok=True)
    archive = destination.with_suffix(".zip")
    with request(f"/actions/artifacts/{artifact['id']}/zip", binary=True) as response, archive.open("wb") as output:
        while chunk := response.read(1024 * 1024):
            output.write(chunk)
            require(output.tell() <= 400_000_000, "Artifact exceeds download bound")
    actual = digest(archive)
    expected = artifact.get("digest")
    require(not expected or expected == "sha256:" + actual, "Artifact archive digest mismatch")
    require(archive.stat().st_size == artifact["size_in_bytes"], "Artifact archive size mismatch")
    unpack(archive, destination, include)
    archive.unlink()
    return {"id": artifact["id"], "name": artifact["name"], "bytes": artifact["size_in_bytes"],
            "sha256": actual, "githubDigest": expected, "directory": str(destination)}


def collect(root, manifest, head, event, seconds=5400):
    workflows = [unit["ownerWorkflow"] for unit in manifest["units"]]
    deadline = time.monotonic() + seconds
    while True:
        runs = pages("/actions/runs?head_sha=" + head + "&event=" + event, "workflow_runs")
        selected = select_runs(runs, workflows, head, event)
        ready = len(selected) == len(workflows) and all(r["status"] == "completed" for r in selected.values())
        if ready or time.monotonic() >= deadline:
            break
        print("Waiting for RB owners:", ", ".join(w for w in workflows if
              w not in selected or selected[w]["status"] != "completed"), flush=True)
        time.sleep(20)
    evidence = {"schema": 1, "headCommit": head, "event": event, "runs": []}
    for unit in manifest["units"]:
        workflow = unit["ownerWorkflow"]
        run = selected.get(workflow)
        row = {"unit": unit["unit"], "workflow": workflow, "status": "NOT_EXECUTED", "artifacts": []}
        evidence["runs"].append(row)
        if run is None:
            row["reason"] = "No exact-head, matching-event owner run"
            continue
        row.update({"id": run["id"], "headCommit": run["head_sha"], "attempt": run["run_attempt"],
                    "url": run["html_url"], "conclusion": run["conclusion"],
                    "status": "PASS" if run["conclusion"] == "success" else
                    "NOT_EXECUTED" if run["status"] != "completed" else "FAIL",
                    "otherExactHeadRuns": [{"id": r["id"], "conclusion": r["conclusion"],
                        "attempt": r["run_attempt"]} for r in runs if r["id"] != run["id"] and
                        r.get("path", "").split("/")[-1] == workflow]})
        history = pages(f"/actions/runs/{run['id']}/jobs?filter=all", "jobs")
        row["jobs"] = latest_jobs(history)
        row["jobHistory"] = history
        artifacts = pages(f"/actions/runs/{run['id']}/artifacts", "artifacts")
        if row["status"] == "PASS":
            try:
                require(len(row["jobs"]) == 2 and all(j["conclusion"] == "success" and j.get("steps")
                        for j in row["jobs"]), "Both native and connected owner jobs must execute")
                for layer, key in (("native", "nativeArtifactPrefix"), ("client", "clientArtifactPrefix")):
                    prefix = unit[key] + f"-{run['id']}-"
                    choices = [a for a in artifacts if a["name"].startswith(prefix) and
                               a["name"][len(prefix):].isdigit() and
                               int(a["name"][len(prefix):]) <= run["run_attempt"]]
                    require(choices, "Missing " + layer + " artifact from this exact-head run")
                    latest = max(int(a["name"][len(prefix):]) for a in choices)
                    choices = [a for a in choices if int(a["name"][len(prefix):]) == latest]
                    require(len(choices) == 1, "Duplicate " + layer + " artifact")
                    retained = download(choices[0], root / "imports" / unit["profile"] / layer,
                                        lambda path: evidence_member(path, unit["profile"], layer))
                    retained["artifactAttempt"] = latest
                    row["artifacts"].append({"layer": layer, **retained})
            except Exception as error:
                row.update(status="BLOCKED_BY_TEST_INFRASTRUCTURE", reason=str(error))
    (root / "rb-owner-evidence.json").write_text(json.dumps(evidence, indent=2) + "\n")
    return evidence


def check_baseline(baseline, head, tree):
    require(baseline.get("status") == "PASS" and baseline.get("consistentExactBinary") is True and
            baseline.get("ownerSourceConsistency") is True, "Shared 13-family campaign did not qualify")
    require(set(baseline.get("families", {})) == set(FAMILIES) and all(r.get("status") == "PASS"
            for r in baseline["families"].values()), "Missing or failed canonical family")
    identities = baseline.get("identities", [])
    require(identities and all(m.get("sourceCommit") == head and m.get("sourceTree") == tree and
            m.get("reviewedHeadCommit") == head and m.get("dirty") is False for m in identities),
            "Shared campaign source/tree differs from reviewed RB source")
    jars = {m["releaseSha256"] for m in identities}
    require(len(jars) == 1, "Shared campaign has mixed production binaries")
    return jars.pop()


def check_screenshots(folder, row, metadata):
    captures = row.get("screenshots", [])
    require(1 <= len(captures) <= 160, "Missing or excessive native screenshots")
    for capture in captures:
        relative = Path(capture.get("path", ""))
        require(not relative.is_absolute() and ".." not in relative.parts, "Unsafe screenshot path")
        data = (folder / relative).read_bytes()
        require(data[:8] == b"\x89PNG\r\n\x1a\n" and len(data) > 1024 and
                int.from_bytes(data[16:20], "big") >= 800 and int.from_bytes(data[20:24], "big") >= 600 and
                hashlib.sha256(data).hexdigest() == capture.get("sha256") and
                capture.get("nativeClientFrame") is True and capture.get("family") == row["id"] and
                capture.get("sourceCommit") == metadata["sourceCommit"] and
                capture.get("releaseSha256") == metadata["releaseSha256"], "Invalid native screenshot envelope")
    return captures


def passing_cases(path):
    cases = list(ET.parse(path).getroot().iter("testcase"))
    require(cases and not any(c.find(tag) is not None for c in cases
                            for tag in ("failure", "error", "skipped")), "Missing/nonpassing cases in " + str(path))
    return cases


def check_unit(root, unit, owner, head, tree, jar):
    require(owner.get("status") == "PASS" and owner.get("headCommit") == head, "Owner is not a passing exact-head run")
    artifacts = {a["layer"]: Path(a["directory"]) for a in owner["artifacts"]}
    native, connected = artifacts["native"], artifacts["client"]
    evidence = native / ("build/" + unit["profile"] + "-evidence")
    folder = connected / ("build/e2e/" + unit["profile"])
    identity = read(evidence / "identity.json")
    metadata = read(folder / "metadata.json")
    require((identity.get("source"), identity.get("tree"), identity.get("jarSha256")) == (head, tree, jar),
            "Native production source/tree/JAR mismatch")
    require((metadata.get("sourceCommit"), metadata.get("sourceTree"), metadata.get("releaseSha256")) == (head, tree, jar)
            and metadata.get("reviewedHeadCommit") == head and metadata.get("dirty") is False,
            "Connected source/tree/JAR mismatch or dirty checkout")
    require(metadata.get("sourceBranch") and all(metadata.get("binaries", {}).get(name, {}).get("sha256") and
            metadata["binaries"][name].get("bytes", 0) > 0 for name in
            ("production", "clientDriver", "observer", "officialClient", "officialServer")), "Missing actual binary identities")
    require(metadata["binaries"]["production"]["sha256"] == jar, "Production binary metadata mismatch")
    for artifact in (native, connected):
        require(digest(artifact / "fabric/build/libs/ai-villages-0.1.0.jar") == jar, "Archived production binary differs")
    document = read(folder / "results.json")
    require(document.get("profile") == unit["profile"], "Wrong resource profile")
    rows = [r for r in document["results"] if r["id"] == unit["family"]]
    require(len(rows) == 1 and rows[0].get("status") == "PASS", "Missing/duplicate/nonpassing resource family")
    captures = check_screenshots(folder, rows[0], metadata)
    passing_cases(folder / "junit.xml")
    # Reuse each accepted owner's detailed physical/AI/privacy/restart checks.
    spec = importlib.util.spec_from_file_location("resource_owner", ROOT / unit["verifier"])
    verifier = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(verifier)
    with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
        verifier.OUT = Path(temporary)
        verifier.ROOT = connected
        verifier.client()
        verifier.ROOT = native
        verifier.junit()
        require(read(verifier.OUT / "junit.json") == read(evidence / "junit.json"), "Changed component report hashes/counts")
    module_counts = {m: sum(len(passing_cases(p)) for p in (native / m / "build/test-results/test").glob("TEST-*.xml"))
                     for m in ("core", "providers", "fabric")}
    require(all(module_counts.values()), "Empty module evidence")
    phases = []
    for phase in unit["nativePhases"]:
        name = phase["phase"]
        proof = read(evidence / (name + ".json"))
        xml, log = evidence / (name + ".xml"), evidence / (name + ".log")
        require(digest(xml) == proof["xmlSha256"] and digest(log) == proof["logSha256"], "Native phase hashes differ")
        cases = [c for c in passing_cases(xml) if c.get("name", "").startswith("ai_villages_tests:")]
        require(len(cases) == 1 and (not phase["className"] or phase["className"] in cases[0].get("name", "")),
                "Missing selected native project case")
        require(not phase["marker"] or phase["marker"] in log.read_text(), "Missing native phase marker")
        phases.append(proof)
    retained = []
    for capture in (captures[0], captures[-1]):
        target = root / "captures" / unit["profile"] / Path(capture["path"]).name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(folder / capture["path"], target)
        retained.append({**capture, "retainedPath": str(target.relative_to(root))})
    return {"unit": unit["unit"], "family": unit["family"], "status": "PASS", "component_result": "PASS",
            "native_result": "PASS", "network_E2E_result": "PASS", "ownerRun": owner["url"],
            "moduleCases": module_counts, "nativePhases": phases, "screenshotsVerified": len(captures),
            "retainedOriginalCaptures": retained, "metadata": metadata,
            "scenarioEvidence": rows[0]["evidence"], "artifacts": owner["artifacts"]}


def qualify(root, manifest, owners, head, tree):
    errors, units = [], []
    jar = None
    try:
        jar = check_baseline(read(root / "baseline/qualification.json"), head, tree)
    except (ValueError, OSError, KeyError, TypeError) as error:
        errors.append("Baseline: " + str(error))
    require(len(manifest["units"]) == 9 and len({u["unit"] for u in manifest["units"]}) == 9,
            "The campaign requires nine unique RB units")
    for unit in manifest["units"]:
        try:
            rows = [r for r in owners.get("runs", []) if r["unit"] == unit["unit"]]
            require(len(rows) == 1 and owners.get("headCommit") == head, "Missing/duplicate/stale owner evidence")
            require(jar is not None, "No qualified shared production binary")
            units.append(check_unit(root, unit, rows[0], head, tree, jar))
        except (Exception, SystemExit) as error:
            units.append({"unit": unit["unit"], "family": unit["family"], "status": "FAIL",
                          "reason": type(error).__name__ + ": " + str(error)})
    passed = not errors and len(units) == 9 and all(u["status"] == "PASS" for u in units)
    output = {"schema": 1, "status": "PASS" if passed else "FAIL", "sourceCommit": head, "sourceTree": tree,
              "releaseSha256": jar, "baselineFamiliesRequired": 13, "resourceUnitsRequired": 9,
              "conditionalLifecycle": manifest["conditionalLifecycle"],
              "baselineErrors": errors, "units": units}
    (root / "rb-series-qualification.json").write_text(json.dumps(output, indent=2) + "\n")
    print("RB series qualification", output["status"], flush=True)
    for unit in units:
        print(unit["unit"], unit["status"], unit.get("reason", ""), flush=True)
    return passed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--head", required=True)
    parser.add_argument("--event", choices=("pull_request", "push"), required=True)
    args = parser.parse_args()
    args.root.mkdir(parents=True, exist_ok=True)
    tree = subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], cwd=ROOT, text=True).strip()
    require(subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip() == args.head,
            "Collector checkout must be the reviewed head")
    manifest = read(MANIFEST)
    owners = collect(args.root, manifest, args.head, args.event)
    raise SystemExit(0 if qualify(args.root, manifest, owners, args.head, tree) else 1)


if __name__ == "__main__":
    main()
