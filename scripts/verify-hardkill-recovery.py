"""Kill only the owned GameTest JVM after its bounded pre-commit barrier, then verify disk state."""

import hashlib
import itertools
import json
import os
from pathlib import Path
import select
import signal
import subprocess
import time


ROOT = Path(__file__).resolve().parents[1]
RUN = ROOT / 'fabric/build/gametest-run'
EVIDENCE = ROOT / 'fabric/build/gametest-evidence'
READY = EVIDENCE / 'hardkill-ready.json'
VERIFIED = EVIDENCE / 'hardkill-verification.json'


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def snapshot(world):
    paths = list(itertools.islice((world / 'data').rglob('*'), 65))
    require(len(paths) <= 64, 'Store traversal exceeded 64 nodes')
    result, total = {}, 0
    for path in paths:
        require(not path.is_symlink(), 'Symbolic store path')
        if not path.is_file() or path.name.endswith('.lock') or '/staging/' in str(path):
            continue
        require(path.stat().st_size <= 65_536, 'Oversized store file')
        data = path.read_bytes()
        total += len(data)
        require(len(data) <= 65_536 and total <= 131_072 and len(result) < 16,
                'Store snapshot exceeded its finite envelope')
        result[str(path.relative_to(world))] = hashlib.sha256(data).hexdigest()
    require(bool(result), 'Missing authoritative files')
    return result


def descendant(pid, ancestor):
    for _ in range(16):
        if pid == ancestor:
            return True
        if pid <= 1:
            return False
        status = Path(f'/proc/{pid}/status').read_text()
        pid = int(next(line.split()[1] for line in status.splitlines() if line.startswith('PPid:')))
    return False


def main():
    require(hasattr(os, 'pidfd_open') and hasattr(signal, 'pidfd_send_signal'),
            'CI needs Linux process-descriptor signal support')
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    for path in (READY, VERIFIED, RUN / 'gametest-results.xml'):
        path.unlink(missing_ok=True)
    environment = os.environ.copy()
    environment.pop('COGNITIVECRAFT_BOOTSTRAP_RESTART', None)
    environment['COGNITIVECRAFT_BOOTSTRAP_CRASH'] = 'kill'
    environment['JAVA_TOOL_OPTIONS'] = (
        environment.get('JAVA_TOOL_OPTIONS', '')
        + ' -Dcognitivecraft.gametest.hardKillReady=' + str(READY))
    launcher = None
    handle = None
    try:
        with (EVIDENCE / 'hardkill-launcher.log').open('wb') as output:
            launcher = subprocess.Popen(
                ['bash', './gradlew', '--no-daemon', '--stacktrace', ':fabric:runGameTest'],
                cwd=ROOT, env=environment, stdout=output, stderr=subprocess.STDOUT,
                start_new_session=True)
            print('Waiting for the paused reconciliation GameTest JVM', flush=True)
            deadline = time.monotonic() + 240
            while not READY.exists():
                require(launcher.poll() is None, 'GameTest exited before the kill barrier')
                require(time.monotonic() < deadline, 'Timed out waiting for the kill barrier')
                time.sleep(0.05)
            require(not READY.is_symlink() and READY.stat().st_size <= 16_384,
                    'Invalid readiness evidence')
            ready = json.loads(READY.read_text())
            require(ready.get('schema') == 1
                    and ready.get('point') == 'before-interrupted-journal-replace',
                    'Unexpected kill boundary')
            for key, expected in (('chestWheat', 21), ('actorWheat', 7), ('matureCrops', 3),
                                  ('checkpointEffects', 0), ('reconstructedReceipts', 0),
                                  ('modelCalls', 0), ('quietTicks', 40)):
                require(ready.get(key) == expected, 'Invalid barrier field: ' + key)
            world = Path(ready['world']).resolve(strict=True)
            require(world.is_relative_to(RUN.resolve(strict=True))
                    and world.name == 'cognitivecraft-bootstrap-interruption-fixture',
                    'Evidence does not identify the disposable GameTest world')
            pid = ready['process']
            require(type(pid) is int and pid > 1 and pid != ready['coldProcess'],
                    'Invalid process identity')
            handle = os.pidfd_open(pid)
            require(descendant(pid, launcher.pid), 'JVM is not a descendant of this test launcher')
            require(Path(os.readlink(f'/proc/{pid}/exe')).name == 'java'
                    and Path(os.readlink(f'/proc/{pid}/cwd')).resolve() == RUN.resolve(),
                    'Target is not the JVM in the disposable GameTest directory')
            require(not select.select([handle], [], [], 0)[0], 'JVM exited before SIGKILL')
            require(snapshot(world) == ready['files'], 'Store changed after the readiness barrier')
            signal.pidfd_send_signal(handle, signal.SIGKILL)
            require(bool(select.select([handle], [], [], 10)[0]), 'SIGKILL termination was not observed')
            exit_code = launcher.wait(timeout=60)
            require(exit_code != 0, 'Killed GameTest unexpectedly reported success')
            require(snapshot(world) == ready['files'], 'SIGKILL changed authoritative records')
            result = {'schema': 1, 'signal': 'SIGKILL', 'process': pid,
                      'observedTermination': True, 'launcherExit': exit_code,
                      'sourceFilesUnchanged': True, 'files': ready['files']}
            temporary = VERIFIED.with_suffix('.tmp')
            temporary.write_text(json.dumps(result, sort_keys=True) + '\n')
            temporary.replace(VERIFIED)
            print('Verified SIGKILL before journal replacement; process=' + str(pid)
                  + ' launcherExit=' + str(exit_code)
                  + ' sourceFilesUnchanged=true files=' + str(len(ready['files'])), flush=True)
    finally:
        if handle is not None:
            os.close(handle)
        if launcher is not None and launcher.poll() is None:
            os.killpg(launcher.pid, signal.SIGTERM)
            try:
                launcher.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(launcher.pid, signal.SIGKILL)
                launcher.wait(timeout=10)


if __name__ == '__main__':
    main()
