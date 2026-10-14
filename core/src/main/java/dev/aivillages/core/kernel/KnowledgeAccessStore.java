package dev.aivillages.core.kernel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.KnowledgeAccess.*;
import static dev.aivillages.core.kernel.Outcomes.*;

/** Sole learning/access writer. Eligibility reads immutable state without taking the storage monitor. */
public final class KnowledgeAccessStore implements AttemptEligibilityPolicy,AutoCloseable {
    public static final String WORLD_RELATIVE_PATH=KnowledgeAccessJournal.PATH;
    public interface Catalog {
        Decision usable(ArtifactRef method);
        List<ArtifactRef> closure(ArtifactRef method);
        List<ArtifactRef> admittedMethods();
    }
    @FunctionalInterface public interface TeachingSharing {
        boolean permits(Enrollment teacher,Enrollment learner,ArtifactRef method);
    }
    public static Catalog repositoryCatalog(VersionedSkillRepository repository){
        return new Catalog(){
            public Decision usable(ArtifactRef ref){
                var view=repository.resolve(ref);
                if(view==null)return Decision.deny(Reason.ARTIFACT_INVALID);
                if(view.admission().status()==AdmissionStatus.QUARANTINED)return Decision.deny(Reason.ARTIFACT_QUARANTINED);
                if(view.admission().status()!=AdmissionStatus.ADMITTED)return Decision.deny(Reason.KNOWLEDGE_REQUIRED);
                if(view.compatibility().status()!=CompatibilityStatus.COMPATIBLE)return Decision.deny(Reason.ARTIFACT_INCOMPATIBLE);
                return view.usable()?Decision.allow():Decision.deny(Reason.ARTIFACT_INVALID);
            }
            public List<ArtifactRef> closure(ArtifactRef ref){return JobArtifactPins.closure(repository.find(ref).orElseThrow(),repository);}
            public List<ArtifactRef> admittedMethods(){
                var view=repository.metadataSnapshot();if(!view.revision().available())throw new IllegalStateException("Unavailable migration catalog");
                var result=view.next(128).stream().filter(c->c.admission()==AdmissionStatus.ADMITTED).map(VersionedSkillRepository.Candidate::ref).toList();
                if(!view.complete())throw new IllegalStateException("Incomplete migration catalog");return result;
            }
        };
    }
    private final KnowledgeAccessJournal journal;
    private final KnowledgeAccessSource source;
    private final Catalog catalog;
    private final Limits limits;
    private final Clock clock;
    private final RetentionRoots roots;
    private final TeachingSharing sharing;
    private final List<VersionTransfer> versions;
    private final Set<UUID> lost=ConcurrentHashMap.newKeySet();
    private final Set<UUID> recovered=ConcurrentHashMap.newKeySet();
    private Predicate<WorldRetentionManager.Amount> capacity=a->true;
    private KnowledgeAccessStore(KnowledgeAccessJournal journal,KnowledgeAccessSource source,Catalog catalog,Limits limits,
                                 Clock clock,RetentionRoots roots,TeachingSharing sharing,List<VersionTransfer> versions){
        this.journal=journal;this.source=Objects.requireNonNull(source);this.catalog=Objects.requireNonNull(catalog);
        this.limits=Objects.requireNonNull(limits);this.clock=Objects.requireNonNull(clock);this.roots=Objects.requireNonNull(roots);
        this.sharing=Objects.requireNonNull(sharing);this.versions=List.copyOf(versions);
        if(versions.size()>16)throw new IllegalArgumentException("Version migration bound");
        journal.snapshot().practices().stream().filter(p->p.stage()==Stage.PRACTICING).map(Practice::job).forEach(recovered::add);
    }
    public static KnowledgeAccessStore open(Path world,UUID id,KnowledgeAccessSource source,Catalog catalog,Clock clock,RetentionRoots roots)throws IOException{
        return open(world,id,source,catalog,clock,roots,Limits.defaults(),(t,l,m)->false,List.of(),p->{});
    }
    public static KnowledgeAccessStore openUnmigrated(Path world,UUID id,KnowledgeAccessSource source,Catalog catalog,Clock clock,RetentionRoots roots)throws IOException{
        return open(world,id,source,catalog,clock,roots,Limits.defaults(),(t,l,m)->false,List.of(),p->{},false);
    }
    static KnowledgeAccessStore open(Path world,UUID id,KnowledgeAccessSource source,Catalog catalog,Clock clock,RetentionRoots roots,
                                     Limits limits,TeachingSharing sharing,List<VersionTransfer> versions,KnowledgeAccessJournal.Faults faults)throws IOException{
        return open(world,id,source,catalog,clock,roots,limits,sharing,versions,faults,true);
    }
    private static KnowledgeAccessStore open(Path world,UUID id,KnowledgeAccessSource source,Catalog catalog,Clock clock,RetentionRoots roots,
                                     Limits limits,TeachingSharing sharing,List<VersionTransfer> versions,KnowledgeAccessJournal.Faults faults,boolean migrate)throws IOException{
        var journal=KnowledgeAccessJournal.open(world,id,faults);
        var owner=new KnowledgeAccessStore(journal,source,catalog,limits,clock,roots,sharing,versions);
        try{
            var update=roots.publishNext("knowledge-access",protectedRoots(journal.snapshot()),!journal.readOnly());
            if(update!=RetentionRoots.Update.APPLIED&&update!=RetentionRoots.Update.UNCHANGED)throw new IOException("Access recovery roots unavailable");
            if(migrate&&!journal.readOnly()&&!journal.snapshot().migrated()){
                var result=owner.migrateSharedAccess(()->false);
                if(result.code()!=Code.APPLIED)throw new IOException("Shared access migration: "+result.code());
            }
            return owner;
        }catch(IOException|RuntimeException failure){journal.close();throw failure;}
    }
    public Snapshot snapshot(){return journal.snapshot();}
    public boolean readOnly(){return journal.readOnly();}
    public KnowledgeAccessSource source(){return source;}
    public synchronized void capacity(Predicate<WorldRetentionManager.Amount> policy){capacity=Objects.requireNonNull(policy);}
    private static String hash(CapabilityRequest request){return KnowledgeAccessCodec.sha(RequestCodec.encode(request).getBytes(StandardCharsets.UTF_8));}
    private Enrollment enrollment(ActorRef actor,TrustedContext caller){
        if(actor==null||caller==null||!caller.scope().worldId().equals(snapshot().world()))return null;
        var value=source.enrollment(actor);return value!=null&&value.owner().equals(caller)?value:null;
    }
    public Decision evaluateUse(ActorRef actor,ArtifactRef method,TrustedContext caller){
        if(readOnly()||!snapshot().migrated())return Decision.deny(Reason.STORAGE_UNAVAILABLE);
        var enrolled=enrollment(actor,caller);if(enrolled==null)return Decision.deny(Reason.AUTHORITY_DENIED);
        var available=catalog.usable(method);if(!available.allowed())return available;
        var state=snapshot();
        boolean known=!state.personalCitizens().contains(enrolled)&&state.legacyMethods().contains(method)
                ||state.grants().stream().anyMatch(g->g.learner().equals(enrolled)&&g.method().equals(method));
        return known?Decision.allow():Decision.deny(Reason.KNOWLEDGE_REQUIRED);
    }
    @Override public boolean mayUse(ActorRef actor,ArtifactRef method,TrustedContext caller){return evaluateUse(actor,method,caller).allowed();}
    public Decision evaluateTeaching(ActorRef teacher,ActorRef learner,ArtifactRef method,TrustedContext caller){
        var student=enrollment(learner,caller);var mentor=source.enrollment(teacher);
        if(student==null||mentor==null||!mentor.owner().scope().worldId().equals(caller.scope().worldId())
                ||!mentor.owner().equals(caller)&&!sharing.permits(mentor,student,method))return Decision.deny(Reason.AUTHORITY_DENIED);
        if(teacher.equals(learner)||!source.present(mentor,student))return Decision.deny(Reason.ACTOR_UNAVAILABLE);
        var use=evaluateUse(teacher,method,mentor.owner());if(!use.allowed())return use;
        if(!source.qualified(mentor,method))return Decision.deny(Reason.KNOWLEDGE_REQUIRED);
        for(var ref:catalog.closure(method))if(!mayUse(teacher,ref,mentor.owner()))return Decision.deny(Reason.KNOWLEDGE_REQUIRED);
        return Decision.allow();
    }
    /** A frozen cartesian reference policy preserves absent legacy rows, including renamed citizens. */
    public synchronized Result migrateSharedAccess(BooleanSupplier cancel){
        if(readOnly())return result(Code.UNAVAILABLE,Reason.STORAGE_UNAVAILABLE,null,0,0);
        var old=snapshot();if(old.migrated())return result(Code.DUPLICATE,null,null,0,0);
        try{
            var citizens=source.citizens();if(citizens==null||!citizens.worldId().equals(old.world()))return result(Code.INVALID,Reason.REQUEST_INVALID,null,0,0);
            var enrolled=citizens.citizens().stream().map(c->new Enrollment(c.actor(),c.owner())).toList();
            var methods=catalog.admittedMethods();if(methods.size()>128)return result(Code.LIMIT,Reason.STORAGE_LIMIT_REACHED,null,0,0);
            if(enrolled.size()+methods.size()>limits.work())return result(Code.LIMIT,Reason.BUDGET_EXHAUSTED,null,limits.work(),0);
            return publish(old,new Snapshot(old.world(),Math.addExact(old.revision(),1),true,enrolled,methods,List.of(),List.of(),List.of()),null,enrolled.size()+methods.size(),cancel);
        }catch(RuntimeException failed){return result(Code.UNAVAILABLE,Reason.STORAGE_UNAVAILABLE,null,0,0);}
    }
    /** Explicit opt-in for a newly enrolled apprentice. Grandfathered citizens cannot forget known methods. */
    public synchronized Result requirePersonalLearning(ActorRef actor,TrustedContext caller,BooleanSupplier cancel){
        var enrolled=enrollment(actor,caller);var old=snapshot();
        if(enrolled==null)return result(Code.DENIED,Reason.AUTHORITY_DENIED,null,0,0);
        if(old.legacyCitizens().contains(enrolled))return result(Code.DENIED,Reason.KNOWLEDGE_REQUIRED,null,0,0);
        if(old.personalCitizens().contains(enrolled))return result(Code.DUPLICATE,null,null,0,0);
        if(old.personalCitizens().size()==64)return result(Code.LIMIT,Reason.STORAGE_LIMIT_REACHED,null,0,0);
        var personal=new ArrayList<>(old.personalCitizens());personal.add(enrolled);
        return publish(old,new Snapshot(old.world(),Math.addExact(old.revision(),1),true,old.legacyCitizens(),old.legacyMethods(),personal,old.grants(),old.practices()),null,personal.size(),cancel);
    }
    /** Jobs must have acknowledged a fresh practice request; this call never assigns or executes it. */
    public synchronized Result startApprenticeship(UUID jobId,ActorRef teacher,ArtifactRef method,TrustedContext caller,BooleanSupplier cancel){
        if(readOnly())return result(Code.UNAVAILABLE,Reason.STORAGE_UNAVAILABLE,jobId,0,0);
        var old=snapshot();var existing=old.practices().stream().filter(p->p.job().equals(jobId)).findFirst().orElse(null);
        if(existing!=null)return existing.learner().owner().equals(caller)&&existing.teacher().actor().equals(teacher)&&existing.method().equals(method)
                ?result(Code.DUPLICATE,null,jobId,0,0):result(Code.DENIED,Reason.AUTHORITY_DENIED,jobId,0,0);
        var job=source.job(jobId);
        if(job==null||!job.origin().equals(caller))return result(Code.DENIED,Reason.AUTHORITY_DENIED,jobId,0,0);
        if(job.current()!=null||job.state()!=Jobs.State.READY||!job.dependencies().isEmpty()
                ||!job.request().request().capability().equals(CropDelivery.ID)||!method.capability().equals(CropDelivery.ID)
                ||clock.millis()>job.allowance().deadlineEpochMillis())return result(Code.INVALID,Reason.REQUEST_INVALID,jobId,0,0);
        ActorRef learner=((ActorValue)job.request().request().arguments().get("actor")).value();
        var teaching=evaluateTeaching(teacher,learner,method,caller);
        if(!teaching.allowed())return result(Code.DENIED,teaching.reason(),jobId,0,0);
        if(mayUse(learner,method,caller))return result(Code.DUPLICATE,null,jobId,0,0);
        if(old.practices().size()>=limits.history()||old.practices().stream().filter(p->p.stage()==Stage.PRACTICING).count()>=limits.active())
            return result(Code.LIMIT,Reason.STORAGE_LIMIT_REACHED,jobId,0,0);
        try{
            var pinned=catalog.closure(method);int work=old.practices().size()+pinned.size()+1;
            if(work>limits.work())return result(Code.LIMIT,Reason.BUDGET_EXHAUSTED,jobId,limits.work(),0);
            var practice=new Practice(jobId,source.enrollment(teacher),enrollment(learner,caller),method,pinned,
                    hash(job.request().request()),Math.addExact(job.generation(),1),job.allowance().deadlineEpochMillis(),Stage.PRACTICING,"");
            var history=new ArrayList<>(old.practices());history.add(practice);
            return publish(old,new Snapshot(old.world(),Math.addExact(old.revision(),1),true,old.legacyCitizens(),old.legacyMethods(),old.personalCitizens(),old.grants(),history),jobId,work,cancel);
        }catch(RuntimeException invalid){return result(Code.INVALID,Reason.REQUEST_INVALID,jobId,0,0);}
    }
    private Practice practice(UUID job){return snapshot().practices().stream().filter(p->p.job().equals(job)).findFirst().orElse(null);}
    private boolean valid(Practice p){
        if(p==null||p.stage()!=Stage.PRACTICING||recovered.contains(p.job())||lost.contains(p.job())||readOnly())return false;
        var job=source.job(p.job());
        boolean valid=job!=null&&job.origin().equals(p.learner().owner())&&hash(job.request().request()).equals(p.requestSha256())
                &&clock.millis()<=p.deadline()&&!job.state().terminal()&&job.state()!=Jobs.State.CANCELLING
                &&source.present(p.teacher(),p.learner())&&evaluateTeaching(p.teacher().actor(),p.learner().actor(),p.method(),p.learner().owner()).allowed()
                &&p.pinned().stream().allMatch(ref->catalog.usable(ref).allowed());
        if(valid&&job.current()!=null)valid=job.current().generation()==p.generation()&&job.current().worker().equals(p.learner().actor())
                &&job.current().executions().stream().anyMatch(e->e.artifact().equals(p.method()));
        if(valid&&job.current()==null)valid=job.generation()==p.generation()-1;
        if(!valid)lost.add(p.job());return valid;
    }
    /** Only the dispatcher holding this actual Job may use temporary eligibility during resolution. */
    public EligibilityPolicy policyForJob(UUID job){return (actor,method,caller)->{
        var p=practice(job);
        if(p!=null)return p.learner().actor().equals(actor)&&p.learner().owner().equals(caller)&&p.pinned().contains(method)&&valid(p);
        return mayUse(actor,method,caller);
    };}
    private Practice practiceForRun(RunCorrelation run){
        for(var p:snapshot().practices()){
            var job=source.job(p.job());if(job!=null&&job.current()!=null&&job.current().executions().stream().anyMatch(e->e.runId().equals(run.runId())))return p;
        }
        return null;
    }
    @Override public boolean mayUseInRun(ValidatedRequest request,RunCorrelation run,ArtifactRef method){
        var p=practiceForRun(run);
        if(p==null)return mayUse(((ActorValue)request.request().arguments().get("actor")).value(),method,request.context());
        var job=source.job(p.job());return valid(p)&&p.learner().owner().equals(request.context())&&p.method().equals(run.artifact())
                &&hash(request.request()).equals(p.requestSha256())&&p.pinned().contains(method)
                &&job.current().executions().stream().anyMatch(e->e.runId().equals(run.runId())&&e.artifact().equals(run.artifact()));
    }
    @Override public boolean mayContinue(ValidatedRequest request,RunCorrelation run,Set<ArtifactRef> pinned){
        return pinned.stream().allMatch(ref->mayUseInRun(request,run,ref));
    }
    /** Latch a observed loss; returning teacher control cannot revive this attempt. */
    public void checkSupervision(){for(var p:snapshot().practices())if(p.stage()==Stage.PRACTICING){
        var job=source.job(p.job());if(job!=null&&!job.state().terminal())valid(p);
        else if(!source.present(p.teacher(),p.learner()))lost.add(p.job());
    }}
    /** No supplied success flags or quantities: only acknowledged Jobs and the evidence owner. */
    public synchronized Result recordLearningOutcome(UUID jobId,TrustedContext caller,BooleanSupplier cancel){
        var p=practice(jobId);if(p==null)return result(Code.INVALID,Reason.REQUEST_INVALID,jobId,0,0);
        if(!p.learner().owner().equals(caller))return result(Code.DENIED,Reason.AUTHORITY_DENIED,jobId,0,0);
        if(p.stage()!=Stage.PRACTICING)return result(Code.DUPLICATE,null,jobId,0,0);
        var work=new JobOutcomeSource.Work(limits.work());var old=snapshot();
        try{
            var job=source.verified(jobId,work);Reason stopped=null;
            if(recovered.contains(jobId))stopped=Reason.INTERRUPTED;
            else if(lost.contains(jobId)||!source.present(p.teacher(),p.learner())
                    ||!evaluateTeaching(p.teacher().actor(),p.learner().actor(),p.method(),caller).allowed())stopped=Reason.KNOWLEDGE_REQUIRED;
            else if(clock.millis()>p.deadline())stopped=Reason.BUDGET_EXHAUSTED;
            else if(job.current()!=null&&job.current().generation()!=p.generation())stopped=Reason.INTERRUPTED;
            else if(job.current()!=null&&job.current().terminal()!=null&&job.current().terminal().status()!=ExecutionStatus.SUCCEEDED)
                stopped=job.current().terminal().reason()==null?Reason.ACTION_FAILED:job.current().terminal().reason();
            else if(job.state().terminal()&&job.state()!=Jobs.State.SUCCEEDED)stopped=job.reason()==null?Reason.ACTION_FAILED:job.reason();
            if(stopped==null&&(!job.state().terminal()||!source.learned(job,p.method(),p.learner().actor(),work)))return result(Code.PENDING,null,jobId,work.count,0);
            var grants=new ArrayList<>(old.grants());
            if(stopped==null){
                if(!hash(job.request().request()).equals(p.requestSha256())||!catalog.usable(p.method()).allowed())return result(Code.INVALID,Reason.ARTIFACT_INCOMPATIBLE,jobId,work.count,0);
                var attempt=job.current();var run=attempt.executions().stream().filter(e->e.artifact().equals(p.method())).findFirst().orElseThrow(JobOutcomeSource.Invalid::new);
                if(grants.stream().noneMatch(g->g.learner().equals(p.learner())&&g.method().equals(p.method())))
                    grants.add(new Grant(p.learner(),p.method(),p.teacher(),Origin.PRACTICE,jobId,attempt.id(),attempt.generation(),run.runId(),job.revision()));
            }
            var completed=p.finish(stopped==null?Stage.LEARNED:Stage.STOPPED,stopped);
            var history=old.practices().stream().map(v->v.job().equals(jobId)?completed:v).toList();
            return publish(old,new Snapshot(old.world(),Math.addExact(old.revision(),1),true,old.legacyCitizens(),old.legacyMethods(),old.personalCitizens(),grants,history),jobId,work.count,cancel);
        }catch(JobOutcomeSource.Limited exhausted){return result(Code.LIMIT,Reason.BUDGET_EXHAUSTED,jobId,work.count,0);}
        catch(JobOutcomeSource.Invalid|ArithmeticException invalid){return result(Code.INVALID,Reason.REQUEST_INVALID,jobId,work.count,0);}
        catch(RuntimeException failure){return result(Code.UNAVAILABLE,Reason.STORAGE_UNAVAILABLE,jobId,work.count,0);}
    }
    /** Preserve the established acquisition path: a real admitted trial's verified job can teach its actor. */
    public synchronized Result recordAcquisition(UUID jobId,BooleanSupplier cancel){
        if(practice(jobId)!=null)return result(Code.DUPLICATE,null,jobId,0,0);
        var work=new JobOutcomeSource.Work(limits.work());
        try{
            var job=source.verified(jobId,work);var a=job.current();
            if(a==null||job.state()!=Jobs.State.SUCCEEDED)return result(Code.PENDING,null,jobId,work.count,0);
            var learner=enrollment(a.worker(),job.origin());if(learner==null)return result(Code.DENIED,Reason.AUTHORITY_DENIED,jobId,work.count,0);
            var execution=a.executions().stream().filter(e->source.learned(job,e.artifact(),a.worker(),work)).findFirst().orElse(null);
            if(execution==null)return result(Code.PENDING,null,jobId,work.count,0);
            if(mayUse(a.worker(),execution.artifact(),job.origin()))return result(Code.DUPLICATE,null,jobId,work.count,0);
            if(!catalog.usable(execution.artifact()).allowed())return result(Code.INVALID,Reason.ARTIFACT_INCOMPATIBLE,jobId,work.count,0);
            var old=snapshot();var grants=new ArrayList<>(old.grants());grants.add(new Grant(learner,execution.artifact(),learner,Origin.ACQUISITION,
                    jobId,a.id(),a.generation(),execution.runId(),job.revision()));
            return publish(old,new Snapshot(old.world(),Math.addExact(old.revision(),1),true,old.legacyCitizens(),old.legacyMethods(),old.personalCitizens(),grants,old.practices()),jobId,work.count,cancel);
        }catch(JobOutcomeSource.Limited exhausted){return result(Code.LIMIT,Reason.BUDGET_EXHAUSTED,jobId,work.count,0);}
        catch(JobOutcomeSource.Invalid|ArithmeticException invalid){return result(Code.INVALID,Reason.REQUEST_INVALID,jobId,work.count,0);}
    }
    public synchronized Result migrateMethod(ActorRef actor,ArtifactRef from,ArtifactRef to,TrustedContext caller,BooleanSupplier cancel){
        if(versions.stream().noneMatch(v->v.from().equals(from)&&v.to().equals(to))||!mayUse(actor,from,caller))return result(Code.DENIED,Reason.KNOWLEDGE_REQUIRED,null,0,0);
        var ready=catalog.usable(to);if(!ready.allowed())return result(Code.INVALID,ready.reason(),null,0,0);
        var old=snapshot();var learner=enrollment(actor,caller);
        var origin=old.grants().stream().filter(g->g.learner().equals(learner)&&g.method().equals(from)).findFirst().orElse(null);
        if(origin==null)return result(Code.INVALID,Reason.KNOWLEDGE_REQUIRED,null,0,0);
        if(mayUse(actor,to,caller))return result(Code.DUPLICATE,null,null,0,0);
        var grants=new ArrayList<>(old.grants());grants.add(new Grant(learner,to,origin.teacher(),Origin.COMPATIBLE_MIGRATION,
                origin.job(),origin.attempt(),origin.generation(),origin.run(),origin.jobRevision()));
        return publish(old,new Snapshot(old.world(),Math.addExact(old.revision(),1),true,old.legacyCitizens(),old.legacyMethods(),old.personalCitizens(),grants,old.practices()),null,grants.size(),cancel);
    }
    private Result publish(Snapshot old,Snapshot next,UUID job,int work,BooleanSupplier cancel){
        if(readOnly())return result(Code.UNAVAILABLE,Reason.STORAGE_UNAVAILABLE,job,work,0);
        if(next.grants().size()>limits.grants()||next.practices().size()>limits.history()
                ||next.practices().stream().filter(p->p.stage()==Stage.PRACTICING).count()>limits.active()||work>limits.work())
            return result(Code.LIMIT,Reason.STORAGE_LIMIT_REACHED,job,Math.min(work,limits.work()),0);
        try{
            byte[] bytes=KnowledgeAccessCodec.encode(next);
            if(bytes.length>limits.bytes()||!capacity.test(new WorldRetentionManager.Amount(journal.reservation(bytes.length),records(next))))
                return result(Code.LIMIT,Reason.STORAGE_LIMIT_REACHED,job,work,bytes.length);
            if(cancel.getAsBoolean())return result(Code.CANCELLED,Reason.CANCELLED,job,work,bytes.length);
            var pins=new HashSet<>(protectedRoots(old));pins.addAll(protectedRoots(next));
            var update=roots.publishNext("knowledge-access",pins,true);
            if(update!=RetentionRoots.Update.APPLIED&&update!=RetentionRoots.Update.UNCHANGED)return result(Code.LIMIT,Reason.STORAGE_LIMIT_REACHED,job,work,bytes.length);
            journal.replace(old,next,bytes,cancel);return result(Code.APPLIED,null,job,work,bytes.length);
        }catch(KnowledgeAccessJournal.Cancelled cancelled){return result(Code.CANCELLED,Reason.CANCELLED,job,work,0);}
        catch(IOException|RuntimeException failed){return result(Code.UNAVAILABLE,Reason.STORAGE_UNAVAILABLE,job,work,0);}
    }
    private Result result(Code code,Reason reason,UUID job,int work,int bytes){return new Result(code,reason,job,work,bytes,journal.mayHaveSucceeded());}
    public List<Grant> grants(ActorRef actor,TrustedContext caller,int maximum){
        var enrolled=enrollment(actor,caller);if(enrolled==null||readOnly())throw new SecurityException("Private access records");
        if(maximum<1||maximum>16)throw new IllegalArgumentException("Access query bound");
        return snapshot().grants().stream().filter(g->g.learner().equals(enrolled)).limit(maximum).toList();
    }
    public static Set<WorldRetentionManager.Key> protectedRoots(Snapshot state){
        var result=new HashSet<WorldRetentionManager.Key>();state.legacyMethods().forEach(r->result.add(WorldRetentionManager.artifact(r)));
        for(var g:state.grants())result.add(WorldRetentionManager.artifact(g.method()));
        for(var p:state.practices())p.pinned().forEach(r->result.add(WorldRetentionManager.artifact(r)));
        if(!state.grants().isEmpty()||!state.practices().isEmpty()){
            result.add(new WorldRetentionManager.Key("jobs","records"));result.add(new WorldRetentionManager.Key("accomplishments","records"));
        }
        return Set.copyOf(result);
    }
    private static long records(Snapshot state){return 1L+state.legacyCitizens().size()+state.legacyMethods().size()+state.personalCitizens().size()+state.grants().size()+state.practices().size();}
    public WorldRetentionManager.Owner retentionOwner(){return new RetentionFileOwner("knowledge-access",journal.directory(),WorldRetentionManager.Category.EVIDENCE,
            ()->snapshot().revision(),()->records(snapshot()),8,3L*KnowledgeAccessCodec.MAX_BYTES);}
    @Override public synchronized void close()throws IOException{journal.close();}
}
