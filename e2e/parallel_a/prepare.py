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
# Level.getBlockState can request a chunk and refresh its UNKNOWN ticket even
# after hasChunkAt succeeds. Keep A's unload observations off that loading path.
# Adapt the reviewed frozen observer under a distinct test-only class, without
# editing or shadowing B's shared source or touching the production release.
baseline_source = ROOT / 'e2e/observer/java/dev/aivillages/fabric/E2EObserver.java'
baseline_sha = digest(baseline_source)
assert baseline_sha == '2c69ab66892bbe7435d9d41db7ca0b30778b8b83d5c0db536b9a6a301a4672e8', baseline_sha
adapted = baseline_source.read_text()
replacements = {
    'public final class E2EObserver implements ModInitializer':
        'public final class RuntimeAArenaObserver implements ModInitializer',
    'if (!level.hasChunkAt(pos)) continue;':
        'var observedChunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);\n'
        '            if (observedChunk == null) continue;',
    'level.getBlockState(pos)': 'observedChunk.getBlockState(pos)',
    'level.getBlockState(pos.offset(0,dy,0))': 'observedChunk.getBlockState(pos.offset(0,dy,0))',
    'level.getBlockState(pos.below())': 'observedChunk.getBlockState(pos.below())',
    'level.getBlockEntity(pos)': 'observedChunk.getBlockEntities().get(pos)',
}
for original, replacement in replacements.items():
    assert adapted.count(original) == 1, original
    adapted = adapted.replace(original, replacement)
assert 'level.getBlockState(' not in adapted and 'level.getBlockEntity(' not in adapted
generated = ROOT / 'build/parallel-a/generated/RuntimeAArenaObserver.java'
generated.parent.mkdir(parents=True, exist_ok=True)
generated.write_text(adapted)
sources.append(generated)
# A second bounded arena supplies a real independently loaded worker while the
# interrupted actor's arena is absent. Both observers use only existing chunks.
secondary = adapted.replace('RuntimeAArenaObserver', 'RuntimeASecondaryObserver')
assert secondary.count('private final int[] observationArea = observationArea();') == 1
secondary = secondary.replace('private final int[] observationArea = observationArea();',
    'private final int[] observationArea = new int[]{319,208,63,340,78};')
for name in ('snapshot.tmp', 'snapshot.json', 'observations.jsonl'):
    assert secondary.count('server/' + name) == 1
    secondary = secondary.replace('server/' + name, 'server/secondary-' + name)
secondary_source = generated.with_name('RuntimeASecondaryObserver.java')
secondary_source.write_text(secondary)
sources.append(secondary_source)
subprocess.run([java('javac'), '--release', '25', '-proc:none', '-encoding', 'UTF-8',
                '-cp', os.pathsep.join(map(str, cp)), '-d', str(out), *map(str, sources)], check=True)
observer = ROOT / 'build/e2e-driver/observer.jar'
shutil.copy2(observer, observer.with_name('observer-baseline.jar'))
with ZipFile(observer.with_name('observer-baseline.jar')) as source, ZipFile(observer, 'w') as target:
    for entry in source.infolist():
        data = source.read(entry.filename)
        if entry.filename == 'fabric.mod.json':
            manifest = json.loads(data)
            assert manifest['entrypoints']['main'].count('dev.aivillages.fabric.E2EObserver') == 1
            manifest['entrypoints']['main'] = [
                'dev.aivillages.fabric.RuntimeAArenaObserver' if entry == 'dev.aivillages.fabric.E2EObserver' else entry
                for entry in manifest['entrypoints']['main']]
            manifest['entrypoints']['main'].append('dev.aivillages.fabric.RuntimeAObserver')
            manifest['entrypoints']['main'].append('dev.aivillages.fabric.RuntimeASecondaryObserver')
            data = json.dumps(manifest).encode()
        target.writestr(entry, data)
    for path in out.rglob('*.class'):
        target.write(path, path.relative_to(out).as_posix())
with ZipFile(release) as packaged:
    assert not any(any(name in n for name in ('RuntimeAObserver', 'RuntimeAArenaObserver', 'RuntimeASecondaryObserver', 'JournalCostProbe'))
                   for n in packaged.namelist())
assert digest(release) == before
(ROOT / 'build/parallel-a/observer-adaptation.json').write_text(json.dumps({
    'baselinePath': baseline_source.relative_to(ROOT).as_posix(), 'baselineSha256': baseline_sha,
    'baselineGitBlob': '3617591542864ee1390abf87da674539094bf901',
    'adaptedSourceSha256': digest(generated), 'releaseSha256': before,
    'secondarySourceSha256': digest(secondary_source), 'secondaryArea': [319,208,63,340,78],
    'observerSha256': digest(observer), 'claim': 'test-only existing chunk reads; unload gate still required',
    'replacements': replacements}, indent=2))
probe = ROOT / 'build/parallel-a/journal-probe'
subprocess.run([java('java'), '-cp', os.pathsep.join(map(str,[out,*cp])),
                'dev.aivillages.fabric.JournalCostProbe', str(probe)], check=True)
print(json.dumps({'releaseSha256': before, 'observerSha256': digest(observer), 'instrumentation': 'test-only'}))
