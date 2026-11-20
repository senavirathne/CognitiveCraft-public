package dev.aivillages.core.kernel.runtime.activity;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.production.planning.PlanningChoiceBuilder;
import dev.aivillages.core.kernel.production.planning.PlanningCoordinator;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.production.planning.PlanningModel.*;

/** Translates B's revision-matched proposals into the existing A Jobs publication request. */
public final class ProductionActivityPlanner {
    @FunctionalInterface public interface NativeChoice {
        boolean validate(ChoiceOffer choice);
    }
    public record Decision(PlanningModelDecision model,ActivityRuntime.Proposal proposal,ActivityPlan plan,
                           List<String> coveredActions) {
        public Decision {coveredActions=List.copyOf(coveredActions);}
    }
    public record PlanningModelDecision(Memory memory,String blocker,boolean feasible,long nextCheckMillis) {}

    private final PlanningCoordinator planner=new PlanningCoordinator();

    public Decision propose(Jobs.Job root,Snapshot frame,Memory memory,Budgets.Limits remaining,NativeChoice nativeChoice) {
        requireOriginal(root,frame,remaining);
        var previous=root.activity().production().plan();
        var saved=previous==null?null:previous.bind(frame);
        boolean exactChildren=previous==null || previous.exactChildren();
        int maximum=ActivityPayload.MAX_STEPS-root.activity().steps().size();
        if(maximum<1)return new Decision(new PlanningModelDecision(memory,"NATIVE_SEGMENT_LIMIT_EXCEEDED",false,
                Long.MAX_VALUE),null,previous,List.of());
        var selected=planner.nextActivity(frame,saved,memory,1,maximum,exactChildren);
        if(selected.neededChoice()!=null) {
            var candidate=PlanningChoiceBuilder.build(frame,selected.neededChoice());
            var offer=new ChoiceOffer(new ValidatedRequest(candidate.invocation(),root.origin(),root.request().observation()),
                    candidate.choice(),candidate.sources());
            if(nativeChoice.validate(offer))selected=planner.nextActivity(withOffer(frame,offer),saved,memory,1,maximum,exactChildren);
        }
        var state=new PlanningModelDecision(selected.memory(),selected.blocker(),
                selected.analysis()!=null && selected.analysis().feasibility()==Feasibility.FEASIBLE,selected.nextCheckMillis());
        if(selected.kind()!=DecisionKind.PROPOSE)return new Decision(state,null,previous,List.of());
        int planned=Math.addExact(root.activity().steps().size(),PlanningCoordinator.remainingSegments(frame,selected.analysis().plan(),1));
        if(planned>ActivityPayload.MAX_STEPS)return new Decision(new PlanningModelDecision(selected.memory(),
                "NATIVE_SEGMENT_LIMIT_EXCEEDED",false,Long.MAX_VALUE),null,previous,List.of());
        return new Decision(state,authoritative(root,frame,remaining,selected.proposal(),planned),ActivityPlan.of(selected.analysis().plan(),exactChildren),
                selected.proposal().coveredActionTokens());
    }

    private static void requireOriginal(Jobs.Job root,Snapshot frame,Budgets.Limits remaining) {
        var request=root.request().request();
        if(root.activity()==null || root.activity().production()==null || root.activity().current()!=null || root.current()!=null && root.current().open()
                || root.state().terminal() || !frame.world().equals(root.origin().scope().worldId())
                || !frame.context().equals(root.origin()) || !frame.root().equals(root.id()) || frame.revision()!=root.revision()
                || !frame.owner().executor().equals(root.responsible()) || frame.owner().executionGeneration()!=root.generation()
                || !frame.owner().originalOwner().equals(root.origin().principal().id()) || frame.confirmedCredit()!=root.fulfilled()
                || frame.goal().mode()!=Mode.valueOf(root.activity().mode().name())
                || !RecipeProduction.of(request.capability()).output().id().equals(frame.goal().item().id())
                || frame.goal().demand()!=((IntValue)request.arguments().get("amount")).value()
                || !frame.goal().destination().equals(ResourceDelivery.destinationAddress(request))
                || frame.budget().absoluteDeadlineMillis()!=root.allowance().deadlineEpochMillis()
                || remaining.deadlineEpochMillis()!=root.allowance().deadlineEpochMillis()
                || !frame.budget().equals(budget(root)))
            throw new IllegalArgumentException("Planner frame differs from the authoritative original activity");
    }

    /** B's planning counters are a projection of original immutable limits and cumulative Jobs usage. */
    public static Budget budget(Jobs.Job root) {
        return new Budget(counters(root.allowance().maxima()),counters(root.usage()),root.allowance().deadlineEpochMillis());
    }

    private static Map<Counter,Long> counters(Map<Budgets.Kind,Long> source) {
        return Map.of(Counter.TRAVEL,source.getOrDefault(Budgets.Kind.TRAVEL_BLOCKS,0L),
                Counter.EFFECTS,source.getOrDefault(Budgets.Kind.ATTEMPTED_EFFECTS,0L),
                Counter.ELAPSED_TICKS,source.getOrDefault(Budgets.Kind.ELAPSED_TICKS,0L),
                Counter.RETRIES,0L,Counter.REPLANS,0L,Counter.MODEL_CALLS,source.getOrDefault(Budgets.Kind.CALLS,0L));
    }

    private static Snapshot withOffer(Snapshot s,ChoiceOffer offer) {
        return new Snapshot(s.schema(),s.world(),s.root(),s.revision(),s.context(),s.owner(),s.goal(),s.activeDestination(),
                s.confirmedCredit(),s.budget(),s.nowMillis(),s.relevantWorldRevision(),s.stock(),s.acquisition(),s.recipes(),
                s.facility(),List.of(offer),s.supportedKinds(),s.progress(),s.catalogueComplete(),s.modelAvailable(),s.returnsUsed());
    }

    private static ActivityRuntime.Proposal authoritative(Jobs.Job root,Snapshot frame,Budgets.Limits remaining,SegmentProposal proposed,int planned) {
        if(proposed.requestedInvocation()==null || proposed.method()==null || !proposed.world().equals(frame.world())
                || !proposed.root().equals(root.id()) || proposed.revision()!=root.revision() || !proposed.context().equals(root.origin())
                || !proposed.owner().equals(frame.owner().originalOwner()) || !proposed.executor().equals(root.responsible())
                || proposed.executionGeneration()!=root.generation() || proposed.originalDeadlineMillis()!=remaining.deadlineEpochMillis()
                || !Set.of(Kind.PRODUCE,Kind.ACQUIRE,Kind.PLACE_TABLE).contains(proposed.kind()))
            throw new IllegalArgumentException("Foreign or unsupported planner proposal");
        var choice=proposed.nativeChoice()==null?null:ActivityPayload.Choice.of(proposed.nativeChoice().choice());
        var request=proposed.requestedInvocation();
        var id=new CapabilityId(proposed.capability().name(),1);
        var invocation=new CapabilityRequest(id,request.arguments());
        long amount=((IntValue)invocation.arguments().get("amount")).value();
        var quantities=choice==null?ActivityPayload.Quantities.exact(amount):
                new ActivityPayload.Quantities(amount,choice.recipe().resultCount(),proposed.quantities().batches(),
                        proposed.quantities().newPhysicalOutput(),amount,proposed.quantities().actorSurplus(),
                        choice.recipe().remainders().stream().mapToLong(r->r.count()).sum());
        var budget=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);
        budget.putAll(remaining.maxima());
        cap(budget,Budgets.Kind.TRAVEL_BLOCKS,proposed.requestedAllowance().get(Counter.TRAVEL));
        cap(budget,Budgets.Kind.ATTEMPTED_EFFECTS,proposed.requestedAllowance().get(Counter.EFFECTS));
        cap(budget,Budgets.Kind.COMMITTED_EFFECTS,proposed.requestedAllowance().get(Counter.EFFECTS));
        cap(budget,Budgets.Kind.ELAPSED_TICKS,proposed.requestedAllowance().get(Counter.ELAPSED_TICKS));
        var proposalId=UUID.nameUUIDFromBytes(proposed.proposalId().getBytes(StandardCharsets.US_ASCII));
        var token=ActivityPayload.digest(proposed.stepToken());
        return new ActivityRuntime.Proposal(1,proposalId,root.id(),root.guard(),token,invocation,
                new ArtifactRef(id,proposed.method().artifactHash()),choice,quantities,
                new Budgets.Limits(budget,remaining.deadlineEpochMillis()),
                planned,true,proposed.quantities().goalAllocationUpperBound());
    }

    private static void cap(EnumMap<Budgets.Kind,Long> limits,Budgets.Kind counter,long bound) {
        limits.compute(counter,(kind,current)->Math.min(current==null?0L:current,bound));
    }
}
