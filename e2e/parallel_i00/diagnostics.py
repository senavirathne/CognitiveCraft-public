"""Small read-only diagnostic copy; full raw archives remain qualification authority."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil


NATIVE_OWNERS = {"jobs", "leases", "harvest-evidence", "pickup-evidence", "deposit-evidence", "recipe-evidence"}
SAVED_RECIPE_CUT = Path("integrated-controls/cases/birch-q3-saved-craft-continuation/stopped-world")


def collect(root, out):
    rows=[]
    for source in sorted(root.rglob("*")):
        relative=source.relative_to(root)
        native_journal = ("cognitivecraft" in relative.parts and any(part in NATIVE_OWNERS for part in relative.parts)
                          and source.suffix == ".jsonl")
        saved_recipe_region = relative.is_relative_to(SAVED_RECIPE_CUT) and source.suffix == ".mca"
        if not source.is_file() or source.is_symlink() or ("game" in relative.parts and "logs" not in relative.parts
                                                          and not native_journal):
            continue
        if source.suffix not in (".json", ".log", ".xml", ".jsonl") and not saved_recipe_region:
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
