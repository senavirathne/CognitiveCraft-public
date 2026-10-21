"""Supplementary actual D04 spatial binding and installed birch production evidence."""
from __future__ import annotations
import json
import math
import re


def language_evidence(h, seed, verify, acquisition, models, needle):
    from suite import ROOT, UUID_PATTERN, DENIED_PATTERN, require, completed_jsonl
    from birch import SRC, DST, LOG, PLANK, OAK, inventory
    corpus=json.loads((ROOT/'providers/src/test/resources/nlu/d04-requests.json').read_text())
    productions=[];refusals=[];full=[10,201,5];full_barrel=[11,201,6];barrel=[12,201,7]

    def coord(p):return ' '.join(map(str,p))
    def actor(snapshot):return next(a for a in snapshot['actors'] if a['uuid']==h.entities['PlayerA'])
    def unchanged(before,after):
        inv=lambda s:{a['uuid']:a['inventory'] for a in s['actors']}
        require(before['blocks']==after['blocks'] and before['containers']==after['containers'] and inv(before)==inv(after),
                'Unexecutable D04 request changed physical resources')
    def filled(p,stock=None):
        values=[(LOG,stock) if i==0 and stock is not None else ('minecraft:stone',64) for i in range(27)]
        return 'data merge block '+coord(p)+' {Items:['+','.join(f'{{Slot:{i}b,id:"{item}",count:{q}}}' for i,(item,q) in enumerate(values))+']}'
    def prepare(q):
        h.actions_active=False
        h.console(['setblock '+coord(full)+' minecraft:chest','setblock '+coord(full_barrel)+' minecraft:barrel',
                   filled(full),filled(full_barrel),'setblock 8 200 7 minecraft:stone',
                   'tp '+h.entities['PlayerA']+' 10.5 201 6.5','tp PlayerA 2.5 201 5.5 180 0'])
        seed((q+3)//4)
        h.wait(lambda:h.container(full,'minecraft:stone')==1728 and h.container(full_barrel,'minecraft:stone')==1728,
               'Actual nearer insufficient-capacity containers')
    def ask(row):
        reply=h.command('PlayerA','/aivillage kernel ask '+row['text'],'interpretation='+UUID_PATTERN)
        ticket=re.search('interpretation=('+UUID_PATTERN+')',reply).group(1)
        view=h.wait(lambda:(v if (v:=h.run_view('PlayerA',ticket)) and v['phase'] not in ('INTERPRETING','RESOLVING') else None),
                    'Genuine D04 language '+row['id'],40)
        h.event('d04-language-ticket',{'id':row['id'],'text':row['text'],'ticket':ticket,'view':view})
        return ticket,view
    def calls_once(before):
        after=h.model_calls()
        require(after['needleCalls']==before['needleCalls']+1 and after['generationCalls']==before['generationCalls'],
                'D04 skipped actual interpretation or generated an already admitted method')
        return after
    def refusal(row):
        before=h.snapshot();calls=h.model_calls();h.actions_active=True;ticket,view=ask(row)
        require(view['phase'] in ('CLARIFICATION','REJECTED'),'Unresolved D04 reference dispatched: '+str(view))
        h.quiet(20);after=h.snapshot();unchanged(before,after)
        result={'id':row['id'],'text':row['text'],'ticket':ticket,'view':view,'physicalBefore':before,'physicalAfter':after,
                'modelCallsBefore':calls,'modelCallsAfter':calls_once(calls)}
        refusals.append(result);h.event('d04-safe-refusal',result);h.actions_active=False

    h.console(['setblock '+coord(barrel)+' minecraft:barrel'])
    prepare(11)
    for row in corpus:
        if row['outcome']!='EXTRACTED':refusal(row)
    refusal({'id':'D04-foreign-citizen','text':'Mira, make eleven birch planks and put them in the chest closest to you.'})

    for row in corpus:
        if row['outcome']!='EXTRACTED':continue
        q=row['amount'];prepare(q)
        destination=barrel if row['type']=='BARREL' else DST if row['anchor']=='ACTOR' or row.get('destination') else SRC
        physical=h.snapshot();before=h.container(destination,PLANK);inv=inventory(h,'PlayerA');calls=h.model_calls()
        h.actions_active=True;ticket,bound=ask(row)
        require(bound['phase']=='SUBMITTED','Supported D04 spatial form not submitted: '+str(bound))
        result=verify(bound['runId'],q,before,SRC,destination,craft=True,before_inventory=inv)
        require(result['artifact']==acquisition['artifact'],'D04 changed the admitted native birch producer')
        after=h.snapshot();require(physical['blocks']==after['blocks'],'Supplied D04 harvested or placed blocks')
        require(inventory(h,'PlayerA').get(OAK,0)==inv.get(OAK,0)==3,'D04 substituted old oak stock')
        require(h.container(full,'minecraft:stone')==1728 and h.container(full_barrel,'minecraft:stone')==1728,
                'D04 changed nearer full or wrong-type candidates')
        rows=result['view']['resourceReceipts']
        require(len({r['receiptId'] for r in rows})==len(rows)==3*((q+3)//4),'D04 reused receipt credit')
        for r in rows:
            t=r['transformation'];require(t and t['recipe']['key']=='minecraft:birch_planks'
                    and t['recipe']['inputs']==1 and t['recipe']['outputs']==4 and t['remainders']==0,
                    'D04 bypassed the installed native birch recipe')
        trace=[s for s in completed_jsonl(h.root/'server/observations.jsonl')
               if physical['gameTick']<=s.get('gameTick',-1)<=after['gameTick']]
        positions=[actor(s)['pos'] for s in trace]
        require(len(set(map(tuple,positions)))>1,'No normal worker travel established')
        if row['anchor']=='ACTOR':
            # These are observations of a moving actor, not a replacement for
            # the host's once-captured anchor. The first resolving frame supplies
            # the native contrast witness; later movement must not shift it.
            anchors=[list(map(math.floor,actor(s)['pos'])) for s in trace
                     if s.get('knownPlayers',{}).get('PlayerA',{}).get('runs',{}).get(ticket,{}).get('phase') in ('INTERPRETING','RESOLVING')]
            require(anchors,'No actual binding-window actor observations')
            resolving=[s for s in trace if s.get('knownPlayers',{}).get('PlayerA',{}).get('runs',{}).get(ticket,{}).get('phase')=='RESOLVING']
            require(resolving,'No native resolving frame for actor-relative selection')
            first=resolving[0];point=list(map(math.floor,actor(first)['pos']))
            witness={'gameTick':first['gameTick'],'observedActorPoint':point,
                     'message':first['knownPlayers']['PlayerA']['runs'][ticket].get('message'),
                     'scope':'First resolving snapshot; captured-anchor stability is independently verified by core tests'}
            if row['type'] in ('CHEST','CONTAINER'):
                require(sum((point[i]-destination[i])**2 for i in range(3))<sum((point[i]-SRC[i])**2 for i in range(3)),
                        'Fixture did not distinguish selected-actor and source anchors')
                witness['sourceCentreAndActorSelectDifferentEligibleContainers']=True
            else:witness['sourceIsEligibleDestinationType']=False
        else:anchors=[];witness=None
        h.command('PlayerB','/aivillage kernel status '+bound['runId'],DENIED_PATTERN)
        h.command('PlayerB','/aivillage kernel cancel '+bound['runId'],DENIED_PATTERN)
        require(bound['runId'] not in h.suggestions('PlayerB','/aivillage kernel status '),'D04 private run leaked')
        h.actions_active=False
        if destination in (DST,barrel):
            h.walk('PlayerA',[7,201,5],reach=.7);h.walk('PlayerA',[destination[0],201,5],reach=.7)
        count=h.menu_count('PlayerA',destination,PLANK);require(count==before+q,'Actual client menu disagrees with D04 receipts')
        result.update(id=row['id'],text=row['text'],ticket=ticket,binding=bound,anchor=row['anchor'],objectType=row['type'],
                      actorPositionBefore=actor(physical)['pos'],bindingWindowActorAnchors=anchors,workerPositions=positions,
                      selectionWitness=witness,
                      physicalBefore=physical,physicalAfter=after,actorInventoryBefore=inv,actorInventoryAfter=inventory(h,'PlayerA'),
                      destinationBefore=before,destinationAfter=count,surplus=4*((q+3)//4)-q,
                      modelCallsBefore=calls,modelCallsAfter=calls_once(calls))
        productions.append(result);h.event('d04-physical-production',result);h.screenshot('PlayerA','d04-'+row['id'],chat=True)

    # A real source remains sufficient, while every observed destination has
    # zero compatible capacity. Selection must fail without physical execution.
    prepare(11)
    h.console([filled(list(map(int,k.split(','))),3 if list(map(int,k.split(',')))==SRC else None)
               for k in h.snapshot()['containers']])
    h.wait(lambda:h.container(SRC,LOG)==3 and h.container(SRC,'minecraft:stone')==1664,'Actual no-capacity fixture')
    refusal({'id':'D04-no-eligible-container','text':corpus[0]['text']})
    require(refusals[-1]['view']['phase']=='REJECTED' and refusals[-1]['view'].get('reason')=='FACILITY_MISSING',
            'No eligible destination fell back to an unauthorized or full container')
    return {'canonicalProduction':acquisition,'supportedProductions':productions,'refusals':refusals,'models':models,'needle':needle,
            'originalPhysicalProduction':'SUCCEEDED','fullTaskPass':True,'tieBreaking':'Existing exact XYZ/identity ties verified in core tests',
            'scope':'Eight supported spatial/control requests, eight semantic refusals, foreign actor and actual no-capacity refusal; frozen assessment unchanged'}
