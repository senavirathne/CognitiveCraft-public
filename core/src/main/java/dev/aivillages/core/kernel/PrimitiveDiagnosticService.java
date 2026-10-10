package dev.aivillages.core.kernel;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static dev.aivillages.core.kernel.PrimitiveDiagnostics.*;
import static dev.aivillages.core.kernel.PrimitiveDiagnosticCodec.*;

/** One bounded observational service and dedicated daemon per server session. */
public final class PrimitiveDiagnosticService implements Sink,AutoCloseable {
    public enum State { OFF, OPENING, REPLAYING, READY, MEMORY_ONLY, DISABLED, CLOSED }
    public record Status(State state,int queued,int aggregateCount,long reservedWorkingBytes,
            Map<Counter,Long> counters,int maxDequeued,int maxInspected,int maxWritten,int maxWriteBytes,
            int maxQueued,long persistedSnapshots,PrimitiveDiagnosticStorePort.Usage storage,
            boolean potentiallyIncomplete,int dedupCount,int maxRecordBytes) { }
    @FunctionalInterface interface Factory { PrimitiveDiagnosticStorePort open() throws IOException; }
    private record Envelope(Observation observation,String eventId) { }
    private static final class Aggregate {
        Snapshot row;long dirtyOrder;
        Aggregate(Snapshot row,long order){this.row=row;this.dirtyOrder=order;}
    }
    private final Policy policy;
    private final Clock clock;
    private final LongSupplier nanos;
    private final Factory factory;
    private final Consumer<String> warning;
    private final UUID session;
    private final AtomicLongArray counters=new AtomicLongArray(Counter.values().length);
    private final AtomicInteger queued=new AtomicInteger(),tickOffers=new AtomicInteger();
    private final AtomicInteger highQueue=new AtomicInteger();
    private final AtomicLong sequence=new AtomicLong();
    private final AtomicBoolean closeRequested=new AtomicBoolean();
    private final ConcurrentLinkedQueue<Envelope> queue=new ConcurrentLinkedQueue<>();
    private final Map<String,Aggregate> aggregates=new HashMap<>();
    private final LinkedHashSet<String> dedup=new LinkedHashSet<>();
    private final CompletableFuture<Void> closure=new CompletableFuture<>();
    private final Thread worker;
    private volatile boolean accepting,closing;
    private volatile State state;
    private volatile Status status;
    private volatile List<Snapshot> published=List.of();
    private PrimitiveDiagnosticStorePort store;
    private boolean persistence=true,ready;
    private byte[] salt;
    private long startNanos,startUtc,effective,lastCycleNanos=Long.MIN_VALUE,dirtySequence,lastWarning=Long.MIN_VALUE;
    private long closeDeadline=Long.MAX_VALUE,persisted;
    private int aggregateCursor,maxDequeue,maxInspect,maxWritten,maxBytes,maxQueued,drained,drainedBytes,maxRecordBytes;
    private boolean maintenanceServiceTurn;

    public static PrimitiveDiagnosticService open(Path world,Policy policy,Clock clock,Consumer<String> warning) {
        return open(world,policy,clock,warning,PrimitiveDiagnosticStore.Faults.none());
    }
    public static PrimitiveDiagnosticService open(Path world,Policy policy,Clock clock,Consumer<String> warning,
            PrimitiveDiagnosticStore.Faults faults) {
        return new PrimitiveDiagnosticService(policy,clock,System::nanoTime,
                ()->PrimitiveDiagnosticStore.open(world,policy,faults),warning,UUID.randomUUID(),true);
    }
    PrimitiveDiagnosticService(Policy policy,Clock clock,LongSupplier nanos,Factory factory,
            Consumer<String> warning,UUID session,boolean runWorker) {
        this.policy=Objects.requireNonNull(policy);this.clock=Objects.requireNonNull(clock);
        this.nanos=Objects.requireNonNull(nanos);this.factory=Objects.requireNonNull(factory);
        this.warning=Objects.requireNonNull(warning);this.session=Objects.requireNonNull(session);
        state=policy.enabled()?State.OPENING:State.OFF;
        publish();
        if(policy.enabled()&&runWorker) {
            worker=new Thread(this::run,"cognitivecraft-primitive-diagnostics");
            worker.setDaemon(true);worker.start();
        } else { worker=null;if(!policy.enabled())closure.complete(null); }
    }
    /** Global per-session intake reset. No I/O, hashing, future wait or worker-held monitor. */
    public void beginTick() { tickOffers.set(0); }
    @Override public Offer offer(Observation observation) {
        if(!policy.enabled())return Offer.DISABLED;
        if(!accepting || closing){increment(Counter.NOT_READY);return Offer.UNAVAILABLE;}
        if(!bounded(observation)){increment(Counter.INVALID);return Offer.UNAVAILABLE;}
        if(!reserve(tickOffers,policy.limit(Limit.OFFERS_PER_TICK))){increment(Counter.TICK_DROP);return Offer.FULL;}
        if(!reserve(queued,policy.limit(Limit.QUEUE))){increment(Counter.QUEUE_DROP);return Offer.FULL;}
        long seq=nextSequence();
        if(seq==0){queued.decrementAndGet();increment(Counter.SATURATED);return Offer.UNAVAILABLE;}
        queue.offer(new Envelope(observation,session+":"+seq));increment(Counter.ACCEPTED);
        highQueue.accumulateAndGet(queued.get(),Math::max);
        return Offer.ACCEPTED;
    }
    private static boolean bounded(Observation o) {
        return o!=null && o.requestShape().length()<=2048
                && o.reference().diagnostic().path().length()<=256;
    }
    private static boolean reserve(AtomicInteger counter,int maximum) {
        int value=counter.get();
        while(value<maximum){if(counter.compareAndSet(value,value+1))return true;value=counter.get();}
        return false;
    }
    private long nextSequence() {
        long current=sequence.get();
        while(current<Long.MAX_VALUE){
            if(sequence.compareAndSet(current,current+1))return current+1;current=sequence.get();
        }
        accepting=false;return 0;
    }
    @Override public void catalogUnavailable(){increment(Counter.CATALOG_OMITTED);}
    private void increment(Counter counter){add(counter,1);}
    private void add(Counter counter,long amount) {
        if(amount<0)throw new IllegalArgumentException("Counter amount");
        int index=counter.ordinal();long old=counters.get(index);
        while(old<Long.MAX_VALUE) {
            long next=amount>Long.MAX_VALUE-old?Long.MAX_VALUE:old+amount;
            if(counters.compareAndSet(index,old,next))return;old=counters.get(index);
        }
    }
    private Map<Counter,Long> counts() {
        var result=new EnumMap<Counter,Long>(Counter.class);
        for(Counter c:Counter.values())result.put(c,counters.get(c.ordinal()));
        return Map.copyOf(result);
    }
    private void run() {
        try {
            while(!closing || !closure.isDone()) {
                cycle();
                if(state==State.CLOSED||state==State.DISABLED)break;
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1000));
                Thread.interrupted();
            }
        } finally { finishClose(); }
    }
    /** Deterministic worker seam; exactly the same cycle is used by the dedicated thread. */
    void cycle() {
        if(state==State.DISABLED&&closing){finishClose();return;}
        if(!policy.enabled() || state==State.CLOSED || state==State.DISABLED)return;
        long mono=nanos.getAsLong();
        if(lastCycleNanos!=Long.MIN_VALUE && mono-lastCycleNanos<1_000_000_000L && !closing)return;
        lastCycleNanos=mono;
        try {
            if(store==null) {
                store=factory.open();salt=store.salt();
                if(salt.length!=32)throw new IOException("Diagnostic salt unavailable");
                startNanos=mono;startUtc=clock.millis();effective=startUtc;
                if(startUtc<0)throw new ArithmeticException();
                state=State.REPLAYING;
            }
            long elapsed=Math.max(0,(mono-startNanos)/1_000_000L);
            effective=Math.max(effective,Math.max(clock.millis(),Math.addExact(startUtc,elapsed)));
            if(!ready) {
                var batch=store.replay(Math.min(32,policy.limit(Limit.MAINTENANCE)),
                        Math.min(131072,policy.limit(Limit.WRITE_BYTES)));
                maxInspect=Math.max(maxInspect,batch.inspected());
                add(Counter.REPLAY_INVALID,batch.invalid());
                for(Snapshot row:batch.rows()) {
                    if(row.lastSeen()>Math.addExact(clock.millis(),300000L)) {
                        increment(Counter.CLOCK_ANOMALY);disable();return;
                    }
                    effective=Math.max(effective,row.lastSeen());
                    if(expired(row.bucketStart()))continue;
                    if(encode(row,policy.limit(Limit.RECORD_BYTES))==null){increment(Counter.OVERSIZE);continue;}
                    Aggregate old=aggregates.get(row.aggregateId());
                    if(old==null) {
                        if(aggregates.size()<policy.limit(Limit.AGGREGATES))aggregates.put(row.aggregateId(),new Aggregate(row,0));
                        else increment(Counter.AGGREGATE_DROP);
                    } else if(row.revision()>old.row.revision())old.row=row;
                }
                if(!batch.complete()){publish();return;}
                startUtc=effective;startNanos=mono;ready=true;accepting=!closing;state=State.READY;
                // The final replay slice owns this cycle's inspection budget as well.
                // Normal maintenance and queued work begin on the following cycle.
                if(!closing){publish();return;}
            }
            int inspected=maintain();
            int dequeued=0,writeCount=0,writeBytes=0;
            int allowance=policy.limit(Limit.DEQUEUE);
            if(closing)allowance=Math.min(allowance,policy.limit(Limit.DRAIN_EVENTS)-drained);
            while(dequeued<allowance && (!closing || mono<closeDeadline)) {
                Envelope e=queue.poll();if(e==null)break;queued.decrementAndGet();dequeued++;
                if(closing) {
                    if(drainedBytes+8192>policy.limit(Limit.DRAIN_BYTES)){increment(Counter.SHUTDOWN_DROP);break;}
                    drained++;drainedBytes+=8192;
                }
                var row=initial(e.observation(),e.eventId(),effective,salt,policy,counts());
                if(expired(row.bucketStart())){increment(Counter.AGGREGATE_DROP);continue;}
                if(encode(row,policy.limit(Limit.RECORD_BYTES))==null){increment(Counter.OVERSIZE);continue;}
                String eventKey=row.representative().attemptRef()+row.representative().generationRef();
                if(dedup.contains(eventKey)){increment(Counter.DUPLICATE);continue;}
                dedup.add(eventKey);
                if(dedup.size()>policy.limit(Limit.DEDUP))dedup.remove(dedup.iterator().next());
                Aggregate aggregate=aggregates.get(row.aggregateId());
                if(aggregate==null) {
                    if(aggregates.size()>=policy.limit(Limit.AGGREGATES)){increment(Counter.AGGREGATE_DROP);continue;}
                    row=new Snapshot(row.aggregateId(),row.revision(),row.occurrences(),row.firstSeen(),row.lastSeen(),
                            row.bucketStart(),row.worldRef(),row.scopeRef(),row.principalRef(),row.capability(),
                            row.catalog(),row.primitive(),row.fingerprint(),row.latestEventId(),row.representative(),
                            row.saturated(),true,counts());
                    aggregate=new Aggregate(row,0);aggregates.put(row.aggregateId(),aggregate);
                } else {
                    if(aggregate.row.revision()==Long.MAX_VALUE){increment(Counter.SATURATED);continue;}
                    Snapshot next=aggregate.row.next(e.eventId(),effective,counts());
                    if(encode(next,policy.limit(Limit.RECORD_BYTES))==null){increment(Counter.OVERSIZE);continue;}
                    aggregate.row=next;
                }
                if(aggregate.dirtyOrder==0) {
                    if(dirtySequence==Long.MAX_VALUE){increment(Counter.SATURATED);accepting=false;continue;}
                    aggregate.dirtyOrder=++dirtySequence;
                }
            }
            if(persistence && (!closing || nanos.getAsLong()<closeDeadline)) {
                List<Aggregate> dirty=aggregates.values().stream().filter(a->a.dirtyOrder>0)
                        .sorted(Comparator.comparingLong((Aggregate a)->a.dirtyOrder).thenComparing(a->a.row.aggregateId())).toList();
                for(Aggregate a:dirty) {
                    if(writeCount>=policy.limit(Limit.WRITES))break;
                    byte[] bytes=encode(a.row,policy.limit(Limit.RECORD_BYTES));
                    if(bytes==null){increment(Counter.OVERSIZE);a.dirtyOrder=0;continue;}
                    maxRecordBytes=Math.max(maxRecordBytes,bytes.length);
                    if(writeBytes+bytes.length>policy.limit(Limit.WRITE_BYTES))break;
                    if(closing && nanos.getAsLong()>=closeDeadline)break;
                    try { store.append(bytes,a.row.bucketStart(),effective); }
                    catch(IOException failed) { storageFailure(failed);break; }
                    a.dirtyOrder=0;writeCount++;writeBytes+=bytes.length;
                    if(persisted<Long.MAX_VALUE)persisted++;
                }
            }
            maxDequeue=Math.max(maxDequeue,dequeued);maxInspect=Math.max(maxInspect,inspected);
            maxWritten=Math.max(maxWritten,writeCount);maxBytes=Math.max(maxBytes,writeBytes);
            if(closing){dropRemainder();finishClose();}else publish();
        } catch(PrimitiveDiagnosticStore.RetentionFailure failed) {
            storageFailure(failed);dropRemainder();disable();
        } catch(IOException | RuntimeException | AssertionError failed) {
            increment(Counter.IO_FAILURE);warn();dropRemainder();disable();
        }
    }
    private int maintain() throws IOException {
        int maximum=policy.limit(Limit.MAINTENANCE),inspected=0;
        List<String> keys=aggregates.keySet().stream().sorted().toList();
        int serviceAllowance=Math.min(maximum==1?(maintenanceServiceTurn?1:0):maximum/2,keys.size());
        if(maximum==1)maintenanceServiceTurn=!maintenanceServiceTurn;
        for(int i=0;i<serviceAllowance;i++) {
            if(keys.isEmpty())break;aggregateCursor%=keys.size();
            String key=keys.get(aggregateCursor++);inspected++;
            Aggregate a=aggregates.get(key);if(a!=null&&expired(a.row.bucketStart()))aggregates.remove(key);
        }
        if(persistence)inspected+=store.maintain(effective,maximum-inspected);
        return inspected;
    }
    private boolean expired(long bucket){return effective>=Math.addExact(bucket,policy.limit(Limit.RETENTION_MILLIS));}
    private void storageFailure(Throwable failed) {
        if(failed instanceof PrimitiveDiagnosticStore.RetentionFailure)increment(Counter.RETENTION_FAILURE);
        increment(Counter.IO_FAILURE);persistence=false;state=State.MEMORY_ONLY;warn();
    }
    private void warn() {
        long now=nanos.getAsLong();
        if(lastWarning==Long.MIN_VALUE || now-lastWarning>=60_000_000_000L) {
            lastWarning=now;
            try{warning.accept("CognitiveCraft primitive diagnostics unavailable; gameplay continues.");}
            catch(RuntimeException | AssertionError ignored){}
        }
    }
    private void disable(){accepting=false;state=State.DISABLED;aggregates.clear();publish();}
    private void publish() {
        published=aggregates.values().stream().map(a->a.row).filter(r->!expiredSafe(r.bucketStart()))
                .sorted(Comparator.comparing(Snapshot::aggregateId)).toList();
        int q=queued.get();maxQueued=Math.max(maxQueued,highQueue.get());
        status=new Status(state,q,published.size(),policy.workingReservationBytes(),counts(),
                maxDequeue,maxInspect,maxWritten,maxBytes,maxQueued,persisted,
                store==null?new PrimitiveDiagnosticStorePort.Usage(0,0,0,0,0,0,0):store.usage(),true,dedup.size(),maxRecordBytes);
    }
    private boolean expiredSafe(long bucket) {
        try{return expired(bucket);}catch(ArithmeticException overflow){return true;}
    }
    public Status status(){return status;}
    List<Snapshot> snapshot(){return published;}
    public CompletableFuture<Void> closeAsync() {
        if(!closeRequested.compareAndSet(false,true))return closure;
        accepting=false;
        try{closeDeadline=Math.addExact(nanos.getAsLong(),TimeUnit.MILLISECONDS.toNanos(policy.limit(Limit.DRAIN_MILLIS)));}
        catch(ArithmeticException overflow){closeDeadline=nanos.getAsLong();}
        closing=true;
        closure.completeOnTimeout(null,policy.limit(Limit.DRAIN_MILLIS),TimeUnit.MILLISECONDS);
        if(worker!=null)worker.interrupt();
        else if(!policy.enabled()||state==State.DISABLED)closure.complete(null);
        return closure;
    }
    private void dropRemainder() {
        int removed=0;while(queue.poll()!=null)removed++;
        if(removed>0){queued.addAndGet(-removed);add(Counter.SHUTDOWN_DROP,removed);}
    }
    private void finishClose() {
        accepting=false;
        try{dropRemainder();if(store!=null)store.close();}
        catch(IOException | RuntimeException failed){increment(Counter.IO_FAILURE);}
        finally {
            if(salt!=null)Arrays.fill(salt,(byte)0);
            if(state!=State.OFF&&state!=State.DISABLED)state=State.CLOSED;
            publish();closure.complete(null);
        }
    }
    @Override public void close(){closeAsync();}
    // Numeric exhaustion seams remain package-private; they grant no runtime authority.
    void sequenceForTest(long value){sequence.set(value);}
    void counterForTest(Counter counter,long value){if(value<0)throw new IllegalArgumentException();counters.set(counter.ordinal(),value);}
}
