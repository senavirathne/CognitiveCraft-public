#!/usr/bin/env python3
"""Require complete genuine measurements; report quality without a minimum-success gate."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import statistics
import subprocess

MODELS = ('qwen2.5:1.5b-instruct', 'qwen2.5:3b-instruct')


def require(value, message):
    if not value:
        raise ValueError(message)


def wilson(successes, total):
    z = 1.959963984540054
    p = successes / total
    scale = 1 + z * z / total
    center = (p + z * z / (2 * total)) / scale
    radius = z * math.sqrt(p * (1 - p) / total + z * z / (4 * total * total)) / scale
    return [max(0, center - radius), min(1, center + radius)]


def describe(rows):
    first = sum(r['firstPassValid'] for r in rows)
    final = sum(r['valid'] for r in rows)
    latencies = sorted(r['latencyMillis'] for r in rows)
    categories = {}
    feasible = [r for r in rows if r['expectedStatus'] == 'plan']
    impossible = [r for r in rows if r['expectedStatus'] != 'plan']
    feasible_valid = sum(r['valid'] for r in feasible)
    for name in sorted({r['category'] for r in rows}):
        part = [r for r in rows if r['category'] == name]
        categories[name] = {'total': len(part), 'firstPassValid': sum(r['firstPassValid'] for r in part),
                            'finalValid': sum(r['valid'] for r in part)}
    return {'total': len(rows), 'firstPassValid': first, 'finalValid': final,
            'firstPassRate': first / len(rows), 'finalRate': final / len(rows),
            'firstPassWilson95': wilson(first, len(rows)), 'finalWilson95': wilson(final, len(rows)),
            'feasiblePlans': {'total': len(feasible), 'firstPassValid': sum(r['firstPassValid'] for r in feasible),
                              'finalValid': feasible_valid, 'finalRate': feasible_valid / len(feasible),
                              'finalWilson95': wilson(feasible_valid, len(feasible))},
            'correctRefusals': {'total': len(impossible), 'firstPassValid': sum(r['firstPassValid'] for r in impossible),
                                'finalValid': sum(r['valid'] for r in impossible)},
            'rescuedByCorrection': sum(not r['firstPassValid'] and r['valid'] for r in rows),
            'correctionAttempts': sum(r['repairs'] for r in rows), 'calls': sum(r['calls'] for r in rows),
            'latencyMedianMillis': statistics.median(latencies),
            'latencyP95Millis': latencies[math.ceil(len(rows) * .95) - 1],
            'inputBytes': sum(a['inputBytes'] for r in rows for a in r['attempts']),
            'outputBytes': sum(a['outputBytes'] for r in rows for a in r['attempts']), 'categories': categories,
            'cases': rows}


def analyze(root, dataset, head, tree, shards=4):
    raw = dataset.read_bytes()
    cases = json.loads(raw)['cases']
    count = len(cases)
    require(30 <= count <= 50 and len({c['id'] for c in cases}) == count, '30–50 distinct committed cases required')
    require(any(c['expectedStatus'] == 'plan' for c in cases), 'Feasible requests required')
    dataset_hash = hashlib.sha256(raw).hexdigest()
    choices = {}
    for path in root.rglob('*.jsonl'):
        rows = [json.loads(line) for line in path.read_text().splitlines() if line]
        if not rows:
            continue
        group = {(r['model'], r['shard'], r.get('ciAttempt', 1)) for r in rows}
        require(len(group) == 1, 'Mixed model/shard/attempt file')
        model, shard, attempt = group.pop()
        require(model in MODELS and 0 <= shard < shards, 'Unexpected model/shard')
        key = (model, shard)
        if key in choices:
            require(choices[key][0] != attempt, 'Duplicate exact-attempt measurement file')
        if key not in choices or attempt > choices[key][0]:
            choices[key] = (attempt, rows)
    require(len(choices) == len(MODELS) * shards, 'Missing model/shard measurements')
    results = {}
    for model in MODELS:
        rows = []
        for shard in range(shards):
            part = choices[(model, shard)][1]
            require(sorted(r['case'] for r in part) == list(range(shard, count, shards)), 'A latest shard must contain every assigned request exactly once')
            rows.extend(part)
        rows.sort(key=lambda r: r['case'])
        for row, case in zip(rows, cases, strict=True):
            require(row['caseId'] == case['id'] and row['category'] == case['category'] and row['expectedStatus'] == case['expectedStatus'], 'Case identity or golden status mismatch')
            require(row['sourceCommit'] == head and row['sourceTree'] == tree and row['datasetSha256'] == dataset_hash, 'Mixed source/tree/dataset')
            attempts = row['attempts']
            require(1 <= len(attempts) <= 2 and row['calls'] == len(attempts) and row['repairs'] == len(attempts) - 1, 'Original two-call/one-repair accounting violated')
            require(row['firstPassValid'] == attempts[0]['valid'] and row['valid'] == attempts[-1]['valid'], 'Quality fields differ from validation attempts')
            require(row['nativeEffects'] == 0 and row['dedicatedModels'] == 0 and row['latencyMillis'] > 0, 'Invalid measurement scope')
            for i, attempt in enumerate(attempts):
                require(attempt['attempt'] == i + 1 and attempt['role'] == ('PLANNING' if i == 0 else 'PLANNING_REPAIR'), 'Invalid correction ordering/role')
                require(isinstance(attempt['valid'], bool) and 0 < attempt['inputBytes'] <= 32768 and 0 < attempt['outputBytes'] <= 8192 and attempt['latencyMillis'] > 0, 'Missing actual bounded inference bytes/latency')
                require(re.fullmatch('[0-9a-f]{64}', attempt.get('modelDigest') or ''), 'Actual model digest required')
                require(hashlib.sha256(attempt['prompt'].encode()).hexdigest() == attempt['promptSha256'], 'Prompt identity mismatch')
                require(re.fullmatch('[0-9a-f]{64}', attempt.get('constraintSha256') or ''), 'Actual request constraint identity required')
            if len(attempts) == 2:
                require(not attempts[0]['valid'], 'Valid proposals must not trigger correction')
        digests = {a['modelDigest'] for r in rows for a in r['attempts']}
        require(len(digests) == 1, 'Model identity changed within a comparison arm')
        results[model] = {'modelDigest': digests.pop(), **describe(rows)}
    require(len({results[m]['modelDigest'] for m in MODELS}) == 2, 'Comparison arms must use different actual model weights')
    a, b = (results[m]['cases'] for m in MODELS)
    require(all(x['attempts'][0]['promptSha256'] == y['attempts'][0]['promptSha256'] for x, y in zip(a, b, strict=True)), 'Models did not receive identical initial prompts')
    require(all(x['attempts'][0].get('constraintSha256') == y['attempts'][0].get('constraintSha256') for x, y in zip(a, b, strict=True)), 'Models did not receive identical request constraints')
    better = sum(not x['valid'] and y['valid'] for x, y in zip(a, b, strict=True))
    worse = sum(x['valid'] and not y['valid'] for x, y in zip(a, b, strict=True))
    discordant = better + worse
    p = min(1, 2 * sum(math.comb(discordant, k) for k in range(min(better, worse) + 1)) / 2 ** discordant) if discordant else 1
    quality = {m: values['feasiblePlans']['finalRate'] >= .9 for m, values in results.items()}
    return {'schema': 3, 'measurementStatus': 'PASS', 'qualityThresholdEnforced': False,
            'qualityAcceptance': {'minimumFeasibleFinalRate': .9, 'modelsMetTarget': quality,
                                  'allModelsMetTarget': all(quality.values()), 'gatesMeasurementJob': False},
            'requestsPerModel': count, 'sourceCommit': head, 'sourceTree': tree, 'datasetSha256': dataset_hash,
            'models': results, 'pairedComparison': {'largerOnlyValid': better, 'smallerOnlyValid': worse,
                'finalRateDifference': (better - worse) / count, 'exactMcNemarP': p},
            'scope': 'Typed requests; canonical quantities/status and recipe demand supplied by deterministic constraints; model orders required steps. No Minecraft effects or new gameplay entrypoint.',
            'uncertainty': 'Wilson intervals summarize this fixed test mix. One deterministic run per request/model is not a deployment reliability guarantee.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--dataset', type=Path, default=Path('providers/src/test/resources/planning/acceptance-requests.json'))
    parser.add_argument('--expected-dataset-sha')
    parser.add_argument('--head', default=os.environ.get('REVIEWED_HEAD'))
    args = parser.parse_args()
    if args.expected_dataset_sha:
        require(hashlib.sha256(args.dataset.read_bytes()).hexdigest() == args.expected_dataset_sha, 'Frozen dataset changed')
    tree = subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], text=True).strip()
    require(args.head == subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(), 'Reviewed source required')
    proof = analyze(args.root, args.dataset, args.head, tree)
    (args.root / 'acceptance.json').write_text(json.dumps(proof, indent=2) + '\n')
    summary = {name: {k: v for k, v in values.items() if k != 'cases'} for name, values in proof['models'].items()}
    print(json.dumps({'measurementStatus': proof['measurementStatus'], 'qualityThresholdEnforced': False, 'qualityAcceptance': proof['qualityAcceptance'], 'models': summary, 'pairedComparison': proof['pairedComparison']}, indent=2))
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as out:
            out.write('Measurement completeness PASS; no minimum planning success rate is enforced.\n\n')
            out.write('| Model | First pass | After one correction | Median / p95 ms |\n|---|---|---|---|\n')
            for name, result in summary.items():
                out.write(f"| {name} | {result['firstPassValid']}/{proof['requestsPerModel']} | {result['finalValid']}/{proof['requestsPerModel']} | {result['latencyMedianMillis']} / {result['latencyP95Millis']} |\n")
                feasible = result['feasiblePlans']; refused = result['correctRefusals']
                out.write(f"\n{name}: feasible plans {feasible['finalValid']}/{feasible['total']}; correct refusals {refused['finalValid']}/{refused['total']}; separate 90% feasible-plan acceptance {proof['qualityAcceptance']['modelsMetTarget'][name]}.\n\n")
            out.write('\n' + proof['uncertainty'] + '\n')


if __name__ == '__main__':
    main()
