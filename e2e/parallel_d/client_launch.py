#!/usr/bin/env python3
"""Use established native rendering/driver, with an optional private bounded observation-root overlay."""
import os,sys,json,tempfile,hashlib
from pathlib import Path

def prepare_args(args):
    args=list(args);player=args[args.index('--player')+1]
    if player not in ('PlayerD_A','PlayerD_B'):raise ValueError('owned D client required')
    at=args.index('--root')+1;original=Path(args[at]).resolve();box=original/'clients'/player;config=box/'observation-area.json'
    if not config.exists():return args
    if config.stat().st_size>2048:raise ValueError('owned observation bounds size')
    value=json.loads(config.read_text());x,y,z,xx,zz=[value[k]for k in ('minX','footY','minZ','maxX','maxZ')]
    if any(type(n)is not int for n in (x,y,z,xx,zz))or xx<x or zz<z or (xx-x+1)*(zz-z+1)>512 or not -60<=y<=300:raise ValueError('owned observation bounds')
    overlay=Path(tempfile.mkdtemp(prefix='cc-d-'+player+'-'));(overlay/'clients').mkdir();(overlay/'clients'/player).symlink_to(box,target_is_directory=True);(overlay/'observation-area.json').write_bytes(config.read_bytes());args[at]=str(overlay)
    (box/'root-overlay.json').write_text(json.dumps({'root':str(overlay),'shared_box':str(box),'observation_sha256':hashlib.sha256(config.read_bytes()).hexdigest(),'driver_unchanged':True},indent=2)+'\n');return args

def main():
    args=prepare_args(sys.argv[1:]);os.environ['SDL_VIDEO_DRIVER']='offscreen';os.environ['LIBGL_ALWAYS_SOFTWARE']='1'
    os.execv(sys.executable,[sys.executable,'e2e/launch.py',*args])
if __name__=='__main__':main()
