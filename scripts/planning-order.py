#!/usr/bin/env python3
"""Verify frozen material-schema values and the measured wire order, without a quality gate."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('facilities', Path(__file__).with_name('planning-facilities.py'))
FACILITIES = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FACILITIES)
RESOURCES = FACILITIES.RESOURCES
STRUCTURE = RESOURCES.STRUCTURE
require = RESOURCES.require
CORPUS_SHA = '41051c448072694b2a604cabd7972824d4b32de9f98e260a0f303999ee9e76db'


def expected_shape(context):
    """The pre-existing finite contract, including all recipe choices, quantities and array bounds."""
    required = context['requiredWork']
    step_choices = [{'type': 'object', 'additionalProperties': False, 'required': ['recipe', 'batches'],
                     'properties': {'recipe': {'type': 'string', 'enum': [recipe]},
                                    'batches': {'type': 'integer', 'enum': [batches]}}}
                    for recipe, batches in sorted(required['batches'].items())]
    delivery_choices = [{'type': 'object', 'additionalProperties': False, 'required': ['item', 'amount'],
                         'properties': {'item': {'type': 'string', 'enum': [item]},
                                        'amount': {'type': 'integer', 'enum': [amount]}}}
                        for item, amount in sorted(context['requiredDeliveries'].items())]
    empty = {'type': 'array', 'maxItems': 0, 'items': {'type': 'object'}}
    steps = {'type': 'array', 'minItems': len(step_choices), 'maxItems': len(step_choices),
             'uniqueItems': True, 'items': {'anyOf': step_choices}} if step_choices else empty
    deliveries = {'type': 'array', 'minItems': len(delivery_choices), 'maxItems': len(delivery_choices),
                  'uniqueItems': True, 'items': {'anyOf': delivery_choices}} if required['status'] == 'plan' else empty
    return {'type': 'object', 'additionalProperties': False, 'required': ['schema', 'status', 'steps', 'deliveries'],
            'properties': {'schema': {'type': 'integer', 'enum': [1]},
                           'status': {'type': 'string', 'enum': [required['status']]},
                           'steps': steps, 'deliveries': deliveries}}


def check_schema_order(schema, wire_order='canonical'):
    if isinstance(schema, dict):
        if 'properties' in schema:
            properties = schema['properties']
            required = list(dict.fromkeys(k for k in schema.get('required', []) if k in properties))
            expected = sorted(properties) if wire_order == 'canonical' else required + sorted(set(properties) - set(required))
            require(list(properties) == expected, 'Schema properties differ from the measured wire order')
            for child in properties.values():
                check_schema_order(child, wire_order)
        for key in ('items', 'prefixItems', 'anyOf', 'oneOf', 'allOf'):
            if key in schema:
                check_schema_order(schema[key], wire_order)
    elif isinstance(schema, list):
        for child in schema:
            check_schema_order(child, wire_order)


def check_candidate_order(candidate, wire_order='canonical'):
    proposal = json.loads(candidate)
    root = ['deliveries', 'schema', 'status', 'steps'] if wire_order == 'canonical' else ['schema', 'status', 'steps', 'deliveries']
    step_fields = ['batches', 'recipe'] if wire_order == 'canonical' else ['recipe', 'batches']
    delivery_fields = ['amount', 'item'] if wire_order == 'canonical' else ['item', 'amount']
    require(list(proposal) == root, 'Candidate root differs from the measured wire order')
    require(all(list(step) == step_fields for step in proposal['steps']), 'Candidate step differs from the measured wire order')
    require(all(list(delivery) == delivery_fields for delivery in proposal['deliveries']), 'Candidate delivery differs from the measured wire order')


def check_row(row, wire_order='canonical'):
    FACILITIES.check_facility_row(row)
    context = json.loads(row['attempts'][0]['prompt'])
    if context.get('contract') != 'material-plan-preview:1':
        return 0
    expected = expected_shape(context)
    count = 0
    for attempt in row['attempts']:
        request = json.loads(attempt['transport']['request'])
        require(request['format'] == expected, 'Material schema values or permitted work changed')
        check_schema_order(request['format'], wire_order)
        if attempt['outcome'] == 'CANDIDATE':
            check_candidate_order(attempt['candidate'], wire_order)
        count += 1
    return count


def review(root, head, wire_order='canonical'):
    FACILITIES.review(root, head)  # All original safety, raw transport and earlier supplemental checks stay intact.
    groups = {ext: [json.loads(line) for p in root.rglob('*.' + ext) for line in p.read_text().splitlines() if line]
              for ext in ('jsonl', 'ndjson', 'rjsonl', 'fjsonl', 'ojsonl')}
    corpus = Path(__file__).resolve().parents[1] / 'providers/src/test/resources/planning/order-cases.json'
    require(hashlib.sha256(corpus.read_bytes()).hexdigest() == CORPUS_SHA, 'Frozen contract-order corpus changed')
    cases = json.loads(corpus.read_text())['cases']
    extra = groups['ojsonl']
    require(len(cases) == len({c['id'] for c in cases}) == 8 and len(extra) == 16, 'Contract-order corpus incomplete')
    rows = [r for group in groups.values() for r in group]
    require(len({r['sourceTree'] for r in rows}) == 1, 'Mixed source tree')
    for model, digest in STRUCTURE.MODEL_DIGESTS.items():
        selected = [r for r in extra if r['model'] == model]
        require(len(selected) == 8 and {r['caseId'] for r in selected} == {c['id'] for c in cases},
                'Missing/duplicate contract-order case')
        for row in selected:
            case = next(c for c in cases if c['id'] == row['caseId'])
            require(row['sourceCommit'] == head and row['datasetSha256'] == CORPUS_SHA and
                    row['corpusRole'] == 'supplemental-contract-schema-order', 'Unbound contract-order evidence')
            require(row['request'] == case['request'] and row['expectedStatus'] == case['expectedStatus'] and
                    row['category'] == case['category'], 'Frozen contract-order request/status/category changed')
            context = json.loads(row['attempts'][0]['prompt'])
            require(context['request'] == case['request'] and context['requiredDeliveries'] == case['goals'] and
                    context['availableInventory'] == case['inventory'] and context['tableAvailable'] == case['tableAvailable'] and
                    context['placementAllowed'] == case['placementAllowed'] and
                    context['requiredWork']['status'] == case['expectedStatus'], 'Frozen contract-order premises changed')
            require(all(a['modelDigest'] == digest for a in row['attempts']), 'Model weights changed')
    schemas = sum(check_row(row, wire_order) for row in rows)
    result = {'measurementStatus': 'PASS', 'qualityThresholdEnforced': False, 'sourceCommit': head,
              'originalRequests': 100, 'structureRequests': 12, 'resourceRequests': 20,
              'facilityRequests': 20, 'contractOrderRequests': 16,
              'actualMaterialSchemasReconciledWithoutSemanticChanges': schemas, 'wireOrder': wire_order, 'models': {}}
    for model in STRUCTURE.MODEL_DIGESTS:
        primary = [r for r in groups['jsonl'] if r['model'] == model]
        additional = [r for r in extra if r['model'] == model]
        result['models'][model] = {'original': RESOURCES.summarize(primary),
            'originalFeasible': RESOURCES.summarize([r for r in primary if r['expectedStatus'] == 'plan']),
            'originalNegative': RESOURCES.summarize([r for r in primary if r['expectedStatus'] != 'plan']),
            'contractOrderSupplement': RESOURCES.summarize(additional),
            'contractOrderFeasible': RESOURCES.summarize([r for r in additional if r['expectedStatus'] == 'plan']),
            'contractOrderNegative': RESOURCES.summarize([r for r in additional if r['expectedStatus'] != 'plan'])}
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--head', required=True)
    parser.add_argument('--wire-order', choices=('canonical', 'contract'), default='canonical')
    args = parser.parse_args()
    result = review(args.root, args.head, args.wire_order)
    (args.root / 'order-integrity.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
