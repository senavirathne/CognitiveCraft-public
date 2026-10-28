#!/usr/bin/env python3
"""Immutable measured predecessor; no floating B reset, peer import or invented readiness."""
import argparse, copy
from common import *

D00_SOURCE='313f32b80f6b4c1c6e8182982f40c85d417d38ba'
D00_DOCS='2c0add145f376b720b4a7ca9dfbe6a20c39f8519'
D00_QUALIFIED='2b50b80989396ff8ee57da5f9ae152947bc81c4b9b7408c98c5daae67320b589'

def measured(manifest):
    require(manifest['stage']=='D00' and manifest['owner']=='D' and manifest['gate_status']=='OWNED_GATES_PASS','predecessor unqualified')
    require(manifest['source_sha']==D00_SOURCE and manifest['tree_sha']=='d982a45726e1f8ae5c586c60fe9489b88850417e','predecessor source changed')
    require(manifest['workflow_run']==40645634832 and manifest['attempt']==2,'predecessor attempt changed')
    for tier,planned in [('unit',59),('python',26),('game_tests',0),('client_e2e',0)]:
        row=manifest[tier]
        require(row['planned']==planned and row['executed']==planned and row['passed']==planned
                and row['failed']==row['inconclusive']==row['refused_expected']==0,'predecessor incomplete counters: '+tier)
    require(not manifest['regressions'],'predecessor regression')

def verify(stage,docs_root=None):
    spec=registry(stage)
    if not spec.get('predecessor'):return None
    require(spec['predecessor']=='D00','unknown predecessor')
    raw=HERE/'predecessors/D00-qualified.json';require(sha(raw)==D00_QUALIFIED,'predecessor evidence bytes changed')
    qualified=read(raw);measured(qualified);lock=read(HERE/'predecessors/D00-lock.json')
    for key in ('source_sha','tree_sha','jar_sha256','workflow_run','attempt'):require(lock[key]==qualified[key],'predecessor pin mismatch: '+key)
    require(lock['artifact_sha256']=='bf0301baaf164e3c709f8d52d189d0f9a00706b3531e8dbd785ec9a072a28512','predecessor artifact changed')
    require(lock['source_sha']==D00_SOURCE and lock['docs_sha']==D00_DOCS and lock['qualified_sha256']==D00_QUALIFIED,'predecessor handoff changed')
    git('merge-base','--is-ancestor',D00_SOURCE,'HEAD')
    if docs_root:git('merge-base','--is-ancestor',D00_DOCS,'HEAD',root=docs_root)
    return lock

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--stage',required=True);p.add_argument('--docs-root',type=Path)
    a=p.parse_args();print(json.dumps({'stage':a.stage,'predecessor':verify(a.stage,a.docs_root)},sort_keys=True))
