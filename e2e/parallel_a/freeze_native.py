"""Run one unchanged historical gate entry point against the A10 frozen runtime."""
import argparse,importlib,json,os,sys,traceback
from pathlib import Path
from freeze_plan import CASES,MODULE_NAMES,ledger,plan
from freeze_bundle import verify,write

def results(root,profile):
    names=['a06-original-results.json','a06-controls-results.json'] if profile=='A06' else [profile.lower()+'-results.json']
    return [row for name in names if (root/name).is_file() for row in json.loads((root/name).read_text()).get('cases',[])]

def main():
    p=argparse.ArgumentParser();p.add_argument('--profile',choices=CASES,required=True);p.add_argument('--root',type=Path,required=True)
    p.add_argument('--seed-archive',type=Path,required=True);p.add_argument('--source',required=True);p.add_argument('--jar-sha256',required=True);a=p.parse_args()
    root=a.root.resolve();root.mkdir(parents=True,exist_ok=True)
    value={'schema':1,'stage':'A10','profile':a.profile,'source':a.source,'productionSha256':a.jar_sha256,
           'status':'NOT_EXECUTED_REQUIRED','plan':plan(),'omittedTasks':0,'omittedTests':0,'branchReady':False}
    failure=None
    try:
        frozen=verify(a.source,a.jar_sha256);write(root/'a10-frozen-runtime.json',frozen);value['tree']=frozen['tree']
        os.environ['COGNITIVECRAFT_A10_PROFILE']=a.profile;os.environ['COGNITIVECRAFT_A10_SEED']=str(a.seed_archive.resolve())
        sys.argv=[MODULE_NAMES[a.profile]+'.py','--root',str(root)]
        if a.profile in ('A06','A07','A08','A09'):sys.argv+=['--seed-archive',str(a.seed_archive.resolve())]
        if a.profile=='A05':sys.argv+=['--profile-first']
        importlib.import_module(MODULE_NAMES[a.profile]).main()
        verify(a.source,a.jar_sha256);value['status']='COLLECTED_PENDING_INDEPENDENT_REVIEW'
    except BaseException as error:
        failure=error;value.update(status='FAIL_REQUIRED_GATE',reason=str(error),trace=traceback.format_exc())
    finally:
        try:value['cases']=ledger(a.profile,results(root,a.profile))
        except Exception as error:
            value.update(status='FAIL_INVALID_DENOMINATOR',ledgerError=str(error));value['cases']=ledger(a.profile,[])
        value['requiredCases']=len(CASES[a.profile]);value['executedCases']=sum(r['collectionStatus']!='NOT_EXECUTED_REQUIRED' for r in value['cases'])
        write(root/'a10-collection.json',value)
        print('A10_PROFILE_COLLECTION '+json.dumps({k:v for k,v in value.items() if k not in ('plan','trace','cases')}),flush=True)
    if failure is not None:raise failure
    assert value['status']=='COLLECTED_PENDING_INDEPENDENT_REVIEW' and value['executedCases']==value['requiredCases']

if __name__=='__main__':main()
