"""Actual D05 table-item interpretation, typed barrel stock and native recipe evidence."""
from __future__ import annotations
import json
import math
import re


def language_evidence(h,verify,acquisition,models,needle):
    from suite import ROOT,UUID_PATTERN,DENIED_PATTERN,require,completed_jsonl
    from tableitems import SOURCE,TABLE,PLANK,inventory
    corpus=json.loads((ROOT/'providers/src/test/resources/nlu/d05-requests.json').read_text())
    BIRCH='minecraft:birch_planks';OAK='minecraft:oak_log';STONE='minecraft:stone'
    full=[10,201,5];full_barrel=[12,201,7];short_barrel=[11,201,6];close_chest=[7,201,9]
    productions=[];refusals=[]
    coord=lambda p:' '.join(map(str,p))
    key=lambda p:','.join(map(str,p))
    def actor(s):return next(a for a in s['actors'] if a['uuid']==h.entities['PlayerA'])
    def stock(p,items):
        return 'data merge block '+coord(p)+' {Items:['+','.join(f'{{Slot:{i}b,id:"{item}",count:{q}}}' for i,(item,q) in enumerate(items))+']}'
    def filled(p,input_stock=None):
        return stock(p,[(PLANK,input_stock) if i==0 and input_stock is not None else (STONE,64) for i in range(27)])
    def prepare(q,*,input_stock=None,wrong_species=False):
        h.actions_active=False
        supplied=4*q if input_stock is None else input_stock
        inputs=[(BIRCH,64),(OAK,64),(TABLE,11)]
        if not wrong_species:inputs.insert(0,(PLANK,supplied))
        other_inputs=[(TABLE,2)] if wrong_species else [(PLANK,3),(TABLE,2)]
        h.console(['setblock '+coord(SOURCE)+' minecraft:barrel','setblock '+coord(full)+' minecraft:chest',
                   'setblock '+coord(full_barrel)+' minecraft:barrel','setblock '+coord(short_barrel)+' minecraft:barrel',
                   'setblock '+coord(close_chest)+' minecraft:chest',stock(SOURCE,inputs),
                   stock(short_barrel,other_inputs),stock(close_chest,[(PLANK,64),(TABLE,2)]),
                   filled(full),filled(full_barrel),'tp '+h.entities['PlayerA']+' 10.5 201 6.5',
                   'tp PlayerA 2.5 201 5.5 180 0'])
        h.wait(lambda:h.container(SOURCE,PLANK)==(0 if wrong_species else supplied)
                      and h.container(SOURCE,TABLE)==11 and h.container(SOURCE,BIRCH)==64 and h.container(SOURCE,OAK)==64
                      and h.container(short_barrel,PLANK)==(0 if wrong_species else 3) and h.container(close_chest,PLANK)==64
                      and h.container(full,STONE)==h.container(full_barrel,STONE)==1728,
               'Actual D05 barrel inputs, old output and nearer ineligible stock')
    def unchanged(b,a):
        inv=lambda s:{x['uuid']:x['inventory'] for x in s['actors']}
        require(b['blocks']==a['blocks'] and b['containers']==a['containers'] and inv(b)==inv(a),
                'Unexecutable D05 request changed physical resources')
    def ask(row):
        answer=h.command('PlayerA','/aivillage kernel ask '+row['text'],'interpretation='+UUID_PATTERN)
        ticket=re.search('interpretation=('+UUID_PATTERN+')',answer).group(1)
        view=h.wait(lambda:(v if (v:=h.run_view('PlayerA',ticket)) and v['phase'] not in ('INTERPRETING','RESOLVING') else None),
                    'Genuine D05 language '+row['id'],40)
        h.event('d05-language-ticket',{'id':row['id'],'text':row['text'],'ticket':ticket,'view':view})
        return ticket,view
    def once(before):
        after=h.model_calls()
        require(after['needleCalls']==before['needleCalls']+1 and after['generationCalls']==before['generationCalls'],
                'D05 skipped actual interpretation or regenerated an admitted method')
        return after
    def refused(row,*,resource_deficit=False):
        before=h.snapshot();calls=h.model_calls();h.actions_active=True;ticket,view=ask(row)
        job=None
        if resource_deficit and view['phase']=='SUBMITTED':
            job=h.wait(lambda:(j if (j:=h.job_view('PlayerA',view['runId'])) and j['state'] in ('FAILED','SUCCEEDED') else None),
                       'Existing table responsibility retains actual input deficit',40)
            require(job['state']=='FAILED' and job['reason'] in ('RESOURCE_MISSING','KNOWLEDGE_REQUIRED')
                    and not job['children'] and not job['dependencies'] and not job['attempts'] and job['fulfilled']==0
                    and job['generation']==0 and job['usage'].get('ATTEMPTED_EFFECTS',0)==0
                    and job['usage'].get('COMMITTED_EFFECTS',0)==0,
                    'D05 resource deficit gained synthetic work, effects or fulfillment: '+str(job))
        else:
            require(view['phase'] in ('CLARIFICATION','REJECTED'),'Unsupported D05 dispatched: '+str(view))
        h.quiet(20);after=h.snapshot();unchanged(before,after)
        result={'id':row['id'],'text':row['text'],'ticket':ticket,'view':view,'physicalBefore':before,'physicalAfter':after,
                'job':job,'modelCallsBefore':calls,'modelCallsAfter':once(calls)}
        refusals.append(result);h.event('d05-safe-refusal',result);h.actions_active=False
    def selection(row,ticket,physical):
        # This is an independent fixture observation, not the bound destination
        # or a coordinate supplied back to production.
        frames=[s for s in completed_jsonl(h.root/'server/observations.jsonl')
                if s.get('gameTick',-1)>=physical['gameTick']
                and s.get('knownPlayers',{}).get('PlayerA',{}).get('runs',{}).get(ticket,{}).get('phase')=='RESOLVING']
        require(frames,'D05 has no observed resolution window')
        first=frames[0];point=list(map(math.floor,actor(first)['pos']))
        sources=[];destinations=[];anchor=point if row['destinationAnchor']=='ACTOR' else SOURCE
        for k,items in physical['containers'].items():
            p=list(map(int,k.split(',')));block=physical['blocks'].get(k,'')
            kind='BARREL' if 'minecraft:barrel' in block else 'CHEST' if 'minecraft:chest' in block or 'minecraft:trapped_chest' in block else None
            if kind is None:continue
            if kind=='BARREL' and items.get(PLANK,0)>=4*row['amount']:
                sources.append({'point':p,'stock':items[PLANK],'squaredDistance':sum((p[i]-point[i])**2 for i in range(3))})
            capacity=(27-sum((q+63)//64 for item,q in items.items() if item!=TABLE))*64-items.get(TABLE,0)
            if row['destinationType'] not in ('UNSPECIFIED','CONTAINER',kind) or capacity<row['amount']:continue
            if abs(p[0]-anchor[0])>16 or abs(p[1]-anchor[1])>4 or abs(p[2]-anchor[2])>16:continue
            destinations.append({'point':p,'type':kind,'capacity':capacity,'squaredDistance':sum((p[i]-anchor[i])**2 for i in range(3))})
        sources.sort(key=lambda x:(x['squaredDistance'],*x['point']));destinations.sort(key=lambda x:(x['squaredDistance'],*x['point']))
        require(sources and sources[0]['point']==SOURCE and destinations,'Independent D05 candidates do not establish intended binding')
        destination=list(map(int,row['destination'].split(','))) if row.get('destination') else destinations[0]['point']
        return destination,{'gameTick':first['gameTick'],'observedActorPoint':point,'destinationAnchor':anchor,
                            'eligibleBarrelSources':sources,'rankedEligibleDestinations':destinations,
                            'explicitCoordinates':bool(row.get('destination')),
                            'scope':'Independent current fixture stock, type, compatible capacity and exact squared-distance/XYZ ranking before terminal receipt checks'}

    prepare(1)
    for row in corpus:
        if row['outcome']!='EXTRACTED':refused(row)
    refused({'id':'D05-foreign-citizen','text':'Mira, I need another crafting table. Use the planks from that barrel.'})
    for row in corpus:
        if row['outcome']!='EXTRACTED':continue
        q=row['amount'];prepare(q);physical=h.snapshot();inv=inventory(h,'PlayerA');calls=h.model_calls();h.actions_active=True
        ticket,bound=ask(row);require(bound['phase']=='SUBMITTED','Supported D05 was not submitted: '+str(bound))
        destination,witness=selection(row,ticket,physical);before=physical['containers'][key(destination)].get(TABLE,0)
        result=verify(bound['runId'],q,before,inv,SOURCE,destination)
        require(result['artifact']==acquisition['artifact'],'D05 changed the admitted native item producer')
        after=h.snapshot();require(physical['blocks']==after['blocks'],'Table item production harvested or placed a block')
        require(all(h.container(SOURCE,item)==physical['containers'][key(SOURCE)][item] for item in (BIRCH,OAK)),
                'D05 consumed wrong-species or raw-log stock')
        require(h.container(SOURCE,TABLE)==11+(q if destination==SOURCE else 0),'Old source table items were credited or erased')
        require(inventory(h,'PlayerA')==inv,'Table production changed old NPC custody or retained unaccounted surplus')
        require(h.container(short_barrel,PLANK)==3 and h.container(close_chest,PLANK)==64
                and h.container(full,STONE)==h.container(full_barrel,STONE)==1728,'D05 consumed nearer ineligible stock or full candidates')
        positions=[actor(s)['pos'] for s in completed_jsonl(h.root/'server/observations.jsonl')
                   if physical['gameTick']<=s.get('gameTick',-1)<=after['gameTick']]
        h.command('PlayerB','/aivillage kernel status '+bound['runId'],DENIED_PATTERN)
        h.command('PlayerB','/aivillage kernel cancel '+bound['runId'],DENIED_PATTERN)
        require(bound['runId'] not in h.suggestions('PlayerB','/aivillage kernel status '),'D05 private run leaked')
        h.actions_active=False
        h.walk('PlayerA',[7,201,5],reach=.7)
        if destination!=SOURCE:h.walk('PlayerA',[destination[0],201,5],reach=.7)
        count=h.menu_count('PlayerA',destination,TABLE);require(count==before+q,'Real client menu disagrees with new table receipts')
        result.update(id=row['id'],text=row['text'],ticket=ticket,binding=bound,selectionWitness=witness,
                      physicalBefore=physical,physicalAfter=after,actorInventoryBefore=inv,actorInventoryAfter=inventory(h,'PlayerA'),
                      destinationBefore=before,destinationAfter=count,surplus=0,workerPositions=positions,
                      workerTravelObserved=len(set(map(tuple,positions)))>1,modelCallsBefore=calls,modelCallsAfter=once(calls))
        productions.append(result);h.event('d05-physical-production',result)
        h.command('PlayerA','/aivillage kernel status '+bound['runId'],'SUCCEEDED');h.screenshot('PlayerA','d05-'+row['id'],chat=True)
    for name,kwargs in [('insufficient-stock',{'input_stock':3}),('wrong-species-stock',{'wrong_species':True})]:
        prepare(1,**kwargs);refused({'id':'D05-'+name,'text':corpus[0]['text']},resource_deficit=True)
        last=refusals[-1]
        require(last['job'] is not None or last['view']['phase']=='REJECTED' and last['view'].get('reason') in ('RESOURCE_MISSING','KNOWLEDGE_REQUIRED'),
                'D05 fabricated, borrowed or harvested missing authorized recipe inputs')
    prepare(1)
    h.console([filled(list(map(int,k.split(','))),4 if k==key(SOURCE) else None) for k in h.snapshot()['containers']])
    h.wait(lambda:h.container(SOURCE,PLANK)==4 and h.container(SOURCE,STONE)==1664,'D05 sufficient inputs but no output capacity')
    refused({'id':'D05-no-capacity','text':corpus[0]['text']})
    require(refusals[-1]['view']['phase']=='REJECTED' and refusals[-1]['view'].get('reason')=='FACILITY_MISSING',
            'D05 ignored real table-item capacity')
    require(any(x['workerTravelObserved'] for x in productions),'No normal worker travel was observed')
    return {'canonicalProduction':acquisition,'supportedProductions':productions,'refusals':refusals,'models':models,'needle':needle,
            'originalPhysicalProduction':'SUCCEEDED','fullTaskPass':True,'noFacilityCreated':True,
            'scope':'Eight supported/control requests, ten semantic refusals, foreign citizen, two actual stock deficits and zero output capacity; no placement or upstream production'}
