"""Lossless transport of retained native ZIPs through bounded artifact downloads."""
import argparse,hashlib,json,math
from pathlib import Path,PurePosixPath
from tempfile import TemporaryDirectory
from zipfile import ZipFile,ZIP_DEFLATED

PART_BYTES=400*1024**2

def split(archive,out,expected):
    archive=Path(archive);out=Path(out);out.mkdir(parents=True,exist_ok=True)
    with archive.open('rb') as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
    assert digest==expected,'Retained archive SHA changed'
    count=math.ceil(archive.stat().st_size/PART_BYTES);assert 1<=count<=4,'All archive parts must fit the declared transport slots'
    manifest={'schema':1,'archiveBytes':archive.stat().st_size,'archiveSha256':digest,'parts':[]}
    with archive.open('rb') as stream:
        for n in range(count):
            body=stream.read(PART_BYTES);name=f'part-{n:03d}.bin';(out/name).write_bytes(body)
            manifest['parts'].append({'name':name,'bytes':len(body),'sha256':hashlib.sha256(body).hexdigest()})
        assert stream.read(1)==b''
    (out/'parts-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    return manifest

def inspect(archive,out):
    out=Path(out);summary={}
    with ZipFile(archive) as original,ZipFile(out/'diagnostics.zip','w',ZIP_DEFLATED) as compact:
        assert original.testzip() is None
        for row in original.infolist():
            path=PurePosixPath(row.filename);assert not path.is_absolute() and '..' not in path.parts
            if not row.is_dir() and (path.suffix in {'.json','.jsonl','.log','.txt'} or row.filename.endswith('.log.gz')):
                compact.writestr(row.filename,original.read(row))
        for name in ('a06-original-results.json','a06-controls-results.json'):
            summary[name]=json.loads(original.read(name))
    (out/'diagnostic-summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    cases=summary['a06-original-results.json']['cases']
    print('A06_RETAINED_ORIGINALS '+json.dumps([{'id':c['originalCase']['id'],'status':c['status'],'reason':c.get('reason')} for c in cases]),flush=True)
    print('A06_RETAINED_CONTROLS '+json.dumps([{'id':c['id'],'status':c['status'],'reason':c.get('reason')} for c in summary['a06-controls-results.json']['cases']]),flush=True)

def pack(root,out):
    root=Path(root).resolve();out=Path(out).resolve()
    if out.is_relative_to(root):raise ValueError('Transport output must be outside the native evidence root')
    files=sorted(path for path in root.rglob('*') if path.is_file())
    if not files:raise ValueError('Native evidence root contains no files')
    out.mkdir(parents=True,exist_ok=True)
    with TemporaryDirectory(dir=out) as folder:
        archive=Path(folder)/'native.zip'
        with ZipFile(archive,'w',ZIP_DEFLATED,compresslevel=3) as zipped:
            for path in files:zipped.write(path,path.relative_to(root).as_posix())
        with archive.open('rb') as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
        return split(archive,out,digest)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    source=p.add_mutually_exclusive_group(required=True)
    source.add_argument('--archive',type=Path);source.add_argument('--root',type=Path)
    p.add_argument('--out',type=Path,required=True);p.add_argument('--sha256')
    a=p.parse_args()
    if a.root:
        manifest=pack(a.root,a.out)
    else:
        if not a.sha256:p.error('--archive requires --sha256')
        manifest=split(a.archive,a.out,a.sha256)
    print('A06_LOSSLESS_ARCHIVE_PARTS '+json.dumps(manifest),flush=True)
    if a.archive:inspect(a.archive,a.out)

if __name__=='__main__':main()
