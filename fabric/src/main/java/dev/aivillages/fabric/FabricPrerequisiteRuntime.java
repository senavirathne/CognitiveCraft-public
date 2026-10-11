package dev.aivillages.fabric;

import dev.aivillages.core.kernel.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static dev.aivillages.core.kernel.Prerequisites.*;

/** Runtime composition for IMP-018. No planner/model port, worker selection, or crafting implementation. */
public final class FabricPrerequisiteRuntime {
    private final MinecraftServer server;
    private final JobLifecycleStore jobs;
    private final CapabilityResolver.Engine resolver;
    private final Clock clock;
    private final Limits limits=Limits.defaults();
    private final LeasePrerequisiteSupplies supplies;
    private final PrerequisiteAdmission admission;
    public FabricPrerequisiteRuntime(MinecraftServer server,JobLifecycleStore jobs,ResourceLeaseService leases,
                                      CapabilityResolver.Engine resolver,Clock clock) {
        this.server=Objects.requireNonNull(server);this.jobs=Objects.requireNonNull(jobs);
        this.resolver=Objects.requireNonNull(resolver);this.clock=Objects.requireNonNull(clock);
        supplies=new LeasePrerequisiteSupplies(leases,()->server.overworld().getGameTime());
        admission=new PrerequisiteAdmission(jobs,limits,()->server.overworld().getGameTime());
    }
    public Result resolve(List<Need> needs,TrustedContext caller,List<String> recipeKeys) {
        thread();
        if(!jobs.ready())return rejected(ResolutionStatus.BLOCKED,Reason.STORAGE_UNAVAILABLE);
        if(needs.isEmpty() || needs.size()>limits.demands())return rejected(ResolutionStatus.BLOCKED,Reason.REQUEST_INVALID);
        try {
            for(Need need:needs) {
                var job=jobs.query(need.demand(),caller);
                if(!job.origin().equals(need.origin()) || !job.request().equals(need.bound())
                        || !jobs.mayControl(job.id(),need.guard().generation(),caller))
                    return rejected(ResolutionStatus.UNAUTHORIZED,Reason.AUTHORITY_DENIED);
                if(!job.guard().equals(need.guard()))return rejected(ResolutionStatus.BLOCKED,Reason.STALE_OBSERVATION);
                if(job.state()!=Jobs.State.READY && job.state()!=Jobs.State.WAITING)
                    return rejected(ResolutionStatus.BLOCKED,Reason.INTERRUPTED);
            }
        } catch(SecurityException denied) {return rejected(ResolutionStatus.UNAUTHORIZED,Reason.AUTHORITY_DENIED);}
        Proposal proposal=propose(needs,recipeKeys);
        return new Result(proposal,admission.submitExpanded(proposal,caller,FabricPrerequisiteCatalog.capture(server,recipeKeys),supplies,this::method,this::current));
    }
    private Result rejected(ResolutionStatus status,Reason reason) {
        return new Result(null,new Admission(status,reason,new Jobs.Change(Jobs.Code.REJECTED,null,reason,0),List.of(),Qualification.NONE));
    }
    private Proposal propose(List<Need> needs,List<String> recipeKeys) {
        thread();if(needs.isEmpty() || needs.size()>limits.demands())throw new IllegalArgumentException("Bounded demands");
        var remaining=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class);long deadline=Long.MAX_VALUE;
        for(Need need:needs) {
            var allowance=jobs.remainingAllowance(need.demand(),need.origin());
            deadline=Math.min(deadline,allowance.deadlineEpochMillis());
            remaining.merge(Budgets.Kind.INSTRUCTIONS,allowance.maximum(Budgets.Kind.INSTRUCTIONS),Math::min);
        }
        var ledger=new Budgets.Ledger(new Budgets.Limits(remaining,deadline),clock);
        return new DeterministicPrerequisiteResolver(limits,()->server.overworld().getGameTime()).resolve(needs,
                FabricPrerequisiteCatalog.capture(server,recipeKeys),supplies,this::method,this::current,ledger);
    }
    public Jobs.Change allocate(AllocationIntent intent,long quantity,UUID attempt,UUID receipt,Jobs.Guard demand,TrustedContext caller) {
        thread();return admission.allocate(intent,quantity,attempt,receipt,demand,caller);
    }
    private boolean current(Need need) {
        return jobs.mayControl(need.demand(),need.guard().generation(),need.origin());
    }
    private MethodSelection method(Production production,long batches,long output,Need need) {
        if(!production.equals(Production.cropDelivery()) || output>64)throw new IllegalArgumentException("Existing P0 production only");
        var supply=supplies.inspect(need,"minecraft:mature_wheat");
        var arguments=new HashMap<>(need.bound().request().arguments());arguments.put("amount",new IntValue(Math.toIntExact(output)));
        var request=new ValidatedRequest(new CapabilityRequest(production.capability(),arguments),need.origin(),supply.observation());
        var area=((AreaValue)arguments.get("source")).value();
        String identity="source:"+UUID.nameUUIDFromBytes(area.toString().getBytes(StandardCharsets.UTF_8));
        var observation=new ObservationSnapshot(supply.observation(),supply.status(),identity,supply.tick(),
                supply.inspected(),Map.of("mature_wheat",supply.available(),"unknown_cells",supply.status()==ObservationStatus.UNKNOWN?1L:0L));
        return new MethodSelection(resolver.resolve(request,observation),supply.inspected());
    }
    private void thread(){if(!server.isSameThread())throw new IllegalStateException("Prerequisites game thread");}
}
