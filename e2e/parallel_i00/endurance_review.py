"""Independently reconcile native I00 endurance evidence and preserved world history."""

import argparse
from collections import Counter
import gzip
from hashlib import file_digest, sha256
import json
import math
from pathlib import Path
import re
import sys

from .endurance_archive import bounded_bytes, object_from_bytes, require, verify_archive
from .endurance_plan import COUNT, LIMITS, LOG, PLANK, RESTART_AFTER, SHARED, SHARED_TARGETS, TERMINAL, WAREHOUSE, workload
from .identity import ROOT, candidate, verify_metadata

JSON_MAX = 16 * 1024 * 1024
FRAME_MAX = 64 * 1024 * 1024
HASH = re.compile(r"[a-f0-9]{64}")


def load(path):
    return object_from_bytes(bounded_bytes(path, JSON_MAX))


def point(value):
    return ",".join(map(str, value))


def frame(root, reference):
    path = root / reference["path"]
    require(path.resolve().is_relative_to(root.resolve()) and not path.is_symlink(), "Unsafe native frame path")
    with gzip.open(path, "rb") as file:
        raw = file.read(FRAME_MAX + 1)
    require(len(raw) <= FRAME_MAX and len(raw) == reference["bytes"]
            and sha256(raw).hexdigest() == reference["sha256"], "Raw native frame digest or bytes differ")
    state = object_from_bytes(raw)
    require(state["gameTick"] == reference["gameTick"] and state["arenaLoaded"] is True, "Native game tick or arena differs")
    return state


def job_at(state, ticket):
    bound = state["knownPlayers"][ticket["player"]]
    require(bound["online"] is True and bound["uuid"] == ticket["principal"], "Original connected principal differs")
    return bound["jobs"].get(ticket["root"])


def stock(state, position, item):
    location = point(position)
    if location not in state["containers"]:
        require("minecraft:air" in state["blocks"][location], "Participant absent without native air observation")
        return 0
    return state["containers"][location].get(item, 0)


def inventory(state, ticket):
    actor = next(a for a in state["actors"] if a["uuid"] == ticket["actor"])
    require(actor["alive"] and not actor["baby"] and not actor["noAI"], "Native original adult villager differs")
    return actor["inventory"]


def calls(state):
    return state["needleCalls"], state["brokerStats"]["calls"]


def exact_recipe_receipts(job, ticket, seen):
    totals = Counter()
    steps = {value["id"]: value for value in job["activity"]["steps"]}
    for attempt in job["attempts"]:
        require(attempt["worker"]["citizenId"] == ticket["citizen"] and attempt["worker"]["entityId"] == ticket["actor"],
                "Original worker identity differs")
        for row in attempt.get("resourceReceipts", []):
            require(row["actor"]["entityId"] == ticket["actor"]
                    and row["origin"]["principal"]["id"] == ticket["principal"], "Native receipt crosses actors or principals")
            witness = row["choice"]
            step = steps[witness["step"]]
            require(witness["root"] == job["id"] and witness["attempt"] == attempt["id"]
                    and witness["generation"] == attempt["generation"] and step["attempt"] == attempt["id"]
                    and step["choice"]["sha256"] == witness["choiceDigest"], "Recipe receipt lost original choice/attempt binding")
            require(HASH.fullmatch(witness["custodyBefore"]) and HASH.fullmatch(witness["custodyAfter"])
                    and witness["custodyBefore"] != witness["custodyAfter"], "Missing native before/after custody identities")
            require(row["receiptId"] not in seen["receipts"] and witness["journalToken"] not in seen["journal"],
                    "A physical receipt or journal token was credited twice")
            seen["receipts"].add(row["receiptId"])
            seen["journal"].add(witness["journalToken"])
            totals[row["stage"]] += row["quantity"]
    return totals


def validate_row(root, row, case, directory, seen, admissions):
    require(row["case"] == case and row["directoryId"] == directory, "Frozen case or live world directory changed")
    ticket = row["ticket"]
    job = row["terminalJob"]
    require(ticket["root"] == job["id"] == row["lookup"]["job"]["id"] and "accepted=true" in ticket["feedback"]
            and f"activity={job['id']}" in ticket["feedback"] and job["id"] not in seen["root"]
            and job["submissionId"] not in seen["submission"] and not job.get("parent")
            and ticket["player"] == case["player"], "Only distinct, acknowledged original activity roots count")
    seen["root"].add(job["id"])
    seen["submission"].add(job["submissionId"])
    require(job["state"] in TERMINAL and all(a.get("terminal") and a["uncertain"] is False for a in job["attempts"]),
            "Open or uncertain original work cannot enter the terminal denominator")
    request = job["request"]["request"]
    amounts = request["arguments"]["amount"]["value"]
    require(job["activity"]["mode"] == "MAKE_NEW" and amounts == case["demand"]
            and request["arguments"]["actor"]["value"]["citizenId"] == ticket["citizen"]
            and row["lookup"]["status"] == "PASS", "Retained I00 activity demand or authoritative Job differs")
    maxima = job["allowance"]["maxima"]
    require(all(0 <= used <= maxima.get(k, 0) for k, used in job["usage"].items()), "Cumulative allowance exceeded")
    before, after = (frame(root, row[name]) for name in ("beforeFrame", "afterFrame"))
    require(after["pid"] == before["pid"] and after["gameTick"] > before["gameTick"] and calls(before) == calls(after),
            "A measured activity changed native JVM, tick ordering or inference counters")
    actual = job_at(after, ticket)
    require(actual is not None and all(actual.get(key) == value for key, value in row["lookup"]["job"].items())
            and all(job.get(key) == value for key, value in row["lookup"]["job"].items()),
            "Native independently observed Job and authoritative reader disagree")
    owner = job["request"]["context"]["principal"]["id"]
    require(owner == ticket["principal"], "Activity authorizer changed")
    stock_before = stock(before, case["source"], LOG)
    stock_after = stock(after, case["source"], LOG)
    delivered = stock(after, case["destination"], PLANK) - stock(before, case["destination"], PLANK)
    retained = inventory(after, ticket).get(PLANK, 0) - inventory(before, ticket).get(PLANK, 0)
    receipts = exact_recipe_receipts(job, ticket, seen)
    event = row["event"] or {}
    duration = row["settledMillis"] - ticket["acceptedMillis"]
    require(duration >= 0 and ticket["acceptedMillis"] >= ticket["requestedMillis"] and row["lookupNanos"] > 0,
            "Native submission, service or lookup timing reverses")

    if case["role"] in ("delivery", "contention"):
        require(job["state"] == "SUCCEEDED" and job["fulfilled"] == amounts and delivered == amounts and retained == 4 - amounts,
                "Positive activity did not deliver attributable new stock and conserve surplus")
        require(receipts == {"WITHDRAW": 1, "CRAFT": 4, "DEPOSIT": amounts}, "Exact recipe phase quantities differ")
        require(job["activity"]["steps"] and all(s["artifact"]["sha256"] in admissions for s in job["activity"]["steps"]),
                "Production used a method absent from the genuine admission")
        if case["index"] != 6:
            require(stock_before - stock_after == 1, "Production did not consume one actual source log")
        if case["role"] == "contention":
            overlapping = frame(root, event["overlap"])
            require(len(event["owners"]) == 2 and all(owner["state"] not in TERMINAL for owner in event["owners"])
                    and all(any(view["id"] == owner["id"] for frame_player in overlapping["knownPlayers"].values()
                                for view in frame_player["jobs"].values()) for owner in event["owners"]),
                    "Missing independently witnessed concurrent original owners")
            if case["index"] == 6:
                waiting = frame(root, event["waitingFrame"])
                require(job_at(waiting, ticket)["state"] == "WAITING" and stock(waiting, SHARED, LOG) == 0
                        and stock_after == 0 and event["donorSlot"]["playerInventory"] is True
                        and event["donorSlot"]["item"] == LOG and event["donorSlot"]["count"] == 1
                        and event["donated"]["play"] is True and all(not s["playerInventory"] or s["item"] != LOG
                                                                  for s in event["donated"]["slots"])
                        and ticket["acceptedMillis"] < event["eligibleMillis"] <= row["settledMillis"],
                        "Actor B lacked a real wait and connected donor stock witness")
    elif case["role"] == "admitted-failure":
        removed = frame(root, event["removedFrame"])
        admitted = job_at(removed, ticket)
        require(job["state"] == "FAILED" and job["reason"] in ("TARGET_UNAVAILABLE", "STALE_OBSERVATION")
                and job["fulfilled"] == 0 and not receipts and stock_before == stock_after and delivered == retained == 0
                and "minecraft:chest" in before["blocks"][point(case["destination"])]
                and admitted and admitted["state"] not in TERMINAL and not admitted["attempts"]
                and before["gameTick"] == removed["gameTick"]
                and "minecraft:air" in removed["blocks"][point(case["destination"])]
                and "minecraft:air" in after["blocks"][point(case["destination"])]
                and event["obstruction"]["play"] is True, "Expected admitted physical obstruction failure did not occur")
    elif case["role"] == "settled-cancellation":
        require(job["state"] == "CANCELLED" and "cancellation=true" in event["cancellation"]
                and not receipts and stock_before == stock_after and delivered == retained == 0,
                "Original zero-effect cancellation failed to settle")
    elif case["role"] == "partial-custody":
        cut = frame(root, event["partialCut"])
        require(job["state"] == "CANCELLED" and "cancellation=true" in event["cancellation"]
                and job_at(cut, ticket)["state"] not in TERMINAL
                and inventory(cut, ticket).get(PLANK, 0) == inventory(before, ticket).get(PLANK, 0) + 4
                and stock_before - stock_after == 1 and delivered == 0 and retained == 4
                and receipts == {"WITHDRAW": 1, "CRAFT": 4}, "Observed partial craft/cancellation lost custody or permitted late delivery")
    poses = row["trajectory"]
    require(poses and all(left["tick"] < right["tick"] for left, right in zip(poses, poses[1:])),
            "Missing or reordered native movement samples")
    distance = sum(math.dist(first["position"], second["position"]) for first, second in zip(poses, poses[1:]))
    return delivered, duration, after["gameTick"] - before["gameTick"], distance


def milliseconds(samples):
    data = sorted(samples)
    require(data and all(n >= 0 for n in data), "Missing native measurement samples")
    return {**{f"p{p}": data[math.ceil(len(data) * p / 100) - 1] / 1_000_000 for p in (50, 95, 99)},
            "max": data[-1] / 1_000_000, "samples": len(data)}


def telemetry(root, world, processes, first_request, last_settlement):
    closures = [load(path) for path in sorted((root / "server").glob("i00-endurance-ticks-*.json"))]
    ticks, captures, acknowledgements = [], [], []
    peaks = Counter()
    revisions = set()
    latest = (-1, -1)
    for process in processes:
        started = process["namespaceProcess"]["start"] * 1000
        matching = [c for c in closures if c["pid"] == process["namespaceProcess"]["pid"]
                    and abs(c["startedMillis"] - started) < 15_000]
        require(len(matching) == 1, "Missing or ambiguous read-only telemetry from measured native JVM")
        closure = matching[0]
        require(closure["dropped"] == 0 and not closure.get("error"), "Native meter dropped evidence")
        path = root / "server" / f"i00-endurance-ticks-{closure['process']}.jsonl"
        ordinal = 0
        with path.open("rb") as stream:
            for raw in stream:
                ordinal += 1
                require(len(raw) < 65_536 and raw.endswith(b"\n"), "Invalid raw native tick frame")
                row = object_from_bytes(raw)
                require(row["sequence"] == ordinal and row["world"] == world, "Lost or foreign native tick frame")
                if not first_request <= row["timeMillis"] <= last_settlement:
                    continue
                ticks.append(row["tickNanos"])
                captures.append(row["captureNanos"])
                acknowledgements.extend(item["durationNanos"] for item in row["jobAcknowledgements"])
                require(latest[0] <= row["nativeTick"] and latest[1] <= row["archiveRevision"],
                        "Native world history or archive revision rolled back")
                latest = (row["nativeTick"], row["archiveRevision"])
                revisions.add(row["archiveRevision"])
                for metric, key in (("jobsHot", "hotJobs"), ("jobsActive", "activeJobs"),
                                    ("leasesHot", "hotLeases"), ("leasesActive", "activeLeases"),
                                    ("archiveBytes", "archiveBytes"), ("archiveSegments", "archiveSegments"),
                                    ("jobHotBytes", "jobHotBytes"), ("leaseHotBytes", "leaseHotBytes")):
                    peaks[metric] = max(peaks[metric], row[key])
        require(closure["observed"] == ordinal, "Missing raw telemetry records after native close")
    require(len(ticks) >= 400 and len(acknowledgements) >= COUNT and len(revisions) > 2,
            "Missing measured activity, acknowledgement or archive workload")
    require(all(peaks[metric] <= limit for metric, limit in LIMITS.items() if metric in peaks), "Original live quotas exceeded")
    measured = {"tick": milliseconds(ticks), "capture": milliseconds(captures),
                "ack": milliseconds(acknowledgements), "peakLive": dict(peaks), "archiveRevisions": len(revisions)}
    for series, field, maximum in (("tick", "p95", "tickP95Ms"), ("tick", "p99", "tickP99Ms"),
                                    ("capture", "p99", "captureP99Ms"), ("ack", "p95", "ackP95Ms"),
                                    ("ack", "p99", "ackP99Ms"), ("ack", "max", "ackMaxMs")):
        require(measured[series][field] <= LIMITS[maximum], f"Unchanged performance gate failed: {maximum}")
    return measured


def archive_domains(history, world):
    jobs, leases = {}, {}
    for (owner, kind, identifier), payload in history.items():
        if kind != "HISTORY":
            continue
        value = object_from_bytes(payload)
        if owner == "LEASES":
            require(value["id"] == identifier and value["world"] == world and value["state"] in {"RELEASED", "EXPIRED", "REVOKED"},
                    "Archived lease is foreign or can still authorize work")
            leases[identifier] = value
        else:
            require(value["world"] == world and value["jobs"], "Archive Job component lacks original native roots")
            for job in value["jobs"]:
                require(job["state"] in TERMINAL and jobs.setdefault(job["id"], job) == job, "Archived Job changed immutable identity")
    return jobs, leases


def file_hashes(root, files):
    require(files, "Missing stopped native world file inventory")
    names = {file["path"] for file in files}
    require(len(names) == len(files) and names == {p.relative_to(root).as_posix() for p in root.rglob("*")
                                                  if p.is_file() and p.name != "session.lock"},
            "Saved native world inventory lost or gained files")
    for file in files:
        path = root / file["path"]
        require(path.resolve().is_relative_to(root.resolve()) and not path.is_symlink(), "Unsafe saved world file path")
        with path.open("rb") as stream:
            actual = file_digest(stream, "sha256").hexdigest()
        require(file["sha256"] == actual, f"Native saved bytes changed for {file['path']}")


def saved_destination_counts(world, positions):
    sys.path.insert(0, str(ROOT / "e2e/parallel_a"))
    from native_nbt import chunk
    from review_receipt_prefix import dimension_root
    native = dimension_root(world)
    counts = {}
    for x, y, z in positions:
        region = native / "region" / f"r.{x // 512}.{z // 512}.mca"
        data = chunk(region, x // 16, z // 16)
        chests = [e for e in data["block_entities"] if (e["x"], e["y"], e["z"]) == (x, y, z)]
        require(len(chests) == 1 and chests[0]["id"] == "minecraft:chest", "Saved native destination identity missing")
        counts[(x, y, z)] = sum(item.get("count", item.get("Count", 1)) for item in chests[0].get("Items", [])
                                if item["id"] == PLANK)
    return counts


def observed_intakes(path):
    receipts = set()
    with path.open("rb") as stream:
        for line in stream:
            require(line.endswith(b"\n") and len(line) <= JSON_MAX, "Incomplete supervisor command/event record")
            event = object_from_bytes(line)
            if event.get("kind") == "command":
                data = event["data"]
                if data["text"].startswith("/aivillage kernel production-activity ") and "accepted=true" in data["feedback"]:
                    receipts.add((data["player"], data["text"], data["feedback"]))
    return receipts


def review(root, source, jar):
    exact = candidate(source, jar)
    verify_metadata(load(root / "metadata.json"), exact)
    status = load(root / "endurance.json")
    require(status["status"] == "COLLECTED_PENDING_INDEPENDENT_REVIEW" and status["required"] == status["observed"] == COUNT,
            "The continuous native activity denominator did not complete")
    plan = load(root / "frozen-endurance-workload.json")
    require(plan == {"cases": workload(), "limits": LIMITS, "restartAfter": list(RESTART_AFTER)},
            "Workload or service limit changed after freeze")
    admissions = load(root / "independent-admissions.json")["admissions"]
    allowed = {entry["view"]["marker"]["artifactSha256"] for entry in admissions if entry["status"] == "PASS"
               and entry["view"]["marker"]["outcome"] == "ADMITTED"
               and entry["callsAfter"]["generationCalls"] > entry["callsBefore"]["generationCalls"]}
    require(len(allowed) == len(admissions) == 2, "No genuine native admission provenance")
    original = status["initialPin"]
    require(original == status["finalPin"] and original["job"]["state"] == "INTERRUPTED" and original["leases"]
            and all(l["state"] == "INTERRUPTED" and l["owner"]["job"] == original["job"]["id"] for l in original["leases"]),
            "Unresolved native root or lease was not retained")
    cut = load(root / "pinned-original/result.json")
    require(cut["job"] == original["job"]["id"] and cut["oldNativeGoneAfterKill"] is True and cut["saved"] is False
            and cut["after"]["responsibility"]["state"] == "INTERRUPTED", "Pin lacks an actual interrupted process and Job")
    worker = load(root / "pinned-original/separate-worker.json")
    pinned_actor = cut["beforeCustody"]["actorEntity"]
    require(worker["worker"]["uuid"] == pinned_actor and worker["worker"]["alive"] and not worker["worker"]["noAI"]
            and cut["afterCustody"]["actorEntity"] == pinned_actor
            and original["job"]["attempts"][-1]["worker"]["entityId"] == pinned_actor
            and pinned_actor not in worker["measuredWorkers"].values(), "Pin lacks its own original native actor")
    seen = {key: set() for key in ("root", "receipts", "journal", "submission")}
    commands = observed_intakes(root / "supervisor.jsonl")
    if set((root / "activities").glob("*/result.json")) != {
        root / "activities" / f"{index:04d}" / "result.json" for index in range(1, COUNT + 1)
    }:
        raise ValueError("Missing or unexpected measured activity evidence")
    outputs, results, waits, lookup_times, work_ticks, movement = Counter(), Counter(), [], [], [], []
    jobs = {}
    for case in plan["cases"]:
        row = load(root / "activities" / f"{case['index']:04d}" / "result.json")
        require(row["ticket"] == load(root / "activities" / f"{case['index']:04d}" / "intake.json"), "Original public intake changed")
        ticket = row["ticket"]
        require(worker["measuredWorkers"].get(ticket["player"]) == ticket["actor"], "Measured activity changed its original worker")
        require((ticket["player"], ticket["command"], ticket["feedback"]) in commands,
                "Original accepted feedback absent from the connected command transcript")
        delivered, duration, ticks, distance = validate_row(root, row, case, original["directoryId"], seen, allowed)
        outputs[tuple(case["destination"])] += delivered
        results[row["terminalJob"]["state"]] += 1
        jobs[row["terminalJob"]["id"]] = row["terminalJob"]
        waits.append(duration * 1_000_000)
        lookup_times.append(row["lookupNanos"])
        work_ticks.append(ticks)
        movement.append(distance)
        if case["index"] == 1:
            first_request = row["ticket"]["requestedMillis"]
        if case["index"] == COUNT:
            last_settlement = row["settledMillis"]
    require(original["job"]["id"] not in jobs and len(jobs) == COUNT and set(results) == TERMINAL, "Endurance counted pre-admission refusals or omitted mixed outcomes")
    restarts = status["restarts"]
    require([r["completed"] for r in restarts] == list(RESTART_AFTER), "Missing continuous-world restart")
    processes = [status["initialProcess"], *(r["after"] for r in restarts)]
    counters = [(status["initialCalls"], restarts[0]["beforeCalls"])]
    for index, restart in enumerate(restarts):
        require(restart["before"] == processes[index] and restart["pin"] == original
                and all(v["play"] is False for v in restart["disconnected"].values())
                and set(restart["clients"]) == {"PlayerA", "PlayerB"}, "Native process or owner lineage changed")
        next_before = restarts[index + 1]["beforeCalls"] if index + 1 < len(restarts) else status["finalCalls"]
        counters.append((restart["afterCalls"], next_before))
    require(status["finalProcess"] == processes[-1] and len({(p["hostJvmPid"], p["startTicks"]) for p in processes}) == len(processes)
            and all(p["namespaceProcess"]["releaseSha256"] == jar
                    and p["containerResources"] == {"cpusetCpus": "0-1", "memoryBytes": 2 * 1024**3} for p in processes),
            "Repeated native server or exact binary and resource identity differs")
    require(all(before == after for before, after in counters), "Known-method runtime called inference")
    world_path = root / "server/game/world"
    file_hashes(world_path, status["stoppedWorld"])
    history, archive = verify_archive(world_path, original["world"])
    archived, leases = archive_domains(history, original["world"])
    hot = {v["id"]: v for v in status["finalStorage"]["hot"]["jobs"]}
    native_retired = {i for i, row in leases.items() if row["job"] in jobs}
    native_retired.update(l["id"] for l in status["finalStorage"]["hotLeases"]["leases"]
                          if l["owner"]["job"] in jobs and l["state"] in ("RELEASED", "EXPIRED", "REVOKED"))
    require(len(native_retired) > 128 and len(jobs.keys() & archived.keys()) > 64
            and jobs.keys() <= hot.keys() | archived.keys(), "Original 128-lease / 64-Job history boundaries were not crossed continuously")
    for identifier, final in jobs.items():
        persisted = archived.get(identifier) or hot[identifier]
        require(persisted["generation"] == final["generation"] and persisted["state"] == final["state"]
                and [a["id"] for a in persisted["attempts"]] == [a["id"] for a in final["attempts"]]
                and persisted.get("submission", persisted.get("submissionId")) == final["submissionId"],
                "Historical lookup changed original terminal activity or attempt")
    replay = status["replay"]
    require(replay["status"] == "PASS" and replay["hot"] is False and replay["hotUnchanged"] is True
            and replay["sameReceipt"]["code"] == "DUPLICATE" and replay["changedReceipt"]["code"] == "REJECTED"
            and replay["staleSubmission"]["code"] == "REJECTED" and status["foreignReplay"]["status"] == "DENIED",
            "Original archive replay/permission guards changed")
    visible_totals = {point: outputs[point] for point in (*WAREHOUSE, *SHARED_TARGETS)}
    require({tuple(row["position"]): row["planks"] for row in status["clientStock"]} == visible_totals
            and saved_destination_counts(world_path, visible_totals) == visible_totals,
            "Final client menu or independent saved chest counts disagree with attributed output")
    timing = telemetry(root, original["world"], processes, first_request, last_settlement)
    return {"schema": 1, **exact, "status": "PASS_INDEPENDENT_NATIVE_ENDURANCE", "terminalActivities": COUNT,
            "classification": dict(results), "sameWorldRestarts": len(restarts), "nativeRetiredLeases": len(native_retired),
            "archivedOriginalActivities": len(jobs.keys() & archived.keys()), "measurements": timing, "archive": archive,
            "waitMillis": milliseconds(waits), "lookupRoundTripMillis": milliseconds(lookup_times),
            "activityTicks": sum(work_ticks), "sampledTravelBlocksLowerBound": sum(movement),
            "modelCallsByJvm": [end for _, end in counters],
            "measuredAdditionalModelCallsAcrossJvms": sum(sum(after[k] - before[k] for k in before) for before, after in counters)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--jar-sha256", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    exact = candidate(args.source, args.jar_sha256)
    try:
        value = review(args.root, args.source, args.jar_sha256)
    except Exception as failure:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps({**exact, "status": "FAIL_INDEPENDENT_NATIVE_ENDURANCE",
                                        "reason": str(failure)}, indent=2) + "\n")
        raise
    args.out.write_text(json.dumps(value, indent=2) + "\n")
    print(json.dumps(value))


if __name__ == "__main__":
    main()
