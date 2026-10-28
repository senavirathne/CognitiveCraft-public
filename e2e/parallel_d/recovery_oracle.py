"""Independent metadata-file recovery oracle. Observer frames are not extra test cases."""
import hashlib,json
from common import *

def check_case(root,case):
    path=root/case/'observation.json';frame=read(path)
    require(frame['id']==case and frame['tier']=='METADATA_FILESYSTEM_ONLY','wrong recovery case/tier')
    require(case.split('-',1)[1]==frame['cut'],'swapped metadata cut')
    require(frame['before_reopen']==frame['after_reopen'],'reopen changed original metadata bytes')
    names=[]
    for f in frame['files']:
        require(f['path'] in {'metadata.current.json','metadata.previous.json','metadata.pending.json','metadata.previous.pending.json'},'unexpected recovery file')
        file=root/case/f['path'];names.append(f['path'])
        require(file.is_file() and sha(file)==f['sha256'] and file.stat().st_size==f['bytes'],'metadata recovery file hash changed')
        require(frame['after_reopen'][f['path']]==f['sha256'],'observation hash disagreement')
    require(len(names)==len(set(names)) and set(names)==set(frame['after_reopen']),'duplicate/missing recovery file')
    require('metadata.current.json' in names,'missing current metadata')
    current=read(root/case/'metadata.current.json');payload=current['payload']
    canon=lambda value:(json.dumps(value,sort_keys=True,ensure_ascii=False,separators=(',',':'))+'\n').encode()
    require(current['checksum_sha256']==hashlib.sha256(canon(payload)).hexdigest(),'current metadata checksum invalid')
    require(set(payload)=={'schema','world_id','revision','records','assignments','bindings'} and payload['schema']=='cc.storage-metadata/1','unexpected metadata payload')
    require(payload['world_id']=='7b2d1a66-d01d-4000-8000-000000000001','swapped metadata world')
    expected_revision=1 if frame['cut'] in ('REPLACED','DIRECTORY_FORCED','BEFORE_ACK') else 0
    require(payload['revision']==expected_revision and len(payload['records'])==expected_revision,'unexpected crash metadata revision/record count')
    for record in payload['records']:
        require(set(record)=={'storage_id','world_id','label','owner','roles','acl','stamp','acl_revision','state','adapter_id'},'unknown/missing storage fields')
        require(record['acl']==[] and record['state']=='PENDING_BIND','crash fixture gained authority/physical activation')
        require(record['world_id']==payload['world_id'] and record['owner']=={'kind':'PLAYER','id':'7b2d1a66-d01d-4000-8000-000000000002'},'swapped metadata principal/world')
    if case.startswith('kill-'):
        require(type(frame['process_exit'])is int and frame['process_exit']!=0,'missing killed-process exit')
        require('CC_D01_CUT '+frame['cut'] in (root/case/'child.log').read_text(),'missing actual child cut observation')
    else:require(frame['ack_status']=='IO_ERROR' and frame['process_exit']is None,'error cut falsely acknowledged')
    if frame['cut'] in ('PENDING_CREATED','PENDING_FORCED','PREVIOUS_COPIED'):require(frame['reopened_read_only']is True,'pending metadata auto-replayed')
    return {'id':case,'cut':frame['cut'],'current_revision':payload['revision'],'reopened_read_only':frame['reopened_read_only']}

def verify(root,cases):
    require({p.parent.name for p in root.glob('*/observation.json')}==set(cases),'missing/unregistered recovery observations')
    return [check_case(root,case) for case in cases]
