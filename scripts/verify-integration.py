#!/usr/bin/env python3
"""Qualify the combined branch using the original frozen cases and real new binary."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / 'build/generalization/integration-baseline.json'
PROFILE = 'all-published-branches-integration-v1'


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, text=True).strip()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def original_verifier():
    spec = importlib.util.spec_from_file_location('frozen_assessment', ROOT / 'scripts/verify-generalization.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def production_paths():
    original = json.loads((ROOT / 'e2e/generalization/baseline.json').read_text())
    paths = {row['path'] for row in original['productionFiles']}
    for path in git('ls-files').splitlines():
        if path.endswith('.java') and path.startswith(('core/src/main/', 'providers/src/main/', 'fabric/src/main/')):
            paths.add(path)
    return sorted(paths)


def source_facts():
    if git('status', '--porcelain', '--untracked-files=normal'):
        raise ValueError('Integration qualification requires a clean source checkout')
    required = json.loads((ROOT / '.github/implementation/all-branches-source.json').read_text())
    for sha in [required['startingMain'], *(row['sha'] for row in required['requiredHeads'])]:
        subprocess.run(['git', 'merge-base', '--is-ancestor', sha, 'HEAD'], cwd=ROOT, check=True)
    verifier = original_verifier()
    verifier.corpus()
    return required, git('rev-parse', 'HEAD'), git('rev-parse', 'HEAD^{tree}')


def freeze():
    required, head, tree = source_facts()
    jar = ROOT / 'fabric/build/libs/ai-villages-0.1.0.jar'
    if not jar.is_file():
        raise ValueError('Build the actual combined production JAR before freezing its identity')
    rows = [{'path': p, 'sha256': digest(ROOT / p), 'gitBlob': git('hash-object', p)} for p in production_paths()]
    old = json.loads((ROOT / 'e2e/generalization/baseline.json').read_text())
    report = {'schema': 1, 'profile': PROFILE, 'sourceCommit': head, 'sourceTree': tree,
              'releaseJarSha256': digest(jar), 'corpusSha256': original_verifier().CORPUS_SHA256,
              'productionFileCount': len(rows), 'productionFiles': rows,
              'historicalProductionReference': old['sourceCommit'],
              'historicalBaselineFileSha256': digest(ROOT / 'e2e/generalization/baseline.json'),
              'requiredHeads': required['requiredHeads'], 'startingMain': required['startingMain'],
              'assessmentPolicy': 'Original 45 cases, expected fields, physical receipts and native frames remain required. This pins a real new integrated binary and does not claim unchanged historical production.'}
    if MANIFEST.exists() and json.loads(MANIFEST.read_text()) != report:
        raise ValueError('A different integration baseline already exists')
    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST.write_text(json.dumps(report, indent=2) + '\n')
    verify(MANIFEST)


def verify(path, require_jar=True):
    record = json.loads(path.read_text())
    required, head, tree = source_facts()
    if record.get('profile') != PROFILE or record.get('sourceCommit') != head or record.get('sourceTree') != tree:
        raise ValueError('Integration baseline source identity mismatch')
    if record.get('requiredHeads') != required['requiredHeads'] or record.get('startingMain') != required['startingMain']:
        raise ValueError('Integration branch inventory mismatch')
    if record.get('historicalBaselineFileSha256') != digest(ROOT / 'e2e/generalization/baseline.json'):
        raise ValueError('Historical baseline changed after integration capture')
    paths = production_paths()
    if record.get('productionFileCount') != len(paths) or [row['path'] for row in record['productionFiles']] != paths:
        raise ValueError('Incomplete integrated production inventory')
    for row in record['productionFiles']:
        if digest(ROOT / row['path']) != row['sha256'] or git('hash-object', row['path']) != row['gitBlob']:
            raise ValueError('Integrated production changed: ' + row['path'])
    if record.get('corpusSha256') != original_verifier().CORPUS_SHA256:
        raise ValueError('Integration corpus identity mismatch')
    if require_jar:
        original_verifier().baseline(path)
    return record


def collect(directory):
    paths = list(directory.rglob('integration-baseline.json'))
    if not paths or len({digest(p) for p in paths}) != 1:
        raise ValueError('All integration artifacts must retain the same captured baseline')
    baseline = verify(paths[0], require_jar=False)
    original_verifier().collect(directory, paths[0])
    result_path = directory / 'generalization-assessment.json'
    result = json.loads(result_path.read_text())
    result['qualificationProfile'] = PROFILE
    result['historicalProductionReference'] = baseline['historicalProductionReference']
    result['historicalResultUnchanged'] = '26 PASS /7 FAIL /12 UNSUPPORTED remains attached to the original assessment; this file is a new integrated-source measurement.'
    result['integratedBranchHeads'] = baseline['requiredHeads']
    result_path.write_text(json.dumps(result, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['freeze', 'baseline', 'collect'])
    parser.add_argument('--directory', type=Path)
    args = parser.parse_args()
    if args.command == 'freeze':
        freeze()
    elif args.command == 'baseline':
        verify(MANIFEST)
    else:
        if args.directory is None:
            parser.error('--directory is required for collection')
        collect(args.directory)


if __name__ == '__main__':
    main()
