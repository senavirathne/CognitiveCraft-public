"""DChestCourierClient: native menus, actor movement and preserved custody in three distinct arenas."""
import time
from client_runner import DStorageObservationClient,PLAYERS,lines
from common import *
class DChestCourierClient(DStorageObservationClient):
    def run(self):
        for p in PLAYERS:
            self.wait(lambda:bool(lines(self.root/'clients'/p/'events.jsonl')),'D08 driver '+p,180);self.request(p,'connect',address='127.0.0.1:25661');self.wait(lambda:any(r['kind']=='play'for r in lines(self.root/'clients'/p/'events.jsonl')),'D08 multiplayer '+p,180)
        menus=[];actors=[];screens=[]
        def healthy():
            p=self.root/'server/d08/error.json'
            if p.exists():raise RuntimeError(str(read(p)))
        def menu(player,pos,quantity,mode,phase):
            def observe():
                healthy();s=self.request(player,'snapshot');slots=[r for r in s.get('slots',[])if not r['playerInventory']]
                return s if s.get('play')and not s.get('singleplayer')and any(w in s.get('screen','')for w in ('Container','Chest'))and len(slots)==27 and sum(r['count']for r in slots if r['item']=='minecraft:oak_planks')==quantity else None
            value=self.wait(observe,'D08 actual '+mode+' '+phase+' '+player,120);menus.append({'mode':mode,'phase':phase,'player':player,'position':pos,'state':value});write(self.root/'courier-menus.json',menus)
        def open_menu(player,pos):
            self.request(player,'close-menu');self.console(['tp '+player+f' {pos[0]+.5} 201 {pos[2]-1.5} 0 0'])
            self.wait(lambda:((s:=self.request(player,'snapshot')).get('play')and sum((s['pos'][i]-[pos[0]+.5,201,pos[2]-1.5][i])**2 for i in range(3))<.1),'D08 real menu position')
            self.wait(lambda:'minecraft:chest'in self.request(player,'snapshot').get('blocks',{}).get(','.join(map(str,pos)),''),'D08 actual client chest chunk synchronization',120)
            self.request(player,'look',pos=pos);self.request(player,'open',pos=pos)
        def sample(mode,phase,actor):
            for p in PLAYERS:
                s=self.request(p,'snapshot');require(s.get('play')and any(e['uuid']==actor for e in s['entities']),'D08 actual native actor missing')
                actors.append({'mode':mode,'phase':phase,'player':p,'state':s,'actor':actor});write(self.root/'courier-clients.json',actors)
        for mode in ('SUCCESS','REVOKED','REPLACED'):
            self.console(['d-courier prepare '+mode]);self.wait(lambda:(self.root/'server/d08'/('ready-'+mode+'.json')).exists(),'D08 native scene '+mode,180);ready=read(self.root/'server/d08'/('ready-'+mode+'.json'));src=ready['source_pos'];dst=ready['destination_pos']
            open_menu('PlayerD_A',src);menu('PlayerD_A',src,10,mode,'BEFORE');open_menu('PlayerD_B',dst);menu('PlayerD_B',dst,2,mode,'BEFORE')
            for p in PLAYERS:self.wait(lambda:any(e['uuid']==ready['actor']for e in self.request(p,'snapshot')['entities']),'D08 entity packet '+p)
            sample(mode,'BEFORE',ready['actor']);self.request('PlayerD_A','command',text='d-courier run')
            self.wait(lambda:(self.root/'server/d08'/('withdrawn-'+mode+'.json')).exists(),'D08 actual withdrawal');open_menu('PlayerD_A',src);menu('PlayerD_A',src,6,mode,'WITHDRAWN')
            while not (self.root/'server/d08'/('terminal-'+mode+'.json')).exists():
                healthy();sample(mode,'MOVING',ready['actor']);require(time.monotonic()<self.deadline,'D08 finite client courier');time.sleep(.1)
            sample(mode,'TERMINAL',ready['actor']);menu('PlayerD_A',src,6,mode,'AFTER')
            if mode=='REPLACED':open_menu('PlayerD_B',dst)
            menu('PlayerD_B',dst,6 if mode=='SUCCESS'else 2 if mode=='REVOKED'else 0,mode,'AFTER')
            if mode=='SUCCESS':
                for p in PLAYERS:screens.append({'player':p,'result':self.request(p,'screenshot',name='d08-courier-'+p)})
                write(self.root/'screenshots.json',screens)
            for p in PLAYERS:self.request(p,'close-menu')
        # Re-observe both blocked actors at the end: their exact attributed four units remain in native carrier slots.
        self.console(['d-courier audit']);self.wait(lambda:(self.root/'server/d08/final-custody.json').exists(),'D08 final retained custody')
        for p in PLAYERS:self.request(p,'disconnect');self.request(p,'exit')
