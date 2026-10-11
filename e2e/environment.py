#!/usr/bin/env python3
"""Pack or restore a genuine stopped-world F1 arena with strict build/file checks."""
from __future__ import annotations

import argparse
import json
from pathlib import Path, PurePosixPath
import shutil
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

from runtime import ROOT, digest, semantic_jar

DEFAULT_ARCHIVE = ROOT / "e2e/environment/known-skill.zip"
DEFAULT_MANIFEST = DEFAULT_ARCHIVE.with_suffix(".json")
MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
MAX_RESTORED_BYTES = 128 * 1024 * 1024
MAX_FILES = 4096


def matches_production(manifest: Path, jar: Path) -> bool:
    """Select a reusable arena; restore still verifies its archive and world."""
    record = json.loads(manifest.read_text())
    expected = record.get("semanticJarSha256")
    if record.get("schema") != 1 or not isinstance(expected, str) or len(expected) != 64 or any(
            char not in "0123456789abcdef" for char in expected):
        raise ValueError("Invalid arena production identity")
    return expected == semantic_jar(jar)


def path_name(value: str) -> str:
    path = PurePosixPath(value)
    if not value or "\\" in value or path.is_absolute() or ".." in path.parts or str(path) != value:
        raise ValueError("Unsafe arena member: " + value)
    return value


def certificate_files(certificate: dict) -> dict[str, str]:
    if (certificate.get("schema"), certificate.get("fixture"), certificate.get("minecraft")) != (1, "F1", "26.3"):
        raise ValueError("Unsupported arena certificate")
    acquisition, reuse = certificate["acquisition"], certificate["offlineChangedBindingReuse"]
    if not (acquisition.get("artifact") and acquisition.get("generationCalls", 0) > 0 and
            acquisition["artifact"] == reuse.get("artifact") and reuse.get("generationCalls") == 0):
        raise ValueError("Arena requires genuine acquisition and identical model-free reuse")
    files = {}
    for item in certificate["files"]:
        name = "game/" + path_name(item["path"])
        if not name.startswith("game/world/") or name in files or len(item["sha256"]) != 64:
            raise ValueError("Invalid or duplicate certified world file")
        files[name] = item["sha256"]
    if not files or len(files) > MAX_FILES or set(certificate["citizens"]) != {"PlayerA", "PlayerB"}:
        raise ValueError("Incomplete or excessive arena certificate")
    return files


def check_seed(seed: Path, jar: Path) -> dict:
    certificate = json.loads((seed / "certificate.json").read_text())
    files = certificate_files(certificate)
    if certificate["semanticJarSha256"] != semantic_jar(jar):
        raise ValueError("Production JAR contents changed; run genuine acquisition again")
    actual = {str(p.relative_to(seed)) for p in seed.rglob("*") if p.is_file() and p.name != "session.lock"}
    if actual != set(files) | {"certificate.json"} or any(p.is_symlink() for p in seed.rglob("*")):
        raise ValueError("Uncertified file or symbolic link in saved arena")
    if sum((seed / name).stat().st_size for name in files) > MAX_RESTORED_BYTES:
        raise ValueError("Saved arena exceeds size bound")
    for name, expected in files.items():
        if digest(seed / name) != expected:
            raise ValueError("Saved-world integrity mismatch: " + name)
    return certificate


def pack(seed: Path, archive: Path, manifest: Path, jar: Path, evidence_url: str) -> dict:
    certificate = check_seed(seed, jar)
    if not evidence_url.startswith("https://github.com/senavirathne/CognitiveCraft-public/actions/runs/"):
        raise ValueError("Provide the actual producing workflow evidence URL")
    archive.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(archive, "w", compression=ZIP_DEFLATED, compresslevel=9) as out:
        for name in sorted({"certificate.json", *certificate_files(certificate)}):
            info = ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            info.external_attr = 0o100600 << 16
            out.writestr(info, (seed / name).read_bytes())
    if archive.stat().st_size > MAX_ARCHIVE_BYTES:
        archive.unlink()
        raise ValueError("Arena archive exceeds size bound")
    record = {"schema":1, "fixture":"F1", "minecraft":"26.3", "archive":archive.name,
              "archiveSha256":digest(archive), "archiveBytes":archive.stat().st_size,
              "certificateSha256":digest(seed / "certificate.json"),
              "producerCommit":certificate["producerCommit"],
              "producerJarSha256":certificate["producerJarSha256"],
              "semanticJarSha256":certificate["semanticJarSha256"],
              "certifiedWorldFiles":len(certificate["files"]), "evidenceUrl":evidence_url,
              "artifact":certificate["acquisition"]["artifact"],
              "contents":"Stopped actual Minecraft world; original citizen IDs and genuinely admitted knowledge",
              "externalRuntime":"Java 25, official Minecraft 26.3/Fabric assets and real models installed separately"}
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(json.dumps(record, indent=2) + "\n")
    return record


def restore(archive: Path, manifest: Path, destination: Path, jar: Path) -> dict:
    record = json.loads(manifest.read_text())
    if record.get("schema") != 1 or record.get("archive") != archive.name or not (
            archive.stat().st_size == record["archiveBytes"] <= MAX_ARCHIVE_BYTES and
            digest(archive) == record["archiveSha256"]):
        raise ValueError("Arena archive integrity mismatch")
    if destination.exists():
        raise ValueError("Restore destination must be a new directory")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="arena-verify-", dir=destination.parent) as temporary:
        seed = Path(temporary)
        with ZipFile(archive) as source:
            members = source.infolist()
            names = [path_name(item.filename) for item in members]
            if (len(names) != len(set(names)) or len(names) > MAX_FILES + 1 or
                    sum(item.file_size for item in members) > MAX_RESTORED_BYTES):
                raise ValueError("Duplicate or excessive arena members")
            for item in members:
                if (item.external_attr >> 16) & 0o170000 == 0o120000 or item.is_dir():
                    raise ValueError("Unsupported arena member type")
                target = seed / item.filename
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.open(item) as stream, target.open("xb") as out:
                    shutil.copyfileobj(stream, out)
                target.chmod(0o600)
        certificate = check_seed(seed, jar)
        if not (digest(seed / "certificate.json") == record["certificateSha256"] and
                all(certificate[key] == record[key] for key in
                    ("producerCommit", "producerJarSha256", "semanticJarSha256")) and
                len(certificate["files"]) == record["certifiedWorldFiles"]):
            raise ValueError("Arena manifest/certificate mismatch")
        seed.rename(destination)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("pack", "restore", "compatible"))
    parser.add_argument("--seed", type=Path, default=ROOT / "build/e2e-certified-seed")
    parser.add_argument("--archive", type=Path, default=DEFAULT_ARCHIVE)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--destination", type=Path, default=ROOT / "build/e2e-restored-seed")
    parser.add_argument("--jar", type=Path, default=ROOT / "fabric/build/libs/ai-villages-0.1.0.jar")
    parser.add_argument("--evidence-url")
    args = parser.parse_args()
    if args.action == "compatible":
        print("compatible=" + str(matches_production(args.manifest, args.jar)).lower())
        return
    if args.action == "pack":
        if not args.evidence_url:
            parser.error("pack requires --evidence-url")
        record = pack(args.seed, args.archive, args.manifest, args.jar, args.evidence_url)
    else:
        record = restore(args.archive, args.manifest, args.destination, args.jar)
    print(json.dumps(record, indent=2))


if __name__ == "__main__":
    main()
