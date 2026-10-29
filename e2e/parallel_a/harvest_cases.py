"""Frozen original log-H selection. No P/D or wheat case is relabelled as a log-H pass."""
LOG_H_CASE_IDS=('heldout-W6-002','heldout-W6-008','W6-002')
LOGS={'minecraft:oak_log','minecraft:birch_log'}

def select_case(manifest,case_id):
    if case_id not in LOG_H_CASE_IDS:raise ValueError('Not a supported original log-H case: '+case_id)
    cases=[c for c in manifest['cases'] if c['id']==case_id]
    if len(cases)!=1:raise ValueError('Original case missing or duplicated')
    case=cases[0]
    if case['checkpoint']!='HARVEST' or case['item'] not in LOGS or case['requested'] not in (2,3):raise ValueError('Original log-H envelope changed')
    return case

def fixture_positions(case):
    positions=[[73,208,73],[76,208,74],[79,208,75]][:case['requested']]
    source=case['source']
    if len(positions)!=case['requested'] or any(not all(source['min'+a]<=p[n]<=source['max'+a] for n,a in enumerate(('X','Y','Z'))) for p in positions):
        raise ValueError('Selected native log fixture does not fit original geometry')
    return positions

def summarize(reviews,manifest):
    required=set(LOG_H_CASE_IDS);ids=[row['originalCase']['id'] for row in reviews]
    if len(ids)!=len(required) or set(ids)!=required:raise ValueError('Every distinct required original case must be reviewed exactly once')
    rows=[];keys=set()
    for row in reviews:
        case=select_case(manifest,row['originalCase']['id'])
        if row['originalCase']!=case or row['status']!='PASS_FIRST_H_REVIEWED_ONLY' or row['originalOwnerSettlements']!=1 or row['a06Qualified'] is not False:
            raise ValueError('Current raw-native original-owner proof did not pass')
        key=row['key'];identity=tuple(key[k] for k in ('world','root','step','attempt','generation','ordinal'))
        if identity in keys or key['root']!=key['step'] or key['ordinal']!=1:raise ValueError('Reused or wrong original effect identity')
        keys.add(identity);job=row['originalJob'];attempt=job['attempts'][-1]
        if job['id']!=key['step'] or job['generation']!=key['generation'] or job['state']!='CANCELLED' or attempt['id']!=key['attempt'] or attempt['uncertain'] or attempt['effects']!=1 or attempt['credited']!=0:
            raise ValueError('Original owner has not settled exactly')
        receipt=row['nativeSavedProof']['entry']['result']['receipt']
        if receipt['stage']!='HARVEST' or receipt['item']!=case['item'] or receipt['source']!=[case['source'][k] for k in ('minX','minY','minZ','maxX','maxY','maxZ')] or receipt['destination']!=[case['destination'][k] for k in ('x','y','z')]:
            raise ValueError('Original material or geometry changed')
        if job['request']['request']['arguments']['amount']['value']!=case['requested']:raise ValueError('Original requested quantity changed')
        rows.append({'id':case['id'],'checkpoint':case['checkpoint'],'item':case['item'],'requested':case['requested'],'key':key,'state':'CANCELLED','effects':1,'credited':0,'status':'PASS_ORIGINAL_SAVED_H_OWNER'})
    remaining=[c['id'] for c in manifest['cases'] if c['id'] not in required]
    if len(manifest['cases'])!=12 or len(remaining)!=9:raise ValueError('Original denominator changed')
    return {'schema':1,'status':'PASS_THREE_ORIGINAL_LOG_H_CASES_ONLY','originalOwnerSettlements':len(required),'requiredOriginalCases':12,
            'cases':rows,'remainingRequiredCases':remaining,'a06Qualified':False,'nativePickupProducer':'INACTIVE',
            'claim':'Three distinct frozen original H requests are saved, killed, reopened and settled. P/D, wheat and all other A06 gates remain required.'}
