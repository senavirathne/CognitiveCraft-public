"""Frozen A09 native denominator; fixture selection never replaces positive runtime behavior."""
CASES=('two-segments-saved-checkpoint-restart','historical-credit-current-stock','expired-original-deadline',
       'private-runtime-boundary','legacy-native-delivery','legacy-HARVEST-original',
       'legacy-PICKUP-original','legacy-DEPOSIT-original','legacy-unsaved-pickup-fence')

REGRESSIONS={'HARVEST':'heldout-W6-002','PICKUP':'W6-003','DEPOSIT':'W6-004'}

def selected_originals(cases):
    by_id={c['id']:c for c in cases};selected={stage:by_id[id] for stage,id in REGRESSIONS.items()}
    assert all(c['checkpoint']==stage for stage,c in selected.items())
    return selected

def complete(rows):
    assert len(rows)==len(CASES) and [r['id'] for r in rows]==list(CASES)
    assert all(r['status']=='PASS_NATIVE_CANDIDATE_PENDING_REVIEW' for r in rows)
    return {'status':'PASS_ALL_A09_NATIVE_CANDIDATES','requiredCases':len(CASES),'omittedTests':0}
