"""Qualification guards. Observations here never become production authority."""
from __future__ import annotations
import json
from pathlib import Path
import re
from zipfile import ZipFile

TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED"}
ACTIVE_PHASES = {"PERSISTING", "ROUTING", "EXECUTING", "RESEARCHING", "RESOLVING", "INTERPRETING"}


def settled_owner(view):
    return (isinstance(view, dict) and view.get("state") in TERMINAL
            and isinstance(view.get("attempts"), list)
            and all(isinstance(a, dict) and a.get("terminal") is not None and a.get("uncertain") is False
                    for a in view["attempts"]))


def clean_owners(data):
    """Cancellation acknowledgement and INTERRUPTED are never settlement."""
    if not isinstance(data, dict) or not isinstance(data.get("jobs"), dict) or not isinstance(data.get("runs"), dict):
        return False
    owners = list(data["jobs"].values())
    for run in data["runs"].values():
        if run.get("phase") in ACTIVE_PHASES:
            return False
        responsibility = run.get("responsibility")
        if responsibility is None:
            return False  # Missing durable owner observation is unknown.
        owners.append(responsibility)
    return all(settled_owner(owner) for owner in owners)


def clock_response(command, response):
    if not re.fullmatch(r"tick (?:freeze|unfreeze|step 1|rate (?:1|2|20))", command):
        raise ValueError("Unsupported test clock request")
    text = response.lower()
    if any(word in text for word in ("unknown", "incorrect", "incomplete", "permission", "failed", "error")):
        raise ValueError("Rejected vanilla clock command")
    if command == "tick freeze":
        accepted = "frozen" in text and "unfrozen" not in text
    elif command == "tick unfreeze":
        accepted = "unfrozen" in text or "unfreeze" in text
    elif command.startswith("tick rate "):
        accepted = "tick rate" in text and "per second" in text
    else:
        accepted = "step" in text and "tick" in text
    if not accepted:
        raise ValueError("No native clock execution feedback")
    return True


def verified_clock(h, command):
    # Player feedback is emitted after server command execution. The launcher's
    # control-ack merely confirms stdin delivery and cannot substitute for it.
    response = h.command("PlayerA", "/" + command,
            r"(?i)frozen|unfreeze|tick rate|stepp|unknown|incorrect|incomplete|permission|failed|error", 30)
    clock_response(command, response)
    h.event("verified-native-clock", {"command": command, "feedback": response})
    return response


def stopped_process(witness, *, hard):
    if not isinstance(witness, dict) or witness.get("Running") is not False or witness.get("Pid") != 0:
        raise ValueError("No observed process exit")
    if not witness.get("FinishedAt") or witness.get("FinishedAt", "").startswith("0001-"):
        raise ValueError("Missing process-finished identity")
    if hard and witness.get("ExitCode") != 137:
        raise ValueError("SIGKILL was not independently observed")
    return True


def instruments(production, observer, *, oracle_required=True):
    with ZipFile(production) as release:
        names = release.namelist()
        if len(names) != len(set(names)) or any("e2e" in n.lower() for n in names):
            raise ValueError("Test instrumentation contaminated the production JAR")
    with ZipFile(observer) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate instrument entries")
        manifest = json.loads(archive.read("fabric.mod.json"))
        if oracle_required:
            if "dev.aivillages.e2e.CraftingRecipeOracle" not in manifest["entrypoints"]["main"]:
                raise ValueError("Recipe oracle is not registered in the isolated observer")
            for needed in ("CraftingRecipeOracle", "CraftingPrimitiveTrace"):
                if "dev/aivillages/e2e/" + needed + ".class" not in names:
                    raise ValueError("Missing normal observer class")
            config = json.loads(archive.read("crafting-trace.mixins.json"))
            if config.get("package") != "dev.aivillages.e2e.crafting.mixin" or config.get("required") is not True:
                raise ValueError("Unsafe instrument Mixin namespace/requirement")
            if manifest.get("mixins", []).count("crafting-trace.mixins.json") != 1:
                raise ValueError("Missing or duplicate instrument Mixin registration")
            prefix = config["package"].replace(".", "/") + "/"
            declared = {prefix + n + ".class" for n in config["mixins"]}
            actual = {n for n in names if n.startswith(prefix) and n.endswith(".class") and "$" not in n}
            if actual != declared:
                raise ValueError("Mixin configuration does not match packaged classes")
    return True


def observer_inputs(root):
    for name, maximum in (("oracle-candidates.json",131072),):
        p = Path(root) / name
        if not p.is_file() or p.stat().st_size > maximum:
            raise ValueError("Required observer input missing/oversized: " + name)
        value = json.loads(p.read_bytes())
        if not isinstance(value, list) or not 1 <= len(value) <= 128:
            raise ValueError("Invalid observer catalogue")
    if (Path(root) / "observation-area.json").exists():
        p = Path(root) / "assessment-language.json"
        if not p.is_file() or p.stat().st_size > 4096:
            raise ValueError("Missing bounded assessment-language observer input")
        value = json.loads(p.read_bytes())
        if not isinstance(value, dict) or len(value) != 5 or not all(isinstance(t, str) and t for t in value.values()):
            raise ValueError("Malformed assessment-language observer input")
    return True
