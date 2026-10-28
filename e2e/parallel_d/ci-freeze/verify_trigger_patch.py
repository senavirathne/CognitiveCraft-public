#!/usr/bin/env python3
"""Verify exact architect-frozen CI trigger bytes; no network or repository writes."""
import argparse,hashlib,json
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,required=True);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--state',choices=['before','after'],required=True);a=p.parse_args()
m=json.loads(a.manifest.read_text())
for f in m['files']:
    b=(a.repo/f['path']).read_bytes()
    assert hashlib.sha256(b).hexdigest()==f[a.state+'_sha256'],'Unexpected bytes: '+f['path']
print(json.dumps({'status':'PASS','scope':'EXACT_CI_TRIGGER_BYTES_ONLY','files':len(m['files']),'state':a.state}))
