"""One persistent real client; setup is fenced from the activity being measured."""
from __future__ import annotations

from collections import Counter
import contextlib
import json
import math
from pathlib import Path
import re
import shutil
import time
import urllib.request
import uuid

import navigation

from suite import Harness, ROOT, UUID_PATTERN, atomic, read, require, reconnect_players
from runtime import SDK, digest
from .generator import COMMANDS, LOG, OAK, BIRCH, STICK, AXE, TABLE, blueprint
from .oracle import candidates, requirements, rotated_cells

AREA = {"minX": 63, "footY": 208, "minZ": 63, "maxX": 84, "maxZ": 78, "maxObservationDy": 2}
POSITIONS = [([70, 208, 69], [77, 208, 71], [75, 208, 73]),
             ([73, 208, 67], [79, 208, 72], [76, 208, 75]),
             ([68, 208, 72], [76, 208, 70], [79, 208, 74])]
LO, HI = [70, 208, 65], [77, 208, 65]


def coord(value):
    return " ".join(map(str, value))


class BoundaryMissed(RuntimeError):
    pass


class AdmissionBlocked(RuntimeError):
    pass


class CraftingHarness(Harness):
    def __init__(self, root, baseline):
        super().__init__(root, "compose", None)
        atomic(self.root / "observation-area.json", AREA)
        navigation.LEVEL = AREA["footY"]
        atomic(self.root / "oracle-candidates.json", candidates(SDK / "minecraft-client.jar"))
        self.env["COGNITIVECRAFT_E2E_SNAPSHOT_TICKS"] = "1"
        self.subjects, self.artifacts, self.admissions = [], {}, []
        self.legacy_entity = None
        self.current_case = None
        self.trace_tick = None
        self.baseline = baseline
        self.records = None
        self.case_attempted = False
        self.case_requests = []
        self.last_request = None
        metadata = read(self.root / "metadata.json")
        metadata.update(productionBaseline=baseline, clientLimit=1, campaign="crafting",
                        setupAuthority="Only before tasks; vanilla clock/save/stop during recovery")
        atomic(self.root / "metadata.json", metadata)

    def snapshot(self):
        value = super().snapshot()
        if value and self.records and value.get("gameTick") != self.trace_tick:
            self.trace_tick = value.get("gameTick")
            with (self.records / "timeline.jsonl").open("a") as output:
                output.write(json.dumps(value, separators=(",", ":")) + "\n")
        return value

    def screenshot(self, player, checkpoint, chat=False):
        require(len(self.screenshots) < 1600, "Campaign native frame bound exceeded")
        name = re.sub(r"[^A-Za-z0-9_-]", "-", f"{self.family}-{len(self.screenshots):04d}-{checkpoint}")[:150]
        captured = self.action(player, "screenshot", name=name, chat=chat)
        path = self.root / captured["path"]
        data = path.read_bytes()
        require(data[:8] == b"\x89PNG\r\n\x1a\n" and len(data) > 1024, "Incomplete native frame")
        entry = {**captured, "family": self.family, "sha256": digest(path), "time": time.time(),
                 "nativeClientFrame": True, "checkpoint": checkpoint}
        self.screenshots.append(entry)
        atomic(self.root / "screenshots.json", {"schema": 1, "captures": self.screenshots})
        return entry

    def clock(self, command):
        require(re.fullmatch(r"tick (?:freeze|unfreeze|step 1|rate (?:1|2|20))", command), "Vanilla clock only")
        request = {"id": str(uuid.uuid4()), "commands": [command]}
        atomic(self.root / "server/control.json", request)
        self.wait(lambda: (v := read(self.root / "server/control-ack.json")) and v["id"] == request["id"],
                  "Vanilla clock acknowledgement", 20)
        self.event("crafting-clock", request)

    def setup(self, two=False, legacy=False):
        require(not two and not legacy, "The campaign defaults to one actual client")
        self.console(["op PlayerA", "gamemode spectator PlayerA", "tp PlayerA 66.5 208 69.5"])
        self.wait(lambda: self.snapshot() and "84,208,78" in self.snapshot()["blocks"], "Client loaded campaign chunks", 40)
        self.console(["gamerule minecraft:mob_griefing true", "gamerule minecraft:do_mob_spawning false",
                      "gamerule minecraft:random_tick_speed 0", "gamerule minecraft:do_daylight_cycle false",
                      "gamerule minecraft:block_drops true", "time set day",
                      "fill 62 207 62 85 207 80 minecraft:stone", "fill 62 206 62 85 206 80 minecraft:sea_lantern",
                      "fill 62 208 62 85 213 80 minecraft:air",
                      "fill 62 208 62 85 213 62 minecraft:glass", "fill 62 208 80 85 213 80 minecraft:glass",
                      "fill 62 208 62 62 213 80 minecraft:glass", "fill 85 208 62 85 213 80 minecraft:glass",
                      "fill 62 214 62 85 214 80 minecraft:glass",
                      "summon minecraft:villager 66.5 208 66.5 {PersistenceRequired:1b}",
                      "summon minecraft:villager 80.5 208 66.5 {PersistenceRequired:1b}",
                      "tp PlayerA 66.5 208 69.5 180 0", "spawnpoint PlayerA 66 208 69", "gamemode survival PlayerA"])
        self.wait(lambda: len(self.snapshot()["actors"]) == 2, "Two normal adult actors, one player")

    def use_actor(self, index):
        self.citizens["PlayerA"], self.entities["PlayerA"] = self.subjects[index]

    def inventory(self):
        return next(a["inventory"] for a in self.snapshot()["actors"] if a["uuid"] == self.entities["PlayerA"])

    def probe(self, case):
        recipe = self.recipes[case["recipe"]]
        value = {"id": case["id"], "recipe": case["recipe"], "width": recipe["width"],
                 "height": recipe["height"], "grid": case["grid"]}
        atomic(self.root / "oracle-request.json", value)
        result = self.wait(lambda: (r if (r := read(self.root / "oracle-response.json")) and r["id"] == case["id"] else None),
                           "Independent running-game recipe match", 20)
        require(result["worldEffects"] == 0 and result["matches"], "Generated substitution is not a valid vanilla recipe")
        return result

    def stock(self, position, contents, kind="chest", layout="packed"):
        require(not self.actions_active, "Stock setup is forbidden while measured work is active")
        rows = []
        for item, quantity in contents.items():
            while quantity:
                take = min(quantity, 1 if layout == "split" else 64)
                rows.append((item, take)); quantity -= take
        require(len(rows) <= 27, "Generated fixture exceeds native container capacity")
        slots = list(range(27)) if layout != "last" else list(reversed(range(27)))
        stacks = [f'{{Slot:{slot}b,id:"{item}",count:{n}}}' for slot, (item, n) in zip(slots, rows)]
        self.console(["setblock " + coord(position) + " minecraft:" + kind,
                      "data merge block " + coord(position) + " {Items:[" + ",".join(stacks) + "]}"])
        self.wait(lambda: self.snapshot()["containers"].get(",".join(map(str, position))) ==
                  {k: v for k, v in contents.items() if v},
                  "Measured physical fixture stock", 20)

    def actor_stock(self, contents, entity=None):
        entity = entity or self.entities["PlayerA"]
        stacks = [f'{{id:"{item}",count:{count}}}' for item, count in contents.items() if count]
        self.console(["data modify entity " + entity + " Inventory set value [" + ",".join(stacks) + "]"])
        self.wait(lambda: next(a["inventory"] for a in self.snapshot()["actors"] if a["uuid"] == entity) ==
                  {k: v for k, v in contents.items() if v}, "Measured actor input custody", 20)

    def prepare(self, case, expected=None):
        require(not self.actions_active, "Previous work must be fenced before setup")
        self.use_actor(case["actor"])
        self.source, self.destination, self.facility = [list(x) for x in POSITIONS[case["location"]]]
        self.console(["fill 63 208 63 84 211 78 minecraft:air", "fill 63 207 63 84 207 78 minecraft:stone",
                      "kill @e[type=minecraft:item,x=63,y=207,z=63,dx=21,dy=5,dz=15]",
                      "kill @e[type=minecraft:armor_stand,x=63,y=207,z=63,dx=21,dy=5,dz=15]",
                      "tp PlayerA 66.5 208 69.5 180 0", "clear PlayerA"] +
                     ["tp " + entity + " " + coord([66.5 + i * 14, 208, 67.5]) for i, (_, entity) in enumerate(self.subjects)])
        for _, entity in self.subjects:
            self.actor_stock({}, entity)
        if self.legacy_entity:
            self.actor_stock({}, self.legacy_entity)
        self.stock(self.destination, {}, case["container"])
        self.console(["setblock " + coord(self.facility) + " minecraft:crafting_table"])
        stock = {"minecraft:cobblestone": 2}
        self.held, self.distributed = {}, {}
        if case["mode"] == "recipe":
            stock.update({item: n + case.get("surplus", 0) for item, n in expected["consumed"].items()})
        else:
            q = case.get("quantity", 1)
            product_item = case.get("output", AXE)
            distribution = case.get("distribution", "ready")
            if product_item == BIRCH:
                stock["minecraft:birch_log"] = (q + 3) // 4
            elif product_item == TABLE:
                stock[OAK] = 4 * q
            else:
                stock.update({OAK: 3 * q, STICK: 2 * q})
            if distribution in {"logs", "partial", "gather", "insufficient"}:
                stock = {LOG: 8 if distribution == "logs" else 2 if distribution == "partial" else 0,
                         OAK: 1 if distribution == "partial" else 0}
                if product_item == BIRCH:
                    stock = {"minecraft:birch_log": 0 if distribution in {"gather", "insufficient"} else (q + 3) // 4}
            if distribution == "sticks":
                stock = {LOG: 4, STICK: 2 * q}
            if distribution == "held":
                self.held = {STICK: 2 * q}; stock[STICK] = 0
            if distribution == "split-containers":
                self.distributed = {STICK: 2 * q}; stock[STICK] = 0
            if case["mode"] == "facility":
                state = case["facility"]
                self.console(["setblock " + coord(self.facility) + " minecraft:air"])
                stock = {OAK: 3, STICK: 2, TABLE: 1}
                if state in {"produce-item", "raw", "absent"}:
                    stock[TABLE] = 0
                    stock[OAK] = 9 if state == "produce-item" else 0
                if state == "existing":
                    self.console(["setblock " + coord(self.facility) + " minecraft:crafting_table"])
                if state == "occupied":
                    self.console(["setblock " + coord(self.facility) + " minecraft:stone"])
                if state == "unsupported-floor":
                    self.console(["setblock " + coord([self.facility[0], 207, self.facility[2]]) + " minecraft:air"])
                if state == "entity-collision":
                    self.console(["summon minecraft:armor_stand " + coord([self.facility[0]+.5, 208, self.facility[2]+.5]) +
                                  " {NoGravity:1b,Invulnerable:1b}"])
                if state == "actor-collision":
                    self.console(["tp " + self.entities["PlayerA"] + " " + coord([self.facility[0]+.5, 208, self.facility[2]+.5])])
                if state == "held-item":
                    stock[TABLE] = 0; self.held = {TABLE: 1}
            if case["mode"] == "recovery" and case["boundary"] == "after-placement":
                stock = {TABLE: 1, OAK: 3, STICK: 2}
                self.console(["setblock " + coord(self.facility) + " minecraft:air"])
            if case["mode"] == "blueprint":
                outstanding = max(0, len(case["body"]["cells"]) - case["completed"])
                stock = ({OAK: outstanding} if distribution == "ready" else
                         {LOG: (outstanding + 3) // 4} if distribution == "logs" else
                         {OAK: min(2, outstanding), LOG: (max(0, outstanding-2)+3)//4} if distribution == "partial" else {})
                self.destination = self.source if not case["separateSource"] else self.destination
                self.blueprint_origin = [76, 208, 75]
                cells = rotated_cells(case["body"], self.blueprint_origin, case["rotation"])
                self.console(["setblock " + coord(cell[:3]) + " " + case["body"]["palette"][cell[3]]["block"]
                              for cell in cells[:case["completed"]]])
                self.blueprint_cells = cells
            if case["mode"] == "legacy":
                stock = {}
        stock = {k: v for k, v in stock.items() if v}
        # Slot splitting keeps independent item identity while fitting the finite container.
        layout = case["layout"] if sum(stock.values()) <= 27 else "last"
        self.stock(self.source, stock, case["container"], layout)
        if self.held:
            self.actor_stock(self.held)
        if self.distributed:
            self.stock([81, 208, 76], self.distributed, "barrel")
        logs = case.get("worldLogs", 0)
        if logs:
            material = "minecraft:birch_log" if case.get("output") == BIRCH else LOG
            self.console(["setblock " + coord([LO[0]+i, 208, LO[2]]) + " " + material for i in range(logs)])
        self.quiet(3)
        return self.snapshot()

    def command_text(self, case):
        actor = self.citizens["PlayerA"]
        head = "/aivillage kernel "
        output = case.get("output", AXE)
        mode = case["mode"]
        if mode == "recipe":
            return head + COMMANDS[output] + " " + actor + " " + str(case["quantity"]) + " " + coord(
                self.source + self.destination + (self.facility if output == AXE else []))
        if mode == "facility":
            if output == TABLE:
                return head + "place-table " + actor + " " + coord(self.source + self.facility)
            return head + "provision-axe " + actor + " " + coord(self.source+self.destination+self.facility+LO+HI)
        if mode == "recovery" and case["boundary"] == "after-placement":
            return head + "place-table " + actor + " " + coord(self.source+self.facility)
        name = "compose-axes" if output == AXE else "compose-tables" if output == TABLE else "compose-birch-planks"
        return head + name + " " + actor + " " + str(case["quantity"]) + " " + coord(
            self.source+self.destination+(self.facility if output == AXE else [])+LO+HI)

    def submit_command(self, text):
        self.actions_active = True
        self.case_attempted = True
        pending = {"command": text, "feedback": None, "accepted": False, "kind": None, "identity": None}
        self.last_request = pending
        self.case_requests.append(pending)
        answer = self.command("PlayerA", text, "run=|job=|accepted=false|Denied|unavailable", 40)
        # Only a refused zero-work publication may be resubmitted; never accepted work.
        deadline = time.monotonic() + 20
        rejected = []
        while "accepted=false reason=BUDGET_EXHAUSTED" in answer and time.monotonic() < deadline:
            rejected.append(answer)
            time.sleep(.3)
            answer = self.command("PlayerA", text, "run=|job=|accepted=false|Denied|unavailable", 40)
        match = re.search("(run|job)=(" + UUID_PATTERN + ")", answer)
        result = {"command": text, "feedback": answer, "accepted": "accepted=true" in answer,
                  "kind": match[1] if match else None, "identity": match[2] if match else None,
                  "publicationRefusals": rejected}
        pending.update(result)
        self.event("crafting-player-request", result)
        return pending

    def graph(self, identity):
        all_jobs = self.snapshot().get("knownPlayers", {}).get("PlayerA", {}).get("jobs", {})
        todo, visited, result = [identity], set(), []
        while todo:
            current = todo.pop(0)
            if current in visited or current not in all_jobs:
                continue
            visited.add(current); node = all_jobs[current]; result.append(node); todo.extend(node["children"])
        return result

    def views(self, request):
        if request["kind"] == "run":
            view = self.run_view("PlayerA", request["identity"])
            return [view] if view else []
        return self.graph(request["identity"])

    def receipts_for(self, request):
        rows = []
        for value in self.views(request):
            for attempt in value.get("attempts", [value]):
                rows.extend(attempt.get("resourceReceipts", []))
        unique = {}
        for value in rows:
            key = json.dumps(value.get("id") or value, sort_keys=True)
            unique[key] = value
        return list(unique.values())

    def finish_request(self, request, timeout=200):
        if not request["accepted"]:
            self.actions_active = False
            return {"completed": False, "terminal": True, "views": self.views(request)}
        def terminal():
            rows = self.views(request)
            if not rows:
                return None
            root = rows[0]
            if request["kind"] == "run":
                return rows if root.get("phase") == "TERMINAL" else None
            return rows if root.get("state") in {"SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED"} else None
        rows = self.wait(terminal, "Actual player request terminal evidence", timeout)
        root = rows[0]
        completed = root.get("state") == "SUCCEEDED" if request["kind"] == "job" else root.get("marker", {}).get("outcome") in {"SUCCEEDED", "ADMITTED"}
        self.actions_active = False
        return {"completed": completed, "terminal": True, "views": rows, "receipts": self.receipts_for(request)}

    def bootstrap(self):
        # No seeded executable bodies. The only inference phase is genuine method admission.
        self.env["COGNITIVECRAFT_OLLAMA_MODEL"] = "qwen3:4b-instruct-2507-q4_K_M"
        self.compose("build", "minecraft")
        self.compose("up", "-d", "ollama")
        self.compose("exec", "-T", "ollama", "ollama", "pull", self.env["COGNITIVECRAFT_OLLAMA_MODEL"])
        self.fresh(two=False)
        for i in range(2):
            spawn = [66.5 + i*14, 208, 69.5]
            self.console(["tp PlayerA " + coord(spawn)])
            self.wait(lambda: math.dist(self.client_snapshot("PlayerA")["pos"], spawn) < .7,
                      "Connected player reached the distinct enrollment position", 30)
            self.enroll(name=["Ada", "Mira"][i])
            self.subjects.append((self.citizens["PlayerA"], self.entities["PlayerA"]))
        self.recipes = self.wait(lambda: read(self.root / "oracle-recipes.json"), "Live vanilla recipe catalogue", 30)["recipes"]
        self.command("PlayerA", "/aivillage kernel inference true", "admission=true")
        for output in [OAK, BIRCH, STICK, TABLE, AXE, "placement", "oak-acquisition", "birch-acquisition"]:
            row = {"capability": output, "status": "NOT_EXECUTED"}
            self.family = "ADMISSION-" + output.split(":")[-1]
            try:
                recipe = self.recipes.get(output)
                if recipe:
                    grid = ["" if i < 0 else (OAK if OAK in recipe["ingredients"][i] else
                            "minecraft:oak_log" if "minecraft:oak_log" in recipe["ingredients"][i] else
                            "minecraft:birch_log" if "minecraft:birch_log" in recipe["ingredients"][i] else recipe["ingredients"][i][0])
                            for i in recipe["slots"]]
                    case = {"id": self.family, "mode": "recipe", "output": output, "recipe": output,
                            "grid": grid, "quantity": recipe["count"], "surplus": 0,
                            "actor": 0, "location": 0, "layout": "packed", "container": "chest"}
                    expected = requirements(self.probe(case), case["quantity"])
                    self.prepare(case, expected)
                    text = self.command_text(case)
                else:
                    case = {"mode": "facility", "facility": "direct", "output": TABLE, "quantity": 1,
                            "actor": 0, "location": 0, "layout": "packed", "container": "chest"}
                    self.prepare(case)
                    if output == "placement":
                        text = self.command_text(case)
                    else:
                        material = LOG if output == "oak-acquisition" else "minecraft:birch_log"
                        self.console(["setblock " + coord(LO) + " " + material])
                        text = "/aivillage kernel gather-" + ("oak" if material == LOG else "birch") + " " + self.citizens["PlayerA"] + " 1 " + coord(LO+HI+self.destination)
                calls = self.model_calls()
                physical_before, client_before = self.snapshot(), self.client_snapshot("PlayerA")
                request = self.submit_command(text)
                result = self.finish_request(request, 650)
                require(result["completed"], "Genuine method admission did not complete")
                view = result["views"][0]
                require(view.get("marker", {}).get("outcome") == "ADMITTED" and view.get("resourceReceipts"), "Missing genuine admitted physical effects")
                require(self.model_calls()["generationCalls"] > calls["generationCalls"], "No measured generation call")
                from .invariants import totals, recipe_balance, composition_balance
                physical_after, client_after = self.snapshot(), self.client_snapshot("PlayerA")
                if recipe:
                    conserved, balance = recipe_balance(totals(physical_before, client_before),
                                                         totals(physical_after, client_after), expected)
                    require(conserved and self.container(self.destination, output) == case["quantity"],
                            "Admission receipts disagree with independently measured recipe effects")
                else:
                    conserved, balance = composition_balance(totals(physical_before, client_before),
                                                               totals(physical_after, client_after))
                    require(conserved, "Genuine admission lost or duplicated resources")
                    if output == "placement":
                        require(TABLE in physical_after["blocks"].get(",".join(map(str, self.facility)), "") and
                                self.container(self.source, TABLE) == 0, "No physical supplied-item placement")
                    else:
                        require(self.container(self.destination, material) == 1, "No physical gathered input")
                self.artifacts[output] = view["marker"]["artifactSha256"]
                row.update(status="PASS", request=request, result=result, callsBefore=calls, callsAfter=self.model_calls(),
                           before=physical_before, after=physical_after, conservation=balance)
                self.screenshot("PlayerA", "genuine-admission", chat=True)
            except Exception as error:
                row.update(status="BLOCKED_ADMISSION", error=repr(error))
                self.cleanup()
            self.admissions.append(row)
            atomic(self.root / "admissions.json", {"rows": self.admissions, "artifacts": self.artifacts})
        self.command("PlayerA", "/aivillage kernel inference false", "admission=false")
        self.compose("stop", "ollama")
        self.assert_backend_stopped()

    def assert_backend_stopped(self):
        try:
            urllib.request.urlopen("http://127.0.0.1:11434/api/version", timeout=2)
        except OSError:
            self.event("crafting-backend-unavailable", {"stopped": True})
        else:
            raise AdmissionBlocked("Generation backend unexpectedly remains available")

    def cleanup(self):
        with contextlib.suppress(Exception):
            self.clock("tick unfreeze"); self.clock("tick rate 20")
        state = super().snapshot() or {}
        for name, data in state.get("knownPlayers", {}).items():
            if name != "PlayerA":
                continue
            for rid, view in data.get("runs", {}).items():
                if view.get("phase") != "TERMINAL":
                    with contextlib.suppress(Exception):
                        self.command("PlayerA", "/aivillage kernel cancel " + rid, "cancel|CANCEL|denied|unavailable")
            for jid, value in data.get("jobs", {}).items():
                if value.get("state") not in {"SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED"} or value.get("allocation"):
                    with contextlib.suppress(Exception):
                        self.cancel_job("PlayerA", jid)
        self.actions_active = False
        self.quiet(10)
        def fenced():
            data = (super(CraftingHarness, self).snapshot() or {}).get("knownPlayers", {}).get("PlayerA", {})
            active_runs = [v for v in data.get("runs", {}).values() if v.get("phase") in
                           {"INTERPRETING", "RESOLVING", "RESEARCHING", "EXECUTING"}]
            active_jobs = [v for v in data.get("jobs", {}).values() if v.get("state") not in
                           {"SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED"}]
            return not active_runs and not active_jobs
        self.wait(fenced, "Previous case's real execution/cancellation fence", 40)
