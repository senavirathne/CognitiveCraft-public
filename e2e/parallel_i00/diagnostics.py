"""Small read-only diagnostic copy; full raw archives remain qualification authority."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil


def collect(root, out):
    rows=[]
    for source in sorted(root.rglob("*")):
        relative=source.relative_to(root)
        if not source.is_file() or source.is_symlink() or ("game" in relative.parts and "logs" not in relative.parts):
            continue
        if source.suffix not in (".json", ".log", ".xml", ".jsonl"):
            continue
        if source.name in ("timeline.jsonl", "observations.jsonl", "resources.jsonl"):
            continue
        size=source.stat().st_size
        if size>8*1024*1024:
            rows.append({"path":str(relative),"bytes":size,"diagnosticCopy":"OMITTED_OVERSIZE_SEE_FULL_ARCHIVE"})
            continue
        destination=out/relative
        destination.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(source,destination)
        with source.open("rb") as stream:
            digest=hashlib.file_digest(stream,"sha256").hexdigest()
        rows.append({"path":str(relative),"bytes":size,"sha256":digest})
    out.mkdir(parents=True,exist_ok=True)
    (out/"diagnostic-selection.json").write_text(json.dumps({"qualificationEvidence":False,"files":rows},indent=2)+"\n")


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,required=True)
    parser.add_argument("--out",type=Path,required=True)
    args=parser.parse_args()
    if args.out.resolve().is_relative_to(args.root.resolve()):
        parser.error("Diagnostic output must be separate from raw evidence")
    collect(args.root,args.out)
