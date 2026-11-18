package dev.aivillages.fabric;

import dev.aivillages.core.kernel.Budgets;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.CraftingTablePlacement;
import dev.aivillages.core.kernel.JobLeaseOwners;
import dev.aivillages.core.kernel.JobLifecycleStore;
import dev.aivillages.core.kernel.JobHistory;
import dev.aivillages.core.kernel.Jobs;
import dev.aivillages.core.kernel.Outcomes.Execution;
import dev.aivillages.core.kernel.Outcomes.ExecutionStatus;
import dev.aivillages.core.kernel.Outcomes.Reason;
import dev.aivillages.core.kernel.PlacementTransaction;
import dev.aivillages.core.kernel.RecipeTransaction.*;
import dev.aivillages.core.kernel.RequestCodec;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import static dev.aivillages.core.kernel.RecipeTransaction.fingerprint;

/** Native phase wrapper inside the same admitted interpreter, gateway and Jobs attempt. */
final class FabricActivityRecipeEffects implements SurvivalGateway.ExactRecipeEffects {
    private static final List<Stage> PHASES=List.of(Stage.WITHDRAW,Stage.CRAFT,Stage.DEPOSIT);
    private static final List<Stage> PLACEMENT=List.of(Stage.WITHDRAW,Stage.PLACE);
    private static final int MAX_LIVE=8;
    private final FabricGatewayWorld world;
    private final JobLifecycleStore jobs;
    private final JobHistory history;
    private final ResourceLeaseService leases;
    private final NativeRecipeJournal journal;
    private final AuthorityPolicy authority;
    private final Clock clock;
    private final Executor writer;
    private final FabricRecipeParticipants participants;
    private final FabricPlacementParticipant blocks;
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
        final FacilityRef site;
        final String digest;
        int observedPhases;
        boolean observed;
        FabricConcreteRecipeChoices.Context context;
        PlacementTransaction.Preflight placement;
        String placementCustody,placementSource;
        Pending pending;
        Run(Jobs.Job job,ValidatedRequest bound,ValidatedRequest exact,RunCorrelation correlation,RecipeChoice choice) {
            root=job.id(); step=job.activity().current().id(); attempt=job.current().id(); generation=job.current().generation();
            this.bound=bound; this.exact=exact; this.correlation=correlation; this.choice=choice;
            actor=job.current().worker(); sources=choice==null?RecipeSources.single(bound):ActivityRecipeEvidence.sources(exact,choice);
            destination=ResourceDelivery.destinationAddress(bound.request());
            site=choice==null?CraftingTablePlacement.target(bound.request()):null;
            digest=choice==null?fingerprint(RequestCodec.encode(bound.request())):choice.digest();
        }
    }
    private record Captured(List<FabricRecipeParticipants.Capture> inventories,FabricPlacementParticipant.Capture block) {
        List<NativeRecipeJournal.Participant> evidence() {
            var evidence=new ArrayList<>(FabricRecipeParticipants.evidence(inventories));
            if(block!=null)evidence.add(block.evidence());
            return List.copyOf(evidence);
        }
        void mark(Marker marker) {
            inventories.forEach(inventory->inventory.mark(marker));
            if(block!=null)block.mark(marker);
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

    FabricActivityRecipeEffects(FabricGatewayWorld world,JobLifecycleStore jobs,JobHistory history,ResourceLeaseService leases,
                                NativeRecipeJournal journal,AuthorityPolicy authority,Clock clock,Executor writer) {
        this.world=Objects.requireNonNull(world); this.jobs=Objects.requireNonNull(jobs); this.history=history;
        this.leases=Objects.requireNonNull(leases);
        this.journal=Objects.requireNonNull(journal); this.authority=Objects.requireNonNull(authority);
        this.clock=Objects.requireNonNull(clock); this.writer=Objects.requireNonNull(writer);
        participants=new FabricRecipeParticipants(world);
        blocks=new FabricPlacementParticipant(world.level());
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
            if(!durable(step) || !step.artifact().equals(method) || !id.equals(step.attempt()))return Reason.REQUEST_INVALID;
            var exact=step.exactChoice()?ActivityRecipeEvidence.exactInvocation(job,bound,step.choice(),step.quantities()):null;
            var run=new Run(job,bound,exact,correlation,step.exactChoice()?step.choice().recipe():null);
            if(!claims(run))return Reason.STALE_OBSERVATION;
            live.put(id,run);
            return null;
        } catch(SecurityException denied){return Reason.AUTHORITY_DENIED;}
        catch(IllegalArgumentException | NullPointerException invalid){return Reason.REQUEST_INVALID;}
    }

    @Override public boolean owns(UUID run){thread(); return live.containsKey(run);}
    @Override public Reason observePlacement(ValidatedRequest bound,RunCorrelation correlation,PlacementTransaction.Preflight captured) {
        thread();
        var run=bound(bound,correlation);
        if(run==null || run.site==null || run.observed || current(run)==null
                || !run.site.equals(captured.target()) || !run.sources.containers().getFirst().equals(captured.source()))
            return Reason.STALE_OBSERVATION;
        run.placement=captured;
        run.placementCustody=captured.custodyFingerprint();run.placementSource=captured.sourceFingerprint();
        run.observed=true;
        return null;
    }
    @Override public Reason observe(ValidatedRequest bound,RunCorrelation correlation,Budgets.Ledger usage) {
        thread();
        var run=bound(bound,correlation);
        if(run==null || run.site!=null || run.observed || current(run)==null)return Reason.STALE_OBSERVATION;
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
        var phases=run.site==null?PHASES:PLACEMENT;
        int batch=run.observedPhases/phases.size();
        if(batch>=(run.choice==null?1:run.choice.batches().size()) || phase!=phases.get(run.observedPhases%phases.size()))
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
            if(run.site==null) {
                if(phase==Stage.WITHDRAW)run.context=world.prepareChoice(run.exact,run.choice,batch,correlation.runId(),permission(run),run.sources);
                if(run.context==null)return refusal(Reason.STALE_OBSERVATION,false);
            }
            var captured=capture(run,containers);
            if(run.observedPhases>0 && !journal.entries(run.attempt).getLast().result().after().getFirst()
                    .equals(captured.evidence().getFirst()))return refusal(Reason.STALE_OBSERVATION,false);
            var intent=new NativeRecipeJournal.Intent(id(operation.id(),"journal"),run.root,run.step,run.attempt,run.generation,
                    operation.id(),run.correlation.artifact().sha256(),run.digest,batch,phase,
                    owner.allowance().deadlineEpochMillis(),captured.evidence());
            run.pending=new Pending(intent,owner,containers,stop,write(()->journal.prepare(intent)));
            return waiting(false);
        } catch(FabricConcreteRecipeChoices.Refused denied){return refusal(denied.reason,false);}
        catch(Budgets.Exhausted exhausted){return refusal(Reason.BUDGET_EXHAUSTED,false);}
        catch(RuntimeException unavailable){return refusal(Reason.TARGET_UNAVAILABLE,false);}
    }

    private Captured capture(Run run,List<ContainerRef> containers) {
        var block=run.site==null?null:blocks.capture(run.site,true);
        if(run.site!=null && block==null)throw new IllegalStateException("Native placement cell unavailable");
        return new Captured(participants.capture(run.actor,containers),block);
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
        Captured before;
        try {before=capture(run,pending.containers);}
        catch(RuntimeException unavailable){noEffect(pending,Reason.TARGET_UNAVAILABLE,usage.snapshot());return;}
        if(!intent.before().equals(before.evidence())) {noEffect(pending,Reason.STALE_OBSERVATION,usage.snapshot());return;}
        Budgets.Ledger.PhysicalEffect charge;
        try {charge=usage.beginPhysicalEffect();}
        catch(Budgets.Exhausted exhausted){noEffect(pending,Reason.BUDGET_EXHAUSTED,usage.snapshot());return;}
        String effect=fingerprint(intent.token().toString()), digest=intent.digest();
        var mutating=new Marker(effect,digest,MarkerPhase.MUTATING,"");
        NativeResult observed=null;
        try {
            before.mark(mutating);
            pending.possibleEffect=true;
            observed=run.site==null?performRecipe(run,pending,before):performPlacement(run,pending,before);
            pending.possibleEffect=observed.status()!=NativeRecipeJournal.Status.NO_EFFECT;
            pending.blocked=observed.blocked();
        } catch(RuntimeException unavailable){pending.blocked=Reason.INTERRUPTED;}
        finally {charge.settle(pending.possibleEffect);}
        if(observed==null || observed.status()==NativeRecipeJournal.Status.UNCERTAIN) {
            pending.blocked=Reason.INTERRUPTED;
            submitResult(pending,new NativeRecipeJournal.Result(intent.token(),digest,NativeRecipeJournal.Status.UNCERTAIN,List.of(),usage.snapshot()));
            return;
        }
        var result=new NativeRecipeJournal.Result(intent.token(),digest,observed.status(),observed.after().evidence(),
                usage.snapshot(),observed.placement());
        var marker=new Marker(effect,digest,MarkerPhase.RESULT,result.digest());
        try {observed.after().mark(marker);}
        catch(RuntimeException invalid){pending.blocked=Reason.INTERRUPTED;submitResult(pending,new NativeRecipeJournal.Result(intent.token(),digest,NativeRecipeJournal.Status.UNCERTAIN,List.of(),usage.snapshot()));return;}
        submitResult(pending,result);
    }

    private record NativeResult(NativeRecipeJournal.Status status,Captured after,Reason blocked,NativeRecipeJournal.Placement placement) {}
    private NativeResult performRecipe(Run run,Pending pending,Captured before) {
        var intent=pending.intent;
        var nativeResult=world.executeChoice(run.context,ChoicePhase.valueOf(intent.phase().name()));
        var after=capture(run,pending.containers);
        if(nativeResult.observation().kind()==ChoiceObservationKind.PRECHECK_NO_EFFECT
                && intent.before().equals(after.evidence()))
            return new NativeResult(NativeRecipeJournal.Status.NO_EFFECT,after,nativeResult.observation().reason(),null);
        if(nativeResult.observation().kind()==ChoiceObservationKind.APPLIED
                && FabricRecipeParticipants.applied(run.choice,intent.batch(),intent.phase(),before.inventories(),after.inventories())) {
            run.context=nativeResult.continuation();
            return new NativeResult(NativeRecipeJournal.Status.APPLIED,after,null,null);
        }
        return new NativeResult(NativeRecipeJournal.Status.UNCERTAIN,after,Reason.INTERRUPTED,null);
    }

    private NativeResult performPlacement(Run run,Pending pending,Captured before) {
        boolean withdrawing=pending.intent.phase()==Stage.WITHDRAW;
        var actual=withdrawing?world.withdrawTableItem(run.actor,run.placement,run.placementCustody,run.placementSource):
                world.placeTable(run.actor,run.placement,run.placementCustody,run.placementSource);
        var after=capture(run,pending.containers);
        if(!actual.committed() && pending.intent.before().equals(after.evidence()))
            return new NativeResult(NativeRecipeJournal.Status.NO_EFFECT,after,
                    actual.reason()==null?Reason.TARGET_UNAVAILABLE:actual.reason(),null);
        var table=FabricConcreteRecipeChoices.itemKey(world.level(),new ItemStack(Items.CRAFTING_TABLE));
        boolean expected=withdrawing
                ? actual.withdrawn()==1 && actual.consumed()==0 && !actual.placed()
                        && before.block().block().isAir() && before.block().evidence().equals(after.block().evidence())
                : actual.withdrawn()==0 && actual.consumed()==1 && actual.placed() && after.block().placedFrom(before.block());
        if(actual.reason()!=null || !expected
                || !FabricRecipeParticipants.placement(table,pending.intent.phase(),before.inventories(),after.inventories()))
            return new NativeResult(NativeRecipeJournal.Status.UNCERTAIN,after,Reason.INTERRUPTED,null);
        var evidence=new NativeRecipeJournal.Placement(run.placement.sourceIdentity(),run.placement.siteFingerprint(),
                actual.custodyBefore(),actual.custodyAfter(),withdrawing?"minecraft:air":"minecraft:crafting_table",actual.facilityIdentity());
        run.placementCustody=actual.custodyAfter();
        if(withdrawing)run.placementSource=actual.sourceAfter();
        return new NativeResult(NativeRecipeJournal.Status.APPLIED,after,null,evidence);
    }

    private void noEffect(Pending pending,Reason blocked,Map<Budgets.Kind,Long> usage) {
        pending.blocked=blocked;
        submitResult(pending,new NativeRecipeJournal.Result(pending.intent.token(),pending.intent.digest(),
                NativeRecipeJournal.Status.NO_EFFECT,pending.intent.before(),usage));
    }
    private void submitResult(Pending pending,NativeRecipeJournal.Result result){pending.result=write(()->journal.result(result));}

    boolean acknowledged(Jobs.Job job,Jobs.Attempt attempt,ResourceDelivery.Receipt receipt) {
        thread();
        if(journal.fenced() || job.activity()==null)return false;
        var step=job.activity().forAttempt(attempt.id());
        if(!durable(step) || step.exactChoice()!=(receipt.choice()!=null))return false;
        var choice=step.exactChoice()?step.choice().recipe():null;
        for(var entry:journal.entries(attempt.id())) {
            var intent=entry.intent();
            if(matching(job,attempt,step,intent) && intent.operation().equals(receipt.operationId()) && entry.result()!=null
                    && entry.result().status()==NativeRecipeJournal.Status.APPLIED
                    && receipt.equals(receipt(attempt.bound(),choice,entry)))return true;
        }
        return false;
    }

    /** A preceding step is available only from this process's acknowledgements or saved native evidence. */
    boolean settled(Jobs.Job job,ActivityPayload.Step step) {
        thread();
        if(journal.fenced() || !durable(step) || step.attempt()==null)return false;
        var attempt=job.attempts().stream().filter(row->row.id().equals(step.attempt())).findFirst().orElse(null);
        if(attempt==null || attempt.open() || attempt.uncertain())return false;
        var rows=journal.entries(step.attempt());
        try {
            var receipts=complete(job,attempt,step,rows);
            return receipts!=null && receipts.equals(attempt.resourceReceipts())
                    && (rows.stream().allMatch(row->appliedHere.contains(row.intent().token())) || restored(step,rows));
        }catch(RuntimeException unavailable){return false;}
    }

    Jobs.StoppedObservation observeStopped(Jobs.StoppedContext context,ActivityPayload.Choice choice) {
        thread();
        if(journal.fenced() || live.containsKey(context.attempt()))return Jobs.StoppedObservation.ambiguous("Native recipe writer unavailable or still live");
        try {
            var job=jobs.query(context.job(),context.origin());
            var step=job.activity()==null?null:job.activity().forAttempt(context.attempt());
            if(!durable(step) || !Objects.equals(step.choice(),choice) || !job.guard().equals(context.guard())
                    || !context.original().equals(job.current()))return Jobs.StoppedObservation.ambiguous("Original exact recipe attempt unavailable");
            var old=context.original();
            var rows=journal.entries(old.id());
            // Earlier integrated worlds may contain legacy placement attempts that never used this WAL.
            if(step.exactChoice() && rows.isEmpty() && old.effects()==0 && old.receiptCount()==0) {
                var result=new Execution(ExecutionStatus.INTERRUPTED,Reason.INTERRUPTED,0,null);
                return new Jobs.StoppedObservation(Jobs.StoppedKind.NO_EFFECT,
                        new Jobs.Report(old.id(),old.generation(),0,old.usage(),List.of(),result,List.of()),"Intact write-ahead journal has no native intent");
            }
            var receipts=complete(job,old,step,rows);
            boolean cancelledPartial=false;
            if(receipts==null && step.exactChoice() && job.cancellationOutcome()==Jobs.State.CANCELLED) {
                receipts=partial(job,old,step,rows);
                cancelledPartial=receipts!=null;
            }
            // A private acknowledgement plus exact current native participants may
            // settle cancellation in this process. It never certifies a save or
            // starts another execution. A new process must use restored provenance.
            boolean liveCancellation=cancelledPartial && rows.stream().allMatch(row->appliedHere.contains(row.intent().token()))
                    && participantAgreement(step,rows,false);
            if(receipts==null || !liveCancellation && !restored(step,rows))
                return Jobs.StoppedObservation.ambiguous("Required native participant saves are incomplete");
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
            var result=cancelledPartial?new Execution(ExecutionStatus.CANCELLED,Reason.CANCELLED,saved,null):
                    new Execution(ExecutionStatus.SUCCEEDED,null,saved,
                    new EvidenceRef(rows.getLast().intent().token().toString(),"native-recipe:1",
                            context.origin().scope().domainId().toString()));
            return new Jobs.StoppedObservation(cancelledPartial?Jobs.StoppedKind.PARTIAL:Jobs.StoppedKind.EFFECT_COMPLETE,
                    new Jobs.Report(old.id(),old.generation(),saved,usage,List.of(),result,receipts),
                    liveCancellation?"Original cancelled native prefix acknowledged here; exact current custody; no saved proof":
                            "Exact journal and native saved participants agree");
        }catch(RuntimeException unavailable){return Jobs.StoppedObservation.ambiguous("Exact recipe recovery evidence unavailable");}
    }

    private List<ResourceDelivery.Receipt> partial(Jobs.Job job,Jobs.Attempt attempt,ActivityPayload.Step step,
                                                  List<NativeRecipeJournal.Entry> rows) {
        var choice=step.choice().recipe();
        if(rows.isEmpty() || rows.size()>=choice.batches().size()*PHASES.size())return null;
        var receipts=new ArrayList<ResourceDelivery.Receipt>();
        for(var row:rows) {
            if(!matching(job,attempt,step,row.intent()) || row.result()==null
                    || row.result().status()!=NativeRecipeJournal.Status.APPLIED)return null;
            receipts.add(receipt(attempt.bound(),choice,row));
        }
        ActivityRecipeEvidence.quantity(job,attempt,step,receipts);
        return List.copyOf(receipts);
    }

    private List<ResourceDelivery.Receipt> complete(Jobs.Job job,Jobs.Attempt attempt,ActivityPayload.Step step,List<NativeRecipeJournal.Entry> rows) {
        var choice=step.exactChoice()?step.choice().recipe():null;
        if(rows.size()!=(choice==null?PLACEMENT.size():choice.batches().size()*PHASES.size()))return null;
        List<ResourceDelivery.Receipt> receipts=new ArrayList<>();
        for(var row:rows) {
            if(row.result()==null || row.result().status()!=NativeRecipeJournal.Status.APPLIED
                    || !matching(job,attempt,step,row.intent()) || choice==null && row.result().placement()==null)return null;
            receipts.add(receipt(attempt.bound(),choice,row));
        }
        return ActivityRecipeEvidence.completed(job,attempt,step,List.of(),receipts)?List.copyOf(receipts):null;
    }
    private static boolean matching(Jobs.Job job,Jobs.Attempt attempt,ActivityPayload.Step step,NativeRecipeJournal.Intent intent) {
        String digest=step.exactChoice()?step.choice().sha256():fingerprint(RequestCodec.encode(step.invocation()));
        return intent.root().equals(job.id()) && intent.step().equals(step.id()) && intent.attempt().equals(attempt.id())
                && intent.generation()==attempt.generation() && intent.choice().equals(digest)
                && intent.method().equals(step.artifact().sha256()) && intent.deadline()==job.allowance().deadlineEpochMillis();
    }
    private static boolean durable(ActivityPayload.Step step) {
        return step!=null && step.journaledProduction();
    }

    private record LastSaved(NativeRecipeJournal.Entry row,NativeRecipeJournal.Participant participant) {}
    private boolean restored(ActivityPayload.Step step,List<NativeRecipeJournal.Entry> rows) {
        return participantAgreement(step,rows,true);
    }
    private boolean participantAgreement(ActivityPayload.Step step,List<NativeRecipeJournal.Entry> rows,boolean savedOnly) {
        Map<String,LastSaved> latest=new HashMap<>();
        for(var row:rows) {
            for(var before:row.intent().before()) {
                var prior=latest.get(before.address());
                if(prior!=null && !prior.participant().equals(before))return false;
            }
            for(var after:row.result().after())latest.put(after.address(),new LastSaved(row,after));
        }
        FacilityRef site=step.exactChoice()?null:CraftingTablePlacement.target(step.invocation());
        return !latest.isEmpty() && latest.values().stream().allMatch(witness->
                site!=null && witness.participant().address().equals(FabricPlacementParticipant.address(site))
                        ? blocks.restored(site,witness.row(),witness.participant())
                        : savedOnly?participants.restored(witness.row(),witness.participant()):
                                participants.liveCurrent(witness.row(),witness.participant()));
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
        String blockAddress=FabricPlacementParticipant.address(new FacilityRef(target.dimension(),target.x(),target.y(),target.z()));
        for(var entry:journal.entries()) {
            var intent=entry.intent();
            var run=live.get(intent.attempt());
            if(run!=null && run.bound.equals(request) && world.holds(actor,intent.attempt()))continue;
            if(entry.result()!=null && entry.result().status()==NativeRecipeJournal.Status.NO_EFFECT)continue;
            if(intent.before().stream().noneMatch(p->p.address().equals(actorAddress) || p.address().equals(targetAddress)
                    || p.address().equals(blockAddress)))continue;
            if(entry.result()!=null && entry.result().status()==NativeRecipeJournal.Status.APPLIED && accepted(entry))continue;
            return true;
        }
        return false;
    }

    private boolean accepted(NativeRecipeJournal.Entry entry) {
        var intent=entry.intent();
        try {
            var job=jobs.snapshot().jobs().stream().filter(row->row.id().equals(intent.root())).findFirst().orElse(null);
            // An acknowledged Job archive is a read model, never a new owner or
            // proof from current inventory counts. Missing/unknown history fences.
            if(job==null && history!=null && history.ready())job=history.archived(intent.root());
            if(job==null || job.activity()==null)return false;
            var attempt=job.attempts().stream().filter(row->row.id().equals(intent.attempt())).findFirst().orElse(null);
            var step=attempt==null?null:job.activity().forAttempt(attempt.id());
            if(!durable(step) || !matching(job,attempt,step,intent))return false;
            var expected=receipt(attempt.bound(),step.exactChoice()?step.choice().recipe():null,entry);
            return attempt.resourceReceipts().contains(expected);
        }catch(RuntimeException unavailable){return false;}
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
        if(run.site!=null)resources.add(ResourceLeases.Resource.space(worldId,CraftingTablePlacement.workspace(run.bound.request())));
        else {
            if(!run.sources.containers().contains(run.destination))resources.add(ResourceLeases.Resource.facility(worldId,run.destination));
            if(run.choice.facility()!=null)resources.add(ResourceLeases.Resource.table(worldId,run.choice.facility().reference()));
        }
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
        if(run.site!=null) {
            var source=run.sources.containers().getFirst();
            return permitsAt(run,phase==Stage.WITHDRAW?Effect.WITHDRAW:Effect.OBSERVE,source.dimension(),source.x(),source.y(),source.z())
                    && permitsAt(run,phase==Stage.PLACE?Effect.PLACE:Effect.OBSERVE,run.site.dimension(),run.site.x(),run.site.y(),run.site.z());
        }
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
        if(choice==null)return placementReceipt(bound,entry);
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
    private static ResourceDelivery.Receipt placementReceipt(ValidatedRequest bound,NativeRecipeJournal.Entry entry) {
        var intent=entry.intent();var actual=Objects.requireNonNull(entry.result().placement());
        var actor=((ActorValue)bound.request().arguments().get("actor")).value();
        var source=((ContainerValue)bound.request().arguments().get("source")).value();
        var evidence=new PlacementTransaction.Evidence(CraftingTablePlacement.target(bound.request()),actual.sourceIdentity(),actual.siteFingerprint(),
                actual.custodyBefore(),actual.custodyAfter(),actual.blockState(),actual.facilityIdentity());
        return new ResourceDelivery.Receipt(id(intent.operation(),"receipt"),bound.context(),intent.attempt(),intent.operation(),
                id(intent.attempt(),"batch-0"),actor,ResourceLeases.point(source),CraftingTablePlacement.address(bound.request()),
                ResourceDelivery.CRAFTING_TABLE,intent.phase(),1,new ResourceDelivery.BlockPosition(source.dimension(),source.x(),source.y(),source.z()),
                List.of(),null,evidence);
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
