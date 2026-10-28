"""A06 preliminary real-client receipt retention only; does not qualify original-owner native settlement."""
import argparse,json,traceback
from pathlib import Path
from deposits import DepositHarness,positive,cut,actual_stock
from deposit_seed import start
from receipt_prefix_fixture import retained_prefix
from suite import atomic,read,require

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--root',required=True,type=Path);parser.add_argument('--seed-archive',required=True,type=Path)
    args=parser.parse_args();args.root=args.root.resolve();args.root.mkdir(parents=True,exist_ok=True)
    h=None;result={'stage':'A06_RECEIPT_RETENTION','status':'NOT_EXECUTED','cases':[],
        'scope':'actual receipt retention before D, native saved D custody and refusal to infer original-owner settlement; original twelve-case qualification NOT_EXECUTED',
        'activation':{'COGNITIVECRAFT_DEPOSIT_EVIDENCE':True,'COGNITIVECRAFT_RESOURCE_RECEIPT_PREFIX':True,'recordSchema':2,'originalOwnerNativeEvidenceSource':'AMBIGUOUS'},
        'nativeA06OriginalOwnerSettlements':0,'originalTwelveCases':'NOT_EXECUTED','nativeSourceDropLineage':'NOT_PROVED'}
    try:
        h=DepositHarness(args.root);h.activated=0;h.env['A06_RECEIPT_PREFIX_ENABLED']='true'
        seed,result['seedContinuation']=start(h,args.seed_archive.resolve())
        saved,verified,entry,token=positive(h,result)
        published=read(h.root/'positive-live-result.json')['view']['resourceReceipts']
        result['cases'][-1]['actualPrefix']=retained_prefix(entry,[r for r in published if r['stage']!='DEPOSIT'])
        require(entry['result']['receipt']==next(r for r in published if r['stage']=='DEPOSIT'),'Current D receipt differs from actual gateway publication')
        cut(h,seed,result,'saved-original-receipt-prefix','RESULT_ACKNOWLEDGED',{'SAVED_COMPLETE'},savePrepared=True)
        case=result['cases'][-1];native=case['restoredEvidence'];entry=native['journal']['entries'][0]
        case['actualPrefix']=retained_prefix(entry)
        key=entry['intent']['key'];run=case['run']
        job=h.wait(lambda:(v if (v:=h.run_view('PlayerA',run)) and v.get('responsibility') else None),'Original native interrupted Job',45)['responsibility']
        require(job['id']==key['step'] and job['state']=='INTERRUPTED' and job['attempts'][-1]['uncertain'],'Receipt prefix falsely settled an unproved original owner')
        require(job['attempts'][-1]['id']==key['attempt'] and job['generation']==key['generation'],'Prefix retention changed original attempt/generation')
        case['originalJobAfterRestart']=job
        case['publicCancellation']=h.command('PlayerA','/aivillage kernel job-cancel '+job['id'],'job='+job['id']+' cancellation=true')
        def cancelling():
            view=h.run_view('PlayerA',run)
            return view['responsibility'] if view and view.get('responsibility',{}).get('state')=='CANCELLING' else None
        before=h.wait(cancelling,'Original public cancellation durably pending',35);physical=actual_stock(h)
        h.save_checkpoint();case['cancellationRestart']=h.restart(hard=True);after=h.wait(cancelling,'Original unproved owner stays cancelling after another real kill',45)
        again=h.wait(h.stable_entry,'Exact receipt prefix after cancellation restart',45)
        require(again['journal']['entries'][0]==entry,'Repeated restart changed retained original evidence')
        require(after['attempts'][-1]['id']==key['attempt'] and after['attempts'][-1]['uncertain'],'Original attempt was replaced or falsely certain')
        h.quiet(40);later=actual_stock(h)
        require(physical['destination']==later['destination']==1 and physical['actor']['inventory']==later['actor']['inventory']=={},'Receipt-only cancellation replayed a physical effect')
        case.update(originalJobBeforeCancellationRestart=before,originalJobAfterCancellationRestart=after,physicalAfterCancellationRestart=later,
                    originalOwnerSettled=False,retainedPrefixCannotManufactureNativeProof=True)
        result['status']='PASS_RECEIPT_RETENTION_ONLY_PENDING_INDEPENDENT_REVIEW'
    except Exception as error:
        result.update(status='FAIL',reason=str(error),trace=traceback.format_exc())
        if h is not None:result['lastEvidence']=h.evidence();result['lastSnapshot']=h.snapshot()
        raise
    finally:
        if h is not None:h.finish()
        atomic(args.root/'a06-prefix-results.json',result)
        print('PARALLEL_A_A06_PREFIX_RESULT '+json.dumps({'status':result['status'],'reason':result.get('reason'),'completed':[v['id'] for v in result['cases']]}),flush=True)

if __name__=='__main__':main()
