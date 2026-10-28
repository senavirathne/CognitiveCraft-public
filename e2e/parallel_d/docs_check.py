#!/usr/bin/env python3
"""Owned local links/fragments only. This is not the deferred J06 repository audit."""
import argparse, re
from pathlib import Path
from common import *

def check(root, stage='D00'):
    registry(stage);owned=root/'docs/implementation/parallel-d';require(owned.is_dir(),'owned docs missing')
    required=[stage+'.md','progress.json','stage-manifest.json','decisions.md','INDEX_FRAGMENT.md','manifest-fragment.json']
    for name in required:require((owned/name).is_file(),'missing owned docs: '+name)
    for p in owned.rglob('*.md'):
        for target in re.findall(r'\[[^\]]*\]\(([^)]+)\)',p.read_text()):
            if '://' in target or target.startswith('#'):continue
            target=target.split('#')[0];require((p.parent/target).is_file(),'broken owned link: '+str(p)+' -> '+target)
    current=read(owned/'stage-manifest.json')
    manifest=current if current['stage']==stage else read(owned/(stage+'.stage-manifest.json'))
    require(manifest['owner']=='D' and manifest['stage']==stage,'wrong doc stage owner')
    require(manifest['gate_status']!='BRANCH_READY','early stage cannot claim branch readiness')
    progress=read(owned/'progress.json') if current['stage']==stage else read(owned/(stage+'.progress.json'))
    require(progress['next_stage']=={'D00':'D01','D01':'D02'}[stage],'unexpected advancement')
    fragments=read(owned/'manifest-fragment.json')
    require(fragments['integration_owner']=='J06' and fragments['destinations']==['AGENT_DOCUMENTATION_INDEX.md','blueprint-manifest.yaml'],'shared documentation destinations unfrozen')
    return {'stage':stage,'scope':'OWNED_DOCUMENTATION_ONLY','files':len(list(owned.rglob('*'))),'gate_status':manifest['gate_status']}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--docs-root',type=Path,required=True);p.add_argument('--owned-prefix',default='docs/implementation/parallel-d');p.add_argument('--stage',default='D00')
    a=p.parse_args();require(a.owned_prefix=='docs/implementation/parallel-d','unowned docs prefix');print(json.dumps(check(a.docs_root,a.stage),sort_keys=True))
