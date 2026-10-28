#!/usr/bin/env python3
"""D-only display wrapper; launch the unchanged established real Minecraft client driver."""
import os,signal,subprocess,sys,json,time
from pathlib import Path
player=sys.argv[sys.argv.index('--player')+1];root=Path(sys.argv[sys.argv.index('--root')+1]);assert player in ('PlayerD_A','PlayerD_B')
number='95'if player=='PlayerD_A'else'96';display=subprocess.Popen(['Xvfb',':'+number,'-ac','-screen','0','800x600x24','-nolisten','unix','-listen','tcp','+extension','GLX','+render','-noreset'])
os.environ['DISPLAY']='127.0.0.1:'+number;os.environ['SDL_VIDEO_DRIVER']='x11';box=root/'clients'/player;box.mkdir(parents=True,exist_ok=True);(box/'display.json').write_text(json.dumps({'display':os.environ['DISPLAY'],'xvfb_pid':display.pid,'player':player})+'\n')
process=None
try:
    time.sleep(.5)
    if display.poll()is not None:raise RuntimeError('D display failed')
    process=subprocess.Popen([sys.executable,'e2e/launch.py',*sys.argv[1:]])
    def stop(signum,frame):
        if process.poll()is None:process.send_signal(signum)
    signal.signal(signal.SIGTERM,stop);signal.signal(signal.SIGINT,stop);raise SystemExit(process.wait())
finally:
    if process is not None and process.poll()is None:process.terminate();process.wait(timeout=40)
    display.terminate();display.wait(timeout=10)
