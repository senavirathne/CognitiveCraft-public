"""Explicit Linux x86_64 setup. Runtime never invokes this downloader."""
import hashlib
import json
from pathlib import Path
import sys
import urllib.request

REVISION = 'c7c415a3d1b3d929014bc6e866d51ebb971f7089'
BASE = f'https://huggingface.co/Cactus-Compute/needle3/resolve/{REVISION}/'
DEST = Path(sys.argv[1]).resolve()
DEST.mkdir(parents=True, exist_ok=True)
FILES = {
    'needle3.cact': ('needle3.cact', 40_000_000,
                     'c9d915eca282ed42d1a09b143b592adb4cc6744ffe2d294adf5cfc5548170c38'),
    'linux-x86_64/needle': ('needle', 2_000_000, 'b197ceaef3b300a0b14c3a4fde92305527e43f9256c53d2a53d2a2fe8fe69678'),
    'LICENSE': ('LICENSE', 20_000, 'cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30'),
}
manifest = {'repository': 'Cactus-Compute/needle3', 'revision': REVISION, 'files': []}
for remote, (local, limit, digest) in FILES.items():
    with urllib.request.urlopen(BASE + remote, timeout=90) as response:
        data = response.read(limit + 1)
    if len(data) > limit:
        raise SystemExit('Asset exceeds size limit: ' + remote)
    actual = hashlib.sha256(data).hexdigest()
    if digest and actual != digest:
        raise SystemExit('Asset digest mismatch: ' + remote)
    path = DEST / local
    path.write_bytes(data)
    if local == 'needle': path.chmod(0o755)
    manifest['files'].append({'path': local, 'bytes': len(data), 'sha256': actual})
(DEST / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps(manifest))
