"""Independent collection assertions for one actual original gateway receipt prefix; no native proof producer."""
def retained_prefix(entry,expected=None):
    value=entry['intent'].get('receiptPrefix')
    if not isinstance(value,dict) or set(value)!={'receipts'}:raise ValueError('Missing actual retained original receipt prefix')
    prefix=value['receipts'];current=entry['result']['receipt']
    if len(prefix)!=2 or [r['stage'] for r in prefix]!=['HARVEST','PICKUP'] or current['stage']!='DEPOSIT':
        raise ValueError('This independent retention cohort requires its actual one-unit H/P then D chain')
    chain=[*prefix,current]
    if any(r['quantity']!=1 for r in chain):raise ValueError('Wrong native retention cohort quantity')
    if len({r['receiptId'] for r in chain})!=3 or len({r['operationId'] for r in chain})!=3:
        raise ValueError('Repeated original receipt/effect identity')
    for r in chain:
        for key in ('origin','runId','batchId','actor','source','destination','item','sourceBlock','dropIds'):
            if r[key]!=current[key]:raise ValueError('Changed original resource prefix '+key)
    if expected is not None and prefix!=expected:raise ValueError('Retained prefix differs from actual gateway publication')
    return {'receipts':len(prefix),'actualCurrentReceiptExcluded':current not in prefix,'run':current['runId'],
            'batch':current['batchId'],'dropIds':current['dropIds'],'nativeSavedLineageProved':False}
