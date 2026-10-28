#!/usr/bin/env python3
"""Bound repository reads; retry only dependency failures before any Gradle task executes."""
import argparse, re, subprocess, sys, time
from common import *

def dependency_failure(log):
    return not re.search(r'> Task\s',log) and bool(re.search(r'Could not (?:download|find|get resource|resolve)',log))

def run(root):
    root.mkdir(parents=True,exist_ok=True)
    command=['bash','gradlew','--no-daemon','--no-build-cache','--stacktrace',
        '-Dorg.gradle.internal.http.connectionTimeout=30000','-Dorg.gradle.internal.http.socketTimeout=60000',
        '-I','e2e/parallel_d/native/fixture.init.gradle',':core:test',':providers:test',':fabric:test',':fabric:build',':fabric:gameTestJar',':fabric:parallelDNativeJar']
    attempts=[]
    for ordinal in range(1,4):
        path=root/f'gradle-attempt-{ordinal}.log';started=time.monotonic()
        with path.open('w')as log:
            process=subprocess.Popen(command,cwd=REPO,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
            for line in process.stdout:log.write(line);print(line,end='',flush=True)
            code=process.wait()
        output=path.read_text();retry=bool(code and dependency_failure(output) and ordinal<3)
        attempts.append({'ordinal':ordinal,'exit':code,'seconds':time.monotonic()-started,'log':path.name,'sha256':sha(path),'pre_task_dependency_retry':retry})
        write(root/'build-attempts.json',{'source_sha':git('rev-parse','HEAD'),'command':command,'attempts':attempts})
        if code==0:return
        if not retry:raise SystemExit(code)
        print('Retrying the unchanged dependency configuration; no Gradle task executed.',flush=True)
        time.sleep(2)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);run(p.parse_args().root)
