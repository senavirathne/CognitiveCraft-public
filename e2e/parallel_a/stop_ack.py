"""Read-only acknowledgement of one exact owned Docker server incarnation."""
import json,re

def container(rows,identifier,project):
    if not re.fullmatch('[0-9a-f]{64}',identifier) or len(rows)!=1:raise ValueError('Exactly one owned container ID required')
    row=rows[0]
    if row['Id']!=identifier or row['Config']['Labels']['com.docker.compose.project']!=project:
        raise ValueError('Foreign server container refused')
    if row['Config']['Labels']['com.docker.compose.service']!='minecraft':raise ValueError('Server service required')
    return {'id':identifier,'project':project,'startedAt':row['State']['StartedAt'],
            'running':row['State']['Running'],'pid':row['State']['Pid'],
            'status':row['State']['Status'],'exitCode':row['State']['ExitCode'],
            'oomKilled':row['State']['OOMKilled']}

def acknowledge(before,after,waited,process,hard):
    if (before['id'],before['project'],before['startedAt'])!=(after['id'],after['project'],after['startedAt']):
        raise ValueError('Container restarted before stop acknowledgement')
    if after['running'] or after['pid']!=0 or after['status']!='exited' or after['oomKilled']:
        raise ValueError('Native container has not completed its intended stop')
    if not re.fullmatch('-?[0-9]+',waited) or int(waited)!=after['exitCode']:raise ValueError('Docker wait exit acknowledgement mismatch')
    if not process or process['kind']!='server':raise ValueError('Original native server process required')
    return {'schema':1,'status':'PASS_EXACT_CONTAINER_EXIT_ACKNOWLEDGED','before':before,'after':after,
            'process':process,'hard':hard,'dockerWaitTimeoutSeconds':20,'newProcessStarted':False}

def review(root,profile,jar,stops):
    rows=[json.loads(p.read_text()) for p in root.glob('a10-stop-ack-*.json')]
    assert rows,'Missing exact native container stop acknowledgements'
    for row in rows:
        assert row['schema']==1 and row['profile']==profile and row['status']=='PASS_EXACT_CONTAINER_EXIT_ACKNOWLEDGED'
        assert row['dockerWaitTimeoutSeconds']==20 and row['newProcessStarted'] is False
        value=acknowledge(row['before'],row['after'],str(row['after']['exitCode']),row['process'],row['hard'])
        assert value['status']==row['status'] and row['process']['releaseSha256']==jar
    covered={(r['process']['pid'],r['process']['start']) for r in rows}
    assert all((p['pid'],p['start']) in covered for p in stops),'Unacknowledged stopped native incarnation'
    return {'status':'PASS_ALL_STOPPED_NATIVE_CONTAINERS_ACKNOWLEDGED','records':len(rows),'nativeBirths':len(covered)}
