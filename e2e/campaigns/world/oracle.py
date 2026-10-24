"""Independent physical accounting. No world mutation and no success from receipts alone."""
from collections import defaultdict
import math

WHEAT='minecraft:wheat'

def key(pos):
    return ','.join(map(str,pos))

def custody(state,item):
    return dict(containers=sum(box.get(item,0) for box in state.get('containers',{}).values()),
                actors=sum(a.get('inventory',{}).get(item,0) for a in state.get('actors',[])),
                drops=sum(d['count'] for d in state.get('drops',[]) if d['item']==item),
                players=sum(p.get('assessmentInventory',{}).get(item,0)
                            for p in state.get('knownPlayers',{}).values()))

def receipts(view,item):
    field='receipts' if item==WHEAT else 'resourceReceipts'
    transient=view.get(field) or []
    if transient: return transient
    # Recovered bootstrap views deliberately omit transient counters. Use the actual
    # durable attempt for this execution, preserving the original raw view unchanged.
    rows=[]
    for attempt in (view.get('responsibility') or {}).get('attempts',[]):
        if attempt.get('id')==view.get('id') or any(e.get('runId')==view.get('id') for e in attempt.get('executions',[])):
            rows.extend(attempt.get(field) or [])
    return rows

def amount(receipt,item):
    return receipt.get('wheat',0) if item==WHEAT else receipt.get('quantity',0)

def stages(view,item):
    totals=defaultdict(int)
    for r in receipts(view,item): totals[r['stage']]+=amount(r,item)
    return dict(totals)

def outcome(view):
    # Current outcome can precede the last durable marker; never overwrite it with an older checkpoint.
    return (view.get('outcome') or {}).get('status') or (view.get('marker') or {}).get('outcome')

def evaluate(case,before,after,view,citizen,entity,samples):
    item=case['item']; rows=receipts(view,item); sums=stages(view,item)
    positions=case['positions']
    def eligible(s,p):
        value=s.get('blocks',{}).get(key(p),'UNKNOWN')
        return item in value and (item!=WHEAT or 'age=7' in value)
    harvested=sum(eligible(before,p) and 'minecraft:air' in after.get('blocks',{}).get(key(p),'') for p in positions)
    unknown=any(key(p) not in after.get('blocks',{}) for p in positions)
    stock=lambda s:s.get('containers',{}).get(key(case['destination']),{}).get(item,0)
    deposited=stock(after)-stock(before)
    initial,final=custody(before,item),custody(after,item)
    batches=defaultdict(lambda:defaultdict(int))
    ids=set(); lineage=True; identity=True
    for r in rows:
        if r.get('receiptId') in ids: lineage=False
        ids.add(r.get('receiptId'))
        actor=r.get('actor',{})
        identity &= actor.get('citizenId')==citizen and actor.get('entityId')==entity and r.get('runId')==view.get('id')
        dst=r.get('destination',{})
        identity &= [dst.get(k) for k in ('x','y','z')]==case['destination']
        if item!=WHEAT: identity &= r.get('item',{}).get('id')==item
        batches[r.get('batchId')][r['stage']]+=amount(r,item)
    lineage &= all(b['DEPOSIT']<=b['PICKUP']<=b['HARVEST'] for b in batches.values())
    original=next(a['pos'] for a in before['actors'] if a['uuid']==entity)
    moves=[a['pos'] for s in samples+[after] for a in s.get('actors',[]) if a['uuid']==entity]
    moved=bool(moves) and max(math.dist(original,p) for p in moves)>.5
    success=outcome(view)=='SUCCEEDED'
    props=dict(conservation=not unknown and sum(final.values())==sum(initial.values())+harvested,
               receiptIdentity=identity, pickupLineage=lineage,
               harvestAttribution=sums.get('HARVEST',0)<=harvested,
               depositAttribution=sums.get('DEPOSIT',0)==deposited,
               truthfulCompletion=not success or (deposited==case['quantity'] and
                    all(sums.get(stage,0)==case['quantity'] for stage in ('HARVEST','PICKUP','DEPOSIT'))),
               delivery=success and deposited==case['quantity'],harvest=harvested==case['quantity'],
               movementObserved=moved,navigation=moved and sums.get('HARVEST',0)>0 and harvested>0)
    actor_stock=lambda s:next(a['inventory'].get(item,0) for a in s['actors'] if a['uuid']==entity)
    props['actorCustodyAttribution']=actor_stock(after)-actor_stock(before)==sums.get('PICKUP',0)-sums.get('DEPOSIT',0)
    props['immaturePreserved']=all(before['blocks'].get(key(p))==after['blocks'].get(key(p)) for p in case.get('immature',[]))
    props['unrelatedPreserved']=all(before['blocks'].get(key(p))==after['blocks'].get(key(p)) for p in case.get('unrelated',[]))
    props['standingSupportsPreserved']=all(before['blocks'].get(key(p))==after['blocks'].get(key(p)) for p in case.get('standingSupports',[]))
    return props,dict(harvestedBlocks=harvested,destinationDelta=deposited,receipts=sums,
                      custodyBefore=initial,custodyAfter=final,observationMissing=unknown)

SAFETY=('conservation','receiptIdentity','pickupLineage','harvestAttribution','depositAttribution','truthfulCompletion','immaturePreserved','unrelatedPreserved','standingSupportsPreserved','actorCustodyAttribution')
