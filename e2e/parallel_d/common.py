"""Immutable D00 inputs and bounded evidence utilities."""
from __future__ import annotations
import hashlib, json, os, subprocess, sys
sys.dont_write_bytecode = True
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
BASE = '12fb3af99e0c9496171b89d4dd2ffff3f5c7a824'
DOCS_BASE = '9864841ff76d867fba2ccdab87199d3556d19f22'
CONTRACT = 'e1e97de64c8c8934d51b47bba143a1fccb49e9fedb8882380fc5cdd3fe008cb3'
SCHEMA = '99901b31634957fa8d1c3ebeaa668a5ab4297139a98a015bde81f7e5b89c6589'
CI_MANIFEST = '5d687fc1502a583336383785ec136eeab0c1c9e7fd9aae681f0cda2ad62825bc'
RELEASE = REPO / 'fabric/build/libs/ai-villages-0.1.0.jar'

def require(value, reason):
    if not value: raise ValueError(reason)

def sha(path):
    with Path(path).open('rb') as stream: return hashlib.file_digest(stream,'sha256').hexdigest()

def no_duplicates(pairs):
    out = {}
    for key, value in pairs:
        require(key not in out, 'duplicate JSON key: '+key); out[key] = value
    return out

def read(path):
    return json.loads(Path(path).read_text(),object_pairs_hook=no_duplicates)

def write(path, value):
    path = Path(path); path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(value,ensure_ascii=False,sort_keys=True,indent=2)+'\n')

def git(*args, root=REPO):
    return subprocess.check_output(['git','-C',str(root),*args],text=True).strip()

def registry(stage):
    rows = read(HERE/'case-registry.json')
    require(stage in rows, 'unknown or unimplemented stage: '+stage)
    return rows[stage]

def contract_check(root=HERE/'contracts'):
    require(sha(root/'SHA256SUMS.json') == CONTRACT,'contract manifest changed')
    require(sha(root/'contract.schema.json') == SCHEMA,'contract schema changed')
    expected = read(root/'SHA256SUMS.json')['files']
    require({str(p.relative_to(root)) for p in root.rglob('*') if p.is_file()} == set(expected)|{'SHA256SUMS.json'},'contract file set changed')
    for name, digest in expected.items(): require(sha(root/name) == digest,'contract bytes changed: '+name)
    require(len(read(root/'fixtures-index.json')['fixtures']) == 57,'fixture denominator changed')

def source_identity():
    require(not git('status','--porcelain'),'candidate must be clean and committed')
    source, tree = git('rev-parse','HEAD'),git('rev-parse','HEAD^{tree}')
    require(not os.getenv('GITHUB_SHA') or os.environ['GITHUB_SHA'] == source,'workflow SHA mismatch')
    return {'source_sha':source,'tree_sha':tree,'jar_sha256':sha(RELEASE),
            'workflow_run':int(os.environ['GITHUB_RUN_ID']) if os.getenv('GITHUB_RUN_ID') else None,
            'attempt':int(os.environ['GITHUB_RUN_ATTEMPT']) if os.getenv('GITHUB_RUN_ATTEMPT') else None}
