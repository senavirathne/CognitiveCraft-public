"""Deterministic bounded world campaign corpus. No Minecraft actions at generation time."""
import hashlib
import json
import random
from pathlib import Path

BASELINE = 'ea7de8000b2b484b885576c34ed5358274936ce9'
TREE = 'f0915a127521cb32f45e606c39098011796d1f1d'
FAMILIES = ('W1', 'W2', 'W3', 'W4', 'W5', 'W6')

def generate(seed=104729, variations=12):
    if variations < 12:
        raise ValueError('Frozen family domain requires at least twelve variations')
    cases = []
    for family in FAMILIES:
        rng = random.Random(f'{seed}:{family}')
        for i in range(variations):
            quantity = 1 + i % 4
            item = ('oak_log', 'birch_log', 'wheat')[i % 3]
            geometry = ('line', 'separated', 'vertical')[i // 3 % 3]
            if item == 'wheat' and geometry == 'vertical':
                geometry = 'separated'
            x, z = rng.randint(6, 9), rng.randint(7, 9)
            positions = [[x + (j * (2 if geometry == 'separated' else 1) if geometry != 'vertical' else 0),
                          201 + (j if geometry == 'vertical' else 0), z] for j in range(quantity)]
            case = dict(id=f'{family}-{i:03d}', family=family, seed=seed, index=i,
                        item='minecraft:'+item, quantity=quantity, positions=positions,
                        geometry=geometry, actor=[2+i%3,201,2], destination=[17,201,6+i%5],
                        container=('chest','barrel')[i%2], occupiedSlots=(i*7)%27,
                        existingStock=i%3, terrain=('flat','detour')[i%2],
                        logAxis=('x','y','z')[i%3], citizenName=f'Citizen_{i}',
                        disturbance=('cancel','break','restart')[i%3] if family=='W6' else None,
                        ownership='PlayerA', nearbyUnrelated='minecraft:cobblestone')
            case['caseSha256'] = hashlib.sha256(json.dumps(case,sort_keys=True).encode()).hexdigest()
            cases.append(case)
    return cases

def freeze(path, seed=104729):
    cases=generate(seed)
    body=dict(schema=1,baseline=BASELINE,productionTree=TREE,seed=seed,cases=cases)
    Path(path).write_text(json.dumps(body,indent=2)+'\n')
    return body
