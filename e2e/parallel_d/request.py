#!/usr/bin/env python3
"""Only deliberate, validated request changes may launch owner qualification."""
from common import *

if __name__=='__main__':
    final=HERE/'qualification-request.json';path=final if final.exists() else HERE/'blocking-request.json'
    r=read(path);require(r['contract_sha256']==CONTRACT,'request contract changed');registry(r['stage'])
    require(r['stage'] in ('D00','D01') and path.name=='blocking-request.json','unimplemented qualification stage')
    require(r['campaign']=={'D00':'D00-BOOTSTRAP','D01':'D01-METADATA'}[r['stage']] and r.get('reason') and len(r['nonce'])>=12,'invalid blocking request')
    require('source_sha' not in r,'self-referential request source')
    if os.getenv('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'],'a') as f:f.write('stage='+r['stage']+'\n')
    if os.getenv('GITHUB_ENV'):
        with open(os.environ['GITHUB_ENV'],'a')as f:f.write('D_STAGE='+r['stage']+'\nD_ROOT=build/e2e/parallel-d/'+r['stage']+'\n')
    print(json.dumps(r,sort_keys=True))
