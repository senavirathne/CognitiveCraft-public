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
    /** Trusted immutable retired history. Lookup is memory-only and never grants a live claim. */
    public interface History {
        boolean ready(); boolean failed();
        Lease lookup(UUID id);
        List<Lease> group(UUID id);
    }
    @FunctionalInterface public interface Owners {
        OwnerFacts inspect(Owner owner,TrustedContext caller);
        /** Existing ownership may retain coordination while permission is denied and stop is unconfirmed. */
        default boolean stopPending(Owner owner,TrustedContext caller) { return false; }
    }
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
    private History history;
    private LeaseArchive archiving;
    private boolean fenced;
    private Reason failure;
    private long previousTick=-1;
    private int cursor, lastReconciled;
    /** Imminent callers supply a fresh native feasibility check; a ticket is never a claim. */
    @FunctionalInterface public interface Imminent { Reason blocker(); }
    public record FairLimits(int queued,long waitingTicks,long idleTicks,long backoffTicks,long claimTicks) {
        public FairLimits {
            if(queued<1||queued>64||waitingTicks<1||waitingTicks>1200||idleTicks<1||idleTicks>waitingTicks
                    ||backoffTicks<1||backoffTicks>20||claimTicks<1||claimTicks>20)
                throw new IllegalArgumentException("Finite imminent-claim limits");
        }
        public static FairLimits defaults(){return new FairLimits(16,1200,40,2,8);}
    }
    public record FairView(UUID ticket,Owner owner,long firstTick,long lastTick,long eligibleTick,
                           long grantedTick,long attempts,Reason blocker,UUID group) { }
    /** Process-bound opaque ticket; retired objects remain stale after diagnostic history truncation. */
    public static final class ImmediateTicket {
        private final ResourceLeaseService issuer;private final UUID id=UUID.randomUUID();
        private final Owner owner;private final TrustedContext caller;private final List<Demand> demands;
        private final Imminent imminent;private final UUID run;private final ActorRef worker;private boolean retired;
        private ImmediateTicket(ResourceLeaseService issuer,Owner owner,TrustedContext caller,List<Demand> demands,
                                Imminent imminent,OwnerFacts facts){
            this.issuer=issuer;this.owner=owner;this.caller=caller;this.demands=List.copyOf(demands);
            this.imminent=imminent;run=facts.run();worker=facts.worker();
        }
        public UUID id(){return id;}
    }
    private static final class FairEntry {
        final ImmediateTicket ticket;
        final UUID token;final Owner owner;final TrustedContext caller;final List<Demand> demands;
        final Imminent imminent;final long first,sequence;long touched,next,eligible=-1,granted=-1,attempts;
        Reason blocker;Result acquisition;
        FairEntry(ImmediateTicket ticket,UUID token,Owner owner,TrustedContext caller,List<Demand> demands,Imminent imminent,long tick,long sequence){
            this.ticket=ticket;
            this.token=token;this.owner=owner;this.caller=caller;this.demands=demands;this.imminent=imminent;
            first=touched=tick;this.sequence=sequence;
        }
        FairView view(){return new FairView(token,owner,first,touched,eligible,granted,attempts,blocker,acquisition==null?null:acquisition.group());}
    }
    private FairLimits fairLimits=FairLimits.defaults();
    private final Map<UUID,FairEntry> fair=new LinkedHashMap<>();
    private final Deque<Map.Entry<TrustedContext,FairView>> fairHistory=new ArrayDeque<>();
    private long fairSequence;

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
    private boolean storageReady() {
        checkThread();if(history!=null && history.failed()){fenced=true;failure=Reason.STORAGE_UNAVAILABLE;}
        return !fenced && pending==null && (history==null || history.ready());
    }
    public boolean ready() {return storageReady() && archiving==null;}
    boolean beginArchive(LeaseArchive client,Snapshot expected) {
        checkThread();if(state!=expected || !ready())return false;archiving=Objects.requireNonNull(client);return true;
    }
    void endArchive(LeaseArchive client) {
        checkThread();if(archiving==client)archiving=null;
        else if(archiving!=null)throw new IllegalStateException("Archive capture owner");
    }
    boolean archiveHeldBy(LeaseArchive client){checkThread();return archiving==client;}
    public void attachHistory(History history) {
        checkThread();if(this.history!=null)throw new IllegalStateException("Lease history already attached");
        this.history=Objects.requireNonNull(history);
    }
    /** Exact historical evidence for the original holder, never permission to renew/use it. */
    public Optional<Lease> historical(Ref ref,Owner owner,TrustedContext caller) {
        checkThread();if(!ready())throw new IllegalStateException("Lease history unavailable");
        Objects.requireNonNull(ref);Lease row=find(ref.id());if(row==null)return Optional.empty();
        if(!row.origin().equals(caller) || !row.owner().equals(owner))throw new SecurityException("Private lease history");
        return row.ref().equals(ref)?Optional.of(row):Optional.empty();
    }
    /** Only an opaque publication made by this service's archive client can remove hot history. */
    public boolean archive(LeaseArchive.Publication publication) {
        checkThread();if(!storageReady() || publication==null || !publication.permits(this,state))return false;
        Set<UUID> ids=new HashSet<>();for(Lease row:publication.rows())ids.add(row.id());
        List<Lease> retained=state.leases().stream().filter(l->!ids.contains(l.id())).toList();
        if(retained.size()+ids.size()!=state.leases().size())return false;
        return publish(retained,state.generation());
    }
    public Reason failure() { checkThread();return failure; }
    public int lastReconciled() { checkThread();return lastReconciled; }
    public long currentTick() { checkThread();return now(); }
    /** Host-observed stock availability only; no fabricated owner, reservation or cross-principal identity disclosure. */
    public Result availableStock(Resource resource,long physicallyObserved,TrustedContext caller) {
        checkThread();long tick=now();if(!ready())return unavailable();
        if(resource==null || caller==null || resource.kind()!=Kind.STOCK || physicallyObserved<0
                || !resource.world().equals(state.world()) || !caller.scope().worldId().equals(state.world()))
            return result(Code.REJECTED,Reason.REQUEST_INVALID);
        return new Result(Code.VALID,null,null,List.of(),free(resource,physicallyObserved,state.leases(),tick),0);
    }

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
            if(missing!=null && Boolean.getBoolean("cognitivecraft.gametest.trace"))
                System.out.println("CognitiveCraft LEASE_OBSERVATION resource="+demand.resource()+" status="+observation.status()+" quantity="+observation.quantity()
                        +" inspected="+observation.inspected()+" identity="+observation.identity()+" reason="+missing+" tick="+tick);
            if (missing != null) {
                // Native discovery can be unknown before the worker and target are both observable.
                // This pending answer has no claim and requires another observation before any grant.
                Code code = observation.status() == ObservationStatus.UNKNOWN && missing == Reason.AUTHORITY_DENIED
                        ? Code.PENDING : Code.REJECTED;
                return new Result(code, missing, null, List.of(), 0, inspected);
            }
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
    /** Configure once before the first ticket. Existing live/history quotas are never widened. */
    public void fairLimits(FairLimits limits){
        checkThread();if(!fair.isEmpty()||fairSequence!=0)throw new IllegalStateException("Fair claims already started");
        fairLimits=Objects.requireNonNull(limits);
    }
    /**
     * Stable, process-local priority for a currently feasible imminent group. No lease is held while
     * native reach/capacity/stock is infeasible. Older feasible overlapping demand wins even when a
     * younger caller polls first. The existing sole lease owner performs every grant/publication.
     */
    public ImmediateTicket immediateTicket(Owner owner,List<Demand> requested,TrustedContext caller,Imminent imminent){
        checkThread();Objects.requireNonNull(imminent);OwnerFacts fact=facts(owner,caller);
        if(fact==null||!fact.live())throw new SecurityException("Current durable Job owner required");
        var demands=new ArrayList<>(normalize(requested));demands.sort(Comparator.comparing(d->resourceOrder(d.resource())));
        return new ImmediateTicket(this,owner,caller,demands,imminent,fact);
    }
    public Result acquireImmediate(ImmediateTicket ticket,TrustedContext caller){
        checkThread();if(ticket==null||ticket.issuer!=this||ticket.retired)return result(Code.STALE,Reason.INTERRUPTED);
        if(!ticket.caller.equals(caller))return result(Code.REJECTED,Reason.AUTHORITY_DENIED);
        OwnerFacts fact=facts(ticket.owner,caller);
        if(fact==null||!fact.live()||!Objects.equals(fact.run(),ticket.run)||!fact.worker().equals(ticket.worker))
            return result(Code.REJECTED,Reason.AUTHORITY_DENIED);
        return acquireImmediate(ticket);
    }
    private Result acquireImmediate(ImmediateTicket ticket){
        UUID token=ticket.id;Owner owner=ticket.owner;TrustedContext caller=ticket.caller;
        List<Demand> requested=ticket.demands;Imminent imminent=ticket.imminent;
        checkThread();Objects.requireNonNull(token);Objects.requireNonNull(imminent);long tick=now();
        if(!ready())return unavailable();
        OwnerFacts fact=facts(owner,caller);
        if(fact==null||!fact.live())return result(Code.REJECTED,fact==null?Reason.AUTHORITY_DENIED:ownerReason(fact));
        List<Demand> normalized;
        try {normalized=new ArrayList<>(normalize(requested));normalized.sort(Comparator.comparing(d->resourceOrder(d.resource())));}
        catch(RuntimeException invalid){return result(Code.REJECTED,Reason.REQUEST_INVALID);}
        reapFair(tick);
        if(ticket.retired)return result(Code.STALE,Reason.INTERRUPTED);
        FairEntry entry=fair.get(token);
        if(entry==null){
            if(fair.size()>=fairLimits.queued())return result(Code.REJECTED,Reason.BUDGET_EXHAUSTED);
            for(var prior:fairHistory)if(prior.getValue().ticket().equals(token))return result(Code.STALE,Reason.INTERRUPTED);
            if(fairSequence==Long.MAX_VALUE)return result(Code.REJECTED,Reason.BUDGET_EXHAUSTED);
            entry=new FairEntry(ticket,token,owner,caller,List.copyOf(normalized),imminent,tick,fairSequence++);fair.put(token,entry);
        }
        if(!entry.owner.equals(owner)||!entry.caller.equals(caller)||!entry.demands.equals(normalized))
            return result(Code.REJECTED,Reason.AUTHORITY_DENIED);
        entry.touched=tick;
        if(entry.acquisition!=null)return group(entry.acquisition.group(),owner,caller);
        if(tick-entry.first>fairLimits.waitingTicks()){
            rememberFair(entry,Reason.ACTION_TIMEOUT);fair.remove(token);return result(Code.REJECTED,Reason.ACTION_TIMEOUT);
        }
        if(tick<entry.next)return result(Code.PENDING,entry.blocker);
        entry.next=tick+fairLimits.backoffTicks();entry.attempts++;
        Reason own=feasible(entry,tick);
        if(own!=null)return infeasible(entry,own);
        for(FairEntry older:fair.values()){
            if(older.sequence>=entry.sequence)break;
            if(older.acquisition!=null||!groupsOverlap(older.demands,entry.demands))continue;
            if(feasible(older,tick)==null){entry.blocker=Reason.TARGET_UNAVAILABLE;return result(Code.PENDING,entry.blocker);}
        }
        // Native feasibility is observed again immediately before the authoritative lease grant.
        own=feasible(entry,tick);if(own!=null)return infeasible(entry,own);
        Result acquired=acquire(owner,entry.demands,Math.min(fairLimits.claimTicks(),settings.duration()),caller);
        entry.blocker=acquired.reason();
        if(acquired.group()!=null){entry.acquisition=acquired;entry.granted=tick;}
        return acquired.code()==Code.CONFLICT?new Result(Code.PENDING,acquired.reason(),null,List.of(),acquired.available(),acquired.inspected()):acquired;
    }
    /** Complete/release only this exact caller's whole group; stale releases cannot affect another grant. */
    public Result completeImmediate(ImmediateTicket ticket,TrustedContext caller){
        checkThread();if(ticket==null||ticket.issuer!=this||ticket.retired)return result(Code.STALE,Reason.INTERRUPTED);
        UUID token=ticket.id;Owner owner=ticket.owner;FairEntry entry=fair.get(token);
        if(!ticket.caller.equals(caller))return result(Code.REJECTED,Reason.AUTHORITY_DENIED);
        if(entry==null)return result(Code.STALE,Reason.INTERRUPTED);
        if(!entry.owner.equals(owner)||!entry.caller.equals(caller))return result(Code.REJECTED,Reason.AUTHORITY_DENIED);
        if(entry.acquisition==null){rememberFair(entry,Reason.CANCELLED);fair.remove(token);return result(Code.RELEASED,null);}
        Result released=release(entry.acquisition.leases(),caller);
        if(released.code()==Code.RELEASED||released.code()==Code.STALE){rememberFair(entry,released.reason());fair.remove(token);}
        return released;
    }
    /** Private diagnostics only; no queue position or identity from another principal is exposed. */
    public List<FairView> immediateView(TrustedContext caller){
        checkThread();var out=new ArrayList<FairView>();
        for(var entry:fairHistory)if(entry.getKey().equals(caller))out.add(entry.getValue());
        for(var entry:fair.values())if(entry.caller.equals(caller))out.add(entry.view());
        return List.copyOf(out);
    }
    private Reason feasible(FairEntry entry,long tick){
        OwnerFacts fact=facts(entry.owner,entry.caller);
        if(fact==null||!fact.live()||!Objects.equals(fact.run(),entry.ticket.run)||!fact.worker().equals(entry.ticket.worker))
            return entry.blocker=Reason.AUTHORITY_DENIED;
        Reason nativeBlocker;
        try {nativeBlocker=entry.imminent.blocker();}
        catch(Budgets.Exhausted exhausted){nativeBlocker=Reason.BUDGET_EXHAUSTED;}
        catch(RuntimeException unavailable){nativeBlocker=Reason.TARGET_UNAVAILABLE;}
        if(nativeBlocker!=null)return entry.blocker=nativeBlocker;
        for(Demand demand:entry.demands){
            if(!demand.resource().world().equals(state.world()))return entry.blocker=Reason.REQUEST_INVALID;
            Observation observation=observe(demand.resource(),fact,tick);Reason missing=observationReason(observation,tick);
            if(missing!=null)return entry.blocker=missing;
            if(free(demand.resource(),observation.quantity(),state.leases(),tick)<demand.quantity())return entry.blocker=Reason.RESOURCE_MISSING;
        }
        if(entry.eligible<0)entry.eligible=tick;entry.blocker=null;return null;
    }
    private Result infeasible(FairEntry entry,Reason reason){
        entry.blocker=reason;
        return result(reason==Reason.RESOURCE_MISSING||reason==Reason.TARGET_UNAVAILABLE?Code.PENDING:Code.REJECTED,reason);
    }
    private static String resourceOrder(Resource r){
        var a=r.area();return r.world()+"/"+r.kind().ordinal()+"/"+a.dimension()+"/"+a.minX()+"/"+a.minY()+"/"+a.minZ()+"/"+a.maxX()+"/"+a.maxY()+"/"+a.maxZ()+"/"+r.item()+"/"+r.slot();
    }
    private static boolean groupsOverlap(List<Demand> a,List<Demand> b){
        for(Demand one:a)for(Demand other:b)if(conflicts(one.resource(),other.resource()))return true;return false;
    }
    private void rememberFair(FairEntry entry,Reason reason){
        entry.ticket.retired=true;entry.blocker=reason;fairHistory.addLast(Map.entry(entry.caller,entry.view()));while(fairHistory.size()>128)fairHistory.removeFirst();
    }
    private void reapFair(long tick){
        var iterator=fair.values().iterator();
        while(iterator.hasNext()){
            FairEntry entry=iterator.next();
            if(entry.acquisition==null&&(tick-entry.touched>fairLimits.idleTicks()||tick-entry.first>fairLimits.waitingTicks())){
                rememberFair(entry,Reason.ACTION_TIMEOUT);iterator.remove();
            }
        }
    }
    public Result group(UUID id,Owner owner,TrustedContext caller) {
        checkThread();long tick=now();
        if(archiving!=null || history!=null && !history.ready()){ready();return unavailable();}
        OwnerFacts facts=facts(owner,caller);
        if(facts==null)return result(Code.REJECTED,Reason.AUTHORITY_DENIED);
        if(pending!=null && pending.next().leases().stream().anyMatch(l->l.group().equals(id)
                && l.owner().equals(owner) && l.origin().equals(facts.origin())))
            return new Result(Code.PENDING,fenced?failure:null,id,List.of(),0,0);
        List<Lease> rows=state.leases().stream().filter(l->l.group().equals(id)
                && l.owner().equals(owner) && l.origin().equals(facts.origin())).toList();
        if(rows.isEmpty() && history!=null && history.ready())rows=history.group(id).stream()
                .filter(l->l.owner().equals(owner) && l.origin().equals(facts.origin())).toList();
        if(rows.isEmpty())return result(Code.STALE,Reason.INTERRUPTED);
        if(fenced)return unavailable();
        if(!facts.live())return result(Code.REJECTED,ownerReason(facts));
        for(Lease row:rows) if(tick>=row.expires() || !live(row,tick))
            return new Result(row.state()==State.RELEASED?Code.RELEASED:Code.EXPIRED,
                    row.epoch().equals(epoch)?row.reason()==null?Reason.TARGET_UNAVAILABLE:row.reason():Reason.INTERRUPTED,
                    id,List.of(),0,0);
        return new Result(Code.GRANTED,null,id,rows.stream().map(Lease::ref).toList(),0,0);
    }
    /** IMP-018 read-only evidence from the same inspection that subtracts current claims. */
    public record Inspection(Result decision, Observation observation) { }
    public Result inspect(Owner owner,Resource resource,TrustedContext caller) {
        return inspectWithEvidence(owner,resource,caller).decision();
    }
    public Inspection inspectWithEvidence(Owner owner,Resource resource,TrustedContext caller) {
        checkThread();long tick=now();
        if(!ready())return new Inspection(unavailable(),null);
        OwnerFacts facts=facts(owner,caller);
        if(facts==null || !facts.live())return new Inspection(result(Code.REJECTED,facts==null?Reason.AUTHORITY_DENIED:ownerReason(facts)),null);
        try { bound(resource); }catch(RuntimeException invalid){return new Inspection(result(Code.REJECTED,Reason.REQUEST_INVALID),null);}
        if(!resource.world().equals(state.world()))return new Inspection(result(Code.REJECTED,Reason.REQUEST_INVALID),null);
        Observation seen=observe(resource,facts,tick);Reason missing=observationReason(seen,tick);
        Result decision=missing==null?new Result(Code.VALID,null,null,List.of(),
                free(resource,seen.quantity(),state.leases(),tick),seen.inspected())
                :new Result(Code.REJECTED,missing,null,List.of(),0,seen.inspected());
        return new Inspection(decision,seen);
    }
    public Result validate(Ref ref,TrustedContext caller) {
        checkThread();long tick=now();
        if(history!=null && !history.ready()){ready();return unavailable();}
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
        if(all.isEmpty() && history!=null && history.ready())all=history.group(selected);
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
    /** Host-only accounting of one removed oak block, including zero/partial loot outcomes.
     * Drop item counts never debit source block units. The leased gateway supplies the stable native action ID. */
    public Result consumedOakBlock(Ref ref,ValidatedRequest bound,RunCorrelation run,UUID operation,TrustedContext caller) {
        checkThread(); long tick=now();
        if(!ready()) return unavailable();
        if(ref==null || operation==null || bound==null || run==null || !bound.context().equals(caller))
            return result(Code.REJECTED,Reason.REQUEST_INVALID);
        Lease row=find(ref.id()); Result error=reference(row,ref,caller,tick,true);
        if(error!=null) return error;
        OwnerFacts facts=facts(row.owner(),caller);
        if(!LogAcquisition.is(bound.request().capability()) || !row.resource().logBlocks()
                || !row.resource().item().equals(LogAcquisition.item(bound.request().capability()).id())
                || !((ActorValue)bound.request().arguments().get("actor")).value().equals(facts.worker())
                || !run.runId().equals(facts.run())
                || !((AreaValue)bound.request().arguments().get("source")).value().equals(row.resource().area()))
            return result(Code.REJECTED,Reason.REQUEST_INVALID);
        Long prior=row.consumed().get(operation);
        if(prior!=null) return result(prior==1 ? Code.VALID : Code.REJECTED,prior==1 ? null : Reason.REQUEST_INVALID);
        if(row.remaining()<1 || row.consumed().size()>=64) return result(Code.REJECTED,Reason.BUDGET_EXHAUSTED);
        var consumed=new LinkedHashMap<>(row.consumed()); consumed.put(operation,1L);
        Lease next=new Lease(row.id(),row.group(),row.epoch(),row.generation(),row.owner(),row.origin(),row.resource(),
                row.quantity(),row.remaining()-1,row.identity(),row.granted(),row.renewed(),row.expires(),row.renewals(),row.state(),null,consumed);
        List<Lease> rows=new ArrayList<>(state.leases()); replace(rows,next);
        if(!publish(rows,state.generation())) return unavailable();
        return new Result(Code.PENDING,null,row.group(),List.of(ref),0,0);
    }
    /** A witnessed recipe input leaves source stock once; this never creates prospective output. */
    public Result consumedIngredient(Ref ref,ValidatedRequest bound,RunCorrelation run,ResourceDelivery.Receipt receipt,TrustedContext caller) {
        checkThread();long tick=now();if(!ready())return unavailable();
        if(ref==null || bound==null || run==null || receipt==null || !bound.context().equals(caller))
            return result(Code.REJECTED,Reason.REQUEST_INVALID);
        Lease row=find(ref.id());Result error=reference(row,ref,caller,tick,true);if(error!=null)return error;
        var owner=facts(row.owner(),caller);
        boolean supported=CraftingTablePlacement.is(bound.request().capability())
                ? CraftingTablePlacement.matches(bound,run.runId(),receipt) && receipt.quantity()==1
                : RecipeProduction.is(bound.request().capability()) && TransformationDelivery.matches(bound,run.runId(),receipt)
                    && receipt.quantity()==RecipeProduction.of(bound.request().capability()).withdrawalUnits(receipt.item());
        if(!supported || receipt.stage()!=ResourceDelivery.Stage.WITHDRAW
                || !receipt.actor().equals(owner.worker()) || !run.runId().equals(owner.run())
                || row.resource().kind()!=Kind.STOCK || row.resource().slot()!=-1
                || !row.resource().item().equals(receipt.item().id()) || !row.resource().area().equals(receipt.source()))
            return result(Code.REJECTED,Reason.REQUEST_INVALID);
        UUID operation=receipt.operationId();Long previous=row.consumed().get(operation);
        if(previous!=null)return result(previous==receipt.quantity() ? Code.VALID : Code.REJECTED,previous==receipt.quantity() ? null : Reason.REQUEST_INVALID);
        if(row.remaining()<receipt.quantity() || row.consumed().size()>=64)return result(Code.REJECTED,Reason.BUDGET_EXHAUSTED);
        var spent=new LinkedHashMap<>(row.consumed());spent.put(operation,receipt.quantity());
        var next=new Lease(row.id(),row.group(),row.epoch(),row.generation(),row.owner(),row.origin(),row.resource(),
                row.quantity(),row.remaining()-receipt.quantity(),row.identity(),row.granted(),row.renewed(),row.expires(),row.renewals(),row.state(),null,spent);
        var rows=new ArrayList<>(state.leases());replace(rows,next);if(!publish(rows,state.generation()))return unavailable();
        return new Result(Code.PENDING,null,row.group(),List.of(ref),0,0);
    }
    public void tick() {
        checkThread();long tick=now();lastReconciled=0;
        reapFair(tick);
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
            if(row.state()!=State.ACTIVE || stopPending(row))continue;
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
        return new Roots(ids,!fenced && (history==null || history.ready()));
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
            case STOCK -> a.slot()==b.slot() && (a.slot()==-2 || a.item().equals(b.item()));
        };
    }
    private boolean live(Lease l,long tick) {
        return l.state()==State.ACTIVE && l.epoch().equals(epoch) && (tick<l.expires() || stopPending(l));
    }
    private boolean stopPending(Lease row) {
        try { return row.epoch().equals(epoch) && owners.stopPending(row.owner(),row.origin()); }
        catch(RuntimeException uncertain) { return true; }
    }
    private Observation observe(Resource resource,OwnerFacts facts,long tick) {
        try {
            Observation seen=observer.observe(resource,facts);
            if(seen!=null && seen.resource().equals(resource))return seen;
        }catch(RuntimeException unavailable){
            if(Boolean.getBoolean("cognitivecraft.gametest.trace"))System.out.println("CognitiveCraft LEASE_OBSERVATION_FAILED resource="+resource+" error="+unavailable);
        }
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
    private Lease find(UUID id) {
        Lease hot=state.leases().stream().filter(l->l.id().equals(id)).findFirst().orElse(null);
        return hot!=null?hot:history!=null && history.ready()?history.lookup(id):null;
    }
    private Result reference(Lease row,Ref ref,TrustedContext caller,long tick,boolean active) {
        if(row==null)return result(Code.STALE,Reason.INTERRUPTED);
        OwnerFacts facts=facts(row.owner(),caller);
        // Revocation denies use, but the exact original holder must still dispose of its own claim.
        if(facts==null && (active || !row.origin().equals(caller)))return result(Code.REJECTED,Reason.AUTHORITY_DENIED);
        if(!row.epoch().equals(epoch) || !ref.epoch().equals(row.epoch()) || ref.generation()!=row.generation())
            return result(Code.STALE,Reason.INTERRUPTED);
        if(active && (!facts.live() || tick>=row.expires() || !live(row,tick)))
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
