#!/usr/bin/env python3
"""Run actual unittest cases and preserve one raw transcript plus per-case XML outcomes."""
from __future__ import annotations

import argparse
import io
import os
from pathlib import Path
import time
import unittest
import xml.etree.ElementTree as ET

from common import HERE, identity, write


class RecordedResult(unittest.TextTestResult):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs); self.rows = []; self.started = {}
    def startTest(self, test):
        self.started[test.id()] = time.monotonic(); super().startTest(test)
    def record(self, test, status, detail=None):
        self.rows.append({"id": test.id(), "status": status, "detail": detail,
                          "seconds": time.monotonic() - self.started[test.id()]})
    def addSuccess(self, test):
        super().addSuccess(test); self.record(test, "PASS")
    def addFailure(self, test, err):
        super().addFailure(test, err); self.record(test, "FAIL", self._exc_info_to_string(err, test))
    def addError(self, test, err):
        super().addError(test, err); self.record(test, "ERROR", self._exc_info_to_string(err, test))
    def addSkip(self, test, reason):
        super().addSkip(test, reason); self.record(test, "SKIPPED", reason)


def run(output):
    output = Path(output); output.mkdir(parents=True, exist_ok=True)
    suite = unittest.defaultTestLoader.discover(str(HERE), pattern="test_*.py")
    log = io.StringIO()
    previous_evidence = os.environ.get('COGNITIVECRAFT_C_PYTHON_EVIDENCE_ROOT')
    os.environ['COGNITIVECRAFT_C_PYTHON_EVIDENCE_ROOT'] = str(output.resolve())
    try: result = unittest.TextTestRunner(stream=log, verbosity=2, resultclass=RecordedResult).run(suite)
    finally:
        if previous_evidence is None: os.environ.pop('COGNITIVECRAFT_C_PYTHON_EVIDENCE_ROOT', None)
        else: os.environ['COGNITIVECRAFT_C_PYTHON_EVIDENCE_ROOT'] = previous_evidence
    (output / "python.log").write_text(log.getvalue())
    xml = ET.Element("testsuite", name="C-owned Python", tests=str(result.testsRun),
                     failures=str(len(result.failures)), errors=str(len(result.errors)), skipped=str(len(result.skipped)))
    for row in result.rows:
        cls, _, name = row["id"].rpartition(".")
        case = ET.SubElement(xml, "testcase", classname=cls, name=name, time=str(row["seconds"]))
        if row["status"] != "PASS":
            tag = {"FAIL": "failure", "ERROR": "error", "SKIPPED": "skipped"}[row["status"]]
            ET.SubElement(case, tag).text = row["detail"]
    ET.ElementTree(xml).write(output / "python.xml", encoding="utf-8", xml_declaration=True)
    write(output / "python-process.json", {**identity(), "command": ["python3", "e2e/parallel_c/run_tests.py"],
                                          "exit": 0 if result.wasSuccessful() else 1, "tests_run": result.testsRun})
    return result.wasSuccessful()


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__); p.add_argument("--output", type=Path, required=True)
    raise SystemExit(0 if run(p.parse_args().output) else 1)
