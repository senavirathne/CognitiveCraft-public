"""Compare a real partial region restore with its coordinated native save."""

import shutil

from .identity import sha256


def exercise(h, pre_effect, complete, token):
    from baseline import native_process
    from deposits import actual_stock, manifest
    from generalization_support import DST, LOG
    from review_receipt_prefix import dimension_root
    from suite import atomic, require

    folder = h.root / "whole-versus-partial-restore"
    inputs = folder / "partial-game"
    shutil.copytree(complete, inputs, ignore=shutil.ignore_patterns("session.lock"))
    world = inputs / "world"
    x, _, z = DST
    relative = (dimension_root(world) / "region" / f"r.{x // 512}.{z // 512}.mca").relative_to(world)
    older = pre_effect / "world" / relative
    pre = manifest(world)
    require(pre == manifest(complete / "world") and sha256(older) != pre[str(relative)],
            "Native coordinated pre/post save bytes are missing")
    shutil.copyfile(older, world / relative)
    partial = manifest(world)
    require(partial == pre | {str(relative): sha256(older)}, "Partial restore replaced more than one native region")

    h.restore(inputs, "selective-region-rollback")
    h.family = "I00-selective-region-rollback"
    partial_process = native_process(h)
    observed = h.wait(h.stable_entry, "Original metadata and partial native region in a new JVM", 45)
    require(observed["protectedJobs"] and observed["reconciliation"][token] in {"UNPROVEN", "TORN_PARTICIPANTS"},
            "Partial restore incorrectly proved original custody")
    denied = h.rpc("verify", token=token)
    start = actual_stock(h)
    h.quiet(40)
    unchanged = actual_stock(h)
    require(denied["code"] == "STALE" and start["destination"] == unchanged["destination"] == 0
            and start["actor"]["inventory"] == unchanged["actor"]["inventory"] == {},
            "Selective save restore gained authority or replayed the missing deposit")

    h.restore(complete, "coordinated-region-and-owner-rollback")
    h.family = "I00-coordinated-region-and-owner-rollback"
    complete_process = native_process(h)
    resolved = h.wait(lambda: (row if (row := h.stable_entry()) and row["reconciliation"].get(token) == "SAVED_COMPLETE" else None),
                      "Coordinated restored actor, recipient and owner metadata agree", 45)
    accepted = h.rpc("verify", token=token)
    settled = h.wait(lambda: (row if (row := h.stable_entry()) and not row["protectedJobs"] else None),
                     "Original saved custody verification is acknowledged", 45)
    received = h.menu_count("PlayerA", DST, LOG)
    atomic(folder / "result.json", {"path": str(relative), "wholeFiles": pre, "partialFiles": partial,
           "preEffect": str(pre_effect.relative_to(h.root)), "complete": str(complete.relative_to(h.root)),
           "token": token, "partialReader": observed, "completeReader": resolved, "partialProcess": partial_process,
           "completeProcess": complete_process, "partialRefusal": denied, "wholeVerification": accepted,
           "finalReader": settled, "beforeQuiet": start, "afterQuiet": unchanged, "received": received})


def review(root):
    from deposits import manifest
    from generalization_support import DST, LOG, at
    from freeze_storage_review import process_replaced
    from recovery_fixture import marker, participant
    from review_lifecycle_connected import chest, chest_row, entities
    from review_receipt_prefix import block_name, journal
    from suite import read, require

    folder = root / "whole-versus-partial-restore"
    result = folder / "result.json"
    row = read(result)
    complete = root / row["complete"] / "world"
    partial = folder / "partial-game/world"
    earlier = root / row["preEffect"] / "world"
    path = row["path"]
    require(manifest(complete) == row["wholeFiles"] and manifest(partial) == row["partialFiles"]
            and row["partialFiles"] == row["wholeFiles"] | {path: sha256(earlier / path)}
            and row["partialFiles"][path] != row["wholeFiles"][path]
            and chest(partial, DST) == chest(earlier, DST) == {} and chest(complete, DST) == {LOG: 1},
            "Actual saved participants do not demonstrate the required partial counterexample")
    _, entries = journal(complete / "data/cognitivecraft/deposit-evidence/v1/state.jsonl")
    entry, = entries
    actor = entry["intent"]["actor"]["address"]
    saved_actor = entities(complete)[actor]
    require(entities(partial)[actor] == saved_actor and block_name(complete, at(6, 2)) == "minecraft:air"
            and block_name(partial, at(6, 2)) == block_name(earlier, at(6, 2)) == LOG,
            "Partial region restore failed to retain the original saved actor beside the older terrain")
    participant(saved_actor, entry["result"]["actor"], marker(entry), "Inventory")
    participant(chest_row(complete, DST), entry["result"]["chest"], marker(entry), "Items")
    process_replaced(row["partialProcess"], row["completeProcess"])
    old, restored, final = (row[key] for key in ("partialReader", "completeReader", "finalReader"))
    token = row["token"]
    require(old["journal"] == restored["journal"] and old["protectedJobs"]
            and old["reconciliation"][token] in {"UNPROVEN", "TORN_PARTICIPANTS"}
            and row["partialRefusal"]["code"] == "STALE" and restored["reconciliation"][token] == "SAVED_COMPLETE"
            and row["wholeVerification"]["code"] in {"PENDING", "RESOLVED"} and not final["protectedJobs"]
            and final["journal"]["entries"][0]["verification"]["outcome"] == "SAVED_COMPLETE"
            and row["beforeQuiet"]["destination"] == row["afterQuiet"]["destination"] == 0 and row["received"] == 1,
            "The original saved deposit did not distinguish partial restore from coordinated recovery")
    return {"status": "PASS_INDEPENDENT_NATIVE_BOUNDARY", "scope": "NATIVE_PARTIAL_AND_COORDINATED_WORLD_RESTORE",
            "partialRefused": True, "coordinatedSavedReceipt": True, "nativeEffectsAfterRestore": 0,
            "resultSha256": sha256(result)}
