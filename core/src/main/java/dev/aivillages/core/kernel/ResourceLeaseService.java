package dev.aivillages.core.kernel;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.ResourceLeases.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;

/** Sole game-thread lease owner. Storage publishes immutable snapshots on an external worker. */
public final class ResourceLeaseService {
    @FunctionalInterface public interface Storage { CompletionStage<Snapshot> replace(Snapshot expected,Snapshot next); }
    @FunctionalInterface public interface Owners { OwnerFacts inspect(Owner owner,TrustedContext caller); }
    @FunctionalInterface public interface Observer { Observation observe(Resource resource,OwnerFacts owner); }
    private record Written(Snapshot snapshot,Throwable error,long at) { }
    private record Pending(Snapshot next,CompletableFuture<Written> written,long started) { }
    private final Thread thread=Thread.currentThread();
    private final Storage storage;
    private final Owners owners;
    private final Observer observer;
    private final Settings settings;
    private final LongSupplier ticks;
    private final Clock clock;
    private final UUID epoch=UUID.randomUUID();
    private Snapshot state;
    private Pending pending;
    private boolean fenced;
    private Reason failure;
    private long previousTick=-1;
    private int cursor, lastReconciled;

    public ResourceLeaseService(Snapshot initial,Storage storage,Owners owners,Observer observer,
                                Settings settings,LongSupplier ticks,Clock clock,boolean readOnly) {
        this.state=Objects.requireNonNull(initial); this.storage=Objects.requireNonNull(storage);
        this.owners=Objects.requireNonNull(owners); this.observer=Objects.requireNonNull(observer);
        this.settings=Objects.requireNonNull(settings); this.ticks=Objects.requireNonNull(ticks);
        this.clock=Objects.requireNonNull(clock); validateSnapshot(initial,settings);
        fenced=readOnly; if(readOnly)failure=Reason.STORAGE_UNAVAILABLE;
        if(!fenced) {
            List<Lease> rows=initial.leases().stream().map(l->l.state()==State.ACTIVE
                    ? retired(l,State.INTERRUPTED,Reason.INTERRUPTED):l).toList();
            publish(rows,initial.generation());
        }
    }
    public Settings settings() { return settings; }
    public Snapshot snapshot() { checkThread();return state; }
    public boolean ready() { checkThread();return !fenced && pending==null; }
    public Reason failure() { checkThread();return failure; }
    public int lastReconciled() { checkThread();return lastReconciled; }
    public long currentTick() { checkThread();return now(); }

    public Result acquire(Owner owner,List<Demand> requested,long duration,TrustedContext caller) {
        checkThread();long tick=now();
        if(!ready())return unavailable();
        OwnerFacts facts=facts(owner,caller);
        if(facts==null)return result(Code.REJECTED,Reason.AUTHORITY_DENIED);
        if(!facts.live())return result(Code.REJECTED,ownerReason(facts));
        if(duration<1 || duration>settings.duration())return result(Code.REJECTED,Reason.REQUEST_INVALID);
        List<Demand> group;
        try { group=normalize(requested); }
        catch(RuntimeException invalid) { return result(Code.REJECTED,Reason.REQUEST_INVALID); }
        List<Lease> rows=expireRows(tick);
        long active=rows.stream().filter(l->live(l,tick)).count();
        long own=rows.stream().filter(l->live(l,tick)&&l.owner().job().equals(owner.job())).count();
        if(rows.size()+group.size()>settings.retained())return result(Code.REJECTED,Reason.STORAGE_LIMIT_REACHED);
        if(active+group.size()>settings.active() || own+group.size()>settings.perJob())
            return result(Code.REJECTED,Reason.BUDGET_EXHAUSTED);
        List<Observation> observations=new ArrayList<>();
        int inspected=0;
        for(Demand demand:group) {
            if(!demand.resource().world().equals(state.world()))return result(Code.REJECTED,Reason.REQUEST_INVALID);
            Observation observation=observe(demand.resource(),facts,tick);
            inspected+=observation.inspected();
            Reason missing=observationReason(observation,tick);
            if(missing!=null)return new Result(Code.REJECTED,missing,null,List.of(),0,inspected);
            long free=free(demand.resource(),observation.quantity(),rows,tick);
            if(free<demand.quantity())return new Result(Code.CONFLICT,
                    observation.quantity()<demand.quantity()?Reason.RESOURCE_MISSING:Reason.TARGET_UNAVAILABLE,
                    null,List.of(),free,inspected);
            observations.add(observation);
        }
        long expires,generation;
        try { expires=Math.addExact(tick,duration);generation=Math.addExact(state.generation(),1); }
        catch(ArithmeticException overflow){return result(Code.REJECTED,Reason.REQUEST_INVALID);}
        UUID id=UUID.randomUUID();List<Ref> references=new ArrayList<>();
        for(int i=0;i<group.size();i++) {
            Demand demand=group.get(i);Observation seen=observations.get(i);
            Lease lease=new Lease(UUID.randomUUID(),id,epoch,generation,owner,facts.origin(),
                    demand.resource(),demand.quantity(),demand.quantity(),seen.identity(),tick,tick,
                    expires,0,State.ACTIVE,null,Map.of());
            rows.add(lease);references.add(lease.ref());
        }
        if(!publish(rows,generation))return unavailable();
        return new Result(Code.PENDING,null,id,references,0,inspected);
    }
    public Result group(UUID id,Owner owner,TrustedContext caller) {
        checkThread();long tick=now();
        OwnerFacts facts=facts(owner,caller);
        if(facts==null)return result(Code.REJECTED,Reason.AUTHORITY_DENIED);
        if(pending!=null && pending.next().leases().stream().anyMatch(l->l.group().equals(id)
                && l.owner().equals(owner) && l.origin().equals(facts.origin())))
            return new Result(Code.PENDING,fenced?failure:null,id,List.of(),0,0);
        List<Lease> rows=state.leases().stream().filter(l->l.group().equals(id)
                && l.owner().equals(owner) && l.origin().equals(facts.origin())).toList();
        if(rows.isEmpty())return result(Code.STALE,Reason.INTERRUPTED);
        if(fenced)return unavailable();
        if(!facts.live())return result(Code.REJECTED,ownerReason(facts));
        for(Lease row:rows) if(!live(row,tick))
            return new Result(row.state()==State.RELEASED?Code.RELEASED:Code.EXPIRED,
                    row.epoch().equals(epoch)?row.reason()==null?Reason.TARGET_UNAVAILABLE:row.reason():Reason.INTERRUPTED,
                    id,List.of(),0,0);
        return new Result(Code.GRANTED,null,id,rows.stream().map(Lease::ref).toList(),0,0);
    }
    public Result inspect(Owner owner,Resource resource,TrustedContext caller) {
        checkThread();long tick=now();
        if(!ready())return unavailable();
        OwnerFacts facts=facts(owner,caller);
        if(facts==null || !facts.live())return result(Code.REJECTED,facts==null?Reason.AUTHORITY_DENIED:ownerReason(facts));
        try { bound(resource); }catch(RuntimeException invalid){return result(Code.REJECTED,Reason.REQUEST_INVALID);}
        if(!resource.world().equals(state.world()))return result(Code.REJECTED,Reason.REQUEST_INVALID);
        Observation seen=observe(resource,facts,tick);Reason missing=observationReason(seen,tick);
        return missing==null?new Result(Code.VALID,null,null,List.of(),
                free(resource,seen.quantity(),state.leases(),tick),seen.inspected())
                :new Result(Code.REJECTED,missing,null,List.of(),0,seen.inspected());
    }
    public Result validate(Ref ref,TrustedContext caller) {
        checkThread();long tick=now();
        if(ref==null)return result(Code.REJECTED,Reason.REQUEST_INVALID);
        Lease row=find(ref.id());Result error=reference(row,ref,caller,tick,true);
        if(error!=null)return error;
        if(!ready())return unavailable();
        OwnerFacts facts=facts(row.owner(),caller);
        Observation seen=observe(row.resource(),facts,tick);
        Reason reason=row.resource().kind()==Kind.STOCK && row.remaining()==0
                && seen.status()==ObservationStatus.ABSENT && seen.reason()==null
                ? seen.tick()>tick || tick-seen.tick()>settings.observationAge()?Reason.STALE_OBSERVATION:null
                : observationReason(seen,tick);
        if(reason==null && !seen.identity().equals(row.identity()))reason=Reason.TARGET_INVALID;
        if(reason==null && row.resource().kind()==Kind.STOCK
                && held(row.resource(),state.leases(),tick)>seen.quantity())reason=Reason.RESOURCE_MISSING;
        if(reason==null && row.resource().kind()!=Kind.STOCK && seen.quantity()<1)reason=Reason.TARGET_UNAVAILABLE;
        if(reason!=null) {
            retireGroup(row.group(),State.REVOKED,reason);
            return new Result(Code.REJECTED,reason,null,List.of(),0,seen.inspected());
        }
        return new Result(Code.VALID,null,row.group(),List.of(ref),row.remaining(),seen.inspected());
    }
    public Result renew(List<Ref> references,long duration,TrustedContext caller) {
        checkThread();long tick=now();
        if(!ready())return unavailable();
        if(duration<1 || duration>settings.duration())return result(Code.REJECTED,Reason.REQUEST_INVALID);
        List<Lease> group=matching(references,caller,tick);
        if(group==null)return result(Code.STALE,Reason.INTERRUPTED);
        for(Lease row:group) {
            if(row.renewals()>=settings.renewals() || tick-row.renewed()<settings.renewalInterval())
                return result(Code.REJECTED,Reason.BUDGET_EXHAUSTED);
            Result valid=validate(row.ref(),caller);if(!valid.usable())return valid;
        }
        long generation,expires;
        try {generation=Math.addExact(state.generation(),1);expires=Math.addExact(tick,duration);}
        catch(ArithmeticException overflow){return result(Code.REJECTED,Reason.REQUEST_INVALID);}
        List<Lease> rows=new ArrayList<>(state.leases());List<Ref> refs=new ArrayList<>();
        for(Lease l:group) {
            Lease next=new Lease(l.id(),l.group(),epoch,generation,l.owner(),l.origin(),l.resource(),
                    l.quantity(),l.remaining(),l.identity(),l.granted(),tick,expires,l.renewals()+1,
                    State.ACTIVE,null,l.consumed());
            replace(rows,next);refs.add(next.ref());
        }
        if(!publish(rows,generation))return unavailable();
        return new Result(Code.PENDING,null,group.getFirst().group(),refs,0,0);
    }
    public Result release(List<Ref> references,TrustedContext caller) {
        checkThread();long tick=now();
        if(!ready())return unavailable();
        if(references==null || references.isEmpty() || references.size()>settings.group())
            return result(Code.REJECTED,Reason.REQUEST_INVALID);
        List<Lease> rows=new ArrayList<>(state.leases());UUID group=null;
        for(Ref ref:references) {
            if(ref==null)return result(Code.REJECTED,Reason.REQUEST_INVALID);
            Lease row=find(ref.id());Result error=reference(row,ref,caller,tick,false);
            if(error!=null)return error;
            if(group!=null && !group.equals(row.group()))return result(Code.REJECTED,Reason.REQUEST_INVALID);
            group=row.group();
        }
        UUID selected=group;
        List<Lease> all=rows.stream().filter(l->l.group().equals(selected)).toList();
        if(all.size()!=references.size() || references.stream().map(Ref::id).distinct().count()!=all.size())
            return result(Code.REJECTED,Reason.REQUEST_INVALID);
        if(all.stream().allMatch(l->l.state()!=State.ACTIVE))return result(Code.RELEASED,null);
        for(Lease row:all)replace(rows,retired(row,State.RELEASED,Reason.CANCELLED));
        if(!publish(rows,state.generation()))return unavailable();
        return new Result(Code.PENDING,null,group,List.of(),0,0);
    }
    /** Trusted gateway accounting after a real attributed harvest; no physical mutation. */
    public Result consumed(Ref ref,CropDelivery.CropReceipt receipt,TrustedContext caller) {
        checkThread();long tick=now();
        if(!ready())return unavailable();
        if(ref==null)return result(Code.REJECTED,Reason.REQUEST_INVALID);
        Lease row=find(ref.id());Result error=reference(row,ref,caller,tick,true);
        if(error!=null)return error;
        OwnerFacts facts=facts(row.owner(),caller);
        if(!row.resource().item().equals("minecraft:mature_wheat") || receipt==null
                || receipt.stage()!=CropDelivery.Stage.HARVEST || !receipt.actor().equals(facts.worker())
                || !receipt.runId().equals(facts.run())
                || !receipt.source().equals(row.resource().area()))
            return result(Code.REJECTED,Reason.REQUEST_INVALID);
        Long prior=row.consumed().get(receipt.receiptId());
        if(prior!=null)return result(prior==receipt.wheat()?Code.VALID:Code.REJECTED,
                prior==receipt.wheat()?null:Reason.REQUEST_INVALID);
        if(receipt.wheat()>row.remaining())return result(Code.REJECTED,Reason.REQUEST_INVALID);
        if(row.consumed().size()==64)return result(Code.REJECTED,Reason.BUDGET_EXHAUSTED);
        var consumed=new LinkedHashMap<>(row.consumed());consumed.put(receipt.receiptId(),receipt.wheat());
        Lease next=new Lease(row.id(),row.group(),row.epoch(),row.generation(),row.owner(),row.origin(),
                row.resource(),row.quantity(),row.remaining()-receipt.wheat(),row.identity(),row.granted(),
                row.renewed(),row.expires(),row.renewals(),row.state(),null,consumed);
        List<Lease> rows=new ArrayList<>(state.leases());replace(rows,next);
        if(!publish(rows,state.generation()))return unavailable();
        return new Result(Code.PENDING,null,row.group(),List.of(ref),0,0);
    }
    public void tick() {
        checkThread();long tick=now();lastReconciled=0;
        if(fenced)return;
        if(pending!=null) {
            if(clock.millis()<pending.started() || clock.millis()-pending.started()>settings.acknowledgementMillis()) {
                fenced=true;failure=Reason.STORAGE_UNAVAILABLE;return;
            }
            if(!pending.written().isDone())return;
            Written written=pending.written().join();
            if(written.error()!=null || !pending.next().equals(written.snapshot())
                    || written.at()<pending.started() || written.at()-pending.started()>settings.acknowledgementMillis()) {
                fenced=true;failure=Reason.STORAGE_UNAVAILABLE;return;
            }
            state=written.snapshot();pending=null;return;
        }
        if(state.leases().isEmpty())return;
        int count=Math.min(settings.reconcile(),state.leases().size());
        for(int n=0;n<count;n++) {
            cursor%=state.leases().size();Lease row=state.leases().get(cursor++);lastReconciled++;
            if(row.state()!=State.ACTIVE)continue;
            Reason reason=null;State terminal=State.REVOKED;
            if(!live(row,tick)){reason=Reason.TARGET_UNAVAILABLE;terminal=State.EXPIRED;}
            else {
                OwnerFacts facts=facts(row.owner(),row.origin());
                if(facts==null || !facts.live())reason=facts==null?Reason.AUTHORITY_DENIED:ownerReason(facts);
                // Effects re-observe. Maintenance avoids repeatedly scanning every resource.
            }
            if(reason!=null){retireGroup(row.group(),terminal,reason);return;}
        }
    }
    public Roots protectedRoots() {
        checkThread();Set<UUID> ids=new LinkedHashSet<>();
        for(Lease l:state.leases())ids.add(l.owner().job());
        if(pending!=null)for(Lease l:pending.next().leases())ids.add(l.owner().job());
        return new Roots(ids,!fenced);
    }
    private List<Demand> normalize(List<Demand> values) {
        if(values==null || values.isEmpty() || values.size()>settings.group())throw new IllegalArgumentException("Group cap");
        var merged=new LinkedHashMap<Resource,Long>();
        for(Demand value:values) {
            Objects.requireNonNull(value);bound(value.resource());
            merged.merge(value.resource(),value.quantity(),(a,b)->value.resource().kind()==Kind.STOCK?Math.addExact(a,b):1L);
        }
        List<Demand> result=merged.entrySet().stream().map(e->new Demand(e.getKey(),e.getValue())).toList();
        for(int n=0;n<result.size();n++)for(int other=n+1;other<result.size();other++)
            if(conflicts(result.get(n).resource(),result.get(other).resource()))
                throw new IllegalArgumentException("Ambiguous overlapping group");
        return result;
    }
    private void bound(Resource r) {
        Objects.requireNonNull(r);Cuboid a=r.area();
        if(cells(a)>settings.cells() || (long)a.maxX()-a.minX()+1>settings.axis()
                || (long)a.maxY()-a.minY()+1>settings.axis() || (long)a.maxZ()-a.minZ()+1>settings.axis())
            throw new IllegalArgumentException("Spatial limit");
    }
    private long held(Resource resource,List<Lease> rows,long tick) {
        long held=0;
        for(Lease row:rows) if(live(row,tick) && conflicts(resource,row.resource())) {
            if(!resource.equals(row.resource()) || resource.kind()!=Kind.STOCK)return Long.MAX_VALUE;
            held=Math.addExact(held,row.remaining());
        }
        return held;
    }
    private long free(Resource r,long stock,List<Lease> rows,long tick) {
        long held=held(r,rows,tick);return held==Long.MAX_VALUE?0:Math.max(0,stock-held);
    }
    static boolean conflicts(Resource a,Resource b) {
        if(!a.world().equals(b.world()) || a.kind()!=b.kind() || !overlaps(a.area(),b.area()))return false;
        return switch(a.kind()) {
            case SPACE,FACILITY -> true;
            case EQUIPMENT -> a.slot()==b.slot();
            case STOCK -> a.item().equals(b.item());
        };
    }
    private boolean live(Lease l,long tick) {
        return l.state()==State.ACTIVE && l.epoch().equals(epoch) && tick<l.expires();
    }
    private Observation observe(Resource resource,OwnerFacts facts,long tick) {
        try {
            Observation seen=observer.observe(resource,facts);
            if(seen!=null && seen.resource().equals(resource))return seen;
        }catch(RuntimeException ignored){ }
        return new Observation(resource,ObservationStatus.UNKNOWN,0,"unavailable",tick,0,Reason.TARGET_UNAVAILABLE);
    }
    private Reason observationReason(Observation o,long tick) {
        if(o.reason()!=null)return o.reason();
        if(o.status()==ObservationStatus.UNKNOWN)return Reason.TARGET_UNAVAILABLE;
        if(o.tick()>tick || tick-o.tick()>settings.observationAge())return Reason.STALE_OBSERVATION;
        if(o.status()==ObservationStatus.ABSENT)return o.resource().kind()==Kind.FACILITY?Reason.FACILITY_MISSING:Reason.RESOURCE_MISSING;
        return null;
    }
    private OwnerFacts facts(Owner owner,TrustedContext caller) {
        if(owner==null || caller==null || !caller.scope().worldId().equals(state.world()))return null;
        try {
            OwnerFacts facts=owners.inspect(owner,caller);
            if(facts!=null && facts.origin().scope().worldId().equals(state.world()))return facts;
        }catch(SecurityException denied){ }
        return null;
    }
    private Reason ownerReason(OwnerFacts facts) { return facts.reason()==null?Reason.AUTHORITY_DENIED:facts.reason(); }
    private Lease find(UUID id) { return state.leases().stream().filter(l->l.id().equals(id)).findFirst().orElse(null); }
    private Result reference(Lease row,Ref ref,TrustedContext caller,long tick,boolean active) {
        if(row==null)return result(Code.STALE,Reason.INTERRUPTED);
        OwnerFacts facts=facts(row.owner(),caller);
        if(facts==null)return result(Code.REJECTED,Reason.AUTHORITY_DENIED);
        if(!row.epoch().equals(epoch) || !ref.epoch().equals(row.epoch()) || ref.generation()!=row.generation())
            return result(Code.STALE,Reason.INTERRUPTED);
        if(active && (!facts.live() || !live(row,tick)))
            return result(tick>=row.expires()?Code.EXPIRED:Code.REJECTED,!facts.live()?ownerReason(facts):Reason.TARGET_UNAVAILABLE);
        return null;
    }
    private List<Lease> matching(List<Ref> refs,TrustedContext caller,long tick) {
        if(refs==null || refs.isEmpty() || refs.size()>settings.group())return null;
        List<Lease> rows=new ArrayList<>();UUID group=null;
        for(Ref ref:refs) {
            if(ref==null)return null;Lease row=find(ref.id());
            if(reference(row,ref,caller,tick,true)!=null || group!=null&&!group.equals(row.group()))return null;
            group=row.group();rows.add(row);
        }
        UUID selected=group;
        if(refs.stream().map(Ref::id).distinct().count()!=rows.size()
                || state.leases().stream().filter(l->l.group().equals(selected)).count()!=rows.size())return null;
        return rows;
    }
    private List<Lease> expireRows(long tick) {
        List<Lease> rows=new ArrayList<>();
        for(Lease l:state.leases())rows.add(l.state()==State.ACTIVE && !live(l,tick)?retired(l,State.EXPIRED,Reason.TARGET_UNAVAILABLE):l);
        return rows;
    }
    private void retireGroup(UUID id,State terminal,Reason reason) {
        if(!ready())return;List<Lease> rows=new ArrayList<>(state.leases());
        for(Lease l:List.copyOf(rows))if(l.group().equals(id)&&l.state()==State.ACTIVE)replace(rows,retired(l,terminal,reason));
        publish(rows,state.generation());
    }
    private static Lease retired(Lease l,State state,Reason reason) {
        return new Lease(l.id(),l.group(),l.epoch(),l.generation(),l.owner(),l.origin(),l.resource(),
                l.quantity(),l.remaining(),l.identity(),l.granted(),l.renewed(),l.expires(),l.renewals(),state,reason,l.consumed());
    }
    private static void replace(List<Lease> rows,Lease replacement) {
        for(int n=0;n<rows.size();n++)if(rows.get(n).id().equals(replacement.id())){rows.set(n,replacement);return;}
        throw new IllegalArgumentException("Missing lease");
    }
    private boolean publish(List<Lease> rows,long generation) {
        long started=clock.millis();
        try {
            Snapshot next=new Snapshot(state.world(),epoch,Math.addExact(state.revision(),1),generation,rows);
            validateSnapshot(next,settings);
            CompletableFuture<Written> written=storage.replace(state,next)
                    .handle((value,error)->new Written(value,error,clock.millis())).toCompletableFuture();
            pending=new Pending(next,written,started);return true;
        }catch(RuntimeException unavailable){fenced=true;failure=Reason.STORAGE_UNAVAILABLE;return false;}
    }
    private long now() {
        long value=ticks.getAsLong();
        if(value<0 || value<previousTick){fenced=true;failure=Reason.INTERRUPTED;return Math.max(0,previousTick);}
        previousTick=value;return value;
    }
    private Result unavailable() { return result(fenced?Code.REJECTED:Code.PENDING,fenced?failure:Reason.BUDGET_EXHAUSTED); }
    private static Result result(Code code,Reason reason) { return new Result(code,reason,null,List.of(),0,0); }
    private void checkThread(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Lease game thread");}
    public static void validateSnapshot(Snapshot s,Settings settings) {
        List<Lease> active=s.leases().stream().filter(l->l.state()==State.ACTIVE).toList();
        if(s.leases().size()>settings.retained() || active.size()>settings.active())throw new IllegalArgumentException("Lease quota");
        Map<UUID,Long> jobs=new HashMap<>();Map<UUID,List<Lease>> groups=new HashMap<>();
        for(Lease l:s.leases())groups.computeIfAbsent(l.group(),ignored->new ArrayList<>()).add(l);
        for(Lease l:s.leases()) {
            Cuboid a=l.resource().area();
            if(cells(a)>settings.cells() || (long)a.maxX()-a.minX()+1>settings.axis()
                    || (long)a.maxY()-a.minY()+1>settings.axis() || (long)a.maxZ()-a.minZ()+1>settings.axis()
                    || l.expires()-l.renewed()>settings.duration() || l.renewals()>settings.renewals())
                throw new IllegalArgumentException("Persisted lease configuration bounds");
        }
        for(List<Lease> group:groups.values()) {
            Lease first=group.getFirst();
            if(group.size()>settings.group() || group.stream().map(Lease::resource).distinct().count()!=group.size()
                    || group.stream().anyMatch(l->!l.owner().equals(first.owner())||!l.origin().equals(first.origin())
                            || !l.epoch().equals(first.epoch()) || l.generation()!=first.generation()
                            || l.granted()!=first.granted() || l.renewed()!=first.renewed()
                            || l.expires()!=first.expires() || l.renewals()!=first.renewals()
                            || l.state()!=first.state() || l.reason()!=first.reason()))
                throw new IllegalArgumentException("Atomic lease group");
        }
        for(Lease l:active) {
            if(jobs.merge(l.owner().job(),1L,Long::sum)>settings.perJob())throw new IllegalArgumentException("Owner lease quota");
            for(Lease other:active) if(!l.id().equals(other.id()) && conflicts(l.resource(),other.resource())
                    && (l.resource().kind()!=Kind.STOCK || !l.resource().equals(other.resource())))
                throw new IllegalArgumentException("Conflicting exclusive grants");
        }
    }
}
