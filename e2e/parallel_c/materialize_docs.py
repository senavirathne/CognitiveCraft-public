#!/usr/bin/env python3
"""Restore exact sparse documentation Git objects, following the established D workflow."""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess

from common import DOCS_BASE, HERE, check, read, write

PREFIX = 'docs/implementation/parallel-c/'


def restore(snapshot, root, contracts):
    """All content is inert until its original Git object identity and path are verified."""
    check(snapshot.get('schema') == 'cc.c.docs-snapshot/1', 'unsupported docs snapshot')
    check(set(snapshot) == {'schema','repository','base_sha','candidate_sha','base_commit','commits','trees','blobs','files'}, 'docs snapshot fields')
    check(snapshot['repository'] == 'senavirathne/CognitiveCraft-Documentation', 'wrong documentation repository')
    check(len(snapshot['trees']) <= 20000 and len(snapshot['commits']) <= 1000 and len(snapshot['files']) <= 4000, 'bounded docs snapshot')
    root = Path(root).resolve()
    check(not root.exists() or root.is_dir() and not any(root.iterdir()), 'refusing to overwrite an existing docs checkout')
    for path, sha in snapshot['files'].items():
        check(path.startswith(PREFIX) and '..' not in Path(path).parts and not Path(path).is_absolute(), 'unowned snapshot materialization path')
        check(sha in snapshot['blobs'], 'owned docs bytes unavailable')
    root.mkdir(parents=True, exist_ok=True)
    def git(*args, data=None):
        return subprocess.check_output(['git','-C',str(root),*args], input=data, stderr=subprocess.PIPE).decode().strip()
    def put(kind, sha, raw):
        check(hashlib.sha1(kind.encode()+b' '+str(len(raw)).encode()+b'\0'+raw).hexdigest()==sha, 'docs '+kind+' identity mismatch')
        check(git('hash-object','-w','-t',kind,'--stdin',data=raw)==sha, 'Git documentation object identity mismatch')
    git('init','-q')
    for sha, raw in snapshot['trees'].items():
        put('tree',sha,bytes.fromhex(raw))
    commits = dict(snapshot['commits']); commits[snapshot['base_sha']] = snapshot['base_commit']
    check(snapshot['candidate_sha'] in commits, 'candidate commit bytes unavailable')
    for sha, raw in commits.items():
        put('commit',sha,raw.encode())
    for sha, raw in snapshot['blobs'].items():
        put('blob',sha,raw.encode())
    (root/'.git/shallow').write_text(snapshot['base_sha']+'\n')
    git('update-ref','refs/heads/docs/player-assisted-dialogue',snapshot['candidate_sha'])
    git('symbolic-ref','HEAD','refs/heads/docs/player-assisted-dialogue')
    entries = {}
    for row in git('ls-tree','-r','HEAD').splitlines():
        metadata,path=row.split('\t',1);mode,kind,sha=metadata.split();check(kind=='blob','unsupported docs tracked type')
        entries[path]=(mode,sha)
    for path,sha in snapshot['files'].items():
        check(entries.get(path,(None,None))[1] == sha, 'owned snapshot differs from exact HEAD tree')
        destination=root/path;destination.parent.mkdir(parents=True,exist_ok=True);destination.write_text(snapshot['blobs'][sha])
        destination.chmod(0o755 if entries[path][0]=='100755' else 0o644)
    shutil.copytree(contracts,root/PREFIX/'contracts',ignore=shutil.ignore_patterns('__pycache__','*.pyc'))
    materialized=[]
    for path in (root/PREFIX).rglob('*'):
        if not path.is_file(): continue
        relative=path.relative_to(root).as_posix();check(not path.is_symlink() and relative in entries,'unexpected docs bytes')
        mode,sha=entries[relative]
        check(git('hash-object','-w','--stdin',data=path.read_bytes())==sha,'materialized docs/contract blob mismatch')
        path.chmod(0o755 if mode=='100755' else 0o644);materialized.append(relative)
    git('read-tree','HEAD')
    sparse=[path for path in entries if not (root/path).is_file()]
    if sparse: git('update-index','--skip-worktree','--stdin',data=('\n'.join(sparse)+'\n').encode())
    check(not git('status','--porcelain'),'restored documentation checkout is dirty')
    git('merge-base','--is-ancestor',snapshot['base_sha'],'HEAD')
    return {'docs_sha':snapshot['candidate_sha'],'scope':'EXACT_SPARSE_GIT_OBJECTS','materialized_paths':len(materialized),
            'sparse_paths':len(sparse),'verified_commits':len(commits),'verified_trees':len(snapshot['trees'])}


def materialize(root, snapshot_path=HERE/'docs-snapshot.json', expected_sha=None):
    check(Path(snapshot_path).stat().st_size <= 16*1024*1024,'docs snapshot byte bound')
    snapshot=read(snapshot_path)
    check(snapshot['base_sha']==DOCS_BASE,'frozen docs baseline mismatch')
    expected_sha=expected_sha or read(HERE/'qualification-request.json')['docs_candidate_sha']
    check(snapshot['candidate_sha']==expected_sha,'request/docs snapshot commit mismatch')
    return restore(snapshot,root,HERE/'contracts')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,required=True);p.add_argument('--snapshot',type=Path,default=HERE/'docs-snapshot.json');p.add_argument('--expected-sha');p.add_argument('--output',type=Path)
    args=p.parse_args();result=materialize(args.root,args.snapshot,args.expected_sha)
    if args.output: write(args.output,result)
    print(__import__('json').dumps(result,sort_keys=True))
