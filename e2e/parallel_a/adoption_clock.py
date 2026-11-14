"""Hold only the additional read-only P adoption cohort after actual saved proof."""
import hashlib,json,uuid
from pathlib import Path
from recovery_fixture import marker

REQUEST='adoption-cohort-clock.json'
RECEIPT='adoption-cohort-clock-reached.json'

def request(root,case,proof):
    assert case['id']=='W6-003' and case['checkpoint']=='PICKUP'
    assert proof['status']=='PASS_INDEPENDENT_NATIVE_PICKUP'
    entry=proof['entry'];key=entry['intent']['key']
    assert key['ordinal']==2 and entry['result']['live']=='COMPLETE'
    value={'schema':1,'id':str(uuid.uuid4()),'case':case['id'],'key':key,'expected':marker(entry),
           'scope':'Read-only adoption re-kill only, after original physical P cut; no work or credit.'}
    path=Path(root)/'server'/REQUEST
    assert not path.exists();path.write_text(json.dumps(value,indent=2)+'\n')
    return value

def check(value,expected,native):
    assert value['schema']==1 and value['request']==expected
    process=native['namespaceProcess']
    assert process['kind']=='server'
    assert value['pid']==process['pid'] and value['time']>=process['start']*1000
    assert value['commands']==[{'command':'tick freeze','result':1}]
    assert value['key']==expected['key'] and value['marker']==expected['expected']
    assert value['classification']=='SAVED_COMPLETE'
    job=value['originalJob'];assert job['id']==expected['key']['step'] and job['generation']==expected['key']['generation']
    assert job['state']=='INTERRUPTED' and job['attempts'][-1]['uncertain']
    assert job['attempts'][-1]['id']==expected['key']['attempt']
    return value

def witnessed(h,expected,native):
    from suite import read,atomic
    value=h.wait(lambda:(v if (v:=read(h.root/'server'/RECEIPT)) and v['request']['id']==expected['id']
                         and v['pid']==native['namespaceProcess']['pid'] and v['time']>=native['namespaceProcess']['start']*1000 else None),
                 'Actual saved P cohort held for read-only adoption',25)
    check(value,expected,native)
    def captured():
        raw=(h.root/'server/snapshot.json').read_bytes();snapshot=json.loads(raw)
        return (raw,snapshot) if snapshot['time']>=value['time'] else None
    raw,snapshot=h.wait(captured,'Actual current native snapshot of held adoption clock',25)
    assert snapshot['pid']==value['pid'] and snapshot['gameTick']==value['gameTick']
    digest=hashlib.sha256(raw).hexdigest();path=h.root/'adoption-clock-snapshots'/(digest+'.json')
    path.parent.mkdir(exist_ok=True);path.write_bytes(raw)
    value={**value,'heldSnapshot':{'path':path.relative_to(h.root).as_posix(),'sha256':digest}}
    h.event('native-read-only-adoption-clock',value)
    return value

def check_snapshot(root,value):
    path=Path(value['heldSnapshot']['path'])
    assert not path.is_absolute() and '..' not in path.parts
    raw=(Path(root)/path).read_bytes();assert hashlib.sha256(raw).hexdigest()==value['heldSnapshot']['sha256']
    snapshot=json.loads(raw)
    assert snapshot['pid']==value['pid'] and snapshot['time']>=value['time']
    assert snapshot['gameTick']==value['gameTick']
    return snapshot

def release(h):
    from suite import read
    path=h.root/'server'/REQUEST
    held=read(h.root/'server'/RECEIPT)
    assert path.is_file();path.unlink()
    h.console(['tick unfreeze','tick rate 20'],lifecycle=True)
    request=read(h.root/'server/control.json');ack=read(h.root/'server/control-ack.json')
    assert request['commands']==['tick unfreeze','tick rate 20'] and ack['id']==request['id']
    snapshot=h.wait(lambda:(v if (v:=h.snapshot()) and v['gameTick']>held['gameTick'] else None),
                    'Ordinary physical clock resumed before original cancellation',20)
    return {'request':request,'ack':ack,'snapshot':snapshot,'beforeOriginalCancellation':True}

def cleanup(root):
    (Path(root)/'server'/REQUEST).unlink(missing_ok=True)
