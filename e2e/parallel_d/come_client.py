"""DComeClient: actual connected clients, ordinary player input and synchronized native actor positions."""
import time
from client_runner import DStorageObservationClient,PLAYERS,lines
from common import *

class DComeClient(DStorageObservationClient):
    def run(self):
        for player in PLAYERS:
            self.wait(lambda:bool(lines(self.root/'clients'/player/'events.jsonl')),'D06 real driver startup',180)
            self.request(player,'connect',address='127.0.0.1:25661')
            self.wait(lambda:any(r['kind']=='play'for r in lines(self.root/'clients'/player/'events.jsonl')),'D06 authenticated multiplayer '+player,180)
        self.console(['d-come prepare','tp PlayerD_A 14.5 201 7.5 0 0','tp PlayerD_B 16.5 201 4.5 0 0'])
        self.wait(lambda:(self.root/'server/d06/ready.json').exists(),'actual villager scene')
        self.wait(lambda:((s:=self.request('PlayerD_A','snapshot')).get('play')and sum((s['pos'][i]-[14.5,201,7.5][i])**2 for i in range(3))<.1),'actual captured-player position')
        ready=read(self.root/'server/d06/ready.json');frames=[]
        def sample(phase):
            for player in PLAYERS:
                s=self.request(player,'snapshot');require(s.get('play')and not s.get('singleplayer'),'actual multiplayer disconnected')
                require(any(e['uuid']==ready['actor']for e in s['entities']),'native actor not synchronized')
                frames.append({'phase':phase,'player':player,'state':s});write(self.root/'movement-clients.json',frames)
        sample('BEFORE')
        self.request('PlayerD_A','command',text='d-come come')
        self.wait(lambda:(self.root/'server/d06/capture.json').is_file(),'server accepted captured position')
        self.request('PlayerD_A','move',yaw=0,ticks=40)
        while True:
            sample('MOVING')
            if (self.root/'server/d06/terminal.json').exists():break
            require(time.monotonic()<self.deadline,'finite real-client movement campaign');time.sleep(.15)
        self.request('PlayerD_A','stop-move');sample('TERMINAL')
        screenshots=[]
        for player in PLAYERS:
            s=self.request(player,'snapshot');e=next(e for e in s['entities']if e['uuid']==ready['actor']);self.request(player,'look',pos=[int(e['pos'][0]),int(e['pos'][1])+1,int(e['pos'][2])]);screenshots.append({'player':player,'result':self.request(player,'screenshot',name='d06-captured-arrival')})
        write(self.root/'screenshots.json',screenshots)
        for player in PLAYERS:self.request(player,'disconnect');self.request(player,'exit')
