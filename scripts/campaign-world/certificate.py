#!/usr/bin/env python3
"""Import a complete genuine native certificate; never replace or synthesize a program."""
import hashlib
import json
from pathlib import PurePosixPath
import uuid
import zipfile

from extract import api,RangeReader,ROOT

ARTIFACT=12563506914
RUN=40101094391
SOURCE='45b8d7708ea547102f484b576fe46992f4ee4419'
DIGEST='sha256:80b020da21c5cab18fb01a0c9b589fd9d05cac561817bdf8d6d055c5486fe525'
PREFIX='generalization-world/certificates/W6-admitted/'
DEST=ROOT/'build/e2e/generalization-world/certificates/exact-original-methods'


def main():
    metadata=api('/actions/artifacts/'+str(ARTIFACT))
    if metadata['workflow_run']['id']!=RUN or metadata['workflow_run']['head_sha']!=SOURCE or metadata['digest']!=DIGEST or metadata['expired']:
        raise RuntimeError('Expected exact immutable fourth artifact')
    def signed(): return api('/actions/artifacts/'+str(ARTIFACT)+'/zip?campaign_nonce='+str(uuid.uuid4()),redirect=True)
    remote=RangeReader(signed(),metadata['size_in_bytes'],refresh=signed)
    with zipfile.ZipFile(remote) as archive:
        raw=archive.read(PREFIX+'certificate.json'); proof=json.loads(raw)
        if proof['sourceCommit']!=SOURCE or proof['productionBaseline']!='ea7de8000b2b484b885576c34ed5358274936ce9':
            raise RuntimeError('Native certificate provenance mismatch')
        DEST.mkdir(parents=True,exist_ok=False); (DEST/'certificate.json').write_bytes(raw)
        for row in proof['files']:
            relative=PurePosixPath(row['path'])
            if relative.is_absolute() or '..' in relative.parts: raise RuntimeError('Invalid native certificate member')
            data=archive.read(PREFIX+'world/'+str(relative))
            if len(data)!=row['bytes'] or hashlib.sha256(data).hexdigest()!=row['sha256']:
                raise RuntimeError('Genuine world certificate bytes changed')
            target=DEST/'world'/relative; target.parent.mkdir(parents=True,exist_ok=True); target.write_bytes(data)
    evidence={'artifact':metadata,'certificateSha256':hashlib.sha256(raw).hexdigest(),
        'sourceCertificate':'certificates/W6-admitted','filesVerified':len(proof['files']),
        'methods':proof['methods'],'rangeBytesTransferred':remote.transferred,
        'purpose':'Exact original-program controlled comparison in a separately genuinely admitted complete native world; original saturated world preserved'}
    (DEST/'import-provenance.json').write_text(json.dumps(evidence,indent=2)+'\n')
    print('WORLD_EXACT_PROGRAM_CERTIFICATE '+json.dumps(evidence,separators=(',',':')),flush=True)


if __name__=='__main__': main()
