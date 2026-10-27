"""Summarize post-failure native CPU recordings without changing a frozen gate result."""
import argparse
from collections import Counter,defaultdict
import json
from pathlib import Path
import subprocess
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from runtime import java,digest

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--root',required=True,type=Path);args=parser.parse_args()
    args.root.mkdir(parents=True,exist_ok=True);reports=[]
    for path in sorted((args.root/'server').glob('a04-profile-*.jfr')):
        result=subprocess.run([java('jfr'),'print','--json','--events','jdk.ExecutionSample,jdk.NativeMethodSample',str(path)],text=True,capture_output=True,check=True)
        groups=defaultdict(Counter);inclusive=defaultdict(Counter)
        for event in json.loads(result.stdout)['recording']['events']:
            values=event['values'];trace=values.get('stackTrace')
            if not trace:continue
            thread=values.get('sampledThread') or values.get('eventThread') or {};name=thread.get('javaName',thread.get('osName','unknown'))
            frames=[f['method']['type']['name'].replace('/','.')+'.'+f['method']['name'] for f in trace['frames']]
            if frames:groups[name][frames[0]]+=1;inclusive[name].update(set(frames))
        report={'diagnostic':True,'nativeEffects':0,'nativeQualification':'NOT_EXECUTED','recording':path.name,'sha256':digest(path),
                'threads':{name:{'samples':sum(counter.values()),'topFrames':counter.most_common(25),'inclusiveFrames':inclusive[name].most_common(35)} for name,counter in groups.items()}}
        path.with_suffix('.summary.json').write_text(json.dumps(report,indent=2)+'\n');reports.append({'recording':path.name,'sha256':report['sha256'],'threads':len(groups)})
    (args.root/'cpu-profile-collection.json').write_text(json.dumps({'diagnostic':True,'nativeEffects':0,'collection':'PASS' if reports else 'NOT_EXECUTED','recordings':reports},indent=2)+'\n')
    print(json.dumps({'diagnostic':True,'recordings':len(reports),'originalNativeGateUnchanged':True}))

if __name__=='__main__':main()
