"""A01 real-client owner routing/visibility qualification; no automatic settlement claim."""
from __future__ import annotations
import argparse
import json
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
from generalization import acquisition


class ActivityHarness(RuntimeAHarness):
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
        evidence={'first':first,'repeated':again,'feedback':feedback,'cancelFeedback':cancel,
                  'cancelling':cancelling,'secondRestart':after,'beforeNativeProcess':before,'afterNativeProcess':second,
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
