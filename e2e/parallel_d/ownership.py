#!/usr/bin/env python3
"""Audit modes/blobs, ancestry, protected files, bytecode, docs parity and isolation."""
from __future__ import annotations
import argparse, hashlib, os, re, struct
from pathlib import Path
from common import *
from packaging import inspect_release

PREFIXES = tuple(f'{module}/src/{tier}/java/dev/aivillages/{module}/'+suffix+'/'
    for module in ('core','fabric') for tier in ('main','test')
    for suffix in (('kernel/storage','kernel/logistics') if module == 'core' else ('storage','logistics')))+('e2e/parallel_d/',)

def allowed(path):
    return path.startswith(PREFIXES) or re.fullmatch(r'\.github/workflows/parallel-d-[^/]+\.yml',path) is not None

def inventory(root, ref):
    out = {}
    for row in git('ls-tree','-r',ref,root=root).splitlines():
        info,path = row.split('\t'); mode,kind,blob = info.split(); out[path] = {'mode':mode,'sha':blob}
    return out

def check_changes(baseline,candidate,ci):
    for path, entry in baseline.items():
        require(path in candidate,'deleted protected file: '+path)
        if entry != candidate[path]:
            require(path in ci and entry['mode'] == candidate[path]['mode'],'protected baseline change: '+path)
    for path in candidate.keys()-baseline.keys(): require(allowed(path),'unowned new source path: '+path)

def check_compose(value):
    require(value['name'].startswith('cc-d-'),'unowned compose project')
    require(set(value['services']) == {'minecraft','client-a','client-b','tools'},'unfrozen compose service')
    require(set(value.get('networks',{})) == {'d-only'},'shared compose network')
    require(not value.get('volumes'),'external or shared named volume')
    for name, service in value['services'].items():
        require(not service.get('privileged') and not service.get('build'),'unfrozen container authority/build')
        require(service.get('network_mode') != 'host','host network is shared')
        require(not service.get('container_name'),'globally shared container name')
        expected = {
            'minecraft':['${CC_D_REPO:?Set CC_D_REPO}:/repo:ro','${CC_D_RUN_ROOT:?Set CC_D_RUN_ROOT}/server:/evidence/server:rw'],
            'client-a':['${CC_D_REPO:?Set CC_D_REPO}:/repo:ro','${CC_D_RUN_ROOT:?Set CC_D_RUN_ROOT}/clients/PlayerD_A:/evidence/clients/PlayerD_A:rw'],
            'client-b':['${CC_D_REPO:?Set CC_D_REPO}:/repo:ro','${CC_D_RUN_ROOT:?Set CC_D_RUN_ROOT}/clients/PlayerD_B:/evidence/clients/PlayerD_B:rw'],
            'tools':['${CC_D_REPO:?Set CC_D_REPO}:/repo:ro','${CC_D_RUN_ROOT:?Set CC_D_RUN_ROOT}:/evidence:rw']}
        require(service.get('volumes') == expected[name],'unowned/shared volume: '+name)
    for client in ('client-a','client-b'):
        require(value['services'][client]['network_mode'] == 'service:minecraft','client outside isolated D network namespace')
    require(value['services']['minecraft']['networks'] == ['d-only'],'server network changed')

def class_utf8(path):
    data=path.read_bytes(); require(data[:4] == b'\xca\xfe\xba\xbe','invalid class file')
    count=struct.unpack_from('>H',data,8)[0]; offset=10; index=1; strings=[]
    lengths={3:4,4:4,5:8,6:8,7:2,8:2,9:4,10:4,11:4,12:4,15:3,16:2,17:4,18:4,19:2,20:2}
    while index<count:
        tag=data[offset];offset+=1
        if tag == 1:
            length=struct.unpack_from('>H',data,offset)[0];offset+=2
            strings.append(data[offset:offset+length]);offset+=length
        else:
            require(tag in lengths,'unknown constant-pool tag');offset+=lengths[tag]
            if tag in (5,6):index+=1
        index+=1
    return strings

def core_boundary(root=REPO,require_classes=False):
    forbidden=re.compile(rb'(net[./]minecraft|net[./]fabricmc|dev[./]aivillages[./]fabric)')
    for source in (root/'core/src/main/java').rglob('*.java'):
        require(not re.search(rb'(?m)^\s*import\s+(?:static\s+)?(?:net\.minecraft|net\.fabricmc|dev\.aivillages\.fabric)',source.read_bytes()),'core native import: '+str(source))
    classes=list((root/'core/build/classes/java/main').rglob('*.class'))
    require(classes or not require_classes,'core class files missing')
    for path in classes:
        require(not any(forbidden.search(s) for s in class_utf8(path)),'core native bytecode dependency: '+str(path))
    return len(classes)

def docs_parity(root):
    contract_check()
    for p in (HERE/'contracts').rglob('*'):
        if p.is_file(): require((root/'docs/implementation/parallel-d/contracts'/p.relative_to(HERE/'contracts')).read_bytes() == p.read_bytes(),'source/docs contract bytes differ')

def audit(base=BASE,docs_base=DOCS_BASE,docs_root=None,require_built=False):
    require(base == BASE and docs_base == DOCS_BASE,'floating or unknown baseline')
    git('merge-base','--is-ancestor',base,'HEAD')
    require(not git('rev-list','--min-parents=2',base+'..HEAD'),'peer/A merge introduced')
    manifest=HERE/'ci-freeze/trigger-patch-manifest.json'; require(sha(manifest) == CI_MANIFEST,'CI freeze changed')
    ci=read(manifest); require(sha(HERE/'ci-freeze/minimal-push-triggers.patch') == ci['patch_sha256'],'CI patch changed')
    baseline=inventory(REPO,base);candidate=inventory(REPO,'HEAD')
    check_changes(baseline,candidate,{r['path'] for r in ci['files']})
    for path,entry in baseline.items():
        p=REPO/path;require(p.is_file() and not p.is_symlink(),'protected file removed/type changed: '+path)
        actual=hashlib.sha256(p.read_bytes()).hexdigest()
        if path in {r['path'] for r in ci['files']}:
            frozen=next(r for r in ci['files'] if r['path']==path)
            require(actual == frozen['after_sha256'],'unfrozen workflow bytes: '+path)
            require(hashlib.sha256(subprocess.check_output(['git','show',base+':'+path],cwd=REPO)).hexdigest() == frozen['before_sha256'],'unfrozen workflow base')
        else:
            blob=hashlib.sha1(b'blob '+str(p.stat().st_size).encode()+b'\0'+p.read_bytes()).hexdigest()
            require(blob == entry['sha'],'protected working bytes changed: '+path)
        require(('100755' if p.stat().st_mode & 0o111 else '100644') == entry['mode'],'protected mode changed: '+path)
    for line in git('ls-files','--others','--exclude-standard').splitlines(): require(allowed(line),'unowned untracked file: '+line)
    contract_check(); check_compose(read(HERE/'compose.yml'))
    classes=core_boundary(require_classes=require_built)
    for p in (REPO/'core/src/main/java/dev/aivillages/core/kernel/storage').rglob('*.java'):
        require(not re.search(r'dev\.aivillages\.(?:core\.kernel\.interaction|fabric|providers\.interaction)',p.read_text()),'peer/native import')
    if require_built:inspect_release(RELEASE)
    docs_pin=None
    if docs_root:
        docs_root=Path(docs_root);git('merge-base','--is-ancestor',docs_base,'HEAD',root=docs_root)
        before,after=inventory(docs_root,docs_base),inventory(docs_root,'HEAD')
        require(all(after.get(p)==v for p,v in before.items()),'protected documentation changed')
        require(all(p.startswith('docs/implementation/parallel-d/') for p in after.keys()-before.keys()),'unowned new docs')
        docs_parity(docs_root);docs_pin=git('rev-parse','HEAD',root=docs_root)
    return {'scope':'D00_OWNERSHIP','protected_files':len(baseline),'core_class_files':classes,'docs_sha':docs_pin,'contract_sha256':CONTRACT}

if __name__ == '__main__':
    p=argparse.ArgumentParser();p.add_argument('--base',default=BASE);p.add_argument('--docs-base',default=DOCS_BASE)
    p.add_argument('--docs-root',type=Path);p.add_argument('--contract-sha256',default=CONTRACT)
    p.add_argument('--require-built',action='store_true');p.add_argument('--output',type=Path)
    a=p.parse_args();require(a.contract_sha256==CONTRACT,'wrong external contract digest')
    result=audit(a.base,a.docs_base,a.docs_root,a.require_built)
    if a.output:write(a.output,result)
    print(json.dumps(result,sort_keys=True))
