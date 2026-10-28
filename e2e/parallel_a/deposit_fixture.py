"""Explicit synthetic metadata pressure, never saved native effect evidence or a capability."""
import copy,hashlib,json,uuid

def canonical(value):
    return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()

def tick_window(raw,begin,count):
    if not raw or raw.get('process')!=begin['observerProcess'] or raw.get('time',0)<begin['time']:return None
    selected=[s for s in raw['samples'] if begin['gameTick']<s['gameTick']<=begin['gameTick']+count]
    if len(selected)<count:return None
    if [s['gameTick'] for s in selected]!=list(range(begin['gameTick']+1,begin['gameTick']+count+1)):
        raise ValueError('A05 raw game-clock interval is duplicated, missing or out of order')
    return selected

def pressure_journal(original_bytes,original_intent):
    head,payload=original_bytes.split(b'\n',1);header=json.loads(head);world=header['world']
    if hashlib.sha256(payload).hexdigest()!=header['sha256']:raise ValueError('Original pressure journal checksum')
    original_rows=[json.loads(line) for line in payload.splitlines()]
    matches=[row['intent'] for row in original_rows if row['intent']['key']==original_intent['key']]
    if len(matches)!=1:raise ValueError('Pressure fixture requires one exact original journal intent')
    # The observer's record rendering differs from the strict disk codec. Clone the
    # actual canonical native journal body; never infer that body from Gson fields.
    original_intent=matches[0]
    if original_intent['key']['world']!=world:raise ValueError('Synthetic pressure must retain its exact world')
    namespace=uuid.UUID(header['epoch'])
    identity=lambda label:str(uuid.uuid5(namespace,'A05_SYNTHETIC_PRESSURE_ONLY/'+label))
    root,attempt=identity('root'),identity('attempt');rows=[]
    for n in range(64):
        intent=copy.deepcopy(original_intent)
        intent['key'].update(root=root,step=identity('step/'+str(n)),attempt=attempt,generation=1,ordinal=n+1)
        intent['run']=identity('run/'+str(n));intent['operation']=identity('operation/'+str(n))
        intent['actor']['address']=identity('absent-actor/'+str(n));intent['actor']['incarnation']=identity('absent-actor-incarnation/'+str(n))
        intent['chest']['address']=str(1000+n)+',208,1000';intent['chest']['incarnation']=identity('absent-chest-incarnation/'+str(n))
        rows.append({'schema':1,'intent':intent})
    payload=b''.join(canonical(row)+b'\n' for row in rows)
    header.update(revision=header['revision']+1,records=64,sha256=hashlib.sha256(payload).hexdigest())
    encoded=canonical(header)+b'\n'+payload
    if len(encoded)>4_194_304:raise ValueError('Synthetic pressure exceeded the original byte quota')
    return encoded,{'kind':'SYNTHETIC_METADATA_ONLY','nativeEffects':0,'physicalReceipts':0,'executableCapabilities':0,
                    'world':world,'epoch':header['epoch'],'entries':64,'revision':header['revision'],'sha256':hashlib.sha256(encoded).hexdigest()}
