"""Verify the real A08 image dependencies before any server/client starts."""
import ctypes,glob,json,os,platform,shutil,subprocess
from pathlib import Path
PACKAGES=('python3','xvfb','x11-xkb-utils','libgl1-mesa-dri','libglx-mesa0','libegl1','libegl-mesa0',
          'libasound2t64','libxrandr2','libxcursor1','libxinerama1','libxi6','libxtst6','libxfont2','ca-certificates')
LIBRARIES=('libGL.so.1','libGLX_mesa.so.0','libEGL.so.1','libEGL_mesa.so.0','libasound.so.2',
           'libXrandr.so.2','libXcursor.so.1','libXinerama.so.1','libXi.so.6','libXtst.so.6','libXfont2.so.2')
def main():
    system=dict(line.split('=',1) for line in Path('/etc/os-release').read_text().splitlines() if '=' in line)
    assert system['ID']=='ubuntu' and system['VERSION_ID'].strip('"')=='24.04',system
    assert shutil.which('Xvfb') and shutil.which('xkbcomp') and shutil.which('python3')
    for name in LIBRARIES:ctypes.CDLL(name)
    drivers=glob.glob('/usr/lib/*/dri/swrast_dri.so');assert drivers
    java=subprocess.check_output(['java','-version'],stderr=subprocess.STDOUT,text=True,timeout=15)
    assert 'version "25' in java and Path(shutil.which('java')).resolve().is_relative_to(Path('/opt/java/openjdk'))
    packages=subprocess.check_output(['dpkg-query','--show',*PACKAGES],text=True,timeout=15)
    print(json.dumps({'status':'PASS_REAL_IMAGE_DEPENDENCIES_ONLY','ubuntu':system['VERSION_ID'].strip('"'),
          'python':platform.python_version(),'java':java,'libraries':list(LIBRARIES),'softwareDrivers':drivers,
          'packages':packages.splitlines(),'uid':os.getuid(),'gid':os.getgid(),'gameplayQualified':False}),flush=True)
if __name__=='__main__':main()
