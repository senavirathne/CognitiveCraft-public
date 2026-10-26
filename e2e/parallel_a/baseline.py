"""A00 connected baseline characterization. No runtime implementation or recovery credit."""
from __future__ import annotations
import argparse
import json
import math
from pathlib import Path
import re
import shutil
import sys
import time
import traceback
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from suite import ROOT, DENIED_PATTERN, atomic, read, require, reconnect_players
from generalization_support import AssessmentHarness, AssessmentBlocked, Inconclusive, at, coord, LOG, DST
from generalization import acquisition, receipts


class RuntimeAHarness(AssessmentHarness):
    def __init__(self, root):
        super().__init__(root, 'parallel-a-a00')
        self.project = 'cc-runtime-a-' + str(uuid.uuid4())[:8]

    def console(self, lines, lifecycle=False):
        if lifecycle and all(re.fullmatch(r'tick (?:freeze|unfreeze|rate (?:5|20))', line) for line in lines):
            request = {'id': str(uuid.uuid4()), 'commands': lines}
            atomic(self.root / 'server/control.json', request)
            self.wait(lambda: (v := read(self.root / 'server/control-ack.json')) and v['id'] == request['id'],
                      'A-owned vanilla clock mailbox delivery', 20)
            self.event('parallel-a-clock', request)
            return
        return super().console(lines, lifecycle)


def custody(h):
    state = h.snapshot()
    actor = next(a for a in state['actors'] if a['uuid'] == h.entities['PlayerA'])
    drops = sorted((d['uuid'], d['count']) for d in state['drops'] if d['item'] == LOG)
    return {'destination': h.container(DST, LOG), 'inventory': actor['inventory'], 'drops': drops,
            'blocks': {coord(p): h.block(p) for p in (at(6, 2), at(13, 2))},
            'actorEntity': actor['uuid'], 'gameTick': state['gameTick']}


def percentiles(values):
    require(bool(values), 'Nonempty actual measurements required')
    values = sorted(values)
    return {**{f'p{p}': values[max(0, math.ceil(len(values)*p/100)-1)] / 1e6 for p in (50,95,99)},
            'max': max(values)/1e6, 'samples': len(values), 'unit': 'milliseconds'}


def interruption(h, saved, case):
    h.family = case.name
    h.reset({}, table=False)
    h.logs([at(6, 2), at(13, 2)])
    initial = custody(h)
    # Save only the pre-effect starting world. The unflushed cohort never saves after mutation.
    start_save = h.save_checkpoint()
    h.console(['tick rate 5'], lifecycle=True)
    rid = h.submit(LOG, 2)
    def at_cut():
        v = h.run_view('PlayerA', rid)
        if v and v.get('phase') == 'TERMINAL':
            raise Inconclusive('Execution ended before the selected partial-deposit cut')
        return v if v and v.get('phase') == 'EXECUTING' and receipts(v, 'DEPOSIT', LOG) == 1 else None
    try:
        boundary = h.wait(at_cut, 'Actual first deposit of two with original owner still executing', 90)
    except TimeoutError as e:
        raise Inconclusive(str(e)) from e
    log_offset = len(h.server_log())
    h.console(['tick freeze'], lifecycle=True)
    h.wait(lambda: 'Frozen' in h.server_log()[log_offset:] or 'frozen' in h.server_log()[log_offset:],
           'Actual vanilla tick freeze processed')
    cut = h.snapshot(); view = h.run_view('PlayerA', rid)
    require(view and view.get('phase') == 'EXECUTING' and receipts(view, 'DEPOSIT', LOG) == 1,
            'Requested boundary was not actually retained at freeze')
    physical = custody(h)
    require(physical['destination'] == 1, 'Receipt lacks an independent actual chest deposit')
    job = view['responsibility']
    require(job['id'] == rid and job['attempts'] and job['generation'] > 0, 'Original Jobs attempt identity required')
    checkpoint = None
    if saved:
        started = time.monotonic_ns()
        checkpoint = h.save_checkpoint()
        save_nanos = time.monotonic_ns()-started
    else:
        save_nanos = None
    before_kill = h.run_view('PlayerA', rid)
    if before_kill.get('phase') != 'EXECUTING' or receipts(before_kill, 'DEPOSIT', LOG) != 1:
        raise Inconclusive('Selected effect window ended before the real kill')
    process = read(h.root/'server/process.json')
    clients = {p: read(h.root/'clients'/p/'process.json') for p in h.players}
    atomic(case/'before-kill.json', {'snapshot':cut,'view':before_kill,'custody':physical,'process':process,
           'clients':clients,'initial':initial,'startSave':start_save,'effectSave':checkpoint,
           'postEffectSaveRequested':saved,'saveAckWallNanos':save_nanos})
    h.stop('server', hard=True)
    shutil.copytree(h.root/'server/game/world',case/'killed-world',ignore=shutil.ignore_patterns('session.lock'))
    killed_hashes = h.world_hashes()
    for player in h.players:
        h.wait(lambda: not h.action(player, 'snapshot')['play'], 'Actual JVM termination disconnect '+player, 45)
    h.actions_active = False
    h.start('server'); reconnect_players(h, list(h.players))
    h.console(['tick unfreeze', 'tick rate 20'], lifecycle=True)
    require(read(h.root/'server/process.json')['pid'] != process['pid'], 'New server JVM required')
    require(all(read(h.root/'clients'/p/'process.json') == old for p,old in clients.items()), 'Persistent clients were replaced')
    restored = h.wait(lambda: h.run_view('PlayerA', rid), 'Original owner visible after same-world restart', 45)
    h.quiet(80)
    after = h.run_view('PlayerA', rid)
    original = after['responsibility']
    require(original['id'] == rid and original['generation'] == job['generation'] and
            [a['id'] for a in original['attempts']] == [a['id'] for a in job['attempts']], 'Original owner/attempt/generation changed')
    require(original['state'] == 'INTERRUPTED' and any(a['uncertain'] or a.get('terminal') is None for a in original['attempts']),
            'Historical unresolved-owner signature was not reproduced')
    after_custody = custody(h)
    later_stock = h.container(DST, LOG); h.quiet(40)
    require(h.container(DST, LOG) == later_stock, 'Baseline restarted an unresolved native effect')
    if saved:
        require(all(after_custody[k] == physical[k] for k in ('destination','inventory','drops','blocks','actorEntity')),
                'Saved participant custody differs from actual pre-kill evidence')
    evidence = {'status':'PASS','claim':'baseline cut characterization; unresolved recovery remains a failure signature',
                'run':rid,'saved':saved,'requestedCut':'first DEPOSIT of two while EXECUTING',
                'actuallyWitnessedCut':before_kill,'killedWorldHashes':killed_hashes,
                'restored':restored,'after':after,'beforeCustody':physical,'afterCustody':after_custody,
                'ownerSettlement':'FAIL','resumedCompletion':'NOT_EXECUTED',
                'saveOrdering':'observed complete checkpoint only' if saved else 'UNKNOWN; no post-effect flush requested',
                'automaticRecoveryEnabled':False}
    atomic(case/'result.json', evidence)
    h.screenshot('PlayerA', case.name+'-original-owner', chat=True)
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True, type=Path)
    args = parser.parse_args(); args.root.mkdir(parents=True,exist_ok=True)
    results = {'stage':'A00','status':'NOT_EXECUTED','claim':'baseline qualification only','cases':[]}
    h = None
    try:
        h = RuntimeAHarness(args.root)
        h.bootstrap([LOG])
        require('cognitivecraft:deliver_oak_logs' in h.hashes, 'Genuine baseline method admission did not succeed')
        h.family = 'A00-positive'
        positive = acquisition(h,2,[at(6,2),at(9,2)])
        results['cases'].append({'id':'supported-gather-deposit','status':'PASS','evidence':positive})
        h.reset({},table=False); h.logs([at(6,2)])
        before = h.snapshot()
        denial = h.command('PlayerB','/aivillage kernel gather-oak '+h.citizens['PlayerA']+' 1 '+coord(at(6,2)+at(6,2)+DST),DENIED_PATTERN)
        h.quiet(20)
        require(h.container(DST,LOG)==0 and LOG in h.block(at(6,2)), 'Denied request caused a native effect')
        results['cases'].append({'id':'foreign-principal-negative','status':'PASS','feedback':denial,'before':before,'after':h.snapshot()})
        def clean_owners():
            native=h.snapshot()
            jobs=[r['responsibility'] for p in native.get('knownPlayers',{}).values()
                  for r in p.get('runs',{}).values() if r.get('responsibility')]
            jobs += [j for p in native.get('knownPlayers',{}).values() for j in p.get('jobs',{}).values()]
            return native if jobs and all(j['state'] in ('SUCCEEDED','FAILED','CANCELLED') and
                all(a.get('terminal') is not None and not a['uncertain'] for a in j['attempts']) for j in jobs) else None
        atomic(args.root/'clean-owner-seed.json', h.wait(clean_owners,'All baseline owners settled before independent crash cohorts',40))
        start_ms = int(time.time()*1000); start_tick=h.snapshot()['tick']
        h.wait(lambda: h.snapshot()['tick'] >= start_tick+400, '400 stable baseline ticks',35)
        measurements = h.wait(lambda: (v if (v:=read(h.root/'server/runtime-a-ticks.json')) and
                                  len([s for s in v['samples'] if s['time']>=start_ms])>=390 else None), 'Bounded tick measurements flushed')
        selected=[s['nanos'] for s in measurements['samples'] if s['time']>=start_ms]
        results['performance']={'tick':percentiles(selected),'metric':measurements['metric'],
            'workload':'two real clients, two normal-AI villagers, one admitted method, idle at 20 TPS',
            'host':read(h.root/'metadata.json')['host'],'pid':measurements['pid']}
        atomic(args.root/'baseline-tick-samples.json',measurements)
        h.save_checkpoint(); h.stop('server')
        baseline = args.root/'qualified-starting-game'
        shutil.copytree(h.root/'server/game',baseline,ignore=shutil.ignore_patterns('session.lock'))
        for case_id,saved in [('unflushed-probe',False),('saved-restart-signature',True)]:
            game=h.root/'server/game'
            game.rename(args.root/('retained-before-'+case_id))
            shutil.copytree(baseline,game,ignore=shutil.ignore_patterns('session.lock'))
            h.start('server'); reconnect_players(h,list(h.players))
            case=args.root/case_id;case.mkdir()
            try:
                record=interruption(h,saved,case)
                results['cases'].append({'id':case_id,**record})
            except Inconclusive as e:
                results['cases'].append({'id':case_id,'status':'INCONCLUSIVE','reason':str(e)})
                raise
            finally:
                atomic(args.root/'a00-results.json',results)
            h.stop('server')
        results['status']='PASS'
    except Exception as e:
        results.update(status='INCONCLUSIVE' if isinstance(e,Inconclusive) else 'BLOCKED' if isinstance(e,AssessmentBlocked) else 'FAIL',
                       reason=str(e),trace=traceback.format_exc())
        if h is not None: results['lastPhysicalSnapshot']=h.snapshot()
        raise
    finally:
        if h is not None: h.finish()
        atomic(args.root/'a00-results.json',results)
        print('PARALLEL_A_A00_RESULT '+json.dumps({'status':results['status'],'cases':[{k:c[k] for k in ('id','status')} for c in results['cases']]}),flush=True)


if __name__=='__main__': main()
