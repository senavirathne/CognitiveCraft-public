"""Build once and verify one A10 native JAR/instrument bundle. No gameplay qualification."""
import argparse,hashlib,json,os,shutil,stat,subprocess,sys
from pathlib import Path,PurePosixPath
from zipfile import ZipFile,ZIP_DEFLATED
import xml.etree.ElementTree as ET
from freeze_plan import MODULES,plan

ROOT=Path(__file__).resolve().parents[2]
PRODUCTION='fabric/build/libs/ai-villages-0.1.0.jar'
MANIFEST='build/parallel-a/a10-frozen-runtime.json'
def sha(path):
    with Path(path).open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
def write(path,value):
    Path(path).parent.mkdir(parents=True,exist_ok=True);Path(path).write_text(json.dumps(value,indent=2)+'\n')
def source():
    return {'source':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
            'tree':subprocess.check_output(['git','rev-parse','HEAD^{tree}'],cwd=ROOT,text=True).strip(),
            'dirty':bool(subprocess.check_output(['git','status','--porcelain'],cwd=ROOT))}
def production(path):
    with ZipFile(path) as jar:
        names=jar.namelist();assert len(names)==len(set(names)) and jar.testzip() is None
        assert not any('e2e' in n.lower() or n.startswith('org/junit/') or Path(n).name.startswith('RuntimeA') or 'JournalCostProbe' in n for n in names)
    return sha(path)
def native_units(jar,out,java=None,ecj=None):
    jar=Path(jar).resolve();out=Path(out).resolve();out.mkdir(parents=True,exist_ok=True)
    sdk=ROOT/'build/server-sdk';lock=json.loads((ROOT/'scripts/dependencies.json').read_text())
    sys.path.insert(0,str(ROOT/'scripts'));import headless
    junit=headless.download(lock['junit'],sdk)
    java=str(Path(java).resolve()) if java else headless.executable('java')
    javac=None if ecj else headless.executable('javac')
    classes=out/'test-classes';classes.mkdir(exist_ok=True);temporary=out/'tmp';temporary.mkdir(exist_ok=True)
    cp=[jar,junit,sdk/'minecraft-server.jar',*sorted((sdk/'libs').glob('*.jar'))]
    sources=[p for module in MODULES for p in sorted((ROOT/module/'src/test/java').rglob('*.java'))]
    args=['--release','25','-encoding','UTF-8','-proc:none','-d',str(classes),'-classpath',os.pathsep.join(map(str,cp)),*map(str,sources)]
    if ecj:args=['-25','-nowarn',*args[2:]]
    argfile=out/'tests.args';argfile.write_text('\n'.join('"'+s.replace('\\','\\\\').replace('"','\\"')+'"' for s in args))
    command=[java,'-jar',str(Path(ecj).resolve()),'@'+str(argfile)] if ecj else [javac,'@'+str(argfile)]
    subprocess.run(command,cwd=ROOT,check=True)
    counts={};commands=[];jar_hash=production(jar)
    for module,expected in MODULES.items():
        resources=ROOT/module/'src/test/resources'
        classpath=[classes,resources,*cp]
        command=[java,'-Djava.io.tmpdir='+str(temporary),'-cp',os.pathsep.join(map(str,classpath)),
            'org.junit.platform.console.ConsoleLauncher','execute','--scan-class-path='+str(classes),
            '--include-package=dev.aivillages.'+module,'--fail-if-no-tests','--reports-dir='+str(out/module),'--details=summary']
        cwd=ROOT/module if module!='fabric' else ROOT/'fabric/build/test-runtime';cwd.mkdir(parents=True,exist_ok=True)
        with (out/(module+'.log')).open('w') as log:run=subprocess.run(command,cwd=cwd,stdout=log,stderr=subprocess.STDOUT)
        cases=[c for p in (out/module).glob('TEST-*.xml') for c in ET.parse(p).getroot().iter('testcase')]
        count=len(cases);failed=[c.get('name') for c in cases if any(c.find(t) is not None for t in ('error','failure','skipped'))]
        counts[module]=count;commands.append({'module':module,'command':command,'cwd':str(cwd)})
        if run.returncode or failed or count!=expected:
            print((out/(module+'.log')).read_text()[-10000:]);raise AssertionError((module,count,expected,failed))
        assert sha(jar)==jar_hash
        print('A10_EXACT_JAR_UNIT '+json.dumps({'module':module,'tests':count,'jarSha256':jar_hash}),flush=True)
    value={'schema':1,'status':'PASS_ALL_MODULES_AGAINST_ONE_NATIVE_JAR',**source(),'productionSha256':jar_hash,
        'modules':counts,'total':sum(counts.values()),'failures':0,'errors':0,'skipped':0,'commands':commands,
        'javaVersion':subprocess.run([java,'-version'],text=True,capture_output=True,check=True).stderr.strip(),
        'productionClassDirectoriesOnClasspath':False,'claim':'Unit/compatibility/fault contracts, not native gameplay.'}
    write(out/'native-unit.json',value);return value
def export(out):
    identity=source();assert not identity['dirty'] and identity['source']==os.environ.get('GITHUB_SHA',identity['source'])
    unit=ROOT/'build/parallel-a/a10-native-unit/native-unit.json';u=json.loads(unit.read_text())
    assert u['status']=='PASS_ALL_MODULES_AGAINST_ONE_NATIVE_JAR' and u['modules']==MODULES
    assert u['source']==identity['source'] and u['tree']==identity['tree'] and u['productionSha256']==production(ROOT/PRODUCTION)
    fixed=[PRODUCTION,'fabric/build/libs/ai-villages-0.1.0-gametest.jar',
           'build/e2e-driver/client.jar','build/e2e-driver/observer.jar','build/parallel-a/observer-adaptation.json']
    paths=[ROOT/p for p in fixed]+[p for p in (ROOT/'build/needle').rglob('*') if p.is_file()]
    paths += [p for p in unit.parent.rglob('*') if p.is_file() and (p.suffix=='.xml' or p.name=='native-unit.json')]
    paths=sorted(set(paths));assert all(p.is_file() and not p.is_symlink() for p in paths) and len(paths)<1000
    files=[{'path':p.relative_to(ROOT).as_posix(),'bytes':p.stat().st_size,'sha256':sha(p),'mode':stat.S_IMODE(p.stat().st_mode)} for p in paths]
    manifest={'schema':1,'stage':'A10',**identity,'status':'FROZEN_BINARY_BUNDLE_NOT_GAMEPLAY_PASS',
        'productionSha256':u['productionSha256'],'files':files,'plan':plan(),'nativeUnits':{'total':u['total'],'modules':u['modules']}}
    write(ROOT/MANIFEST,manifest);out=Path(out);out.mkdir(parents=True,exist_ok=True)
    archive=out/'a10-frozen-runtime.zip'
    with ZipFile(archive,'w',ZIP_DEFLATED,compresslevel=3) as z:
        for p in paths:z.write(p,p.relative_to(ROOT).as_posix())
        z.write(ROOT/MANIFEST,MANIFEST)
    assert archive.stat().st_size<128*1024**2
    summary={'source':identity['source'],'tree':identity['tree'],'productionSha256':u['productionSha256'],'bundleSha256':sha(archive),'bundleBytes':archive.stat().st_size}
    write(out/'a10-bundle-identity.json',summary)
    if os.environ.get('GITHUB_OUTPUT'):
        with Path(os.environ['GITHUB_OUTPUT']).open('a') as f:f.write('native_jar_sha256='+summary['productionSha256']+'\nbundle_sha256='+summary['bundleSha256']+'\n')
    print('A10_FROZEN_BUNDLE '+json.dumps(summary),flush=True);return summary
def install(archive,expected_source,expected_jar,expected_bundle):
    archive=Path(archive);assert sha(archive)==expected_bundle and archive.stat().st_size<128*1024**2
    with ZipFile(archive) as z:
        names=z.namelist();assert len(names)==len(set(names)) and len(names)<1000 and z.testzip() is None
        manifest=json.loads(z.read(MANIFEST));identity=source()
        assert manifest['schema']==1 and manifest['source']==expected_source==identity['source']
        assert manifest['tree']==identity['tree'] and manifest['productionSha256']==expected_jar and not identity['dirty']
        files=manifest['files'];assert set(names)=={MANIFEST,*[f['path'] for f in files]}
        assert sum(f['bytes'] for f in files)<256*1024**2
        for row in files:
            p=PurePosixPath(row['path']);assert not p.is_absolute() and '..' not in p.parts
            assert row['path'].startswith(('build/needle/','build/e2e-driver/','build/parallel-a/','fabric/build/libs/'))
            info=z.getinfo(row['path']);assert not stat.S_ISLNK(info.external_attr>>16)
            body=z.read(row['path']);assert len(body)==row['bytes'] and hashlib.sha256(body).hexdigest()==row['sha256']
            target=ROOT/p;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(body);target.chmod(row['mode'])
        write(ROOT/MANIFEST,manifest)
    verify(expected_source,expected_jar);return manifest
def verify(expected_source,expected_jar):
    manifest=json.loads((ROOT/MANIFEST).read_text());identity=source()
    assert identity['source']==manifest['source']==expected_source and identity['tree']==manifest['tree'] and not identity['dirty']
    assert manifest['productionSha256']==production(ROOT/PRODUCTION)==expected_jar
    for row in manifest['files']:
        p=ROOT/row['path'];assert p.stat().st_size==row['bytes'] and sha(p)==row['sha256'],row['path']
    return manifest
def main():
    p=argparse.ArgumentParser();p.add_argument('operation',choices=('units','export','install','verify'));p.add_argument('--out',type=Path);p.add_argument('--jar',type=Path);p.add_argument('--java');p.add_argument('--ecj',type=Path)
    p.add_argument('--archive',type=Path);p.add_argument('--source');p.add_argument('--jar-sha256');p.add_argument('--bundle-sha256');a=p.parse_args()
    if a.operation=='units':native_units(a.jar or ROOT/PRODUCTION,a.out or ROOT/'build/parallel-a/a10-native-unit',a.java,a.ecj)
    elif a.operation=='export':export(a.out or ROOT/'build/parallel-a/a10-bundle')
    elif a.operation=='install':install(a.archive,a.source,a.jar_sha256,a.bundle_sha256)
    else:verify(a.source,a.jar_sha256)
if __name__=='__main__':main()
