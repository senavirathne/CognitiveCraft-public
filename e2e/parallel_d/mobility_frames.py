"""Lossless collection of native-emitted JSON frames. This makes no physical success decision."""
import json
from common import require,no_duplicates

FILES={'COME_TO_PLAYER':'movement.jsonl','GO_NAMED_STORAGE':'storage-movement.jsonl'}
def emitted(log):
    rows=[]
    for line in log.read_text().splitlines():
        if 'CC_D06_FRAME 'in line:
            payload=line.split('CC_D06_FRAME ',1)[1];row=json.loads(payload,object_pairs_hook=no_duplicates)
            require(row.get('mode')in FILES,'unknown raw movement mode');rows.append((payload,row))
    return rows
def collect(log,root):
    rows=emitted(log)
    if not rows:return 0
    root.mkdir(parents=True,exist_ok=True)
    for mode,file in FILES.items():
        selected=[text for text,r in rows if r['mode']==mode]
        if selected:(root/file).write_text(''.join(t+'\n'for t in selected))
    return len(rows)
def reconcile(log,root):
    rows=emitted(log);require(rows,'native JVM emitted no raw movement frames')
    for mode,file in FILES.items():
        expected=[r for text,r in rows if r['mode']==mode]
        if expected:require([json.loads(s,object_pairs_hook=no_duplicates)for s in (root/file).read_text().splitlines()]==expected,'raw frames differ from native JVM stdout')
    return len(rows)
