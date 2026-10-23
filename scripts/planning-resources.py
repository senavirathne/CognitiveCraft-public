#!/usr/bin/env python3
"""Audit genuine resource measurements; quality rates are reported, never collection gates."""
import argparse
import collections
import hashlib
import importlib.util
import json
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('structure', Path(__file__).with_name('planning-structure.py'))
STRUCTURE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(STRUCTURE)
CORPUS_SHA = 'db2bf29d69a05691af0fd4d903ebf48700b7eccd9d51defddf3ddfda5b8c5117'
ITEMS = ('minecraft:oak_log', 'minecraft:birch_log', 'minecraft:oak_planks', 'minecraft:birch_planks',
         'minecraft:stick', 'minecraft:wooden_axe', 'minecraft:crafting_table')
PLACE = 'cognitivecraft:place_crafting_table'
RECIPES = {
    'minecraft:oak_planks': ({'minecraft:oak_log': 1}, 4),
    'minecraft:birch_planks': ({'minecraft:birch_log': 1}, 4),
    'minecraft:stick': ({'minecraft:oak_planks': 2}, 4),
    'minecraft:crafting_table': ({'minecraft:oak_planks': 4}, 1),
    'minecraft:wooden_axe': ({'minecraft:oak_planks': 3, 'minecraft:stick': 2}, 1),
}


def require(value, message):
    if not value:
        raise ValueError(message)


def check_grounding(context):
    required = context['requiredWork']
    facts = required['resourceGrounding']
    expected = {item: context['availableInventory'].get(item, 0) for item in ITEMS}
    require(facts['initialStock'] == expected, 'Observed inventory changed or missing stock was invented')
    transactions = {}
    for recipe, batches in required['batches'].items():
        if recipe == PLACE:
            transactions[recipe] = {'consumes': {'minecraft:crafting_table': batches}, 'produces': {}}
        else:
            inputs, output = RECIPES[recipe]
            transactions[recipe] = {'consumes': {item: amount * batches for item, amount in inputs.items()},
                                    'produces': {recipe: output * batches}}
    require(facts['transactions'] == transactions, 'Future work quantities differ from the trusted recipe catalog')
    return facts


def resource_valid(candidate, context):
    """Independent forward stock replay, including delivery reservations and existing facility guards."""
    p = json.loads(candidate)
    expected = context['requiredWork']
    if p['status'] != expected['status']:
        return False
    if p['status'] != 'plan':
        return not p['steps'] and not p['deliveries']
    deliveries = {row['item']: row['amount'] for row in p['deliveries']}
    if len(deliveries) != len(p['deliveries']) or deliveries != context['requiredDeliveries']:
        return False
    stock = collections.Counter(context['availableInventory'])
    used = collections.Counter()
    table = context['tableAvailable']
    for step in p['steps']:
        recipe, batches = step['recipe'], step['batches']
        used[recipe] += batches
        if recipe == PLACE:
            if not context['placementAllowed'] or table or batches != 1 or stock['minecraft:crafting_table'] < 1:
                return False
            stock['minecraft:crafting_table'] -= 1
            table = True
            continue
        if recipe not in RECIPES or batches > (4 if recipe in ('minecraft:wooden_axe', 'minecraft:crafting_table') else 16):
            return False
        if recipe == 'minecraft:wooden_axe' and not table:
            return False
        inputs, output = RECIPES[recipe]
        if any(stock[item] < amount * batches for item, amount in inputs.items()):
            return False
        for item, amount in inputs.items():
            stock[item] -= amount * batches
        stock[recipe] += output * batches
    return dict(used) == expected['batches'] and all(stock[item] >= amount for item, amount in deliveries.items())


def summarize(rows):
    attempts = [a for r in rows for a in r['attempts']]
    return {'requests': len(rows), 'firstPassValid': sum(r['firstPassValid'] for r in rows),
            'finalValid': sum(r['valid'] for r in rows), 'calls': sum(r['calls'] for r in rows),
            'repairs': sum(r['repairs'] for r in rows),
            'diagnostics': dict(collections.Counter(a['diagnostic'] for a in attempts)),
            'failures': [{'caseId': r['caseId'], 'diagnostic': r['attempts'][-1]['diagnostic'],
                          'path': r['attempts'][-1]['path']} for r in rows if not r['valid']]}


def review(root, head):
    STRUCTURE.review(root, head, 6)  # Original corpus and Task 5.1 checks stay intact.
    primary = [json.loads(line) for p in root.rglob('*.jsonl') for line in p.read_text().splitlines() if line]
    structure = [json.loads(line) for p in root.rglob('*.ndjson') for line in p.read_text().splitlines() if line]
    extra = [json.loads(line) for p in root.rglob('*.rjsonl') for line in p.read_text().splitlines() if line]
    corpus = Path(__file__).resolve().parents[1] / 'providers/src/test/resources/planning/resource-cases.json'
    require(hashlib.sha256(corpus.read_bytes()).hexdigest() == CORPUS_SHA, 'Frozen resource corpus changed')
    cases = json.loads(corpus.read_text())['cases']
    require(len(cases) == len({c['id'] for c in cases}) == 10 and len(extra) == 20, 'Resource corpus incomplete')
    models = STRUCTURE.MODEL_DIGESTS
    require(len({r['sourceTree'] for r in primary + structure + extra}) == 1, 'Mixed source tree')
    for model, digest in models.items():
        rows = [r for r in extra if r['model'] == model]
        require(len(rows) == 10 and {r['caseId'] for r in rows} == {c['id'] for c in cases}, 'Missing/duplicate resource case')
        for row in rows:
            case = next(c for c in cases if c['id'] == row['caseId'])
            require(row['sourceCommit'] == head and row['datasetSha256'] == CORPUS_SHA and
                    row['corpusRole'] == 'supplemental-resource-grounding', 'Unbound resource evidence')
            require(row['request'] == case['request'] and row['expectedStatus'] == case['expectedStatus'] and
                    row['category'] == case['category'], 'Frozen request/status/category changed')
            context = json.loads(row['attempts'][0]['prompt'])
            require(context['request'] == case['request'] and context['requiredDeliveries'] == case['goals'] and
                    context['availableInventory'] == case['inventory'] and context['tableAvailable'] == case['tableAvailable'] and
                    context['placementAllowed'] == case['placementAllowed'], 'Frozen resource facts changed')
            require(all(a['modelDigest'] == digest for a in row['attempts']), 'Model weights changed')
            STRUCTURE.check_row(row)
    material_attempts = 0
    for row in primary + structure + extra:
        first = row['attempts'][0]
        context = json.loads(first['prompt'])
        if context.get('contract') != 'material-plan-preview:1':
            continue
        check_grounding(context)
        for attempt in row['attempts']:
            material_attempts += 1
            if attempt['outcome'] == 'CANDIDATE':
                require(resource_valid(attempt['candidate'], context) == attempt['valid'], 'Resource validation disagrees with independent stock replay')
            else:
                require(not attempt['valid'] and attempt['candidate'] == 'unavailable', 'Rejected candidate gained authority')
            if attempt['attempt'] == 2:
                feedback = json.loads(attempt['prompt'])
                require(feedback['trustedContext'] == first['prompt'] and feedback['attemptLimit'] == 2,
                        'Repair replaced trusted inventory or correction allowance')
                require(attempt['constraintSha256'] == first['constraintSha256'], 'Repair changed the trusted grammar')
                previous = feedback['previousProposal']
                if first['diagnostic'] == 'FABRICATED_RESOURCE':
                    require(previous == 'Omitted after resource rejection; use trusted context and deficit witness.' and
                            bool(feedback['validationError']['details'].get('resourceFailure')),
                            'Resource repair must retain the deficit witness without echoing the rejected sequence')
                elif first['diagnostic'] == 'FACILITY':
                    require(previous == 'Omitted after facility rejection; use trusted context and facility-state witness.' and
                            bool(feedback['validationError']['details'].get('facilityFailure')),
                            'Facility repair must retain a state witness without echoing the rejected sequence')
                else:
                    require(first['candidate'].startswith(previous) if first['outcome'] == 'CANDIDATE' else previous == 'unavailable',
                            'Non-resource previous-proposal evidence changed')
    result = {'measurementStatus': 'PASS', 'qualityThresholdEnforced': False, 'sourceCommit': head,
              'independentlyReplayedMaterialAttempts': material_attempts, 'originalRequests': 100,
              'structureRequests': 12, 'resourceRequests': 20, 'models': {}}
    for model in models:
        original = [r for r in primary if r['model'] == model]
        resource = [r for r in extra if r['model'] == model]
        result['models'][model] = {'original': summarize(original), 'resourceSupplement': summarize(resource),
                                   'resourceFeasible': summarize([r for r in resource if r['expectedStatus'] == 'plan']),
                                   'resourceNegative': summarize([r for r in resource if r['expectedStatus'] != 'plan'])}
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--head', required=True)
    args = parser.parse_args()
    result = review(args.root, args.head)
    (args.root / 'resource-integrity.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
