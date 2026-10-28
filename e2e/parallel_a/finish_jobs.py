"""Finish A04's five outstanding native checks from its exact saved72-Job cohort."""
from pathlib import Path
import argparse,hashlib,json,shutil,sys,traceback
sys.path.insert(0,str(Path(__file__).resolve().parent))
from jobs import JobHarness,crash_cut,corrupt_required,pinned_unchanged
from continuation import start as continue_world,CURRENT_PRODUCTION_SHA
from qualified_checkpoint import read_checkpoint,SOURCE,ARTIFACT,ARCHIVE_SHA
from suite import ROOT,atomic,read,require,reconnect_players
from baseline import native_process
from instrumentation_identity import same_jar_entries

def private_replay(value,prior):
    require(value['status']=='PASS' and not value['hot'] and value['hotUnchanged'],'Old native outcome is not stable archived history')
    require(all(value[k]==prior[k] for k in ('job','knowledge','accomplishments')) and value['knowledge']['allowed'],'Original authorized outcome/access changed')
    require(value['sameReceipt']['code']==value['cancel']['code']=='DUPLICATE' and value['changedReceipt']['code']==value['staleSubmission']['code']=='REJECTED','Original receipt/submission identity changed')

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--root',required=True,type=Path);parser.add_argument('--resume-archive',required=True,type=Path);args=parser.parse_args()
    # Harness roots are absolute; bind both sides to the same filesystem identity.
    args.root=args.root.resolve();args.resume_archive=args.resume_archive.resolve()
    args.root.mkdir(parents=True,exist_ok=True);h=None;result={'stage':'A04','status':'NOT_EXECUTED','cases':[]}
    try:
        # Validate the original source cohort before creating a server or a client.
        activation=read(Path(__file__).with_name('a04-qualified-continuation.json'))
        require(activation['inputSource']==SOURCE and activation['inputArtifact']==ARTIFACT and activation['inputSha256']==ARCHIVE_SHA and activation['productionJarSha256']==CURRENT_PRODUCTION_SHA,'Qualified continuation provenance changed')
        require(activation['inputOriginalQualification']=='FAIL' and activation['performanceIsCarriedEvidence'] and activation['performanceSource']==SOURCE,'Retained failure/performance attribution changed')
        result['activation']=activation
        prior=args.root.parent/('a04-prior-'+args.root.name)
        prepared=read_checkpoint(args.resume_archive,prior)
        instrumentation=same_jar_entries(prior/'server/game/mods/observer.jar',ROOT/'build/e2e-driver/observer.jar')
        require(hashlib.file_digest((ROOT/'fabric/build/libs/ai-villages-0.1.0.jar').open('rb'),'sha256').hexdigest()==CURRENT_PRODUCTION_SHA,'Byte-identical production JAR required')
        # The general continuation reader accepts this explicit, already checked input.
        def prepared_reader(archive,target):
            require(Path(target)==prior and Path(archive)==args.resume_archive,'Prepared cohort routing changed')
            return prepared
        h=JobHarness(args.root);h.flags(True)
        previous,continuation=continue_world(h,args.resume_archive,reader=prepared_reader)
        result['continuation']=continuation
        result['retainedPrefix']={'source':SOURCE,'artifact':ARTIFACT,'archiveSha256':ARCHIVE_SHA,'originalQualification':'FAIL','completedCases':5,'newClientProcessesAtRunBoundary':True,'retainedNativeDeliveries':69,'retainedLogicalJobs':72}
        for case in previous['priorResult']['cases']:
            result['cases'].append(dict(case,carriedSourceArtifact=ARTIFACT,carriedSource=SOURCE))
        for point in ('after_segment_ack','after_directory_ack'):
            shutil.copytree(prior/('cut-'+point),args.root/('cut-'+point))
        for mode in ('baseline','candidate'):
            for suffix in ('performance.json','raw-window.json'):shutil.copyfile(prior/(mode+'-'+suffix),args.root/(mode+'-'+suffix))
        for name in ('actual-old-job-wall.json','old-wall-native-jobs.jsonl'):shutil.copyfile(prior/name,args.root/name)
        continuous=next(c for c in result['cases'] if c['id']=='continuous-native-over-64-terminal-jobs')
        old_job=continuous['oldLookup']['job']['id'];pinned=continuous['pinned'];pinned_unchanged(h.stable(),pinned)
        inventory=h.wait(lambda:h.probe('inventory'),'Original72 native hot/archive identities after continuation',45);expected=previous['expectedInventory']
        require({j['id']:j for j in inventory['hot']['jobs']}=={j['id']:j for j in expected['hot']['jobs']} and {r['id']:r for r in inventory['archived']}=={r['id']:r for r in expected['archived']},'Continuation changed the complete original72-Job inventory')
        lookup=h.wait(lambda:h.lookup(old_job,replay=True,accomplishments=True),'Actual old private history and replay controls',45);private_replay(lookup,continuous['oldLookup'])
        denied=h.lookup(old_job,foreign=True);require(denied['status']=='DENIED','Foreign principal gained archived access')
        end=h.probe('end-window');process=native_process(h);old_cpu=read(prior/'candidate-performance.json')['jvm']['begin']
        require(process['containerResources']=={'cpusetCpus':'0-1','memoryBytes':2*1024**3},'Native CPU/memory allocation differs from the frozen prefix')
        require(end['jvm']['inputArguments']==old_cpu['inputArguments'] and end['jvm']['availableProcessors']==2 and end['jvm']['heap']['maxBytes']==old_cpu['heap']['maxBytes'],'JVM configuration differs from the frozen performance source')
        result['resumeChecks']={'inventory':inventory,'lookup':lookup,'foreign':denied,'nativeProcess':process,'jvm':end['jvm'],'instrumentationIdentity':instrumentation,'nativeEffects':0,'hotJobs':40,'archivedJobs':32}
        h.save_checkpoint();h.stop('server');world=args.root/'server/game/world';backup=args.root/'qualified-continuous-world';shutil.copytree(world,backup,ignore=shutil.ignore_patterns('session.lock'))
        h.start('server');reconnect_players(h,list(h.players));h.stable()
        for point in ('BEFORE_HOT_PUBLICATION','AFTER_HOT_ACKNOWLEDGEMENT'):
            result['cases'].append({'id':'native-job-cut-'+point.lower(),'status':'PASS','evidence':crash_cut(h,backup,point,old_job,pinned)})
        for kind in ('segment-missing','segment-corrupt','version-missing'):
            result['cases'].append({'id':'native-required-'+kind,'status':'PASS','evidence':corrupt_required(h,backup,kind,old_job,pinned)})
        require(len(result['cases'])==10 and all(c['status']=='PASS' for c in result['cases']),'A04 qualification matrix is incomplete')
        result['status']='PASS'
    except Exception as error:
        result.update(status='FAIL',reason=str(error),trace=traceback.format_exc())
        if h is not None:result['lastSnapshot']=h.snapshot()
        raise
    finally:
        if h is not None:h.finish()
        atomic(args.root/'a04-results.json',result)
        print('PARALLEL_A_A04_RESULT '+json.dumps({'status':result['status'],'reason':result.get('reason'),'retainedPrefix':result.get('retainedPrefix'),'cases':[{'id':c['id'],'status':c['status'],'carriedSourceArtifact':c.get('carriedSourceArtifact')} for c in result['cases']]}),flush=True)

if __name__=='__main__':main()
