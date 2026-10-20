#!/usr/bin/env python3
"""Collect independently executed exact-head owner workflow gates, with finite polling."""
import argparse
import json
import os
from pathlib import Path
import time
import urllib.request


def request(path):
    req = urllib.request.Request("https://api.github.com/repos/" + os.environ["GITHUB_REPOSITORY"] + path,
            headers={"Accept":"application/vnd.github+json", "Authorization":"Bearer " + os.environ["GITHUB_TOKEN"],
                     "X-GitHub-Api-Version":"2022-11-28"})
    with urllib.request.urlopen(req,timeout=30) as response:
        return json.load(response)


def collect(root, head, deadline_seconds=2400):
    required = json.loads(Path(__file__).with_name("coverage.json").read_text())["ownerWorkflows"]
    deadline = time.monotonic() + deadline_seconds
    while True:
        runs = request("/actions/runs?head_sha=" + head + "&per_page=100")["workflow_runs"]
        selected = {}
        for workflow in required:
            choices = [run for run in runs if run["path"].split("/")[-1] == workflow]
            if choices:
                selected[workflow] = max(choices,key=lambda run:run["id"])
        ready = len(selected) == len(required) and all(run["status"] == "completed" for run in selected.values())
        if ready or time.monotonic() >= deadline:
            break
        time.sleep(10)
    evidence = []
    for workflow in required:
        run = selected.get(workflow)
        if run is None:
            evidence.append({"workflow":workflow,"status":"NOT_EXECUTED","reason":"No exact-head workflow run"})
            continue
        jobs = request("/actions/runs/" + str(run["id"]) + "/jobs?per_page=100")["jobs"]
        artifacts = request("/actions/runs/" + str(run["id"]) + "/artifacts?per_page=100")["artifacts"]
        row = {"workflow":workflow,"run":run["id"],"url":run["html_url"],"headCommit":run["head_sha"],
               "status":"PASS" if run["status"] == "completed" and run["conclusion"] == "success" else
                        "NOT_EXECUTED" if run["status"] != "completed" else "FAIL",
               "conclusion":run["conclusion"],"attempt":run["run_attempt"],
               "jobs":[{"id":job["id"],"name":job["name"],"conclusion":job["conclusion"],
                        "steps":[{"name":s["name"],"conclusion":s["conclusion"]} for s in job.get("steps",[])]}
                       for job in jobs],
               "artifacts":[{"id":a["id"],"name":a["name"],"sha256":a.get("digest"),"bytes":a["size_in_bytes"]}
                            for a in artifacts],
               "otherExactHeadRuns":[{"id":r["id"],"conclusion":r["conclusion"],"url":r["html_url"]}
                                     for r in runs if r["path"].split("/")[-1] == workflow and r["id"] != run["id"]]}
        if workflow == "goal-planning.yml":
            live = [job for job in jobs if "genuine held-out shared-backend" in job["name"]]
            row["liveAI"] = {"status":"PASS" if len(live) == 1 and live[0]["conclusion"] == "success" else "NOT_EXECUTED",
                             "layer":"ACTUAL_SHARED_PROVIDER_QUALITY", "network_E2E_result":"NOT_CLIENT_EXPOSED",
                             "job":live[0]["id"] if live else None,"url":run["html_url"],
                             "measurementOnly":True,"qualityThresholdEnforced":False,
                             "claim":"50 frozen typed requests per model with at most one correction; complete genuine measurements, not a minimum planning success rate. Feasible-plan validity, correct refusals and the separate 90% quality acceptance target remain in artifacts"}
        evidence.append(row)
    root.mkdir(parents=True,exist_ok=True)
    (root / "owner-evidence.json").write_text(json.dumps({"schema":1,"headCommit":head,"runs":evidence},indent=2)+"\n")
    return all(row["status"] == "PASS" for row in evidence)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root",type=Path)
    parser.add_argument("--head",required=True)
    args = parser.parse_args()
    raise SystemExit(0 if collect(args.root,args.head) else 1)
