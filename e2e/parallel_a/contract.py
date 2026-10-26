"""A00 test-only semantic boundary. This module has no native execution port."""
import hashlib
import json

MAX_I64 = (1 << 63) - 1


def quantity(value):
    if type(value) is not int or not 0 <= value <= MAX_I64:
        raise ValueError('nonnegative signed-64-bit integer required')
    return value


def canonical(value):
    def visit(v):
        if isinstance(v, dict):
            if not all(isinstance(k, str) for k in v):
                raise ValueError('string keys required')
            for child in v.values():
                visit(child)
        elif isinstance(v, list):
            for child in v:
                visit(child)
        elif v is not None and type(v) not in (str, bool, int):
            raise ValueError('floats and non-JSON values forbidden')
        elif type(v) is int and not -MAX_I64-1 <= v <= MAX_I64:
            raise ValueError('integer overflow')
    visit(value)
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8')


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def batch(deficit, yield_each):
    quantity(deficit)
    quantity(yield_each)
    if deficit == 0 or yield_each == 0:
        raise ValueError('positive deficit/yield required')
    batches = deficit // yield_each + (1 if deficit % yield_each else 0)
    produced = quantity(batches * yield_each)
    return {'demand': deficit, 'batches': batches, 'physicalOutput': produced,
            'goalAllocation': deficit, 'surplus': produced - deficit}


def validate_proposal(snapshot, proposal, supported, *, native=False):
    """Declarative fixture oracle; successful validation never causes an effect."""
    fields = {'version', 'proposalId', 'root', 'revision', 'stepToken', 'capability',
              'capabilityVersion', 'allowance', 'deadline', 'quantities'}
    if set(proposal) != fields or proposal['version'] != 1:
        raise ValueError('unexpected fields, including proofs/terminal results')
    for key in ('proposalId', 'root', 'stepToken', 'capability'):
        if not isinstance(proposal[key], str) or not 1 <= len(proposal[key]) <= 128:
            raise ValueError('bounded identity required')
    if (proposal['root'], quantity(proposal['revision'])) != (snapshot['root'], snapshot['revision']):
        raise ValueError('stale root/revision')
    cap = proposal['capability']
    if native and cap.startswith('fixture:'):
        raise ValueError('synthetic capability cannot execute natively')
    if (cap, proposal['capabilityVersion']) not in supported:
        raise ValueError('unsupported capability/version')
    if quantity(proposal['deadline']) != snapshot['deadline']:
        raise ValueError('original absolute deadline required')
    if set(proposal['allowance']) - set(snapshot['remaining']):
        raise ValueError('unknown budget dimension')
    for key, value in proposal['allowance'].items():
        if quantity(value) > quantity(snapshot['remaining'][key]):
            raise ValueError('budget widening')
    q = proposal['quantities']
    if set(q) != {'demand', 'batches', 'physicalOutput', 'goalAllocation', 'surplus'}:
        raise ValueError('quantity fields')
    for value in q.values():
        quantity(value)
    if q['goalAllocation'] > q['demand'] or quantity(q['goalAllocation'] + q['surplus']) > q['physicalOutput']:
        raise ValueError('over-allocation')
    return digest(proposal)
