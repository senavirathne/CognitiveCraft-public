"""DComponentCampaign: the frozen 48 cases with two actual native clients and real save cuts."""
import math,time,shutil,json
from pathlib import Path
from client_runner import DStorageObservationClient,PLAYERS,lines
from common import *
from save_observer import observe

class DComponentCampaign(DStorageObservationClient):
    def healthy(self):
        p=self.root/'server/d10/error.json'
        if p.exists():raise RuntimeError(str(read(p)))
    def wait(self,condition,description,timeout=120):
        def checked():self.healthy();return condition()
        return super().wait(checked,description,timeout)
    def rows(self,case):return lines(self.root/'server/d10'/case/'frames.jsonl')
    def sample(self,case,phase,players=PLAYERS,actor_required=True):
        ready=read(self.root/'server/d10'/case/'ready.json');raw=self.rows(case);native=raw[-1]
        for player in players:
            def snapshot():
                state=self.request(player,'snapshot')
                if not state.get('play') or state.get('singleplayer'):return None
                entities={e['uuid']:e for e in state.get('entities',[])}
                if actor_required and ready['actor'] not in entities:return None
                for role,entity in native['entities'].items():
                    if not entity['loaded'] and entity['entity'] in entities:return None
                return state
            state=self.wait(snapshot,f'{case} {phase} actual client packets {player}')
            self.snapshots.append({'case':case,'phase':phase,'player':player,'state':state});write(self.root/'component-clients.json',self.snapshots)
    def tp(self,player,pos):
        self.request(player,'close-menu');self.console([f'tp {player} {pos[0]} {pos[1]} {pos[2]} 0 0'])
        self.wait(lambda:math.dist(self.request(player,'snapshot').get('pos',[0,0,0]),pos)<.4,'actual observer position packet '+player)
    def menus(self,spec,phase,raw=None):
        if spec['group']=='mobility':return
        case=spec['id'];raw=raw or next(r for r in reversed(self.rows(case)) if 'inventories'in r);ready=read(self.root/'server/d10'/case/'ready.json')
        for role,native in raw['inventories'].items():
            if not native['inventory_known'] or role in raw['entities'] and not raw['entities'][role]['loaded']:continue
            for player in PLAYERS:
                if case=='D10-STORAGE-09' and player=='PlayerD_B':continue
                pos=native.get('pos',raw['entities'].get(role,raw['entities']['carrier'])['pos']);self.tp(player,[pos[0]+.5,pos[1],pos[2]-1.5]);self.request(player,'command',text='d-component inspect '+role)
                expected=[(x['native_item_id'],x['count']) for x in native['slots']];size=((len(expected)+8)//9)*9
                def snapshot():
                    s=self.request(player,'snapshot');slots=[r for r in s.get('slots',[]) if not r['playerInventory']]
                    if not s.get('play') or not any(k in s.get('screen','') for k in ('Chest','Container')) or len(slots)!=size:return None
                    if any((x['item'] if x['count'] else None,x['count'])!=expected[i] for i,x in enumerate(slots[:len(expected)])):return None
                    if any(x['count'] for x in slots[len(expected):]):return None
                    return s
                s=self.wait(snapshot,f'{case} {phase} actual native {role} menu {player}')
                self.menu_rows.append({'case':case,'phase':phase,'role':role,'player':player,'native_slots':len(expected),'state':s});write(self.root/'component-menus.json',self.menu_rows)
                self.request(player,'close-menu')
    def connected(self):
        for player in PLAYERS:
            self.wait(lambda:bool(lines(self.root/'clients'/player/'events.jsonl')),'native D10 driver '+player,180)
            self.request(player,'connect',address='127.0.0.1:25661')
            self.wait(lambda:self.request(player,'snapshot').get('play'),'actual D10 multiplayer '+player,180)
    def phase(self,case,value):
        rows=self.rows(case);return rows and rows[-1]['phase']==value
    def restart(self,spec):
        case=spec['id'];box=self.root/'server/d10'/case;self.console(['d-component save']);self.wait(lambda:(box/'save-ready.json').exists(),'native participant save boundary '+case)
        disk=observe(self.root/'server/game',box/'participants.json',box/'prekill-saved');expected=[6,7,2] if spec['group']=='courier' else [5,1]
        require(disk['participants'][spec['group']]['vector']==expected,'actual native disk cut not witnessed')
        history=self.root/'boots'/case;history.mkdir(parents=True);boot={'case':case,'boundary':'AFTER_WITNESSED_NATIVE_PARTICIPANT_SAVE','disk_sha256':sha(box/'prekill-saved/distributions.json'),'original_owner_settlement':None,'save_proof':None,'before':{}}
        for kind in ('server',*(f'clients/{p}' for p in PLAYERS)):
            p=self.root/kind/'process.json';boot['before'][kind]=read(p);q=history/kind;q.mkdir(parents=True);shutil.copy2(p,q/'process-before.json')
        killed=self.compose('kill','-s','SIGKILL','minecraft');(history/'kill.log').write_text(killed.stdout+killed.stderr);(history/'containers-after-kill.jsonl').write_text(self.compose('ps','-a','--format','json').stdout)
        boot['kill_kind']='SIGKILL';self.compose('down','--remove-orphans');after=observe(self.root/'server/game',box/'participants.json',box/'killed-saved');require(disk==after,'native saved bytes changed across exact kill')
        for player in PLAYERS:
            for filename in ('request.json','response.json'):
                p=self.root/'clients'/player/filename
                if p.exists():p.rename(history/(player+'-'+filename))
        self.compose('up','-d','minecraft','client-a','client-b')
        for kind in boot['before']:
            self.wait(lambda:(p:=self.root/kind/'process.json').exists() and read(p)['start']>boot['before'][kind]['start'],'new actual native process '+kind,180)
        self.connected();self.position_players(spec)
        self.console(['d-component resume '+case]);self.wait(lambda:(box/'terminal.json').exists(),'fresh restored UNKNOWN and 100 ticks without replay '+case,120)
        boot['after']={kind:read(self.root/kind/'process.json') for kind in boot['before']};write(history/'boundary.json',boot)
        fresh=lines(box/'restart.jsonl')[-1];roles=fresh['participants'][spec['group']]['roles'];raw=self.rows(case)[-1];restored={'entities':raw['entities'],'inventories':{}}
        for role,value in roles.items():
            actual='carrier' if role=='sender' else role;p=read(box/'participants.json')['participants'][spec['group']][role]
            restored['inventories'][actual]={'inventory_known':True,'slots':[{'native_item_id':s['id'],'count':s['count']}for s in value['slots']],**({'pos':p['pos']} if 'entity' not in p else {})}
            if 'entity'in p:restored['entities'][actual]={**raw['entities'][actual],'pos':p['pos'],'loaded':True,'entity':p['entity']}
        self.menus(spec,'AFTER_RELOAD',restored)
    def position_players(self,spec):
        x,y,z=spec['origin'];self.tp('PlayerD_A',[x+14.5,y,z+7.5]);self.tp('PlayerD_B',[x+16.5,y,z+4.5])
    def run(self):
        self.deadline=time.monotonic()+read(HERE/'scenarios.json')['limits']['client_seconds'];self.snapshots=[];self.menu_rows=[];screens=[];self.connected();self.console(['gamemode creative PlayerD_A','gamemode creative PlayerD_B'])
        for spec in read(HERE/'scenarios.json')['cases']:
            case=spec['id'];n=int(case[-2:]);group=spec['group'];box=self.root/'server/d10'/case
            self.position_players(spec);self.console(['d-component prepare '+case]);self.wait(lambda:(box/'ready.json').exists(),'fresh native fixture '+case,180);self.position_players(spec);self.sample(case,'BEFORE');self.menus(spec,'BEFORE');self.position_players(spec)
            if case=='D10-STORAGE-01':
                x,y,z=spec['origin'];pos=[x+5,y,z+7];self.tp('PlayerD_A',[pos[0]+.5,y,pos[2]-2.5]);self.request('PlayerD_A','look',pos=pos)
            if case=='D10-MOBILITY-04':
                x,y,z=spec['origin'];target=[x+100000.5,y,z+100007.5];self.tp('PlayerD_A',target);self.console([f'fill {int(target[0])-2} {y-1} {int(target[2])-2} {int(target[0])+2} {y-1} {int(target[2])+2} stone']);self.wait(lambda:self.request('PlayerD_A','snapshot')['pos'][1]>=y-1,'actual distant player chunks',20)
            if case=='D10-STORAGE-09':
                self.request('PlayerD_B','command',text='d-component probe');self.wait(lambda:any(e['kind']=='feedback' and e['data']['text']=='D10_REPLY UNAVAILABLE'for e in lines(self.root/'clients/PlayerD_B/events.jsonl')),'fieldless private refusal')
            self.request('PlayerD_B' if case=='D10-STORAGE-09' else 'PlayerD_A','command',text='d-component execute')
            if group=='courier' and n in (7,8,9):
                self.wait(lambda:any(r['event']=='MOVE' and r['detail']['travelBlocks']>2 for r in self.rows(case)),'actual travelling custody before interference '+case,60);self.request('PlayerD_B','command',text='d-component interfere during-travel')
            if group=='courier' and n in (10,11) or group=='handoff' and n in (5,6,7,8):
                self.wait(lambda:self.rows(case)[-1]['phase'].startswith('AWAIT_'),'actual fresh interference window '+case);self.request('PlayerD_B','command',text='d-component interfere native')
            if group=='mobility':self.mobility(spec)
            if n==12 and group in ('courier','handoff'):
                self.wait(lambda:self.phase(case,'AWAIT_NATIVE_SAVE'),'actual live native save-cut window '+case);self.sample(case,'IN_FLIGHT');self.menus(spec,'IN_FLIGHT');self.restart(spec)
            else:self.wait(lambda:(box/'terminal.json').exists(),'native terminal '+case,150)
            self.position_players(spec);self.sample(case,'AFTER');
            if not(n==12 and group in ('courier','handoff')):self.menus(spec,'AFTER')
            if n in (1,5,12):
                self.position_players(spec)
                for player in PLAYERS:
                    feet=self.rows(case)[-1]['native_feet'];self.request(player,'look',pos=[int(feet[0]),int(feet[1])+1,int(feet[2])]);screens.append({'case':case,'player':player,'result':self.request(player,'screenshot',name=case.lower()+'-'+player)})
                write(self.root/'screenshots.json',screens)
            write(self.root/'campaign-progress.json',{'completed':[r['id']for r in read(HERE/'scenarios.json')['cases'][:spec['ordinal']]],'executed':spec['ordinal'],'planned':48})
        for player in PLAYERS:self.request(player,'disconnect');self.request(player,'exit')
    def mobility(self,spec):
        case=spec['id'];n=int(case[-2:]);box=self.root/'server/d10'/case
        if n==1:self.request('PlayerD_A','move',yaw=0,ticks=30);time.sleep(1.7);self.request('PlayerD_A','stop-move')
        if n in (6,7,8,9,10,11,12):
            def caught():
                rows=self.rows(case);moving=[r for r in rows if r['event']=='MOVE'];return moving and moving[-1]['detail']['connected'] and math.dist(moving[-1]['native_feet'],moving[-1]['detail']['native_player'])<=4 and any(r['facts'].get('path_nodes',0)>1 for r in moving)
            self.wait(caught,'actual feasible follow band '+case,90);self.sample(case,'FOLLOW_BAND')
            if n in (6,7):
                self.request('PlayerD_A','move',yaw=0,ticks=30);time.sleep(1.7);self.request('PlayerD_A','stop-move');self.wait(caught,'following actual ordinary moving-player input '+case,60);self.sample(case,'MOVED_FOLLOW_BAND');self.request('PlayerD_A','command',text='d-component stop')
            elif n in (8,12):self.request('PlayerD_A','command',text='d-component stop')
            elif n==9:
                self.request('PlayerD_A','disconnect');self.wait(lambda:(box/'terminal.json').exists(),'actual disconnect bounded pause',30);self.sample(case,'DISCONNECTED',('PlayerD_B',));self.request('PlayerD_A','connect',address='127.0.0.1:25661');self.wait(lambda:self.request('PlayerD_A','snapshot').get('play'),'same authenticated player reconnect')
            elif n==10:
                self.console(['execute in minecraft:the_nether run tp PlayerD_A 0 80 0']);self.wait(lambda:(box/'terminal.json').exists(),'actual dimension change guard',30);x,y,z=spec['origin'];self.console([f'execute in minecraft:overworld run tp PlayerD_A {x+14.5} {y} {z+7.5}'])
            elif n==11:
                self.request('PlayerD_A','disconnect');self.wait(lambda:any(r['event']=='MOVE'and r['detail']['observation'].get('pausedTicks',0)>=30 for r in self.rows(case)),'actual cumulative pause without renewed original deadline',10);self.request('PlayerD_A','connect',address='127.0.0.1:25661');self.wait(lambda:self.request('PlayerD_A','snapshot').get('play'),'native same-UUID resumed player');self.wait(lambda:(box/'time-expired.json').exists(),'original 1200 tick follow deadline',100);self.request('PlayerD_A','command',text='d-component travel-budget');self.request('PlayerD_A','move',yaw=0,ticks=60);self.wait(lambda:(box/'terminal.json').exists(),'explicit tighter original travel budget',60);self.request('PlayerD_A','stop-move')
