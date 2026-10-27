"""Profile exact retained A04 metadata; no Minecraft world or gameplay result is created."""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path,PurePosixPath
import subprocess
import sys
from zipfile import ZipFile

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from runtime import ROOT,SDK,java,digest

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive',type=Path,required=True)
    parser.add_argument('--root',type=Path,required=True)
    args=parser.parse_args();root=args.root.resolve();root.mkdir(parents=True,exist_ok=True)
    assert digest(args.archive)=='8f8837a90078cb529c5ae1d47bcd0e6115e6fc60d03116cd469dbf492bf17415'
    data=root/'metadata-fixtures';data.mkdir(exist_ok=False);files=[]
    with ZipFile(args.archive) as archive:
        chosen=[info for info in archive.infolist() if not info.is_dir() and (info.filename=='job-cost/frozen.json' or info.filename.startswith('job-cost/template/'))]
        assert len(chosen)<=160 and sum(info.file_size for info in chosen)<=16*1024*1024
        for info in chosen:
            relative=PurePosixPath(info.filename).relative_to('job-cost')
            assert not relative.is_absolute() and '..' not in relative.parts
            payload=archive.read(info);path=data/str(relative);path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(payload)
            files.append({'path':str(relative),'bytes':len(payload),'sha256':hashlib.sha256(payload).hexdigest()})
    provenance={'diagnostic':True,'nativeEffects':0,'nativeQualification':'NOT_EXECUTED','sourceArtifact':12729290176,
                'sourceCommit':'213fa02b051c947753dbd180c9188e3f83ebd35e','sourceArchiveSha256':digest(args.archive),
                'sourceFiles':files,'profiledReleaseSha256':digest(ROOT/'fabric/build/libs/ai-villages-0.1.0.jar')}
    (root/'input-provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
    recording=root/'metadata.jfr'
    cp=[ROOT/'build/parallel-a/classes',ROOT/'fabric/build/libs/ai-villages-0.1.0.jar',SDK/'minecraft-client.jar',
        *sorted((SDK/'libraries').rglob('*.jar')),*sorted((ROOT/'build/server-sdk/libs').glob('*.jar'))]
    command=[java('java'),'-Xms256M','-Xmx1536M',f'-XX:StartFlightRecording=filename={recording},settings=profile,dumponexit=true,maxsize=32m',
             '-cp',os.pathsep.join(map(str,cp)),'dev.aivillages.core.kernel.RuntimeAJobCost',str(data)]
    result=subprocess.run(command,cwd=ROOT,text=True,capture_output=True)
    (root/'profile-process.log').write_text(result.stdout+result.stderr);result.check_returncode()
    (root/'profile-result.json').write_bytes((data/'profile-result.json').read_bytes())
    printed=subprocess.run([java('jfr'),'print','--json','--events','jdk.ExecutionSample,jdk.NativeMethodSample',str(recording)],text=True,capture_output=True,check=True)
    events=json.loads(printed.stdout)['recording']['events'];tops=Counter();inclusive=Counter();stacks=Counter()
    for event in events:
        trace=event['values'].get('stackTrace')
        if not trace:continue
        names=[f["method"]["type"]["name"].replace('/','.')+'.'+f["method"]["name"] for f in trace['frames']]
        if not names:continue
        tops[names[0]]+=1;inclusive.update(set(names));stacks[' > '.join(names[:10])]+=1
    assert events and tops
    summary={'diagnostic':True,'nativeEffects':0,'samples':sum(tops.values()),'jfrSha256':digest(recording),
             'topFrames':tops.most_common(30),'inclusiveFrames':inclusive.most_common(40),'topStacks':stacks.most_common(25)}
    (root/'hot-stacks.json').write_text(json.dumps(summary,indent=2)+'\n')
    print(json.dumps({'diagnostic':True,'nativeEffects':0,'samples':summary['samples'],'topFrames':summary['topFrames'][:8]}))

if __name__=='__main__':main()
