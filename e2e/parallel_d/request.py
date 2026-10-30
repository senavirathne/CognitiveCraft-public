#!/usr/bin/env python3
"""Only deliberate, validated request changes may launch owner qualification."""
from common import *

if __name__=='__main__':
    final=HERE/'qualification-request.json';path=final if final.exists() else HERE/'blocking-request.json'
    r=read(path);require(r['contract_sha256']==CONTRACT,'request contract changed');registry(r['stage'])
    if path==final:
        require(r['stage']=='D11' and r['campaign']=='D11-CUMULATIVE','unimplemented final qualification stage')
    else:
        require(r['stage'] in ('D00','D01','D02','D03','D04','D05','D06','D07','D08','D09','D10'),'unimplemented diagnostic stage')
        require(r['campaign']=={'D00':'D00-BOOTSTRAP','D01':'D01-METADATA','D02':'D02-INCARNATION','D03':'D03-ACL','D04':'D04-RESOLUTION','D05':'D05-ADAPTERS','D06':'D06-MOBILITY','D07':'D07-FOLLOW','D08':'D08-COURIER','D09':'D09-HANDOFF','D10':'D10-COMPONENT-48'}[r['stage']],'invalid diagnostic campaign')
    require(r.get('reason') and len(r['nonce'])>=12,'invalid qualification request')
    require('source_sha' not in r,'self-referential request source')
    if os.getenv('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'],'a') as f:f.write('stage='+r['stage']+'\n')
    if os.getenv('GITHUB_ENV'):
        with open(os.environ['GITHUB_ENV'],'a')as f:f.write('D_STAGE='+r['stage']+'\nD_ROOT=build/e2e/parallel-d/'+r['stage']+'\n')
    print(json.dumps(r,sort_keys=True))
