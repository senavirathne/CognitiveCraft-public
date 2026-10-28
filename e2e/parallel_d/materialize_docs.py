#!/usr/bin/env python3
"""Reconstruct an exact sparse docs checkout from pinned Git objects; no credentials."""
import argparse, shutil, subprocess
from common import *

def materialize(root):
    root=Path(root).resolve();require(not root.exists(),'docs destination already exists')
    root.mkdir(parents=True);snapshot=read(HERE/'docs-snapshot.json')
    require(snapshot['base_sha']==DOCS_BASE,'wrong docs baseline')
    def command(*args,data=None):
        return subprocess.check_output(['git','-C',str(root),*args],input=data).decode().strip()
    command('init','-q')
    for expected,raw in snapshot['trees'].items():
        require(command('hash-object','-w','-t','tree','--stdin',data=bytes.fromhex(raw))==expected,'docs tree bytes changed')
    for pin,raw in snapshot.get('commits',{}).items():
        require(command('hash-object','-w','-t','commit','--stdin',data=raw.encode())==pin,'docs ancestor commit bytes changed')
    for key,pin in [('base_commit',DOCS_BASE),('candidate_commit',snapshot['candidate_sha'])]:
        require(command('hash-object','-w','-t','commit','--stdin',data=snapshot[key].encode())==pin,'docs commit bytes changed')
    (root/'.git/shallow').write_text(DOCS_BASE+'\n')
    command('update-ref','refs/heads/docs/storage-mobility-logistics',snapshot['candidate_sha'])
    command('symbolic-ref','HEAD','refs/heads/docs/storage-mobility-logistics')
    for path,content in snapshot['owned_files'].items():
        require(path.startswith('docs/implementation/parallel-d/') and '..' not in Path(path).parts,'unowned docs snapshot')
        p=root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
    target=root/'docs/implementation/parallel-d/contracts';shutil.copytree(HERE/'contracts',target,ignore=shutil.ignore_patterns('__pycache__','*.pyc'))
    # Only supplied blobs are materialized; all protected blob identities remain exact.
    for p in (root/'docs/implementation/parallel-d').rglob('*'):
        if p.is_file():
            blob=command('hash-object','-w','--stdin',data=p.read_bytes())
            expected=command('ls-tree','HEAD',str(p.relative_to(root))).split()[2]
            require(blob==expected,'docs owned blob mismatch: '+str(p))
    command('read-tree','HEAD')
    protected=[row.split('\t')[1] for row in command('ls-tree','-r','HEAD').splitlines() if not (root/row.split('\t')[1]).is_file()]
    command('update-index','--skip-worktree','--stdin',data=('\n'.join(protected)+'\n').encode())
    require(not command('status','--porcelain'),'docs snapshot working bytes mismatch')
    command('merge-base','--is-ancestor',DOCS_BASE,'HEAD')
    print(json.dumps({'docs_sha':snapshot['candidate_sha'],'scope':'EXACT_SPARSE_GIT_OBJECTS'},sort_keys=True))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);materialize(p.parse_args().root)
