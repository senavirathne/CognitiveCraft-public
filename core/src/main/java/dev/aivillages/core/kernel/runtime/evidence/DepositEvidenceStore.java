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
    public enum Code { PENDING, PREPARED, RESULT_ACKNOWLEDGED, RECOVERY_REQUIRED, STALE, FULL, FENCED }
    public record Status(Code code,Prepared prepared) { }
    /** This capability is specific to its exact owner/process/intent; no public constructor. */
    public static final class Prepared {
        private final DepositEvidenceStore owner;private final UUID process;private final Intent intent;
        private Prepared(DepositEvidenceStore owner,Intent intent){this.owner=owner;process=owner.process;this.intent=intent;}
        public Intent intent(){return intent;}
    }
    public static final class Reconciled {
        private final DepositEvidenceStore owner;private final Entry entry;private final Reconciliation result;
        private Reconciled(DepositEvidenceStore owner,Entry entry,Reconciliation result){this.owner=owner;this.entry=entry;this.result=result;}
        public Entry entry(){return entry;}public Reconciliation result(){return result;}
        public UUID process(){return owner.process;}
    }
    private record Written(Snapshot snapshot,Throwable failure,long at) { }
    private final Thread thread=Thread.currentThread();
    private final UUID process=UUID.randomUUID();
    private final Storage storage;private final NativePort nativePort;private final Clock clock;private final long timeoutMillis;
    private final ConcurrentLinkedQueue<Written> completed=new ConcurrentLinkedQueue<>();
    private final Map<String,Prepared> created=new HashMap<>();private final Set<String> started=new HashSet<>();
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
    public void tick() {
        thread();Written value;
        while((value=completed.poll())!=null) {
            if(pending==null || value.failure!=null || value.snapshot!=pending || value.at>deadline){fenced=true;continue;}
            if(!fenced){state=value.snapshot;pending=null;}
        }
        if(pending!=null && clock.millis()>deadline)fenced=true;
    }
    public Status prepare(Intent intent) {
        thread();Objects.requireNonNull(intent);if(fenced)return status(Code.FENCED,null);
        Entry existing=find(state,intent.key().token());
        if(existing!=null) {
            if(!existing.intent().equals(intent))return status(Code.STALE,null);
            Prepared prepared=created.get(existing.token());
            if(prepared==null)return status(Code.RECOVERY_REQUIRED,null);
            return status(existing.result()!=null?Code.RESULT_ACKNOWLEDGED:Code.PREPARED,prepared);
        }
        if(pending!=null)return status(Code.PENDING,null);
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
        if(entry.result()!=null)return status(Code.RESULT_ACKNOWLEDGED,prepared);
        if(pending!=null || started.contains(entry.token()))return status(Code.PENDING,prepared);
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
        thread();Entry entry=find(state,key.token());if(entry==null || !entry.intent().key().equals(key))return null;
        Witness actor=nativePort.restored(entry.intent().actor()),chest=nativePort.restored(entry.intent().chest());
        return new Reconciled(this,entry,fenced?Reconciliation.UNPROVEN:classify(entry,actor,chest));
    }
    /** Includes unacknowledged snapshots; neither timeout nor lease expiry erases an exclusion. */
    public Set<UUID> protectedJobs() {
        thread();var ids=new HashSet<UUID>();for(Snapshot snapshot:pending==null?List.of(state):List.of(state,pending))
            snapshot.entries().forEach(e->ids.add(e.intent().key().root()));return Set.copyOf(ids);
    }
    public boolean excludes(Participant participant,UUID run) {
        thread();for(Snapshot snapshot:pending==null?List.of(state):List.of(state,pending))for(Entry entry:snapshot.entries())
            if(!entry.intent().run().equals(run) && (sameAddress(entry.intent().actor(),participant) || sameAddress(entry.intent().chest(),participant)))return true;
        return false;
    }
    private static boolean sameAddress(Participant a,Participant b){return a.kind()==b.kind() && a.dimension().equals(b.dimension()) && a.address().equals(b.address());}
    private static Entry find(Snapshot snapshot,String token){return snapshot.entries().stream().filter(e->e.token().equals(token)).findFirst().orElse(null);}
    private Status status(Code code,Prepared prepared){return new Status(code,prepared);}
    private void publish(Snapshot next) {
        if(pending!=null || fenced)throw new IllegalStateException("Deposit publication already in flight");
        Snapshot expected=state;pending=next;deadline=Math.addExact(clock.millis(),timeoutMillis);
        try{storage.replace(expected,next).whenComplete((written,error)->completed.add(new Written(written,error,clock.millis())));}
        catch(RuntimeException unavailable){fenced=true;throw unavailable;}
    }
}
