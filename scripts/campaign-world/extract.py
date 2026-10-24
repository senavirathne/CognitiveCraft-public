#!/usr/bin/env python3
"""Read selected ZIP members with authenticated GitHub metadata and unsigned HTTPS ranges.

Never forward the Actions token to artifact storage. Never unpack runtime caches or native cores.
The original complete archive remains at its recorded artifact ID and SHA-256.
"""
from collections import OrderedDict
import hashlib
import io
import json
import os
from pathlib import Path,PurePosixPath
import shutil
import time
import urllib.error
import urllib.request
from urllib.parse import urlparse
import zipfile

REPO='senavirathne/CognitiveCraft-public'
ARTIFACT=12555009116
RUN=40076986301
DIGEST='sha256:e13956b88af83f6c6102260457f032a59384bcc588de538a91081bd433ad9df5'
ROOT=Path(__file__).resolve().parents[2]
DEST=ROOT/'build/e2e/previous-world'
RUNTIME_PREFIX=None

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl): return None

def api(path,redirect=False):
    request=urllib.request.Request('https://api.github.com/repos/'+REPO+path,headers={
        'Authorization':'Bearer '+os.environ['GITHUB_TOKEN'],'Accept':'application/vnd.github+json',
        'X-GitHub-Api-Version':'2022-11-28','User-Agent':'CognitiveCraft-world-evidence','Cache-Control':'no-cache'})
    opener=urllib.request.build_opener(NoRedirect())
    try:
        with opener.open(request,timeout=60) as response: return json.load(response)
    except urllib.error.HTTPError as error:
        if redirect and error.code in (301,302,303,307,308):
            location=error.headers['Location']
            if urlparse(location).scheme!='https': raise RuntimeError('Artifact redirect requires HTTPS')
            return location
        raise RuntimeError('GitHub artifact request failed with HTTP '+str(error.code)) from None

class RangeReader(io.RawIOBase):
    def __init__(self,url,size,refresh=None):
        self.url=url; self.length=size; self.offset=0; self.blocks=OrderedDict(); self.transferred=0
        self.block_size=1024*1024; self.refresh=refresh
    def seekable(self): return True
    def readable(self): return True
    def tell(self): return self.offset
    def seek(self,offset,whence=0):
        self.offset=offset if whence==0 else self.offset+offset if whence==1 else self.length+offset
        if not 0<=self.offset<=self.length: raise ValueError('ZIP seek outside original artifact')
        return self.offset
    def read(self,size=-1):
        if size<0: size=self.length-self.offset
        size=min(size,self.length-self.offset); chunks=[]
        while size:
            number=self.offset//self.block_size
            if number not in self.blocks:
                first=number*self.block_size; last=min(self.length-1,first+self.block_size-1)
                # Signed storage URL only: no GitHub token or authorization header crosses the redirect.
                for attempt in range(3):
                    req=urllib.request.Request(self.url,headers={'Range':f'bytes={first}-{last}'})
                    try:
                        with urllib.request.urlopen(req,timeout=90) as response:
                            if response.status!=206: raise RuntimeError('Artifact storage did not honor bounded byte ranges')
                            block=response.read(self.block_size+1)
                        break
                    except urllib.error.HTTPError as error:
                        if error.code==403 and self.refresh and attempt<2:
                            self.url=self.refresh(); continue
                        if error.code in (429,500,502,503,504) and attempt<2:
                            time.sleep(attempt+1); continue
                        raise RuntimeError('Artifact storage range failed with HTTP '+str(error.code)) from None
                    except (urllib.error.URLError,TimeoutError,ConnectionError):
                        if attempt==2: raise RuntimeError('Artifact storage range remained unavailable after bounded retries') from None
                        time.sleep(attempt+1)
                if len(block)!=last-first+1: raise RuntimeError('Incomplete ZIP range')
                self.blocks[number]=block; self.transferred+=len(block)
                if len(self.blocks)>24: self.blocks.popitem(last=False)
            block=self.blocks[number]; self.blocks.move_to_end(number)
            start=self.offset%self.block_size; count=min(size,len(block)-start)
            chunks.append(block[start:start+count]); self.offset+=count; size-=count
        return b''.join(chunks)

def selected(name):
    parts=PurePosixPath(name).parts
    if '..' in parts or PurePosixPath(name).is_absolute(): raise RuntimeError('Unsafe artifact member path')
    if name.endswith('/'): return False
    if PurePosixPath(name).name=='ai-villages-0.1.0.jar': return True
    prefix=RUNTIME_PREFIX
    if not name.startswith(prefix): return False
    relative=name[len(prefix):]; basename=PurePosixPath(relative).name
    if relative=='server/observations.jsonl': return False
    if basename=='core' or basename.startswith('core.') or basename.endswith('.hprof') or basename=='session.lock': return False
    if relative.startswith('server/game/') and not relative.startswith('server/game/world/'):
        return '/logs/' in relative or '/crash-reports/' in relative or basename.startswith('hs_err_')
    if relative.startswith('clients/') and '/game/' in relative:
        return '/logs/' in relative or '/crash-reports/' in relative or basename.startswith('hs_err_')
    return True

def main():
    global RUNTIME_PREFIX
    import uuid
    metadata=api('/actions/artifacts/'+str(ARTIFACT))
    if metadata['workflow_run']['id']!=RUN or metadata['digest']!=DIGEST or metadata['expired']:
        raise RuntimeError('Exact original artifact provenance mismatch')
    def signed_url():
        return api('/actions/artifacts/'+str(ARTIFACT)+'/zip?campaign_nonce='+str(uuid.uuid4()),redirect=True)
    remote=RangeReader(signed_url(),metadata['size_in_bytes'],refresh=signed_url)
    DEST.mkdir(parents=True,exist_ok=True)
    manifest={'artifact':ARTIFACT,'run':RUN,'archiveDigest':DIGEST,
        'integrity':'HTTPS source plus ZIP member CRC and extracted SHA-256; whole archive not redownloaded',
        'members':[],'selectedUncompressedBytes':0}
    with zipfile.ZipFile(remote) as archive:
        entries=archive.infolist()
        roots=[i.filename[:-len('metadata.json')] for i in entries if i.filename.endswith('generalization-world/metadata.json')]
        if not roots and any(i.filename=='metadata.json' for i in entries): roots=['']
        if len(roots)!=1: raise RuntimeError('Exactly one campaign evidence prefix required')
        RUNTIME_PREFIX=roots[0]
        print('WORLD_ARCHIVE_LARGEST '+json.dumps([{'path':i.filename,'bytes':i.file_size,'selected':selected(i.filename)} for i in sorted(entries,key=lambda x:x.file_size,reverse=True)[:16]]),flush=True)
        total=sum(i.file_size for i in entries if selected(i.filename))
        if total>shutil.disk_usage(DEST).free-2*1024**3: raise RuntimeError('Insufficient disk for selected immutable evidence')
        for info in sorted(entries,key=lambda i:i.header_offset):
            keep=selected(info.filename); row={'path':info.filename,'bytes':info.file_size,'zipCRC32':info.CRC,'selected':keep}
            if keep:
                path=DEST/info.filename; path.parent.mkdir(parents=True,exist_ok=True); checksum=hashlib.sha256()
                temporary=path.with_name(path.name+'.extracting')
                with archive.open(info) as source,temporary.open('wb') as target:
                    while block:=source.read(1024*1024): target.write(block); checksum.update(block)
                temporary.replace(path)
                row['sha256']=checksum.hexdigest(); manifest['selectedUncompressedBytes']+=info.file_size
            manifest['members'].append(row)
    manifest['rangeBytesTransferred']=remote.transferred
    (DEST/'evidence-selection.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print('WORLD_EVIDENCE_SELECTION '+json.dumps({k:v for k,v in manifest.items() if k!='members'}),flush=True)


if __name__=='__main__': main()
