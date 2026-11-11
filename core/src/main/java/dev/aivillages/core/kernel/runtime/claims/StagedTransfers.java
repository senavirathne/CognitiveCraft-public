package dev.aivillages.core.kernel.runtime.claims;

import dev.aivillages.core.kernel.*;
import java.time.Clock;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.ResourceLeases.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;

/**
 * Bounded, disposable physical staging over the existing Jobs and lease owners. Live native moves
 * are observations, not save-confirmed receipts or goal credit. No durable activity store/scheduler.
 */
public final class StagedTransfers {
    public enum Operation { WITHDRAW, DEPOSIT, RETURN }
    public enum Phase { WAITING, RELEASING, HELD, DEPOSITED, RETURNED, BLOCKED }
    public record Settings(int frames,int units,long residenceTicks,int replans) {
        public Settings {if(frames<1||frames>64||units<1||units>64||residenceTicks<1||residenceTicks>1200||replans<0||replans>8)
            throw new IllegalArgumentException("Finite staging settings");}
        public static Settings defaults(){return new Settings(32,64,600,8);}
    }
    public record Units(ResourceDelivery.ItemIdentity item,int quantity) {
        public Units {Objects.requireNonNull(item);if(!item.components().equals("default")||quantity<1||quantity>64)
            throw new IllegalArgumentException("Staged item quantity/components");}
    }
    public record Plan(ContainerRef source,ContainerRef destination,ContainerRef returnTarget,List<Units> units) {
        public Plan {
            Objects.requireNonNull(source);Objects.requireNonNull(destination);Objects.requireNonNull(returnTarget);
            units=units.stream().sorted(Comparator.comparing(u->u.item().id())).toList();
            if(units.isEmpty()||units.size()>2||units.stream().map(u->u.item().id()).distinct().count()!=units.size()
                    ||!source.dimension().equals(destination.dimension())||!returnTarget.equals(source)
                    ||units.stream().mapToInt(Units::quantity).sum()>64)throw new IllegalArgumentException("Bounded exact staging plan");
        }
        public Map<String,Integer> quantities(){var out=new TreeMap<String,Integer>();for(var u:units)out.put(u.item().id(),u.quantity());return Map.copyOf(out);}
    }
    /** All maps/fingerprints come from the registered native host, never player/model JSON. */
    public record Snapshot(long tick,String sourceIdentity,String destinationIdentity,String actorFingerprint,
                           String sourceFingerprint,String destinationFingerprint,Map<String,Integer> sourceStock,
                           Map<String,Integer> actorStock,boolean actorFits,boolean destinationFits,boolean reachable,int inspected,Reason blocker) {
        public Snapshot {
            sourceStock=Map.copyOf(sourceStock);actorStock=Map.copyOf(actorStock);
            if(tick<0||sourceIdentity==null||destinationIdentity==null||actorFingerprint==null||sourceFingerprint==null||destinationFingerprint==null
                    ||inspected<0||inspected>264||sourceStock.size()>8||actorStock.size()>8||sourceStock.values().stream().anyMatch(n->n<0||n>8192)
                    ||actorStock.values().stream().anyMatch(n->n<0||n>8192))throw new IllegalArgumentException("Bounded native staging snapshot");
        }
        public static Snapshot blocked(long tick,Reason why){return new Snapshot(tick,"","","","","",Map.of(),Map.of(),false,false,false,0,Objects.requireNonNull(why));}
    }
    public record Move(boolean committed,Map<String,Integer> moved,Snapshot after,Reason blocker) {
        public Move {moved=Map.copyOf(moved);Objects.requireNonNull(after);if(moved.size()>2||moved.values().stream().anyMatch(n->n<1||n>64)
                ||!committed&&!moved.isEmpty())throw new IllegalArgumentException("Live move observation");}
        public static Move blocked(Snapshot snapshot,Reason why){return new Move(false,Map.of(),snapshot,why);}
    }
    public interface World {
        Snapshot observe(ValidatedRequest request,Plan plan,Operation operation);
        /** Recheck all identities/components, permissions, stock, reach and joint capacity before mutation. */
        Move move(ValidatedRequest request,Plan plan,Operation operation,Snapshot captured);
    }
    public record View(UUID token,Owner owner,Phase phase,Map<String,Integer> acquired,int changes,
                       long firstTick,long ticks,Reason blocker,boolean uncertain,List<LiveMove> moves,Map<Budgets.Kind,Long> usage) { }
    /** Diagnostic live effect identity only: cannot be passed to Jobs reconciliation as native durability proof. */
    public record LiveMove(UUID operation,Operation kind,long tick,Map<String,Integer> units,Snapshot before,Snapshot after) { }
    public static final class Frame {
        private final StagedTransfers issuer;private final UUID token=UUID.randomUUID();private final Owner owner;
        private final ValidatedRequest request;private final UUID execution;private final ActorRef actor;private final Plan plan;
        private final Budgets.Limits allowance;private final Budgets.Ledger usage;private final long first;
        private final long initialEffects;
        private final List<LiveMove> moves=new ArrayList<>();private final Map<String,Integer> acquired=new TreeMap<>();
        private Phase phase=Phase.WAITING;private Reason blocker;private int changes;private long accounted;
        private String sourceIdentity,destinationIdentity,expectedCustody,waitingFingerprint;
        private ResourceLeaseService.ImmediateTicket ticket;private Operation operation;private Snapshot captured;
        private Phase afterRelease;
        private boolean uncertain,dirty,cleanCancellation;
        private Frame(StagedTransfers issuer,Jobs.Job job,Plan plan,Budgets.Ledger usage,long tick){
            this.issuer=issuer;owner=new Owner(job.id(),job.generation());request=job.current().bound();
            execution=execution(job.current());actor=job.current().worker();this.plan=plan;
            allowance=job.current().allowance();this.usage=usage;first=accounted=tick;initialEffects=job.current().effects();
        }
        public UUID token(){return token;}
    }
    private final Thread thread=Thread.currentThread();private final JobLifecycleStore jobs;
    private final ResourceLeaseService leases;private final World world;private final Clock clock;private final Settings settings;
    private final Map<Owner,Frame> frames=new LinkedHashMap<>();private int cursor;
    public StagedTransfers(JobLifecycleStore jobs,ResourceLeaseService leases,World world,Clock clock,Settings settings){
        this.jobs=Objects.requireNonNull(jobs);this.leases=Objects.requireNonNull(leases);this.world=Objects.requireNonNull(world);
        this.clock=Objects.requireNonNull(clock);this.settings=Objects.requireNonNull(settings);
    }
    public Frame open(UUID jobId,Plan plan,TrustedContext caller){
        check();Jobs.Job job=jobs.query(jobId,caller);var a=job.current();
        if(!jobs.ready()||a==null||!a.open()||!a.executions().isEmpty()||job.state()!=Jobs.State.ACTIVE
                ||job.cancellationOutcome()!=null||!jobs.mayControl(jobId,job.generation(),caller)||job.parent()!=null)
            throw new SecurityException("Current reserved root Job without an interpreter required");
        bound(job,plan);Owner owner=new Owner(job.id(),job.generation());Frame old=frames.get(owner);
        if(old!=null){if(!old.plan.equals(plan))throw new SecurityException("Staging plan is immutable");return old;}
        if(a.effects()!=0||a.usage().getOrDefault(Budgets.Kind.COMMITTED_EFFECTS,0L)!=0)
            throw new SecurityException("Fresh physical preparation required");
        if(frames.size()>=settings.frames())throw new IllegalStateException("Staging frame capacity");
        var usage=new Budgets.Ledger(a.allowance(),clock);usage.debit(a.usage());
        Frame f=new Frame(this,job,plan,usage,leases.currentTick());frames.put(owner,f);
        if(plan.source().equals(plan.destination())){block(f,Reason.REQUEST_INVALID);return f;}
        Snapshot initial;
        try {initial=observe(f,Operation.WITHDRAW);}
        catch(Budgets.Exhausted exhausted){block(f,Reason.BUDGET_EXHAUSTED);return f;}
        if(initial.blocker()!=null){block(f,initial.blocker());return f;}
        f.sourceIdentity=initial.sourceIdentity();f.destinationIdentity=initial.destinationIdentity();f.expectedCustody=initial.actorFingerprint();
        int held=initial.actorStock().values().stream().mapToInt(Integer::intValue).sum();
        if(held+plan.units().stream().mapToInt(Units::quantity).sum()>settings.units())block(f,Reason.BUDGET_EXHAUSTED);
        else if(!initial.actorFits()||!initial.destinationFits())block(f,Reason.TARGET_UNAVAILABLE);
        return f;
    }
    public View step(Frame f,Operation operation,TrustedContext caller){
        check(f,caller);long tick=leases.currentTick();
        if(f.phase==Phase.BLOCKED||f.phase==Phase.DEPOSITED||f.phase==Phase.RETURNED)return view(f,caller);
        try {
            f.usage.debit(Budgets.Kind.ELAPSED_TICKS,Math.max(0,tick-f.accounted));f.accounted=tick;
            f.usage.debit(Budgets.Kind.INSTRUCTIONS,1);
            if(tick-f.first>settings.residenceTicks()){block(f,Reason.ACTION_TIMEOUT);return view(f,caller);}
            if(f.phase==Phase.RELEASING){release(f);publish(f);return view(f,caller);}
            if(!jobs.ready())return view(f,caller);
            if(!current(f,caller)){block(f,Reason.AUTHORITY_DENIED);return view(f,caller);}
            // Raw recipe ingredients are not the authorized transformation output.
            if(operation==Operation.DEPOSIT&&RecipeProduction.is(f.request.request().capability())){
                block(f,Reason.REQUEST_INVALID);return view(f,caller);
            }
            if(operation!=Operation.WITHDRAW&&f.acquired.isEmpty()){block(f,Reason.REQUEST_INVALID);return view(f,caller);}
            if(operation==Operation.WITHDRAW&&f.phase==Phase.HELD)return view(f,caller); // Exact repeat: no second withdrawal.
            if(f.ticket==null){
                f.operation=operation;
                f.ticket=leases.immediateTicket(f.owner,demands(f,operation),caller,()->probe(f));
            }else if(operation!=f.operation){
                // Explicit return is another physical action. No refund, discard or residence reset.
                if(operation==Operation.RETURN&&!f.acquired.isEmpty()&&++f.changes<=settings.replans()){
                    f.afterRelease=Phase.HELD;f.phase=Phase.RELEASING;release(f);return view(f,caller);
                }
                block(f,Reason.REQUEST_INVALID);return view(f,caller);
            }
            Result granted=leases.acquireImmediate(f.ticket,caller);
            if(granted.code()==Code.PENDING||granted.code()==Code.CONFLICT){f.blocker=granted.reason();return view(f,caller);}
            if(!granted.usable()){block(f,granted.reason()==null?Reason.INTERRUPTED:granted.reason());return view(f,caller);}
            // Existing owner checks current epoch/generation and native lease identities just before the effect.
            for(Ref ref:granted.leases()){
                Result valid=leases.validate(ref,caller);
                if(valid.code()==Code.PENDING)return view(f,caller);
                if(!valid.usable()){block(f,valid.reason());return view(f,caller);}
            }
            if(f.captured==null){block(f,Reason.STALE_OBSERVATION);return view(f,caller);}
            if(!f.usage.canDebit(Budgets.Kind.COMMITTED_EFFECTS,1))throw new Budgets.Exhausted();
            // Conservatively meter the native fresh-read and resulting-custody scans as well.
            f.usage.debit(Map.of(Budgets.Kind.ATTEMPTED_EFFECTS,1L,Budgets.Kind.OBSERVATIONS,528L));
            Move move;
            try {move=world.move(f.request,f.plan,operation,f.captured);}
            catch(RuntimeException unknown){f.uncertain=true;block(f,Reason.INTERRUPTED);return view(f,caller);}
            if(move.committed()){
                f.usage.debit(Budgets.Kind.COMMITTED_EFFECTS,1);
                f.moves.add(new LiveMove(UUID.randomUUID(),operation,tick,move.moved(),f.captured,move.after()));f.dirty=true;
                f.expectedCustody=move.after().actorFingerprint();
                if(operation==Operation.WITHDRAW)f.acquired.putAll(move.moved());
                else for(var moved:move.moved().entrySet())f.acquired.computeIfPresent(moved.getKey(),(id,n)->Math.max(0,n-moved.getValue()));
                f.acquired.values().removeIf(n->n==0);
                if(!move.moved().equals(f.plan.quantities())){block(f,Reason.STALE_OBSERVATION);return view(f,caller);}
                f.afterRelease=operation==Operation.WITHDRAW?Phase.HELD:operation==Operation.DEPOSIT?Phase.DEPOSITED:Phase.RETURNED;
            }else{f.afterRelease=Phase.BLOCKED;f.blocker=move.blocker()==null?Reason.ACTION_FAILED:move.blocker();}
            f.phase=Phase.RELEASING;release(f);publish(f);
        }catch(Budgets.Exhausted exhausted){block(f,Reason.BUDGET_EXHAUSTED);}
        return view(f,caller);
    }
    private Reason probe(Frame f){
        if(!current(f,f.request.context()))return Reason.AUTHORITY_DENIED;
        Snapshot s=observe(f,f.operation);
        if(s.blocker()!=null)return s.blocker();
        if(!s.sourceIdentity().equals(f.sourceIdentity)||!s.destinationIdentity().equals(f.destinationIdentity)
                ||!s.actorFingerprint().equals(f.expectedCustody))return Reason.STALE_OBSERVATION;
        String fingerprint=s.sourceFingerprint()+"/"+s.destinationFingerprint();
        if(f.waitingFingerprint!=null&&!f.waitingFingerprint.equals(fingerprint)&&++f.changes>settings.replans())return Reason.BUDGET_EXHAUSTED;
        f.waitingFingerprint=fingerprint;
        if(!s.reachable())return Reason.TARGET_UNAVAILABLE;
        if(f.operation==Operation.WITHDRAW){
            for(Units u:f.plan.units())if(s.sourceStock().getOrDefault(u.item().id(),0)<u.quantity())return Reason.RESOURCE_MISSING;
            if(!s.actorFits()||!s.destinationFits())return Reason.TARGET_UNAVAILABLE;
        }else if(!s.destinationFits())return Reason.TARGET_UNAVAILABLE;
        f.captured=s;return null;
    }
    private Snapshot observe(Frame f,Operation operation){
        // Maximum bounded scan is reserved before calling the native host; charge actual slots after.
        if(!f.usage.canDebit(Budgets.Kind.OBSERVATIONS,264))throw new Budgets.Exhausted();
        Snapshot snapshot=world.observe(f.request,f.plan,operation);
        f.usage.debit(Budgets.Kind.OBSERVATIONS,Math.max(1,snapshot.inspected()));return snapshot;
    }
    private List<Demand> demands(Frame f,Operation operation){
        UUID worldId=f.request.context().scope().worldId();var out=new ArrayList<Demand>();
        if(operation==Operation.WITHDRAW){
            for(Units u:f.plan.units())out.add(new Demand(new Resource(worldId,Kind.STOCK,ResourceLeases.point(f.plan.source()),u.item().id(),-1),u.quantity()));
            out.add(new Demand(Resource.facility(worldId,f.plan.source()),1));
        }
        out.add(new Demand(Resource.facility(worldId,operation==Operation.RETURN?f.plan.returnTarget():f.plan.destination()),1));
        return List.copyOf(out);
    }
    private boolean current(Frame f,TrustedContext caller){
        Jobs.Job job=jobs.query(f.owner.job(),caller);var a=job.current();
        return jobs.ready()&&job.generation()==f.owner.generation()&&job.state()==Jobs.State.ACTIVE&&job.cancellationOutcome()==null
                &&a!=null&&a.open()&&a.worker().equals(f.actor)&&a.bound().equals(f.request)&&a.allowance().equals(f.allowance)
                &&Objects.equals(execution(a),f.execution)
                &&jobs.mayControl(job.id(),job.generation(),caller)&&clock.millis()<=f.allowance.deadlineEpochMillis()
                &&a.usage().entrySet().stream().allMatch(e->e.getValue()<=f.usage.snapshot().getOrDefault(e.getKey(),0L));
    }
    private static void bound(Jobs.Job job,Plan p){
        var request=job.current().bound().request();Cuboid area=ResourceDelivery.sourceArea(request);
        var source=p.source();
        if(!source.dimension().equals(area.dimension())||source.x()<area.minX()||source.x()>area.maxX()||source.y()<area.minY()||source.y()>area.maxY()
                ||source.z()<area.minZ()||source.z()>area.maxZ()||!p.destination().equals(ResourceDelivery.destinationAddress(request)))
            throw new SecurityException("Original authorized staging addresses required");
        long amount=((IntValue)request.arguments().get("amount")).value();var allowed=new HashMap<String,Long>();
        if(LogAcquisition.is(request.capability()))allowed.put(LogAcquisition.item(request.capability()).id(),amount);
        else if(RecipeProduction.is(request.capability())){
            var recipe=RecipeProduction.of(request.capability());allowed.put(recipe.input().id(),(long)recipe.inputs(amount));
            if(recipe.axe())allowed.put(ResourceDelivery.STICK.id(),(long)recipe.secondaryInputs(amount));
        }else throw new SecurityException("Supported staging input envelope required");
        for(Units u:p.units())if(u.quantity()>allowed.getOrDefault(u.item().id(),0L))throw new SecurityException("Original staged quantity exceeded");
    }
    private static UUID execution(Jobs.Attempt attempt){return attempt.executions().isEmpty()?null:attempt.executions().getLast().runId();}
    private void release(Frame f){
        if(f.ticket==null)return;Result released=leases.completeImmediate(f.ticket,f.request.context());
        if(released.code()==Code.RELEASED||released.code()==Code.STALE){
            f.ticket=null;f.operation=null;f.captured=null;f.phase=f.afterRelease==null?Phase.BLOCKED:f.afterRelease;
            if(f.phase==Phase.BLOCKED)f.dirty=true;
        }
    }
    private void block(Frame f,Reason reason){
        f.blocker=reason==null?Reason.ACTION_FAILED:reason;f.afterRelease=Phase.BLOCKED;f.dirty=true;
        if(f.ticket==null)f.phase=Phase.BLOCKED;else{f.phase=Phase.RELEASING;release(f);}
        publish(f);
    }
    /** At most four derived frames per server tick; idle callers cannot renew residence. */
    public void tick(){
        check();if(frames.isEmpty())return;var rows=List.copyOf(frames.values());long now=leases.currentTick();
        for(int n=0;n<Math.min(4,rows.size());n++){
            cursor%=rows.size();Frame f=rows.get(cursor++);
            if(f.phase==Phase.RELEASING)release(f);
            if(jobs.ready()){
                Jobs.Job job=jobs.query(f.owner.job(),f.request.context());
                if(job.state()==Jobs.State.CANCELLING&&job.generation()==f.owner.generation()){
                    f.cleanCancellation=f.acquired.isEmpty()&&!f.uncertain;
                    block(f,Reason.CANCELLED);
                }else if(job.state().terminal()&&job.attempts().stream().noneMatch(Jobs.Attempt::open)
                        &&f.acquired.isEmpty()&&!f.uncertain){
                    frames.remove(f.owner);continue;
                }
            }
            if(f.phase!=Phase.BLOCKED&&(now-f.first>settings.residenceTicks()||clock.millis()>f.allowance.deadlineEpochMillis())){
                try{f.usage.debit(Budgets.Kind.ELAPSED_TICKS,Math.max(0,now-f.accounted));f.accounted=now;}
                catch(Budgets.Exhausted exhausted){ /* Scheduling stops; custody is preserved. */ }
                block(f,now-f.first>settings.residenceTicks()?Reason.ACTION_TIMEOUT:Reason.BUDGET_EXHAUSTED);
            }
            publish(f);
        }
    }
    private void publish(Frame f){
        if(!f.dirty||!jobs.ready())return;
        Jobs.Job job=jobs.query(f.owner.job(),f.request.context());var a=job.current();
        if(job.generation()!=f.owner.generation()||a==null||!a.open()||!a.bound().equals(f.request)
                ||!a.worker().equals(f.actor)||!Objects.equals(execution(a),f.execution))return;
        // Only live scalar accounting, through the original sole Job writer. No receipt or goal credit.
        Outcomes.Execution terminal=null;
        if(f.phase==Phase.BLOCKED){
            boolean noEffect=f.initialEffects==0&&f.moves.isEmpty()&&f.acquired.isEmpty()&&!f.uncertain;
            var status=f.cleanCancellation?Outcomes.ExecutionStatus.CANCELLED:
                    noEffect?Outcomes.ExecutionStatus.BLOCKED:Outcomes.ExecutionStatus.INTERRUPTED;
            terminal=new Outcomes.Execution(status,f.cleanCancellation?Reason.CANCELLED:noEffect?f.blocker:Reason.INTERRUPTED,
                    f.initialEffects+f.moves.size(),null);
        }
        var result=jobs.recordExecutionResult(job.id(),job.guard(),new Jobs.Report(a.id(),a.generation(),f.initialEffects+f.moves.size(),
                f.usage.snapshot(),a.receipts(),terminal,a.resourceReceipts()),f.request.context());
        if(result.accepted())f.dirty=false;
    }
    public boolean retainsCustody(UUID job){
        check();return frames.values().stream().anyMatch(f->f.owner.job().equals(job)&&(!f.acquired.isEmpty()||f.uncertain));
    }
    public View view(Frame f,TrustedContext caller){
        check(f,caller);return new View(f.token,f.owner,f.phase,Map.copyOf(f.acquired),f.changes,f.first,
                Math.max(0,leases.currentTick()-f.first),f.blocker,f.uncertain,List.copyOf(f.moves),f.usage.snapshot());
    }
    public boolean owns(Jobs.Job job){
        check();Frame f=frames.get(new Owner(job.id(),job.generation()));return f!=null&&f.phase!=Phase.BLOCKED
                &&job.state()==Jobs.State.ACTIVE&&job.current()!=null&&job.current().open()
                &&job.current().bound().equals(f.request)&&job.current().worker().equals(f.actor);
    }
    private void check(Frame f,TrustedContext caller){
        check();if(f==null||f.issuer!=this||!f.request.context().equals(caller))throw new SecurityException("Private staging frame");
    }
    private void check(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Staging server thread");}
}
