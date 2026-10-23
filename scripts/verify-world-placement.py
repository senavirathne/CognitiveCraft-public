#!/usr/bin/env python3
"""Qualify Task 1.1 only; never substitute it for complete RB006B acceptance."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/rb006b-evidence'


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def native(phase):
    folder = ROOT / 'fabric/build/gametest-run'
    report = folder / 'gametest-results.xml'
    log = folder / 'logs/latest.log'
    cases = [c for c in ET.parse(report).getroot().iter('testcase')
             if c.get('name', '').startswith('ai_villages_tests:')]
    expected = 3 if phase == 'variants' else 5
    owner = 'world_placement_game_tests' if phase == 'variants' else 'placement_effect_game_tests'
    assert len(cases) == expected, [c.attrib for c in cases]
    assert len({c.get('name') for c in cases}) == expected
    assert all(owner in c.get('name', '') for c in cases)
    assert not any(c.find(t) is not None for c in cases for t in ('failure', 'error', 'skipped'))
    markers = ['RB006B_SELF_OCCUPANCY', 'RB006B_CENTER_RAY', 'RB006B_COLLISION'] if phase == 'variants' else ['RB006B_EFFECTS', 'RB006B_SELF_OCCUPANCY', 'RB006B_CENTER_RAY', 'RB006B_COLLISION', 'RB006B_FALLING']
    lines = log.read_text().splitlines()
    for marker in markers:
        assert sum(marker in line for line in lines) == 1, marker
    OUT.mkdir(parents=True, exist_ok=True)
    for path in (report, log):
        shutil.copy2(path, OUT / ('task11-' + phase + '-' + path.name))
    (OUT / ('task11-' + phase + '.json')).write_text(json.dumps({
        'task': '1.1', 'status': 'PASS', 'cases': expected,
        'caseNames': [c.get('name') for c in cases],
        'xmlSha256': sha(report), 'logSha256': sha(log),
    }, indent=2) + '\n')


def client():
    folder = ROOT / 'build/e2e/rb006b-placement'
    data = json.loads((folder / 'results.json').read_text())
    rows = [r for r in data['results'] if r['id'] == 'GP-RB-06B-PLACEMENT']
    assert len(rows) == 1 and rows[0]['status'] == 'PASS'
    evidence = rows[0]['evidence']
    assert evidence['task'] == '1.1'
    assert evidence['offlineReuse'] == evidence['laterTasks'] == 'NOT_STARTED'
    assert len(evidence['independentAcquisitions']) == len(evidence['artifacts']) == 6
    assert evidence['foreignDenied']
    assert len(evidence['principals']) == len(evidence['citizens']) == 2
    for key, tables, placements, logs in [('existingFacility', 0, 0, 0),
                                          ('existingItem', 0, 1, 0),
                                          ('emptyProvision', 1, 1, 3)]:
        result = evidence[key]
        assert result['root']['state'] == 'SUCCEEDED' and result['root']['fulfilled'] == 1
        assert (result['tableJobs'], result['placements'], result['harvested']) == (tables, placements, logs)
        receipts = [r for job in result['graph'] for attempt in job['attempts']
                    for r in attempt.get('resourceReceipts', [])]
        placed = [r for r in receipts if r['stage'] == 'PLACE']
        assert len(placed) == placements and all(r['quantity'] == 1 for r in placed)
        for receipt in placed:
            withdrawals = [r for r in receipts if r['stage'] == 'WITHDRAW'
                           and r['batchId'] == receipt['batchId'] and r.get('placement')]
            assert len(withdrawals) == 1 and withdrawals[0]['quantity'] == 1
            assert withdrawals[0]['placement']['custodyAfter'] == receipt['placement']['custodyBefore']
            assert receipt['placement']['blockState'] == 'minecraft:crafting_table'
            assert receipt['origin'] == result['root']['request']['context']
    snapshot = evidence['finalSnapshot']
    source = snapshot['containers']['15,201,9']
    assert source.get('minecraft:oak_planks', 0) == 3 and source.get('minecraft:stick', 0) == 2
    assert source.get('minecraft:crafting_table', 0) == 0
    assert 'minecraft:crafting_table' in snapshot['blocks']['16,201,11']
    assert len(rows[0]['screenshots']) >= 4
    identity = json.loads((OUT / 'identity.json').read_text())
    jar = ROOT / 'fabric/build/libs/ai-villages-0.1.0.jar'
    assert sha(jar) == identity['jarSha256']
    metadata = json.loads((folder / 'metadata.json').read_text())
    # The harness additionally pins every screenshot and actual installed release.
    assert metadata['sourceCommit'] == identity['source']
    assert metadata['releaseSha256'] == identity['jarSha256']
    OUT.mkdir(parents=True, exist_ok=True)
    shutil.copy2(folder / 'results.json', OUT / 'task11-connected-client.json')
    print('TASK11_CLIENT_PASS ' + json.dumps({'source': identity['source'], 'jarSha256': sha(jar),
                                             'emptyRoot': evidence['emptyProvision']['job'],
                                             'offlineReuse': 'NOT_STARTED'}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['variants', 'original', 'client'])
    args = parser.parse_args()
    client() if args.mode == 'client' else native(args.mode)
