#!/usr/bin/env python3
"""Package read-only B guard diagnostics in the isolated observer, never production."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from zipfile import ZipFile, ZIP_DEFLATED

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from runtime import ROOT, SDK, java, digest

def main():
    release=ROOT/'fabric/build/libs/ai-villages-0.1.0.jar'; before=digest(release)
    observer=ROOT/'build/e2e-driver/observer.jar'; out=ROOT/'build/b-diagnostics';out.mkdir(parents=True,exist_ok=True)
    sources=sorted((ROOT/'e2e/parallel_b/observer/java').rglob('*.java'))
    libs=[SDK/'minecraft-client.jar',release,*sorted((SDK/'libraries').rglob('*.jar')),*sorted((ROOT/'build/server-sdk/libs').glob('*.jar'))]
    subprocess.run([java('javac'),'--release','25','-proc:none','-cp',os.pathsep.join(map(str,libs)),'-d',str(out),*map(str,sources)],check=True)
    replacement={str(p.relative_to(out)) for p in out.rglob('*.class')}; config='parallel-b-diagnostics.mixins.json'; temporary=observer.with_suffix('.b.jar')
    with ZipFile(observer) as old,ZipFile(temporary,'w',ZIP_DEFLATED) as new:
        for name in old.namelist():
            if name in replacement or name==config:continue
            data=old.read(name)
            if name=='fabric.mod.json':
                m=json.loads(data);m['mixins']=list(dict.fromkeys([*m.get('mixins',[]),config]));m['entrypoints']['main']=list(dict.fromkeys([*m['entrypoints']['main'],'dev.aivillages.fabric.BReadOnlyProbe']));data=json.dumps(m).encode()
            new.writestr(name,data)
        for path in sorted(out.rglob('*.class')):new.write(path,str(path.relative_to(out)))
        new.writestr(config,json.dumps({'required':True,'package':'dev.aivillages.fabric','compatibilityLevel':'JAVA_25','mixins':['BRecipeDiagnosticsMixin'],'injectors':{'defaultRequire':1}}))
    temporary.replace(observer)
    assert digest(release)==before
    with ZipFile(release) as z:assert not any('BRecipeDiagnosticsMixin' in n for n in z.namelist())
    integrity={'productionSha256':before,'observerSha256':digest(observer),'sources':{str(p.relative_to(ROOT)):digest(p) for p in sources},'readOnly':True,'productionUnchanged':True}
    (out/'integrity.json').write_text(json.dumps(integrity,indent=2)+'\n');print(json.dumps(integrity))

if __name__=='__main__':main()
