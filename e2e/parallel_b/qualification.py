"""Bounded evidence verification after newest-artifact selection.

The expected identity comes from the checked-out source, actual built binary and
CI event, independently of the candidate report. An intact failed gameplay report
passes integrity and still fails its gameplay gate; neither conclusion replaces
the other. No previous passing archive is consulted on refusal.
"""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import re

MAX_JSON = 16_000_000
MAX_FILES = 20_000


class Refused(ValueError):
    def __init__(self, code, detail):
        super().__init__(detail)
        self.code = code


def safe(root, name):
    if not isinstance(name, str) or not name or "\\" in name:
        raise Refused("INVALID_PATH", "Invalid evidence path")
    relative = Path(name)
    if relative.is_absolute() or ".." in relative.parts:
        raise Refused("INVALID_PATH", "Evidence leaves its root")
    path = root / relative
    if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
        raise Refused("INVALID_PATH", "Evidence follows a foreign symlink")
    if not path.is_file():
        raise Refused("MISSING", "Required evidence is missing: " + name)
    return path


def load(root, name):
    p = safe(root, name)
    if p.stat().st_size > MAX_JSON:
        raise Refused("BYTE_LIMIT", "Oversized JSON evidence")
    try:
        return json.loads(p.read_bytes())
    except (ValueError, UnicodeError) as error:
        raise Refused("MALFORMED", "Malformed JSON evidence: " + name) from error


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def identity(metadata, expected, config_digest):
    fields = {"sourceCommit":"FOREIGN_SOURCE", "sourceTree":"FOREIGN_TREE", "sourceBranch":"FOREIGN_BRANCH",
              "releaseSha256":"WRONG_BINARY", "profile":"WRONG_PROFILE"}
    for field, code in fields.items():
        if not expected.get(field) or metadata.get(field) != expected[field]:
            raise Refused(code, "Mismatched required identity: " + field)
    if metadata.get("dirty") is not False:
        raise Refused("DIRTY_SOURCE", "Uncommitted source cannot qualify")
    ci = metadata.get("ci", {})
    for field, code in (("GITHUB_RUN_ID","FOREIGN_RUN"), ("GITHUB_RUN_ATTEMPT","STALE_ATTEMPT")):
        if not expected.get(field) or str(ci.get(field)) != str(expected[field]):
            raise Refused(code, "Mismatched required CI identity: " + field)
    if config_digest != expected.get("configurationSha256"):
        raise Refused("WRONG_CONFIG", "Configuration digest differs")
    if metadata.get("binaries", {}).get("production", {}).get("sha256") != expected["releaseSha256"]:
        raise Refused("WRONG_BINARY", "Production binary envelope differs")
    if metadata.get("binaries", {}).get("observer", {}).get("sha256") != expected.get("observerSha256"):
        raise Refused("WRONG_INSTRUMENT", "Observer binary envelope differs")


def verify(root, expected, *, required, mandatory):
    """Integrity may PASS while mandatory gameplay FAILS/was not executed."""
    root = Path(root)
    try:
        metadata = load(root, "metadata.json")
        if not isinstance(metadata, dict):
            raise Refused("MALFORMED", "Metadata must be an object")
        config = safe(root, "configuration-manifest.json")
        identity(metadata, expected, sha(config))
        manifest = load(root, "manifest.json")
        if not isinstance(manifest, dict):
            raise Refused("MALFORMED", "Manifest must be an object")
        if manifest.get("source") != metadata:
            raise Refused("MISMATCHED_ENVELOPE", "Manifest source differs from metadata")
        files = manifest.get("files")
        if not isinstance(files, list) or not files or len(files) > MAX_FILES:
            raise Refused("INCOMPLETE_MANIFEST", "Missing/oversized witness inventory")
        names = set()
        for record in files:
            if not isinstance(record, dict) or not isinstance(record.get("path"), str):
                raise Refused("MALFORMED", "Invalid manifest witness")
            name = record.get("path")
            if name in names:
                raise Refused("DUPLICATE", "Duplicate manifest witness")
            names.add(name)
            path = safe(root, name)
            if type(record.get("bytes")) is not int or record["bytes"] != path.stat().st_size:
                raise Refused("CORRUPT", "Witness size differs: " + name)
            if not re.fullmatch(r"[a-f0-9]{64}", record.get("sha256", "")) or sha(path) != record["sha256"]:
                raise Refused("CORRUPT", "Witness digest differs: " + name)
        if not set(required) <= names:
            raise Refused("MISSING", "Mandatory witness omitted from the manifest")
        # Mandatory outcome assertions run only after all original evidence has
        # survived verification. Exceptions are a failed assertion, never PASS.
        checks = []
        for name, predicate in mandatory.items():
            try:
                good = predicate(root)
                status = "PASS" if good is True else "FAIL"
            except Exception as error:
                status = "FAIL"
                checks.append({"name": name, "status": status, "reason": str(error)})
                continue
            checks.append({"name": name, "status": status})
        gameplay = "PASS" if checks and all(c["status"] == "PASS" for c in checks) else "FAIL" if checks else "NOT_EXECUTED"
        return {"integrity":"PASS", "mandatoryGameplay":gameplay,"checks":checks,"verifiedFiles":len(files)}
    except Refused as error:
        return {"integrity":"FAIL","mandatoryGameplay":"NOT_QUALIFIED","code":error.code,"reason":str(error)}
    except (OSError, ValueError, TypeError, KeyError) as error:
        return {"integrity":"FAIL","mandatoryGameplay":"NOT_QUALIFIED","code":"MALFORMED","reason":str(error)}
