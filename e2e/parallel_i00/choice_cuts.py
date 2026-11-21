"""Exercise the frozen native recipe and placement journal interruption windows."""

import argparse
from collections import Counter
import json
from pathlib import Path
import shutil
import sys
import traceback
from uuid import uuid4

from . import recipe_journal
from .campaign import admissions
from .frozen_campaign import material_controls
from .gameplay import ROOT, witness_identity
from .identity import sha256
from .joint_controls import ACACIA, BAMBOO, IDENTICAL, MIXED, SECOND_SOURCE, SITE, fixture, live_frame, source_frames, unfreeze
from .native_evidence import item_at, jsonl, require_command, request_job, stock_at, verify_frames, verify_installation
from .walkthrough import (AXE, DESTINATION, METHODS, PROFILE as SEED, SOURCE, STICK, TABLE, boot_trial,
                          launch_seed, process_model_calls, public_views)
from .walkthrough_review import world_hashes
from parallel_b.guards import stopped_process
from suite import atomic, read, reconnect_players, require

sys.path.insert(0, str(ROOT / "e2e/parallel_a"))
from review_lifecycle_connected import chest, chest_row, entities, original
from review_receipt_prefix import block_name, chunk, dimension_root


PROFILE = "I00_FROZEN_CHOICE_CUTS"
OPERATIONS = {"recipe-withdraw": "WITHDRAW", "recipe-craft": "CRAFT", "recipe-deposit": "DEPOSIT",
              "table-withdraw": "WITHDRAW", "table-place": "PLACE"}
PREPARED = "before-native"
MUTATED = "after-native-before-publication"
PUBLISHED = "after-publication-before-participant-saves"
SAVED = "after-saves-before-acknowledgement"
COLLECTED = "COLLECTED_PENDING_INDEPENDENT_REVIEW"


def definitions():
    material_controls()  # Verifies the frozen cut file digest bound by B07.
    plan = read(ROOT / "e2e/parallel_b/choice-crash-cuts.json")
    rows = plan["cuts"]
    require(plan["fixtureOnly"] is True and len(rows) == len({r["id"] for r in rows}) == 20
            and {(row["operation"], row["requestedBoundary"]) for row in rows}
                == {(operation, cut) for operation in OPERATIONS for cut in (PREPARED, MUTATED, PUBLISHED, SAVED)},
            "Frozen interruption matrix changed")
    return rows


def case_folder(root, case):
    return root / "cases" / case["id"].replace(":", "-")


def relevant(items):
    return {item: n for item, n in items.items() if item in (ACACIA, BAMBOO, STICK, TABLE, AXE)}


def live_custody(frame, actor, placement):
    def count(point, item):
        return item_at(frame, point, item)
    return {"actor": relevant(stock_at(frame, actor)), "primary": count(SOURCE, TABLE if placement else ACACIA),
            "second": 0 if placement else count(SECOND_SOURCE, BAMBOO),
            "recipient": count(DESTINATION, AXE if placement else STICK),
            "site": placement and TABLE in frame["blocks"][",".join(map(str, SITE))]}


def saved_custody(world, actor, placement):
    native = Counter()
    for item in entities(world)[actor].get("Inventory", []):
        native[item["id"]] += int(item.get("count", 1))
    return {"actor": relevant(native), "primary": chest(world, SOURCE).get(TABLE if placement else ACACIA, 0),
            "second": 0 if placement else chest(world, SECOND_SOURCE).get(BAMBOO, 0),
            "recipient": chest(world, DESTINATION).get(AXE if placement else STICK, 0),
            "site": placement and block_name(world, SITE) == TABLE}


def expected(operation, after):
    if operation.startswith("table-"):
        withdrawn = operation == "table-place" or after
        placed = operation == "table-place" and after
        return {"actor": {TABLE: 1} if withdrawn and not placed else {}, "primary": int(not withdrawn),
                "second": 0, "recipient": 0, "site": placed}
    acquired = operation != "recipe-withdraw" or after
    crafted = operation == "recipe-deposit" or operation == "recipe-craft" and after
    delivered = int(operation == "recipe-deposit" and after)
    actor = {STICK: 4 - delivered} if crafted else {ACACIA: 1, BAMBOO: 1} if acquired else {}
    return {"actor": actor, "primary": int(not acquired), "second": int(not acquired), "recipient": delivered, "site": False}


def file_at(folder, label):
    return folder / "server" / ("i00-choice-cut-" + label + ".json")


def observed(h, label, selected, timeout):
    return h.wait(lambda: (r if (r := read(file_at(h.root, label))) and r.get("id") == selected else None),
                  f"Original exact-choice interruption {label}", timeout)


def trial(folder, case, seed):
    folder.mkdir(parents=True)
    operation, cut = case["operation"], case["requestedBoundary"]
    placement = operation.startswith("table-")
    positive = cut == SAVED and operation in {"recipe-craft", "recipe-deposit", "table-place"}
    output = AXE if placement else STICK
    row = {"case": case, "status": "NOT_EXECUTED_REQUIRED"}
    h = None
    try:
        h = boot_trial(folder, seed, PROFILE)
        start, initial = fixture(h, IDENTICAL if placement else MIXED)
        actor = h.entities["PlayerA"]
        row.update(start=start, initialSave=initial, actor=actor, principal=h.players["PlayerA"], pins=h.artifacts)
        if cut in (MUTATED, PUBLISHED):
            offset = len(h.server_log())
            h.console(["save-off"])
            h.wait(lambda: "Automatic saving is now disabled" in h.server_log()[offset:],
                   "Vanilla automatic saves disabled before the measured unsaved choice", 25)
            row["noAutomaticSave"] = {"stdoutOffset": offset, "time": time.time()}
        arm = {"id": str(uuid4()), "boundary": cut, "phase": OPERATIONS[operation],
               "method": h.artifacts["placement" if placement else STICK]}
        request_file = folder / "server/i00-choice-cut.json"
        atomic(request_file, arm)
        row["arm"] = arm
        row["armed"] = observed(h, "armed", arm["id"], 30)
        command = h.recipe_command(h.citizens["PlayerA"], output, 1, SOURCE, DESTINATION, SITE if placement else None)
        if not placement:
            command += " " + " ".join(map(str, SECOND_SOURCE))
        row["request"] = request_job(h, "PlayerA", command)
        identity = row["request"]["root"]
        require(identity and row["request"]["accepted"], "Original exact recipe Job was refused")
        mark = observed(h, "reached", arm["id"], 100)
        row["nativeBoundary"] = mark
        require(mark["intent"]["root"] == identity, "Native writer intercepted a foreign original attempt")
        h.clock("tick freeze")
        row["cut"] = live_frame(h, identity, lambda job, frame: job["state"] == "ACTIVE"
                                and live_custody(frame, actor, placement) == expected(operation, cut != PREPARED),
                                "Actual native custody at the exact interruption boundary", 12)
        h.screenshot("PlayerA", "original-choice-cut", chat=True)
        if cut in (PREPARED, SAVED):
            row["cutSave"] = h.save_checkpoint()
        cold = read(folder / "server/process.json")
        h.stop("server", hard=True)
        row["stop"] = {"cold": cold, "hashes": h.world_hashes()}
        request_file.rename(folder / "arm-request.json")
        shutil.copytree(folder / "server/game/world", folder / "stopped-world", ignore=shutil.ignore_patterns("session.lock"))
        h.compose("stop", "ollama")
        h.assert_backend_stopped()
        h.start("server")
        row["stop"]["warm"] = read(folder / "server/process.json")
        reconnect_players(h, ["PlayerA", "PlayerB"], unbound=("PlayerB",))
        unfreeze(h)
        if positive:
            row["after"] = live_frame(h, identity, lambda job, _: job["state"] == "SUCCEEDED",
                                      "Supported saved native choice finishes the original Job", 145)
            row["clientDelivery"] = h.menu_count("PlayerA", DESTINATION, output)
        else:
            row["after"] = live_frame(h, identity, lambda job, _: job["state"] == "INTERRUPTED" and job["fulfilled"] == 0,
                                      "Ambiguous native choice remains fenced at the original Job", 85)
            h.quiet(35)
            row["later"] = h.snapshot()
        row["view"] = public_views(h, identity)
        h.screenshot("PlayerA", "original-choice-outcome", chat=True)
        h.screenshot("PlayerB", "private-choice-view", chat=True)
        row["status"] = COLLECTED
    except Exception as error:
        row.update(status="FAIL_NATIVE", reason=str(error), trace=traceback.format_exc())
    finally:
        if h:
            row["screenshots"] = h.screenshots
            h.finish()
        atomic(folder / "result.json", row)
    return row


def check_stop(folder, row, cut, jar):
    events = list(jsonl(folder / "supervisor.jsonl"))
    stop, native = row["stop"], row["nativeBoundary"]
    cold, warm = stop["cold"], stop["warm"]
    ended, = [e for e in events if e["kind"] == "process-stop-witness" and e["data"]["process"] == cold]
    require(ended["data"]["hard"] and not ended["data"]["state"]["OOMKilled"]
            and stopped_process(ended["data"]["state"], hard=True) and native["pid"] == row["cut"]["state"]["pid"] == cold["pid"]
            and cold["releaseSha256"] == warm["releaseSha256"] == jar and cold["start"] < warm["start"]
            and warm["pid"] == row["after"]["state"]["pid"] and native["time"] / 1000 < ended["time"],
            "Actual original native cut and cold JVM replacement do not agree")
    saves = [e["data"] for e in events if e["kind"] == "acknowledged-world-checkpoint" and e["time"] < ended["time"]]
    required = [row["initialSave"]]
    if cut in (PREPARED, SAVED):
        required.append(row["cutSave"])
    require(saves == required and any(e["kind"] == "crafting-backend-unavailable" and e["time"] > ended["time"] for e in events),
            "Native checkpoint sequence or cold model state differs")
    world = folder / "stopped-world"
    require(world_hashes(world) == stop["hashes"], "Stopped native world bytes differ")
    if cut in (MUTATED, PUBLISHED):
        disabled = row["noAutomaticSave"]
        log = (folder / "server/stdout.log").read_text(errors="replace")[disabled["stdoutOffset"]:]
        require(disabled["time"] < native["time"] / 1000
                and "Automatic saving is now disabled" in log
                and not any(e["time"] < ended["time"] and "save-on" in e["data"].get("commands", []) for e in events),
                "The unflushed native participant window lacks its actual save-off witness")
        def terrain(files):
            return {entry["path"]: entry["sha256"] for entry in files
                    if entry["path"].endswith(("/region/r.0.0.mca", "/entities/r.0.0.mca"))}
        before, stopped = terrain(row["initialSave"]["files"]), terrain(stop["hashes"])
        require(before and all(stopped.get(path) == digest for path, digest in before.items()),
                "An arbitrary unsaved cut changed a pre-existing native participant region")
    return world


def saved_markers(world, entry, actor):
    native = entities(world)[actor]
    for witness in entry["result"]["after"]:
        address = witness["address"]
        if address.startswith("actor:"):
            require(address.endswith("/" + actor), "Saved result contains a foreign actor")
            recipe_journal.saved_marker(native, entry, witness)
        elif address.startswith("chest:"):
            position = list(map(int, address.rsplit("/", 1)[1].split(",")))
            recipe_journal.saved_marker(chest_row(world, position), entry, witness)
        else:
            require(address.startswith("block:") and list(map(int, address.rsplit("/", 1)[1].split(","))) == SITE,
                    "Saved native site differs from the original placement")
            x, _, z = SITE
            region = dimension_root(world) / "region" / f"r.{x // 512}.{z // 512}.mca"
            recipe_journal.saved_block_marker(chunk(region, x // 16, z // 16), entry, witness, SITE)


def review_case(folder, case, source, jar, pins):
    metadata = read(folder / "metadata.json")
    witness_identity(metadata, source, jar, PROFILE)
    row = read(folder / "result.json")
    require(row and row["case"] == case and row["status"] == COLLECTED,
            (row or {}).get("reason", "Missing exact native interruption"))
    verify_installation(folder, metadata, ("PlayerA", "PlayerB"))
    cut, operation = case["requestedBoundary"], case["operation"]
    placement = operation.startswith("table-")
    positive = cut == SAVED and operation in {"recipe-craft", "recipe-deposit", "table-place"}
    old, final = row["cut"]["job"], row["after"]["job"]
    native, actor = row["nativeBoundary"], row["actor"]
    root = old["id"]
    require(read(file_at(folder, "reached")) == native and read(file_at(folder, "armed")) == row["armed"]
            and read(folder / "arm-request.json") == row["arm"] and native["id"] == row["armed"]["id"] == row["arm"]["id"]
            and native["phase"] == OPERATIONS[operation] and native["boundary"] == cut
            and root == final["id"] == row["request"]["root"] and root not in row["view"]["foreign"]
            and old["request"] == final["request"] and old["allowance"] == final["allowance"]
            and old["request"]["context"]["principal"]["id"] == row["principal"]
            and row["pins"] == pins and native["intent"]["method"] == row["arm"]["method"] == pins["placement" if placement else STICK],
            "Original authority, admitted method or cut identity changed")
    original_job = old["attempts"][-1]
    require(native["intent"]["root"] == root and native["intent"]["attempt"] == original_job["id"]
            and native["intent"]["generation"] == original_job["generation"] == old["generation"]
            and native["intent"]["deadline"] == old["allowance"]["deadlineEpochMillis"], "Native cut changed the original attempt or deadline")
    world = check_stop(folder, row, cut, jar)
    owner = original(world, root)
    require(owner["submission"] == old["submissionId"] and owner["attempts"][-1]["id"] == original_job["id"],
            "The stopped Job does not own the original physical effect")
    journal = recipe_journal.read(world, expected_world=owner["request"]["world"])
    entry = journal[native["intent"]["token"]]
    acknowledged = cut in (PUBLISHED, SAVED)
    observed = native["observedResult"]
    require(entry["prepare"] == native["intent"] and native["resultPublished"] == acknowledged
            and (entry["result"] == observed and recipe_journal.positive(entry) if acknowledged else entry["result"] is None)
            and (not observed if cut == PREPARED else observed["status"] == "APPLIED" and observed["token"] == entry["prepare"]["token"]),
            "Actual native journal bytes disagree with the requested publication window")
    live = live_custody(row["cut"]["state"], actor, placement)
    stored = saved_custody(world, actor, placement)
    original_vector = expected(operation, cut == SAVED) if cut in (PREPARED, SAVED) else expected(
        "table-withdraw" if placement else "recipe-withdraw", False)
    require(live == expected(operation, cut != PREPARED) and stored == original_vector,
            "Stopped participant bytes do not establish the observed native cut")
    if cut == SAVED:
        saved_markers(world, entry, actor)
    after = row["after"]["state"]
    require(not any(process_model_calls(after).values()) and len(final["attempts"]) >= len(old["attempts"])
            and all(a["id"] == b["id"] and a["generation"] == b["generation"] and a["allowance"] == b["allowance"]
                    and a["resourceReceipts"] == b["resourceReceipts"][:len(a["resourceReceipts"])]
                    for a, b in zip(old["attempts"], final["attempts"])), "Restart regenerated method, attempt or earlier receipts")
    if positive:
        target = AXE if placement else STICK
        require(final["state"] == "SUCCEEDED" and final["fulfilled"] == row["clientDelivery"] == item_at(after, DESTINATION, target) == 1
                and (not placement and stock_at(after, actor) == {STICK: 3}
                     or placement and TABLE in after["blocks"][",".join(map(str, SITE))]),
                "Supported original saved prefix failed native completion or surplus conservation")
        proved = recipe_journal.phases(recipe_journal.read(folder / "server/game/world"), root)
        first = [e for e in proved if e["prepare"]["attempt"] == original_job["id"]]
        phases = [e["prepare"]["phase"] for e in first]
        require(phases == (["WITHDRAW", "PLACE"] if placement else ["WITHDRAW", "CRAFT", "DEPOSIT"])
                and all(map(recipe_journal.positive, first)), "Restart duplicated or replaced a native phase of the original attempt")
    else:
        require(final["state"] == "INTERRUPTED" and final["fulfilled"] == 0 and final["generation"] == old["generation"]
                and "next=RECOVER_OR_SETTLE" in row["view"]["owner"]
                and live_custody(after, actor, placement) == live_custody(row["later"], actor, placement) == original_vector,
                "Ambiguous original cut replayed or credited a native effect")
    source_frames(folder, row)
    require_command(folder / "clients/PlayerA/events.jsonl", row["request"]["command"], row["request"]["feedback"], "PlayerA")
    verify_frames(folder, row["screenshots"], minimum=3)
    return {"id": case["id"], "status": "PASS_NATIVE_SAVED_CONTINUATION" if positive else "PASS_NATIVE_CONTAINMENT",
            "nativeResultPublished": acknowledged, "nativeSaveAtCut": cut in (PREPARED, SAVED),
            "originalRoot": root, "originalAttempt": original_job["id"], "liveParticipants": live,
            "savedParticipants": stored, "resultSha256": sha256(folder / "result.json")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("run", "review"))
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--operation", choices=OPERATIONS, required=True)
    parser.add_argument("--source")
    parser.add_argument("--jar-sha256")
    args = parser.parse_args()
    root = args.root.resolve()
    cases = [row for row in definitions() if row["operation"] == args.operation]
    if args.mode == "run":
        root.mkdir(parents=True, exist_ok=False)
        seed = launch_seed(root / "seed", METHODS)
        rows = [trial(case_folder(root, row), row, seed) for row in cases]
        if any(row["status"] != COLLECTED for row in rows):
            raise SystemExit(1)
        return
    report = {"operation": args.operation, "profile": PROFILE, "status": "FAIL_REQUIRED_CHOICE_CUTS", "cases": []}
    try:
        report.update(witness_identity(read(root / "seed/metadata.json"), args.source, args.jar_sha256, SEED))
        pins = admissions(root / "seed")
        for case in cases:
            try:
                report["cases"].append(review_case(case_folder(root, case), case, args.source, args.jar_sha256, pins))
            except Exception as error:
                report["cases"].append({"id": case["id"], "status": "FAIL_REVIEW", "reason": str(error), "trace": traceback.format_exc()})
        if all(row["status"].startswith("PASS_NATIVE_") for row in report["cases"]):
            report["status"] = "PASS_INDEPENDENT_CHOICE_CUTS"
    finally:
        atomic(root / "review.json", report)
    print(json.dumps(report, indent=2))
    if report["status"] != "PASS_INDEPENDENT_CHOICE_CUTS":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
