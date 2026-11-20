"""An isolated native tmpfs exhaustion after an acknowledged exact recipe preparation."""

import json
import os
import shutil
import subprocess
from uuid import uuid4

from . import recipe_journal
from .identity import sha256
from .native_evidence import item_at, request_job, require_command, stock_at
from .recipe_challenges import observe, result_file
from campaigns.crafting.generator import OAK, STICK
from suite import atomic, read, require


CASE = "storage-pressure"
VOLUME_BYTES = 16 * 1024 * 1024
JOURNAL = "data/cognitivecraft/recipe-evidence/phases.jsonl"
REQUIRED = "data/cognitivecraft/recipe-evidence-required.json"


class JournalVolume:
    def __init__(self, root):
        self.root = root
        self.original = root / "pre-pressure-directory"
        self.target = root / "server/game/world/data/cognitivecraft"
        self.mounted = False

    def mount(self):
        require(not self.original.exists() and self.target.is_dir() and not self.target.is_symlink(),
                "Expected an isolated stopped journal directory")
        self.target.rename(self.original)
        self.target.mkdir()
        options = f"size={VOLUME_BYTES},mode=0755,uid={os.getuid()},gid={os.getgid()}"
        subprocess.run(["sudo", "mount", "-t", "tmpfs", "-o", options, "tmpfs",
                        str(self.target.resolve())], check=True, timeout=20)
        self.mounted = True
        shutil.copytree(self.original, self.target, dirs_exist_ok=True)

    def close(self):
        if not self.mounted:
            if self.original.is_dir() and self.target.is_dir():
                self.target.rmdir()
                self.original.rename(self.target)
            return
        retained = self.root / "final-native-journal-volume"
        try:
            shutil.copytree(self.target, retained)
        finally:
            subprocess.run(["sudo", "umount", str(self.target.resolve())], check=True, timeout=20)
            self.mounted = False
        self.target.rmdir()
        retained.rename(self.target)


def exercise(h, row, source, destination):
    from .joint_controls import live_frame, stop_world, unfreeze
    from .walkthrough import public_views

    token = str(uuid4())
    request = {"id": token, "kind": CASE, "method": h.artifacts[STICK]}
    atomic(h.root / "server/i00-recipe-challenge.json", request)
    row["armed"] = observe(h, "armed", token)
    row["request"] = request_job(h, "PlayerA", h.recipe_command(h.citizens["PlayerA"], STICK, 3, source, destination))
    root = row["request"]["root"]
    require(row["request"]["accepted"], "Original real request did not reach durable preparation")
    row["pressure"] = observe(h, "pressure", token, 90)
    require(row["pressure"]["intent"]["root"] == root, "Storage pressure missed the original preparation")
    h.quiet(40)
    row["cut"] = live_frame(h, root, lambda job, state: job["fulfilled"] == 0 and item_at(state, destination, STICK) == 0,
                           "Original credit stays empty while actual journal storage is full")
    h.clock("tick freeze")
    h.screenshot("PlayerA", CASE + "-cut", chat=True)
    row["stop"] = stop_world(h)
    unfreeze(h)
    row["after"] = live_frame(h, root, lambda job, _: job["state"] == "INTERRUPTED" and job["fulfilled"] == 0,
                             "Original storage-failed owner remains truthfully interrupted", 65)
    h.quiet(40)
    row["later"] = h.snapshot()
    row["view"] = public_views(h, root)
    h.screenshot("PlayerA", CASE + "-outcome", chat=True)
    h.screenshot("PlayerB", CASE + "-independent-player", chat=True)


def review(folder, row, source, destination, jar):
    from .joint_controls import confirmed_stop, receipts, source_frames

    root = row["request"]["root"]
    pressure = read(result_file(folder, "pressure"))
    old = row["cut"]["job"]
    final = row["after"]["job"]
    require(row["pressure"] == pressure and pressure["initialFreeBytes"] == pressure["allocatedBytes"] > 0
            and pressure["remainingBytes"] == 0 and pressure["volumeBytes"] == VOLUME_BYTES
            and "No space left" in pressure["systemRefusal"] and pressure["pid"] == row["stop"]["cold"]["pid"],
            "No observed native ENOSPC on the isolated writer filesystem")
    world = folder / "stopped-world"
    required = read(world / REQUIRED)
    data = (world / JOURNAL).read_bytes()
    prefix = data[:required["bytes"]].splitlines(keepends=True)
    require(prefix and recipe_journal.digest(prefix[-1]) == required["head"], "Actual committed journal prefix was not preserved")
    original_prep = [json.loads(line) for line in prefix if json.loads(line).get("root") == root]
    require(original_prep and {k: v for k, v in original_prep[0].items() if k not in ("previous", "kind")} == pressure["intent"]
            and original_prep[0]["kind"] == "prepare" and original_prep[0]["phase"] == "WITHDRAW"
            and old["id"] == final["id"] == root and final["request"] == old["request"] and final["allowance"] == old["allowance"]
            and final["attempts"][0]["id"] == original_prep[0]["attempt"] and final["generation"] == old["generation"] == 1,
            "Full storage changed the original prepared effect identity or allowance")
    first, after, later = row["start"], row["after"]["state"], row["later"]
    require(final["state"] == "INTERRUPTED" and "next=RECOVER_OR_SETTLE" in row["view"]["owner"]
            and row["after"]["job"]["fulfilled"] == 0 and all(r["stage"] != "DEPOSIT" for r in receipts(final))
            and root not in row["view"]["foreign"]
            and all(item_at(frame, destination, STICK) == 0 for frame in (first, after, later))
            and item_at(after, source, OAK) == item_at(later, source, OAK)
            and stock_at(after, row["actor"]) == stock_at(later, row["actor"])
            and item_at(after, source, OAK) + stock_at(after, row["actor"]).get(OAK, 0) == 2,
            "Original stock was replayed, lost, or granted unsupported delivery when storage ran out")
    confirmed_stop(folder, row, jar)
    require_command(folder / "clients/PlayerA/events.jsonl", row["request"]["command"], row["request"]["feedback"], "PlayerA")
    source_frames(folder, row)
    return {"id": CASE, "root": root, "status": "PASS", "scope": "I00_NATIVE_DURABLE_STORAGE_PRESSURE",
            "preparedBeforeExhaustion": True, "nativeFilesystemFull": True, "deliveryCredit": 0, "replayEffects": 0,
            "evidenceSha256": sha256(folder / "result.json")}
