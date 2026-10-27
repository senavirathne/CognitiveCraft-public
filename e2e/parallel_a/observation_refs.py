"""Reconstruct exact original A04 observer UTF-8 frames; references never stand in for physical evidence."""
import hashlib
import json
from pathlib import Path
import re

TOKEN=re.compile(rb'\{"\$a04Raw":"([0-9a-f]{64})"\}')
MAX_BODY=4*1024*1024
MAX_FRAME=64*1024*1024

def restore(wrapper,body_root):
    if wrapper.get('schema')!=1 or not re.fullmatch('[0-9a-f]{64}',wrapper.get('originalSha256','')):
        raise ValueError('Unknown reference log schema/hash')
    raw=wrapper['template'].encode('utf-8')
    if len(raw)>MAX_FRAME:raise ValueError('Frame byte quota')
    def body(match):
        digest=match[1].decode('ascii');path=Path(body_root)/(digest+'.json')
        if path.is_symlink() or not path.is_file() or path.stat().st_size>MAX_BODY:raise ValueError('Missing/bounded immutable observation body')
        data=path.read_bytes()
        if hashlib.sha256(data).hexdigest()!=digest:raise ValueError('Observation body checksum')
        return data
    raw=TOKEN.sub(body,raw)
    if len(raw)>MAX_FRAME or hashlib.sha256(raw).hexdigest()!=wrapper['originalSha256']:raise ValueError('Exact original frame checksum')
    frame=json.loads(raw)
    if frame['time']!=wrapper['time'] or frame['pid']!=wrapper['pid']:raise ValueError('Original frame attribution')
    return raw

def frames(server,stream='observations',*,tails=None):
    root=Path(server)
    for path in sorted(root.glob(stream+'.refs-*.jsonl')):
        with path.open('rb') as source:
            for number,line in enumerate(source,1):
                if len(line)>MAX_FRAME:raise ValueError('Reference line quota')
                if not line.endswith(b'\n'):
                    if tails is None:raise ValueError('Incomplete retained log tail')
                    tails.append({'path':path.name,'line':number,'bytes':len(line),'sha256':hashlib.sha256(line).hexdigest()})
                    break
                yield json.loads(restore(json.loads(line),root/'observation-bodies'))
