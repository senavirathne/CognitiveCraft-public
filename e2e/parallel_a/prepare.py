"""Package A instrumentation into the isolated baseline observer, never the release."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from runtime import ROOT, SDK, java, digest

out = ROOT / 'build/parallel-a/classes'
out.mkdir(parents=True, exist_ok=True)
release = ROOT / 'fabric/build/libs/ai-villages-0.1.0.jar'
before = digest(release)
cp = [release, SDK / 'minecraft-client.jar', *sorted((SDK / 'libraries').rglob('*.jar')),
      *sorted((ROOT / 'build/server-sdk/libs').glob('*.jar'))]
sources = sorted((ROOT / 'e2e/parallel_a/java').rglob('*.java'))
subprocess.run([java('javac'), '--release', '25', '-proc:none', '-encoding', 'UTF-8',
                '-cp', os.pathsep.join(map(str, cp)), '-d', str(out), *map(str, sources)], check=True)
observer = ROOT / 'build/e2e-driver/observer.jar'
shutil.copy2(observer, observer.with_name('observer-baseline.jar'))
with ZipFile(observer.with_name('observer-baseline.jar')) as source, ZipFile(observer, 'w') as target:
    for entry in source.infolist():
        data = source.read(entry.filename)
        if entry.filename == 'fabric.mod.json':
            manifest = json.loads(data)
            manifest['entrypoints']['main'].append('dev.aivillages.fabric.RuntimeAObserver')
            data = json.dumps(manifest).encode()
        target.writestr(entry, data)
    for path in out.rglob('*.class'):
        target.write(path, path.relative_to(out).as_posix())
with ZipFile(release) as packaged:
    assert not any('RuntimeAObserver' in n or 'JournalCostProbe' in n for n in packaged.namelist())
assert digest(release) == before
probe = ROOT / 'build/parallel-a/journal-probe'
subprocess.run([java('java'), '-cp', os.pathsep.join(map(str,[out,*cp])),
                'dev.aivillages.fabric.JournalCostProbe', str(probe)], check=True)
print(json.dumps({'releaseSha256': before, 'observerSha256': digest(observer), 'instrumentation': 'test-only'}))
