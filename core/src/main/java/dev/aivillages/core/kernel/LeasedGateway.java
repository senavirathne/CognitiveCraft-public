package dev.aivillages.core.kernel;

import java.util.*;
import java.util.function.LongSupplier;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.ResourceLeases.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;

/** Bounded coordination decorator; the existing gateway remains the only physical effect owner. */
public final class LeasedGateway implements GatewayPort {
    public record Binding(Owner owner,List<Demand> demands) {
        public Binding { Objects.requireNonNull(owner);demands=List.copyOf(demands);
            if(demands.isEmpty()||demands.size()>4)throw new IllegalArgumentException("Lease binding"); }
    }
    @FunctionalInterface public interface Bindings { Binding bind(ValidatedRequest request,RunCorrelation run); }
    private static final class Run {
        final ValidatedRequest request;
        final RunCorrelation correlation;
        final Binding binding;
        Result acquisition;
        boolean released;
        Run(ValidatedRequest request,RunCorrelation correlation,Binding binding) {
            this.request=request;this.correlation=correlation;this.binding=binding;
        }
    }
    private static final class Action {
        final ActionHandle handle;
        final Run run;
        final PrimitiveRequirement primitive;
        final Map<String,Value> arguments;
        final Budgets.Ledger usage;
        final long started;
        long polled=-1,accounted;
        ActionHandle nativeHandle;
        ActionReceipt current,saved;
        Ref consuming;
        CropDelivery.CropReceipt consumption;
        UUID oakConsumption;
        ResourceDelivery.Receipt ingredientConsumption;
        final java.util.ArrayDeque<Map.Entry<Ref,ResourceDelivery.Receipt>> ingredients=new java.util.ArrayDeque<>();
        Action(ActionHandle handle,Run run,PrimitiveRequirement primitive,Map<String,Value> arguments,
               Budgets.Ledger usage,long tick) {
            this.handle=handle;this.run=run;this.primitive=primitive;this.arguments=Map.copyOf(arguments);
            this.usage=usage;started=tick;accounted=tick;current=new ActionReceipt(handle,false,0,List.of(),null);
        }
    }
    private final Thread thread=Thread.currentThread();
    private final SurvivalGateway delegate;
    private final ResourceLeaseService leases;
    private final Bindings bindings;
    private final LongSupplier ticks;
    private final long duration,waitingTicks;
    private final int maxRuns,maxHandles;
    private final Map<UUID,Run> runs=new LinkedHashMap<>();
    private final Map<UUID,Action> actions=new LinkedHashMap<>();
    public LeasedGateway(SurvivalGateway delegate,ResourceLeaseService leases,Bindings bindings,
                         LongSupplier ticks,long duration,long waitingTicks,int maxRuns,int maxHandles) {
        this.delegate=Objects.requireNonNull(delegate);this.leases=Objects.requireNonNull(leases);
        this.bindings=Objects.requireNonNull(bindings);this.ticks=Objects.requireNonNull(ticks);
        if(duration<1 || duration>leases.settings().duration() || waitingTicks<1 || waitingTicks>1200
                || maxRuns<1 || maxRuns>64 || maxHandles<1 || maxHandles>512)
            throw new IllegalArgumentException("Bounded lease gateway");
        this.duration=duration;this.waitingTicks=waitingTicks;this.maxRuns=maxRuns;this.maxHandles=maxHandles;
    }
    public static Binding workspaces(JobLeaseOwners owners,ValidatedRequest request,RunCorrelation run) {
        if(RecipeProduction.is(request.request().capability()))return recipeWorkspaces(owners,request,run);
        UUID world=request.context().scope().worldId();
        Cuboid source=ResourceDelivery.sourceArea(request.request());
        ContainerRef destination=((ContainerValue)request.request().arguments().get("destination")).value();
        return new Binding(owners.byRun(request,run),List.of(new Demand(Resource.space(world,source),1),
                new Demand(Resource.facility(world,destination),1)));
    }
    public static Binding cropWorkspaces(JobLeaseOwners owners,ValidatedRequest request,RunCorrelation run) {
        if(RecipeProduction.is(request.request().capability()))return recipeWorkspaces(owners,request,run);
        Binding spaces=workspaces(owners,request,run);
        var demands=new ArrayList<>(spaces.demands());
        demands.add(new Demand(sourceResource(request.context().scope().worldId(),request),
                ((IntValue)request.request().arguments().get("amount")).value()));
        return new Binding(spaces.owner(),demands);
    }
    private static Binding recipeWorkspaces(JobLeaseOwners owners,ValidatedRequest request,RunCorrelation run) {
        UUID world=request.context().scope().worldId();
        var source=((ContainerValue)request.request().arguments().get("source")).value();
        var target=((ContainerValue)request.request().arguments().get("destination")).value();
        var demands=new ArrayList<Demand>();
        addAxeDemands(demands,world,request,source);
        demands.add(new Demand(RecipeProduction.of(request.request().capability()).stock(world,source),RecipeProduction.of(request.request().capability()).inputs(((IntValue)request.request().arguments().get("amount")).value())));
        if(!RecipeProduction.of(request.request().capability()).axe())demands.add(new Demand(Resource.facility(world,source),1));
        if(RecipeProduction.of(request.request().capability()).axe() || !target.equals(source))demands.add(new Demand(Resource.facility(world,target),1));
        return new Binding(owners.byRun(request,run),demands);
    }
    /** Advisory owner inspection avoids consuming assignment generations for a known conflict. */
    public Result preflight(Jobs.Job job,ValidatedRequest request) {
        checkThread();UUID world=job.origin().scope().worldId();
        // A historical responsibility anchor is not the prospective worker. The real grant
        // must use the new durable assignment, especially after loss of the previous actor.
        ActorRef anchor=job.current()==null?job.responsible():job.current().worker();
        if(!anchor.equals(((ActorValue)request.request().arguments().get("actor")).value()))
            return new Result(Code.VALID,null,null,List.of(),0,0);
        Cuboid source=ResourceDelivery.sourceArea(request.request());
        ContainerRef destination=((ContainerValue)request.request().arguments().get("destination")).value();
        var demands=RecipeProduction.is(request.request().capability())
                ? recipeDemands(world,request) : List.of(new Demand(sourceResource(world,request),((IntValue)request.request().arguments().get("amount")).value()),
                new Demand(Resource.space(world,source),1),new Demand(Resource.facility(world,destination),1));
        int inspected=0;
        for(Demand demand:demands) {
            Result current=leases.inspect(new Owner(job.id(),job.generation()),demand.resource(),job.origin());
            inspected+=current.inspected();
            if(!current.usable())return current;
            if(current.available()<demand.quantity())return new Result(Code.CONFLICT,Reason.TARGET_UNAVAILABLE,null,List.of(),current.available(),inspected);
        }
        return new Result(Code.VALID,null,null,List.of(),0,inspected);
    }
    /** Preflight coordination for an already durable assignment, before interpreter start. */
    public Result prepare(ValidatedRequest request,RunCorrelation correlation) {
        checkThread();
        Run run=runs.get(correlation.runId());
        if(run==null) {
            if(runs.size()>=maxRuns)return new Result(Code.REJECTED,Reason.BUDGET_EXHAUSTED,null,List.of(),0,0);
            run=new Run(request,correlation,bindings.bind(request,correlation));runs.put(correlation.runId(),run);
        }
        if(run.released || !run.request.equals(request) || !run.correlation.equals(correlation)
                || !run.binding.equals(bindings.bind(request,correlation)))
            return new Result(Code.REJECTED,Reason.AUTHORITY_DENIED,null,List.of(),0,0);
        if(!leases.ready())return new Result(Code.PENDING,leases.failure(),null,List.of(),0,0);
        if(run.acquisition==null)run.acquisition=leases.acquire(run.binding.owner(),run.binding.demands(),duration,request.context());
        if(run.acquisition.group()==null)return run.acquisition;
        return leases.group(run.acquisition.group(),run.binding.owner(),request.context());
    }
    public Result validate(ValidatedRequest request,RunCorrelation correlation) {
        Result group=prepare(request,correlation);
        if(!group.usable())return group;
        for(Ref ref:group.leases()) { Result valid=leases.validate(ref,request.context());if(!valid.usable())return valid; }
        return group;
    }
    public boolean released(UUID run) { checkThread();return !runs.containsKey(run); }
    @Override public ActionHandle start(ValidatedRequest request,RunCorrelation correlation,
                                       PrimitiveRequirement primitive,Map<String,Value> arguments,Budgets.Ledger usage) {
        checkThread();Objects.requireNonNull(request);Objects.requireNonNull(correlation);
        actions.values().removeIf(a->a.current.terminal() && a.run.released);
        if(actions.size()>=maxHandles)throw new IllegalStateException("Lease action capacity");
        Run run=runs.get(correlation.runId());
        if(run==null) {
            if(runs.size()>=maxRuns)throw new IllegalStateException("Lease run capacity");
            run=new Run(request,correlation,bindings.bind(request,correlation));runs.put(correlation.runId(),run);
        }
        if(run.released || !run.request.equals(request) || !run.correlation.equals(correlation))
            throw new SecurityException("Changed lease execution");
        ActionHandle handle=new ActionHandle(UUID.randomUUID(),correlation.runId());
        actions.put(handle.id(),new Action(handle,run,primitive,arguments,usage,ticks.getAsLong()));return handle;
    }
    @Override public ActionReceipt poll(ActionHandle handle) {
        checkThread();Action action=get(handle);
        if(action.current.terminal() || action.polled==ticks.getAsLong())return action.current;
        action.polled=ticks.getAsLong();
        if(action.run.released)return fail(action,Reason.INTERRUPTED);
        if(action.polled<action.started || action.polled-action.started>waitingTicks)return fail(action,Reason.ACTION_TIMEOUT);
        if(leases.failure()!=null)return fail(action,leases.failure());
        if(!leases.ready())return waitForPublication(action);
        // A new research execution pin may supersede a run without changing the job generation.
        // Rebind against the current job owner before any further native effect or accounting.
        try {
            if(!action.run.binding.equals(bindings.bind(action.run.request,action.run.correlation)))
                return fail(action,Reason.AUTHORITY_DENIED);
        }catch(SecurityException denied){return fail(action,Reason.AUTHORITY_DENIED);}
        if(action.consumption!=null || action.oakConsumption!=null || action.ingredientConsumption!=null) {
            Result result=action.ingredientConsumption!=null
                    ? leases.consumedIngredient(action.consuming,action.run.request,action.run.correlation,action.ingredientConsumption,action.run.request.context())
                    : action.oakConsumption!=null
                    ? leases.consumedOakBlock(action.consuming,action.run.request,action.run.correlation,action.oakConsumption,action.run.request.context())
                    : leases.consumed(action.consuming,action.consumption,action.run.request.context());
            if(result.code()==Code.PENDING)return waitForPublication(action);
            if(!result.usable())return fail(action,result.reason());
            if(action.ingredientConsumption!=null && !action.ingredients.isEmpty()) {
                var next=action.ingredients.removeFirst();action.consuming=next.getKey();action.ingredientConsumption=next.getValue();
                action.current=map(action.saved,handle,false,null);return action.current;
            }
            action.current=map(action.saved,handle,true,action.saved.reason());return action.current;
        }
        Run run=action.run;
        if(run.acquisition==null) {
            run.acquisition=leases.acquire(run.binding.owner(),run.binding.demands(),duration,run.request.context());
            if(run.acquisition.code()!=Code.PENDING)return fail(action,run.acquisition.reason());
            return waitForPublication(action);
        }
        Result granted=leases.group(run.acquisition.group(),run.binding.owner(),run.request.context());
        if(!granted.usable())return granted.code()==Code.PENDING?waitForPublication(action):fail(action,granted.reason());
        for(Ref ref:granted.leases()) {
            Result valid=leases.validate(ref,run.request.context());
            if(!valid.usable())return valid.code()==Code.PENDING?waitForPublication(action):fail(action,valid.reason());
        }
        var operation=GatewayPrimitives.instance().operation(action.primitive).orElse(null);
        if(operation==GatewayPrimitives.Operation.HARVEST_WHEAT
                || operation==GatewayPrimitives.Operation.HARVEST_NEXT_WHEAT
                || operation==GatewayPrimitives.Operation.HARVEST_NEXT_OAK_LOG) {
            for(Ref ref:granted.leases()) {
                Lease claim=leases.snapshot().leases().stream().filter(l->l.id().equals(ref.id())).findFirst().orElseThrow();
                if((claim.resource().item().equals("minecraft:mature_wheat") || claim.resource().oakBlocks()) && claim.remaining()<1)
                    return fail(action,Reason.RESOURCE_MISSING);
            }
        }
        if(action.nativeHandle==null)
            action.nativeHandle=delegate.start(run.request,run.correlation,action.primitive,action.arguments,action.usage);
        ActionReceipt answer=delegate.poll(action.nativeHandle);
        action.accounted=ticks.getAsLong();
        action.current=map(answer,handle,answer.terminal(),answer.reason());
        if(answer.terminal() && answer.reason()==null && (operation==GatewayPrimitives.Operation.WITHDRAW_OAK_LOG
                || operation==GatewayPrimitives.Operation.WITHDRAW_OAK_PLANKS || operation==GatewayPrimitives.Operation.WITHDRAW_AXE_INPUTS)) {
            for(var receipt:answer.resourceReceipts())if(receipt.stage()==ResourceDelivery.Stage.WITHDRAW) {
                Ref matching=null;
                for(Ref ref:granted.leases()) {
                    Lease claim=leases.snapshot().leases().stream().filter(l->l.id().equals(ref.id())).findFirst().orElseThrow();
                    if(claim.resource().kind()==Kind.STOCK && claim.resource().slot()==-1 && claim.resource().item().equals(receipt.item().id()))matching=ref;
                }
                if(matching==null)return fail(action,Reason.REQUEST_INVALID);
                action.ingredients.add(Map.entry(matching,receipt));
            }
            if(!action.ingredients.isEmpty()) {
                var next=action.ingredients.removeFirst();action.saved=answer;action.consuming=next.getKey();action.ingredientConsumption=next.getValue();
                action.current=map(answer,handle,false,null);return action.current;
            }
        }
        if(answer.terminal() && operation==GatewayPrimitives.Operation.HARVEST_NEXT_OAK_LOG && answer.committedEffects()>0) {
            for(Ref ref:granted.leases()) {
                Lease claim=leases.snapshot().leases().stream().filter(l->l.id().equals(ref.id())).findFirst().orElseThrow();
                if(claim.resource().oakBlocks()) {
                    action.saved=answer; action.consuming=ref; action.oakConsumption=action.nativeHandle.id();
                    Result recorded=leases.consumedOakBlock(ref,run.request,run.correlation,action.oakConsumption,run.request.context());
                    if(recorded.code()==Code.PENDING) { action.current=map(answer,handle,false,null); return action.current; }
                    if(!recorded.usable()) return fail(action,recorded.reason());
                }
            }
        }
        if(answer.terminal() && answer.reason()==null) {
            for(CropDelivery.CropReceipt receipt:answer.cropReceipts())
                if(receipt.stage()==CropDelivery.Stage.HARVEST) {
                    for(Ref ref:granted.leases()) {
                        Lease claim=leases.snapshot().leases().stream().filter(l->l.id().equals(ref.id())).findFirst().orElseThrow();
                        if(claim.resource().item().equals("minecraft:mature_wheat")) {
                            action.saved=answer;action.consuming=ref;action.consumption=receipt;
                            Result recorded=leases.consumed(ref,receipt,run.request.context());
                            if(recorded.code()==Code.PENDING) {
                                action.current=map(answer,handle,false,null);return action.current;
                            }
                            if(!recorded.usable())return fail(action,recorded.reason());
                        }
                    }
                }
        }
        return action.current;
    }
    @Override public ActionReceipt cancel(ActionHandle handle) {
        checkThread();Action a=get(handle);if(a.current.terminal())return a.current;
        ActionReceipt receipt=a.nativeHandle==null?a.current:delegate.cancel(a.nativeHandle);
        a.current=map(receipt,handle,true,Reason.CANCELLED);releaseRun(handle.runId());return a.current;
    }
    public void releaseRun(UUID id) {
        checkThread();delegate.releaseRun(id);
        Run run=runs.get(id);if(run==null)return;run.released=true;drainRelease(run);
    }
    public void tick() {
        checkThread();
        for(Run run:List.copyOf(runs.values())) {
            if(run.released) { drainRelease(run);if(!leases.ready())return; }
        }
    }
    public Result group(UUID runId,TrustedContext caller) {
        checkThread();Run run=runs.get(runId);
        if(run==null || run.acquisition==null)throw new SecurityException("No scoped lease group");
        return leases.group(run.acquisition.group(),run.binding.owner(),caller);
    }
    public int retainedActions(){checkThread();return actions.size();}
    private void drainRelease(Run run) {
        if(!leases.ready())return;
        if(run.acquisition==null || run.acquisition.group()==null) { runs.remove(run.correlation.runId());return; }
        Result group=leases.group(run.acquisition.group(),run.binding.owner(),run.request.context());
        List<Ref> refs=group.usable()?group.leases():run.acquisition.leases();
        if(refs.isEmpty()){runs.remove(run.correlation.runId());return;}
        Result released=leases.release(refs,run.request.context());
        if(released.code()!=Code.PENDING)runs.remove(run.correlation.runId());
    }
    private ActionReceipt waitForPublication(Action a) {
        long elapsed=ticks.getAsLong()-a.started;
        if(elapsed<0 || elapsed>waitingTicks)return fail(a,Reason.ACTION_TIMEOUT);
        try {
            // This wait belongs to the original action, not a fresh execution allowance.
            if(a.nativeHandle==null || a.saved!=null)
                a.usage.debit(Budgets.Kind.ELAPSED_TICKS,Math.max(0,ticks.getAsLong()-a.accounted));
            a.usage.debit(Budgets.Kind.INSTRUCTIONS,1);a.accounted=ticks.getAsLong();
        }catch(Budgets.Exhausted exhausted){return fail(a,Reason.BUDGET_EXHAUSTED);}
        return a.current;
    }
    private ActionReceipt fail(Action a,Reason reason) {
        ActionReceipt evidence=a.current;
        if(a.nativeHandle!=null) {
            ActionReceipt stopped=delegate.cancel(a.nativeHandle);
            if(stopped.committedEffects()>=evidence.committedEffects())evidence=stopped;
        }
        a.current=map(evidence,a.handle,true,reason==null?Reason.ACTION_FAILED:reason);releaseRun(a.handle.runId());return a.current;
    }
    private static ActionReceipt map(ActionReceipt r,ActionHandle handle,boolean terminal,Reason reason) {
        return new ActionReceipt(handle,terminal,r.committedEffects(),r.cropReceipts(),reason,
                reason==null?r.result():null,reason==null?r.observation():null,r.resourceReceipts());
    }
    private static Resource sourceResource(UUID world,ValidatedRequest request) {
        if(RecipeProduction.is(request.request().capability()))
            return RecipeProduction.of(request.request().capability()).stock(world,((ContainerValue)request.request().arguments().get("source")).value());
        Cuboid source=ResourceDelivery.sourceArea(request.request());
        return request.request().capability().equals(OakLogDelivery.ID) ? Resource.oakBlocks(world,source) : Resource.crops(world,source);
    }
    private static List<Demand> recipeDemands(UUID world,ValidatedRequest request) {
        var source=((ContainerValue)request.request().arguments().get("source")).value();
        var target=((ContainerValue)request.request().arguments().get("destination")).value();
        var result=new ArrayList<Demand>();addAxeDemands(result,world,request,source);result.add(new Demand(RecipeProduction.of(request.request().capability()).stock(world,source),
                RecipeProduction.of(request.request().capability()).inputs(((IntValue)request.request().arguments().get("amount")).value())));
        if(!RecipeProduction.of(request.request().capability()).axe())result.add(new Demand(Resource.facility(world,source),1));
        if(RecipeProduction.of(request.request().capability()).axe() || !source.equals(target))result.add(new Demand(Resource.facility(world,target),1));return List.copyOf(result);
    }
    private static void addAxeDemands(List<Demand> demands,UUID world,ValidatedRequest request,ContainerRef source) {
        var d=RecipeProduction.of(request.request().capability());if(!d.axe())return;
        demands.add(new Demand(d.secondaryStock(world,source),d.secondaryInputs(((IntValue)request.request().arguments().get("amount")).value())));
        demands.add(new Demand(Resource.table(world,((FacilityValue)request.request().arguments().get("table")).value()),1));
    }
    private Action get(ActionHandle handle) {
        Action action=actions.get(handle.id());
        if(action==null || !action.handle.equals(handle))throw new IllegalArgumentException("Unknown lease action");
        return action;
    }
    private void checkThread(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Lease gateway game thread");}
}
