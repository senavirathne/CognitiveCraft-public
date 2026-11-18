"""Fetch the unchanged stopped native admission archive with bounded transport retries."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import time

ARTIFACT=13311090608
SHA256="8e3a030c078d0e1423b4342eae5105eed27c2e848619390a2f40e231a22d8ae8"


def download(destination):
    repository=os.environ["GITHUB_REPOSITORY"]
    if repository!="senavirathne/CognitiveCraft-public":
        raise ValueError("Pinned native fixture belongs to the source repository")
    destination.parent.mkdir(parents=True,exist_ok=True)
    temporary=destination.with_suffix(".download")
    for attempt in range(3):
        with temporary.open("wb") as output:
            result=subprocess.run(["gh","api",f"repos/{repository}/actions/artifacts/{ARTIFACT}/zip"],
                                  stdout=output,stderr=subprocess.PIPE,text=True,timeout=180)
        if result.returncode:
            if attempt<2 and re.search(r"HTTP 50[0234]",result.stderr):
                print(f"Pinned fixture transport unavailable; bounded retry {attempt+1}/2",flush=True)
                time.sleep(attempt+1)
                continue
            raise RuntimeError("Pinned fixture download failed: "+result.stderr[-1000:])
        with temporary.open("rb") as source:
            actual=hashlib.file_digest(source,"sha256").hexdigest()
        if actual!=SHA256:
            raise ValueError("Pinned native fixture digest mismatch; bytes retained for inspection")
        temporary.replace(destination)
        print(f"Pinned native artifact {ARTIFACT} verified SHA-256 {actual}",flush=True)
        return


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out",required=True,type=Path)
    download(parser.parse_args().out)
