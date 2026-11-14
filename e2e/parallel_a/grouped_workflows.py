"""Group the existing required regression jobs without removing their steps, conditions or evidence uploads."""
from pathlib import Path
import argparse,re

ROOT=Path(__file__).resolve().parents[2]
TARGET=ROOT/'.github/workflows/parallel-a-runtime.yml'
BEGIN='# BEGIN GENERATED REQUIRED REGRESSION JOBS\n'
END='# END GENERATED REQUIRED REGRESSION JOBS\n'
SOURCES=('accomplishment-evidence','knowledge-apprenticeship','primitive-diagnostics','goal-planning',
         'observed-knowledge','world-retention','save-compatibility','blueprint-boundary','prerequisites')

def render():
    fragments=[];jobs=0;steps=0
    for name in SOURCES:
        text=(ROOT/'.github/workflows'/(name+'.yml')).read_text()
        before,fragment=text.split('\njobs:\n',1)
        top=set(re.findall(r'^([a-z_]+):',before,re.M))
        if top!={'name','on','permissions','concurrency'}:raise ValueError('Review new workflow-level semantics before grouping '+name+': '+str(top))
        permissions=re.search(r'(?m)^permissions:\n((?:  [^\n]*\n)+)',before)
        if not permissions or permissions.group(1)!='  contents: read\n':raise ValueError('Review changed permission scope before grouping '+name)
        ids=re.findall(r'^  ([a-zA-Z0-9_-]+):\s*$',fragment,re.M)
        if not ids:raise ValueError('No required jobs in '+name)
        mapping={key:'required_'+name.replace('-','_')+'_'+key.replace('-','_') for key in ids}
        for old,new in mapping.items():
            fragment=re.sub(r'^  '+re.escape(old)+r':\s*$',
                            '  '+new+':\n    permissions:\n      contents: read',fragment,flags=re.M)
            fragment=fragment.replace('needs.'+old+'.','needs.'+new+'.')
            for quote in ("'",'"'):fragment=fragment.replace('needs['+quote+old+quote+']','needs['+quote+new+quote+']')
        def needs(match):
            old=match.group(1).strip()
            if old not in mapping:raise ValueError('Review unsupported dependency form '+name+': '+old)
            return '    needs: '+mapping[old]
        fragment=re.sub(r'^    needs: ([^\n]+)$',needs,fragment,flags=re.M)
        # The grouped workflow owns run scheduling; every original job retains
        # its independent runner, timeout, setup, commands, conditions and uploads.
        fragments.append('  # Required jobs from '+name+'.yml; source remains unchanged.\n'+fragment.rstrip()+'\n')
        jobs+=len(ids);steps+=len(re.findall(r'^      - ',fragment,re.M))
    return BEGIN+'\n'.join(fragments)+END,jobs,steps

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--write',action='store_true');p.add_argument('--check',action='store_true');a=p.parse_args()
    expected,jobs,steps=render();text=TARGET.read_text()
    if BEGIN in text:
        before,remainder=text.split(BEGIN,1);_,after=remainder.split(END,1);candidate=before+expected+after
    else:candidate=text.rstrip()+'\n\n'+expected
    if a.write:TARGET.write_text(candidate)
    elif text!=candidate:raise SystemExit('Grouped regression jobs drifted from their original workflow sources; regenerate and review the complete diff')
    print('PARALLEL_A_GROUPED_TESTS_PRESERVED workflows='+str(len(SOURCES))+' jobs='+str(jobs)+' steps='+str(steps))

if __name__=='__main__':main()
