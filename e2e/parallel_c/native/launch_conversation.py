#!/usr/bin/env python3
"""Reuse the frozen E2E launcher and add only C's isolated test fixture mod."""
import os
from pathlib import Path
import shutil
import sys

ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'e2e'))
import launch

original=launch.install_mods
def install(directory,observer):
    original(directory,observer)
    fixture=Path(os.environ['COGNITIVECRAFT_C_FIXTURE_JAR'])
    if not fixture.is_file():raise RuntimeError('compiled C fixture missing')
    shutil.copy2(fixture,directory/'mods'/'parallel-c-conversation-fixture.jar')
launch.install_mods=install

if __name__=='__main__':launch.main()
