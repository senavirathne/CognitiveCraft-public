"""Independently review each exact original saved H owner, then count unique frozen cases."""
import argparse,json,traceback
from pathlib import Path
from harvest_cases import LOG_H_CASE_IDS,select_case,summarize
from review_harvest_current import review

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,required=True);p.add_argument('--repo',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--source',required=True);a=p.parse_args()
    a.out.mkdir(parents=True,exist_ok=True);manifest=json.loads((a.repo/'e2e/parallel_a/a06_original_cases.json').read_text())
    plan=json.loads((a.root/'a06-original-h-scenarios.json').read_text());assert plan['plannedCases']==list(LOG_H_CASE_IDS)
    reviews=[];failures=[]
    for case_id in LOG_H_CASE_IDS:
        directory=a.root if case_id=='heldout-W6-002' else a.root/'original-cases'/case_id
        try:
            select_case(manifest,case_id)
            value=review(directory,a.repo,a.out/case_id,a.source,case_id=case_id);reviews.append(value)
            assert value['status']=='PASS_FIRST_H_REVIEWED_ONLY'
        except Exception as error:failures.append({'id':case_id,'reason':str(error),'trace':traceback.format_exc()})
    if failures:
        value={'schema':1,'status':'FAIL_REQUIRED_ORIGINAL_CASE_REVIEW','failures':failures,'reviewedCases':[r['caseId'] for r in reviews],
               'individuallyPassingCases':[r['caseId'] for r in reviews if r['status']=='PASS_FIRST_H_REVIEWED_ONLY'],'a06Qualified':False}
    else:
        assert plan['status']=='PASS_NATIVE_RUNS_PENDING_INDEPENDENT_REVIEW'
        value=summarize(reviews,manifest);value.update(source=a.source,reviews={r['caseId']:{'key':r['key'],'releaseSha256':r['releaseSha256'],'observerSha256':r['observerSha256']} for r in reviews})
    (a.out/'original-cases-review.json').write_text(json.dumps(value,indent=2)+'\n')
    print('A06_DISTINCT_ORIGINAL_CASES_REVIEW '+json.dumps(value),flush=True)
    if failures:raise SystemExit(1)
if __name__=='__main__':main()
