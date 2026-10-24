"""Deterministic bounded world campaign corpus. No Minecraft actions at generation time."""
import hashlib
import json
import random
from itertools import combinations
from functools import lru_cache
from pathlib import Path

BASELINE = 'ea7de8000b2b484b885576c34ed5358274936ce9'
TREE = 'f0915a127521cb32f45e606c39098011796d1f1d'
FAMILIES = ('W1', 'W2', 'W3', 'W4', 'W5', 'W6', 'W7', 'W8', 'W9', 'W10')

def point(x, z, dy=0):
    return [64+x, 208+dy, 64+z]

def _design(rng, count):
    """Greedily cover previously unseen parameter pairs; no Cartesian explosion."""
    domains = dict(item=('oak_log','birch_log','wheat'), quantity=(1,2,3,4),
                   geometry=('line','separated','vertical'), container=('chest','barrel'),
                   terrain=('flat','detour','corridor'), logAxis=('x','y','z'))
    candidates = [dict(zip(domains, (rng.choice(v) for v in domains.values()))) for _ in range(1200)]
    candidates = [c for c in candidates if c['item']!='wheat' or c['geometry']!='vertical']
    covered=set(); selected=[]
    def pairs(c):
        return {(a,c[a],b,c[b]) for a,b in combinations(domains,2)}
    for _ in range(count):
        eligible=[c for c in candidates if not selected or sum(c[k]!=selected[-1][k] for k in domains)>=3]
        row=max(eligible,key=lambda c:len(pairs(c)-covered))
        selected.append(row); covered.update(pairs(row)); candidates.remove(row)
    return selected

@lru_cache(maxsize=8)
def generate(seed=104729, variations=12):
    if variations < 12:
        raise ValueError('Frozen family domain requires at least twelve variations')
    cases = []
    for family in FAMILIES:
        rng = random.Random(f'{seed}:{family}')
        for i, factors in enumerate(_design(rng, variations)):
            quantity, item, geometry = (factors[k] for k in ('quantity','item','geometry'))
            if family=='W7': item='oak_log'
            if family in ('W8','W9','W10'): item='wheat'
            if item=='wheat' and geometry=='vertical': geometry='line'
            x, z = rng.randint(6, 9), rng.randint(7, 9)
            positions = [point(x + (j * (2 if geometry == 'separated' else 1) if geometry != 'vertical' else 0),
                               z,j if geometry == 'vertical' else 0) for j in range(quantity)]
            capacity_mode=('empty','merge','exact','full')[i%4] if family=='W4' else 'empty'
            slots=[]
            if capacity_mode in ('exact','full'):
                slots=[(slot,'minecraft:cobblestone',64) for slot in range(26)]
                slots.append((26,'minecraft:'+item,64-(quantity if capacity_mode=='exact' else 0)))
            elif capacity_mode=='merge':
                slots=[(0,'minecraft:'+item,60)]+[(slot,'minecraft:cobblestone',64) for slot in range(1,14)]
            else:
                slots=[(slot,'minecraft:cobblestone',64) for slot in range((i*7)%20)]
            existing=sum(q for _,t,q in slots if t=='minecraft:'+item)
            actor_slots=(0,6,7,8)[i%4] if family=='W3' else 0
            disturbance=('cancel','break','restart-harvest','restart-pickup','restart-deposit','gate')[i%6] if family=='W6' else None
            if disturbance in ('restart-deposit','gate'):
                # More than one source gives an observable partial delivery boundary.
                quantity=4
                geometry='line'
                positions=[point(7+j,8) for j in range(quantity)]
            case = dict(id=f'{family}-{i:03d}', family=family, seed=seed, index=i,
                        item='minecraft:'+item, quantity=quantity, positions=positions,
                        geometry=geometry, actor=point(2+i%3,2), destination=point(17,6+i%5),
                        container=factors['container'], occupiedSlots=len(slots), destinationSlots=slots,
                        existingStock=existing, capacityMode=capacity_mode,
                        actorOccupiedSlots=actor_slots, terrain=factors['terrain'],
                        actorChoice=i%2,
                        logAxis=factors['logAxis'], citizenName=f'Citizen_{seed}_{i}',
                        lifecycle=('rename','reconnect','unload','restart')[i%4] if family=='W5' else None,
                        disturbance=disturbance, ownership='PlayerA',
                        expectedCompletion=capacity_mode!='full' and actor_slots<8,
                        immature=[point(15,12)] if item=='wheat' and family=='W2' else [],
                        unrelated=[point(16,12)], nearbyUnrelated='minecraft:cobblestone')
            if family=='W7':
                case.update(destination=point(7,5),reference=('that','this','the','nearest suitable')[i%4],
                            distractorFull=point(4,4),distractorWrongType=point(5,5),distractorFar=point(15,11))
            if family=='W8':
                case.update(destination=point(13,4),terrain='flat',
                            legacyMode=('harvest-replant','plant','pickup','delivery','cancel','auto-release','legacy-death')[i%7])
            if family=='W9':
                case.update(terrain='flat',authorityMode=('competition','cross-principal','foreign-control')[i%3],
                            secondaryDestination=point(18,12))
            if family=='W10':
                case.update(terrain='flat',deathMode=('death','death-restart','same-name-successor')[i%3])
            case['standingSupports']=([[positions[0][0]+1,208,positions[0][2]]]
                if item!='wheat' and geometry=='vertical' and quantity>3 else [])
            points=positions+case['immature']+case['unrelated']
            lo=[min(p[k] for p in points) for k in range(3)]; hi=[max(p[k] for p in points) for k in range(3)]
            if (hi[0]-lo[0]+1)*(hi[1]-lo[1]+1)*(hi[2]-lo[2]+1)>256:
                points=positions+case['immature']
                lo=[min(p[k] for p in points) for k in range(3)]; hi=[max(p[k] for p in points) for k in range(3)]
            case['sourceBounds']=lo+hi
            case['caseSha256'] = hashlib.sha256(json.dumps(case,sort_keys=True).encode()).hexdigest()
            cases.append(case)
    return cases

def freeze(path, seed=104729):
    cases=generate(seed)
    body=dict(schema=1,baseline=BASELINE,productionTree=TREE,seed=seed,cases=cases)
    Path(path).write_text(json.dumps(body,indent=2)+'\n')
    return body
