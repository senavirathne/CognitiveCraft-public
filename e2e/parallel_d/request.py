#!/usr/bin/env python3
"""Only deliberate, validated request changes may launch owner qualification."""
from common import *

if __name__=='__main__':
    final=HERE/'qualification-request.json';path=final if final.exists() else HERE/'blocking-request.json'
    r=read(path);require(r['contract_sha256']==CONTRACT,'request contract changed');registry(r['stage'])
    require(r['stage']=='D00' and path.name=='blocking-request.json','unimplemented qualification stage')
    require(r['campaign']=='D00-BOOTSTRAP' and r.get('reason') and len(r['nonce'])>=12,'invalid blocking request')
    require('source_sha' not in r,'self-referential request source')
    if os.getenv('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'],'a') as f:f.write('stage='+r['stage']+'\n')
    print(json.dumps(r,sort_keys=True))
