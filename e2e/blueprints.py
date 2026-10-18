"""GP-RB-08: actual network preflight, existing producers, physical claims and saved no-replay."""
from __future__ import annotations
import json,re,time,urllib.request
PLANK='minecraft:oak_planks';LOG='minecraft:oak_log'
SRC=[5,201,5];DST=[9,201,7];SECOND=[15,201,9]
SITE=[15,201,13];LO=[6,201,14];HI=[13,201,14]

def body(n):
    width=min(n,8)
    return {'schema':1,'dimensions':{'x':width,'y':1,'z':(n+width-1)//width},
            'constraints':{'supportedGround':True,'nonHazardous':True,'perimeter':0},
            'palette':[{'block':PLANK,'properties':{}}],
            'cells':[{'x':i%width,'y':0,'z':i//width,'palette':0} for i in range(n)]}

def scenario(h):
    from suite import UUID_PATTERN,DENIED_PATTERN,require,read,reconnect_players
    require(h.topology=='compose','Staging requires actual connected Minecraft clients')
    h.env.update(COGNITIVECRAFT_OLLAMA_MODEL='qwen3:4b-instruct-2507-q4_K_M',COGNITIVECRAFT_NEEDLE_DIR='',COGNITIVECRAFT_E2E_SNAPSHOT_TICKS='1')
    h.compose('up','-d','ollama');h.compose('exec','-T','ollama','ollama','pull',h.env['COGNITIVECRAFT_OLLAMA_MODEL'])
    def tags():
        try:return json.load(urllib.request.urlopen('http://127.0.0.1:11434/api/tags',timeout=5))
        except OSError:return None
    models=h.wait(tags,'Genuine existing oak producer backend',60)
    req=urllib.request.Request('http://127.0.0.1:11434/api/generate',data=json.dumps({'model':h.env['COGNITIVECRAFT_OLLAMA_MODEL'],'stream':False,'keep_alive':'30m'}).encode(),headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(req,timeout=120) as response:require(json.load(response)['done'],'Genuine model prewarm failed')
    h.fresh(two=True);h.enroll();h.enroll('PlayerB','Mira');h.release_gate();h.release_gate('PlayerB')
    h.console(['setblock '+' '.join(map(str,p))+' minecraft:chest' for p in (SRC,SECOND)])
    def seed(planks=0,logs=0,source=SRC):
        h.actions_active=False
        values=[f'{{Slot:{i}b,id:"{item}",count:{q}}}' for i,(item,q) in enumerate(((PLANK,planks),(LOG,logs))) if q]
        h.console(['data merge block '+' '.join(map(str,source))+' {Items:['+','.join(values)+']}'])
        h.wait(lambda:h.container(source,PLANK)==planks and h.container(source,LOG)==logs,'Actual staged/input stock fixture',20)
    def logs(q=8):h.console([f'setblock {x} {LO[1]} {LO[2]} '+('minecraft:oak_log' if x<LO[0]+q else 'minecraft:air') for x in range(LO[0],HI[0]+1)])
    def inventory(player='PlayerA'):return next(a['inventory'] for a in h.snapshot()['actors'] if a['uuid']==h.entities[player]).copy()
    def run(text):
        answer=h.command('PlayerA',text,'run='+UUID_PATTERN);require('accepted=true' in answer,'Existing producer rejected '+answer);h.actions_active=True
        return re.search('run=('+UUID_PATTERN+')',answer).group(1)
    require('plankCatalog=[]' in h.command('PlayerA','/aivillage kernel catalog','catalog='),'Producer was seeded as admitted')
    h.command('PlayerA','/aivillage kernel inference true','admission=true');admissions=[];artifacts={}
    logs();seed()
    for cap,text in [('cognitivecraft:deliver_oak_logs','/aivillage kernel gather-oak '+h.citizens['PlayerA']+' 1 '+' '.join(map(str,LO+HI+SRC))),
                     ('cognitivecraft:produce_oak_planks','/aivillage kernel make-planks '+h.citizens['PlayerA']+' 4 '+' '.join(map(str,SRC+DST)))]:
        if cap.endswith('planks'):seed(0,1)
        rid=run(text);v=h.terminal('PlayerA',rid,650);require(v.get('marker',{}).get('outcome')=='ADMITTED' and v.get('resourceReceipts'),'Genuine existing method admission failed '+v['description'])
        artifacts[cap]=v['marker']['artifactSha256'];admissions.append(v);h.screenshot('PlayerA','rb008-genuine-'+cap.split(':')[1],chat=True);h.actions_active=False
    h.command('PlayerA','/aivillage kernel inference false','admission=false')
    def store(data,player='PlayerA'):
        answer=h.command(player,'/aivillage kernel blueprint-store '+json.dumps(data,separators=(',',':')),'blueprint=[0-9a-f]{64} status=STORED')
        return re.search('blueprint=([0-9a-f]{64})',answer).group(1)
    ref=store(body(5));site_end=[SITE[0]+4,SITE[1],SITE[2]]
    def request(mode='stage',player='PlayerA',source=SRC,blueprint=ref,origin=SITE,end=None):
        end=end or [origin[0]+4,origin[1],origin[2]]
        text='/aivillage kernel blueprint-materials '+mode+' '+h.citizens[player]+' '+blueprint+' 0 '+' '.join(map(str,origin+origin+end+source+source+LO+HI))
        answer=h.command(player,text,'mode='+('PREFLIGHT' if mode=='preflight' else 'STAGE_MATERIALS')+' job=')
        if mode=='stage' and 'accepted=false reason=BUDGET_EXHAUSTED' in answer:
            def acknowledged():
                nonlocal answer
                answer=h.command(player,text,'mode=STAGE_MATERIALS job=')
                return answer if 'accepted=false reason=BUDGET_EXHAUSTED' not in answer else None
            h.wait(acknowledged,'Durable blueprint Job publication',20)
        return answer
    def submit(**kwargs):
        answer=request(**kwargs);require('accepted=true' in answer,'Supported staging rejected '+answer);h.actions_active=True
        return re.search('job=('+UUID_PATTERN+')',answer).group(1)
    def graph(jid,player='PlayerA'):
        rows=h.snapshot()['knownPlayers'][player]['jobs'];out=[]
        def visit(j):
            n=rows[j];out.append(n)
            for c in n['children']:visit(c)
        visit(jid);return out
    def complete(jid,expected,player='PlayerA',source=SRC):
        def finished():
            j=h.job_view(player,jid)
            if j and j['state'] in ('FAILED','CANCELLED','INTERRUPTED'):raise AssertionError('Staging stopped '+json.dumps(j))
            return j if j and j['state']=='SUCCEEDED' else None
        root=h.wait(finished,'Current physical material staging '+jid,220)
        answer=h.command(player,'/aivillage kernel blueprint-status '+jid,'currentlyAllocated=true')
        require(f'allocated={expected}' in answer and h.container(source,PLANK)==expected,'Staging claim differs from current items')
        nodes=graph(jid,player);deposited=sum(r['quantity'] for n in nodes for a in n['attempts'] for r in a.get('resourceReceipts',[]) if r['stage']=='DEPOSIT' and r['item']['id']==PLANK)
        require(not root['attempts'] and root['fulfilled']==0,'SUPPLY fabricated manufacture or executed its root')
        for node in nodes[1:]:require(node['preparation']['method']['sha256']==artifacts[node['request']['request']['capability']['name']],'Changed existing admitted method')
        h.actions_active=False
        return {'job':jid,'root':root,'graph':nodes,'deposited':deposited,'status':answer,'source':source}
    def cancel(jid,player='PlayerA'):
        h.cancel_job(player,jid);v=h.wait(lambda:(j if (j:=h.job_view(player,jid)) and j['state']=='CANCELLED' else None),'Staging cancellation fence',40);h.actions_active=False;return v
    seed(2,1);logs();calls=h.model_calls();before_jobs=set(h.snapshot()['knownPlayers']['PlayerA']['jobs']);before_stock=h.snapshot()['containers']
    preflight=request('preflight');require('job=null accepted=true' in preflight and 'minecraft:oak_planks=3' in preflight,'Wrong read-only shortage '+preflight)
    require(set(h.snapshot()['knownPlayers']['PlayerA']['jobs'])==before_jobs and h.snapshot()['containers']==before_stock and h.model_calls()==calls,'Preflight created work, effects or inference')
    h.screenshot('PlayerA','rb008-read-only-preflight',chat=True)
    # Installed structural data support does not imply accepted producers for those items.
    unsupported_ref=store({'schema':1,'dimensions':{'x':1,'y':1,'z':1},'constraints':{'supportedGround':True,'nonHazardous':True,'perimeter':0},'palette':[{'block':'minecraft:oak_slab','properties':{'type':'bottom'}}],'cells':[{'x':0,'y':0,'z':0,'palette':0}]})
    unsupported=request(blueprint=unsupported_ref,end=SITE);require('accepted=false' in unsupported and 'missing-producer-and-staging-support:minecraft:oak_slab' in unsupported,'Missing slab support started hidden work')
    require(set(h.snapshot()['knownPlayers']['PlayerA']['jobs'])==before_jobs and h.model_calls()==calls,'Unsupported staging created work or generated a method')
    h.screenshot('PlayerA','rb008-explicit-missing-producer',chat=True)
    staged=[];client_containers=[]
    for p,l,expected_new in ((5,0,0),(2,1,3),(0,1,5)):
        seed(p,l);logs();calls=h.model_calls();inv=inventory();jid=submit();row=complete(jid,5)
        require(row['deposited']==expected_new and h.model_calls()==calls,'Staging manufactured the wrong shortage or inferred')
        surplus=(expected_new+3)//4*4-expected_new;require(inventory().get(PLANK,0)==inv.get(PLANK,0)+surplus,'Actual batch surplus was lost')
        require(all('minecraft:oak_planks' not in h.snapshot()['blocks'][f'{x},201,13'] for x in range(15,20)),'Staging constructed a blueprint cell')
        actual=h.menu_count('PlayerA',SRC,PLANK);require(actual==5,'Actual connected staging menu mismatch');client_containers.append({'player':'PlayerA','expected':5,'actual':actual})
        h.screenshot('PlayerA','rb008-staged-shortage-'+str(expected_new),chat=True);staged.append(row)
        if expected_new==0:
            foreign=h.command('PlayerB','/aivillage kernel blueprint-status '+jid,DENIED_PATTERN)
            foreign_cancel=h.command('PlayerB','/aivillage kernel job-cancel '+jid,DENIED_PATTERN)
            require(jid not in h.suggestions('PlayerB','/aivillage kernel blueprint-status '),'Private staging UUID leaked')
            logs(0);blocked=request('preflight');require('accepted=false' in blocked,'A competing request counted already claimed stock as free')
            h.screenshot('PlayerB','rb008-private-staging-denial',chat=True)
        cancel(jid)
    # Remove currently staged stock: old success/receipts must not create another physical allocation.
    seed(5,0);jid=submit();complete(jid,5);seed(4,0)
    removed=h.command('PlayerA','/aivillage kernel blueprint-status '+jid,'currentlyAllocated=false');cancel(jid)
    # Real stopped generator, independent JVM, same release/save, fresh principal's citizen/bindings.
    h.compose('stop','ollama');require(tags() is None,'Actual backend remained running')
    cold=read(h.root/'server/process.json');h.env['COGNITIVECRAFT_OLLAMA_MODEL']='';h.stop('server');h.start('server');reconnect_players(h,['PlayerA','PlayerB']);warm=read(h.root/'server/process.json')
    require(warm['start']>cold['start'] and warm['releaseSha256']==cold['releaseSha256'],'Offline staging reused the original JVM or a changed release')
    store(body(5),'PlayerB');seed(1,1,SECOND);logs();calls=h.model_calls();offline_id=submit(player='PlayerB',source=SECOND);offline=complete(offline_id,5,'PlayerB',SECOND)
    require(offline['deposited']==4 and h.model_calls()==calls,'Known stopped-backend staging inferred or miscounted stock')
    actual=h.menu_count('PlayerB',SECOND,PLANK);require(actual==5,'Fresh principal connected menu mismatch');client_containers.append({'player':'PlayerB','expected':5,'actual':actual});h.screenshot('PlayerB','rb008-offline-fresh-citizen-staging',chat=True);cancel(offline_id,'PlayerB')
    large=store(body(64));large_origin=[4,201,6];large_end=[11,201,13]
    seed(0,0);logs();bounded=request('preflight',blueprint=large,origin=large_origin,end=large_end)
    require('accepted=false' in bounded and 'bounded-acquisition:8-logs;missing:16' in bounded,'Oversized acquisition was silently split or misclassified')
    seed(2,16);calls=h.model_calls();jid=submit(blueprint=large,origin=large_origin,end=large_end)
    def first_craft():
        j=h.job_view('PlayerA',jid)
        if not j or not j['children']:return None
        nodes=graph(jid)
        if any(n['state'] in ('FAILED','CANCELLED','INTERRUPTED') for n in nodes):raise AssertionError('Cancellation fixture stopped before an effect '+json.dumps(nodes))
        return nodes if any(r['stage']=='CRAFT' for n in nodes for a in n['attempts'] for r in a.get('resourceReceipts',[])) else None
    cancellation_boundary=h.wait(first_craft,'Real staging partial craft before cancellation',100);cancelled=cancel(jid);saved= h.snapshot()['containers'];inv=inventory();h.quiet(60)
    require(h.snapshot()['containers']==saved and inventory()==inv and h.model_calls()==calls,'Cancelled stage performed late effects')
    # Ordinary known crafting positions the citizen before the finite frozen crash fixture.
    seed(0,1);rid=run('/aivillage kernel make-planks '+h.citizens['PlayerA']+' 4 '+' '.join(map(str,SRC+SRC)));approach=h.terminal('PlayerA',rid,180);require('SUCCEEDED' in approach['description'],'Ordinary staging approach failed');h.actions_active=False
    seed(2,16);h.console(['tick rate 20','tick freeze']);jid=submit(blueprint=large,origin=large_origin,end=large_end)
    deadline=time.monotonic()+300;steps=0;kill=None
    while steps<=512 and time.monotonic()<deadline:
        root=h.job_view('PlayerA',jid)
        if root and root['children']:
            nodes=graph(jid);deposited=sum(r['quantity'] for n in nodes for a in n['attempts'] for r in a.get('resourceReceipts',[]) if r['stage']=='DEPOSIT' and r['item']['id']==PLANK)
            if any(n['state'] in ('FAILED','CANCELLED','INTERRUPTED') for n in nodes):raise AssertionError('Crash staging stopped before deposit '+json.dumps(nodes))
            if 0<deposited<62 and h.container(SRC,PLANK)==2+deposited:
                kill={'job':jid,'graph':nodes,'deposited':deposited,'remaining':62-deposited};break
        if steps==512:break
        tick=h.snapshot()['gameTick'];h.console(['tick step 1'],lifecycle=True);observed=h.wait(lambda:(s if (s:=h.snapshot())['gameTick']>tick else None),'One vanilla staging crash tick',15);require(observed['gameTick']==tick+1,'Crash fixture advanced multiple game ticks');steps+=1
    require(kill is not None,'Finite staged-deposit crash boundary missed')
    claim=h.command('PlayerA','/aivillage kernel blueprint-status '+jid,'allocated=2 currentlyAllocated=false');kill['claimStatus']=claim
    saved_inventory=inventory();saved_containers=h.snapshot()['containers'];checkpoint=h.save_checkpoint();h.stop('server',hard=True);h.actions_active=False
    h.start('server');h.console(['tick unfreeze','tick rate 20']);reconnect_players(h,['PlayerA','PlayerB'])
    recovered=h.wait(lambda:h.job_view('PlayerA',jid),'Interrupted same-save blueprint demand',30);require(recovered['state']=='INTERRUPTED','Saved staging demand replayed')
    require(h.snapshot()['containers']==saved_containers and inventory()==saved_inventory,'Acknowledged staged save lost real materials')
    unavailable=h.command('PlayerA','/aivillage kernel blueprint-status '+jid,'allocated=0 currentlyAllocated=false')
    h.quiet(80);require(h.snapshot()['containers']==saved_containers and inventory()==saved_inventory,'Restart replayed, consumed or duplicated materials');h.screenshot('PlayerA','rb008-interrupted-claims-no-replay',chat=True)
    return {'admissions':admissions,'artifacts':artifacts,'models':models,'preflight':preflight,'unsupported':unsupported,'staged':staged,'foreignDenied':foreign,'foreignCancelDenied':foreign_cancel,'clientContainers':client_containers,'removedStock':removed,
            'offline':offline,'offlineProcessProof':{'cold':cold,'warm':warm,'sameRelease':True,'backendUnavailable':True},'cancelBoundary':cancellation_boundary,'cancelled':cancelled,'crashApproach':approach,'killBoundary':kill,'clockSteps':steps,'checkpoint':checkpoint,'recovered':recovered,'recoveredClaims':unavailable,'savedInventory':saved_inventory}
