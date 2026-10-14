package dev.aivillages.core.kernel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.*;
import static dev.aivillages.core.kernel.Accomplishments.*;
import static dev.aivillages.core.kernel.Contracts.*;

/** Serialized derived-state owner. Failed ledger publication never changes Jobs or performs an effect. */
public final class AccomplishmentLedger implements AutoCloseable {
    public static final String WORLD_RELATIVE_PATH=AccomplishmentJournal.PATH;
    @FunctionalInterface public interface AggregatePolicy { boolean mayInspect(ActorRef actor,TrustedContext origin,TrustedContext viewer); }
    private final AccomplishmentJournal journal;
    private final JobOutcomeSource source;
    private final Limits limits;
    private final RetentionRoots roots;
    private final AggregatePolicy sharing;
    private Predicate<WorldRetentionManager.Amount> capacity=amount->true;
    private AccomplishmentLedger(AccomplishmentJournal journal,JobOutcomeSource source,Limits limits,RetentionRoots roots,AggregatePolicy sharing){
        this.journal=journal;this.source=Objects.requireNonNull(source);this.limits=Objects.requireNonNull(limits);
        this.roots=Objects.requireNonNull(roots);this.sharing=Objects.requireNonNull(sharing);
    }
    public static AccomplishmentLedger open(Path world,UUID id,JobOutcomeSource source,RetentionRoots roots)throws IOException{
        return open(world,id,source,roots,Limits.defaults(),(actor,origin,viewer)->false,point->{});
    }
    static AccomplishmentLedger open(Path world,UUID id,JobOutcomeSource source,RetentionRoots roots,Limits limits,
                                    AggregatePolicy sharing,AccomplishmentJournal.Faults faults)throws IOException{
        var journal=AccomplishmentJournal.open(world,id,faults);
        var ledger=new AccomplishmentLedger(journal,source,limits,roots,sharing);
        var update=roots.publishNext("accomplishment-ingestion",protectedRoots(journal.snapshot()),!journal.readOnly());
        if(update!=RetentionRoots.Update.APPLIED&&update!=RetentionRoots.Update.UNCHANGED){journal.close();throw new IOException("Ledger recovery roots unavailable");}
        return ledger;
    }
    public synchronized void capacity(Predicate<WorldRetentionManager.Amount> assessment){capacity=Objects.requireNonNull(assessment);}
    /** Trusted immutable composition view. Player clients use the scoped query methods. */
    public synchronized Snapshot snapshot(){return journal.snapshot();}
    public synchronized boolean readOnly(){return journal.readOnly();}
    public synchronized Result recordOutcomes(List<OutcomeRef> references,BooleanSupplier cancel){
        Objects.requireNonNull(references);Objects.requireNonNull(cancel);
        var work=new JobOutcomeSource.Work(limits.work());
        if(journal.readOnly())return result(Code.UNAVAILABLE,0,work,0);
        if(references.size()>limits.batch())return result(Code.LIMIT,0,work,0);
        if(cancel.getAsBoolean())return result(Code.CANCELLED,0,work,0);
        var old=journal.snapshot();var aggregates=new LinkedHashMap<Key,Aggregate>();old.aggregates().forEach(a->aggregates.put(a.key(),a));
        var progress=new LinkedHashMap<UUID,Progress>();old.progress().forEach(p->progress.put(p.job(),p));
        int events=0;
        try{
            for(var ref:references){
                work.step();if(ref==null||!ref.world().equals(old.world()))throw new JobOutcomeSource.Invalid();
                var prior=progress.get(ref.job());if(prior!=null&&ref.revision()<=prior.revision())continue;
                var verified=source.read(ref,work);ingest(verified.job(),prior,aggregates,progress,work);events++;
            }
            if(events==0)return result(Code.DUPLICATE,0,work,0);
            var rules=old.rules();
            var measured=List.copyOf(aggregates.values());
            var next=new Snapshot(old.world(),Math.addExact(old.revision(),1),measured,List.copyOf(progress.values()),rules,
                    qualify(measured,rules,old.recognition(),work));
            return publish(old,next,events,work,cancel);
        }catch(JobOutcomeSource.Limited limited){return result(Code.LIMIT,0,work,0);}
        catch(JobOutcomeSource.Stale stale){return result(Code.STALE,0,work,0);}
        catch(JobOutcomeSource.Invalid|IllegalArgumentException|ArithmeticException invalid){return result(Code.UNVERIFIABLE,0,work,0);}
        catch(RuntimeException unavailable){return result(Code.UNAVAILABLE,0,work,0);}
    }
    private void ingest(Jobs.Job job,Progress prior,Map<Key,Aggregate> aggregates,Map<UUID,Progress> progress,JobOutcomeSource.Work work){
        String request=AccomplishmentCodec.sha(RequestCodec.encode(job.request().request()).getBytes(StandardCharsets.UTF_8));
        if(prior!=null&&(!prior.origin().equals(job.origin())||!prior.requestSha256().equals(request)))throw new JobOutcomeSource.Invalid();
        var attempts=new ArrayList<AttemptProgress>();boolean completed=prior!=null&&prior.completed();
        for(var attempt:job.attempts()){
            work.step();var before=prior==null?null:prior.attempts().stream().filter(p->p.attempt().equals(attempt.id())).findFirst().orElse(null);
            if(before!=null&&(before.generation()!=attempt.generation()||!before.worker().equals(attempt.worker())))throw new JobOutcomeSource.Invalid();
            var credits=new LinkedHashMap<UUID,RunCredit>();if(before!=null)before.runs().forEach(r->credits.put(r.run(),r));
            var byRun=new LinkedHashMap<UUID,Long>();var witness=new HashMap<UUID,List<UUID>>();
            var batches=new LinkedHashMap<List<UUID>,List<CropDelivery.CropReceipt>>();
            for(var receipt:attempt.receipts()){work.step();batches.computeIfAbsent(List.of(receipt.runId(),receipt.batchId()),ignored->new ArrayList<>()).add(receipt);}
            for(var entry:batches.entrySet()){
                long[] amounts=new long[3];UUID[] ids=new UUID[3];
                for(var receipt:entry.getValue()){work.step();int stage=receipt.stage().ordinal();amounts[stage]=Math.addExact(amounts[stage],receipt.wheat());ids[stage]=receipt.receiptId();}
                long quantity=Math.min(amounts[0],Math.min(amounts[1],amounts[2]));
                if(quantity>0){UUID run=entry.getKey().getFirst();byRun.merge(run,quantity,Math::addExact);witness.put(run,List.of(ids));}
            }
            long credited=0;for(var existing:credits.values()){
                work.step();var execution=attempt.executions().stream().filter(e->e.runId().equals(existing.run())).findFirst().orElseThrow(JobOutcomeSource.Invalid::new);
                if(!execution.artifact().equals(existing.method())||existing.quantity()>byRun.getOrDefault(existing.run(),0L))throw new JobOutcomeSource.Invalid();
                credited=Math.addExact(credited,existing.quantity());
            }
            if(credited>attempt.credited())throw new JobOutcomeSource.Invalid();long remaining=attempt.uncertain()?0:attempt.credited()-credited;
            var environment=Environment.from(attempt.bound());
            for(var execution:attempt.executions()){
                work.step();var previous=credits.get(execution.runId());long oldQuantity=previous==null?0:previous.quantity();
                long delta=Math.min(remaining,byRun.getOrDefault(execution.runId(),0L)-oldQuantity);
                if(delta<0)throw new JobOutcomeSource.Invalid();
                if(delta>0){
                    if(limits.samples()==0)throw new JobOutcomeSource.Limited();
                    var key=new Key(attempt.worker(),job.origin(),CropDelivery.ID,execution.artifact(),environment);
                    var value=aggregates.getOrDefault(key,Aggregate.empty(key));
                    var sample=new Sample(job.id(),job.revision(),attempt.id(),execution.runId(),OutcomeClass.CONTRIBUTION,null,delta,witness.get(execution.runId()));
                    aggregates.put(key,new Aggregate(key,Math.addExact(value.quantity(),delta),value.completedJobs(),
                            Math.addExact(value.contributionSamples(),oldQuantity==0?1:0),value.outcomes(),samples(value,sample)));
                    credits.put(execution.runId(),new RunCredit(execution.runId(),execution.artifact(),Math.addExact(oldQuantity,delta)));remaining-=delta;
                }
            }
            if(remaining!=0)throw new JobOutcomeSource.Invalid();
            int outcomes=before==null?0:before.outcomes();
            if(attempt.terminal()!=null&&!attempt.executions().isEmpty()){
                OutcomeClass classification=classify(attempt.terminal());int bit=1<<classification.ordinal();
                var execution=attempt.executions().getFirst();
                if(classification==OutcomeClass.SUCCESS){
                    execution=attempt.executions().stream().filter(e->JobOutcomeSource.completed(attempt,e,work)).findFirst().orElseThrow(JobOutcomeSource.Invalid::new);
                }
                var key=new Key(attempt.worker(),job.origin(),CropDelivery.ID,execution.artifact(),environment);
                var value=aggregates.getOrDefault(key,Aggregate.empty(key));boolean jobSuccess=!completed&&job.state()==Jobs.State.SUCCEEDED
                        &&classification==OutcomeClass.SUCCESS&&!attempt.uncertain();
                if((outcomes&bit)==0||jobSuccess){
                    var counts=new EnumMap<OutcomeClass,Long>(OutcomeClass.class);counts.putAll(value.outcomes());
                    if((outcomes&bit)==0)counts.merge(classification,1L,Math::addExact);
                    var sample=new Sample(job.id(),job.revision(),attempt.id(),execution.runId(),classification,attempt.terminal().reason(),0,List.of());
                    aggregates.put(key,new Aggregate(key,value.quantity(),Math.addExact(value.completedJobs(),jobSuccess?1:0),value.contributionSamples(),counts,samples(value,sample)));
                }
                outcomes|=bit;if(jobSuccess)completed=true;
            }
            attempts.add(new AttemptProgress(attempt.id(),attempt.generation(),attempt.worker(),List.copyOf(credits.values()),outcomes));
        }
        if(prior!=null&&!attempts.stream().map(AttemptProgress::attempt).toList().containsAll(prior.attempts().stream().map(AttemptProgress::attempt).toList()))throw new JobOutcomeSource.Invalid();
        progress.put(job.id(),new Progress(job.id(),job.revision(),job.origin(),request,completed,attempts));
        bounds(aggregates.values(),progress.size(),work);
    }
    private List<Sample> samples(Aggregate value,Sample sample){
        if(limits.samples()==0)return List.of();var list=new ArrayList<>(value.samples());list.add(sample);
        while(list.size()>limits.samples())list.removeFirst();return List.copyOf(list);
    }
    private void bounds(Collection<Aggregate> aggregates,int jobs,JobOutcomeSource.Work work){
        if(aggregates.size()>limits.aggregates()||jobs>limits.jobs())throw new JobOutcomeSource.Limited();
        var dimensions=new HashMap<List<Object>,Integer>();
        for(var aggregate:aggregates){work.step();var key=aggregate.key();
            int count=dimensions.merge(List.of(key.actor().citizenId(),key.origin(),key.capability()),1,Integer::sum);
            if(count>limits.contexts()||aggregate.samples().size()>limits.samples())throw new JobOutcomeSource.Limited();
        }
    }
    /** Small host-owned rule updates recompute or retire metadata. Existing evidence is never reset. */
    public synchronized Result rules(List<Rule> rules,BooleanSupplier cancel){
        var work=new JobOutcomeSource.Work(limits.work());var old=journal.snapshot();
        if(journal.readOnly())return result(Code.UNAVAILABLE,0,work,0);
        if(rules==null||rules.size()>4||rules.stream().map(Rule::id).distinct().count()!=rules.size())return result(Code.LIMIT,0,work,0);
        if(cancel.getAsBoolean())return result(Code.CANCELLED,0,work,0);
        try{
            var retained=new LinkedHashMap<String,Rule>();old.rules().forEach(r->retained.put(r.id(),r.retired()));
            for(var rule:rules){work.step();var previous=old.rules().stream().filter(r->r.id().equals(rule.id())).findFirst().orElse(null);
                if(previous!=null&&(rule.version()<previous.version()||rule.version()==previous.version()&&!rule.sameMeaning(previous)))return result(Code.UNVERIFIABLE,0,work,0);
                retained.put(rule.id(),rule);}
            if(retained.size()>4)return result(Code.LIMIT,0,work,0);var configured=List.copyOf(retained.values());
            if(configured.equals(old.rules()))return result(Code.DUPLICATE,0,work,0);
            var next=new Snapshot(old.world(),Math.addExact(old.revision(),1),old.aggregates(),old.progress(),configured,qualify(old.aggregates(),configured,old.recognition(),work));
            return publish(old,next,0,work,cancel);
        }catch(JobOutcomeSource.Limited limited){return result(Code.LIMIT,0,work,0);}
        catch(IllegalArgumentException|ArithmeticException invalid){return result(Code.UNVERIFIABLE,0,work,0);}
    }
    private List<Recognition> qualify(List<Aggregate> aggregates,List<Rule> rules,List<Recognition> old,JobOutcomeSource.Work work){
        var result=new LinkedHashMap<List<Object>,Recognition>();
        for(var value:old){work.step();result.put(List.of(value.actor().citizenId(),value.origin(),value.rule()),new Recognition(value.actor(),value.origin(),value.rule(),value.version(),value.title(),false,
                value.support(),value.completedJobs(),value.quantity(),value.samples(),value.priorVersion(),value.priorTitle()));}
        for(var rule:rules){
            if(!rule.active())continue;
            var support=new LinkedHashMap<List<Object>,Aggregate>();
            for(var aggregate:aggregates){work.step();if(!aggregate.key().capability().equals(rule.capability()))continue;
                var key=List.<Object>of(aggregate.key().actor().citizenId(),aggregate.key().origin(),rule.id());
                var previous=support.get(key);
                if(previous==null||eligible(aggregate,rule)&&!eligible(previous,rule)||eligible(aggregate,rule)==eligible(previous,rule)&&aggregate.quantity()>previous.quantity())support.put(key,aggregate);
            }
            for(var entry:support.entrySet()){
                work.step();var a=entry.getValue();var previous=result.get(entry.getKey());boolean changed=previous!=null&&previous.version()!=rule.version();
                result.put(entry.getKey(),new Recognition(a.key().actor(),a.key().origin(),rule.id(),rule.version(),rule.title(),eligible(a,rule),a.key(),a.completedJobs(),a.quantity(),a.contributionSamples(),
                        changed?previous.version():previous==null?0:previous.priorVersion(),changed?previous.title():previous==null?null:previous.priorTitle()));
            }
        }
        if(result.size()>256)throw new JobOutcomeSource.Limited();return List.copyOf(result.values());
    }
    private static boolean eligible(Aggregate a,Rule rule){return a.completedJobs()>=rule.completedJobs()&&a.quantity()>=rule.quantity()&&a.contributionSamples()>=rule.samples();}
    private Result publish(Snapshot old,Snapshot next,int events,JobOutcomeSource.Work work,BooleanSupplier cancel){
        try{
            byte[] bytes=AccomplishmentCodec.encode(next);if(bytes.length>limits.bytes())return result(Code.LIMIT,0,work,bytes.length);
            if(!capacity.test(new WorldRetentionManager.Amount(journal.reservation(bytes.length),records(next))))return result(Code.LIMIT,0,work,bytes.length);
            if(cancel.getAsBoolean())return result(Code.CANCELLED,0,work,bytes.length);
            var pins=new HashSet<>(protectedRoots(old));pins.addAll(protectedRoots(next));
            var update=roots.publishNext("accomplishment-ingestion",pins,true);
            if(update!=RetentionRoots.Update.APPLIED&&update!=RetentionRoots.Update.UNCHANGED)return result(Code.LIMIT,0,work,bytes.length);
            journal.replace(old,next,bytes,cancel);
            return result(Code.APPLIED,events,work,bytes.length);
        }catch(AccomplishmentJournal.Cancelled cancelled){return result(Code.CANCELLED,0,work,0);}
        catch(IOException|RuntimeException unavailable){return result(Code.UNAVAILABLE,0,work,0);}
    }
    private Result result(Code code,int events,JobOutcomeSource.Work work,int bytes){return new Result(code,events,work.count,bytes,journal.mayHaveSucceeded());}
    public synchronized Query query(ActorRef actor,TrustedContext caller,int maximum){
        if(maximum<1||maximum>16)return new Query(Code.LIMIT,List.of(),List.of(),false);
        if(journal.readOnly())return new Query(Code.UNAVAILABLE,List.of(),List.of(),false);
        if(!source.controls(actor,caller))return new Query(Code.DENIED,List.of(),List.of(),false);
        var state=journal.snapshot();var matching=state.aggregates().stream().filter(a->a.key().actor().equals(actor)&&a.key().origin().equals(caller)).toList();
        var recognition=state.recognition().stream().filter(r->r.actor().equals(actor)&&r.origin().equals(caller)).limit(4).toList();
        return new Query(Code.APPLIED,matching.stream().limit(maximum).toList(),recognition,matching.size()>maximum);
    }
    public synchronized List<Summary> summaries(ActorRef actor,TrustedContext viewer,int maximum){
        if(maximum<1||maximum>16||viewer==null||!viewer.scope().worldId().equals(journal.snapshot().world())||journal.readOnly())return List.of();
        return journal.snapshot().aggregates().stream().filter(a->a.key().actor().equals(actor)&&source.controls(actor,a.key().origin())
                        &&(a.key().origin().equals(viewer)||sharing.mayInspect(actor,a.key().origin(),viewer)))
                .limit(maximum).map(a->new Summary(a.key().capability(),a.key().environment(),a.quantity(),a.completedJobs(),a.contributionSamples(),a.outcomes())).toList();
    }
    public static Set<WorldRetentionManager.Key> protectedRoots(Snapshot state){
        var result=new HashSet<WorldRetentionManager.Key>();
        if(!state.progress().isEmpty())result.add(new WorldRetentionManager.Key("jobs","records"));
        for(var a:state.aggregates())result.add(WorldRetentionManager.artifact(a.key().method()));
        for(var p:state.progress())for(var a:p.attempts())for(var run:a.runs())result.add(WorldRetentionManager.artifact(run.method()));
        return Set.copyOf(result);
    }
    private static long records(Snapshot state){return state.aggregates().size()+state.progress().size()+state.rules().size()+state.recognition().size();}
    public WorldRetentionManager.Owner retentionOwner(){
        return new RetentionFileOwner("accomplishments",journal.directory(),WorldRetentionManager.Category.EVIDENCE,
                ()->journal.snapshot().revision(),()->records(journal.snapshot()),8,3L*AccomplishmentCodec.MAX_BYTES);
    }
    @Override public synchronized void close()throws IOException{journal.close();}
}
