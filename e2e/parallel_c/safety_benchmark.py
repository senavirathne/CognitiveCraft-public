"""Measure the identical finite parser schedule on the preserved C06 release, then the candidate."""
from pathlib import Path
import os
import shutil
import subprocess
from common import HERE,JAR,ROOT,check,digest,read,write
from blocker_runtime import dependency
from oracle import SCENARIOS,SCENARIOS_SHA256,freeze

BASELINE=HERE/'safety-baseline-C06.jar'
BASELINE_SHA256='28c6452b6b31ba4f76ef72d86daf742875e39327d940dbd763e3c31466b1a103'

def run(output):
    output=Path(output);output.mkdir(parents=True,exist_ok=False);schedule=freeze()
    check(digest(BASELINE)==BASELINE_SHA256,'unchanged C06 benchmark JAR changed')
    driver=output/'classes/dev/aivillages/core/kernel/interaction';driver.mkdir(parents=True)
    files=list((ROOT/'core/build/classes/java/test/dev/aivillages/core/kernel/interaction').glob('InteractionGrammarProbe*.class'));check(files,'actual compiled parser comparison driver required')
    for p in files:shutil.copy2(p,driver/p.name)
    results=[]
    java=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    for name,jar in [('baseline',BASELINE),('candidate',JAR)]:
        target=output/(name+'.json');cp=os.pathsep.join([str(jar),str(output/'classes'),dependency()['path']]);command=[java,'-cp',cp,'dev.aivillages.core.kernel.interaction.InteractionGrammarProbe',str(SCENARIOS),str(target)]
        with (output/(name+'.log')).open('w') as log:p=subprocess.run(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,timeout=40)
        check(p.returncode==0,'actual parser comparison failed: '+name);result=read(target);check(result['jar_sha256']==digest(jar) and len(result['rows'])==60,'parser comparison candidate/schedule mismatch')
        results.append(dict(name=name,jar_sha256=digest(jar),file=target.name,sha256=digest(target),command=command,exit=p.returncode))
    receipt=dict(tier='SYNTHETIC_PARSER_COMPARISON',campaign_sha256=SCENARIOS_SHA256,runs=results,driver_classes={p.relative_to(output).as_posix():digest(p) for p in driver.glob('*.class')})
    write(output/'receipt.json',receipt);return verify(output)

def verify(output):
    output=Path(output);receipt=read(output/'receipt.json');expected=freeze()['cases']
    check(digest(BASELINE)==BASELINE_SHA256,'unchanged C06 comparison release bytes changed')
    check(receipt['campaign_sha256']==SCENARIOS_SHA256 and len(receipt['runs'])==2,'same frozen parser comparison schedule required')
    summary={}
    for item,(name,sha) in zip(receipt['runs'],[('baseline',BASELINE_SHA256),('candidate',digest(JAR))]):
        check(item['name']==name and item['jar_sha256']==sha and item['exit']==0 and digest(output/item['file'])==item['sha256'],'parser comparison identity changed')
        raw=read(output/item['file']);check(raw['tier']=='SYNTHETIC_PARSER_COMPARISON' and raw['jar_sha256']==sha,'parser comparison loaded another classpath')
        rows=raw['rows'];check([(r['id'],r['seed']) for r in rows]==[(r['id'],r['seed']) for r in expected],'parser comparison case order changed')
        times=sorted(r['elapsed_ns'] for r in rows);check(all(type(t)is int and 0<=t<1_000_000_000 for t in times),'finite parser comparison latency exceeded')
        summary[name]=dict(cases=60,jar_sha256=sha,p50_ns=times[29],p95_ns=times[56],p99_ns=times[59],max_ns=times[-1])
    for p,sha in receipt['driver_classes'].items():check(digest(output/p)==sha,'comparison driver changed')
    return dict(status='PASS',tier='SYNTHETIC_PARSER_COMPARISON',native_credit=0,measurements=summary)
