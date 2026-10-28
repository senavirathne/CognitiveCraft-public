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

def measured_d01(q):
    require(q['stage']=='D01' and q['owner']=='D' and q['gate_status']=='OWNED_GATES_PASS','D01 predecessor unqualified')
    require(q['source_sha']=='ea1ff7ec9ab1fd16782c32130207aa7e5230442a' and q['tree_sha']=='4890dae733de13eb74fa1c9827e50215d4fa2cce','D01 source changed')
    require(q['workflow_run']==40662229204 and q['attempt']==1 and q['jar_sha256']=='8aaea7d9a69874c1a975d31acade6110c3f9bfa7b35e46125eaa4c699f772cc0','D01 execution changed')
    for tier,n in [('unit',90),('python',42),('game_tests',0),('client_e2e',0)]:
        r=q[tier];require(r['planned']==r['executed']==r['passed']==n and r['failed']==r['inconclusive']==r['refused_expected']==0,'D01 incomplete counters')
    require(not q['regressions'],'D01 regression')

def measured_d02(q):
    require(q['stage']=='D02' and q['owner']=='D' and q['gate_status']=='OWNED_GATES_PASS','D02 predecessor unqualified')
    require(q['source_sha']=='ffcd3f5f3d37fc0d590dacca0a88471bb7e5eee9' and q['tree_sha']=='a51dc4711641afe9e85139cbfe756a282ff3e132','D02 source changed')
    require(q['jar_sha256']=='ff505ffabd76d61f46aeb0569b0afeb6274aa1443a4276d554208129593e1ed1' and q['workflow_run']==40672315339 and q['attempt']==1,'D02 execution changed')
    for tier,n in [('unit',100),('python',50),('game_tests',4),('client_e2e',0)]:
        r=q[tier];require(r['planned']==r['executed']==r['passed']==n and r['failed']==r['inconclusive']==r['refused_expected']==0,'D02 incomplete counters')
    require(not q['regressions'],'D02 regression')

def measured_d03(q):
    require(q['stage']=='D03' and q['owner']=='D' and q['gate_status']=='OWNED_GATES_PASS','D03 predecessor unqualified')
    require(q['source_sha']=='cc3d8e692ab736f0ebc7466d85ecfa77d8c6b793' and q['tree_sha']=='acd6feb7cde0dbfb37183922a8b4f435ccbf3f62','D03 source changed')
    require(q['jar_sha256']=='e2c5065618856ad10d539c274f55ac40a41b8e1cedd9b104e39ae8328531ec44' and q['workflow_run']==40677975122 and q['attempt']==1,'D03 execution changed')
    for tier,n in [('unit',133),('python',56),('game_tests',4),('client_e2e',0)]:
        row=q[tier];require(row['planned']==row['executed']==row['passed']==n and row['failed']==row['inconclusive']==row['refused_expected']==0,'D03 incomplete counters')
    require(not q['regressions'],'D03 regression')

def measured_d04(q):
    require(q['stage']=='D04' and q['owner']=='D' and q['gate_status']=='OWNED_GATES_PASS','D04 predecessor unqualified')
    require(q['source_sha']=='0ebc4f3ea08986fc024b775d77ef3bade134e5d9' and q['tree_sha']=='95048a3a7c6e39873bb3e10631f429638102c3c8','D04 source changed')
    require(q['jar_sha256']=='4fae10bcfdccbcf55ec95c9fa2f29fc387a28a5d7874842a54c0ce70bd137e4d' and q['workflow_run']==40712423016 and q['attempt']==1,'D04 execution changed')
    for tier,n in [('unit',159),('python',61),('game_tests',4),('client_e2e',0)]:
        row=q[tier];require(row['planned']==row['executed']==row['passed']==n and row['failed']==row['inconclusive']==row['refused_expected']==0,'D04 incomplete counters')
    require(not q['regressions'],'D04 regression')

def measured_d05(q):
    require(q['stage']=='D05' and q['owner']=='D' and q['gate_status']=='OWNED_GATES_PASS','D05 predecessor unqualified')
    require(q['source_sha']=='45c959474515d785817b8924fa2ca2b2c8a592ba' and q['tree_sha']=='2bac80cb90a9c8cc983cbfd3b86bd06528c87ab2','D05 source changed')
    require(q['jar_sha256']=='10c2894e23c8f931be1e0c9ba003b874788b69648444651b75df1843e90a4aa7' and q['workflow_run']==40730533503 and q['attempt']==1,'D05 execution changed')
    for tier,n in [('unit',177),('python',68),('game_tests',5),('client_e2e',1)]:
        row=q[tier];require(row['planned']==row['executed']==row['passed']==n and row['failed']==row['inconclusive']==row['refused_expected']==0,'D05 incomplete counters')
    require(not q['regressions'],'D05 regression')

def verify(stage,docs_root=None):
    spec=registry(stage)
    if not spec.get('predecessor'):return None
    require(spec['predecessor'] in ('D00','D01','D02','D03','D04','D05','D06'),'unknown predecessor')
    raw=HERE/'predecessors/D00-qualified.json';require(sha(raw)==D00_QUALIFIED,'predecessor evidence bytes changed')
    qualified=read(raw);measured(qualified);lock=read(HERE/'predecessors/D00-lock.json')
    for key in ('source_sha','tree_sha','jar_sha256','workflow_run','attempt'):require(lock[key]==qualified[key],'predecessor pin mismatch: '+key)
    require(lock['artifact_sha256']=='bf0301baaf164e3c709f8d52d189d0f9a00706b3531e8dbd785ec9a072a28512','predecessor artifact changed')
    require(lock['source_sha']==D00_SOURCE and lock['docs_sha']==D00_DOCS and lock['qualified_sha256']==D00_QUALIFIED,'predecessor handoff changed')
    git('merge-base','--is-ancestor',D00_SOURCE,'HEAD')
    if docs_root:git('merge-base','--is-ancestor',D00_DOCS,'HEAD',root=docs_root)
    if spec['predecessor'] in ('D01','D02','D03','D04','D05','D06'):
        raw=HERE/'predecessors/D01-qualified.json'
        require(sha(raw)=='673945a9830a347bdefbe96fb6ca95e98bcfbb3a57f5438ec1351b7163fca17f','D01 predecessor evidence changed')
        q=read(raw);lock=read(HERE/'predecessors/D01-lock.json');measured_d01(q)
        for key in ('source_sha','tree_sha','jar_sha256','workflow_run','attempt'):require(q[key]==lock[key],'D01 handoff pin mismatch')
        require(lock['docs_sha']=='3fd622e0d7b4c24762da3b6cd12ab85b6380db83' and lock['artifact_sha256']=='70845ef686d99bee9b8b6b981b5b76cd7ac1a0a652b216ea1143f603da8743a5','D01 docs/artifact changed')
        git('merge-base','--is-ancestor',lock['source_sha'],'HEAD')
        if docs_root:git('merge-base','--is-ancestor',lock['docs_sha'],'HEAD',root=docs_root)
    if spec['predecessor'] in ('D02','D03','D04','D05','D06'):
        raw=HERE/'predecessors/D02-qualified.json';require(sha(raw)=='793a14cb1444a4b44c43a4b1766542e52779023c9ff22450140c385d6f255bde','D02 raw evidence changed')
        q=read(raw);lock=read(HERE/'predecessors/D02-lock.json');measured_d02(q)
        for key in ('source_sha','tree_sha','jar_sha256','workflow_run','attempt'):require(q[key]==lock[key],'D02 handoff pin mismatch')
        require(lock['docs_sha']=='b4294e84e602b929d61dcc9c4b03c9e224b71182' and lock['artifact_sha256']=='db46f4890cf9d68d8054e0ed0e857909b94ef02cf464a4c7e31bb9c3fcb981e5','D02 docs/artifact changed')
        git('merge-base','--is-ancestor',lock['source_sha'],'HEAD')
        if docs_root:git('merge-base','--is-ancestor',lock['docs_sha'],'HEAD',root=docs_root)
    if spec['predecessor'] in ('D03','D04','D05','D06'):
        raw=HERE/'predecessors/D03-qualified.json';require(sha(raw)=='7a5bd3fe2f646b15d2c185e153fe470e8b64ed4b8e838dc475951cb3a573bd70','D03 raw evidence changed')
        q=read(raw);lock=read(HERE/'predecessors/D03-lock.json');measured_d03(q)
        for key in ('source_sha','tree_sha','jar_sha256','workflow_run','attempt'):require(q[key]==lock[key],'D03 handoff pin mismatch')
        require(lock['docs_sha']=='6185844f50200b1fbeeff0a8bef11e3854efab58' and lock['artifact_sha256']=='22edc3fbe2028e8bbf3fd0c30a280678d5a0572a5656f32b21a15dbaa1c240e2','D03 docs/artifact changed')
        git('merge-base','--is-ancestor',lock['source_sha'],'HEAD')
        if docs_root:git('merge-base','--is-ancestor',lock['docs_sha'],'HEAD',root=docs_root)
    if spec['predecessor'] in ('D04','D05','D06'):
        raw=HERE/'predecessors/D04-qualified.json';require(sha(raw)=='4dc070fe9d49a525b82a53c23a78680e2edd033527a8fe1f68977838dbc32d50','D04 raw evidence changed')
        q=read(raw);lock=read(HERE/'predecessors/D04-lock.json');measured_d04(q)
        for key in ('source_sha','tree_sha','jar_sha256','workflow_run','attempt'):require(q[key]==lock[key],'D04 handoff pin mismatch')
        require(lock['docs_sha']=='b1965f33ca4125f7130abd0ba418d862a8f290a6' and lock['artifact_sha256']=='4f2fb39102dcc0c3517a7faebab1d98a073732dfb87ee349a8f0f8747c8cc801','D04 docs/artifact changed')
        git('merge-base','--is-ancestor',lock['source_sha'],'HEAD')
        if docs_root:git('merge-base','--is-ancestor',lock['docs_sha'],'HEAD',root=docs_root)
    if spec['predecessor'] in ('D05','D06'):
        raw=HERE/'predecessors/D05-qualified.json';require(sha(raw)=='eb8479f3a5948bbf1e80ca43c3c024520bc10b063bb12dc3d3f7a10d0b873ddd','D05 raw evidence changed')
        q=read(raw);lock=read(HERE/'predecessors/D05-lock.json');measured_d05(q)
        for key in ('source_sha','tree_sha','jar_sha256','workflow_run','attempt'):require(q[key]==lock[key],'D05 handoff pin mismatch')
        require(lock['docs_sha']=='abbbae8f2c63790f512d72c91a4a89e5b33f862e' and lock['artifact_sha256']=='a7f4632e08323817e42118046781b8db1b901571780a53f947c81d6a925e536b','D05 docs/artifact changed')
        git('merge-base','--is-ancestor',lock['source_sha'],'HEAD')
        if docs_root:git('merge-base','--is-ancestor',lock['docs_sha'],'HEAD',root=docs_root)
    if spec['predecessor']=='D06':
        raw=HERE/'predecessors/D06-qualified.json';require(sha(raw)=='dee292e1cc011ea6c7e93038c49f553ad5a294fe2b1d910333693cf9f1e36df0','D06 evidence changed')
        q=read(raw);lock=read(HERE/'predecessors/D06-lock.json')
        require(q['stage']=='D06' and q['gate_status']=='OWNED_GATES_PASS' and not q['regressions'],'D06 not qualified')
        for key in ('source_sha','tree_sha','jar_sha256','workflow_run','attempt'):require(q[key]==lock[key],'D06 pin mismatch')
        require(lock['source_sha']=='7fa81a5ec84ed48a12c1f60104fe1d51d7b48bc2' and lock['docs_sha']=='c8afc4c32b44609a2ef910ce37d0b794820ea06d' and lock['artifact_sha256']=='ba1a4a38995f2994dab638d4a090047bd25d05e0d4d958b98a2b8ef867153ad2','D06 handoff changed')
        for tier,n in [('unit',193),('python',75),('game_tests',6),('client_e2e',2)]:
            a=q[tier];require(a['planned']==a['executed']==a['passed']==n and a['failed']==a['inconclusive']==a['refused_expected']==0,'D06 counters')
        git('merge-base','--is-ancestor',lock['source_sha'],'HEAD')
        if docs_root:git('merge-base','--is-ancestor',lock['docs_sha'],'HEAD',root=docs_root)
    return lock

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--stage',required=True);p.add_argument('--docs-root',type=Path)
    a=p.parse_args();print(json.dumps({'stage':a.stage,'predecessor':verify(a.stage,a.docs_root)},sort_keys=True))
