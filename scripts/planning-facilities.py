#!/usr/bin/env python3
"""Audit actual facility evidence; deterministic safety is separate from model quality."""
import argparse
import collections
import hashlib
import importlib.util
import json
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('resources', Path(__file__).with_name('planning-resources.py'))
RESOURCES = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RESOURCES)
STRUCTURE = RESOURCES.STRUCTURE
CORPUS_SHA = '798b0ea6ed3dc482a937d75690358be542d7c90cac97893840895d1968a62232'
TABLE = 'minecraft:crafting_table'
AXE = 'minecraft:wooden_axe'
PLACE = RESOURCES.PLACE
require = RESOURCES.require


def facility_failure(candidate, context):
    """Independent first-error replay. Later facility errors cannot hide an earlier stock error."""
    p = json.loads(candidate)
    required = context['requiredWork']
    if p['status'] != required['status'] or p['status'] != 'plan':
        return {}
    deliveries = {row['item']: row['amount'] for row in p['deliveries']}
    if len(deliveries) != len(p['deliveries']) or deliveries != context['requiredDeliveries']:
        return {}
    # Java strictly decodes every step before validation.
    if any(s['recipe'] not in (*RESOURCES.RECIPES, PLACE) or not 1 <= s['batches'] <= 16 for s in p['steps']):
        return {}
    stock = dict(context['availableInventory'])
    table = context['tableAvailable']
    for index, step in enumerate(p['steps']):
        recipe, batches = step['recipe'], step['batches']
        reason = None
        if recipe == PLACE:
            if not context['placementAllowed']:
                reason = 'PLACEMENT_NOT_AUTHORIZED'
            elif table:
                reason = 'TABLE_ALREADY_AVAILABLE'
            elif batches != 1:
                reason = 'INVALID_PLACEMENT_BATCHES'
            elif stock.get(TABLE, 0) < 1:
                reason = 'MISSING_TABLE_ITEM'
        else:
            if batches > (4 if recipe in (TABLE, AXE) else 16):
                return {}
            if recipe == AXE and not table:
                reason = 'TABLE_NOT_PLACED'
        if reason:
            return {'step': index, 'recipe': recipe, 'reason': reason,
                    'tableAvailableBeforeStep': table, 'tableItemsBeforeStep': stock.get(TABLE, 0),
                    'placementAllowed': context['placementAllowed'],
                    'missingTableItemsForPlacement': max(0, 1 - stock.get(TABLE, 0)), 'stockBeforeStep': stock.copy()}
        if recipe == PLACE:
            stock[TABLE] -= 1
            table = True
        else:
            inputs, output = RESOURCES.RECIPES[recipe]
            if any(stock.get(item, 0) < amount * batches for item, amount in inputs.items()):
                return {}
            for item, amount in inputs.items():
                stock[item] = stock.get(item, 0) - amount * batches
            stock[recipe] = stock.get(recipe, 0) + output * batches
    return {}


def check_facility_row(row):
    STRUCTURE.check_row(row)
    context = json.loads(row['attempts'][0]['prompt'])
    if context.get('contract') != 'material-plan-preview:1':
        return 0
    RESOURCES.check_grounding(context)
    count = 0
    for attempt in row['attempts']:
        witness = facility_failure(attempt['candidate'], context) if attempt['outcome'] == 'CANDIDATE' else {}
        require(bool(witness) == (attempt['diagnostic'] == 'FACILITY'), 'First facility diagnostic disagrees with trusted replay')
        if attempt['outcome'] == 'CANDIDATE':
            require(RESOURCES.resource_valid(attempt['candidate'], context) == attempt['valid'],
                    'Facility/stock validation disagrees with independent replay')
        if witness:
            count += 1
        if attempt['attempt'] == 2:
            first = row['attempts'][0]
            feedback = json.loads(attempt['prompt'])
            require(feedback['trustedContext'] == first['prompt'] and feedback['attempt'] == feedback['attemptLimit'] == 2,
                    'Facility repair changed trusted context or allowance')
            require(attempt['constraintSha256'] == first['constraintSha256'], 'Facility repair changed the output grammar')
            if first['diagnostic'] == 'FACILITY':
                expected = facility_failure(first['candidate'], context)
                require(feedback['previousProposal'] == 'Omitted after facility rejection; use trusted context and facility-state witness.',
                        'Facility repair echoed the rejected sequence')
                require(feedback['validationError']['details'].get('facilityFailure') == expected,
                        'Facility-state witness differs from trusted replay')
    return count


def review(root, head):
    RESOURCES.review(root, head)  # Previous corpus/hash/transport/stock checks remain mandatory.
    groups = {ext: [json.loads(line) for p in root.rglob('*.' + ext) for line in p.read_text().splitlines() if line]
              for ext in ('jsonl', 'ndjson', 'rjsonl', 'fjsonl')}
    corpus = Path(__file__).resolve().parents[1] / 'providers/src/test/resources/planning/facility-cases.json'
    require(hashlib.sha256(corpus.read_bytes()).hexdigest() == CORPUS_SHA, 'Frozen facility corpus changed')
    cases = json.loads(corpus.read_text())['cases']
    extra = groups['fjsonl']
    require(len(cases) == len({c['id'] for c in cases}) == 10 and len(extra) == 20, 'Facility corpus incomplete')
    all_rows = [r for rows in groups.values() for r in rows]
    require(len({r['sourceTree'] for r in all_rows}) == 1, 'Mixed source tree')
    for model, digest in STRUCTURE.MODEL_DIGESTS.items():
        rows = [r for r in extra if r['model'] == model]
        require(len(rows) == 10 and {r['caseId'] for r in rows} == {c['id'] for c in cases}, 'Missing/duplicate facility case')
        for row in rows:
            case = next(c for c in cases if c['id'] == row['caseId'])
            require(row['sourceCommit'] == head and row['datasetSha256'] == CORPUS_SHA and
                    row['corpusRole'] == 'supplemental-facility-state', 'Unbound facility evidence')
            require(row['request'] == case['request'] and row['expectedStatus'] == case['expectedStatus'] and
                    row['category'] == case['category'], 'Frozen facility request/status/category changed')
            context = json.loads(row['attempts'][0]['prompt'])
            require(context['request'] == case['request'] and context['requiredDeliveries'] == case['goals'] and
                    context['availableInventory'] == case['inventory'] and context['tableAvailable'] == case['tableAvailable'] and
                    context['placementAllowed'] == case['placementAllowed'], 'Frozen facility premises changed')
            require(all(a['modelDigest'] == digest for a in row['attempts']), 'Model weights changed')
    rejected = sum(check_facility_row(row) for row in all_rows)
    result = {'measurementStatus': 'PASS', 'qualityThresholdEnforced': False, 'sourceCommit': head,
              'originalRequests': 100, 'structureRequests': 12, 'resourceRequests': 20, 'facilityRequests': 20,
              'independentlyReplayedFacilityRejections': rejected, 'models': {}}
    for model in STRUCTURE.MODEL_DIGESTS:
        primary = [r for r in groups['jsonl'] if r['model'] == model]
        facility = [r for r in extra if r['model'] == model]
        result['models'][model] = {
            'originalFacilityCategory': RESOURCES.summarize([r for r in primary if r['category'] == 'facility_provision']),
            'originalNegatives': RESOURCES.summarize([r for r in primary if r['expectedStatus'] != 'plan']),
            'facilitySupplement': RESOURCES.summarize(facility),
            'facilityFeasible': RESOURCES.summarize([r for r in facility if r['expectedStatus'] == 'plan']),
            'facilityNegative': RESOURCES.summarize([r for r in facility if r['expectedStatus'] != 'plan'])}
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--head', required=True)
    args = parser.parse_args()
    result = review(args.root, args.head)
    (args.root / 'facility-integrity.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))

