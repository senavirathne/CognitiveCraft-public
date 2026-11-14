#!/usr/bin/env python3
"""Select current-run evidence without colliding with preserved rerun artifacts."""
import argparse
import json
import os
from pathlib import Path
import re
import urllib.request

MAX_ARTIFACTS = 100
MAX_RESPONSE_BYTES = 2_000_000
PROFILES = ("acquire", "deterministic", "nlu", "resilience", "lifecycle")


class SelectionError(ValueError):
    """Stable refusal codes, while preserving ValueError compatibility."""
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def positive(value):
    return type(value) is int and 0 < value <= 2**63 - 1


def select(document, names, run_id, head):
    """Newest IDs win, including failed evidence; no fallback to older passes."""
    if not positive(run_id) or not re.fullmatch(r"[0-9a-f]{40}", head):
        raise ValueError("Invalid source identity")
    artifacts = document.get("artifacts")
    if not isinstance(artifacts, list) or len(artifacts) > MAX_ARTIFACTS:
        raise ValueError("Artifact inventory limit")
    if type(document.get("total_count")) is not int or document["total_count"] != len(artifacts):
        raise ValueError("Incomplete artifact inventory")
    if not names or len(names) > len(PROFILES) or len(set(names)) != len(names):
        raise ValueError("Invalid expected artifact names")
    selected = {}
    seen = set()
    for artifact in artifacts:
        if not isinstance(artifact, dict):
            raise ValueError("Invalid artifact record")
        name = artifact.get("name")
        if name not in names:
            continue
        identity = artifact.get("workflow_run", {})
        artifact_id = artifact.get("id")
        if (not positive(artifact_id) or artifact_id in seen or identity.get("id") != run_id
                or identity.get("head_sha") != head):
            code = "FOREIGN_SOURCE" if identity.get("head_sha") != head else "FOREIGN_RUN" if identity.get("id") != run_id else "BAD_ARTIFACT_ID"
            raise SelectionError(code, "Artifact identity mismatch")
        seen.add(artifact_id)
        if name not in selected or artifact_id > selected[name]["id"]:
            selected[name] = artifact
    if set(selected) != set(names):
        raise SelectionError("MISSING", "Missing required profile evidence")
    if any(row.get("expired") is not False for row in selected.values()):
        raise SelectionError("EXPIRED", "Latest required evidence is expired or unavailable")
    return [selected[name] for name in names]


def fetch(repository, run_id, token):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid repository")
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repository}/actions/runs/{run_id}/artifacts?per_page={MAX_ARTIFACTS}",
        headers={"Accept": "application/vnd.github+json", "Authorization": "Bearer " + token,
                 "X-GitHub-Api-Version": "2022-11-28"})
    with urllib.request.urlopen(request, timeout=30) as response:
        data = response.read(MAX_RESPONSE_BYTES + 1)
    if len(data) > MAX_RESPONSE_BYTES:
        raise ValueError("Artifact response byte limit")
    return json.loads(data.decode("utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kind", choices=("profiles", "certified"), required=True)
    parser.add_argument("--run", type=int, required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--proof", type=Path, required=True)
    args = parser.parse_args()
    names = ([f"real-gameplay-{profile}-{args.run}" for profile in PROFILES]
             if args.kind == "profiles" else [f"certified-known-skill-{args.run}"])
    document = fetch(os.environ["GITHUB_REPOSITORY"], args.run, os.environ["GITHUB_TOKEN"])
    selected = select(document, names, args.run, args.head)
    selected_ids = {row["id"] for row in selected}
    proof = {"schema": 1, "run": args.run, "head": args.head,
             "selection": "latest ID per exact required name; failures are not filtered",
             "selected": [{"id": row["id"], "name": row["name"]} for row in selected],
             "superseded": [{"id": row["id"], "name": row["name"]}
                            for row in document["artifacts"] if row.get("name") in names
                            and row["id"] not in selected_ids]}
    args.proof.parent.mkdir(parents=True, exist_ok=True)
    args.proof.write_text(json.dumps(proof, indent=2) + "\n")
    with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
        output.write("artifact_ids=" + ",".join(str(row["id"]) for row in selected) + "\n")
    print(json.dumps(proof, indent=2))


if __name__ == "__main__":
    main()
