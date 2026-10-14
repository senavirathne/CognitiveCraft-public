#!/usr/bin/env python3
"""Strict Actions-only native/connected composition evidence verification."""
from pathlib import Path
import argparse,json,hashlib,shutil,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/rb005-evidence'
def native(phase):
    root=ROOT/'fabric/build/gametest-run';xml=root/'gametest-results.xml';log=root/'logs/latest.log'
    cases=[c for c in ET.parse(xml).getroot().iter('testcase') if c.get('name','').startswith('ai_villages_tests:')]
    assert len(cases)==1 and 'composition_game_tests' in cases[0].get('name','')
    assert not any(c.find(t) is not None for c in cases for t in ('failure','error','skipped'))
    body=log.read_text();genuine=phase!='controlled';warm=phase.endswith('warm')
    expected='RB005_NATIVE phase='+('warm' if warm else 'cold')+' genuine='+str(genuine).lower()
    lines=[l for l in body.splitlines() if expected in l]
    assert len(lines)==(1 if warm else 8) and all('modelCalls=0' in l for l in lines)
    if not warm:assert len([l for l in body.splitlines() if 'RB005_ADMISSION genuine='+str(genuine).lower() in l])==4
    OUT.mkdir(parents=True,exist_ok=True)
    for p in (xml,log):shutil.copy2(p,OUT/(phase+p.suffix))
    (OUT/(phase+'.json')).write_text(json.dumps({'phase':phase,'nativeCases':1,'goldenRows':len(lines),'xmlSha256':hashlib.sha256(xml.read_bytes()).hexdigest(),'logSha256':hashlib.sha256(log.read_bytes()).hexdigest()},indent=2)+'\n')
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['native']);p.add_argument('--phase',required=True);a=p.parse_args();native(a.phase)
