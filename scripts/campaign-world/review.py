#!/usr/bin/env python3
"""Read immutable prior artifacts by ranges and write a bounded, separately qualified review.

The original complete archives are never changed. Duplicate server histories and unselected
native world/cache files stay in those archives. Every copied file retains CRC and SHA-256.
"""
import base64
from collections import Counter
import hashlib
import json
from pathlib import Path,PurePosixPath
import re
import urllib.request
import uuid
import zipfile

from extract import api,RangeReader,ROOT

RUNS={
    40076986301:('1ae31f09114e41edb61f41a9dede1b0a040b5269',12555009116,
        'sha256:e13956b88af83f6c6102260457f032a59384bcc588de538a91081bd433ad9df5',120822353338),
    40085523505:('526c183ea65523744fc7997737790868253fb04b',12558960549,
        'sha256:59a83f91474d8cadee3cfecf68b0fcd2e31b41abac86f4ae3b8379f3b35ddbbd',120847543411),
    40088063035:('cc47f4b680bc6bd5f07d1ff8ebe5f876039f7564',12558308802,
        'sha256:82d5d88006e6a1198653c21cf62da477dd999878fa2dcc6e0da86a2729f7a36b',120861143983),
}
OUT=ROOT/'build/e2e/generalization-world/review'
BASELINE='ea7de8000b2b484b885576c34ed5358274936ce9'


def write(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(value,indent=2)+'\n')


def read_json(archive,name,limit=8*1024**2):
    try: info=archive.getinfo(name)
    except KeyError: return None
    if info.file_size>limit: raise RuntimeError('Review JSON exceeds its explicit size bound: '+name)
    with archive.open(info) as stream: return json.load(stream)


def runtime_prefix(archive,expected):
    found=[]
    for info in archive.infolist():
        if not info.filename.endswith('generalization-world/metadata.json'): continue
        value=read_json(archive,info.filename)
        if value and value.get('sourceCommit')==expected:
            found.append(info.filename[:-len('metadata.json')])
    if not found:
        for info in archive.infolist():
            if not info.filename.endswith('generalization-world/plan.json'): continue
            value=read_json(archive,info.filename)
            if value and value.get('ci',{}).get('GITHUB_SHA')==expected:
                found.append(info.filename[:-len('plan.json')])
    if len(found)!=1: raise RuntimeError('Exactly one source-pinned runtime or blocked plan required')
    return found[0]


def qualification(record,before,after,view,log,admitted_hash=None):
    c=record['case']; raw=record['status']; req=record.get('request',{})
    reason=(view.get('outcome') or {}).get('reason') or (view.get('marker') or {}).get('reason')
    reason=reason or record.get('failureReason') or req.get('interpretation',{}).get('reason')
    match=re.search(r'reason=([A-Z_]+)',req.get('feedback',''))
    if not reason and match: reason=match.group(1)
    if 'STORAGE_LIMIT_REACHED' in str(record.get('reason','')): reason='STORAGE_LIMIT_REACHED'
    if 'reason=STORAGE_LIMIT_REACHED nativeStarted=false' in log: reason='STORAGE_LIMIT_REACHED'
    qualified=raw; genuine=record.get('genuineGameplay',False); notes=[]
    if reason=='STORAGE_LIMIT_REACHED':
        qualified='FAIL_ARCHITECTURAL_GAP'; genuine=False
        notes.append('Persistent capacity refusal; no physical generalization credit. Raw verdict preserved.')
    props=record.get('properties',{})
    counter_ok=(props.get('noInference') is False and before and after and
        (before.get('brokerStats') or {}).get('calls')==(after.get('brokerStats') or {}).get('calls') and
        before.get('needleCalls')==after.get('needleCalls') and (view.get('marker') or {}).get('modelCalls')==0 and
        admitted_hash and (view.get('marker') or {}).get('artifactSha256')==admitted_hash)
    if counter_ok:
        notes.append('Retained-marker counter changed while broker and interpreter counters stayed constant.')
        other={k:v for k,v in props.items() if k!='noInference'}
        if other and all(other.values()) and raw=='FAIL_GENERALIZATION': qualified='PASS'
    accounting=record.get('accounting',{})
    if (c.get('geometry')=='vertical' and c.get('quantity')==4 and not c.get('standingSupports') and
        reason=='TARGET_UNAVAILABLE' and accounting.get('harvestedBlocks')==3 and
        accounting.get('destinationDelta')==3):
        qualified='EXPECTED_SAFE_REFUSAL'
        notes.append('Old unsupported top-log reach fixture; three lower logs delivered and highest log retained.')
    if "'NoneType' object is not iterable" in str(record.get('reason','')):
        qualified='INCONCLUSIVE'; genuine=False
        notes.append('Harness iterated an explicitly None actor pool before native death test admission.')
    if raw=='INFRASTRUCTURE_BLOCKED' and c['family'] in ('W7','W11','W12'):
        notes.append('Third launch failed during signed artifact range download with HTTP 403; generic build-failure label is inaccurate.')
    resource_rows=view.get('receipts') or view.get('resourceReceipts') or []
    usage=(view.get('responsibility') or {}).get('usage')
    return {'case':c['id'],'family':c['family'],'seed':c['seed'],'rawStatus':raw,
        'qualifiedStatus':qualified,'rawGenuineGameplay':record.get('genuineGameplay',False),
        'qualifiedGenuineGameplay':genuine,'nativeReason':reason,'qualifications':notes,
        'nativeEffectsObserved':accounting.get('harvestedBlocks',0)>0 or accounting.get('destinationDelta',0)>0,
        'counterEvidence':{'retainedBefore':before.get('generationCalls'),'retainedAfter':after.get('generationCalls'),
            'brokerBefore':(before.get('brokerStats') or {}).get('calls'),'brokerAfter':(after.get('brokerStats') or {}).get('calls')},
        'methodSha256':(view.get('marker') or {}).get('artifactSha256'),'usage':usage,
        'receiptSequence':[r.get('stage') for r in resource_rows],'accounting':accounting,
        'reason':record.get('reason'),'firstDivergence':record.get('firstDivergence'),
        'traceLast':record.get('trace','').splitlines()[-8:]}


def inspect_journal(path,data):
    if not path.endswith('state.jsonl'): return None
    if '/leases/v1/' not in path and '/jobs/v1/' not in path: return None
    try: rows=[json.loads(line) for line in data.decode().splitlines() if line]
    except (ValueError,UnicodeError): return None
    return {'path':path,'records':len(rows),'header':rows[0] if rows else None,
        'rowKinds':dict(Counter(str(r.get('kind',r.get('type','row'))) for r in rows))}


def review(run,expected,artifact,digest,job):
    metadata=api('/actions/artifacts/'+str(artifact))
    if (metadata['workflow_run']['id']!=run or metadata['workflow_run']['head_sha']!=expected or
        metadata['digest']!=digest or metadata['expired']): raise RuntimeError('Prior immutable artifact identity mismatch')
    def signed(): return api('/actions/artifacts/'+str(artifact)+'/zip?campaign_nonce='+str(uuid.uuid4()),redirect=True)
    remote=RangeReader(signed(),metadata['size_in_bytes'],refresh=signed)
    directory=OUT/str(run); directory.mkdir(parents=True,exist_ok=True)
    manifest={'sourceArtifact':metadata,'integrity':'HTTPS plus per-member ZIP CRC and extracted SHA-256; original complete artifact unchanged',
        'retained':[],'omitted':[],'journals':[]}
    with zipfile.ZipFile(remote) as archive:
        prefix=runtime_prefix(archive,expected)
        result=read_json(archive,prefix+'results.json',32*1024**2)
        if not result or result.get('baseline')!=BASELINE: raise RuntimeError('Prior results do not match frozen production')
        records=result['records']; selected_cases=set()
        admitted={r['item']:r['view']['marker']['artifactSha256'] for r in
            (read_json(archive,prefix+'admissions.json') or []) if r.get('status')=='PASS'}
        for record in records:
            if record['status'].startswith('FAIL_') and len(selected_cases)<28: selected_cases.add(record['case']['id'])
        for family in sorted({r['case']['family'] for r in records}):
            for status in ('PASS','EXPECTED_SAFE_REFUSAL','INCONCLUSIVE'):
                row=next((r for r in records if r['case']['family']==family and r['status']==status),None)
                if row: selected_cases.add(row['case']['id'])
        selected_cases.update(('W5-002','W5-003','W5-007','W10-000','probe-W1-001-q1','probe-W1-001-q2'))
        qualified=[]
        for record in records:
            case=record['case']['id']; base=prefix+'cases/'+case+'/'
            view=read_json(archive,base+'run.json') or {}
            before=read_json(archive,base+'before.json') or {}
            after=read_json(archive,base+'after.json') or {}
            log=''
            try:
                if archive.getinfo(base+'server.log').file_size<4*1024**2:
                    log=archive.read(base+'server.log').decode(errors='replace')
            except KeyError: pass
            value=qualification(record,before,after,view,log,admitted.get(record['case']['item'])); qualified.append(value)
            print('WORLD_REVIEW_CASE '+json.dumps({'run':run,**value},separators=(',',':')),flush=True)
        write(directory/'qualified-cases.json',qualified)
        def choose(info):
            name=info.filename
            if not name.startswith(prefix) or name.endswith('/'): return False,'Other artifact runtime or directory'
            relative=name[len(prefix):]; parts=PurePosixPath(relative).parts
            if '..' in parts or PurePosixPath(relative).is_absolute(): raise RuntimeError('Unsafe evidence member')
            if info.file_size>16*1024**2: return False,'Bounded local review file size; full original retained'
            if relative.startswith('cases/'):
                if '/crashed-world/' in relative or '/durable-checkpoint/' in relative:
                    return False,'Native checkpoint remains in complete original archive'
                case=parts[1]; basename=parts[-1]
                if basename=='observations.jsonl': return False,'Complete focused trajectory remains in original; selected raw states and native logs copied'
                if basename in ('before.json','after.json','failure-state.json','client-before.json','client-after.json'):
                    return case in selected_cases,'Selected native state detail'
                return basename.endswith(('.json','.jsonl','.log','.txt')),'Per-case raw result, request, native log or client events'
            if '/data/cognitivecraft/' in relative and any('/'+kind+'/v1/' in relative for kind in ('leases','jobs','skills','citizens','bootstrap')):
                return parts[-1] not in ('writer.lock','.writer.lock'),'Actual native state and admitted IR'
            if relative.startswith('screenshots/') and relative.endswith('.png'): return True,'Original native frame'
            if '/' not in relative and relative.endswith(('.json','.txt','.md')): return True,'Run envelope'
            return False,'Duplicate process history, native world or reproducible runtime; complete original retained'
        for info in sorted(archive.infolist(),key=lambda i:i.header_offset):
            keep,why=choose(info)
            if not info.filename.startswith(prefix): continue
            row={'path':info.filename[len(prefix):],'bytes':info.file_size,'zipCRC32':info.CRC,'reason':why}
            if not keep:
                manifest['omitted'].append(row); continue
            path=directory/row['path']; path.parent.mkdir(parents=True,exist_ok=True)
            part=path.with_name(path.name+'.extracting'); checksum=hashlib.sha256()
            with archive.open(info) as source,part.open('wb') as target:
                while block:=source.read(1024**2): target.write(block); checksum.update(block)
            part.replace(path); row['sha256']=checksum.hexdigest(); manifest['retained'].append(row)
            if row['path'].endswith('state.jsonl'):
                journal=inspect_journal(row['path'],path.read_bytes())
                if journal: manifest['journals'].append(journal)
        if run==40076986301:
            for case in ('W1-008','W2-003','W4-010'):
                captures=read_json(archive,prefix+'cases/'+case+'/screenshots.json') or []
                if isinstance(captures,dict): captures=captures.get('captures',[])
                if captures:
                    frame=captures[-1]; frame_name=prefix+frame['path']
                    try: frame_info=archive.getinfo(frame_name)
                    except KeyError: continue
                    if frame_info.file_size<=300000:
                        data=archive.read(frame_name)
                        print('WORLD_REVIEW_FRAME '+json.dumps({'run':run,'case':case,'path':frame['path'],
                            'sha256':hashlib.sha256(data).hexdigest(),'pngBase64':base64.b64encode(data).decode()},separators=(',',':')),flush=True)
        summary={'run':run,'sourceCommit':expected,'artifact':artifact,'digest':digest,'requested':len(records),
            'rawCounts':dict(Counter(r['status'] for r in records)),
            'qualifiedCounts':dict(Counter(r['qualifiedStatus'] for r in qualified)),
            'rawGenuine':sum(r.get('genuineGameplay',False) for r in records),
            'qualifiedGenuine':sum(r['qualifiedGenuineGameplay'] for r in qualified),
            'nativeEffectfulPipelineCases':sum(r['nativeEffectsObserved'] for r in qualified),
            'retainedBytes':sum(r['bytes'] for r in manifest['retained']),
            'rangeBytesTransferred':remote.transferred,'journals':manifest['journals']}
        write(directory/'review-summary.json',summary)
        write(directory/'review-manifest.json',manifest)
        print('WORLD_REVIEW_SUMMARY '+json.dumps(summary,separators=(',',':')),flush=True)
        return summary


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    summaries=[]
    for run,(expected,artifact,digest,job) in RUNS.items(): summaries.append(review(run,expected,artifact,digest,job))
    write(OUT/'index.json',{'frozenProduction':BASELINE,'runs':summaries,
        'qualification':'Raw evidence remains immutable. Review qualifications are separate and retrospective.'})


if __name__=='__main__': main()
