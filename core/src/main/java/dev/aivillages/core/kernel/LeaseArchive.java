package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.runtime.persistence.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static dev.aivillages.core.kernel.ResourceLeases.*;
import static dev.aivillages.core.kernel.runtime.persistence.ArchiveFormat.*;

/** Derived lease-history client. ResourceLeaseService remains the sole hot-state publisher. */
public final class LeaseArchive {
    public enum Point { BEFORE_HOT_PUBLICATION, AFTER_HOT_ACKNOWLEDGEMENT }
    @FunctionalInterface public interface Boundary { void at(Point point)throws IOException; }
    public record Closure(long jobsRevision,long leaseRevision,long retentionGeneration,Set<UUID> settledJobs,boolean complete) {
        public Closure {
            if(jobsRevision<0 || leaseRevision<0 || retentionGeneration<0 || settledJobs.size()>64)throw new IllegalArgumentException("Reference closure");
            settledJobs=Set.copyOf(settledJobs);
        }
    }
    private record Index(Map<UUID,Lease> ids,Map<UUID,List<Lease>> groups) {
        private Index {ids=Map.copyOf(ids);groups=Map.copyOf(groups);}
    }
    private final class Reader implements ResourceLeaseService.History {
        private volatile Index index;
        private volatile boolean failed;
        public boolean ready(){return index!=null && !failed && !writer.readOnly();}
        public boolean failed(){return failed || writer.readOnly();}
        public Lease lookup(UUID id){if(!ready())throw new IllegalStateException("Fenced lease archive");return index.ids().get(id);}
        public List<Lease> group(UUID id){if(!ready())throw new IllegalStateException("Fenced lease archive");return index.groups().getOrDefault(id,List.of());}
    }
    /** Process-bound, non-constructible acknowledgement of exact rows and reference closure. */
    public static final class Publication {
        private final LeaseArchive owner;
        private final Snapshot expected;
        private final Closure closure;
        private final List<Lease> rows;
        private final ArchiveWriter.Acknowledgement acknowledgement;
        private Publication(LeaseArchive owner,Snapshot expected,Closure closure,List<Lease> rows,ArchiveWriter.Acknowledgement acknowledgement) {
            this.owner=owner;this.expected=expected;this.closure=closure;this.rows=List.copyOf(rows);this.acknowledgement=acknowledgement;
        }
        public List<Lease> rows(){return rows;}
        boolean permits(ResourceLeaseService service,Snapshot actual) {
            if(owner.service!=service || expected!=actual || !closure.complete() || !closure.equals(owner.current.get())
                    || !owner.reader.ready() || rows.isEmpty()
                    || !owner.writer.authentic(acknowledgement,ArchiveFormat.Owner.LEASES,
                            new Guard(actual.revision(),closure.retentionGeneration())))return false;
            Set<UUID> ids=new HashSet<>();
            for(Lease row:rows) {
                if(row.state()==State.ACTIVE || !closure.settledJobs().contains(row.owner().job()) || !ids.add(row.id())
                        || !actual.leases().contains(row) || !row.equals(owner.reader.lookup(row.id())))return false;
            }
            // A group is atomic, including when a future reader follows a previous hot snapshot.
            return actual.leases().stream().filter(l->rows.stream().anyMatch(r->r.group().equals(l.group())))
                    .allMatch(l->ids.contains(l.id()));
        }
    }
    private record Written(Index index,Publication publication,ArchiveWriter.Status status,int scanned,long hotBytes,long revision) { }
    public record Status(boolean ready,boolean readOnly,int hot,int active,int archived,long hotBytes,long archiveBytes,
                         long reservedBytes,boolean pending,String pressure,int lastScanned,long evicted) { }
    private final Thread thread=Thread.currentThread();
    private final ResourceLeaseService service;
    private final ArchiveWriter writer;
    private final ArchiveService storage;
    private final PersistencePolicy policy;
    private final Reader reader=new Reader();
    private final AtomicReference<Closure> current=new AtomicReference<>();
    private CompletableFuture<Written> pending;
    private int cursor,lastScanned;
    private long evicted;
    private long measuredRevision=-1,hotBytes,pendingSince;
    private String pressure="INITIALIZING";
    private Boundary boundary=point->{};
    private boolean observing;
    private Set<UUID> eviction=Set.of();
    public LeaseArchive(ResourceLeaseService service,ArchiveWriter writer,ArchiveService storage,PersistencePolicy policy) {
        this.service=Objects.requireNonNull(service);this.writer=Objects.requireNonNull(writer);
        this.storage=Objects.requireNonNull(storage);this.policy=Objects.requireNonNull(policy);service.attachHistory(reader);
    }
    /** Host instrumentation only; a callback is never evidence or permission to evict. */
    public void observe(Boundary boundary) {
        checkThread();if(observing)throw new IllegalStateException("Lease observer already bound");
        this.boundary=Objects.requireNonNull(boundary);observing=true;
    }
    /** One capture/offer per tick; all encoding, archive enumeration and file work use the existing worker. */
    public void step(Closure closure) {
        checkThread();current.set(Objects.requireNonNull(closure));lastScanned=0;
        if(!eviction.isEmpty()) {
            if(!service.ready()){if(service.failure()!=null){reader.failed=true;pressure="FENCED";}return;}
            if(service.snapshot().leases().stream().noneMatch(l->eviction.contains(l.id()))) {
                evicted=Math.addExact(evicted,eviction.size());eviction=Set.of();
                try{boundary.at(Point.AFTER_HOT_ACKNOWLEDGEMENT);}catch(IOException failure){reader.failed=true;pressure="FENCED";}
            }else{reader.failed=true;pressure="FENCED";}
            return;
        }
        if(pending!=null) {
            if(!pending.isDone()) {
                if(System.nanoTime()-pendingSince>TimeUnit.MILLISECONDS.toNanos(service.settings().acknowledgementMillis())) {
                    reader.failed=true;pressure="FENCED_ACK_TIMEOUT";
                }
                return;
            }
            try {
                Written done=pending.join();reader.index=done.index();lastScanned=done.scanned();
                hotBytes=done.hotBytes();measuredRevision=done.revision();
                pressure=done.status()==null?"NONE":done.status().name();
                if(done.publication()!=null && done.publication().permits(service,service.snapshot())) {
                    boundary.at(Point.BEFORE_HOT_PUBLICATION);
                    if(service.archive(done.publication()))eviction=done.publication().rows().stream().map(Lease::id)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
                }
            }catch(IOException | RuntimeException failure){reader.failed=true;pressure="FENCED";}
            finally{pending=null;}
            return;
        }
        if(reader.failed() || !closure.complete()){if(reader.failed())pressure="FENCED";return;}
        Snapshot captured=service.snapshot();
        if(captured.revision()!=closure.leaseRevision())return;
        if(reader.index!=null && (!service.ready() || captured.revision()==measuredRevision
                && captured.leases().size()<policy.leaseRows() && hotBytes<policy.leaseBytes()))return;
        int start=cursor;if(reader.index!=null)cursor=(cursor+policy.scanRows())%Math.max(1,captured.leases().size());
        var offered=storage.offer(w->{
            long bytes=ResourceLeaseJournal.encode(captured).length;
            if(reader.index==null || captured.leases().size()<policy.leaseRows() && bytes<policy.leaseBytes())
                return new Written(readIndex(w,captured),null,null,0,bytes,captured.revision());
            List<Lease> selected=select(captured,closure,start);int scanned=Math.min(policy.scanRows(),captured.leases().size());
            if(selected.isEmpty())return new Written(readIndex(w,captured),null,null,scanned,bytes,captured.revision());
            Guard guard=new Guard(captured.revision(),closure.retentionGeneration());
            List<Row> rows=selected.stream().map(l->new Row(ArchiveFormat.Kind.HISTORY,l.id(),
                    ResourceLeaseCodec.encode(l).getBytes(StandardCharsets.UTF_8))).toList();
            ArchiveWriter.Result result=w.append(w.snapshot(),ArchiveFormat.Owner.LEASES,guard,rows,
                    ignored->closure.equals(current.get())?guard:null);
            Index index=readIndex(w,captured);
            Publication publication=result.acknowledged()?new Publication(this,captured,closure,selected,result.acknowledgement()):null;
            return new Written(index,publication,result.status(),scanned,bytes,captured.revision());
        });
        if(offered.isPresent()){pending=offered.get();pendingSince=System.nanoTime();}else pressure=writer.readOnly()?"FENCED":"BUSY";
    }
    private List<Lease> select(Snapshot captured,Closure closure,int start) {
        List<Lease> hot=captured.leases();var result=new ArrayList<Lease>();var groups=new HashSet<UUID>();int bytes=81;
        for(int n=0;n<Math.min(policy.scanRows(),hot.size());n++) {
            Lease row=hot.get((start+n)%hot.size());if(!groups.add(row.group()))continue;
            List<Lease> group=hot.stream().filter(l->l.group().equals(row.group())).toList();
            if(result.size()+group.size()>policy.scanRows() || group.stream().anyMatch(l->l.state()==State.ACTIVE
                    || !closure.settledJobs().contains(l.owner().job())))continue;
            int framed=group.stream().mapToInt(l->21+ResourceLeaseCodec.encode(l).getBytes(StandardCharsets.UTF_8).length).sum();
            if((long)bytes+framed>policy.scanBytes())continue;
            result.addAll(group);bytes+=framed;
        }
        return List.copyOf(result);
    }
    private Index readIndex(ArchiveWriter w,Snapshot hot)throws IOException {
        var ids=new HashMap<UUID,Lease>();var grouped=new HashMap<UUID,List<Lease>>();
        try {
            for(Row row:w.historyView().rows(ArchiveFormat.Owner.LEASES,ArchiveFormat.Kind.HISTORY)) {
                Lease lease=ResourceLeaseCodec.decode(StrictJson.object(new String(row.payload(),StandardCharsets.UTF_8)));
                if(!row.id().equals(lease.id()) || lease.state()==State.ACTIVE || !lease.resource().world().equals(hot.world())
                        || !Arrays.equals(row.payload(),ResourceLeaseCodec.encode(lease).getBytes(StandardCharsets.UTF_8)))
                    throw new IOException("Invalid exact retired lease history");
                Lease prior=ids.putIfAbsent(lease.id(),lease);if(prior!=null && !prior.equals(lease))throw new IOException("Conflicting lease ID");
                grouped.computeIfAbsent(lease.group(),ignored->new ArrayList<>()).add(lease);
            }
            var groups=new HashMap<UUID,List<Lease>>();
            for(var entry:grouped.entrySet()) {
                List<Lease> values=List.copyOf(entry.getValue());Lease first=values.getFirst();
                ResourceLeaseService.validateSnapshot(new Snapshot(hot.world(),first.epoch(),0,
                        values.stream().mapToLong(Lease::generation).max().orElseThrow(),values),service.settings());
                groups.put(entry.getKey(),values);
            }
            for(Lease lease:hot.leases())if(ids.containsKey(lease.id()) && !ids.get(lease.id()).equals(lease))
                throw new IOException("Hot/archive lease conflict");
            return new Index(ids,groups);
        }catch(StrictJson.Invalid | RuntimeException invalid){throw new IOException("Unreadable lease domain history",invalid);}
    }
    /** Trusted server diagnostics only; no identities or player command are exposed. */
    public Status status() {
        checkThread();Snapshot hot=service.snapshot();long reserved=writer.snapshot().slots().stream().mapToLong(Slot::remainingBytes).sum();
        return new Status(reader.ready(),reader.failed(),hot.leases().size(),(int)hot.leases().stream().filter(l->l.state()==State.ACTIVE).count(),
                reader.index==null?0:reader.index.ids().size(),hotBytes,writer.historyView().bytes(),reserved,pending!=null || !eviction.isEmpty(),pressure,lastScanned,evicted);
    }
    public boolean readOnly(){return reader.failed();}
    private void checkThread(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Lease archive game thread");}
}
