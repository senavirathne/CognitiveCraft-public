#!/usr/bin/env python3
"""One connected-client batch on a frozen production tree; physical evidence owns the verdict."""
from __future__ import annotations
import argparse
from collections import Counter
import contextlib
import copy
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import traceback
import uuid

HERE=Path(__file__).resolve().parent
E2E=HERE.parents[1]
sys.path.insert(0,str(E2E))
import navigation
from suite import Harness, InfrastructureBlocked, ROOT, UUID_PATTERN, atomic, read, require, reconnect_players
from generalization_support import AssessmentHarness, AssessmentBlocked, Inconclusive, LOG, BIRCH
from generate import generate, BASELINE, TREE, FAMILIES, point
from oracle import evaluate, outcome, stages, receipts, SAFETY, key, WHEAT,custody

CAPS={WHEAT:'deliver_wheat',LOG:'deliver_oak_logs',BIRCH:'deliver_birch_logs'}
ACTIVE=('PERSISTING','ROUTING','EXECUTING','RESEARCHING','RESOLVING','INTERPRETING')

def coord(p): return ' '.join(map(str,p))

def selection(seed,mode,families,budget=0,case=None,include_cases=(),heldout_seed=None,extra_heldout=(),replay_original=False):
    corpus=copy.deepcopy(generate(seed))
    if mode=='full':
        held=copy.deepcopy(generate(heldout_seed if heldout_seed is not None else seed+1))
        for row in held:
            row['id']='heldout-'+row['id']
            row.pop('caseSha256',None)
            row['caseSha256']=hashlib.sha256(json.dumps(row,sort_keys=True).encode()).hexdigest()
        corpus+=held
    chosen=[c for c in corpus if c['family'] in families or c['id'] in include_cases or c['id'].startswith('heldout-') and c['family'] in extra_heldout]
    if replay_original:
        previous=read(HERE/'replay-cases.json')
        for original in previous['cases']:
            c=copy.deepcopy(original); old_id=c['id']; old_sha=c.pop('caseSha256')
            c.update(id='replay-'+old_id,actorChoice=0,standingSupports=[],
                replaySource={'run':previous['run'],'sourceCommit':previous['sourceCommit'],'case':old_id,'caseSha256':old_sha})
            points=c['positions']+c.get('immature',[])+c.get('unrelated',[])
            lo=[min(p[k] for p in points) for k in range(3)]; hi=[max(p[k] for p in points) for k in range(3)]
            if (hi[0]-lo[0]+1)*(hi[1]-lo[1]+1)*(hi[2]-lo[2]+1)>256:
                points=c['positions']+c.get('immature',[])
                lo=[min(p[k] for p in points) for k in range(3)]; hi=[max(p[k] for p in points) for k in range(3)]
                c['testDomainCorrection']='Outside-source distractor exceeded the 256-cell contract'
            c['sourceBounds']=lo+hi
            if c['item']!=WHEAT and c['geometry']=='vertical' and c['quantity']>3:
                c['expectedCompletion']=False; c['expectedReachLimitation']=True
            c['caseSha256']=hashlib.sha256(json.dumps(c,sort_keys=True).encode()).hexdigest()
            corpus.append(c); chosen.append(c)
        probes=read(HERE/'failure-probes.json',{'cases':[]})['cases']
        for c in probes:
            c.pop('caseSha256',None)
            c['caseSha256']=hashlib.sha256(json.dumps(c,sort_keys=True).encode()).hexdigest()
        corpus+=copy.deepcopy(probes); chosen+=copy.deepcopy(probes)
    if case is not None: chosen=[c for c in chosen if c['id']==case]
    chosen.sort(key=lambda c:(FAMILIES.index(c['family']),c['seed'],c['index']))
    if mode=='smoke' and case is None:
        chosen=[next(c for c in chosen if c['family']==f) for f in families]
    if budget: chosen=chosen[:budget]
    if not chosen: raise ValueError('Selection contains no cases')
    return corpus,chosen


def reproduction(c,campaign_seed,reuse_world=False,heldout_seed=None,certificate=None):
    suffix=' --reuse-world build/e2e/previous-world' if reuse_world else ''
    if heldout_seed is not None: suffix+=' --heldout-seed '+str(heldout_seed)
    if c.get('replaySource'): suffix+=' --replay-original'
    if certificate: suffix+=' --reuse-certificate '+certificate
    return f'python3 e2e/campaigns/world/runner.py --root build/replay-{c["id"]}-{campaign_seed} --seed {campaign_seed} --mode full --case {c["id"]}'+suffix+'\n'


class WorldHarness(AssessmentHarness):
    def __init__(self,root,interpreter=False):
        self.case_dir=None; self.case=None; self.samples=[]; self.active_run=None; self.executed_case=False
        self.last_sample=None; self.attempted_case=False; self.family='WORLD-STARTUP'
        Harness.__init__(self,root,'compose',None)
        self.hashes={}; self.admissions=[]; self.profile='generalization-world'
        navigation.LEVEL=208
        atomic(self.root/'observation-area.json',dict(minX=63,footY=208,minZ=63,maxX=85,maxZ=79,maxObservationDy=4))
        corpus=read(E2E/'generalization/cases.json')
        atomic(self.root/'assessment-language.json',{r['id']:r['requirement'] for r in corpus['scenarios'] if r['category']=='language'})
        self.env['COGNITIVECRAFT_E2E_SNAPSHOT_TICKS']='1'
        metadata=read(self.root/'metadata.json')
        metadata['campaignInstrumentation']=read(ROOT/'build/e2e-driver/campaign-instruments.json')
        atomic(self.root/'metadata.json',metadata)
        if interpreter:
            shutil.copytree(ROOT/'build/needle',self.root/'needle',dirs_exist_ok=True)
            self.env['COGNITIVECRAFT_NEEDLE_DIR']='/evidence/needle'

    def event(self,kind,data):
        Harness.event(self,kind,data)
        if self.case_dir:
            with (self.case_dir/'events.jsonl').open('a') as f:
                f.write(json.dumps(dict(time=time.time(),kind=kind,data=data))+'\n')

    def model_calls(self):
        state=self.snapshot()
        return {'generationCalls':state['brokerStats']['calls'],'needleCalls':state['needleCalls']}

    def client_aux(self,player,action):
        request={'id':str(uuid.uuid4()),'action':action}; box=self.root/'clients'/player
        atomic(box/'world-request.json',request)
        return self.wait(lambda:(v['result'] if (v:=read(box/'world-response.json')) and v['id']==request['id'] else None),
                         'Campaign native client '+action,35)

    def ensure_client(self,player='PlayerA'):
        try: current=self.action(player,'snapshot')
        except TimeoutError:
            self.event('campaign-client-infrastructure-restart',{'player':player,'reason':'Driver mailbox unresponsive; preserve prior process evidence'})
            self.stop(player,hard=True); self.start(player); current=self.client_snapshot(player)
        if not current.get('play'): self.start(player)
        state=self.client_aux(player,'state')
        if state.get('play') and not state.get('alive'):
            self.event('campaign-player-native-respawn',{'player':player,'before':state})
            self.client_aux(player,'respawn')
            self.wait(lambda:(v if (v:=self.client_aux(player,'state')).get('alive') else None),'Actual native player respawn',40)

    def snapshot(self):
        state=Harness.snapshot(self)
        if state and self.case and self.case_dir and state.get('gameTick')!=self.last_sample:
            self.last_sample=state.get('gameTick')
            view=state.get('knownPlayers',{}).get('PlayerA',{}).get('runs',{}).get(self.active_run) or {}
            if view.get('phase')=='EXECUTING' or receipts(view,self.case['item']): self.executed_case=True
            sample={k:state.get(k) for k in ('time','gameTick','pid','actors','containers','drops')}
            points=self.case['positions']+self.case.get('immature',[])+self.case.get('unrelated',[])
            sample['blocks']={key(p):state.get('blocks',{}).get(key(p)) for p in points}
            sample['knownPlayers']={p:{'uuid':d.get('uuid'),'assessmentInventory':d.get('assessmentInventory'),
                'leases':d.get('leases'),'run':d.get('runs',{}).get(self.active_run)}
                for p,d in state.get('knownPlayers',{}).items()}
            if len(self.samples)>=7000: raise Inconclusive('Per-case physical observation budget exceeded')
            self.samples.append(sample)
            with (self.case_dir/'observations.jsonl').open('a') as f: f.write(json.dumps(sample)+'\n')
        return state

    def screenshot(self,player,checkpoint,chat=False):
        # The shared helper caps a smaller suite at 160 frames; this is a larger batch.
        require(len(self.screenshots)<512,'Campaign native-frame budget exceeded')
        name=re.sub(r'[^A-Za-z0-9_-]','-',f'{self.family}-{len(self.screenshots):03d}-{checkpoint}')[:150]
        frame=self.action(player,'screenshot',name=name,chat=chat)
        path=self.root/frame['path']; raw=path.read_bytes()
        require(raw[:8]==b'\x89PNG\r\n\x1a\n' and len(raw)>1024,'Native frame incomplete')
        metadata=read(self.root/'metadata.json')
        value={**frame,'family':self.family,'player':player,'checkpoint':checkpoint,
            'sha256':hashlib.sha256(raw).hexdigest(),'bytes':len(raw),'width':int.from_bytes(raw[16:20],'big'),
            'height':int.from_bytes(raw[20:24],'big'),'nativeClientFrame':True,'time':time.time(),
            'sourceCommit':metadata['sourceCommit'],'releaseSha256':metadata['releaseSha256']}
        self.screenshots.append(value); atomic(self.root/'screenshots.json',{'captures':self.screenshots})
        self.event('screenshot-evidence',value)
        return value

    def setup(self,two=False,legacy=False):
        require(not two and not legacy,'Campaign starts one client')
        self.console(['op PlayerA','gamemode spectator PlayerA','tp PlayerA 66.5 208 69.5'])
        self.wait(lambda:self.snapshot() and all(key(p) in self.snapshot()['blocks'] for p in (point(-1,-1),point(21,15))),
                  'Native chunks loaded before relocated fixture writes',40)
        self.console(['gamerule minecraft:mob_griefing true','gamerule minecraft:do_mob_spawning false',
            'gamerule minecraft:random_tick_speed 0','gamerule minecraft:do_daylight_cycle false',
            'gamerule minecraft:block_drops true','time set day',
            'fill 63 207 63 85 207 79 minecraft:stone','fill 63 206 63 85 206 79 minecraft:sea_lantern',
            'fill 63 208 63 85 213 79 minecraft:air',
            'summon minecraft:villager 66.5 208 66.5 {PersistenceRequired:1b}',
            'spawnpoint PlayerA 66 208 69','gamemode survival PlayerA'])
        self.wait(lambda:self.snapshot() and len(self.snapshot()['actors'])==1,'One adult normal-AI subject',40)
        require(all(a['alive'] and not a['baby'] and not a['noAI'] for a in self.snapshot()['actors']),
                'Normal native villager AI is required')

    def reset_case(self,c):
        require(not self.actions_active,'No fixtures during active gameplay')
        require(not getattr(self,'legacy_subjects',{}),'No fixture reset until all enrolled legacy owners are released')
        self.ensure_client()
        self.console(['tp PlayerA 66.5 208 69.5'])
        self.wait(lambda:(s if (s:=self.snapshot()) and 'PlayerA' in s.get('players',{}) and
            all(key(p) in s.get('blocks',{}) for p in (point(-1,-1),point(21,15))) else None),
            'Real client loaded the scene before fixture writes',50)
        if c['family'] in ('W1','W2','W3','W4','W5','W6','W7'):
            self.select_actor(c.get('actorChoice',0))
        actor=[c['actor'][0]+.5,c['actor'][1],c['actor'][2]+.5]
        inv=[{'id':'minecraft:cobblestone','count':64} for _ in range(c.get('actorOccupiedSlots',0))]
        self.console(['kill @e[type=minecraft:item]','kill @e[type=minecraft:villager,tag=campaign_world_subject]',
            'fill 63 208 63 85 213 79 minecraft:air','fill 63 207 63 85 207 79 minecraft:stone',
            'fill 63 208 63 85 213 63 minecraft:glass','fill 63 208 79 85 213 79 minecraft:glass',
            'fill 63 208 63 63 213 79 minecraft:glass','fill 85 208 63 85 213 79 minecraft:glass',
            'fill 63 214 63 85 214 79 minecraft:glass',
            'data merge entity '+self.entities['PlayerA']+' {Inventory:'+json.dumps(inv,separators=(',',':'))+'}',
            'tp '+self.entities['PlayerA']+' '+coord(actor),'tp PlayerA 66.5 208 69.5','clear PlayerA'])
        if getattr(self,'competition_entity',None):
            self.console(['tp '+self.competition_entity+' 83.5 208 76.5',
                'fill 82 208 75 84 210 77 minecraft:glass','setblock 83 208 76 minecraft:air',
                'setblock 83 209 76 minecraft:air','data merge entity '+self.competition_entity+' {Inventory:[]}'])
        if getattr(self,'foreign_entity',None):
            self.console(['tp '+self.foreign_entity+' 83.5 208 72.5',
                'fill 82 208 71 84 210 73 minecraft:glass','setblock 83 208 72 minecraft:air',
                'setblock 83 209 72 minecraft:air','data merge entity '+self.foreign_entity+' {Inventory:[]}'])
        for citizen,entity in getattr(self,'actor_pool',[]):
            if entity!=self.entities['PlayerA']:
                self.console(['tp '+entity+' 65.5 208 76.5','fill 64 208 75 66 210 77 minecraft:glass',
                    'setblock 65 208 76 minecraft:air','setblock 65 209 76 minecraft:air',
                    'data merge entity '+entity+' {Inventory:[]}'])
        self.stock_fixture({},c['destination'],c['container'],slots=c['destinationSlots'])
        if c['family']=='W7':
            for citizen,_ in getattr(self,'actor_pool',[]):
                if citizen!=self.citizens['PlayerA']:
                    self.command('PlayerA','/aivillage kernel name '+citizen+' Auxiliary','name=Auxiliary')
            full=[(i,'minecraft:cobblestone',64) for i in range(27)]
            self.stock_fixture({},c['distractorFull'],c['container'],slots=full)
            self.stock_fixture({},c['distractorWrongType'],'chest' if c['container']=='barrel' else 'barrel')
            self.stock_fixture({},c['distractorFar'],c['container'])
        lines=[]
        for p in c['positions']:
            if c['item']==WHEAT:
                lines.append('setblock '+coord([p[0],p[1]-1,p[2]])+' minecraft:farmland[moisture=7]')
                block='minecraft:wheat[age=7]'
            else: block=c['item']+'[axis='+c['logAxis']+']'
            lines.append('setblock '+coord(p)+' '+block)
        for p in c.get('immature',[]):
            lines+=['setblock '+coord([p[0],p[1]-1,p[2]])+' minecraft:farmland[moisture=7]',
                    'setblock '+coord(p)+' minecraft:wheat[age=3]']
        lines+=['setblock '+coord(p)+' '+c['nearbyUnrelated'] for p in c.get('unrelated',[])]
        lines+=['setblock '+coord(p)+' minecraft:stone' for p in c.get('standingSupports',[])]
        if c['terrain']=='detour': lines.append('fill 69 208 67 69 209 74 minecraft:stone')
        if c['terrain']=='corridor': lines+=['fill 69 208 65 69 209 70 minecraft:stone',
                                           'fill 69 208 72 69 209 77 minecraft:stone']
        if c.get('disturbance')=='gate':
            lines+=['fill 69 208 64 69 210 78 minecraft:oak_fence',
                    'setblock 69 208 69 minecraft:oak_fence_gate[facing=east,open=true]',
                    'setblock 69 209 69 minecraft:air','setblock 69 210 69 minecraft:air']
        self.console(lines)
        self.wait(lambda:all(c['item'] in self.block(p) for p in c['positions']), 'Observed generated source',20)
        self.wait(lambda:self.inventory().get('minecraft:cobblestone',0)==64*c.get('actorOccupiedSlots',0),
                  'Native actor inventory occupancy precondition',20)
        self.quiet(3)
        require(self.container(c['destination'],c['item'])==c['existingStock'],'Initial destination stock mismatch')

    def select_actor(self,choice):
        if not getattr(self,'actor_pool',None):
            self.actor_pool=[(self.citizens['PlayerA'],self.entities['PlayerA'])]
        if choice and len(self.actor_pool)<2:
            original=self.actor_pool[0]
            self.console(['kill @e[type=minecraft:villager,tag=campaign_world_subject]',
                'tp '+original[1]+' 83.5 208 66.5','tp PlayerA 66.5 208 69.5',
                'summon minecraft:villager 66.5 208 66.5 {PersistenceRequired:1b,Tags:["campaign_world_auxiliary"]}'])
            self.enroll(name='Auxiliary')
            self.actor_pool.append((self.citizens['PlayerA'],self.entities['PlayerA']))
            atomic(self.root/'actor-pool.json',[{'role':i,'citizen':c,'entity':e} for i,(c,e) in enumerate(self.actor_pool)])
        self.citizens['PlayerA'],self.entities['PlayerA']=self.actor_pool[choice]

    def request_case(self,c,acquire=False):
        if c['family']=='W7':
            self.command('PlayerA','/aivillage kernel name '+self.citizens['PlayerA']+' Ada','name=Ada')
            reference='the nearest suitable' if c['reference']=='nearest suitable' else c['reference']
            self.wait(lambda:'Ada' in self.command('PlayerA','/aivillage kernel citizens','citizens='),'Named actor published before language request',30)
            text=f'Ada, fetch {c["quantity"]} oak logs and put them into {reference} {c["container"]}.'
            command='/aivillage kernel ask '+text; self.attempted_case=True
            # The public switch governs both generation and language interpretation.
            # Keep the generator service stopped; require the admitted artifact and unchanged broker calls below.
            self.command('PlayerA','/aivillage kernel inference true','admission=true')
            try:
                answer=self.command('PlayerA',command,'interpretation='+UUID_PATTERN)
                ticket=re.search('interpretation=('+UUID_PATTERN+')',answer).group(1)
                view=self.wait(lambda:(v if (v:=self.run_view('PlayerA',ticket)) and v['phase'] not in ('INTERPRETING','RESOLVING') else None),
                               'Real interpreter and independent nearest selection',65)
            finally: self.command('PlayerA','/aivillage kernel inference false','admission=false')
            self.event('nearest-interpretation',dict(ticket=ticket,view=view,text=text))
            return dict(command=command,feedback=answer,accepted=view['phase']=='SUBMITTED',
                        run=view.get('runId') or ticket,interpretation=view)
        # Unrelated distractors can sit outside the source; they must not enlarge its contract volume.
        points=c['positions']+c.get('immature',[])
        bounds=c.get('sourceBounds') or [min(p[k] for p in points) for k in range(3)]+[max(p[k] for p in points) for k in range(3)]
        lo,hi=bounds[:3],bounds[3:]
        verb={WHEAT:'harvest',LOG:'gather-oak',BIRCH:'gather-birch'}[c['item']]
        command='/aivillage kernel '+verb+' '+self.citizens['PlayerA']+' '+str(c['quantity'])+' '+coord(lo+hi+c['destination'])
        self.attempted_case=True
        answer=self.command('PlayerA',command,'run='+UUID_PATTERN)
        refusals=[]
        if 'accepted=false reason=BUDGET_EXHAUSTED' in answer:
            def published():
                nonlocal answer
                refusals.append(answer); self.event('campaign-zero-work-intake-refusal',{'command':command,'feedback':answer})
                answer=self.command('PlayerA',command,'run='+UUID_PATTERN)
                return answer if 'accepted=false reason=BUDGET_EXHAUSTED' not in answer else None
            self.wait(published,'Durable intake publication became available; no accepted work repeated',30)
        return dict(command=command,feedback=answer,accepted='accepted=true' in answer,
                    run=re.search('run=('+UUID_PATTERN+')',answer).group(1),sourceBounds=lo+hi,publicationRefusals=refusals)

    def prepare_backend(self):
        if getattr(self,'backend_prepared',False):
            self.compose('--profile','ai','up','-d','ollama'); return
        import urllib.request
        self.compose('--profile','ai','up','-d','ollama')
        self.compose('exec','-T','ollama','ollama','pull',self.env['COGNITIVECRAFT_OLLAMA_MODEL'])
        (self.root/'models.txt').write_text(self.compose('exec','-T','ollama','ollama','list').stdout)
        with urllib.request.urlopen('http://127.0.0.1:11434/api/tags',timeout=20) as response:
            atomic(self.root/'models.json',json.load(response))
        self.backend_prepared=True

    def public_intake(self,player,command,tag):
        answers=[]
        def ready():
            answer=self.command(player,command,tag+'='+UUID_PATTERN)
            answers.append(answer)
            if 'accepted=false reason=BUDGET_EXHAUSTED' in answer:
                self.event('campaign-zero-work-publication-refusal',{'player':player,'command':command,'feedback':answer})
                return None
            return answer
        answer=self.wait(ready,'Public intake available without repeating accepted work',30)
        return answer,answers[:-1]

    def bootstrap_world(self,reuse_world=None,resources=(WHEAT,LOG,BIRCH),reuse_certificate=None):
        self.env['COGNITIVECRAFT_OLLAMA_MODEL']='qwen3:4b-instruct-2507-q4_K_M'
        if not getattr(self,'image_prepared',False):
            self.compose('build','minecraft'); self.image_prepared=True
        if reuse_certificate:
            from cohort import restore
            restore(self,reuse_certificate)
        elif reuse_world:
            from seed import restore
            restore(self,reuse_world)
        else: self.fresh(two=False); self.enroll()
        missing=[item for item in resources if 'cognitivecraft:'+CAPS[item] not in self.hashes]
        if not missing or reuse_world or reuse_certificate:
            self.command('PlayerA','/aivillage kernel inference false','admission=false'); return
        self.prepare_backend()
        self.command('PlayerA','/aivillage kernel inference true','admission=true')
        for item in missing:
            self.family='ADMISSION-'+CAPS[item]
            self.case_dir=self.root/'admissions'/getattr(self,'resource_cohort','initial')/CAPS[item]; self.case_dir.mkdir(parents=True)
            c=next(c for c in generate() if c['item']==item)
            c={**c,'quantity':1,'positions':[point(7,8)],'destinationSlots':[],'occupiedSlots':0,
                'actorOccupiedSlots':0,'existingStock':0,'terrain':'flat','immature':[],'unrelated':[],
                'standingSupports':[],'sourceBounds':point(7,8)+point(7,8),'disturbance':None}
            admission={'item':item,'status':'INCONCLUSIVE','cohort':getattr(self,'resource_cohort','initial')}
            try:
                self.reset_case(c); before=self.snapshot(); calls=self.model_calls(); req=self.request_case(c,True)
                atomic(self.case_dir/'before.json',before); atomic(self.case_dir/'request.json',req)
                require(req['accepted'],'Admission refused: '+req['feedback'])
                self.actions_active=True; self.active_run=req['run']
                view=self.finish_run(req['run'],admitted=True)
                require(self.model_calls()['generationCalls']>calls['generationCalls'],'No genuine model admission')
                require(self.container(c['destination'],item)==1,'Admission lacks physical output')
                self.hashes['cognitivecraft:'+CAPS[item]]=view['marker']['artifactSha256']
                admission.update(status='PASS',request=req,view=view,callsBefore=calls,callsAfter=self.model_calls())
            except Exception as error:
                admission.update(status='FAIL_ARCHITECTURAL_GAP',reason=str(error),trace=traceback.format_exc())
            finally:
                self.cleanup_world(); admission['after']=self.snapshot()
                atomic(self.case_dir/'result.json',admission)
                self.admissions.append(admission); atomic(self.root/'admissions.json',self.admissions)
                self.case_dir=None; self.active_run=None
        self.command('PlayerA','/aivillage kernel inference false','admission=false')
        self.compose('stop','ollama'); self.actions_active=False

    def cleanup_world(self):
        # Unlike the shared assessment helper, do not allow fixture writes until every owner is terminal.
        if 'server' not in self.booted: return
        self.ensure_client()
        self.console(['tick unfreeze'],lifecycle=True)
        self.cleanup_legacy()
        state=self.snapshot() or {}
        for player,data in state.get('knownPlayers',{}).items():
            if not data.get('online'):
                require(not any(v.get('phase') in ACTIVE for v in data.get('runs',{}).values()),'Disconnected principal still owns active work; halt before fixtures')
                require(all(j.get('state') in ('SUCCEEDED','FAILED','CANCELLED','INTERRUPTED') for j in data.get('jobs',{}).values()),
                        'Disconnected principal still owns a nonterminal Job; halt before fixtures')
                continue
            for rid,v in data.get('runs',{}).items():
                if v.get('phase') in ACTIVE:
                    self.command(player,'/aivillage kernel cancel '+rid,'cancel|CANCEL|denied|unavailable',20)
                job=v.get('responsibility')
                if job and job.get('state') not in ('SUCCEEDED','FAILED','CANCELLED'):
                    jid=job['id']
                    self.command(player,'/aivillage kernel job-cancel '+jid,'job='+jid)
                    def stopped(player=player,rid=rid,jid=jid):
                        d=(self.snapshot() or {}).get('knownPlayers',{}).get(player,{})
                        j=(d.get('runs',{}).get(rid) or {}).get('responsibility') or d.get('jobs',{}).get(jid)
                        return j and j.get('state') in ('SUCCEEDED','FAILED','CANCELLED')
                    self.wait(stopped,'Actual direct responsibility cancelled before fixture reset',45)
            for jid,j in data.get('jobs',{}).items():
                if j.get('state') not in ('SUCCEEDED','FAILED','CANCELLED','INTERRUPTED'):
                    self.cancel_job(player,jid)
        self.wait(lambda:all(v.get('phase') not in ACTIVE for d in (self.snapshot() or {}).get('knownPlayers',{}).values()
                            for v in d.get('runs',{}).values()),'All gameplay owners terminal before resetting fixtures',40)
        self.actions_active=False; self.quiet(3)

    def cleanup_legacy(self):
        if getattr(self,'legacy_subjects',{}): self.action('PlayerA','close-menu')
        for name in list(getattr(self,'legacy_subjects',{})):
            self.command('PlayerA','/aivillage stop '+name,'stop: '+re.escape(name))
            self.command('PlayerA','/aivillage release '+name,'release: '+re.escape(name))
            del self.legacy_subjects[name]
        require(not getattr(self,'legacy_subjects',{}),'Legacy owner cleanup must be acknowledged before fixture writes')


def citizen_case(h,c):
    base_citizen,base_entity=h.citizens['PlayerA'],h.entities['PlayerA']
    fresh=c['index']%3==0
    try:
        if fresh:
            existing={a['uuid'] for a in h.snapshot()['actors']}
            h.console(['tp '+base_entity+' 83.5 208 66.5',
                'summon minecraft:villager '+coord([c['actor'][0]+.5,208,c['actor'][2]+.5])+
                ' {PersistenceRequired:1b,Tags:["campaign_world_subject"]}'])
            h.wait(lambda:any(a['uuid'] not in existing for a in h.snapshot()['actors']),'Fresh physical citizen subject',20)
            h.attempted_case=True; h.enroll(name=c['citizenName'])
        else:
            h.attempted_case=True
            h.command('PlayerA','/aivillage kernel name '+base_citizen+' '+c['citizenName'],'name='+c['citizenName'])
        citizen,entity=h.citizens['PlayerA'],h.entities['PlayerA']; h.executed_case=True
        def address(): return h.snapshot().get('knownPlayers',{}).get('PlayerA',{}).get('lifecycle',{}).get(citizen)
        before=h.wait(lambda:(v if (v:=address()) and v.get('displayName')==c['citizenName'] else None),
                      'Durable client-owned citizen name',30)
        boundary=None
        if c['lifecycle']=='reconnect':
            h.disconnect('PlayerA'); h.start('PlayerA')
        elif c['lifecycle']=='unload':
            h.console(['forceload add 10000 10000'])
            h.wait(lambda:'Test passed' in h.command('PlayerA','/execute if loaded 10000 208 10000','Test passed|Test failed'),
                   'Remote landing chunk genuinely loaded before fixture writes',45)
            h.console(['fill 10000 207 10000 10008 207 10008 minecraft:stone','setworldspawn 10004 208 10004'])
            h.command('PlayerA','/execute if block 10004 207 10004 minecraft:stone','Test passed')
            try:
                h.command('PlayerA','/tp 10004.5 208 10004.5','Teleported')
                boundary=h.wait(lambda:(v if (v:=address()) and v['availability']=='UNLOADED'
                    and v['lifecycle']['event']=='UNLOAD' else None),'Actual same-citizen native chunk unload',110)
            finally:
                h.ensure_client(); h.command('PlayerA','/tp 66.5 208 69.5','Teleported')
                h.console(['forceload remove 10000 10000'])
        elif c['lifecycle']=='restart':
            boundary=h.save_checkpoint(); h.stop('server',hard=True)
            h.wait(lambda:not h.action('PlayerA','snapshot')['play'],'Actual server termination disconnected client',40)
            h.start('server'); reconnect_players(h,['PlayerA'])
        after=h.wait(lambda:(v if (v:=address()) and v['availability']=='LOADED' and
                    v['lifecycle']['event']=='LOAD' else None),'Native identity returned loaded',80)
        native=next(a for a in h.snapshot()['actors'] if a['uuid']==entity)
        props=dict(identity=after['actor']==before['actor'] and after['owner']==before['owner']
                    and after['displayName']==c['citizenName'] and native['alive'] and not native['noAI'])
        if c['lifecycle']=='unload': props['lifecycle']=after['lifecycle']['generation']==before['lifecycle']['generation']+1
        if c['lifecycle']=='restart': props['recovery']=props['identity'] and bool(boundary['files'])
        listing=h.command('PlayerA','/aivillage kernel citizens','citizens=')
        props['clientIdentity']=citizen in listing and c['citizenName'] in listing
        atomic(h.case_dir/'citizen.json',dict(before=before,boundary=boundary,after=after,native=native,listing=listing))
        h.screenshot('PlayerA','citizen-lifecycle',chat=True)
        return dict(status='PASS' if all(props.values()) else 'FAIL_GAMEPLAY',genuineGameplay=True,
                    gameplayAttempted=True,properties=props,freshEnrollment=fresh,citizen=citizen,entity=entity)
    finally:
        h.citizens['PlayerA']=base_citizen; h.entities['PlayerA']=base_entity


def disturb(h,c,run):
    stage={'restart-harvest':'HARVEST','restart-pickup':'PICKUP','restart-deposit':'DEPOSIT'}.get(c['disturbance'])
    def boundary():
        v=h.run_view('PlayerA',run)
        if not v: return None
        if v.get('phase')=='TERMINAL': raise Inconclusive('Task ended before the required disturbance boundary')
        if v.get('phase')=='EXECUTING' and (stage is None or stages(v,c['item']).get(stage,0)>0): return v
    view=h.wait(boundary,'Witness live '+str(stage or 'execution')+' boundary',130)
    atomic(h.case_dir/'disturbance-boundary.json',view)
    if c['disturbance']=='cancel':
        answer=h.command('PlayerA','/aivillage kernel cancel '+run,'cancel|CANCEL|TERMINAL')
        return dict(kind='cancel',feedback=answer,boundary=view)
    if c['disturbance']=='gate':
        target=point(5,5); h.walk('PlayerA',target); h.action('PlayerA','use',pos=target)
        h.wait(lambda:'open=false' in h.block(target),'Connected player closed actual navigation gate',25)
        return dict(kind='gate',target=target,observed=h.snapshot())
    if c['disturbance']=='break':
        target=c['positions'][-1]
        if 'minecraft:air' in h.block(target): raise Inconclusive('Chosen interference block was already harvested')
        h.walk('PlayerA',target); h.action('PlayerA','break',pos=target)
        h.wait(lambda:'minecraft:air' in h.block(target),'Connected player removed actual source',30)
        return dict(kind='break',target=target,observed=h.snapshot())
    # Stop time only to make a genuine already-observed boundary reproducible; no tested effect is supplied.
    h.console(['tick freeze'],lifecycle=True)
    current=h.run_view('PlayerA',run)
    if not current or current.get('phase')!='EXECUTING':
        h.console(['tick unfreeze'],lifecycle=True)
        raise Inconclusive('Execution ended before checkpoint could be frozen')
    saved=h.snapshot(); checkpoint=h.save_checkpoint()
    before_kill=h.run_view('PlayerA',run)
    if not before_kill or before_kill.get('phase')!='EXECUTING':
        h.console(['tick unfreeze'],lifecycle=True)
        raise Inconclusive('Task ended during the acknowledged checkpoint; active crash boundary missed')
    client_process=read(h.root/'clients/PlayerA/process.json')
    h.stop('server',hard=True); killed=h.world_hashes()
    last=h.snapshot()
    last_view=last.get('knownPlayers',{}).get('PlayerA',{}).get('runs',{}).get(run) or {}
    live_at_kill=last_view.get('phase')=='EXECUTING'
    shutil.copytree(h.root/'server/game/world',h.case_dir/'crashed-world',ignore=shutil.ignore_patterns('session.lock'))
    atomic(h.case_dir/'crash-world-proof.json',{'files':killed,'lastNativeSnapshot':last,'liveAtKill':live_at_kill})
    h.wait(lambda:not h.action('PlayerA','snapshot')['play'],'SIGKILL disconnected actual client',40)
    h.start('server'); reconnect_players(h,['PlayerA']); h.console(['tick unfreeze'],lifecycle=True)
    require(read(h.root/'clients/PlayerA/process.json')==client_process,'Recovery replaced persistent client process')
    return dict(kind=c['disturbance'],checkpoint=checkpoint,boundary=current,before=saved,
                killedWorldHashes=killed,after=h.snapshot(),liveAtKill=live_at_kill,persistentClientProcess=client_process)


def execute(h,c):
    h.executed_case=False; h.attempted_case=False; h.samples=[]; h.last_sample=None; h.active_run=None
    if c['family'] in ('W8','W9','W10','W11','W12'):
        from extra import legacy,authority,death
        from legacy_extra import legacy_group,legacy_safety
        return {'W8':legacy,'W9':authority,'W10':death,'W11':legacy_group,'W12':legacy_safety}[c['family']](h,c)
    h.reset_case(c)
    if c['family']=='W5': return citizen_case(h,c)
    before=h.snapshot(); atomic(h.case_dir/'before.json',before)
    client_before=h.client_snapshot('PlayerA'); atomic(h.case_dir/'client-before.json',client_before)
    require(client_before and client_before['play'] and not client_before['singleplayer'],'Real multiplayer client required')
    calls=h.model_calls(); request=h.request_case(c); atomic(h.case_dir/'request.json',request)
    h.active_run=request['run']; disturbance=None
    if request['accepted']:
        h.actions_active=True
        if c['family']=='W6': disturbance=disturb(h,c,request['run'])
        view=h.finish_run(request['run'],success=False)
    else: view=h.run_view('PlayerA',request['run']) or {'id':request['run']}
    h.quiet(12); after=h.snapshot(); atomic(h.case_dir/'after.json',after); atomic(h.case_dir/'run.json',view)
    client=h.client_snapshot('PlayerA'); atomic(h.case_dir/'client-after.json',client)
    props,accounting=evaluate(c,before,after,view,h.citizens['PlayerA'],h.entities['PlayerA'],h.samples)
    props['noInference']=h.model_calls()==calls
    props['methodIdentity']=(view.get('marker') or {}).get('artifactSha256')==h.hashes.get('cognitivecraft:'+CAPS[c['item']]) and 'cognitivecraft:'+CAPS[c['item']] in h.hashes
    if c['family']=='W7':
        now=h.model_calls()
        props['noInference']=now['generationCalls']==calls['generationCalls'] and now['needleCalls']==calls['needleCalls']+1
        props['nearestSelection']=all(before['containers'].get(key(c[k]))==after['containers'].get(key(c[k]))
            for k in ('distractorFull','distractorWrongType','distractorFar'))
    h.executed_case=bool(receipts(view,c['item'])) or any(s.get('knownPlayers',{}).get('PlayerA',{}).get('run',{} or {}).get('phase')=='EXECUTING'
                            for s in h.samples if s.get('knownPlayers',{}).get('PlayerA',{}).get('run'))
    if c['family']=='W6' and disturbance:
        props['disturbanceObserved']=True
        if c['disturbance']=='cancel': props['cancellation']=outcome(view) in ('CANCELLED','INTERRUPTED')
        if c['disturbance'].startswith('restart-'):
            if not disturbance['liveAtKill']: raise Inconclusive('Last native pre-kill snapshot was already terminal; no active checkpoint recovery credit')
            old=disturbance['before']; restarted=disturbance['after']; h.quiet(30); later=h.snapshot()
            already_harvested=[p for p in c['positions'] if 'minecraft:air' in old['blocks'].get(key(p),'')]
            props['durableCustody']=(sum(custody(later,c['item']).values())>=sum(custody(old,c['item']).values()) and
                all('minecraft:air' in later['blocks'].get(key(p),'') for p in already_harvested))
            recovered_outcome=outcome(view)
            acceptable=recovered_outcome in ('INTERRUPTED','FAILED','CANCELLED') or recovered_outcome=='SUCCEEDED' and props['delivery'] and props['harvest']
            props['recovery']=(bool(disturbance['checkpoint']['files']) and restarted['containers']==later['containers'] and
                not any(a['controlled'] for a in later['actors']) and acceptable)
            props['noInference']=(disturbance['before']['brokerStats']['calls']==calls['generationCalls'] and
                disturbance['before']['needleCalls']==calls['needleCalls'] and all(value==0 for value in h.model_calls().values()))
    failure_reason=(view.get('outcome') or {}).get('reason') or (view.get('marker') or {}).get('reason')
    if not request['accepted']:
        match=re.search(r'reason=([A-Z_]+)',request.get('feedback',''))
        if match: failure_reason=match.group(1)
    if failure_reason=='STORAGE_LIMIT_REACHED':
        status='FAIL_ARCHITECTURAL_GAP'
        if not receipts(view,c['item']): h.executed_case=False
    elif not all(props[k] for k in SAFETY) or props.get('durableCustody') is False: status='FAIL_SAFETY'
    elif c['family']=='W6':
        required=['noInference','disturbanceObserved']+(['recovery'] if c['disturbance'].startswith('restart-') else ['cancellation'] if c['disturbance']=='cancel' else [])
        status='PASS' if all(props.get(k) for k in required) else 'FAIL_GAMEPLAY'
        if status=='PASS' and c['disturbance'] in ('gate','break') and not props['delivery']:
            status='EXPECTED_SAFE_REFUSAL'
    elif not c['expectedCompletion'] and not props['delivery']:
        status='EXPECTED_SAFE_REFUSAL' if c.get('expectedReachLimitation') or accounting['destinationDelta']==0 and not receipts(view,c['item']) else 'INCONCLUSIVE'
    elif 'cognitivecraft:'+CAPS[c['item']] not in h.hashes: status='FAIL_ARCHITECTURAL_GAP'
    else: status='PASS' if props['delivery'] and props['harvest'] and props['navigation'] and props['noInference'] and props['methodIdentity'] and props.get('nearestSelection',True) else 'FAIL_GENERALIZATION'
    if props['delivery']:
        require(h.menu_count('PlayerA',c['destination'],c['item'])==c['existingStock']+c['quantity'],
                'Actual connected container menu differs from native transfer')
    else: h.screenshot('PlayerA','world-result',chat=True)
    return dict(status=status,genuineGameplay=h.executed_case,gameplayAttempted=h.attempted_case,
                request=request,properties=props,accounting=accounting,disturbance=disturbance,failureReason=failure_reason,
                firstDivergence=next(({'property':k,'observed':v,'context':'observations.jsonl, request.json, run.json'}
                    for k,v in props.items() if v is False),None))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root',type=Path,required=True); p.add_argument('--seed',type=int,default=104729)
    p.add_argument('--mode',choices=('smoke','full'),default='smoke'); p.add_argument('--families',default=','.join(FAMILIES))
    p.add_argument('--budget',type=int,default=0); p.add_argument('--case'); p.add_argument('--generate-only',action='store_true')
    p.add_argument('--include-cases',default=''); p.add_argument('--reuse-world',type=Path)
    p.add_argument('--reuse-certificate',type=Path); p.add_argument('--isolate-families',action='store_true')
    p.add_argument('--cohort-cases',type=int,default=6)
    p.add_argument('--heldout-seed',type=int); p.add_argument('--extra-heldout-families',default=''); p.add_argument('--replay-original',action='store_true')
    a=p.parse_args(); a.root.mkdir(parents=True,exist_ok=True)
    require(not (a.root/'results.json').exists(),'New evidence root required; original attempts are immutable')
    selected=a.families.split(','); require(set(selected)<=set(FAMILIES),'Unknown family'); require(a.budget>=0,'Negative budget')
    files=[Path(__file__),HERE/'generate.py',HERE/'oracle.py',HERE/'extra.py',HERE/'legacy_extra.py',HERE/'seed.py',HERE/'cohort.py']
    if a.replay_original: files.extend([HERE/'replay-cases.json',HERE/'failure-probes.json'])
    atomic(a.root/'generator-freeze.json',dict(files={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in files},heldOutSeed=a.heldout_seed if a.heldout_seed is not None else a.seed+1))
    corpus,cases=selection(a.seed,a.mode,selected,a.budget,a.case,a.include_cases.split(',') if a.include_cases else (),a.heldout_seed,
                           a.extra_heldout_families.split(',') if a.extra_heldout_families else (),a.replay_original)
    atomic(a.root/'corpus.json',dict(baseline=BASELINE,productionTree=TREE,cases=corpus,selected=[c['id'] for c in cases]))
    records=[dict(case=c,status='INFRASTRUCTURE_BLOCKED',gameplayAttempted=False,genuineGameplay=False,reason='Not reached') for c in cases]
    result=dict(schema=2,baseline=BASELINE,productionTree=TREE,campaignSeed=a.seed,requested=len(cases),records=records,complete=False)
    atomic(a.root/'results.json',result)
    if a.generate_only: return
    h=None
    try:
        require(not (a.isolate_families and a.replay_original),'Original replays cannot silently replace the admitted methods')
        require(not a.replay_original or a.reuse_world,'Original replays require the intact original native world')
        if shutil.which('docker') is None: raise InfrastructureBlocked('Docker executable unavailable')
        subprocess.run(['docker','info'],check=True,capture_output=True)
        paths=['core','fabric','providers','build.gradle','settings.gradle','gradle.properties','gradle','scripts/dependencies.json']
        changed=subprocess.check_output(['git','diff',BASELINE,'--',*paths],cwd=ROOT,text=True)
        require(not changed,'Production differs from frozen baseline')
        require(not subprocess.check_output(['git','ls-files','--others','--exclude-standard',*paths],cwd=ROOT,text=True),'Untracked production inputs')
        from cohort import begin as begin_cohort,restore as restore_cohort,resources as family_resources
        require(1<=a.cohort_cases<=6,'A native fixture cohort contains at most six measured cases')
        require(not (a.reuse_world and a.reuse_certificate),'Choose one authentic native seed')
        h=WorldHarness(a.root,interpreter=any(c['family']=='W7' for c in cases))
        needed=tuple(dict.fromkeys(c['item'] for c in cases if family_resources(c['family'])))
        if a.isolate_families:
            require(not a.reuse_world and not a.reuse_certificate,'Isolated family training uses fresh genuine admissions')
            begin_cohort(h,cases[0]['family'])
        else: h.bootstrap_world(a.reuse_world,needed,a.reuse_certificate)
        for rec,c in zip(records,cases):
            if a.isolate_families and family_resources(c['family']):
                group=lambda f:'pipeline' if f in ('W1','W2','W3','W4') else f
                if group(c['family'])!=group(getattr(h,'resource_cohort',None)): begin_cohort(h,c['family'])
                elif h.cohort_case_count>=a.cohort_cases: restore_cohort(h,h.current_certificate)
            h.case_dir=a.root/'cases'/c['id']; h.case_dir.mkdir(parents=True); h.case=c; h.family=c['id']
            if family_resources(c['family']) and getattr(h,'current_certificate',None):
                cert=h.current_certificate.relative_to(h.root) if h.current_certificate.is_relative_to(h.root) else h.current_certificate
                rec['nativeCertificate']=('build/e2e/replay-artifact/generalization-world/'+str(cert)
                    if not cert.is_absolute() else str(cert))
                rec['fixtureCohort']=h.fixture_cohort
                atomic(h.case_dir/'cohort.json',{'fixture':h.fixture_cohort,'certificate':str(cert),
                    'certificateSha256':hashlib.sha256((h.current_certificate/'certificate.json').read_bytes()).hexdigest(),
                    'methods':dict(h.hashes),'purpose':'Explicit isolated native fixture; not gameplay recovery'})
            h.executed_case=False; h.attempted_case=False; offset=len(h.server_log()); frame_offset=len(h.screenshots)
            rec['started']=time.time(); atomic(h.case_dir/'parameters.json',c)
            atomic(h.case_dir/'provenance.json',read(a.root/'metadata.json'))
            try: rec.update(execute(h,c)); rec.pop('reason',None)
            except Exception as error:
                rec.update(status='INCONCLUSIVE' if isinstance(error,(Inconclusive,TimeoutError)) else
                    'INFRASTRUCTURE_BLOCKED' if isinstance(error,(InfrastructureBlocked,AssessmentBlocked,OSError)) else
                    'FAIL_GAMEPLAY' if h.attempted_case and isinstance(error,AssertionError) else 'INCONCLUSIVE',
                    reason=str(error),trace=traceback.format_exc(),gameplayAttempted=h.attempted_case,genuineGameplay=h.executed_case)
                if 'STORAGE_LIMIT_REACHED' in str(error):
                    rec.update(status='FAIL_ARCHITECTURAL_GAP',genuineGameplay=False,failureReason='STORAGE_LIMIT_REACHED')
                with contextlib.suppress(Exception): atomic(h.case_dir/'failure-state.json',h.snapshot())
            finally:
                rec['finished']=time.time(); (h.case_dir/'server.log').write_text(h.server_log()[offset:])
                atomic(h.case_dir/'client-events.json',[e for e in h.events('PlayerA') if e.get('time',0)>=rec['started']*1000])
                for player in h.players:
                    atomic(h.case_dir/('client-events-'+player+'.json'),[e for e in h.events(player) if e.get('time',0)>=rec['started']*1000])
                atomic(h.case_dir/'screenshots.json',h.screenshots[frame_offset:]); atomic(h.case_dir/'result.json',rec)
                atomic(a.root/'results.json',result)
                print('WORLD_PROGRESS '+json.dumps({'case':c['id'],'status':rec['status'],'genuineGameplay':rec['genuineGameplay']},separators=(',',':')),flush=True)
            h.cleanup_world(); h.case=None; h.case_dir=None
            if a.isolate_families and family_resources(c['family']): h.cohort_case_count+=1
        result['batchFinished']=True
    except Exception as error:
        result['infrastructureFailure']=dict(reason=str(error),trace=traceback.format_exc())
        for rec in records:
            if rec.get('reason')=='Not reached': rec['reason']=str(error)
    finally:
        if h:
            h.case=None; h.case_dir=None
            with contextlib.suppress(Exception): h.finish()
        for rec in records:
            c=rec['case']; d=a.root/'cases'/c['id']; d.mkdir(parents=True,exist_ok=True)
            atomic(d/'result.json',rec); atomic(d/'parameters.json',c)
            atomic(d/'provenance.json',read(a.root/'metadata.json',dict(baseline=BASELINE,productionTree=TREE,releaseSha256=None,reason='Runtime never started')))
            (d/'reproduce.txt').write_text(reproduction(c,a.seed,bool(a.reuse_world),a.heldout_seed,rec.get('nativeCertificate')))
        result['counts']=dict(Counter(r['status'] for r in records)); result['gameplayAttempts']=sum(r['gameplayAttempted'] for r in records)
        result['genuineGameplayExecutions']=sum(r['genuineGameplay'] for r in records)
        native=[r for r in records if r.get('genuineGameplay')]
        result['rates']={name:dict(passed=sum(r.get('properties',{}).get(name) is True for r in native),
            evaluated=sum(name in r.get('properties',{}) for r in native),
            observedWithoutNativeExecution=sum(name in r.get('properties',{}) for r in records if not r.get('genuineGameplay'))) for name in
            ('conservation','truthfulCompletion','navigation','delivery','harvest','identity','recovery','cancellation',
             'authority','receiptAuthority','leaseContention','groupAdmission','safetyInterruption')}
        positive=[r for r in records if r['case']['family'] in ('W1','W2','W3','W4','W7') and r['case']['expectedCompletion']]
        measured=[r for r in positive if r.get('genuineGameplay')]
        result['rates']['gameplayCompletion']=dict(requested=len(positive),evaluated=len(measured),passed=sum(r.get('properties',{}).get('delivery') is True for r in measured))
        result['rates']['parameterGeneralization']=dict(requested=len(positive),evaluated=len(measured),passed=sum(r.get('status')=='PASS' for r in measured))
        checked=[r for r in native if all(k in r.get('properties',{}) for k in SAFETY)]
        result['rates']['safety']=dict(evaluated=len(checked),passed=sum(all(r['properties'][k] for k in SAFETY) for r in checked),scope='Physical custody, attribution and world-mutation safety; authority coverage reported separately')
        atomic(a.root/'results.json',result)
    raise SystemExit(1 if any(r['status'] not in ('PASS','EXPECTED_SAFE_REFUSAL') for r in records) else 0)

if __name__=='__main__': main()
