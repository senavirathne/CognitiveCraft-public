"""Bind every A10 profile and grouped regression job; external standard CI still required."""
import argparse,json
from pathlib import Path
from freeze_plan import CASES,REQUIRED_JOBS,plan
from review_freeze import modules
from freeze_bundle import write,sha

def aggregate(root,source,jar,needs):
    reviews={}
    for path in root.rglob('review.json'):
        value=json.loads(path.read_text())
        if value.get('stage')!='A10':continue
        profile=value['profile'];assert profile in CASES and profile not in reviews
        assert value['status']=='PASS_INDEPENDENT_A10_PROFILE_REVIEW' and value['source']==source and value['productionSha256']==jar
        assert value['requiredCases']==len(CASES[profile]) and [r['id'] for r in value['cases']]==list(CASES[profile])
        assert all(r['status']=='PASS_ORIGINAL_CLASSIFICATION_RETAINED' for r in value['cases'])
        reviews[profile]={'reviewSha256':sha(path),'tree':value['tree'],'cases':value['requiredCases'],
            'observerSha256':value['observerSha256'],'clientSha256':value['clientSha256']}
    assert set(reviews)==set(CASES) and len({r['tree'] for r in reviews.values()})==1
    assert len({r['observerSha256'] for r in reviews.values()})==len({r['clientSha256'] for r in reviews.values()})==1
    assert needs and all(v['result']=='success' for v in needs.values()),needs
    required=[k for k in needs if k.startswith('required_')];assert set(required)==set(REQUIRED_JOBS)
    return {'schema':1,'stage':'A10','status':'PASS_OWNED_CAMPAIGN_PENDING_STANDARD_AND_HANDOFF','source':source,'productionSha256':jar,
            'tree':next(iter(reviews.values()))['tree'],'plan':plan(),'profiles':reviews,'requiredGateCases':sum(v['cases'] for v in reviews.values()),
            'requiredRegressions':required,'branchReady':False,'remaining':['Exact-event standard CI independent XML review','Offline full archive replay and final documented handoff'],
            'omittedTasks':0,'omittedTests':0,'workflowCountPerCandidate':2,'mainMerged':False,'peerBranchMerged':False}

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--source',required=True);p.add_argument('--jar-sha256',required=True)
    p.add_argument('--needs',required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
    value=aggregate(a.root,a.source,a.jar_sha256,json.loads(a.needs));write(a.out,value);print(json.dumps(value),flush=True)

if __name__=='__main__':main()
