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
        UUID world=request.context().scope().worldId();
        Cuboid source=((AreaValue)request.request().arguments().get("source")).value();
        ContainerRef destination=((ContainerValue)request.request().arguments().get("destination")).value();
        return new Binding(owners.byRun(request,run),List.of(new Demand(Resource.space(world,source),1),
                new Demand(Resource.facility(world,destination),1)));
    }
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
        if(action.consumption!=null) {
            Result result=leases.consumed(action.consuming,action.consumption,action.run.request.context());
            if(result.code()==Code.PENDING)return waitForPublication(action);
            if(!result.usable())return fail(action,result.reason());
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
                || operation==GatewayPrimitives.Operation.HARVEST_NEXT_WHEAT) {
            for(Ref ref:granted.leases()) {
                Lease claim=leases.snapshot().leases().stream().filter(l->l.id().equals(ref.id())).findFirst().orElseThrow();
                if(claim.resource().item().equals("minecraft:mature_wheat") && claim.remaining()<1)
                    return fail(action,Reason.RESOURCE_MISSING);
            }
        }
        if(action.nativeHandle==null)
            action.nativeHandle=delegate.start(run.request,run.correlation,action.primitive,action.arguments,action.usage);
        ActionReceipt answer=delegate.poll(action.nativeHandle);
        action.accounted=ticks.getAsLong();
        action.current=map(answer,handle,answer.terminal(),answer.reason());
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
                reason==null?r.result():null,reason==null?r.observation():null);
    }
    private Action get(ActionHandle handle) {
        Action action=actions.get(handle.id());
        if(action==null || !action.handle.equals(handle))throw new IllegalArgumentException("Unknown lease action");
        return action;
    }
    private void checkThread(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Lease gateway game thread");}
}
