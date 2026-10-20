"""Assessment-only fixtures and observations; production owners remain unchanged."""
from __future__ import annotations

import json
import re
import shutil
import time
import urllib.request
import uuid

import navigation
from suite import Harness, ROOT, UUID_PATTERN, atomic, read, require, reconnect_players

BASELINE = "895304eb4b7e2379c6b82f7cda49b7ab6c9e1ebf"
LOG = "minecraft:oak_log"
PLANK = "minecraft:oak_planks"
STICK = "minecraft:stick"
AXE = "minecraft:wooden_axe"
TABLE = "minecraft:crafting_table"
BIRCH = "minecraft:birch_log"
BIRCH_PLANK = "minecraft:birch_planks"
CAPS = {LOG: "deliver_oak_logs", PLANK: "produce_oak_planks", STICK: "produce_sticks",
        AXE: "produce_wooden_axes", TABLE: "produce_crafting_tables",
        "place": "place_crafting_table", BIRCH: "deliver_birch_logs",
        BIRCH_PLANK: "produce_birch_planks"}
COMMANDS = {LOG: "gather-oak", BIRCH: "gather-birch", PLANK: "make-planks",
            BIRCH_PLANK: "make-birch-planks", STICK: "make-sticks", AXE: "make-axes",
            TABLE: "make-tables", "place": "place-table"}


class AssessmentBlocked(RuntimeError):
    """An evaluation prerequisite is unavailable, distinct from gameplay failure."""


class Inconclusive(RuntimeError):
    """The requested dynamic boundary was not actually witnessed."""


def at(x, z, dy=0):
    return [64+x, 208+dy, 64+z]


def coord(value):
    return " ".join(map(str, value))


SRC, DST, FACILITY, SITE = at(10, 5), at(13, 8), at(12, 10), at(17, 11)
LO, HI = at(6, 2), at(13, 2)


class AssessmentHarness(Harness):
    def __init__(self, root, group):
        super().__init__(root, "compose", None)
        self.profile = "generalization-"+group
        self.group = group
        self.hashes = {}
        self.admissions = []
        self.last_sample = None
        self.sample_count = 0
        self.family = "GENERALIZATION-STARTUP"
        corpus = read(ROOT / "e2e/generalization/cases.json")
        atomic(self.root / "observation-area.json", corpus["assessmentArea"])
        atomic(self.root / "assessment-language.json", {r["id"]: r["requirement"]
               for r in corpus["scenarios"] if r["category"] == "language"})
        navigation.LEVEL = corpus["assessmentArea"]["footY"]
        self.env["COGNITIVECRAFT_E2E_SNAPSHOT_TICKS"] = "1"
        metadata = read(self.root / "metadata.json")
        metadata["assessment"] = {"productionBaseline": BASELINE,
                                  "area": corpus["assessmentArea"],
                                  "fixtureAuthority": "administrative setup only before each task",
                                  "dynamicMutations": "ordinary connected PlayerB actions"}
        atomic(self.root / "metadata.json", metadata)

    def setup(self, two=True, legacy=False):
        require(two and not legacy, "Assessment requires two genuine clients")
        # Load the relocated chunks before bounded fixture writes. Spectator mode
        # exists only during empty-world setup so players cannot fall before the
        # assessment platform is installed; survival is restored before enrollment.
        self.console(["op PlayerA", "op PlayerB", "gamemode spectator PlayerA",
                      "gamemode spectator PlayerB", "tp PlayerA 66.5 208 69.5",
                      "tp PlayerB 79.5 208 69.5"])
        self.wait(lambda: self.snapshot() and all(",".join(map(str, p)) in self.snapshot()["blocks"]
                  for p in (at(-1, -1), at(20, -1), at(-1, 14), at(20, 14))),
                  "Relocated chunks loaded by actual connected players before fixture construction", 30)
        self.console([
            "gamerule minecraft:mob_griefing true", "gamerule minecraft:do_mob_spawning false",
            "gamerule minecraft:random_tick_speed 0", "gamerule minecraft:do_daylight_cycle false",
            "gamerule minecraft:block_drops true", "time set day",
            "fill 62 207 62 85 207 80 minecraft:stone", "fill 62 206 62 85 206 80 minecraft:sea_lantern",
            "fill 62 208 62 85 213 80 minecraft:air",
            "fill 62 208 62 85 213 62 minecraft:glass", "fill 62 208 80 85 213 80 minecraft:glass",
            "fill 62 208 62 62 213 80 minecraft:glass", "fill 85 208 62 85 213 80 minecraft:glass",
            "fill 62 214 62 85 214 80 minecraft:glass",
            "summon minecraft:villager 66.5 208 66.5 {PersistenceRequired:1b}",
            "summon minecraft:villager 79.5 208 66.5 {PersistenceRequired:1b}",
            "op PlayerA", "op PlayerB", "tp PlayerA 66.5 208 69.5 180 0",
            "tp PlayerB 79.5 208 69.5 180 0", "spawnpoint PlayerA 66 208 69",
            "spawnpoint PlayerB 79 208 69", "gamemode survival PlayerA", "gamemode survival PlayerB"])
        self.wait(lambda: self.snapshot() and len(self.snapshot()["actors"]) == 2,
                  "Relocated assessment world and two normal-AI villagers", 40)
        require(all(a["alive"] and not a["baby"] and not a["noAI"]
                    for a in self.snapshot()["actors"]), "Normal adult villager AI required")

    def snapshot(self):
        state = super().snapshot()
        if state and getattr(self, "last_sample", None) != state.get("gameTick"):
            self.last_sample = state.get("gameTick")
            if getattr(self, "sample_count", 0) < 30000:
                self.sample_count += 1
                self.event("assessment-physical-sample", {"scenario": self.family,
                           "tick": state.get("gameTick"), "actors": state.get("actors"),
                           "containers": state.get("containers"), "drops": state.get("drops")})
        return state

    def console(self, lines, lifecycle=False):
        if lifecycle and all(re.fullmatch(r"tick (?:freeze|unfreeze|rate 20|step 1)", line)
                             for line in lines):
            request = {"id": str(uuid.uuid4()), "commands": lines}
            atomic(self.root / "server/control.json", request)
            self.wait(lambda: (v := read(self.root / "server/control-ack.json"))
                      and v["id"] == request["id"], "Assessment vanilla clock acknowledgement", 20)
            self.event("assessment-clock-control", request)
            return
        return super().console(lines, lifecycle)

    def inventory(self, player="PlayerA"):
        return next(a["inventory"] for a in self.snapshot()["actors"]
                    if a["uuid"] == self.entities[player]).copy()

    def block(self, pos):
        return self.snapshot()["blocks"].get(",".join(map(str, pos)), "UNKNOWN")

    def stock_fixture(self, values, source=SRC, kind="chest", slots=None):
        require(not self.actions_active, "Stock fixtures may not run during a task")
        rows = slots if slots is not None else [(i, item, q) for i, (item, q)
                                               in enumerate(values.items()) if q]
        nbt = "{Items:["+",".join(f'{{Slot:{i}b,id:"{item}",count:{q}}}'
                                 for i, item, q in rows)+"]}"
        self.console(["setblock "+coord(source)+" minecraft:"+kind,
                      "data merge block "+coord(source)+" "+nbt])
        totals = {}
        for _, item, q in rows:
            totals[item] = totals.get(item, 0)+q
        self.wait(lambda: all(self.container(source, item) == q for item, q in totals.items()),
                  "Acknowledged relocated actual stock", 20)

    def reset(self, values=None, *, source=SRC, destination=DST, table=True,
              source_kind="chest", destination_kind="chest", slots=None, actor=at(3, 4)):
        self.actions_active = False
        self.console(["fill 63 208 63 84 212 78 minecraft:air",
                      "fill 63 207 63 84 207 78 minecraft:stone",
                      "tp "+self.entities["PlayerA"]+" "+coord([actor[0]+.5, actor[1], actor[2]+.5]),
                      "tp "+self.entities["PlayerB"]+" 80.5 208 68.5",
                      "tp PlayerA 66.5 208 69.5 180 0", "tp PlayerB 80.5 208 69.5 180 0",
                      "clear PlayerB", "clear PlayerA"])
        self.stock_fixture(values or {}, source, source_kind, slots)
        if destination != source:
            self.stock_fixture({}, destination, destination_kind)
        if table:
            self.console(["setblock "+coord(FACILITY)+" minecraft:crafting_table"])
        self.quiet(5)
        return self.snapshot()

    def logs(self, positions=None, item=LOG):
        positions = positions or [at(x, 2) for x in range(6, 14)]
        self.console(["setblock "+coord(p)+" "+item for p in positions])
        self.wait(lambda: all(item in self.block(p) for p in positions), "Real selected log arrangement", 20)

    def submit(self, item, quantity=1, *, source=SRC, destination=DST, table=FACILITY,
               player="PlayerA", minimum=LO, maximum=HI, target=SITE, acquire=False):
        cap = "cognitivecraft:"+CAPS[item]
        if not acquire and cap not in self.hashes:
            raise AssessmentBlocked("Genuine independent method was not admitted: "+cap)
        tail = minimum+maximum+destination if item in (LOG, BIRCH) else source+target if item == "place" else source+destination+(table if item == AXE else [])
        text = "/aivillage kernel "+COMMANDS[item]+" "+self.citizens[player]+" "+("" if item == "place" else str(quantity)+" ")+coord(tail)
        answer = self.command(player, text, "run="+UUID_PATTERN)
        if "accepted=true" not in answer:
            raise AssertionError("Request refused before execution: "+answer)
        self.actions_active = True
        return re.search("run=("+UUID_PATTERN+")", answer).group(1)

    def finish_run(self, run, player="PlayerA", *, admitted=False, success=True):
        view = self.terminal(player, run, 650 if admitted else 200)
        if success:
            require(view.get("marker", {}).get("outcome") == ("ADMITTED" if admitted else "SUCCEEDED"),
                    "Physical execution did not succeed: "+json.dumps(view))
        self.actions_active = False
        return view

    def verify_recipe(self, item, quantity, stock, *, source=SRC, destination=DST,
                      table=FACILITY, player="PlayerA", source_kind="chest", slots=None):
        self.reset(stock, source=source, destination=destination, table=item == AXE,
                   source_kind=source_kind, slots=slots)
        before = self.snapshot(); inv = self.inventory(player); calls = self.model_calls()
        rid = self.submit(item, quantity, source=source, destination=destination, table=table, player=player)
        view = self.finish_run(rid, player)
        require(view["marker"]["artifactSha256"] == self.hashes["cognitivecraft:"+CAPS[item]],
                "Method identity changed with new bindings")
        rows = view.get("resourceReceipts", [])
        sums = {s: sum(r["quantity"] for r in rows if r["stage"] == s)
                for s in ("WITHDRAW", "CRAFT", "DEPOSIT")}
        batch = quantity if item in (AXE, TABLE) else (quantity+3)//4
        produced = quantity if item in (AXE, TABLE) else batch*4
        inputs = batch*(5 if item == AXE else 4 if item == TABLE else 2 if item == STICK else 1)
        require(sums == {"WITHDRAW": inputs, "CRAFT": produced, "DEPOSIT": quantity},
                "Installed recipe receipts do not conserve requested inputs/output")
        input_item = PLANK if item in (AXE, TABLE, STICK) else BIRCH if item == BIRCH_PLANK else LOG
        expected_input = batch*(3 if item == AXE else 4 if item == TABLE else 2 if item == STICK else 1)
        initial = before["containers"][",".join(map(str, source))]
        require(self.container(source, input_item) == initial.get(input_item, 0)-expected_input,
                "Physical eligible input mismatch")
        if item == AXE:
            require(self.container(source, STICK) == initial.get(STICK, 0)-2*quantity, "Axe secondary input mismatch")
        old = before["containers"].get(",".join(map(str, destination)), {}).get(item, 0)
        require(self.container(destination, item) == old+quantity, "Actual output container delta mismatch")
        require(self.inventory(player).get(item, 0) == inv.get(item, 0)+produced-quantity,
                "Actual retained batch surplus mismatch")
        eligible = {input_item, STICK} if item == AXE else {input_item}
        for foreign, count in initial.items():
            if foreign not in eligible and foreign != item:
                require(self.container(source, foreign) == count, "Irrelevant stock consumed: "+foreign)
        require(self.model_calls() == calls, "Known method invoked a model")
        actual = self.menu_count(player, destination, item)
        require(actual == old+quantity, "Connected vanilla menu differs from actual stock")
        return {"run": rid, "before": before, "view": view, "receipts": sums,
                "physicalAfter": self.snapshot(),
                "source": source, "destination": destination, "inventoryBefore": inv,
                "inventoryAfter": self.inventory(player), "clientMenuCount": actual,
                "modelCallsBefore": calls, "modelCallsAfter": self.model_calls()}

    def job_graph(self, jid, player="PlayerA"):
        state = self.snapshot()["knownPlayers"][player]["jobs"]
        out, todo, seen = [], [jid], set()
        while todo:
            current = todo.pop(0)
            if current in seen or current not in state:
                continue
            seen.add(current); node = state[current]; out.append(node); todo.extend(node["children"])
        return out

    def complete_job(self, jid, player="PlayerA"):
        def result():
            nodes = self.job_graph(jid, player)
            if any(n["state"] in ("FAILED", "CANCELLED", "INTERRUPTED") for n in nodes):
                raise AssertionError("Dependency graph stopped: "+json.dumps(nodes))
            return nodes if nodes and nodes[0]["state"] == "SUCCEEDED" else None
        nodes = self.wait(result, "Ordinary deterministic prerequisite Jobs", 260)
        for node in nodes:
            method = (node.get("preparation") or {}).get("method")
            if method:
                cap = node["request"]["request"]["capability"]["name"]
                require(method["sha256"] == self.hashes.get(cap), "Composed node changed admitted method identity")
        self.actions_active = False
        return nodes

    def compose_axes(self, quantity, stock, *, source=SRC, destination=DST, table=FACILITY,
                     player="PlayerA", acquisition=0):
        self.reset(stock, source=source, destination=destination)
        if table != FACILITY:
            self.console(["setblock "+coord(FACILITY)+" minecraft:air", "setblock "+coord(table)+" minecraft:crafting_table"])
        if acquisition:
            self.logs([at(x, 2) for x in range(6, 6+acquisition)])
        before = self.snapshot(); calls = self.model_calls()
        answer = self.command(player, "/aivillage kernel compose-axes "+self.citizens[player]+" "+str(quantity)+" "+coord(source+destination+table+LO+HI), "job="+UUID_PATTERN)
        require("accepted=true" in answer, "Supported composition refused: "+answer)
        self.actions_active = True
        jid = re.search("job=("+UUID_PATTERN+")", answer).group(1)
        graph = self.complete_job(jid, player)
        require(graph[0]["fulfilled"] == quantity and self.container(destination, AXE) == quantity,
                "Composition credited old or missing axes")
        require(self.model_calls() == calls, "Known prerequisite graph invoked inference")
        return {"job": jid, "before": before, "graph": graph, "source": source,
                "physicalAfter": self.snapshot(),
                "destination": destination, "modelCallsBefore": calls,
                "modelCallsAfter": self.model_calls(), "inventoryAfter": self.inventory(player)}

    def cleanup(self):
        self.actions_active = False
        if "server" in self.booted:
            self.console(["tick unfreeze"], lifecycle=True)
        state = self.snapshot() or {}
        for player, data in state.get("players", {}).items():
            for rid, view in data.get("runs", {}).items():
                if view.get("phase") in ("EXECUTING", "RESEARCHING", "RESOLVING", "INTERPRETING"):
                    try:
                        self.command(player, "/aivillage kernel cancel "+rid, "cancel|CANCEL|denied|unavailable", 15)
                    except Exception as error:
                        self.event("assessment-cleanup-error", {"run": rid, "reason": str(error)})
            for jid, node in data.get("jobs", {}).items():
                if node.get("state") not in ("SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED"):
                    try:
                        self.cancel_job(player, jid)
                    except Exception as error:
                        self.event("assessment-cleanup-error", {"job": jid, "reason": str(error)})
        self.quiet(10)

    def bootstrap(self, items):
        shutil.copytree(ROOT / "build/needle", self.root / "needle", dirs_exist_ok=True)
        self.env.update(COGNITIVECRAFT_OLLAMA_MODEL="qwen3:4b-instruct-2507-q4_K_M",
                        COGNITIVECRAFT_NEEDLE_DIR="/evidence/needle")
        self.compose("build", "minecraft")
        self.compose("up", "-d", "ollama")
        self.compose("exec", "-T", "ollama", "ollama", "pull", self.env["COGNITIVECRAFT_OLLAMA_MODEL"])
        with urllib.request.urlopen("http://127.0.0.1:11434/api/tags", timeout=20) as response:
            models = json.load(response)
        atomic(self.root / "models.json", models)
        self.fresh(two=True); self.enroll(); self.enroll("PlayerB", "Mira")
        self.command("PlayerA", "/aivillage kernel inference true", "admission=true")
        supplied = {LOG: {}, PLANK: {LOG: 1}, STICK: {PLANK: 2}, AXE: {PLANK: 3, STICK: 2},
                    TABLE: {PLANK: 4}, "place": {TABLE: 1}, BIRCH: {}, BIRCH_PLANK: {BIRCH: 1}}
        for item in items:
            self.family = "ADMISSION-"+CAPS[item]
            try:
                self.reset(supplied[item]); self.logs(item=BIRCH if item == BIRCH else LOG)
                calls = self.model_calls(); rid = self.submit(item, 1 if item in (LOG, BIRCH, AXE, TABLE, "place") else 4, acquire=True)
                view = self.finish_run(rid, admitted=True)
                require(view.get("resourceReceipts") and self.model_calls()["generationCalls"] > calls["generationCalls"],
                        "Admission lacked actual model invocation and physical effects")
                self.hashes["cognitivecraft:"+CAPS[item]] = view["marker"]["artifactSha256"]
                self.admissions.append({"item": item, "run": rid, "view": view, "callsBefore": calls,
                                        "callsAfter": self.model_calls(), "status": "PASS"})
                self.screenshot("PlayerA", "independent-genuine-admission", chat=True)
            except Exception as error:
                self.admissions.append({"item": item, "status": "FAIL", "reason": str(error)})
                self.cleanup()
            atomic(self.root / "independent-admissions.json", {"admissions": self.admissions, "artifacts": self.hashes})
        self.command("PlayerA", "/aivillage kernel inference false", "admission=false")
        self.compose("stop", "ollama")
        try:
            urllib.request.urlopen("http://127.0.0.1:11434/api/version", timeout=2)
        except OSError:
            self.event("assessment-generator-unavailable", {"actualBackendStopped": True})
        else:
            raise AssessmentBlocked("Generator still responds after stop")
