"""Run distinct frozen original H cases in one workflow; every failure and world is retained."""
import argparse,json,subprocess,sys
from pathlib import Path
from harvest_cases import LOG_H_CASE_IDS
from suite import ROOT,atomic,read

def case_root(root,case_id):
    return root if case_id=='heldout-W6-002' else root/'original-cases'/case_id

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,required=True);p.add_argument('--seed-archive',type=Path,required=True);a=p.parse_args()
    root=a.root.resolve();root.mkdir(parents=True,exist_ok=True);rows=[]
    for case_id in LOG_H_CASE_IDS:
        directory=case_root(root,case_id);directory.mkdir(parents=True,exist_ok=True)
        code=subprocess.run([sys.executable,str(ROOT/'e2e/parallel_a/harvest_native.py'),'--root',str(directory),'--seed-archive',str(a.seed_archive.resolve()),'--case-id',case_id],cwd=ROOT).returncode
        result=read(directory/'a06-harvest-results.json')
        rows.append({'id':case_id,'root':'.' if directory==root else directory.relative_to(root).as_posix(),'exitCode':code,'status':result.get('status') if result else 'NO_RESULT_REQUIRED'})
        atomic(root/'a06-original-h-scenarios.json',{'schema':1,'status':'AWAITING_REMAINING_CASES','plannedCases':list(LOG_H_CASE_IDS),'cases':rows,'originalCasesQualifiedBeforeIndependentReview':0})
    passed=all(r['exitCode']==0 and r['status']=='PASS_FIRST_H_PENDING_ARCHIVE_REVIEW' for r in rows)
    value={'schema':1,'status':'PASS_NATIVE_RUNS_PENDING_INDEPENDENT_REVIEW' if passed else 'FAIL','plannedCases':list(LOG_H_CASE_IDS),'cases':rows,'originalCasesQualifiedBeforeIndependentReview':0}
    atomic(root/'a06-original-h-scenarios.json',value);print('A06_ORIGINAL_H_NATIVE_BATCH '+json.dumps(value),flush=True)
    if not passed:raise SystemExit(1)
if __name__=='__main__':main()
