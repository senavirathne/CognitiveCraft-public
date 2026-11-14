"""Read-only export of pinned A08 artifacts; never gameplay qualification."""
import argparse, hashlib, io, json, subprocess, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

REPOSITORY = 'senavirathne/CognitiveCraft-public'

def control(path):
    value = json.loads(Path(path).read_text())
    assert value['schema'] == 1 and value['repository'] == REPOSITORY
    rows = value['artifacts']
    assert 1 <= len(rows) <= 4 and len({r['id'] for r in rows}) == len(rows)
    for row in rows:
        assert isinstance(row['id'], int) and row['id'] > 0
        assert 0 < row['bytes'] <= 512 * 1024**2
        assert len(row['sha256']) == 64 and all(c in '0123456789abcdef' for c in row['sha256'])
        if row.get('nativeArchive'):
            assert 0 < row['archiveBytes'] <= 512 * 1024**2
            assert len(row['archiveSha256']) == 64 and all(c in '0123456789abcdef' for c in row['archiveSha256'])
    return rows

def fetch(rows, directory):
    directory.mkdir(parents=True, exist_ok=True)
    for row in rows:
        with (directory / (str(row['id']) + '.zip')).open('wb') as out:
            subprocess.run(['gh', 'api', 'repos/' + REPOSITORY + '/actions/artifacts/' + str(row['id']) + '/zip'],
                           stdout=out, check=True, timeout=180)

def names(zipped, cap):
    values = zipped.namelist()
    assert len(values) == len(set(values)) and len(values) < 20000
    assert sum(n.file_size for n in zipped.infolist()) < cap
    assert all(not Path(n).is_absolute() and '..' not in Path(n).parts for n in values)
    assert not any((n.external_attr >> 16) & 0o170000 == 0o120000 for n in zipped.infolist())
    return values

def export(zipped, row, prefix=''):
    modules = {}
    for name in names(zipped, 4 * 1024**3):
        if name.endswith('.xml') and '/build/test-results/test/' in name:
            cases = list(ET.fromstring(zipped.read(name)).iter('testcase'))
            assert not any(c.find(k) is not None for c in cases for k in ('failure', 'error', 'skipped'))
            module = name.split('/build/test-results/test/')[0].rsplit('/', 1)[-1]
            modules[module] = modules.get(module, 0) + len(cases)
        if name.endswith('fabric/build/libs/ai-villages-0.1.0.jar'):
            print('A08_ARTIFACT_MOD ' + json.dumps({'artifact': row['id'], 'path': prefix + name,
                  'sha256': hashlib.sha256(zipped.read(name)).hexdigest()}), flush=True)
        if Path(name).name == 'docker.log':
            assert zipped.getinfo(name).file_size <= 32 * 1024**2
            lines = zipped.read(name).decode('utf-8', errors='replace').splitlines()
            print('A08_ARTIFACT_DOCKER_LOG ' + json.dumps({'artifact': row['id'], 'path': prefix + name,
                  'totalLines': len(lines), 'tail': lines[-300:]}), flush=True)
        if Path(name).name not in ('a08-results.json', 'metadata.json', 'a08-independent-review.json',
                                   'parts-manifest.json', 'a08-modules.json'):
            continue
        assert zipped.getinfo(name).file_size < 8 * 1024**2
        value = json.loads(zipped.read(name))
        if name.endswith('a08-results.json'):
            source = value
            value = {k: v for k, v in source.items() if k != 'cases'}
            value['cases'] = [{k: v for k, v in case.items() if k in ('id', 'status', 'reason', 'trace', 'ownerPollTicks')}
                              for case in source['cases']]
        print('A08_ARTIFACT_JSON ' + json.dumps({'artifact': row['id'], 'path': prefix + name, 'value': value}), flush=True)
    if modules:
        assert modules == {'core': 2400, 'providers': 131, 'fabric': 130}, modules
        print('A08_ARTIFACT_MODULE_XML ' + json.dumps({'artifact': row['id'], 'modules': modules,
              'total': sum(modules.values()), 'failures': 0, 'errors': 0, 'skipped': 0}), flush=True)

def read(rows, directory):
    for row in rows:
        path = directory / (str(row['id']) + '.zip')
        with path.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        assert path.stat().st_size == row['bytes'] and digest == row['sha256']
        with zipfile.ZipFile(path) as zipped:
            entries = names(zipped, 1024**3)
            assert zipped.testzip() is None
            print('A08_ARTIFACT_VERIFIED ' + json.dumps({'id': row['id'], 'bytes': row['bytes'], 'sha256': digest}), flush=True)
            if row.get('nativeArchive'):
                manifests = [n for n in entries if n.endswith('parts-manifest.json')]
                assert len(manifests) == 1
                manifest = json.loads(zipped.read(manifests[0]))
                assert len(manifest['parts']) == 1 and manifest['archiveBytes'] == row['archiveBytes']
                assert manifest['archiveSha256'] == row['archiveSha256']
                part = manifest['parts'][0]
                candidates = [n for n in entries if Path(n).name == part['name']]
                assert len(candidates) == 1
                body = zipped.read(candidates[0])
                assert len(body) == row['archiveBytes'] == part['bytes']
                assert hashlib.sha256(body).hexdigest() == row['archiveSha256'] == part['sha256']
                print('A08_NATIVE_ARCHIVE_VERIFIED ' + json.dumps({'artifact': row['id'], **manifest}), flush=True)
                with zipfile.ZipFile(io.BytesIO(body)) as native:
                    export(native, row, 'native/')
            else:
                export(zipped, row)
    print('A08_ARTIFACT_EXPORT_COMPLETE qualification=false', flush=True)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('fetch', 'read'))
    parser.add_argument('--control', type=Path, required=True)
    parser.add_argument('--directory', type=Path, required=True)
    args = parser.parse_args()
    rows = control(args.control)
    (fetch if args.operation == 'fetch' else read)(rows, args.directory)

if __name__ == '__main__':
    main()
