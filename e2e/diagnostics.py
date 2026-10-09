#!/usr/bin/env python3
"""Bounded text diagnostics usable when an Actions artifact is not downloadable."""
from pathlib import Path
import sys

for argument in sys.argv[1:]:
    root = Path(argument)
    for pattern in ("results.json", "metadata.json", "**/stdout.log", "*-launcher.log", "**/response.json", "server/snapshot.json"):
        for path in root.glob(pattern):
            print("\nDIAGNOSTIC", path)
            lines = path.read_text(errors="replace").splitlines()
            print("\n".join(line[:2000] for line in lines[-110:]))
    for path in root.glob("clients/*/events.jsonl"):
        print("\nCLIENT EVENTS", path)
        print("\n".join(line[:2000] for line in path.read_text(errors="replace").splitlines()[-15:]))
