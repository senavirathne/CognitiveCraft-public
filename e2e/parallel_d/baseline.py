#!/usr/bin/env python3
"""Extract only the exact qualified D01 production JAR for the unchanged performance control."""
import argparse,hashlib
from zipfile import ZipFile
from common import HERE,REPO,read,require,sha,write

def extract(archive):
    lock=read(HERE/'predecessors/D01-lock.json')
    require(sha(archive)==lock['artifact_sha256'],'D01 baseline artifact digest mismatch')
    with ZipFile(archive) as z:
        require(z.testzip() is None,'D01 artifact CRC error')
        name='fabric/build/libs/ai-villages-0.1.0.jar';require(z.namelist().count(name)==1,'ambiguous baseline JAR')
        data=z.read(name)
    require(hashlib.sha256(data).hexdigest()==lock['jar_sha256'],'D01 baseline JAR mismatch')
    path=REPO/'build/d02-baseline/ai-villages-0.1.0.jar';path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
    write(path.parent/'identity.json',lock)
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--archive',type=__import__('pathlib').Path,required=True);a=p.parse_args();extract(a.archive)
