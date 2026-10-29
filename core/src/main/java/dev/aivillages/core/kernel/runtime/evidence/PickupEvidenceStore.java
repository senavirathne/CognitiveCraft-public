package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.Contracts.ActorRef;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import static dev.aivillages.core.kernel.runtime.evidence.NativePickup.*;
import static dev.aivillages.core.kernel.runtime.evidence.PickupJournal.Snapshot;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.Key;

/**
 * Pickup intent/result/verification owner. A live original H acknowledgement is necessary,
 * never sufficient: only the registered native port checks current Jobs/claims/custody and executes.
 * This component is not a scheduler and is not yet installed as the Fabric pickup producer.
 */
public final class PickupEvidenceStore {
    public static final int MAX_INTENT_BYTES=8192;
    @FunctionalInterface public interface Storage {CompletionStage<Snapshot> replace(Snapshot expected,Snapshot next);}
    public interface NativePort {
        boolean current(Intent intent);
        Result execute(Intent intent);
        Observation restored(Entry entry);
    }
    public enum Code {PENDING,PREPARED,RESULT_ACKNOWLEDGED,RESOLVED,RECOVERY_REQUIRED,STALE,FULL,FENCED}
    public record Status(Code code,Prepared prepared){}
    public static final class Prepared {
        private final PickupEvidenceStore owner;private final UUID process;private final Intent intent;
        private final HarvestEvidenceStore.Acknowledged harvest;
        private Prepared(PickupEvidenceStore owner,Intent intent,HarvestEvidenceStore.Acknowledged harvest){this.owner=owner;process=owner.process;this.intent=intent;this.harvest=harvest;}
        public Intent intent(){return intent;}
    }
    /** Descriptive current-process result linkage for a future joined D producer, never effect authority. */
    public static final class Acknowledged {
        private final PickupEvidenceStore owner;private final UUID process;private final Entry entry;
        private Acknowledged(PickupEvidenceStore owner,Entry entry){this.owner=owner;process=owner.process;this.entry=entry;}
        public Entry entry(){return entry;}
    }
    public static final class Reconciled {
        private final PickupEvidenceStore owner;private final Entry entry;private final Observation observation;private final Reconciliation result;
        private Reconciled(PickupEvidenceStore owner,Entry entry,Observation observation,Reconciliation result){this.owner=owner;this.entry=entry;this.observation=observation;this.result=result;}
        public Entry entry(){return entry;}public Observation observation(){return observation;}public Reconciliation result(){return result;}
        public UUID process(){return owner.process;}
    }
    private record Written(Snapshot snapshot,Throwable failure,long at,long elapsedNanos){}
    private final Thread thread=Thread.currentThread();private final UUID process=UUID.randomUUID();
    private final HarvestEvidenceStore harvest;private final Storage storage;private final NativePort port;private final Clock clock;private final long timeoutMillis;
    private final ConcurrentLinkedQueue<Written> completed=new ConcurrentLinkedQueue<>();
    private final Map<String,Prepared> created=new HashMap<>();private final Set<String> started=new HashSet<>();
    private final Map<String,Acknowledged> acknowledged=new HashMap<>();private final ArrayDeque<Long> acknowledgementNanos=new ArrayDeque<>();
    private Snapshot state,pending;private long deadline;private boolean fenced;
    public PickupEvidenceStore(Snapshot initial,HarvestEvidenceStore harvest,Storage storage,NativePort port,Clock clock,long timeoutMillis,boolean readOnly) {
        state=Objects.requireNonNull(initial);this.harvest=Objects.requireNonNull(harvest);this.storage=Objects.requireNonNull(storage);
        this.port=Objects.requireNonNull(port);this.clock=Objects.requireNonNull(clock);
        if(timeoutMillis<1||timeoutMillis>30_000)throw new IllegalArgumentException("Pickup acknowledgement deadline");
        if(!initial.world().equals(harvest.snapshot().world()))throw new IllegalArgumentException("Pickup/harvest world");
        this.timeoutMillis=timeoutMillis;fenced=readOnly;
    }
    private void thread(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Pickup game-thread owner");}
    public Snapshot snapshot(){thread();return state;}public boolean pending(){thread();return pending!=null;}public boolean fenced(){thread();return fenced;}
    public UUID process(){return process;}public List<Long> acknowledgementNanos(){thread();return List.copyOf(acknowledgementNanos);}
    public void tick() {
        thread();Written written;
        while((written=completed.poll())!=null) {
            if(pending==null||written.failure()!=null||written.snapshot()!=pending||written.at()>deadline){fenced=true;continue;}
            if(!fenced){state=written.snapshot();pending=null;if(acknowledgementNanos.size()==192)acknowledgementNanos.removeFirst();acknowledgementNanos.addLast(written.elapsedNanos());}
        }
        if(pending!=null&&clock.millis()>deadline)fenced=true;
    }
    private static Entry find(Snapshot snapshot,String token){return snapshot.entries().stream().filter(e->e.token().equals(token)).findFirst().orElse(null);}
    private boolean ancestry(Entry entry) {
        if(harvest.fenced()||harvest.pending())return false;
        return harvest.snapshot().entries().stream().anyMatch(h->h.equals(entry.intent().harvest()));
    }
    public Status prepare(Intent intent,HarvestEvidenceStore.Acknowledged original) {
        thread();Objects.requireNonNull(intent);if(fenced)return new Status(Code.FENCED,null);
        Entry existing=find(state,intent.key().token());
        if(existing!=null) {
            if(!existing.intent().equals(intent))return new Status(Code.STALE,null);
            if(!ancestry(existing))return new Status(Code.RECOVERY_REQUIRED,null);
            if(existing.verification()!=null)return new Status(Code.RESOLVED,null);
            Prepared prepared=created.get(existing.token());
            if(prepared==null)return new Status(Code.RECOVERY_REQUIRED,null);
            return new Status(existing.result()!=null?Code.RESULT_ACKNOWLEDGED:Code.PREPARED,prepared);
        }
        if(pending!=null)return new Status(Code.PENDING,null);
        if(!harvest.links(original,intent)||clock.millis()>intent.conditions().actionDeadlineEpochMillis())return new Status(Code.STALE,null);
        // The first native producer is an eight-slot villager. Reserve the full unchanged
        // 32-KiB row before PREPARED; oversized ancestors refuse before any physical call.
        if(intent.harvest().intent().actorBefore().slots().size()!=8)return new Status(Code.STALE,null);
        for(Entry prior:state.entries())if(prior.verification()==null &&
                (sameActor(prior.intent(),intent)||prior.intent().harvest().result().drops().getFirst().entity().equals(intent.harvest().result().drops().getFirst().entity())))
            return new Status(Code.RECOVERY_REQUIRED,null);
        if(state.entries().size()==MAX_ENTRIES)return new Status(Code.FULL,null);
        Entry entry=new Entry(intent,null);
        if(NativePickup.encode(entry).length>MAX_INTENT_BYTES)return new Status(Code.FULL,null);
        var entries=new ArrayList<>(state.entries());entries.add(entry);Snapshot next;
        try{next=new Snapshot(state.world(),state.epoch(),Math.addExact(state.revision(),1),entries);}
        catch(IllegalArgumentException collision){return new Status(Code.STALE,null);}
        var prepared=new Prepared(this,intent,original);created.put(entry.token(),prepared);publish(next);return new Status(Code.PENDING,null);
    }
    private static boolean sameActor(Intent a,Intent b) {
        ActorRef x=a.harvest().intent().actor(),y=b.harvest().intent().actor();return x.entityId().equals(y.entityId())&&x.dimension().equals(y.dimension());
    }
    public Status perform(Prepared prepared) {
        thread();if(prepared==null||prepared.owner!=this||!prepared.process.equals(process))return new Status(Code.STALE,null);
        if(fenced)return new Status(Code.FENCED,null);
        Entry entry=find(state,prepared.intent.key().token());
        if(entry==null||!entry.intent().equals(prepared.intent)||created.get(entry.token())!=prepared)return new Status(Code.STALE,null);
        if(entry.verification()!=null)return new Status(Code.RESOLVED,null);
        if(entry.result()!=null)return new Status(Code.RESULT_ACKNOWLEDGED,prepared);
        if(pending!=null||started.contains(entry.token()))return new Status(Code.PENDING,prepared);
        if(!harvest.links(prepared.harvest,entry.intent())||clock.millis()>entry.intent().conditions().actionDeadlineEpochMillis()
                ||!port.current(entry.intent()))return new Status(Code.STALE,prepared);
        started.add(entry.token());
        try {
            var observed=new Entry(entry.intent(),Objects.requireNonNull(port.execute(entry.intent())));NativePickup.encode(observed);
            var entries=new ArrayList<>(state.entries());entries.set(entries.indexOf(entry),observed);
            publish(new Snapshot(state.world(),state.epoch(),Math.addExact(state.revision(),1),entries));return new Status(Code.PENDING,prepared);
        }catch(RuntimeException unknown){fenced=true;throw unknown;}
    }
    public Acknowledged acknowledged(Key key) {
        thread();Objects.requireNonNull(key);if(fenced||pending!=null)return null;Entry entry=find(state,key.token());
        if(entry==null||!entry.intent().key().equals(key)||entry.verification()!=null||entry.result()==null
                ||entry.result().live()!=Live.COMPLETE||entry.result().receipt()==null||!created.containsKey(entry.token())||!ancestry(entry))return null;
        return acknowledged.computeIfAbsent(entry.token(),ignored->new Acknowledged(this,entry));
    }
    public boolean owns(Acknowledged proof) {
        thread();return proof!=null&&proof.owner==this&&proof.process.equals(process)&&!fenced&&pending==null
                &&find(state,proof.entry.token())==proof.entry&&proof.entry.verification()==null&&created.containsKey(proof.entry.token())&&ancestry(proof.entry);
    }
    public Reconciled reconcile(Key key) {
        thread();Objects.requireNonNull(key);Entry entry=find(state,key.token());if(entry==null&&pending!=null)entry=find(pending,key.token());
        if(entry==null||!entry.intent().key().equals(key))return null;
        if(fenced||pending!=null||!ancestry(entry))return new Reconciled(this,entry,null,Reconciliation.UNPROVEN);
        if(entry.verification()!=null)return new Reconciled(this,entry,null,entry.verification().outcome());
        Observation observation=port.restored(entry);return new Reconciled(this,entry,observation,NativePickup.classify(entry,observation));
    }
    /** Reobserve the whole native cohort and publish verification before releasing any retained owner. */
    public Code verify(Reconciled proof) {
        thread();if(fenced)return Code.FENCED;if(proof==null||proof.owner!=this)return Code.STALE;
        Entry current=find(state,proof.entry.token());
        if(current==null||!current.intent().equals(proof.entry.intent())||!Objects.equals(current.result(),proof.entry.result())||!ancestry(current))return Code.STALE;
        if(current.verification()!=null)return current.verification().outcome()==proof.result?Code.RESOLVED:Code.STALE;
        if(current!=proof.entry)return Code.STALE;if(pending!=null)return Code.PENDING;
        if(!Set.of(Reconciliation.SAVED_COMPLETE,Reconciliation.SAVED_NO_EFFECT).contains(proof.result)
                ||!Objects.equals(proof.observation,port.restored(current)))return Code.STALE;
        var verified=new Entry(current.intent(),current.result(),new Verification(proof.result,proof.observation,process,clock.millis()));NativePickup.encode(verified);
        var entries=new ArrayList<>(state.entries());entries.set(entries.indexOf(current),verified);
        publish(new Snapshot(state.world(),state.epoch(),Math.addExact(state.revision(),1),entries));return Code.PENDING;
    }
    public Set<UUID> protectedJobs() {
        thread();var jobs=new HashSet<UUID>();
        for(Snapshot snapshot:pending==null?List.of(state):List.of(state,pending))for(Entry entry:snapshot.entries())
            if(entry.verification()==null){jobs.add(entry.intent().key().root());jobs.add(entry.intent().key().step());}
        return Set.copyOf(jobs);
    }
    private void publish(Snapshot next) {
        if(pending!=null||fenced)throw new IllegalStateException("Pickup publication already in flight");
        Snapshot expected=state;pending=next;deadline=Math.addExact(clock.millis(),timeoutMillis);long began=System.nanoTime();
        try{storage.replace(expected,next).whenComplete((written,error)->completed.add(new Written(written,error,clock.millis(),System.nanoTime()-began)));}
        catch(RuntimeException unknown){fenced=true;throw unknown;}
    }
}
