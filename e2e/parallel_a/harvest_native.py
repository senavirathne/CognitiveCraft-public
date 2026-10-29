"""First genuine saved H/original cancellation trial; the remaining original variants remain mandatory."""
import argparse,json,shutil,time,traceback,uuid
from pathlib import Path
from deposits import DepositHarness,manifest
from deposit_seed import start
from baseline import native_process,same_native_alive
from suite import ROOT,atomic,read,require,reconnect_players
from generalization_support import LOG,coord
from harvest_fixture import saved_harvest

class HarvestHarness(DepositHarness):
    def evidence(self):
        value=read(self.root/'server/harvest-snapshot.json')
        return value if value and value['time']>=self.activated else None
    def rpc(self,op,**values):
        def current_ready():
            ready=read(self.root/'server/harvest-ready.json');value=self.evidence()
            return ready if ready and value and ready['process']==value['process'] else None
        ready=self.wait(current_ready,'Current native H observer',45)
        request={'id':str(uuid.uuid4()),'process':ready['process'],'op':op,**values};atomic(self.root/'server/harvest-request.json',request)
        response=self.wait(lambda:(v if (v:=read(self.root/'server/harvest-response.json')) and v['id']==request['id'] else None),'H '+op+' reply',45)
        atomic(self.root/'harvest-rpc'/(request['id']+'.json'),{'request':request,'response':response});require(response['status']=='PASS',json.dumps(response))
        return response['value']

def custody(h,case):
    value=h.snapshot();actor=next(a for a in value['actors'] if a['uuid']==h.entities['PlayerA'])
    dst=[case['destination'][k] for k in ('x','y','z')]
    return {'actorInventory':actor['inventory'],'destination':h.container(dst,case['item']),
            'drops':sorted((d['uuid'],d['count']) for d in value['drops'] if d['item']==case['item']),
            'blocks':{coord(p):h.block(p) for p in ([73,208,73],[76,208,74])}}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,required=True);p.add_argument('--seed-archive',type=Path,required=True);args=p.parse_args()
    root=args.root.resolve();root.mkdir(parents=True,exist_ok=True);h=None
    case=next(c for c in read(ROOT/'e2e/parallel_a/a06_original_cases.json')['cases'] if c['id']=='heldout-W6-002')
    result={'stage':'A06_FIRST_NATIVE_H','status':'NOT_EXECUTED','originalCase':case,'originalTwelveSettlements':0,
            'remainingOriginalVariants':'NOT_EXECUTED_REQUIRED','nativePickupProducer':'INACTIVE','automaticContinuation':'INACTIVE','costQualification':'NOT_QUALIFIED_SMALL_SAMPLE'}
    try:
        h=HarvestHarness(root);h.activated=0;h.env['A06_HARVEST_ENABLED']='true'
        seed,result['seedContinuation']=start(h,args.seed_archive.resolve())
        dst=[case['destination'][k] for k in ('x','y','z')]
        h.reset({},destination=dst,table=False);h.logs([[73,208,73],[76,208,74]])
        require(case['requested']==2 and case['item']==LOG,'Frozen original first H envelope changed')
        token=str(uuid.uuid4());result['arm']=h.rpc('arm',point='RESULT_ACKNOWLEDGED',token=token,save=True)
        result['initialCustody']=custody(h,case);calls=h.model_calls();src=case['source']
        run=h.submit(case['item'],case['requested'],minimum=[src[k] for k in ('minX','minY','minZ')],maximum=[src[k] for k in ('maxX','maxY','maxZ')],destination=dst)
        marker=h.wait(lambda:(v if (v:=read(root/'server/harvest-cut-reached.json')) and v['token']==token else None),'Actually reached saved original H boundary',170)
        result['reached']=marker;result['run']=run
        require(marker['point']=='RESULT_ACKNOWLEDGED' and marker['commands']==[{'command':'tick freeze','result':1},{'command':'save-all flush','result':1}],'Native frozen/acknowledged cut missing')
        entry=marker['journal']['entries'][0];key=entry['intent']['key'];original=next(j for j in marker['runtime']['jobs']['jobs'] if j['id']==key['step'])
        require(original['state']=='ACTIVE' and original['attempts'][-1].get('terminal') is None,'Original H owner was not live at the actual saved cut')
        require(original['request']['request']['arguments']['amount']['value']==case['requested'],'Original H quantity changed')
        result['originalAtCut']=original;before=native_process(h);clients={p:read(root/'clients'/p/'process.json') for p in h.players}
        h.stop('server',hard=True);h.wait(lambda:not same_native_alive(before),'Real saved H JVM killed',25)
        killed=root/'saved-harvest-killed-world';shutil.copytree(root/'server/game/world',killed,ignore=shutil.ignore_patterns('session.lock'))
        result['killedWorldHashes']=manifest(killed);result['independentSavedNative']=saved_harvest(killed)
        saved_game=root/'saved-harvest-game';shutil.copytree(root/'server/game',saved_game,ignore=shutil.ignore_patterns('session.lock'))
        for player in h.players:h.wait(lambda:not h.action(player,'snapshot')['play'],'Actual H disconnect '+player,45)
        h.actions_active=False;h.activated=int(time.time()*1000);h.start('server');reconnect_players(h,list(h.players));h.console(['tick unfreeze','tick rate 20'],lifecycle=True)
        restored=h.wait(lambda:(v if (v:=h.stable_entry()) and list(v['reconciliation'].values())==['SAVED_COMPLETE'] else None),'Saved native source/drop/actor cohort',60)
        result['restored']=restored
        require(all(read(root/'clients'/p/'process.json')==old for p,old in clients.items()),'Native H restart replaced connected clients')
        interrupted=h.wait(lambda:(j if (v:=h.run_view('PlayerA',run)) and (j:=v.get('responsibility')) and j['state']=='INTERRUPTED' and j['attempts'][-1]['uncertain'] else None),'Uncancelled original H stays interrupted',45)
        result['beforeCancellation']=interrupted;physical=custody(h,case)
        require(physical['destination']==0 and physical['actorInventory']=={} and len(physical['drops'])==1,'Saved H custody is not one original drop and no delivery')
        result['cancellation']=h.command('PlayerA','/aivillage kernel job-cancel '+key['step'],'job='+key['step']+' cancellation=true')
        settled=h.wait(lambda:(j if (v:=h.run_view('PlayerA',run)) and (j:=v.get('responsibility')) and j['state']=='CANCELLED' and not j['attempts'][-1]['uncertain'] else None),'Terminal certain original H owner',45)
        require(settled['attempts'][-1]['id']==key['attempt'] and settled['generation']==key['generation'] and settled['attempts'][-1]['effects']==1 and settled['attempts'][-1]['credited']==0,'Original H identity/debit/credit changed')
        require(settled['children']==[] and settled['allowance']==original['allowance'],'Original H children or allowance changed')
        verified=h.wait(lambda:(v if (v:=h.stable_entry()) and v['journal']['entries'][0].get('verification') else None),'Durably verified original H cohort',40)
        h.wait(lambda:(v if (v:=h.evidence()) and not any(l['owner']['job']==key['step'] and l['state']=='ACTIVE' for l in v['runtime']['leases']['leases']) else None),'Original H claims settled',35)
        h.quiet(40);require(custody(h,case)==physical,'Cancellation changed original H native custody')
        h.save_checkpoint();result['settlementRestart']=h.restart(hard=True)
        again=h.wait(lambda:(j if (v:=h.run_view('PlayerA',run)) and (j:=v.get('responsibility')) and j['state']=='CANCELLED' else None),'Original H cancellation survives another kill',45)
        h.quiet(40);require(again==settled and custody(h,case)==physical,'Repeated H restart changed owner or replayed a native effect')
        require(h.model_calls()==calls,'First H recovery requested a model replacement')
        # Separate same-coordinate/equal-state negative cohort; no receipt is added to the old world.
        h.restore(saved_game,'source-replacement-negative');h.actions_active=False
        source=entry['result']['source'];position=[source[k] for k in ('x','y','z')]
        h.console(['setblock '+coord(position)+' minecraft:oak_log','setblock '+coord(position)+' minecraft:air'])
        h.save_checkpoint();negative_restart=h.restart(hard=True)
        negative=h.wait(lambda:(v if (v:=h.stable_entry()) and list(v['reconciliation'].values())==['TORN_SAVED_COHORT'] else None),'Equal-state source replacement refuses old saved proof',45)
        require(not negative['journal']['entries'][0].get('verification'),'Changed source unexpectedly became verified')
        h.command('PlayerA','/aivillage kernel job-cancel '+key['step'],'job='+key['step']+' cancellation=true')
        h.quiet(40);blocked=h.run_view('PlayerA',run)['responsibility']
        require(blocked['attempts'][-1]['uncertain'] and blocked['attempts'][-1]['id']==key['attempt'] and not blocked['state']=='CANCELLED','Changed source settled an unproved original effect')
        result['negative']={'status':'PASS_REFUSED','restart':negative_restart,'native':negative,'originalJob':blocked,'physical':custody(h,case)}

        result.update(status='PASS_FIRST_H_PENDING_ARCHIVE_REVIEW',originalAfterSettlement=settled,afterSettlementRestart=again,verified=verified,finalCustody=physical,originalTwelveSettlements=1)
    except Exception as error:
        result.update(status='FAIL',reason=str(error),trace=traceback.format_exc())
        if h is not None:result['lastEvidence']=h.evidence();result['lastSnapshot']=h.snapshot()
        raise
    finally:
        if result['status']=='FAIL':
            native=result.get('lastEvidence') or {}
            jobs=native.get('runtime',{}).get('jobs',{}).get('jobs',[])
            owners=[{'id':j['id'],'state':j['state'],'reason':j.get('reason'),'attempts':j.get('attempts',[])} for j in jobs
                    if j['request']['request']['arguments'].get('amount',{}).get('value')==case['requested']]
            records=[{'key':e['intent']['key'],'live':e.get('result',{}).get('live'),'verified':bool(e.get('verification'))} for e in native.get('journal',{}).get('entries',[])]
            print('A06_HARVEST_FAILURE_STATE '+json.dumps({'pending':native.get('pending'),'fenced':native.get('fenced'),
                  'records':records,'refusals':native.get('metrics',{}).get('refusals',[]),'originalOwners':owners}),flush=True)
        if h is not None:h.finish()
        atomic(root/'a06-harvest-results.json',result);print('PARALLEL_A_A06_HARVEST_RESULT '+json.dumps({'status':result['status'],'reason':result.get('reason')}),flush=True)

if __name__=='__main__':main()
