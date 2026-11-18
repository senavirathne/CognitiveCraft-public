package dev.aivillages.core.kernel.runtime.activity;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.production.RecipeChoice;
import dev.aivillages.core.kernel.production.planning.PlanningModel;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Jobs.*;

/** Immutable data embedded in the root Job. No second lifecycle, inventory or writer. */
public record ActivityPayload(GoalMode mode, Policy policy, int plannedSteps, UUID currentStep,
                              List<Step> steps, List<Checkpoint> checkpoints, Blocker blocker,ProductionControl production) {
    public static final int SCHEMA=1, MAX_STEPS=8, CHOICE_BYTES=768, EXACT_CHOICE_BYTES=24_576;
    public enum GoalMode { ACQUIRE_NEW, MAKE_NEW, SUPPLY }
    public enum Policy { ORIGINAL_ACTOR_SETTLED_EFFECTS }
    public enum Blocker { AWAITING_PROPOSAL, NONE, INTEGRATION_PENDING, NEEDS_ATTENTION, SETTLED_CANCELLED_CUSTODY }
    public enum Evidence { LIVE_RECEIPTS }
    public record ProductionControl(Cuboid acquisition, int decisions, PlanningModel.Memory memory,
                                    String blocker, long nextCheckMillis,ActivityPlan plan,
                                    Map<UUID,List<String>> coveredActions) {
        public static final int MAX_DECISIONS=32;
        public ProductionControl(Cuboid acquisition,int decisions,PlanningModel.Memory memory,String blocker,long nextCheckMillis) {
            this(acquisition,decisions,memory,blocker,nextCheckMillis,null,Map.of());
        }
        public ProductionControl {
            Objects.requireNonNull(memory);Objects.requireNonNull(blocker);
            var actionBindings=new HashMap<UUID,List<String>>();
            coveredActions.forEach((id,tokens)->actionBindings.put(Objects.requireNonNull(id),List.copyOf(tokens)));
            coveredActions=Map.copyOf(actionBindings);
            if(decisions<0 || decisions>MAX_DECISIONS || blocker.length()>128 || nextCheckMillis<0)
                throw new IllegalArgumentException("Bounded production coordination");
            var covered=coveredActions.values().stream().flatMap(List::stream).toList();
            if(plan==null && !coveredActions.isEmpty() || coveredActions.size()>MAX_STEPS
                    || coveredActions.values().stream().anyMatch(List::isEmpty)
                    || new HashSet<>(covered).size()!=covered.size()
                    || plan!=null && !plan.tokens().containsAll(covered))
                throw new IllegalArgumentException("Exact prior action token coverage");
            if(acquisition!=null && Math.multiplyExact(Math.multiplyExact((long)acquisition.maxX()-acquisition.minX()+1,
                    (long)acquisition.maxY()-acquisition.minY()+1),(long)acquisition.maxZ()-acquisition.minZ()+1)>256)
                throw new IllegalArgumentException("Bounded original acquisition area");
        }
        public static ProductionControl initial(Cuboid source){return new ProductionControl(source,0,PlanningModel.Memory.empty(),"",0);}
        public ProductionControl next(PlanningModel.Memory next,String reason,long checkAt){
            return new ProductionControl(acquisition,Math.addExact(decisions,1),next,reason,checkAt,plan,coveredActions);
        }
        public ProductionControl prepared(PlanningModel.Memory next,ActivityPlan pinned,UUID proposal,List<String> tokens) {
            if(plan!=null && !plan.equals(pinned))throw new IllegalArgumentException("Original pinned production plan changed");
            var actions=new HashMap<>(coveredActions);
            if(actions.putIfAbsent(proposal,List.copyOf(tokens))!=null)throw new IllegalArgumentException("Duplicate authoritative proposal");
            return new ProductionControl(acquisition,Math.addExact(decisions,1),next,"",0,pinned,actions);
        }
    }
    public ActivityPayload(GoalMode mode,Policy policy,int plannedSteps,UUID currentStep,List<Step> steps,
                           List<Checkpoint> checkpoints,Blocker blocker) {
        this(mode,policy,plannedSteps,currentStep,steps,checkpoints,blocker,null);
    }
    /** Opaque canonical bytes are data only. Reload epoch is never semantic identity. */
    public record Choice(String base64, String sha256, boolean exactRecipe) {
        public Choice(String base64,String sha256){this(base64,sha256,false);}
        public Choice {
            Objects.requireNonNull(base64);Objects.requireNonNull(sha256);
            int maximum=exactRecipe?EXACT_CHOICE_BYTES:CHOICE_BYTES;
            if(base64.length()>maximum*4/3)throw new IllegalArgumentException("Recipe choice byte bound");
            byte[] bytes=Base64.getDecoder().decode(base64);
            if(bytes.length==0 || bytes.length>maximum || !Base64.getEncoder().encodeToString(bytes).equals(base64)
                    || !digest(bytes).equals(sha256))throw new IllegalArgumentException("Recipe choice identity");
        }
        public static Choice of(RecipeChoice recipe) {
            var bytes=recipe.canonicalBytes();
            return new Choice(Base64.getEncoder().encodeToString(bytes),recipe.digest(),true);
        }
        public RecipeChoice recipe() {
            if(!exactRecipe)throw new IllegalArgumentException("Opaque activity choice has no exact recipe authority");
            return RecipeChoice.fromCanonical(new String(Base64.getDecoder().decode(base64),StandardCharsets.UTF_8));
        }
        @Override public String toString(){return "Choice[base64="+base64+", sha256="+sha256+"]";}
    }
    /** Contract arithmetic; acceptance of this shape is not native recipe support. */
    public record Quantities(long demand,long yield,long batches,long produced,long allocation,long surplus,long remainders) {
        public Quantities {
            if(demand<1 || yield<1 || batches<1 || produced<1 || allocation<1 || allocation>demand
                    || surplus<0 || remainders<0 || batches!=Math.addExact(demand/yield,demand%yield==0?0:1)
                    || produced!=Math.multiplyExact(batches,yield) || Math.addExact(allocation,surplus)!=produced)
                throw new IllegalArgumentException("Demand, physical production and surplus are distinct");
        }
        public static Quantities exact(long quantity){return new Quantities(quantity,1,quantity,quantity,quantity,0,0);}
        public boolean exact(){return demand==produced && demand==allocation && surplus==0 && remainders==0;}
    }
    public record Step(UUID id,UUID proposal,String token,String bodyDigest,String signature,long creditBefore,
                       long quantity,ArtifactRef artifact,Choice choice,Quantities quantities,Budgets.Limits allowance,
                       long preparedRevision,UUID attempt,CapabilityRequest invocation,long goalCredit) {
        public Step(UUID id,UUID proposal,String token,String bodyDigest,String signature,long creditBefore,
                    long quantity,ArtifactRef artifact,Choice choice,Quantities quantities,Budgets.Limits allowance,
                    long preparedRevision,UUID attempt) {
            this(id,proposal,token,bodyDigest,signature,creditBefore,quantity,artifact,choice,quantities,allowance,
                    preparedRevision,attempt,null,quantity);
        }
        public Step {
            Objects.requireNonNull(id);Objects.requireNonNull(proposal);Objects.requireNonNull(artifact);
            Objects.requireNonNull(quantities);Objects.requireNonNull(allowance);
            if(!hash(artifact.sha256()) || token==null || !token.matches("[A-Za-z0-9._:-]{1,64}") || !hash(bodyDigest) || !hash(signature)
                    || creditBefore<0 || quantity<1 || quantity>64 || quantities.demand()!=quantity || preparedRevision<1
                    || (goalCredit!=0 && goalCredit!=quantity) || invocation==null && goalCredit!=quantity
                    || invocation!=null && (!artifact.capability().equals(invocation.capability())
                        || !(invocation.arguments().get("amount") instanceof IntValue amount) || amount.value()!=quantity)
                    || (choice!=null && choice.exactRecipe())!=(invocation!=null && RecipeProduction.is(artifact.capability())))
                throw new IllegalArgumentException("Bounded activity preparation");
        }
        public boolean integrated(){return invocation!=null;}
        public boolean exactChoice(){return choice!=null && choice.exactRecipe();}
        public boolean journaledProduction(){return exactChoice() || integrated() && CraftingTablePlacement.is(invocation.capability());}
        public long creditedToGoal(Attempt a){return Math.min(a.credited(),goalCredit);}
        public Step assigned(UUID original){return new Step(id,proposal,token,bodyDigest,signature,creditBefore,quantity,
                artifact,choice,quantities,allowance,preparedRevision,Objects.requireNonNull(original),invocation,goalCredit);}
    }
    /** References to actual original receipts. Never a new receipt or stock allocation. */
    public record Checkpoint(UUID step,UUID attempt,long generation,long quantity,List<UUID> receipts,Evidence evidence) {
        public Checkpoint {
            Objects.requireNonNull(step);Objects.requireNonNull(attempt);Objects.requireNonNull(evidence);receipts=List.copyOf(receipts);
            if(generation<1 || quantity<1 || quantity>64 || receipts.isEmpty() || receipts.size()>64
                    || new HashSet<>(receipts).size()!=receipts.size())throw new IllegalArgumentException("Checkpoint references");
        }
    }
    public ActivityPayload {
        Objects.requireNonNull(mode);Objects.requireNonNull(policy);Objects.requireNonNull(blocker);
        steps=List.copyOf(steps);checkpoints=List.copyOf(checkpoints);
        if(plannedSteps<1 || plannedSteps>MAX_STEPS || steps.size()>MAX_STEPS || checkpoints.size()>MAX_STEPS
                || checkpoints.size()>plannedSteps || checkpoints.size()>steps.size()
                || steps.stream().map(Step::id).distinct().count()!=steps.size()
                || steps.stream().map(Step::token).distinct().count()!=steps.size()
                || steps.stream().map(Step::proposal).distinct().count()!=steps.size()
                || checkpoints.stream().map(Checkpoint::step).distinct().count()!=checkpoints.size()
                || checkpoints.stream().mapToInt(c->c.receipts().size()).sum()>64
                || currentStep!=null && steps.stream().noneMatch(s->s.id().equals(currentStep)))
            throw new IllegalArgumentException("Finite activity history");
    }
    public static ActivityPayload initial(CapabilityId capability,int planned) {
        GoalMode mode=RecipeProduction.is(capability)||CraftingTablePlacement.is(capability)?GoalMode.MAKE_NEW:GoalMode.ACQUIRE_NEW;
        return new ActivityPayload(mode,Policy.ORIGINAL_ACTOR_SETTLED_EFFECTS,planned,null,List.of(),List.of(),Blocker.AWAITING_PROPOSAL);
    }
    public Step current(){return currentStep==null?null:steps.stream().filter(s->s.id().equals(currentStep)).findFirst().orElseThrow();}
    public Step forAttempt(UUID attempt){return steps.stream().filter(s->attempt.equals(s.attempt())).findFirst().orElse(null);}
    public ActivityPayload planning(ProductionControl control){
        return new ActivityPayload(mode,policy,plannedSteps,currentStep,steps,checkpoints,blocker,Objects.requireNonNull(control));
    }
    /** Typed stopped-owner accounting only; this disposition grants no completion or future execution. */
    public ActivityPayload cancelledCustody() {
        return new ActivityPayload(mode,policy,plannedSteps,currentStep,steps,checkpoints,Blocker.SETTLED_CANCELLED_CUSTODY,production);
    }
    public ActivityPayload prepared(Step step,int planned,boolean executable) {
        if(currentStep!=null)throw new IllegalArgumentException("One prepared writer");
        var next=new ArrayList<>(steps);next.add(step);
        return new ActivityPayload(mode,policy,planned,step.id(),next,checkpoints,executable?Blocker.NONE:Blocker.INTEGRATION_PENDING,production);
    }
    public ActivityPayload assigned(UUID attempt) {
        Step selected=Objects.requireNonNull(current());
        if(selected.attempt()!=null)throw new IllegalArgumentException("Preparation already assigned");
        return new ActivityPayload(mode,policy,plannedSteps,currentStep,steps.stream().map(s->s.id().equals(currentStep)?s.assigned(attempt):s).toList(),checkpoints,blocker,production);
    }
    public ActivityPayload completed(Attempt attempt) {
        Step selected=Objects.requireNonNull(current());
        if(!attempt.id().equals(selected.attempt()) || attempt.open() || attempt.terminal()==null
                || attempt.terminal().status()!=Outcomes.ExecutionStatus.SUCCEEDED || attempt.credited()!=selected.quantity())
            throw new IllegalArgumentException("Settled actual segment required");
        var refs=new ArrayList<UUID>();attempt.receipts().forEach(r->refs.add(r.receiptId()));attempt.resourceReceipts().forEach(r->refs.add(r.receiptId()));
        var history=new ArrayList<>(checkpoints);history.add(new Checkpoint(selected.id(),attempt.id(),attempt.generation(),attempt.credited(),refs,Evidence.LIVE_RECEIPTS));
        return new ActivityPayload(mode,policy,plannedSteps,null,steps,history,Blocker.AWAITING_PROPOSAL,production);
    }
    /** Load validation binds every reference back to the sole authoritative Job and original attempts. */
    public void validate(Job job) {
        if(job.parent()!=null || job.preparation()!=null || !job.children().isEmpty() || !job.dependencies().isEmpty()
                || !ResourceDelivery.isDelivery(job.request().request().capability()) || job.attempts().size()>steps.size())
            throw new IllegalArgumentException("Activity root ownership");
        var selected=current();
        if(selected!=null && blocker==Blocker.NONE && !selected.integrated() && (selected.choice()!=null || !selected.quantities().exact()
                || !LogAcquisition.is(job.request().request().capability()) && !CropDelivery.ID.equals(job.request().request().capability())))
            throw new IllegalArgumentException("Unvalidated semantics cannot authorize execution");
        long goal=((IntValue)job.request().request().arguments().get("amount")).value(),verified=0;
        if(mode!=initial(job.request().request().capability(),1).mode())throw new IllegalArgumentException("Goal mode");
        if(production!=null) {
            var m=production.memory();
            var budget=ProductionActivityPlanner.budget(job);
            if(steps.size()>production.decisions() || production.acquisition()!=null
                    && !production.acquisition().dimension().equals(job.responsible().dimension())
                    || !steps.stream().map(Step::proposal).collect(java.util.stream.Collectors.toSet()).containsAll(production.coveredActions().keySet())
                    || production.plan()!=null && steps.size()!=production.coveredActions().size()
                    || !m.originalBudgetDigest().isEmpty() && (!job.id().equals(m.root())
                        || !job.origin().scope().worldId().equals(m.world()) || !job.origin().equals(m.context())
                        || !job.origin().principal().id().equals(m.owner()) || m.originalDeadlineMillis()!=job.allowance().deadlineEpochMillis()
                        || !budget.initialDigest().equals(m.originalBudgetDigest())
                        || Arrays.stream(PlanningModel.Counter.values()).anyMatch(k->m.observedUsage().get(k)>budget.used().get(k))))
                throw new IllegalArgumentException("Foreign production memory or decision history");
        }
        var assigned=new HashSet<UUID>();long priorCredit=0,priorRevision=0;int index=0,cancelledCustody=0;
        for(Step s:steps) {
            if(s.creditBefore()!=priorCredit || s.preparedRevision()<=priorRevision || s.attempt()==null&&!s.id().equals(currentStep))
                throw new IllegalArgumentException("Ordered historical preparation");
            priorRevision=s.preparedRevision();
            if(s.preparedRevision()>job.revision() || (s.integrated() ?
                    !ActivityRecipeEvidence.permittedInvocation(job,s.invocation(),s.goalCredit()) :
                    !s.artifact().capability().equals(job.request().request().capability()))
                    || Math.addExact(s.creditBefore(),s.goalCredit())>goal || s.allowance().deadlineEpochMillis()>job.allowance().deadlineEpochMillis())
                throw new IllegalArgumentException("Original activity envelope");
            if(s.exactChoice())ActivityRecipeEvidence.exactInvocation(job,
                    new ValidatedRequest(s.invocation(),job.origin(),job.request().observation()),s.choice(),s.quantities());
            for(var k:Budgets.Kind.values())if(s.allowance().maximum(k)>job.allowance().maximum(k))throw new IllegalArgumentException("Step allowance");
            if(s.attempt()!=null) {
                if(!assigned.add(s.attempt()))throw new IllegalArgumentException("Duplicate step writer");
                Attempt a=job.attempts().get(index++);
                if(!a.id().equals(s.attempt()))throw new IllegalArgumentException("Original attempt order");
                priorCredit=Math.addExact(priorCredit,s.creditedToGoal(a));
                if(s.integrated()&&!a.bound().request().equals(s.invocation()))
                    throw new IllegalArgumentException("Changed integrated invocation");
                if(!a.open() && a.effects()>0 && !ActivityRecipeEvidence.completed(job,a,s,a.receipts(),a.resourceReceipts())) {
                    if(blocker!=Blocker.SETTLED_CANCELLED_CUSTODY || !s.id().equals(currentStep)
                            || job.state()!=State.CANCELLED || job.cancellationOutcome()!=State.CANCELLED
                            || a.terminal()==null || a.terminal().status()!=Outcomes.ExecutionStatus.CANCELLED
                            || a.receiptCount()==0)
                        throw new IllegalArgumentException("Unclassified activity effects");
                    cancelledCustody++;
                }
                if(((IntValue)a.bound().request().arguments().get("amount")).value()!=s.quantity()
                        || !a.worker().equals(job.responsible()) || a.executions().stream().anyMatch(e->!e.artifact().equals(s.artifact())))
                    throw new IllegalArgumentException("Prepared original attempt");
            }
        }
        if((blocker==Blocker.SETTLED_CANCELLED_CUSTODY)!=(cancelledCustody==1))
            throw new IllegalArgumentException("Cancelled custody disposition without its original partial owner");
        if(checkpoints.size()!=job.attempts().stream().filter(a->!a.open() && a.terminal()!=null && a.terminal().status()==Outcomes.ExecutionStatus.SUCCEEDED).count())
            throw new IllegalArgumentException("Verified history cannot be erased");
        if(currentStep!=null && !steps.getLast().id().equals(currentStep) || currentStep==null && job.current()!=null && job.current().open())
            throw new IllegalArgumentException("Current prepared owner");
        if(assigned.size()!=job.attempts().size())throw new IllegalArgumentException("Unprepared activity attempt");
        for(Checkpoint c:checkpoints) {
            Step s=steps.stream().filter(v->v.id().equals(c.step())).findFirst().orElseThrow();
            Attempt a=job.attempts().stream().filter(v->v.id().equals(c.attempt())).findFirst().orElseThrow();
            var refs=new ArrayList<UUID>();a.receipts().forEach(r->refs.add(r.receiptId()));a.resourceReceipts().forEach(r->refs.add(r.receiptId()));
            if(!Objects.equals(s.attempt(),a.id()) || a.generation()!=c.generation() || a.open() || a.terminal()==null
                    || a.terminal().status()!=Outcomes.ExecutionStatus.SUCCEEDED || c.quantity()!=a.credited() || !refs.equals(c.receipts())
                    || !ActivityRecipeEvidence.completed(job,a,s,a.receipts(),a.resourceReceipts()))
                throw new IllegalArgumentException("Forged historical checkpoint");
            verified=Math.addExact(verified,s.creditedToGoal(a));
        }
        if(job.fulfilled()>goal || verified>job.fulfilled() || job.state()==State.SUCCEEDED
                && (verified!=goal || currentStep!=null || job.attempts().stream().anyMatch(Attempt::open)))
            throw new IllegalArgumentException("Activity completion evidence");
    }
    public static String digest(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    public static String digest(String text){return digest(text.getBytes(StandardCharsets.UTF_8));}
    private static boolean hash(String s){return s!=null&&s.matches("[0-9a-f]{64}");}
}
