"""Real connected-client D07 campaign with ordinary player input, STOP and disconnect."""
import time
from client_runner import DStorageObservationClient,PLAYERS,lines
from common import *
class DFollowClient(DStorageObservationClient):
    def run(self):
        for p in PLAYERS:
            self.wait(lambda:bool(lines(self.root/'clients'/p/'events.jsonl')),'D07 driver '+p,180)
            self.request(p,'connect',address='127.0.0.1:25661')
            self.wait(lambda:any(r['kind']=='play'for r in lines(self.root/'clients'/p/'events.jsonl')),'D07 multiplayer '+p,180)
        self.console(['d-follow prepare','tp PlayerD_A 14.5 201 7.5 0 0','tp PlayerD_B 17.5 201 4.5 0 0'])
        self.wait(lambda:(self.root/'server/d07/ready.json').exists(),'D07 native arena')
        ready=read(self.root/'server/d07/ready.json');frames=[]
        def sample(phase,players=PLAYERS):
            for p in players:
                s=self.request(p,'snapshot');require(s.get('play')and not s.get('singleplayer'),'D07 actual multiplayer')
                require(any(e['uuid']==ready['actor']for e in s['entities']),'D07 synchronized villager missing')
                frames.append({'phase':phase,'player':p,'state':s});write(self.root/'follow-clients.json',frames)
        sample('BEFORE');self.request('PlayerD_A','command',text='d-follow start')
        self.wait(lambda:(self.root/'server/d07/accepted-1.json').exists(),'D07 actual start')
        # Wait for traversal before ordinary walking; every position is sampled from real client state.
        def caught():
            sample('FOLLOWING');s=frames[-2]['state'];a=next(e for e in s['entities']if e['uuid']==ready['actor']);return a['pos'][0]>10 and sum((a['pos'][i]-s['pos'][i])**2 for i in range(3))<=17
        self.wait(caught,'D07 obstacle traversal',80)
        self.request('PlayerD_A','move',yaw=0,ticks=65)
        for _ in range(12):sample('PLAYER_WALKING');time.sleep(.2)
        self.request('PlayerD_A','stop-move')
        self.wait(caught,'D07 follows moving target',60);sample('BEFORE_STOP')
        self.request('PlayerD_A','command',text='d-follow stop')
        self.wait(lambda:(self.root/'server/d07/stopped.json').exists(),'D07 STOP event');sample('STOPPED')
        shots=[]
        for p in PLAYERS:
            s=self.request(p,'snapshot');a=next(e for e in s['entities']if e['uuid']==ready['actor']);self.request(p,'look',pos=[int(a['pos'][0]),int(a['pos'][1])+1,int(a['pos'][2])]);shots.append({'player':p,'result':self.request(p,'screenshot',name='d07-follow-stopped')})
        write(self.root/'screenshots.json',shots)
        self.request('PlayerD_A','command',text='d-follow start');self.wait(lambda:(self.root/'server/d07/accepted-2.json').exists(),'D07 explicit new session')
        self.request('PlayerD_A','move',yaw=180,ticks=35);time.sleep(.8);sample('BEFORE_DISCONNECT')
        self.request('PlayerD_A','disconnect')
        while not (self.root/'server/d07/terminal-2.json').exists():
            sample('DISCONNECTED',('PlayerD_B',));require(time.monotonic()<self.deadline,'D07 finite disconnect');time.sleep(.2)
        sample('PAUSE_TERMINAL',('PlayerD_B',))
        for p in PLAYERS:
            if p=='PlayerD_B':self.request(p,'disconnect')
            self.request(p,'exit')
