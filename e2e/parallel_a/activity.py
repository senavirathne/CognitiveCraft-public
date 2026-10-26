"""A01 real-client owner routing/visibility qualification; no automatic settlement claim."""
from __future__ import annotations
import argparse
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import time
import traceback

sys.path.insert(0, str(Path(__file__).resolve().parent))
from baseline import RuntimeAHarness, interruption, native_process
from suite import ROOT, DENIED_PATTERN, atomic, read, require, reconnect_players
from generalization_support import at, coord, LOG, DST
from generalization import acquisition, receipts


class ActivityHarness(RuntimeAHarness):
    def snapshot(self):
        state = super().snapshot()
        other = read(self.root/'server/secondary-snapshot.json')
        if not state or not other or abs(state['time']-other['time']) > 3000:
            return state
        state = dict(state)
        for field in ('blocks','containers','placementColumnsClear'):
            state[field] = {**state.get(field,{}), **other.get(field,{})}
        for field in ('actors','drops'):
            state[field] = list({v['uuid']:v for v in [*state.get(field,[]),*other.get(field,[])]}.values())
        return state

    def enroll(self, player='PlayerA', name='Ada'):
        # Normal-AI villagers may wander between the two sequential enrollments.
        # Move the actual player to the currently unclaimed actor; never move or freeze the actor.
        def approached():
            state=self.snapshot(); position=self.client_snapshot(player)['pos']
            candidates=[a for a in state['actors'] if a['uuid'] not in self.entities.values()
                        and a['alive'] and not a['baby'] and not a['noAI'] and not a['controlled']]
            require(bool(candidates),'Actual unclaimed adult villager required for enrollment fixture')
            chosen=min(candidates,key=lambda a:math.dist(position,a['pos']))
            if math.dist(position,chosen['pos'])<=3:return chosen
            self.walk(player,[math.floor(chosen['pos'][0]),math.floor(chosen['pos'][1]),math.floor(chosen['pos'][2])],reach=1.5)
            actual=next((a for a in self.snapshot()['actors'] if a['uuid']==chosen['uuid']),None)
            return actual if actual and math.dist(self.client_snapshot(player)['pos'],actual['pos'])<=3 else None
        subject=self.wait(approached,'Connected player within native range of the live unclaimed villager',60)
        self.event('a01-enrollment-native-proximity',{'player':player,'actor':subject})
        return super().enroll(player,name)

    def prepare_unrelated(self):
        # A separate region, ordinary actor and its empty source/destination are
        # prepared before the interrupted task. Only actual players move later.
        self.other_source=[326,208,66];self.other_destination=[333,208,72]
        self.console(['gamemode spectator PlayerA','tp PlayerA 329.5 208 69.5'])
        self.wait(lambda: all(','.join(map(str,p)) in self.snapshot()['blocks']
                  for p in ([319,208,63],[340,208,78])),
                  'Actual player loads the separate pre-effect fixture region',40)
        self.console(['fill 318 207 62 341 207 80 minecraft:stone',
                      'fill 318 206 62 341 206 80 minecraft:sea_lantern',
                      'fill 318 208 62 341 213 80 minecraft:air',
                      'fill 318 208 62 341 213 62 minecraft:glass',
                      'fill 318 208 80 341 213 80 minecraft:glass',
                      'fill 318 208 62 318 213 80 minecraft:glass',
                      'fill 341 208 62 341 213 80 minecraft:glass',
                      'fill 318 214 62 341 214 80 minecraft:glass',
                      'summon minecraft:villager 329.5 208 67.5 {PersistenceRequired:1b}',
                      'setblock '+coord(self.other_source)+' minecraft:oak_log',
                      'setblock '+coord(self.other_destination)+' minecraft:chest',
                      'gamemode survival PlayerA'])
        self.wait(lambda: any(a['uuid'] not in self.entities.values() for a in self.snapshot()['actors']),
                  'Separate normal-AI adult exists before its native enrollment')
        primary=(self.citizens['PlayerA'],self.entities['PlayerA'])
        try:
            self.enroll('PlayerA','Hana')
            self.companion=(self.citizens['PlayerA'],self.entities['PlayerA'])
        finally:
            self.citizens['PlayerA'],self.entities['PlayerA']=primary
        require(LOG in self.block(self.other_source) and self.container(self.other_destination,LOG)==0,
                'Unrelated native resources were not prepared before interruption')
        self.command('PlayerA','/tp 66.5 208 69.5','Teleported')
        self.wait(lambda:self.snapshot()['arenaLoaded'],'Original actual arena remains loaded before its cut')

    def compose(self, *args):
        result = subprocess.run(['docker','compose','-p',self.project,'-f',str(ROOT/'e2e/compose.yml'),
                    '-f',str(ROOT/'e2e/parallel_a/compose-activity.yml'),*args],cwd=ROOT,env=self.env,text=True,capture_output=True)
        with (self.root/'docker.log').open('a') as output:output.write(result.stdout+result.stderr)
        if result.returncode:raise RuntimeError('A activity Compose failed: '+result.stderr[-2000:])
        return result

    def activity(self, root):
        value=read(self.root/'server/runtime-a-activities.json')
        return next((a for a in (value or {}).get('players',{}).get('PlayerA',{}).get('activities',[]) if a['root']==root),None)

    def observe_live(self, run):
        view=self.wait(lambda:self.run_view('PlayerA',run) and self.run_view('PlayerA',run).get('responsibility'),
                       'Original live Job identity')
        root=view['id']
        live=self.wait(lambda:(v if (v:=self.activity(root)) and v['route']=='LIVE_BOOTSTRAP' and v['state']=='ACTIVE' else None),
                       'Actual Jobs-owned live activity projection',35)
        feedback=self.command('PlayerA','/aivillage kernel activity '+root,'activity='+root)
        require('owner=LIVE_BOOTSTRAP' in feedback,'Connected live activity view disagrees with native owner')
        denied=self.command('PlayerB','/aivillage kernel activity '+root,DENIED_PATTERN)
        require(root not in self.command('PlayerB','/aivillage kernel activities','activities='),'Private activity listing leaked root')
        return {'snapshot':live,'authorizedFeedback':feedback,'foreignDenied':denied}

    def observe_recovery(self, run, case):
        job=self.run_view('PlayerA',run)['responsibility'];root=job['id']
        first=self.wait(lambda:(v if (v:=self.activity(root)) and v['route']=='RECOVERY' and v['blocker']=='NEEDS_ATTENTION' else None),
                        'Native recovered owner and explicit ambiguous blocker',45)
        require(first['attempt']==job['attempts'][-1]['id'] and first['executionGeneration']==job['generation'],
                'Activity changed original attempt/generation')
        feedback=self.command('PlayerA','/aivillage kernel activity '+root,'activity='+root)
        require('owner=RECOVERY' in feedback and 'NEEDS_ATTENTION' in feedback,'Truthful recovery view missing')
        self.quiet(40)
        again=self.wait(lambda:self.activity(root),'Repeated adoption projection')
        require(again['recoveryOwner']==first['recoveryOwner'] and again['process']==first['process'],
                'Repeated adoption duplicated owner identity')
        cancel=self.command('PlayerA','/aivillage kernel job-cancel '+root,'job='+root+' cancellation=true')
        cancelling=self.wait(lambda:(v if (v:=self.activity(root)) and v['cancellationIntent'] else None),
                             'Original cancellation intent published',35)
        require(self.run_view('PlayerA',run)['responsibility']['attempts'][-1]['uncertain'],
                'Cancellation hid the unclassified physical effect')
        before=native_process(self);self.stop('server',hard=True)
        for p in self.players:self.wait(lambda:not self.action(p,'snapshot')['play'],'Repeated native restart disconnect '+p,45)
        self.start('server');reconnect_players(self,list(self.players));self.console(['tick unfreeze','tick rate 20'],lifecycle=True)
        after=self.wait(lambda:(v if (v:=self.activity(root)) and v['cancellationIntent'] and v['route']=='RECOVERY' else None),
                        'Cancellation intent survives second same-world process adoption',45)
        require(after['recoveryOwner']==first['recoveryOwner'] and after['process']!=first['process'],
                'Original owner identity or process fence lost on repeated adoption')
        self.command('PlayerB','/aivillage kernel activity '+root,DENIED_PATTERN)
        second=native_process(self)
        require((before['hostJvmPid'],before['startTicks'])!=(second['hostJvmPid'],second['startTicks']),
                'Repeated native restart did not replace the actual JVM')
        # Actual chunk unloading, with no forced loading or actor/inventory edits.
        for p in self.players:
            self.command(p,'/gamemode spectator','Spectator|spectator')
            self.command(p,'/tp 10000.5 220 10000.5','Teleported')
        def unloaded():
            state=self.snapshot();v=self.activity(root)
            address=next((c for c in state['knownPlayers']['PlayerA']['citizens']['citizens']
                          if c['actor']['citizenId']==job['attempts'][-1]['worker']['citizenId']),None)
            return {'view':v,'address':address,'nativeSnapshot':state} if v and v['blocker']=='WAIT_WORLD' and address and address['availability']=='UNLOADED' else None
        waiting=self.wait(unloaded,'Native chunk unload and truthful WAIT_WORLD activity',110)
        wait_feedback=self.command('PlayerA','/aivillage kernel activity '+root,'blocker=WAIT_WORLD')
        original_credit=self.run_view('PlayerA',run)['responsibility']['attempts'][-1]['credited']
        # The original destination is absent. Never turn an unknown stock read
        # into a zero or use it as physical proof while its chunk is unavailable.
        self.command('PlayerA','/tp 329.5 208 69.5','Teleported')
        self.command('PlayerA','/gamemode survival','Survival|survival')
        self.wait(lambda:any(c['availability']=='LOADED' and c['actor']['citizenId']==self.companion[0]
                             for c in self.snapshot()['knownPlayers']['PlayerA']['citizens']['citizens']),
                  'Same pre-existing companion actually loaded in its separate region',60)
        during=self.wait(unloaded,'Original actor stays absent while separate actor is available',30)
        primary=(self.citizens['PlayerA'],self.entities['PlayerA']);calls=self.model_calls()
        try:
            self.citizens['PlayerA'],self.entities['PlayerA']=self.companion
            self.actions_active=True
            other_run=self.submit(LOG,1,minimum=self.other_source,maximum=self.other_source,destination=self.other_destination)
            other=self.finish_run(other_run)
            require(all(receipts(other,s,LOG)==1 for s in ('HARVEST','PICKUP','DEPOSIT')),
                    'Unrelated original native actor did not conserve its own log')
            require(self.container(self.other_destination,LOG)==1 and self.inventory().get(LOG,0)==0 and
                    self.menu_count('PlayerA',self.other_destination,LOG)==1,'Unrelated connected physical delivery missing')
            require(self.model_calls()==calls,'Known unrelated execution called a model')
        finally:
            self.citizens['PlayerA'],self.entities['PlayerA']=primary
        after_unrelated=self.wait(unloaded,'Unrelated delivery completed while original actor still WAIT_WORLD',30)
        require(self.run_view('PlayerA',run)['responsibility']['attempts'][-1]['credited']==original_credit,
                'Unrelated progress changed the interrupted original effect or credit')
        self.command('PlayerA','/tp 66.5 208 69.5','Teleported')
        self.command('PlayerB','/tp 80.5 208 69.5','Teleported')
        self.command('PlayerB','/gamemode survival','Survival|survival')
        reloaded=self.wait(lambda:(v if (v:=self.activity(root)) and v['blocker']=='NEEDS_ATTENTION' else None),
                           'Same original actor reloads; ambiguity remains explicit',70)
        self.wait(lambda:any(c['availability']=='LOADED' and c['actor']['citizenId']==self.citizens['PlayerA']
                             for c in self.snapshot()['knownPlayers']['PlayerA']['citizens']['citizens']),
                  'Original enrolled actor actually loaded after independent work',45)
        require(self.container(DST,LOG)==1,'Original saved first deposit changed while its owner was waiting')
        evidence={'first':first,'repeated':again,'feedback':feedback,'cancelFeedback':cancel,
                  'cancelling':cancelling,'secondRestart':after,'beforeNativeProcess':before,'afterNativeProcess':second,
                  'waiting':waiting,'waitingFeedback':wait_feedback,'reloaded':reloaded,
                  'unrelated':{'run':other_run,'view':other,'stock':1,'modelCallsUnchanged':True,
                               'originalBefore':during,'originalAfter':after_unrelated,'executionClass':'NATIVE_WHILE_ORIGINAL_UNLOADED'},
                  'ownerSettlement':'NOT_CLAIMED','automaticContinuation':False}
        atomic(case/'activity-owner-evidence.json',evidence)
        return evidence


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--root',required=True,type=Path)
    args=parser.parse_args();args.root.mkdir(parents=True,exist_ok=True)
    result={'stage':'A01','status':'NOT_EXECUTED','claim':'routing and private visibility only; no automatic effect settlement','cases':[]}
    h=None
    try:
        h=ActivityHarness(args.root);h.bootstrap([LOG])
        require('cognitivecraft:deliver_oak_logs' in h.hashes,'Genuine baseline method admission required')
        h.family='A01-gather-deposit-control'
        positive=acquisition(h,2,[at(6,2),at(9,2)])
        result['cases'].append({'id':'legacy-positive','status':'PASS','evidence':positive})
        h.family='A01-saved-original-owner'
        case=args.root/'saved-original-owner';case.mkdir()
        observed=interruption(h,True,case)
        result['cases'].append({'id':'repeated-adoption-cancel-private-view','status':'PASS','evidence':observed})
        result['status']='PASS'
    except Exception as e:
        result.update(status='FAIL',reason=str(e),trace=traceback.format_exc())
        if h is not None:result['lastNativeSnapshot']=h.snapshot()
        raise
    finally:
        if h is not None:h.finish()
        atomic(args.root/'a01-results.json',result)
        print('PARALLEL_A_A01_RESULT '+json.dumps({'status':result['status'],'reason':result.get('reason'),
                    'cases':[{k:c[k] for k in ('id','status')} for c in result['cases']]}),flush=True)


if __name__=='__main__':main()
