"""Require identical instrumentation content; record the JDK's build-only manifest."""
import hashlib,re
from pathlib import Path
from zipfile import ZipFile

MANIFEST='META-INF/MANIFEST.MF'
BUILD_MANIFEST=re.compile(rb'Manifest-Version: 1\.0\r\nCreated-By: (25\.[0-9.]+ \(Eclipse Adoptium\))\r\n\r\n')

def same_jar_entries(before,after):
    with ZipFile(before) as old,ZipFile(after) as current:
        names=[n for n in old.namelist() if not n.endswith('/')]
        actual=[n for n in current.namelist() if not n.endswith('/')]
        assert len(names)==len(set(names)) and len(actual)==len(set(actual)), 'Duplicate instrumentation entries'
        assert set(names)==set(actual), 'Retained performance requires identical instrumentation entries'
        changed=[n for n in names if old.read(n)!=current.read(n)]
        assert all(n==MANIFEST for n in changed), 'Retained performance requires identical class and resource bytes'
        manifests=[BUILD_MANIFEST.fullmatch(z.read(MANIFEST)) for z in (old,current)]
        assert all(manifests), 'Only the exact version-only build manifest is allowed; execution attributes are forbidden'
        return {'status':'PASS','entries':len(names),'identicalClassAndResourceEntries':len(names)-1,
                'differentBuildManifest':bool(changed),'beforeCreatedBy':manifests[0][1].decode('ascii'),
                'afterCreatedBy':manifests[1][1].decode('ascii'),
                'beforeJarSha256':hashlib.sha256(Path(before).read_bytes()).hexdigest(),
                'afterJarSha256':hashlib.sha256(Path(after).read_bytes()).hexdigest()}
