"""C01-P01: exercise the actual compiled Java candidate ingress, never a Python parser substitute."""
import copy
import json
import os
from pathlib import Path
import re
import subprocess
import unittest

from common import ROOT


class IntentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
        observed = subprocess.run([cls.java, "-version"], capture_output=True, text=True, check=True)
        if not re.search(r'(?:version\s+"|openjdk\s+)25(?:[.\s"+-])', observed.stdout + observed.stderr):
            raise AssertionError("C01 Python oracle requires the actual Java 25 classes")
        cls.classpath = os.pathsep.join(str(ROOT / p) for p in ("core/build/classes/java/main", "core/build/classes/java/test"))

    def probe(self, text, candidate=None):
        command = [self.java, "-cp", self.classpath, "dev.aivillages.core.kernel.interaction.IntentParserProbe",
                   "parse" if candidate is None else "validate", text]
        if candidate is not None:
            command.append(json.dumps(candidate, ensure_ascii=False, separators=(",", ":")))
        result = subprocess.run(command, capture_output=True, text=True, timeout=15, check=True)
        frame = json.loads(result.stdout)
        self.assertEqual("SYNTHETIC_INTENT", frame["tier"])
        return frame

    def envelope(self, text):
        frame = self.probe(text)
        self.assertEqual("CANDIDATE", frame["result"])
        return {"schema": "cc.cd/1.0.0", "kind": frame["kind"], "body": frame["body"]}

    def test_zero_negative_sixty_five_and_overflow_quantities_are_rejected(self):
        for quantity in ("0", "-1", "65", "9223372036854775808", "9" * 200):
            frame = self.probe(f'deliver {quantity} bread from chest "Source" to barrel "Output"')
            self.assertEqual(("CLARIFY", "INVALID_QUANTITY"), (frame["result"], frame["code"]))

    def test_inserted_principal_root_and_coordinates_are_rejected(self):
        text = 'deliver 3 bread from chest "Source" to chest "Output"'
        original = self.envelope(text)
        for field, value in (("principal_id", "00000000-0000-0000-0000-000000000099"),
                             ("root_id", "00000000-0000-0000-0000-000000000088"),
                             ("coordinates", {"x": 1, "y": 64, "z": 1}), ("scope", {"principal_id": "spoof"})):
            candidate = copy.deepcopy(original); candidate["body"][field] = value
            self.assertEqual("CLARIFY", self.probe(text, candidate)["result"])
        candidate = copy.deepcopy(original); candidate["body"]["source"]["selector"]["x"] = 7
        self.assertEqual("CLARIFY", self.probe(text, candidate)["result"])

    def test_integer_endpoints_work_and_float_or_hallucinated_quantity_does_not(self):
        for quantity in (1, 64):
            text = f'deliver {quantity} bread from chest "Source" to chest "Output"'
            candidate = self.envelope(text)
            self.assertEqual(quantity, candidate["body"]["items"][0]["quantity"])
            self.assertEqual("CANDIDATE", self.probe(text, candidate)["result"])
            for bad in (float(quantity), quantity + 1):
                candidate["body"]["items"][0]["quantity"] = bad
                self.assertEqual("CLARIFY", self.probe(text, candidate)["result"])

    def test_reply_question_and_revision_cannot_be_swapped(self):
        text = "check again"; original = self.envelope(text)
        for field, value in (("question_id", "00000000-0000-0000-0000-000000000099"), ("dialogue_revision", 8)):
            candidate = copy.deepcopy(original); candidate["body"][field] = value
            frame = self.probe(text, candidate)
            self.assertEqual(("CLARIFY", "CORRELATION"), (frame["result"], frame["code"]))

    def test_labels_cannot_be_promoted_to_actor_identity_or_permission(self):
        text = 'rename chest "Ada" to "Alice"'; candidate = self.envelope(text)
        self.assertEqual("CONFIRM_PROPOSAL", self.probe(text)["routing"])
        candidate["body"]["actor"] = {"kind": "NAMED", "label": "Alice"}
        self.assertEqual("CLARIFY", self.probe(text, candidate)["result"])
        candidate = self.envelope("Hello Ada")
        self.assertEqual("CHAT", self.probe("Hello Ada")["routing"])
        candidate["body"]["action"] = "FOLLOW"
        self.assertEqual("CLARIFY", self.probe("Hello Ada", candidate)["result"])

    def test_ambiguous_unsupported_and_overlong_text_clarify(self):
        for text in ("follow me and come here", "do not follow me", "go to the moon", "deliver some bread", "x" * 1025):
            self.assertEqual("CLARIFY", self.probe(text)["result"])
