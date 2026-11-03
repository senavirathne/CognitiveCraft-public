"""Negative controls for the PG00 file oracle. Fixtures here confer no gameplay credit."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from verify_evidence import CONNECTION, LEGACY, PROFILE, png_size, verify


SOURCE = "a" * 40
ALICE = "40bf42a2-f298-49da-bf16-05308ac3b429"
BOB = "71e52aa7-07f1-4e73-ac09-f6c720e521b5"
BAKER = "747619be-b538-4143-9308-72d52a6dca2c"
SPEECH = "[Baker] I'm available"


def write(root, name, obj):
    p = root / name
    p.parent.mkdir(parents=True, exist_ok=True)
    if p.suffix == ".jsonl":
        p.write_text("".join(json.dumps(row) + "\n" for row in obj))
    else:
        p.write_text(json.dumps(obj))


def packet(player, timestamp, kind, **data):
    return {"player": player, "time": timestamp, "kind": kind, "data": data}


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.jar = self.root / "sample.jar"
        self.jar.write_bytes(b"unit-fixture-only")
        sha = hashlib.sha256(self.jar.read_bytes()).hexdigest()
        write(self.root, "metadata.json", {"sourceCommit": SOURCE, "reviewedHeadCommit": SOURCE,
              "repository": "senavirathne/CognitiveCraft-public", "sourceTree": "b" * 40,
              "topology": "compose", "releaseSha256": sha, "binaries": {"production": {"sha256": sha}},
              "ci": {"GITHUB_SHA": SOURCE, "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1"}})
        for name, kind in (("server", "server"), ("clients/PlayerA", "client"), ("clients/PlayerB", "client")):
            write(self.root, name + "/process.json",
                  {"kind": kind, "releaseSha256": sha, "mods": [{"sha256": sha}]})

        self.a = [packet("PlayerA", 1100, "play", uuid=ALICE), packet("PlayerA", 1400, "disconnect"),
                  packet("PlayerA", 1800, "play", uuid=ALICE),
                  packet("PlayerA", 2100, "feedback", text="citizens=1 Ada"),
                  packet("PlayerA", 4300, "suggestion-request", text="/aivillage status ", transaction=3),
                  packet("PlayerA", 4500, "suggestion-response", transaction=3, values=["Baker"]),
                  packet("PlayerA", 5300, "feedback", text="food: Baker"),
                  packet("PlayerA", 5500, "menu-content", menu=4, slots=[{"item": "minecraft:bread", "count": 8}])]
        self.b = [packet("PlayerB", 4400, "suggestion-request", text="/aivillage status ", transaction=2),
                  packet("PlayerB", 4550, "suggestion-response", transaction=2, values=[]),
                  packet("PlayerB", 4700, "feedback", text="Only the owner or an operator may do that"),
                  packet("PlayerB", 6500, "feedback", text=SPEECH)]
        cases, captures = [], []
        samples = Path(__file__).resolve().parents[2] / "e2e/evidence/screenshots"
        for family, checkpoint, screen, timestamp, image in (
                (CONNECTION, "named-citizen-after-reconnect", "ChatScreen", 3000, "smoke-named-citizen-after-reconnect.png"),
                (LEGACY, "synchronized-container-8", "ContainerScreen", 6000, "offline-reuse-synchronized-container-6.png")):
            data = (samples / image).read_bytes()
            stem = f"{family}-001-PlayerA-{checkpoint}"
            name = f"clients/PlayerA/screenshots/{stem}.png"
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            cap = {"family": family, "checkpoint": checkpoint, "screen": screen, "path": name,
                   "nativeClientFrame": True, "tick": 19, "time": timestamp / 1000, "player": "PlayerA",
                   "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data), "width": 800,
                   "height": 600, "sourceCommit": SOURCE, "releaseSha256": sha}
            self.a.append(packet("PlayerA", timestamp, "screenshot", name=stem, path="/evidence/" + name, tick=19))
            proof = {"players": {"PlayerA": ALICE}} if family == CONNECTION else {
                "bread": 8, "entity": BAKER, "nearbySpeech": SPEECH, "outsideSpeechDistance": 36}
            cases.append({"id": family, "status": "PASS", "evidence": proof, "screenshots": [cap]})
            captures.append(cap)

        self.supervisor = [{"time": 1, "kind": "family-start", "data": {"id": CONNECTION}},
                           {"time": 3.5, "kind": "family-result", "data": cases[0]},
                           {"time": 4, "kind": "family-start", "data": {"id": LEGACY}},
                           {"time": 5.31, "kind": "command", "data": {"player": "PlayerA",
                            "text": "/aivillage food Baker", "feedback": "food: Baker"}},
                           {"time": 7, "kind": "family-result", "data": cases[1]}]
        write(self.root, "screenshots.json", {"captures": captures})
        write(self.root, "results.json", {"profile": PROFILE, "results": cases})
        (self.root / "junit.xml").write_text(f'<testsuite><testcase name="{CONNECTION}"/>'
                                             f'<testcase name="{LEGACY}"/></testsuite>')
        write(self.root, "supervisor.jsonl", self.supervisor)
        write(self.root, "clients/PlayerA/events.jsonl", sorted(self.a, key=lambda e: e["time"]))
        write(self.root, "clients/PlayerB/events.jsonl", self.b)
        self.menu = {"time": 5900, "state": {"play": True, "singleplayer": False, "uuid": ALICE,
                     "menu": 4, "slots": [{"slot": 0, "playerInventory": False, "item": "minecraft:bread", "count": 8}]}}
        write(self.root, "clients/PlayerA/observations.jsonl", [self.menu])
        write(self.root, "clients/PlayerB/observations.jsonl",
              [{"time": 4700, "state": {"play": True, "singleplayer": False, "uuid": BOB}}])
        self.server = [{"time": t, "players": {"PlayerA": {"uuid": ALICE}, "PlayerB": {"uuid": BOB}},
                       "actors": [{"uuid": BAKER, "alive": True, "noAI": False}],
                       "containers": {"9,201,7": {"minecraft:bread": bread}}} for t, bread in ((5100, 0), (5800, 8))]
        write(self.root, "server/observations.jsonl", self.server)

    def checked(self):
        return verify(self.root, SOURCE, self.jar, 123, 1)

    def test_reconciles_all_three_producers(self):
        self.assertEqual(self.checked()["bread"], 8)

    def test_wrong_release_or_attempt(self):
        with self.assertRaises(ValueError):
            verify(self.root, SOURCE, self.jar, 123, 2)
        self.jar.write_bytes(b"wrong")
        with self.assertRaises(ValueError):
            self.checked()

    def test_render_bytes_must_decode_with_valid_crc(self):
        frame = next(self.root.glob("clients/PlayerA/screenshots/*.png"))
        data = bytearray(frame.read_bytes())
        data[len(data) // 2] ^= 1
        with self.assertRaises(ValueError):
            png_size(data)
        frame.write_bytes(bytes(data))
        with self.assertRaises(ValueError):
            self.checked()

    def test_screenshot_without_native_render_event(self):
        write(self.root, "clients/PlayerA/events.jsonl", [e for e in self.a if e["kind"] != "screenshot"])
        with self.assertRaises(ValueError):
            self.checked()

    def test_stock_was_already_present(self):
        self.server[0]["containers"]["9,201,7"]["minecraft:bread"] = 8
        write(self.root, "server/observations.jsonl", self.server)
        with self.assertRaises(ValueError):
            self.checked()

    def test_client_player_inventory_cannot_count_as_chest(self):
        self.menu["state"]["slots"][0]["playerInventory"] = True
        write(self.root, "clients/PlayerA/observations.jsonl", [self.menu])
        with self.assertRaises(ValueError):
            self.checked()

    def test_menu_packet_must_agree(self):
        next(e for e in self.a if e["kind"] == "menu-content")["data"]["slots"][0]["count"] = 7
        write(self.root, "clients/PlayerA/events.jsonl", self.a)
        with self.assertRaises(ValueError):
            self.checked()

    def test_foreign_suggestion_reveals_baker(self):
        self.b[1]["data"]["values"] = ["Baker"]
        write(self.root, "clients/PlayerB/events.jsonl", self.b)
        with self.assertRaises(ValueError):
            self.checked()

    def test_setup_during_measured_action(self):
        self.supervisor.insert(-1, {"time": 5.7, "kind": "fixture-setup", "data": {}})
        write(self.root, "supervisor.jsonl", self.supervisor)
        with self.assertRaises(ValueError):
            self.checked()


if __name__ == "__main__":
    unittest.main()
