package dev.aivillages.core.kernel.runtime.activity;

import dev.aivillages.core.kernel.*;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Jobs.*;

/** Callable A boundary. Deterministic proposals are data; Jobs owns every publication and result. */
public final class ActivityRuntime {
    public record Proposal(int version,UUID id,UUID root,Guard expected,String token,CapabilityRequest request,
                           ArtifactRef artifact,ActivityPayload.Choice choice,ActivityPayload.Quantities quantities,
                           Budgets.Limits allowance,int plannedSteps) {
        public Proposal {
            Objects.requireNonNull(id);Objects.requireNonNull(root);Objects.requireNonNull(expected);Objects.requireNonNull(request);
            Objects.requireNonNull(artifact);Objects.requireNonNull(quantities);Objects.requireNonNull(allowance);
            if(version!=1 || token==null || !token.matches("[A-Za-z0-9._:-]{1,64}") || plannedSteps<1 || plannedSteps>8)
                throw new IllegalArgumentException("Proposal envelope");
        }
        public String signature(){return ActivityPayload.digest(RequestCodec.encode(request)+"\n"+artifact+"\n"+choice+"\n"+quantities);}
        public String bodyDigest(){var maxima=new TreeMap<String,Long>();allowance.maxima().forEach((k,v)->maxima.put(k.name(),v));
            return ActivityPayload.digest(signature()+"\n"+StrictJson.canonical(Map.of("maxima",maxima,"deadline",allowance.deadlineEpochMillis(),"plannedSteps",(long)plannedSteps)));}
    }
    public enum Validation { UNAVAILABLE, INVALID, VALID }
    /** Joint adapter obligation: exact recipe semantics AND original prepared-effect observations.
     * A09 deliberately supplies no implementation capable of generalized recipe execution/recovery. */
    public interface SemanticAdapter {
        Validation validate(ActivityPayload.Choice choice,ActivityPayload.Quantities quantities,CapabilityRequest exactBindings);
        StoppedObservation observeStopped(StoppedContext original,ActivityPayload.Choice choice);
        static SemanticAdapter unavailable(){return new SemanticAdapter(){
            public Validation validate(ActivityPayload.Choice c,ActivityPayload.Quantities q,CapabilityRequest r){return Validation.UNAVAILABLE;}
            public StoppedObservation observeStopped(StoppedContext c,ActivityPayload.Choice choice){return StoppedObservation.ambiguous("Exact-choice host adapter unavailable");}
        };}
    }
    public record Availability(boolean known,long observedTick,long destinationQuantity,long actorQuantity,String blocker) {
        public Availability {
            if(observedTick<0 || destinationQuantity<0 || destinationQuantity>8192 || actorQuantity<0 || actorQuantity>512
                    || blocker==null || blocker.length()>128 || !known && (destinationQuantity!=0 || actorQuantity!=0))
                throw new IllegalArgumentException("Bounded scoped availability");
        }
        public static Availability unavailable(){return new Availability(false,0,0,0,"current availability unavailable");}
    }
    @FunctionalInterface public interface ObservationSource { Availability observe(Job authorized); }
    public enum Next { PREPARE, PREPARED_FOR_DISPATCH, EXECUTING, RECOVER_OR_SETTLE, INTEGRATION_PENDING, CANCEL_OR_SETTLE, TERMINAL, EXPIRED, LEGACY }
    public record AppliedEffect(UUID step,UUID attempt,long generation,UUID run,UUID operation,UUID batch,UUID receipt,String stage,long quantity){}
    public record Snapshot(int schema,UUID world,UUID root,long revision,ActivityPayload.GoalMode goalMode,
                           CapabilityRequest goal,long confirmedCredit,long remainingGoal,ActorRef actor,long generation,UUID attempt,
                           State state,boolean cancellationIntent,List<ActivityPayload.Checkpoint> checkpoints,int verifiedCheckpoints,
                           Integer plannedSteps,ActivityPayload.Evidence lastPhysicalEvidence,UUID currentStep,Next next,String blocker,
                           Budgets.Limits remaining,Map<Budgets.Kind,Long> cumulativeUsage,long originalDeadline,
                           Availability availability,List<AppliedEffect> acceptedEffects,List<CapabilityId> supportedPrimitives,int repeatedSignatures,boolean plannerEnabled) {
        public Snapshot {checkpoints=List.copyOf(checkpoints);cumulativeUsage=Map.copyOf(cumulativeUsage);supportedPrimitives=List.copyOf(supportedPrimitives);acceptedEffects=List.copyOf(acceptedEffects);if(acceptedEffects.size()>64)throw new IllegalArgumentException("Bounded effect projection");}
    }
    private final JobLifecycleStore jobs;
    private final ObservationSource observation;
    public ActivityRuntime(JobLifecycleStore jobs,ObservationSource observation) {
        this.jobs=Objects.requireNonNull(jobs);this.observation=Objects.requireNonNull(observation);
    }
    public Change create(UUID root,CapabilityRequest goal,TrustedContext caller,ObservationRef observed,Budgets.Limits limits,int plannedSteps) {
        return jobs.createActivity(root,goal,caller,observed,limits,plannedSteps);
    }
    public Change prepare(Proposal proposal,TrustedContext caller){return jobs.prepareActivity(proposal,caller);}
    public Change cancel(UUID root,Guard expected,TrustedContext caller){return jobs.cancel(root,expected,caller);}
    public Snapshot snapshot(UUID root,TrustedContext caller) {
        Job job=jobs.query(root,caller);if(job.parent()!=null)throw new IllegalArgumentException("Activity root required");
        var p=job.activity();var a=job.current();boolean uncertain=a!=null&&a.open()&&job.state()!=State.ACTIVE;
        boolean cancelling=job.cancellationOutcome()!=null||job.state()==State.CANCELLING;
        long goal=((IntValue)job.request().request().arguments().get("amount")).value();
        long credit=job.fulfilled();var checkpoints=p==null?List.<ActivityPayload.Checkpoint>of():p.checkpoints();
        Next next=p==null?Next.LEGACY:job.state().terminal()?Next.TERMINAL:cancelling?Next.CANCEL_OR_SETTLE:uncertain?Next.RECOVER_OR_SETTLE:
                job.state()==State.ACTIVE?Next.EXECUTING:jobs.deadlineExpired(root,caller)?Next.EXPIRED:p.blocker()==ActivityPayload.Blocker.INTEGRATION_PENDING?Next.INTEGRATION_PENDING:
                p.current()!=null?Next.PREPARED_FOR_DISPATCH:Next.PREPARE;
        String blocker=next==Next.EXPIRED?"original absolute deadline expired":uncertain?"uncertain original effect; no replay":p!=null&&p.blocker()==ActivityPayload.Blocker.INTEGRATION_PENDING?
                "exact-choice semantic/host adapter unavailable":job.reason()==null?"":job.reason().name();
        Availability available;
        try{available=Objects.requireNonNull(observation.observe(job));}catch(RuntimeException unavailable){available=Availability.unavailable();}
        Budgets.Limits limits;
        try{limits=jobs.remainingAllowance(root,caller);}
        catch(SecurityException readOnlyHistory) {
            var maxima=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);
            for(var k:Budgets.Kind.values())maxima.put(k,job.state().terminal()&&job.attempts().stream().noneMatch(Attempt::open)?Math.max(0,job.allowance().maximum(k)-job.usage().getOrDefault(k,0L)):0L);
            limits=new Budgets.Limits(maxima,job.allowance().deadlineEpochMillis());
        }
        int repeated=p==null?0:p.steps().size()-(int)p.steps().stream().map(ActivityPayload.Step::signature).distinct().count();
        var effects=new ArrayList<AppliedEffect>();
        for(var c:checkpoints) {
            var original=job.attempts().stream().filter(v->v.id().equals(c.attempt())).findFirst().orElseThrow();
            for(var r:original.resourceReceipts())effects.add(new AppliedEffect(c.step(),original.id(),original.generation(),r.runId(),r.operationId(),r.batchId(),r.receiptId(),r.stage().name(),r.quantity()));
            for(var r:original.receipts())effects.add(new AppliedEffect(c.step(),original.id(),original.generation(),r.runId(),null,r.batchId(),r.receiptId(),r.stage().name(),r.wheat()));
        }
        return new Snapshot(1,jobs.snapshot().worldId(),root,job.revision(),p==null?null:p.mode(),job.request().request(),credit,
                Math.max(0,goal-credit),a==null?job.responsible():a.worker(),job.generation(),a==null?null:a.id(),job.state(),cancelling,
                checkpoints,checkpoints.size(),p==null?null:p.plannedSteps(),checkpoints.isEmpty()?null:checkpoints.getLast().evidence(),
                p==null?null:p.currentStep(),next,blocker,limits,job.usage(),job.allowance().deadlineEpochMillis(),available,effects,
                List.of(OakLogDelivery.ID,BirchLogDelivery.ID,CropDelivery.ID),repeated,false);
    }
}
