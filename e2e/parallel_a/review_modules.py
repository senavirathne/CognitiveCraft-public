"""Independent complete JUnit XML accounting for the frozen A08 source; no native claim."""
import argparse,json,os,subprocess
from pathlib import Path
import xml.etree.ElementTree as ET

def main():
    p=argparse.ArgumentParser();p.add_argument('--stage',choices=('A08','A09'),default='A08');a=p.parse_args()
    modules={}
    for name,expected in (('core',2400 if a.stage=='A08' else 2438),('providers',131),('fabric',130 if a.stage=='A08' else 131)):
        files=sorted((Path(name)/'build/test-results/test').glob('TEST-*.xml'))
        assert files,('No XML',name)
        cases=[c for file in files for c in ET.parse(file).getroot().iter('testcase')]
        assert len(cases)==expected,(name,len(cases),expected)
        assert all(c.find(tag) is None for c in cases for tag in ('failure','error','skipped')),name
        modules[name]={'cases':len(cases),'classes':len(files),'failures':0,'errors':0,'skipped':0}
    value={'schema':1,'stage':a.stage,'status':'PASS_TARGET_MODULE_XML_ONLY','source':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
           'tree':subprocess.check_output(['git','rev-parse','HEAD^{tree}'],text=True).strip(),'modules':modules,'total':sum(v['cases'] for v in modules.values()),
           'nativeQualified':False,'javaHome':os.environ.get('JAVA_HOME')}
    out=Path('build/parallel-a/source-audit/'+a.stage.lower()+'-modules.json');out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(value,indent=2)+'\n')
    print(a.stage+'_MODULE_XML '+json.dumps(value),flush=True)
if __name__=='__main__':main()
