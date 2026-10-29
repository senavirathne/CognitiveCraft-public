"""DVillagerHandoffClient: two actual native clients and native menus backed by eight villager slots."""
import math,time
from client_runner import DStorageObservationClient,PLAYERS,lines
from common import *

MODES=('EXISTING','STORAGE','DEATH','UNLOAD','RIVAL','MOVED','FULL')
class DVillagerHandoffClient(DStorageObservationClient):
    def run(self):
        self.deadline=time.monotonic()+1800
        for player in PLAYERS:
            self.wait(lambda:bool(lines(self.root/'clients'/player/'events.jsonl')),'D09 native driver '+player,180)
            self.request(player,'connect',address='127.0.0.1:25661')
            self.wait(lambda:any(e['kind']=='play'for e in lines(self.root/'clients'/player/'events.jsonl')),'D09 actual multiplayer '+player,180)
        menus=[];actors=[];screens=[]
        def healthy():
            error=self.root/'server/d09/error.json'
            if error.exists():raise RuntimeError(str(read(error)))
        def capture_menu(player,mode,phase,oak,slots=9):
            def current():
                healthy();value=self.request(player,'snapshot');native=[s for s in value.get('slots',[])if not s['playerInventory']]
                return value if value.get('play')and not value.get('singleplayer')and any(k in value.get('screen','')for k in ('Container','Chest'))and len(native)==slots and sum(s['count']for s in native if s['item']=='minecraft:oak_planks')==oak else None
            value=self.wait(current,'D09 real '+mode+' '+phase+' '+player,120)
            if mode=='FULL'and phase=='AFTER'and player=='PlayerD_B':require(sum(s['count']for s in value['slots']if not s['playerInventory']and s['item']=='minecraft:stone')==512,'actual native full recipient menu')
            menus.append({'mode':mode,'phase':phase,'player':player,'state':value});write(self.root/'handoff-menus.json',menus);return value
        def inspect(player,role):
            self.request(player,'close-menu');self.request(player,'command',text='d-handoff inspect '+role)
        def sample(mode,phase,ready,recipient_required=True):
            for player in PLAYERS:
                def synchronized():
                    healthy();value=self.request(player,'snapshot');entities={e['uuid']:e for e in value.get('entities',[])}
                    if not value.get('play')or ready['sender']not in entities:return None
                    if recipient_required and ready['recipient']not in entities:return None
                    if not recipient_required and ready['recipient']in entities:return None
                    if mode=='MOVED'and phase=='AFTER'and math.dist(entities[ready['sender']]['pos'],entities[ready['recipient']]['pos'])<=2.5:return None
                    if mode=='STORAGE'and phase=='AFTER'and (math.dist(entities[ready['sender']]['pos'],ready['sender_pos'])<10 or math.dist(entities[ready['sender']]['pos'],entities[ready['recipient']]['pos'])>3):return None
                    if mode=='EXISTING'and phase=='AFTER':
                        raw=lines(self.root/'server/d09/EXISTING/handoff.jsonl');renamed=next((r for r in raw if r['event']=='RENAMED_AND_NAMESAKE'),None)
                        if renamed is None or entities[ready['recipient']]['name']!='Renamed receiver' or renamed['namesake']['entity']not in entities or entities[renamed['namesake']['entity']]['name']!='Receiver':return None
                    return value
                value=self.wait(synchronized,'D09 actual entity packets '+mode+' '+phase+' '+player,120)
                actors.append({'mode':mode,'phase':phase,'player':player,'sender':ready['sender'],'recipient':ready['recipient'],'state':value});write(self.root/'handoff-clients.json',actors)
        def source_menu(ready):
            player='PlayerD_A';pos=ready['source_pos'];self.request(player,'close-menu');self.console([f'tp {player} {pos[0]+.5} 201 {pos[2]-1.5} 0 0'])
            self.wait(lambda:'minecraft:chest'in self.request(player,'snapshot').get('blocks',{}).get(','.join(map(str,pos)),''),'D09 actual source chest block packet',120)
            self.request(player,'look',pos=pos);self.request(player,'open',pos=pos)
        for mode in MODES:
            self.console(['d-handoff prepare '+mode]);self.wait(lambda:(self.root/'server/d09'/('ready-'+mode+'.json')).exists(),'D09 native scene '+mode,180);healthy();ready=read(self.root/'server/d09'/('ready-'+mode+'.json'))
            commands=[]
            for player,field in [('PlayerD_A','sender_pos'),('PlayerD_B','recipient_pos')]:
                pos=ready[field];commands.append(f'tp {player} {pos[0]} {pos[1]} {pos[2]-2} 0 0')
            self.console(commands)
            for player,field in [('PlayerD_A','sender_pos'),('PlayerD_B','recipient_pos')]:
                expected=ready[field][:];expected[2]-=2
                self.wait(lambda:math.dist(self.request(player,'snapshot')['pos'],expected)<.4,'D09 actual player packet synchronization '+player,120)
            if mode=='STORAGE':source_menu(ready);capture_menu('PlayerD_A',mode,'SOURCE_BEFORE',10,27)
            inspect('PlayerD_A','sender');capture_menu('PlayerD_A',mode,'BEFORE',3 if mode=='STORAGE'else 9)
            inspect('PlayerD_B','recipient');capture_menu('PlayerD_B',mode,'BEFORE',1);sample(mode,'BEFORE',ready)
            self.request('PlayerD_A','command',text='d-handoff start');self.wait(lambda:(self.root/'server/d09'/('accepted-'+mode+'.json')).exists(),'D09 actual authorized cargo admission');healthy()
            if mode=='STORAGE':
                source_menu(ready);capture_menu('PlayerD_A',mode,'SOURCE_WITHDRAWN',6,27);inspect('PlayerD_A','sender');capture_menu('PlayerD_A',mode,'WITHDRAWN',7);capture_menu('PlayerD_B',mode,'WITHDRAWN',1)
            sample(mode,'ADMITTED',ready);self.request('PlayerD_A','command',text='d-handoff execute')
            while not (self.root/'server/d09'/('terminal-'+mode+'.json')).exists():
                healthy();require(time.monotonic()<self.deadline,'D09 finite real-client campaign');time.sleep(.15)
            sample(mode,'AFTER',ready,mode not in ('DEATH','UNLOAD'))
            capture_menu('PlayerD_A',mode,'AFTER',3 if mode=='STORAGE'else 5 if mode=='EXISTING'else 9)
            capture_menu('PlayerD_B',mode,'AFTER',5 if mode in ('EXISTING','STORAGE')else 0 if mode=='FULL'else 1)
            if mode in ('EXISTING','STORAGE'):
                for player in PLAYERS:screens.append({'mode':mode,'player':player,'result':self.request(player,'screenshot',name='d09-'+mode.lower()+'-'+player)})
                write(self.root/'screenshots.json',screens)
            for player in PLAYERS:self.request(player,'close-menu')
        self.console(['d-handoff audit']);self.wait(lambda:(self.root/'server/d09/final-custody.json').exists(),'D09 actual final retained carrier audit');healthy()
        for player in PLAYERS:self.request(player,'disconnect');self.request(player,'exit')
