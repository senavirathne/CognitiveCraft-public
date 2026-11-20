"""Connected native data-reload and unsupported-remainder adversarial cases."""

from uuid import uuid4

from . import recipe_journal
from .identity import sha256
from .native_evidence import item_at, request_job, require_command, stock_at
from campaigns.crafting.generator import OAK, STICK
from suite import atomic, read, require


RELOAD = "same-catalogue-reload"
COMPONENTS = "changed-result-components"
REMAINDERS = "native-remainders"
CASES = (RELOAD, COMPONENTS, REMAINDERS)
HONEY = "minecraft:honey_bottle"
FILLED_ACTOR = {OAK: 2, HONEY: 2, STICK: 60, "minecraft:stone": 64, "minecraft:dirt": 64,
                "minecraft:sand": 64, "minecraft:gravel": 64, "minecraft:cobblestone": 64}


def result_file(root, kind):
    return root / "server" / ("i00-recipe-challenge-" + kind + ".json")


def observe(h, stage, token, timeout=30):
    return h.wait(lambda: (result if (result := read(result_file(h.root, stage))) and result["id"] == token else None),
                  "Native datapack fixture " + stage, timeout)


def exercise(h, row, source, destination):
    from .joint_controls import live_frame, receipts
    from .walkthrough import public_views

    kind = row["id"]
    token = str(uuid4())
    method = h.artifacts[STICK]
    request = {"id": token, "kind": kind, "method": method}
    atomic(h.root / "server/i00-recipe-challenge.json", request)
    row["reloadRequest"] = request
    if kind == REMAINDERS:
        row["reload"] = observe(h, "finished", token)
    else:
        row["armed"] = observe(h, "armed", token)
    command = h.recipe_command(h.citizens["PlayerA"], STICK, 3, source, destination)
    row["request"] = request_job(h, "PlayerA", command)
    root = row["request"]["root"]
    require(root and row["request"]["accepted"], "Original ordinary recipe request did not reach the Job pipeline")
    if kind == REMAINDERS:
        row["cut"] = live_frame(h, root, lambda job, _: job["state"] == "WAITING" and not job["attempts"]
                                and job["activity"]["production"]["blocker"] != "", "Native remainder profile is refused")
    else:
        row["prepared"] = observe(h, "prepared", token)
        row["reload"] = observe(h, "finished", token)
        row["cut"] = live_frame(h, root, lambda job, _: any(a.get("terminal") and a["terminal"].get("reason") == "ARTIFACT_INCOMPATIBLE"
                                 for a in job["attempts"]), "Original prepared recipe is rejected after actual reload", 40)
    require(not receipts(row["cut"]["job"]), "Original incompatible recipe received a physical receipt")
    if row["cut"]["job"]["state"] in {"ACTIVE", "READY", "WAITING"}:
        row["cancelled"] = h.cancel_job("PlayerA", root)
    h.quiet(20)
    row["after"] = live_frame(h, root, lambda job, _: bool(job), "Original incompatible prescription remained effect-free")
    row["view"] = public_views(h, root)
    h.screenshot("PlayerA", kind + "-outcome", chat=True)
    h.screenshot("PlayerB", kind + "-independent-player", chat=True)


def review(folder, row, source, destination):
    from .joint_controls import receipts, source_frames

    kind, original = row["id"], row["request"]["root"]
    initial, cut, final = row["start"], row["cut"], row["after"]
    job = final["job"]
    require(job["id"] == cut["job"]["id"] == original and job["fulfilled"] == 0 and not receipts(job)
            and job["allowance"] == cut["job"]["allowance"] and job["request"] == cut["job"]["request"]
            and all(a["effects"] == 0 for a in job["attempts"]) and original not in row["view"]["foreign"],
            "Original owner, allowance or incompatible physical result changed")
    state = final["state"]
    require(stock_at(initial, row["actor"]) == stock_at(state, row["actor"])
            and all(item_at(state, source, item) == item_at(initial, source, item) for item in (OAK, HONEY))
            and item_at(initial, destination, STICK) == item_at(state, destination, STICK) == 0
            and state["generationCalls"] == initial["generationCalls"], "Actual game custody or inference changed")
    loaded = read(result_file(folder, "finished"))
    require(loaded == row["reload"] and loaded["kind"] == kind and loaded["id"] == row["reloadRequest"]["id"]
            and loaded["pid"] == state["pid"] and loaded["started"] <= loaded["ended"] <= state["time"]
            and (loaded["before"]["recipe"], loaded["after"]["recipe"], loaded["after"]["output"], loaded["after"]["count"])
                == ("minecraft:stick", "minecraft:stick", STICK, 4), "Actual native datapack reload identity differs")
    if kind == REMAINDERS:
        native = stock_at(initial, row["actor"])
        require(native == FILLED_ACTOR and len(native) == 8 and native[STICK] + 4 == 64
                and (native[HONEY], native[OAK]) == (2, 2) and item_at(initial, source, HONEY) == item_at(initial, source, OAK) == 1
                and loaded["before"]["remainders"] == []
                and loaded["after"]["remainders"] == [{"item": "minecraft:glass_bottle", "count": 1}]
                and not job["attempts"] and not job["activity"]["steps"],
                "Loaded recipe does not separate result capacity from native remainder capacity")
        proof = {"resultUnitsFit": 4, "nativeRemainderSlotsNeeded": 1, "nativeRemainderSlotsAvailable": 0}
    else:
        world = folder / "server/game/world"
        entries = recipe_journal.phases(recipe_journal.read(world, expected_world=job["request"]["context"]["scope"]["worldId"]), original)
        original_phase = entries[0]
        prepared = read(result_file(folder, "prepared"))
        intent = original_phase["prepare"]
        require(prepared == row["prepared"] and prepared["id"] == loaded["id"] and prepared["pid"] == loaded["pid"]
                and prepared["started"] == loaded["started"]
                and prepared["intent"] == {k: v for k, v in intent.items() if k != "kind"}
                and intent["phase"] == "WITHDRAW" and intent["method"] == row["methodPins"][STICK]
                and intent["attempt"] == job["attempts"][0]["id"] and intent["generation"] == job["attempts"][0]["generation"]
                and original_phase["result"]["status"] == "NO_EFFECT"
                and all(entry["result"] and entry["result"]["status"] == "NO_EFFECT" for entry in entries),
                "Actual reload did not invalidate the exact already acknowledged original preparation")
        require(loaded["before"]["customName"] == "" and loaded["after"]["remainders"] == []
                and (loaded["before"] == loaded["after"] if kind == RELOAD else
                     loaded["after"]["customName"] == "I00 changed recipe result"
                     and loaded["before"]["encoding"] != loaded["after"]["encoding"]), "Native recipe semantics were not controlled")
        proof = {"durablePreparationBeforeReload": True, "sameRecipeId": True, "componentChange": kind == COMPONENTS,
                 "oldAttempt": intent["attempt"], "oldJournalToken": intent["token"]}
    require_command(folder / "clients/PlayerA/events.jsonl", row["request"]["command"], row["request"]["feedback"], "PlayerA")
    source_frames(folder, row)
    return {"id": kind, "root": original, "status": "PASS", "scope": "I00_NATIVE_RECIPE_CHALLENGE",
            "nativeEffects": 0, "evidenceSha256": sha256(folder / "result.json"), **proof}
