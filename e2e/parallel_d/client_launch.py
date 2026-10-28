#!/usr/bin/env python3
"""Use the established SDL offscreen renderer and unchanged genuine Minecraft client launcher."""
import os,sys
player=sys.argv[sys.argv.index('--player')+1]
if player not in ('PlayerD_A','PlayerD_B'):raise ValueError('owned D client required')
os.environ['SDL_VIDEO_DRIVER']='offscreen'
os.environ['LIBGL_ALWAYS_SOFTWARE']='1'
os.execv(sys.executable,[sys.executable,'e2e/launch.py',*sys.argv[1:]])
