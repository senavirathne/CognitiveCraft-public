"""Runtime-driven A-only native slice. One actual Jobs root, genuine admitted method, no planner."""
import argparse,json,shutil,time,traceback,uuid
from pathlib import Path
from claims_connected import ClaimsHarness
from lifecycle_connected import checkpoint,starting_checkpoint
from recovery_seed import start as retained_seed
from recovery_native import one as original_case,restart_after_cut
from recovery_controls import unsaved
from recovery_cases import originals
from activity_cases import CASES,selected_originals
from baseline import native_process,same_native_alive,percentiles
from suite import ROOT,atomic,read,require
from generalization_support import LOG,coord,Inconclusive
from generalization import receipts
from deposits import manifest

POINTS=[[73,208,73],[74,208,73]];DST=[77,208,72]

class ActivityHarness(ClaimsHarness):
    compose_files=['e2e/compose.yml','e2e/parallel_a/compose-deposits.yml','e2e/parallel_a/compose-lifecycle.yml',
                   'e2e/parallel_a/compose-claims.yml','e2e/parallel_a/compose-activity-runtime.yml']
    def state(self):
        v=read(self.root/'server/a09-snapshot.json')
        return v if v and v['time']>=self.activated else None
    def runtime_rpc(self,op,root,player='PlayerA',expected='PASS',**values):
        current=self.wait(self.state,'Actual current A09 runtime-test caller',45)
        request={'id':str(uuid.uuid4()),'process':current['process'],'principal':self.players[player],'op':op,'root':root,**values}
        atomic(self.root/'server/a09-request.json',request)
        response=self.wait(lambda:(v if (v:=read(self.root/'server/a09-response.json')) and v['id']==request['id'] else None),'Public runtime '+op,30)
        atomic(self.root/'a09-rpc'/(request['id']+'.json'),{'request':request,'response':response})
        self.rpc_sequence=(response['process'],response['value']['sequence'])
        require(response['value']['status']==expected,'Public runtime '+op+': '+json.dumps(response));return response['value']
    def view(self,root):return self.runtime_rpc('snapshot',root)['activity']
    def create_activity(self,deadline=600000):
        self.ready();root=str(uuid.uuid4());created=self.runtime_rpc('create',root,citizen=self.citizens['PlayerA'],minimum=POINTS[0],maximum=POINTS[1],destination=DST,deadlineMillis=deadline)
        require(created['change']['code']=='PENDING','Root was not admitted through the public boundary');self.ready();return root,self.view(root)
    def prepare_segment(self,root,token):
        self.ready();before=self.view(root)
        value=self.runtime_rpc('prepare',root,proposal=str(uuid.uuid4()),token=token,revision=before['revision'],generation=before['generation'],artifact=self.hashes['cognitivecraft:deliver_oak_logs'])
        return before,value
    def progress(self,root,count):
        def current():
            v=(self.state() or {}).get('views',{}).get(root)
            return v if v and v['verifiedCheckpoints']==count else None
        value=self.wait(current,'Authoritative real Jobs checkpoint '+str(count),180);self.ready();return self.view(root)
    def player_progress(self,root,count,extra=''):
        text=self.command('PlayerA','/aivillage kernel activity '+root,'checkpoints='+str(count)+'/2')
        require('remaining='+str(2-count) in text and (not extra or extra in text),'Connected progress differs from authoritative checkpoints')
        return text
    def setup_runtime(self,seed,name):
        self.case_stage='HARVEST';self.env.update(A06_HARVEST_ENABLED='true',A06_PICKUP_ENABLED='true',A05_DEPOSIT_ENABLED='true',A06_RECEIPT_PREFIX_ENABLED='true')
        self.restore(seed,'a09-'+name);self.ready();self.reset({},destination=DST,table=False);self.logs(POINTS)
        self.watch(POINTS+[DST],[self.entities['PlayerA']]);self.quiet(5)
        require(self.container(DST,LOG)==0 and not self.inventory() and all('minecraft:oak_log' in self.block(p) for p in POINTS),'Starting physical fixture differs')
    def physical_runtime(self):
        return {'native':self.physical(),'destination':self.container(DST,LOG),'inventory':self.inventory(),
                'blocks':{coord(p):self.block(p) for p in POINTS},'drops':[d for d in self.snapshot()['drops'] if d['item']==LOG]}

def measure(h,folder,segment,start):
    end=int(time.time()*1000)
    def fresh():
        v=read(h.root/'server/runtime-a-ticks.json')
        return v if v and v['samples'] and v['samples'][-1]['time']>=end else None
    raw=h.wait(fresh,'Current native tick measurement after '+segment,15);selected=[v for v in raw['samples'] if start<=v['time']<=end]
    require(bool(selected),'Native runtime segment lacks measured ticks')
    atomic(folder/(segment+'-ticks.json'),raw);timings=h.state()['publicationTimings'];require(bool(timings),'Actual original Jobs publications were not measured')
    require(all(0<=t['durationNanos']<=30_000_000_000 for t in timings),'Actual Jobs publication exceeded its unchanged30s bound')
    return {'start':start,'end':end,'tickMillis':percentiles([v['nanos'] for v in selected]),'publicationTimings':timings,
            'publicationMillis':percentiles([v['durationNanos'] for v in timings]),'metric':'Segment plus connected query overhead; first saved-cut window includes explicit fixture save cost. No full-endurance performance claim.'}

def runtime_case(h,seed,name):
    folder=h.root/'cases'/name;folder.mkdir(parents=True,exist_ok=True);h.setup_runtime(seed,name)
    result={'id':name,'status':'NOT_EXECUTED','startingWorldHashes':starting_checkpoint(h,folder),'fixture':{'points':POINTS,'destination':DST,
            'actor':h.entities['PlayerA'],'citizen':h.citizens['PlayerA'],'principal':h.players['PlayerA']},'initialPhysical':h.physical_runtime(),'modelCallsBefore':h.model_calls()}
    if name=='legacy-native-delivery':
        run=h.submit(LOG,1,minimum=POINTS[0],maximum=POINTS[1],destination=DST);view=h.finish_run(run)
        require(all(receipts(view,s,LOG)==1 for s in ('HARVEST','PICKUP','DEPOSIT')),'Actual legacy receipts missing')
        require(not view['responsibility'].get('activity') and view['responsibility']['state']=='SUCCEEDED','Legacy acquired an invented activity payload')
        result.update(run=run,legacy=view,finalPhysical=h.physical_runtime())
    else:
        root,created=h.create_activity(2000 if name=='expired-original-deadline' else 600000);result.update(root=root,created=created)
        if name=='private-runtime-boundary':
            denied=h.runtime_rpc('snapshot',root,'PlayerB',expected='DENIED');before=h.view(root)
            denied_prepare=h.runtime_rpc('denied-prepare',root,'PlayerB',actor=before['actor'],minimum=POINTS[0],maximum=POINTS[1],destination=DST,
                    allowance=before['remaining'],revision=before['revision'],generation=before['generation'],artifact=h.hashes['cognitivecraft:deliver_oak_logs'])
            require(denied_prepare['change']['code']=='REJECTED' and denied_prepare['change']['reason']=='AUTHORITY_DENIED','Foreign writer was not refused by actual Jobs boundary')
            result.update(denied=denied,deniedPrepare=denied_prepare,after=h.view(root));require(all(result['after'][k]==before[k] for k in ('revision','generation','confirmedCredit','checkpoints','currentStep','cumulativeUsage','originalDeadline') if k in before),'Denied writer changed authoritative progress')
        elif name=='expired-original-deadline':
            h.wait(lambda:time.time()*1000>created['originalDeadline']+50,'Original absolute deadline actually expired',15)
            before,refused=h.prepare_segment(root,'expired');after=h.view(root)
            require(refused['change']['code']=='REJECTED' and refused['change']['reason']=='BUDGET_EXHAUSTED','Expired proposal acquired execution authority')
            require(after['next']=='EXPIRED' and not after['checkpoints'] and after['generation']==0,'Expired root fabricated work')
            result.update(before=before,refused=refused,after=after,connectedProgress=h.player_progress(root,0,'next=EXPIRED'))
        else:
            if name=='two-segments-saved-checkpoint-restart':
                token=str(uuid.uuid4());h.runtime_rpc('arm',root,token=token);result['intendedCut']={'token':token,'verifiedCheckpoints':1,'owner':'NONE','save':'save-all flush'}
            first_start=int(time.time()*1000);first_before,first_prepare=h.prepare_segment(root,'segment-one');require(first_prepare['change']['code']=='PENDING','First segment was not prepared');h.actions_active=True
            first=h.progress(root,1);result.update(firstBefore=first_before,firstPrepare=first_prepare,first=first,firstConnectedProgress=h.player_progress(root,1),firstPhysical=h.physical_runtime())
            require(first['confirmedCredit']==1 and first['state']=='WAITING' and first['remainingGoal']==1,'First physical segment did not retain original remaining demand')
            result['firstCost']=measure(h,folder,'segment-one',first_start)
            if name=='two-segments-saved-checkpoint-restart':
                cut=h.wait(lambda:(v if (v:=read(h.root/'server/a09-cut.json')) and v['token']==token else None),'Actual saved settled first checkpoint cut',45)
                require(cut['root']==root and cut['freezeResult']==cut['saveResult']==1 and cut['activity']['verifiedCheckpoints']==1,'Intended native cut was not witnessed')
                before=native_process(h);clients={p:read(h.root/'clients'/p/'process.json') for p in h.players};h.stop('server',hard=True)
                h.wait(lambda:not same_native_alive(before),'Actual A09 checkpoint JVM killed',25)
                killed=folder/'killed-world';shutil.copytree(h.root/'server/game/world',killed,ignore=shutil.ignore_patterns('session.lock'))
                restart=restart_after_cut(h,before,clients);h.runtime_rpc('watch',root);h.watch(POINTS+[DST],[h.entities['PlayerA']]);restored=h.view(root)
                require(restored['root']==root and restored['checkpoints']==first['checkpoints'] and restored['generation']==1
                        and restored['originalDeadline']==created['originalDeadline'] and restored['confirmedCredit']==1,'Restart replaced or erased the original root checkpoint')
                require(restored['cumulativeUsage']==first['cumulativeUsage'],'Restart refilled cumulative usage')
                result.update(actualCut=cut,killedWorldHashes=manifest(killed),restart=restart,restored=restored,restoredConnectedProgress=h.player_progress(root,1))
            second_start=int(time.time()*1000);second_before,second_prepare=h.prepare_segment(root,'segment-two');require(second_prepare['change']['code']=='PENDING','Second segment was not prepared');h.actions_active=True
            final=h.progress(root,2);h.actions_active=False
            require(final['state']=='SUCCEEDED' and final['confirmedCredit']==2 and final['remainingGoal']==0 and final['generation']==2,'Original root did not settle both native segments')
            require(final['originalDeadline']==created['originalDeadline'] and not final['plannerEnabled'],'Deadline changed or a planner was activated')
            require(h.container(DST,LOG)==2 and not h.inventory(),'Native two-unit delivery differs')
            result['secondCost']=measure(h,folder,'segment-two',second_start)
            result.update(secondBefore=second_before,secondPrepare=second_prepare,final=final,finalConnectedProgress=h.player_progress(root,2),beforeExternal=h.physical_runtime())
            if name=='historical-credit-current-stock':
                h.vanilla('PlayerA','/item replace block '+coord(DST)+' container.0 with minecraft:oak_log 1')
                h.wait(lambda:h.container(DST,LOG)==1,'Independent external native removal of one delivered unit',20)
                after=h.view(root);require(after['confirmedCredit']==2 and after['checkpoints']==final['checkpoints'] and after['availability']['known'] and after['availability']['destinationQuantity']==1,'Historical delivery was confused with current stock')
                result.update(afterExternal=after,externalRemoval={'actor':'PlayerA vanilla fixture command','delta':-1},afterExternalConnectedProgress=h.player_progress(root,2,'currentStock=1'))
        result['finalPhysical']=h.physical_runtime();result['finalRuntime']=h.view(root)
    result['modelCallsAfter']=h.model_calls();require(result['modelCallsBefore']==result['modelCallsAfter'],'Runtime-driven slice unexpectedly invoked a model')
    result['terminalWorldHashes']=checkpoint(h,folder);result['status']='PASS_NATIVE_CANDIDATE_PENDING_REVIEW';atomic(folder/'result.json',result);return result

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--seed-archive',type=Path,required=True);a=p.parse_args()
    root=a.root.resolve();root.mkdir(parents=True,exist_ok=True);h=None
    result={'schema':1,'stage':'A09','status':'NOT_EXECUTED','a09Qualified':False,'runtimeDriven':True,'plannerEnabled':False,'plannedCases':list(CASES),'requiredCases':len(CASES),'cases':[],'omittedTests':0,'omittedTasks':0}
    try:
        h=ActivityHarness(root);h.activated=0;seed=retained_seed(h,a.seed_archive.resolve());h.primary=h.citizens['PlayerA'],h.entities['PlayerA'];templates=originals(ROOT/'e2e/parallel_a/a06_original_cases.json')
        selected=selected_originals(templates)
        print('A09_NATIVE_METADATA '+json.dumps(read(root/'metadata.json')),flush=True)
        for name in CASES:
            try:
                if name.startswith('legacy-') and name.endswith('-original'):
                    stage=name.split('-')[1];value={'id':name,'status':'PASS_NATIVE_CANDIDATE_PENDING_REVIEW','regression':original_case(h,seed,selected[stage])}
                elif name=='legacy-unsaved-pickup-fence':value={'id':name,'status':'PASS_NATIVE_CANDIDATE_PENDING_REVIEW','regression':unsaved(h,seed,selected['PICKUP'])}
                else:value=runtime_case(h,seed,name)
            except Exception as failed:
                value={'id':name,'status':'INCONCLUSIVE_REQUIRED_NATIVE_WINDOW' if isinstance(failed,(Inconclusive,TimeoutError)) else 'FAIL','reason':str(failed),'trace':traceback.format_exc()}
                failure=root/'failures'/name;failure.mkdir(parents=True,exist_ok=True)
                try:
                    value['runtime']=h.state();h.actions_active=False;h.save_checkpoint();h.stop('server',hard=True)
                    target=failure/'terminal-world';shutil.copytree(root/'server/game/world',target,ignore=shutil.ignore_patterns('session.lock'));value['failureWorldHashes']=manifest(target)
                except Exception as evidence:value['evidenceError']=str(evidence)
                atomic(failure/'result.json',value)
            result['cases'].append(value);atomic(root/'a09-results.json',result);print('A09_CASE '+json.dumps({'id':name,'status':value['status'],'reason':value.get('reason')}),flush=True)
        result['status']='PASS_NATIVE_CANDIDATES_PENDING_REVIEW' if all(c['status']=='PASS_NATIVE_CANDIDATE_PENDING_REVIEW' for c in result['cases']) else 'FAIL_REQUIRED_A09_CASES'
    except Exception as failed:
        result.update(status='FAIL_A09_PREREQUISITE',reason=str(failed),trace=traceback.format_exc());print('A09_PREREQUISITE_FAILURE '+json.dumps(result),flush=True)
    finally:
        seen={r['id'] for r in result['cases']};result['cases'] += [{'id':n,'status':'NOT_EXECUTED_REQUIRED'} for n in CASES if n not in seen]
        atomic(root/'a09-results.json',result)
        if h:h.finish()
    if result['status']!='PASS_NATIVE_CANDIDATES_PENDING_REVIEW':raise SystemExit(1)

if __name__=='__main__':main()
