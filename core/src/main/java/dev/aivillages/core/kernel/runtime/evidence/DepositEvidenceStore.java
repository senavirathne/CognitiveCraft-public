package dev.aivillages.core.kernel.runtime.evidence;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import static dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;
import static dev.aivillages.core.kernel.runtime.evidence.DepositJournal.Snapshot;

/** Main-thread intent owner; the registered native port is the only effect caller. */
public final class DepositEvidenceStore {
    @FunctionalInterface public interface Storage {CompletionStage<Snapshot> replace(Snapshot expected,Snapshot next);}
    public interface NativePort {
        boolean current(Intent intent);
        Result execute(Intent intent);
        Witness restored(Participant participant);
    }
    public enum Code { PENDING, PREPARED, RESULT_ACKNOWLEDGED, RESOLVED, RECOVERY_REQUIRED, STALE, FULL, FENCED }
    public record Status(Code code,Prepared prepared) { }
    /** This capability is specific to its exact owner/process/intent; no public constructor. */
    public static final class Prepared {
        private final DepositEvidenceStore owner;private final UUID process;private final Intent intent;
        private Prepared(DepositEvidenceStore owner,Intent intent){this.owner=owner;process=owner.process;this.intent=intent;}
        public Intent intent(){return intent;}
    }
    public static final class Reconciled {
        private final DepositEvidenceStore owner;private final Entry entry;private final Reconciliation result;private final Witness actor,chest;
        private Reconciled(DepositEvidenceStore owner,Entry entry,Reconciliation result,Witness actor,Witness chest){this.owner=owner;this.entry=entry;this.result=result;this.actor=actor;this.chest=chest;}
        public Entry entry(){return entry;}public Reconciliation result(){return result;}
        public UUID process(){return owner.process;}
    }
    private record Written(Snapshot snapshot,Throwable failure,long at,long elapsedNanos) { }
    private final Thread thread=Thread.currentThread();
    private final UUID process=UUID.randomUUID();
    private final Storage storage;private final NativePort nativePort;private final Clock clock;private final long timeoutMillis;
    private final ConcurrentLinkedQueue<Written> completed=new ConcurrentLinkedQueue<>();
    private final Map<String,Prepared> created=new HashMap<>();private final Set<String> started=new HashSet<>();
    private final java.util.ArrayDeque<Long> acknowledgementNanos=new java.util.ArrayDeque<>();
    private Snapshot state,pending;private long deadline;private boolean fenced;
    public DepositEvidenceStore(Snapshot initial,Storage storage,NativePort nativePort,Clock clock,long timeoutMillis,boolean readOnly) {
        state=Objects.requireNonNull(initial);this.storage=Objects.requireNonNull(storage);this.nativePort=Objects.requireNonNull(nativePort);this.clock=Objects.requireNonNull(clock);
        if(timeoutMillis<1 || timeoutMillis>30_000)throw new IllegalArgumentException("Deposit acknowledgement deadline");
        this.timeoutMillis=timeoutMillis;fenced=readOnly;
    }
    private void thread(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Deposit game-thread owner");}
    public Snapshot snapshot(){thread();return state;}
    public boolean fenced(){thread();return fenced;}
    public boolean pending(){thread();return pending!=null;}
    public UUID process(){return process;}
    public List<Long> acknowledgementNanos(){thread();return List.copyOf(acknowledgementNanos);}
    public void tick() {
        thread();Written value;
        while((value=completed.poll())!=null) {
            if(pending==null || value.failure!=null || value.snapshot!=pending || value.at>deadline){fenced=true;continue;}
            if(!fenced){state=value.snapshot;pending=null;
                if(acknowledgementNanos.size()==192)acknowledgementNanos.removeFirst();acknowledgementNanos.addLast(value.elapsedNanos);}
        }
        if(pending!=null && clock.millis()>deadline)fenced=true;
    }
    public Status prepare(Intent intent) {
        thread();Objects.requireNonNull(intent);if(fenced)return status(Code.FENCED,null);
        Entry existing=find(state,intent.key().token());
        if(existing!=null) {
            if(!existing.intent().equals(intent))return status(Code.STALE,null);
            if(existing.verification()!=null)return status(Code.RESOLVED,null);
            Prepared prepared=created.get(existing.token());
            if(prepared==null)return status(Code.RECOVERY_REQUIRED,null);
            return status(existing.result()!=null?Code.RESULT_ACKNOWLEDGED:Code.PREPARED,prepared);
        }
        if(pending!=null)return status(Code.PENDING,null);
        if(clock.millis()>intent.conditions().actionDeadlineEpochMillis())return status(Code.STALE,null);
        for(Entry prior:state.entries())if(prior.verification()==null && (sameAddress(prior.intent().actor(),intent.actor()) || sameAddress(prior.intent().chest(),intent.chest())))
            return status(Code.RECOVERY_REQUIRED,null);
        if(state.entries().size()==MAX_ENTRIES)return status(Code.FULL,null);
        // Validate the complete bounded record before reserving its worst-case result.
        Entry entry=new Entry(intent,null);NativeDeposit.encode(entry);
        var entries=new ArrayList<>(state.entries());entries.add(entry);
        Snapshot next;
        try{next=new Snapshot(state.world(),state.epoch(),Math.addExact(state.revision(),1),entries);}
        catch(IllegalArgumentException collision){return status(Code.STALE,null);}
        Prepared prepared=new Prepared(this,intent);created.put(entry.token(),prepared);publish(next);return status(Code.PENDING,null);
    }
    public Status perform(Prepared prepared) {
        thread();
        if(prepared==null || prepared.owner!=this || !prepared.process.equals(process))return status(Code.STALE,null);
        if(fenced)return status(Code.FENCED,null);
        Entry entry=find(state,prepared.intent.key().token());
        if(entry==null || !entry.intent().equals(prepared.intent) || created.get(entry.token())!=prepared)return status(Code.STALE,null);
        if(entry.verification()!=null)return status(Code.RESOLVED,null);
        if(entry.result()!=null)return status(Code.RESULT_ACKNOWLEDGED,prepared);
        if(pending!=null || started.contains(entry.token()))return status(Code.PENDING,prepared);
        if(clock.millis()>entry.intent().conditions().actionDeadlineEpochMillis())return status(Code.STALE,prepared);
        if(!nativePort.current(entry.intent()))return status(Code.STALE,prepared);
        started.add(entry.token());
        try {
            Result result=Objects.requireNonNull(nativePort.execute(entry.intent()),"Native result unavailable");
            Entry observed=new Entry(entry.intent(),result);NativeDeposit.encode(observed);
            var entries=new ArrayList<>(state.entries());entries.set(entries.indexOf(entry),observed);
            publish(new Snapshot(state.world(),state.epoch(),Math.addExact(state.revision(),1),entries));
            return status(Code.PENDING,prepared);
        }catch(RuntimeException unknown) {
            // The original PREPARED intent and participant exclusion survive. No second native call.
            fenced=true;throw unknown;
        }
    }
    public Reconciled reconcile(Key key) {
        thread();Entry entry=find(state,key.token());if(entry==null && pending!=null)entry=find(pending,key.token());
        if(entry==null || !entry.intent().key().equals(key))return null;
        if(fenced || pending!=null)return new Reconciled(this,entry,Reconciliation.UNPROVEN,null,null);
        if(entry.verification()!=null)return new Reconciled(this,entry,entry.verification().outcome(),null,null);
        Witness actor=nativePort.restored(entry.intent().actor()),chest=nativePort.restored(entry.intent().chest());
        return new Reconciled(this,entry,fenced?Reconciliation.UNPROVEN:classify(entry,actor,chest),actor,chest);
    }
    /** Persist positive saved evidence before releasing its scoped exclusion. No physical replay. */
    public Code verify(Reconciled proof) {
        thread();if(fenced)return Code.FENCED;if(proof==null || proof.owner!=this)return Code.STALE;
        Entry current=find(state,proof.entry.token());if(current==null || !current.intent().equals(proof.entry.intent()) || !Objects.equals(current.result(),proof.entry.result()))return Code.STALE;
        if(current.verification()!=null)return current.verification().outcome()==proof.result?Code.RESOLVED:Code.STALE;
        if(current!=proof.entry)return Code.STALE;if(pending!=null)return Code.PENDING;
        if(!Set.of(Reconciliation.PREPARED_NO_EFFECT,Reconciliation.SAVED_COMPLETE,Reconciliation.SAVED_NO_EFFECT).contains(proof.result)
                || !Objects.equals(proof.actor,nativePort.restored(current.intent().actor()))
                || !Objects.equals(proof.chest,nativePort.restored(current.intent().chest())))return Code.STALE;
        var verification=new Verification(proof.result,proof.actor.participant(),proof.chest.participant(),proof.actor.marker(),proof.chest.marker(),process,clock.millis());
        Entry resolved=new Entry(current.intent(),current.result(),verification);NativeDeposit.encode(resolved);
        var entries=new ArrayList<>(state.entries());entries.set(entries.indexOf(current),resolved);
        publish(new Snapshot(state.world(),state.epoch(),Math.addExact(state.revision(),1),entries));return Code.PENDING;
    }
    /** Includes unacknowledged snapshots; neither timeout nor lease expiry erases an exclusion. */
    public Set<UUID> protectedJobs() {
        thread();var ids=new HashSet<UUID>();for(Snapshot snapshot:pending==null?List.of(state):List.of(state,pending))
            snapshot.entries().stream().filter(e->e.verification()==null).forEach(e->{ids.add(e.intent().key().root());ids.add(e.intent().key().step());});return Set.copyOf(ids);
    }
    public boolean excludes(Participant participant,UUID run) {
        thread();for(Snapshot snapshot:pending==null?List.of(state):List.of(state,pending))for(Entry entry:snapshot.entries())
            if(entry.verification()==null && !entry.intent().run().equals(run) && (sameAddress(entry.intent().actor(),participant) || sameAddress(entry.intent().chest(),participant)))return true;
        return false;
    }
    public boolean conflicts(Kind kind,String dimension,String address,String allowedEffect) {
        thread();for(Snapshot snapshot:pending==null?List.of(state):List.of(state,pending))for(Entry entry:snapshot.entries()) {
            if(entry.verification()!=null || entry.token().equals(allowedEffect))continue;
            for(Participant participant:List.of(entry.intent().actor(),entry.intent().chest()))
                if(participant.kind()==kind && participant.dimension().equals(dimension) && participant.address().equals(address))return true;
        }
        return false;
    }
    private static boolean sameAddress(Participant a,Participant b){return a.kind()==b.kind() && a.dimension().equals(b.dimension()) && a.address().equals(b.address());}
    private static Entry find(Snapshot snapshot,String token){return snapshot.entries().stream().filter(e->e.token().equals(token)).findFirst().orElse(null);}
    private Status status(Code code,Prepared prepared){return new Status(code,prepared);}
    private void publish(Snapshot next) {
        if(pending!=null || fenced)throw new IllegalStateException("Deposit publication already in flight");
        Snapshot expected=state;pending=next;deadline=Math.addExact(clock.millis(),timeoutMillis);
        long startedNanos=System.nanoTime();
        try{storage.replace(expected,next).whenComplete((written,error)->completed.add(new Written(written,error,clock.millis(),System.nanoTime()-startedNanos)) );}
        catch(RuntimeException unavailable){fenced=true;throw unavailable;}
    }
}
