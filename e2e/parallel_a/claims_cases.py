"""Frozen A08 connected denominator; missed windows never become successful negatives."""
CASES=(
    'five-logs-three-and-four-external-two','older-feasible-small-arrivals','impossible-large-useful-small',
    'complementary-inputs-atomic','initial-full-zero-effect','dynamic-fill-before-withdraw',
    'dynamic-fill-after-withdraw-custody','full-return-retains-custody','revoked-return-retains-custody',
    'lawful-return-new-action','source-destination-alias','component-stack-swap','chest-replacement',
    'double-chest-split','stale-epoch-release','cross-principal-denial','external-mutation-replan-bound',
    'crash-after-withdraw-scoped-fence-C-progress','navigation-no-storage-claim','slot-stack-swap-after-claim',
    'complementary-private-custody-residence-bound')

def service(rows):
    values=[r['grantedTick']-r['eligibleTick'] for r in rows if r['eligibleTick']>=0 and r['grantedTick']>=0]
    assert values and min(values)>=0
    return {'samples':len(values),'sortedTicks':sorted(values),'maximumTicks':max(values),
            'assumptions':'Finite <=16 tickets, stable native identity/permission/reach/capacity, sufficient stock, bounded storage acknowledgement and owner polling. No guarantee under endless interference.'}
