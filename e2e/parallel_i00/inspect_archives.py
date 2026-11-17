"""Read the two oversized preserved failure ZIPs without executing their contents."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess
from zipfile import ZipFile

ARCHIVES = (
    ("b", 14337817173, "9da2fb0f61404de55afe0fef8c841e00f3a3e48459180abd75bb36c9745dc2a7"),
    ("a06", 14337963054, "f70b1647c2f6ab94b3f3f442bc55d69d4c8e85caf65663834ed2527cc23f2c55"),
)


def main():
    out = Path("build/i00-failure-inspection")
    out.mkdir(parents=True, exist_ok=False)
    for label, artifact, expected in ARCHIVES:
        archive = Path("build") / (label + "-original.zip")
        with archive.open("wb") as stream:
            subprocess.run(["gh", "api", f"repos/{__import__('os').environ['GITHUB_REPOSITORY']}/actions/artifacts/{artifact}/zip"],
                           stdout=stream, check=True)
        with archive.open("rb") as stream:
            actual = hashlib.file_digest(stream, "sha256").hexdigest()
        if actual != expected:
            raise ValueError("Original archive digest differs: " + label)
        selected = []
        with ZipFile(archive) as bundle:
            for member in bundle.infolist():
                path = PurePosixPath(member.filename)
                if path.is_absolute() or ".." in path.parts:
                    raise ValueError("Unsafe archive member")
                if member.is_dir() or member.file_size > 12_000_000:
                    continue
                if "game" in path.parts and "logs" not in path.parts:
                    continue
                if path.suffix not in (".json", ".jsonl", ".log", ".txt"):
                    continue
                destination = out / label / path
                destination.parent.mkdir(parents=True, exist_ok=True)
                body = bundle.read(member)
                destination.write_bytes(body)
                selected.append({"path": str(path), "bytes": len(body), "sha256": hashlib.sha256(body).hexdigest()})
        (out / (label + "-selection.json")).write_text(json.dumps({"artifact": artifact, "sha256": actual, "files": selected}, indent=2))
        print(json.dumps({"archive": label, "sha256": actual, "selectedFiles": len(selected)}), flush=True)
        archive.unlink()


if __name__ == "__main__":
    main()
