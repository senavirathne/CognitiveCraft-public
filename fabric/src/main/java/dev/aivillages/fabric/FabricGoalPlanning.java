package dev.aivillages.fabric;

import dev.aivillages.core.kernel.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.server.MinecraftServer;
import static dev.aivillages.core.kernel.Contracts.*;

/** Bounded game-thread capture/revalidation. Only immutable authorized history crosses the storage worker. */
public final class FabricGoalPlanning implements GoalPlanner.ContextPort {
    private final MinecraftServer server;private final BootstrapController.Observer observer;
    private final CapabilityResolver.Engine resolver;private final BiPredicate<ActorRef,TrustedContext> controls;
    private final CapabilityResolver.CandidateSource methods;private final ObservedWorldKnowledgeStore observations;private final Executor worker;
    public FabricGoalPlanning(MinecraftServer server,BootstrapController.Observer observer,CapabilityResolver.Engine resolver,
                              BiPredicate<ActorRef,TrustedContext> controls,CapabilityResolver.CandidateSource methods,
                              ObservedWorldKnowledgeStore observations,Executor worker) {
        this.server=server;this.observer=observer;this.resolver=resolver;this.controls=controls;this.methods=methods;this.observations=observations;this.worker=worker;
    }
    public boolean controls(ActorRef actor,TrustedContext scope){thread();return controls.test(actor,scope);}
    public long tick(){thread();return server.overworld().getGameTime();}
    public CapabilityResolver.Decision resolve(ValidatedRequest request) {
        thread();var actor=((ActorValue)request.request().arguments().get("actor")).value();
        if(!controls(actor,request.context()))throw new SecurityException("Current planner authority");
        var snapshot=observer.capture(request.request(),actor);
        return resolver.resolve(new ValidatedRequest(request.request(),request.context(),snapshot.reference()),snapshot);
    }
    public CompletionStage<dev.aivillages.core.kernel.Plan.Context> capture(Jobs.Job root,PlanningBudget budget) {
        thread();var actor=root.responsible();if(!controls(actor,root.origin()))throw new SecurityException("Planner authority");
        var snapshot=observer.capture(root.request().request(),actor);
        var direct=resolver.resolve(new ValidatedRequest(root.request().request(),root.origin(),snapshot.reference()),snapshot);
        var coverage=DeterministicPrerequisiteResolver.outcomeCoverage(root.request().request().capability(),FabricPrerequisiteCatalog.capture(server,List.of()));
        var slots=new ArrayList<dev.aivillages.core.kernel.Plan.Slot>();
        if(direct.routing().status()==Outcomes.ResolutionStatus.NEEDS_PLANNING && direct.complete() && !coverage.handled() && coverage.complete()) {
            for(var request:PlanningGoals.slots(root.request().request())) {
                var selected=resolve(new ValidatedRequest(request,root.origin(),snapshot.reference()));
                if(selected.routing().status()!=Outcomes.ResolutionStatus.RESOLVED || !selected.complete())
                    throw new IllegalStateException("Planning requires currently eligible known child methods: "+selected.routing());
                slots.add(new dev.aivillages.core.kernel.Plan.Slot(selected.selectedBindings(),selected.observation(),methods.descriptor(selected.routing().artifact()).orElseThrow()));
            }
        }
        if(slots.isEmpty())return CompletableFuture.completedFuture(new dev.aivillages.core.kernel.Plan.Context(root.id(),root.guard(),root.request(),direct,coverage,slots,""));
        var target=new SurvivalGateway.ObservationTarget(SurvivalGateway.ObservationKind.SOURCE_CROPS,((AreaValue)root.request().request().arguments().get("source")).value(),null);
        return CompletableFuture.supplyAsync(()->{
            var history=observations.createEvidenceSnapshot(root.origin(),target,new ObservedWorldKnowledgeStore.QueryLimits(8,2,2048));
            String summary=StrictJson.canonical(Map.of("advisory",true,"status",history.status().name(),"records",history.evidence().stream().map(e->Map.of(
                    "id",e.record().id().toString(),"freshness",e.freshness().name(),"observedTick",e.record().latest().observation().observedTick(),
                    "qualification",e.qualification().name())).toList()));
            return new dev.aivillages.core.kernel.Plan.Context(root.id(),root.guard(),root.request(),direct,coverage,slots,summary);
        },worker);
    }
    private void thread(){if(!server.isSameThread())throw new IllegalStateException("Planning game thread");}
}
