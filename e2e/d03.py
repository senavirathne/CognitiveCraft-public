"""D03 supplementary genuine language, existing-stock selection and native axe conservation."""
from __future__ import annotations
import json
import re


def language_evidence(h, prepare, verify, acquisition, typed_interface, models, needle):
    from suite import ROOT, UUID_PATTERN, require
    from axes import SOURCE, DESTINATION, LOG, STICK, PLANK, actor_inventory
    corpus=json.loads((ROOT/'providers/src/test/resources/nlu/d03-requests.json').read_text())
    refusals=[];productions=[];shortages=[]

    def ask(row):
        reply=h.command('PlayerA','/aivillage kernel ask '+row['text'],'interpretation='+UUID_PATTERN)
        ticket=re.search('interpretation=('+UUID_PATTERN+')',reply).group(1)
        view=h.wait(lambda:(v if (v:=h.run_view('PlayerA',ticket)) and v['phase'] not in ('INTERPRETING','RESOLVING') else None),
                    'Genuine D03 language '+row['id'],40)
        h.event('d03-language-ticket',{'id':row['id'],'text':row['text'],'ticket':ticket,'view':view})
        return ticket,view

    def unchanged(before,after):
        inventory=lambda snap:{a['uuid']:a.get('inventory',{}) for a in snap['actors']}
        require(before['blocks']==after['blocks'] and before['containers']==after['containers']
                and inventory(before)==inventory(after),'Unexecutable D03 request changed physical resources')

    def calls_once(before):
        after=h.model_calls()
        require(after['needleCalls']==before['needleCalls']+1 and after['generationCalls']==before['generationCalls'],
                'D03 skipped genuine language inference or generated an existing method')
        return after

    prepare(3)
    for row in corpus:
        if row['outcome']=='EXTRACTED':continue
        before=h.snapshot();calls=h.model_calls();h.actions_active=True;ticket,view=ask(row)
        require(view['phase'] in ('CLARIFICATION','REJECTED'),'Unsupported D03 request dispatched: '+str(view))
        h.quiet(20);after=h.snapshot();unchanged(before,after)
        result={'id':row['id'],'text':row['text'],'ticket':ticket,'view':view,'physicalBefore':before,'physicalAfter':after,
                'modelCallsBefore':calls,'modelCallsAfter':calls_once(calls)}
        refusals.append(result);h.event('d03-safe-refusal',result);h.actions_active=False

    for row in corpus:
        if row['outcome']!='EXTRACTED':continue
        q=row['amount'];source=prepare(q)
        destination=DESTINATION if row['id'] in ('D03-would-like','D03-coordinates') else SOURCE
        # A closer container has enough primary material but lacks a stick.
        # Existing coupled observation must prefer the fully stocked source.
        if row['id']=='D03-original':
            h.console(['item replace block 9 201 7 container.25 with minecraft:oak_planks 9',
                       'item replace block 9 201 7 container.26 with minecraft:stick 5'])
            h.wait(lambda:h.container(DESTINATION,LOG)==9 and h.container(DESTINATION,STICK)==5,'Real incomplete-input distractor')
        before=h.container(destination,PLANK);inventory=actor_inventory('PlayerA',h);calls=h.model_calls();h.actions_active=True
        physical=h.snapshot();ticket,view=ask(row);require(view['phase']=='SUBMITTED','D03 supported goal not submitted: '+str(view))
        result=verify(view['runId'],q,before,inventory,source,destination)
        require(result['artifact']==acquisition['artifact'],'D03 did not reuse existing admitted axe producer')
        require(len({tuple(p) for p in result['workerPositions']})>1,'No real worker movement')
        receipts=result['receipts'];units={item:sum(r['quantity'] for r in receipts if r['stage']=='WITHDRAW' and r['item']['id']==item) for item in (LOG,STICK)}
        require(units=={LOG:q*3,STICK:q*2},'D03 lost one of the two exact ingredient roles')
        require(h.snapshot()['blocks']==physical['blocks'],'Stocked D03 unnecessarily harvested or placed blocks')
        result.update(id=row['id'],text=row['text'],ticket=ticket,ingredientUnits=units,modelCallsBefore=calls,modelCallsAfter=calls_once(calls),
                      actorPositionBefore=next(a['pos'] for a in physical['actors'] if a['uuid']==h.entities['PlayerA']),
                      actorInventoryBefore=inventory,actorInventoryAfter=actor_inventory('PlayerA',h),destinationBefore=before,destinationAfter=h.container(destination,PLANK))
        if row['id']=='D03-original':
            require(h.container(DESTINATION,LOG)==9 and h.container(DESTINATION,STICK)==5,'Incomplete closer stock was consumed')
            result['closerInsufficientStockUnchanged']={LOG:9,STICK:5}
        productions.append(result);h.event('d03-physical-production',result)
        h.screenshot('PlayerA','d03-'+row['id'],chat=True);h.actions_active=False
        if row['id']=='D03-original':
            h.console(['data remove block 9 201 7 Items[{id:"minecraft:oak_planks"}]',
                       'data remove block 9 201 7 Items[{id:"minecraft:stick"}]'])
            h.wait(lambda:h.container(DESTINATION,LOG)==h.container(DESTINATION,STICK)==0,'Next independent fixture preserves old axes')

    # Existing prerequisite admission may accept the goal responsibility, but it
    # cannot invent unadmitted plank/stick producers or unavailable input stock.
    for label,item,count in [('primary',LOG,8),('secondary',STICK,5)]:
        prepare(3);slot=0 if item==LOG else 1
        h.console([f'item replace block 5 201 5 container.{slot} with {item} {count}'])
        h.wait(lambda:h.container(SOURCE,item)==count,'Actual D03 '+label+' deficit')
        row={'id':'D03-observed-'+label+'-shortage','text':'We need three wooden axes from chest at 5,201,5.'}
        before=h.snapshot();calls=h.model_calls();h.actions_active=True;ticket,view=ask(row);job=None
        if view['phase']=='SUBMITTED':
            job=h.wait(lambda:(j if (j:=h.job_view('PlayerA',view['runId'])) and j['state'] in ('FAILED','SUCCEEDED') else None),
                       'Existing prerequisite resolution retains real material deficit',40)
            require(job['state']=='FAILED' and job['reason'] in ('RESOURCE_MISSING','KNOWLEDGE_REQUIRED'),
                    'Missing materials became a successful or unrelated goal: '+str(job))
            require(not job['children'] and not job['attempts'] and job['fulfilled']==0,'Deficient stock gained synthetic work or credit')
        else:
            require(view['phase']=='REJECTED' and view.get('reason') in ('RESOURCE_MISSING','KNOWLEDGE_REQUIRED'),
                    'D03 shortage was not a grounded resource/prerequisite rejection: '+str(view))
        h.quiet(20);after=h.snapshot();unchanged(before,after)
        result={'id':row['id'],'text':row['text'],'ticket':ticket,'view':view,'job':job,'physicalBefore':before,'physicalAfter':after,
                'required':{LOG:9,STICK:6},'available':{LOG:h.container(SOURCE,LOG),STICK:h.container(SOURCE,STICK)},
                'modelCallsBefore':calls,'modelCallsAfter':calls_once(calls)}
        shortages.append(result);h.event('d03-grounded-shortage',result);h.actions_active=False

    return {'canonicalProduction':acquisition,'typedInterface':typed_interface,'supportedProductions':productions,'refusals':refusals,
            'observedShortages':shortages,'models':models,'needle':needle,'originalPhysicalProduction':'SUCCEEDED','fullTaskPass':True,
            'scope':'Original and four new supported goal forms plus unchanged imperative control; eight safe semantic refusals and two observed ingredient shortages; frozen assessment unchanged'}
