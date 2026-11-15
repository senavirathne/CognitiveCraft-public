package dev.aivillages.fabric;

import dev.aivillages.core.kernel.Budgets;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.JobLeaseOwners;
import dev.aivillages.core.kernel.JobLifecycleStore;
import dev.aivillages.core.kernel.Jobs;
import dev.aivillages.core.kernel.Outcomes.Execution;
import dev.aivillages.core.kernel.Outcomes.ExecutionStatus;
import dev.aivillages.core.kernel.Outcomes.Reason;
import dev.aivillages.core.kernel.RecipeTransaction.*;
import dev.aivillages.core.kernel.ResourceDelivery;
import dev.aivillages.core.kernel.ResourceDelivery.Stage;
import dev.aivillages.core.kernel.ResourceLeaseService;
import dev.aivillages.core.kernel.ResourceLeases;
import dev.aivillages.core.kernel.SurvivalGateway;
import dev.aivillages.core.kernel.SurvivalGateway.ExactRecipeResult;
import dev.aivillages.core.kernel.production.RecipeChoice;
import dev.aivillages.core.kernel.production.RecipeSources;
import dev.aivillages.core.kernel.runtime.activity.ActivityRecipeEvidence;
import dev.aivillages.core.kernel.runtime.activity.ActivityPayload;
import dev.aivillages.core.kernel.runtime.evidence.LiveAttemptAuthority;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.Marker;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.MarkerPhase;
import dev.aivillages.core.kernel.runtime.evidence.NativeRecipeJournal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import static dev.aivillages.core.kernel.RecipeTransaction.fingerprint;

/** Native phase wrapper inside the same admitted interpreter, gateway and Jobs attempt. */
final class FabricActivityRecipeEffects implements SurvivalGateway.ExactRecipeEffects {
    private static final List<Stage> PHASES=List.of(Stage.WITHDRAW,Stage.CRAFT,Stage.DEPOSIT);
    private static final int MAX_LIVE=8;
    private final FabricGatewayWorld world;
    private final JobLifecycleStore jobs;
    private final ResourceLeaseService leases;
    private final NativeRecipeJournal journal;
    private final AuthorityPolicy authority;
    private final Clock clock;
    private final Executor writer;
    private final FabricRecipeParticipants participants;
    private final Map<UUID,Run> live=new HashMap<>();
    private final HashSet<UUID> appliedHere=new HashSet<>();

    private static final class Run {
        final UUID root,step,attempt;
        final long generation;
        final ValidatedRequest bound,exact;
        final RunCorrelation correlation;
        final ActorRef actor;
        final RecipeChoice choice;
        final RecipeSources sources;
        final ContainerRef destination;
        int observedPhases;
        boolean observed;
        FabricConcreteRecipeChoices.Context context;
        Pending pending;
        Run(Jobs.Job job,ValidatedRequest bound,ValidatedRequest exact,RunCorrelation correlation,RecipeChoice choice) {
            root=job.id(); step=job.activity().current().id(); attempt=job.current().id(); generation=job.current().generation();
            this.bound=bound; this.exact=exact; this.correlation=correlation; this.choice=choice;
            actor=job.current().worker(); sources=ActivityRecipeEvidence.sources(exact,choice);
            destination=ResourceDelivery.destinationAddress(bound.request());
        }
    }
    private static final class Pending {
        final NativeRecipeJournal.Intent intent;
        final Jobs.Job owner;
        final List<ContainerRef> containers;
        final long actionDeadline;
        final CompletableFuture<NativeRecipeJournal.Entry> prepared;
        CompletableFuture<NativeRecipeJournal.Entry> result;
        boolean possibleEffect;
        Reason blocked;
        Pending(NativeRecipeJournal.Intent intent,Jobs.Job owner,List<ContainerRef> containers,long actionDeadline,
                CompletableFuture<NativeRecipeJournal.Entry> prepared) {
            this.intent=intent; this.owner=owner; this.containers=containers; this.actionDeadline=actionDeadline; this.prepared=prepared;
        }
    }

    FabricActivityRecipeEffects(FabricGatewayWorld world,JobLifecycleStore jobs,ResourceLeaseService leases,
                                NativeRecipeJournal journal,AuthorityPolicy authority,Clock clock,Executor writer) {
        this.world=Objects.requireNonNull(world); this.jobs=Objects.requireNonNull(jobs); this.leases=Objects.requireNonNull(leases);
        this.journal=Objects.requireNonNull(journal); this.authority=Objects.requireNonNull(authority);
        this.clock=Objects.requireNonNull(clock); this.writer=Objects.requireNonNull(writer);
        participants=new FabricRecipeParticipants(world);
    }

    Reason bind(Jobs.Job job,ValidatedRequest bound,ArtifactRef method,UUID id) {
        thread();
        if(journal.fenced() || !jobs.ready() || !leases.ready())return Reason.STORAGE_UNAVAILABLE;
        if(live.containsKey(id) || live.size()>=MAX_LIVE)return Reason.BUDGET_EXHAUSTED;
        try {
            var correlation=new RunCorrelation(id,method,null);
            var current=new JobLeaseOwners(jobs,clock).execution(bound,correlation);
            if(!LiveAttemptAuthority.unchanged(job,current) || !id.equals(job.current().id()))return Reason.STALE_OBSERVATION;
            var step=job.activity().current();
            if(!step.exactChoice() || !step.artifact().equals(method) || !id.equals(step.attempt()))return Reason.REQUEST_INVALID;
            var exact=ActivityRecipeEvidence.exactInvocation(job,bound,step.choice(),step.quantities());
            var run=new Run(job,bound,exact,correlation,step.choice().recipe());
            if(!claims(run))return Reason.STALE_OBSERVATION;
            live.put(id,run);
            return null;
        } catch(SecurityException denied){return Reason.AUTHORITY_DENIED;}
        catch(IllegalArgumentException | NullPointerException invalid){return Reason.REQUEST_INVALID;}
    }

    @Override public boolean owns(UUID run){thread(); return live.containsKey(run);}
    @Override public Reason observe(ValidatedRequest bound,RunCorrelation correlation,Budgets.Ledger usage) {
        thread();
        var run=bound(bound,correlation);
        if(run==null || run.observed || current(run)==null)return Reason.STALE_OBSERVATION;
        usage.debit(Budgets.Kind.OBSERVATIONS,40L+27L*(run.sources.containers().size()+1));
        Reason denied=world.validateActivityChoice(run.exact,run.choice,permission(run),run.sources);
        if(denied==null)run.observed=true;
        return denied;
    }

    @Override public ExactRecipeResult poll(ValidatedRequest bound,RunCorrelation correlation,ActionHandle operation,
                                           Stage phase,Budgets.Ledger usage,long deadline) {
        thread();
        var run=bound(bound,correlation);
        if(run==null || !run.observed)return refusal(Reason.STALE_OBSERVATION,false);
        if(run.pending!=null)return advance(run,operation,phase,usage);
        int batch=run.observedPhases/PHASES.size();
        if(batch>=run.choice.batches().size() || phase!=PHASES.get(run.observedPhases%PHASES.size()))
            return refusal(Reason.REQUEST_INVALID,false);
        var owner=current(run);
        if(owner==null)return refusal(Reason.INTERRUPTED,false);
        // A new physical write may follow only an acknowledged original Jobs receipt prefix.
        if(owner.current().resourceReceipts().size()!=run.observedPhases)return waiting(false);
        long stop=Math.min(Math.min(deadline,usage.deadline()),owner.current().allowance().deadlineEpochMillis());
        if(clock.millis()>stop)return refusal(Reason.BUDGET_EXHAUSTED,false);
        if(!permits(run,phase))return refusal(Reason.AUTHORITY_DENIED,false);
        try {
            List<ContainerRef> containers=containers(run,phase);
            usage.debit(Budgets.Kind.OBSERVATIONS,32L+54L*containers.size());
            if(phase==Stage.WITHDRAW)run.context=world.prepareChoice(run.exact,run.choice,batch,correlation.runId(),permission(run),run.sources);
            if(run.context==null)return refusal(Reason.STALE_OBSERVATION,false);
            var captured=participants.capture(run.actor,containers);
            if(run.observedPhases>0 && !owner.current().resourceReceipts().getLast().choice().custodyAfter()
                    .equals(captured.getFirst().evidence().slots()))return refusal(Reason.STALE_OBSERVATION,false);
            var intent=new NativeRecipeJournal.Intent(id(operation.id(),"journal"),run.root,run.step,run.attempt,run.generation,
                    operation.id(),run.correlation.artifact().sha256(),run.choice.digest(),batch,phase,
                    owner.allowance().deadlineEpochMillis(),FabricRecipeParticipants.evidence(captured));
            run.pending=new Pending(intent,owner,containers,stop,write(()->journal.prepare(intent)));
            return waiting(false);
        } catch(FabricConcreteRecipeChoices.Refused denied){return refusal(denied.reason,false);}
        catch(Budgets.Exhausted exhausted){return refusal(Reason.BUDGET_EXHAUSTED,false);}
        catch(RuntimeException unavailable){return refusal(Reason.TARGET_UNAVAILABLE,false);}
    }

    private ExactRecipeResult advance(Run run,ActionHandle operation,Stage phase,Budgets.Ledger usage) {
        Pending pending=run.pending;
        if(!pending.intent.operation().equals(operation.id()) || pending.intent.phase()!=phase)return refusal(Reason.STALE_OBSERVATION,pending.possibleEffect);
        if(!pending.prepared.isDone())return waiting(false);
        try { pending.prepared.join(); }
        catch(CompletionException failed){run.pending=null;return refusal(journal.fenced()?Reason.STORAGE_UNAVAILABLE:Reason.STORAGE_LIMIT_REACHED,false);}
        if(pending.result==null) {
            if(jobs.unavailableReason()==Reason.BUDGET_EXHAUSTED || leases.failure()==null && !leases.ready())return waiting(false);
            apply(run,pending,usage);
            return waiting(pending.possibleEffect);
        }
        if(!pending.result.isDone())return waiting(pending.possibleEffect);
        try {
            var entry=pending.result.join();
            if(entry.result().status()!=NativeRecipeJournal.Status.APPLIED) {
                if(!pending.possibleEffect)run.pending=null;
                return refusal(pending.blocked==null?Reason.INTERRUPTED:pending.blocked,pending.possibleEffect);
            }
            var receipt=receipt(run.bound,run.choice,entry);
            appliedHere.add(entry.intent().token());
            run.pending=null; run.observedPhases++;
            return new ExactRecipeResult(false,true,receipt,null);
        }catch(RuntimeException missing){return refusal(Reason.INTERRUPTED,pending.possibleEffect);}
    }

    private void apply(Run run,Pending pending,Budgets.Ledger usage) {
        var intent=pending.intent;
        var current=current(run);
        if(current==null || !LiveAttemptAuthority.unchanged(pending.owner,current) || clock.millis()>pending.actionDeadline
                || !permits(run,intent.phase())) {
            noEffect(pending,current==null?Reason.INTERRUPTED:Reason.STALE_OBSERVATION,usage.snapshot());
            return;
        }
        List<FabricRecipeParticipants.Capture> before;
        try {before=participants.capture(run.actor,pending.containers);}
        catch(RuntimeException unavailable){noEffect(pending,Reason.TARGET_UNAVAILABLE,usage.snapshot());return;}
        if(!intent.before().equals(FabricRecipeParticipants.evidence(before))) {noEffect(pending,Reason.STALE_OBSERVATION,usage.snapshot());return;}
        Budgets.Ledger.PhysicalEffect charge;
        try {charge=usage.beginPhysicalEffect();}
        catch(Budgets.Exhausted exhausted){noEffect(pending,Reason.BUDGET_EXHAUSTED,usage.snapshot());return;}
        String effect=fingerprint(intent.token().toString()), digest=intent.digest();
        var mutating=new Marker(effect,digest,MarkerPhase.MUTATING,"");
        List<FabricRecipeParticipants.Capture> after=List.of();
        NativeRecipeJournal.Status status=NativeRecipeJournal.Status.UNCERTAIN;
        try {
            before.forEach(participant->participant.mark(mutating));
            pending.possibleEffect=true;
            var nativeResult=world.executeChoice(run.context,ChoicePhase.valueOf(intent.phase().name()));
            after=participants.capture(run.actor,pending.containers);
            if(nativeResult.observation().kind()==ChoiceObservationKind.PRECHECK_NO_EFFECT
                    && intent.before().equals(FabricRecipeParticipants.evidence(after))) {
                status=NativeRecipeJournal.Status.NO_EFFECT;
                pending.possibleEffect=false; pending.blocked=nativeResult.observation().reason();
            } else if(nativeResult.observation().kind()==ChoiceObservationKind.APPLIED
                    && FabricRecipeParticipants.applied(run.choice,intent.batch(),intent.phase(),before,after)) {
                status=NativeRecipeJournal.Status.APPLIED; run.context=nativeResult.continuation();
            }
        } catch(RuntimeException unavailable){pending.blocked=Reason.INTERRUPTED;}
        finally {charge.settle(pending.possibleEffect);}
        if(status==NativeRecipeJournal.Status.UNCERTAIN) {
            pending.blocked=Reason.INTERRUPTED;
            submitResult(pending,new NativeRecipeJournal.Result(intent.token(),digest,status,List.of(),usage.snapshot()));
            return;
        }
        var result=new NativeRecipeJournal.Result(intent.token(),digest,status,FabricRecipeParticipants.evidence(after),usage.snapshot());
        var marker=new Marker(effect,digest,MarkerPhase.RESULT,result.digest());
        try {after.forEach(participant->participant.mark(marker));}
        catch(RuntimeException invalid){pending.blocked=Reason.INTERRUPTED;submitResult(pending,new NativeRecipeJournal.Result(intent.token(),digest,NativeRecipeJournal.Status.UNCERTAIN,List.of(),usage.snapshot()));return;}
        submitResult(pending,result);
    }

    private void noEffect(Pending pending,Reason blocked,Map<Budgets.Kind,Long> usage) {
        pending.blocked=blocked;
        submitResult(pending,new NativeRecipeJournal.Result(pending.intent.token(),pending.intent.digest(),
                NativeRecipeJournal.Status.NO_EFFECT,pending.intent.before(),usage));
    }
    private void submitResult(Pending pending,NativeRecipeJournal.Result result){pending.result=write(()->journal.result(result));}

    boolean acknowledged(Jobs.Job job,Jobs.Attempt attempt,ResourceDelivery.Receipt receipt) {
        thread();
        if(journal.fenced() || receipt.choice()==null || job.activity()==null)return false;
        var step=job.activity().forAttempt(attempt.id());
        if(step==null || !step.exactChoice())return false;
        for(var entry:journal.entries(attempt.id())) {
            var intent=entry.intent();
            if(intent.root().equals(job.id()) && intent.step().equals(step.id()) && intent.generation()==attempt.generation()
                    && intent.token().equals(receipt.choice().journalToken()) && intent.choice().equals(step.choice().sha256())
                    && intent.method().equals(step.artifact().sha256()) && entry.result()!=null
                    && entry.result().status()==NativeRecipeJournal.Status.APPLIED
                    && receipt.equals(receipt(attempt.bound(),step.choice().recipe(),entry)))return true;
        }
        return false;
    }

    /** A preceding step is available only from this process's acknowledgements or saved native evidence. */
    boolean settled(Jobs.Job job,ActivityPayload.Step step) {
        thread();
        if(journal.fenced() || step==null || !step.exactChoice() || step.attempt()==null)return false;
        var attempt=job.attempts().stream().filter(row->row.id().equals(step.attempt())).findFirst().orElse(null);
        if(attempt==null || attempt.open() || attempt.uncertain())return false;
        var rows=journal.entries(step.attempt());
        try {
            var receipts=complete(job,attempt,step,rows);
            return receipts!=null && receipts.equals(attempt.resourceReceipts())
                    && (rows.stream().allMatch(row->appliedHere.contains(row.intent().token())) || restored(rows));
        }catch(RuntimeException unavailable){return false;}
    }

    Jobs.StoppedObservation observeStopped(Jobs.StoppedContext context,ActivityPayload.Choice choice) {
        thread();
        if(journal.fenced() || live.containsKey(context.attempt()))return Jobs.StoppedObservation.ambiguous("Native recipe writer unavailable or still live");
        try {
            var job=jobs.query(context.job(),context.origin());
            var step=job.activity()==null?null:job.activity().forAttempt(context.attempt());
            if(step==null || !step.exactChoice() || !step.choice().equals(choice) || !job.guard().equals(context.guard())
                    || !context.original().equals(job.current()))return Jobs.StoppedObservation.ambiguous("Original exact recipe attempt unavailable");
            var old=context.original();
            var rows=journal.entries(old.id());
            if(rows.isEmpty() && old.effects()==0 && old.receiptCount()==0) {
                var result=new Execution(ExecutionStatus.INTERRUPTED,Reason.INTERRUPTED,0,null);
                return new Jobs.StoppedObservation(Jobs.StoppedKind.NO_EFFECT,
                        new Jobs.Report(old.id(),old.generation(),0,old.usage(),List.of(),result,List.of()),"Intact write-ahead journal has no native intent");
            }
            var receipts=complete(job,old,step,rows);
            if(receipts==null || !restored(rows))return Jobs.StoppedObservation.ambiguous("Required exact recipe participant saves are incomplete");
            int saved=receipts.size();
            if(old.effects()>saved || old.resourceReceipts().size()>saved
                    || !old.resourceReceipts().equals(receipts.subList(0,old.resourceReceipts().size())))
                return Jobs.StoppedObservation.ambiguous("Stopped prefix differs from the authoritative attempt");
            var usage=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);
            usage.putAll(old.usage());
            for(var row:rows)row.result().usage().forEach((kind,used)->usage.merge(kind,used,Math::max));
            if(usage.getOrDefault(Budgets.Kind.COMMITTED_EFFECTS,0L)!=saved
                    || usage.entrySet().stream().anyMatch(charge->charge.getValue()>old.allowance().maximum(charge.getKey())))
                return Jobs.StoppedObservation.ambiguous("Stopped effect accounting exceeds the original allowance");
            var result=new Execution(ExecutionStatus.SUCCEEDED,null,saved,null);
            return new Jobs.StoppedObservation(Jobs.StoppedKind.EFFECT_COMPLETE,
                    new Jobs.Report(old.id(),old.generation(),saved,usage,List.of(),result,receipts),"Exact journal and native saved participants agree");
        }catch(RuntimeException unavailable){return Jobs.StoppedObservation.ambiguous("Exact recipe recovery evidence unavailable");}
    }

    private List<ResourceDelivery.Receipt> complete(Jobs.Job job,Jobs.Attempt attempt,ActivityPayload.Step step,List<NativeRecipeJournal.Entry> rows) {
        var choice=step.choice().recipe();
        if(rows.size()!=choice.batches().size()*PHASES.size())return null;
        List<ResourceDelivery.Receipt> receipts=new ArrayList<>();
        for(var row:rows) {
            if(row.result()==null || row.result().status()!=NativeRecipeJournal.Status.APPLIED
                    || !row.intent().method().equals(step.artifact().sha256()))return null;
            receipts.add(receipt(attempt.bound(),choice,row));
        }
        return ActivityRecipeEvidence.completed(job,attempt,step,List.of(),receipts)?List.copyOf(receipts):null;
    }

    private record LastSaved(NativeRecipeJournal.Entry row,NativeRecipeJournal.Participant participant) {}
    private boolean restored(List<NativeRecipeJournal.Entry> rows) {
        Map<String,LastSaved> latest=new HashMap<>();
        for(var row:rows) {
            for(var before:row.intent().before()) {
                var prior=latest.get(before.address());
                if(prior!=null && !prior.participant().equals(before))return false;
            }
            for(var after:row.result().after())latest.put(after.address(),new LastSaved(row,after));
        }
        return !latest.isEmpty() && latest.values().stream().allMatch(witness->participants.restored(witness.row(),witness.participant()));
    }

    @Override public boolean possibleEffect(ActionHandle operation) {
        thread();
        var run=live.get(operation.runId());
        return run!=null && run.pending!=null && run.pending.intent.operation().equals(operation.id()) && run.pending.possibleEffect;
    }
    @Override public void release(UUID id) {
        thread();
        var run=live.remove(id);
        if(run==null || run.pending==null || run.pending.result!=null || run.pending.possibleEffect)return;
        var pending=run.pending;
        pending.prepared.thenRun(()->noEffect(pending,Reason.INTERRUPTED,pending.owner.current().usage()));
    }

    boolean blocked(ValidatedRequest request,Effect effect,SurvivalGateway.Cell target) {
        thread();
        if(effect==Effect.MOVE || effect==Effect.OBSERVE)return false;
        if(journal.fenced())return true;
        var actor=((ActorValue)request.request().arguments().get("actor")).value();
        String actorAddress=FabricRecipeParticipants.actorAddress(actor);
        String targetAddress=FabricRecipeParticipants.chestAddress(new ContainerRef(target.dimension(),target.x(),target.y(),target.z()));
        for(var entry:journal.entries()) {
            var intent=entry.intent();
            var run=live.get(intent.attempt());
            if(run!=null && run.bound.equals(request) && world.holds(actor,intent.attempt()))continue;
            if(entry.result()!=null && entry.result().status()==NativeRecipeJournal.Status.NO_EFFECT)continue;
            if(intent.before().stream().noneMatch(p->p.address().equals(actorAddress) || p.address().equals(targetAddress)))continue;
            if(entry.result()!=null && entry.result().status()==NativeRecipeJournal.Status.APPLIED && accepted(entry))continue;
            return true;
        }
        return false;
    }

    private boolean accepted(NativeRecipeJournal.Entry entry) {
        var intent=entry.intent();
        for(var job:jobs.snapshot().jobs())if(job.id().equals(intent.root())) {
            for(var attempt:job.attempts())if(attempt.id().equals(intent.attempt()))
                return attempt.resourceReceipts().stream().anyMatch(r->r.choice()!=null && r.choice().journalToken().equals(intent.token()));
            return false;
        }
        return false;
    }

    private Jobs.Job current(Run run) {
        try {
            if(journal.fenced() || !jobs.ready() || !leases.ready() || !claims(run))return null;
            var job=new JobLeaseOwners(jobs,clock).execution(run.bound,run.correlation);
            if(!job.id().equals(run.root) || job.generation()!=run.generation || job.state()!=Jobs.State.ACTIVE
                    || job.current()==null || !job.current().open() || job.current().uncertain() || job.activity()==null
                    || job.activity().current()==null || !job.activity().current().id().equals(run.step)
                    || !job.current().id().equals(run.attempt) || jobs.stopPending(job.id(),job.generation(),job.origin())
                    || clock.millis()>job.current().allowance().deadlineEpochMillis()
                    || !world.holds(run.actor,run.attempt))return null;
            return job;
        }catch(SecurityException revoked){return null;}
    }
    private boolean claims(Run run) {
        var owner=new ResourceLeases.Owner(run.root,run.generation);
        UUID worldId=run.bound.context().scope().worldId();
        var resources=new LinkedHashSet<ResourceLeases.Resource>();
        for(var source:run.sources.containers())resources.add(ResourceLeases.Resource.recipeSource(worldId,source));
        if(!run.sources.containers().contains(run.destination))resources.add(ResourceLeases.Resource.facility(worldId,run.destination));
        if(run.choice.facility()!=null)resources.add(ResourceLeases.Resource.table(worldId,run.choice.facility().reference()));
        UUID group=null;
        var snapshot=leases.snapshot();
        for(var lease:snapshot.leases()) {
            if(!lease.owner().equals(owner) || !lease.origin().equals(run.bound.context())
                    || !lease.epoch().equals(snapshot.epoch()) || lease.state()!=ResourceLeases.State.ACTIVE
                    || lease.expires()<=world.tick() || !resources.remove(lease.resource()))continue;
            if(group==null)group=lease.group();else if(!group.equals(lease.group()))return false;
        }
        return resources.isEmpty();
    }
    private ChoiceAuthority permission(Run run) {
        return new ChoiceAuthority() {
            public boolean permits(TrustedContext caller,ActorRef actor,ContainerRef target,ChoicePhase phase) {
                if(!caller.equals(run.bound.context()) || !actor.equals(run.actor))return false;
                boolean source=run.sources.containers().contains(target), destination=target.equals(run.destination);
                if(!source && !destination)return false;
                Effect effect=phase==ChoicePhase.WITHDRAW && source?Effect.WITHDRAW:
                        phase==ChoicePhase.DEPOSIT && destination?Effect.TRANSFER:Effect.OBSERVE;
                return permitsAt(run,effect,target.dimension(),target.x(),target.y(),target.z());
            }
            public boolean permitsFacility(TrustedContext caller,ActorRef actor,FacilityRef target,ChoicePhase phase) {
                return caller.equals(run.bound.context()) && actor.equals(run.actor) && run.choice.facility()!=null
                        && run.choice.facility().reference().equals(target)
                        && permitsAt(run,phase==ChoicePhase.CRAFT?Effect.CRAFT:Effect.OBSERVE,target.dimension(),target.x(),target.y(),target.z());
            }
        };
    }
    private boolean permits(Run run,Stage phase) {
        var permission=permission(run);
        ChoicePhase nativePhase=ChoicePhase.valueOf(phase.name());
        if(!permission.permits(run.bound.context(),run.actor,run.destination,nativePhase))return false;
        for(var box:run.sources.containers())if(!permission.permits(run.bound.context(),run.actor,box,nativePhase))return false;
        return run.choice.facility()==null || permission.permitsFacility(run.bound.context(),run.actor,run.choice.facility().reference(),nativePhase);
    }
    private boolean permitsAt(Run run,Effect effect,String dimension,int x,int y,int z) {
        return authority.currentlyAllows(run.bound,run.correlation.runId(),effect,new EffectTarget(dimension,x,y,z))
                && world.permit(run.bound,effect,new SurvivalGateway.Cell(dimension,x,y,z))==null;
    }
    private static List<ContainerRef> containers(Run run,Stage phase) {
        return switch(phase) {case WITHDRAW->run.sources.containers();case DEPOSIT->List.of(run.destination);default->List.of();};
    }
    private Run bound(ValidatedRequest bound,RunCorrelation correlation) {
        var run=live.get(correlation.runId());
        return run==null || !run.bound.equals(bound) || !run.correlation.equals(correlation)?null:run;
    }
    private static ResourceDelivery.Receipt receipt(ValidatedRequest bound,RecipeChoice choice,NativeRecipeJournal.Entry entry) {
        var intent=entry.intent();
        long quantity=switch(intent.phase()) {
            case WITHDRAW->choice.batch(intent.batch()).bindings().size();
            case CRAFT->choice.resultCount();
            case DEPOSIT->choice.deliveryForBatch(intent.batch());
            default->throw new IllegalStateException("Exact recipe receipt phase");
        };
        var witness=new ResourceDelivery.ChoiceWitness(intent.root(),intent.step(),intent.attempt(),intent.generation(),intent.choice(),
                intent.batch(),intent.token(),intent.before().getFirst().slots(),entry.result().after().getFirst().slots());
        return ActivityRecipeEvidence.receipt(bound,id(intent.operation(),"receipt"),intent.attempt(),intent.operation(),
                id(intent.attempt(),"batch-"+intent.batch()),intent.phase(),quantity,witness);
    }
    private static UUID id(UUID parent,String label){return UUID.nameUUIDFromBytes((parent+":"+label).getBytes(StandardCharsets.UTF_8));}
    private static ExactRecipeResult waiting(boolean possibleEffect){return new ExactRecipeResult(true,possibleEffect,null,null);}
    private static ExactRecipeResult refusal(Reason reason,boolean possibleEffect){return new ExactRecipeResult(false,possibleEffect,null,reason);}
    @FunctionalInterface private interface Write { NativeRecipeJournal.Entry call() throws IOException; }
    private CompletableFuture<NativeRecipeJournal.Entry> write(Write action) {
        return CompletableFuture.supplyAsync(()->{try{return action.call();}catch(IOException error){throw new CompletionException(error);}},writer);
    }
    private void thread(){if(!world.onGameThread())throw new IllegalStateException("Exact recipe server thread");}
}
