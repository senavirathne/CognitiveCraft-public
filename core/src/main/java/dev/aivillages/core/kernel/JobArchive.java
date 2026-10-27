package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.runtime.persistence.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static dev.aivillages.core.kernel.Jobs.*;

/** Archive client only. Jobs alone acknowledges and publishes removal from the bounded hot journal. */
public final class JobArchive {
    public enum Point { ARCHIVE_ACKNOWLEDGED, BEFORE_HOT_PUBLICATION, AFTER_HOT_ACKNOWLEDGEMENT }
    @FunctionalInterface public interface Boundary { void at(Point point)throws IOException; }
    public record Closure(long jobsRevision,long leaseRevision,long retentionGeneration,Set<UUID> pinnedJobs,boolean complete) {
        public Closure {
            if(jobsRevision<0 || leaseRevision<0 || retentionGeneration<0 || pinnedJobs.size()>64)
                throw new IllegalArgumentException("Bounded Job reference closure");
            pinnedJobs=Set.copyOf(pinnedJobs);
        }
    }
    public static final class Publication {
        private final JobArchive owner;
        private final Snapshot expected;
        private final Closure closure;
        private final Set<UUID> ids;
        private final ArchiveWriter.Acknowledgement acknowledgement;
        private Publication(JobArchive owner,Snapshot expected,Closure closure,Set<UUID> ids,ArchiveWriter.Acknowledgement acknowledgement) {
            this.owner=owner;this.expected=expected;this.closure=closure;this.ids=Set.copyOf(ids);this.acknowledgement=acknowledgement;
        }
        Set<UUID> ids(){return ids;}
        boolean permits(JobLifecycleStore service,Snapshot actual) {
            if(owner.jobs!=service || !service.archiveHeldBy(owner) || actual!=expected || !closure.complete()
                    || !closure.equals(owner.current.get()) || !owner.history.ready() || ids.isEmpty()
                    || !owner.writer.authentic(acknowledgement,ArchiveFormat.Owner.JOBS,
                            new ArchiveFormat.Guard(actual.revision(),closure.retentionGeneration())))return false;
            for(Job job:actual.jobs())if(ids.contains(job.id()) && (closure.pinnedJobs().contains(job.id())
                    || JobHistoryCodec.custody(job,actual)==null || !job.equals(owner.history.archived(job.id()))))return false;
            // Independently reconstruct the surviving hot closure before allowing the sole owner to publish it.
            try {
                new Snapshot(actual.worldId(),actual.revision(),actual.jobs().stream().filter(j->!ids.contains(j.id())).toList(),
                        actual.allocations().stream().filter(a->!ids.contains(a.producer()) && !ids.contains(a.demand())).toList());
                return actual.jobs().stream().filter(j->ids.contains(j.id())).count()==ids.size();
            }catch(IllegalArgumentException invalid){return false;}
        }
    }
    private record Selected(List<ArchiveFormat.Row> records,Set<UUID> ids,int scanned) { }
    private record Written(Publication publication,String pressure,int scanned,long bytes,long revision) { }
    public record Status(boolean ready,boolean readOnly,int hot,int active,int archived,long hotBytes,long hotBytesRevision,
                         boolean pending,String pressure,int lastScanned,long evicted) { }
    private final Thread thread=Thread.currentThread();
    private final JobLifecycleStore jobs;
    private final JobHistory history;
    private final ArchiveWriter writer;
    private final ArchiveService storage;
    private final PersistencePolicy policy;
    private final AtomicReference<Closure> current=new AtomicReference<>();
    private CompletableFuture<Written> pending;
    private Set<UUID> eviction=Set.of();
    private int cursor,lastScanned;
    private long evicted,pendingSince,hotBytes,measuredRevision=-1;
    private String pressure="INITIALIZING";
    private boolean gated,observing;
    private volatile Boundary boundary=point->{};
    public JobArchive(JobLifecycleStore jobs,JobHistory history,ArchiveWriter writer,ArchiveService storage,PersistencePolicy policy) {
        this.jobs=Objects.requireNonNull(jobs);this.history=Objects.requireNonNull(history);this.writer=Objects.requireNonNull(writer);
        this.storage=Objects.requireNonNull(storage);this.policy=Objects.requireNonNull(policy);jobs.attachHistory(history);
    }
    public void observe(Boundary boundary) {
        checkThread();if(observing)throw new IllegalStateException("Job archive observer already bound");
        this.boundary=Objects.requireNonNull(boundary);observing=true;
    }
    /** Readers remain attached when new archival is disabled. */
    public void step(Closure closure,boolean compact) {
        checkThread();current.set(Objects.requireNonNull(closure));lastScanned=0;
        if(!eviction.isEmpty()) {
            if(jobs.snapshot().jobs().stream().anyMatch(j->eviction.contains(j.id()))) {
                if(jobs.lastFailure()==Outcomes.Reason.STORAGE_UNAVAILABLE){history.fence();pressure="FENCED";endGate();}
                return;
            }
            evicted=Math.addExact(evicted,eviction.size());eviction=Set.of();endGate();
            try{boundary.at(Point.AFTER_HOT_ACKNOWLEDGEMENT);}catch(IOException failure){history.fence();pressure="FENCED";}
            return;
        }
        if(pending!=null) {
            if(!pending.isDone()) {
                if(System.nanoTime()-pendingSince>TimeUnit.MILLISECONDS.toNanos(jobs.settings().publicationMillis())) {
                    history.fence();pressure="FENCED_ACK_TIMEOUT";
                }
                return;
            }
            try {
                Written done=pending.join();lastScanned=done.scanned();hotBytes=done.bytes();measuredRevision=done.revision();
                pressure=history.failed()?"FENCED":done.pressure();
                if(done.publication()!=null && done.publication().permits(jobs,jobs.snapshot())) {
                    boundary.at(Point.BEFORE_HOT_PUBLICATION);
                    if(jobs.archive(done.publication()))eviction=done.publication().ids();
                }
                if(eviction.isEmpty())endGate();
            }catch(IOException | RuntimeException failure){history.fence();pressure="FENCED";endGate();}
            finally{pending=null;}
            return;
        }
        if(history.failed() || !closure.complete()){if(history.failed())pressure="FENCED";return;}
        Snapshot captured=jobs.snapshot();if(captured.revision()!=closure.jobsRevision())return;
        boolean measure=captured.revision()!=measuredRevision;
        boolean copy=compact && history.ready() && (captured.jobs().size()>=policy.jobRows()
                || !measure && hotBytes>=policy.jobBytes());
        if(!measure && !copy && history.revision()==writer.snapshot().revision())return;
        if(copy){if(!jobs.beginArchive(this,captured))return;gated=true;}
        int start=cursor;if(copy)cursor=(cursor+policy.scanRows())%Math.max(1,captured.jobs().size());
        var offered=storage.offer(w->{
            long bytes=JobJournal.encode(captured).length;
            history.refresh(captured);
            if(!copy)return new Written(null,"NONE",0,bytes,captured.revision());
            Selected selected=select(captured,closure,start);
            if(selected.ids().isEmpty())return new Written(null,"PINNED",selected.scanned(),bytes,captured.revision());
            ArchiveFormat.Guard guard=new ArchiveFormat.Guard(captured.revision(),closure.retentionGeneration());
            var result=w.append(w.snapshot(),ArchiveFormat.Owner.JOBS,guard,selected.records(),
                    ignored->Objects.equals(current.get(),closure)?guard:null);
            history.refresh(captured);
            if(result.acknowledged())boundary.at(Point.ARCHIVE_ACKNOWLEDGED);
            Publication publication=result.acknowledged()?new Publication(this,captured,closure,selected.ids(),result.acknowledgement()):null;
            return new Written(publication,result.status().name(),selected.scanned(),bytes,captured.revision());
        });
        if(offered.isPresent()){pending=offered.get();pendingSince=System.nanoTime();}
        else{pressure=writer.readOnly()?"FENCED":"BUSY";endGate();}
    }
    private Selected select(Snapshot captured,Closure closure,int start) {
        var components=JobHistory.components(captured.worldId(),captured.jobs(),captured.allocations());
        var visited=Collections.newSetFromMap(new IdentityHashMap<Snapshot,Boolean>());
        var records=new ArrayList<ArchiveFormat.Row>();var ids=new HashSet<UUID>();var allocationIds=new HashSet<UUID>();int bytes=81;
        int scanned=Math.min(policy.scanRows(),captured.jobs().size());
        for(int n=0;n<scanned;n++) {
            Job candidate=captured.jobs().get((start+n)%captured.jobs().size());Snapshot component=components.get(candidate.id());
            if(!visited.add(component) || component.jobs().size()+ids.size()>policy.scanRows()
                    || component.jobs().stream().anyMatch(j->closure.pinnedJobs().contains(j.id()) || JobHistoryCodec.custody(j,component)==null))continue;
            byte[] payload=JobHistoryCodec.encode(component);
            if(payload.length>PersistencePolicy.RECORD_BYTES || (long)bytes+21+payload.length>policy.scanBytes()
                    || history.size()+ids.stream().filter(id->history.archived(id)==null).count()
                        +component.jobs().stream().filter(j->history.archived(j.id())==null).count()>JobHistory.JOBS
                    || history.allocationCount()+allocationIds.size()
                        +component.allocations().stream().filter(a->history.allocation(a.id())==null).count()>JobHistory.ALLOCATIONS)continue;
            records.add(new ArchiveFormat.Row(ArchiveFormat.Kind.HISTORY,JobHistoryCodec.id(payload),payload));bytes+=21+payload.length;
            component.jobs().forEach(j->ids.add(j.id()));
            component.allocations().stream().filter(a->history.allocation(a.id())==null).forEach(a->allocationIds.add(a.id()));
        }
        return new Selected(List.copyOf(records),Set.copyOf(ids),scanned);
    }
    public boolean capturing(){checkThread();return gated;}
    public boolean readOnly(){return history.failed();}
    public boolean readReady(){return history.ready();}
    public Status status() {
        checkThread();Snapshot snapshot=jobs.snapshot();return new Status(history.ready(),history.failed(),snapshot.jobs().size(),
                (int)snapshot.jobs().stream().filter(j->!j.state().terminal()).count(),history.size(),hotBytes,measuredRevision,
                pending!=null || !eviction.isEmpty(),pressure,lastScanned,evicted);
    }
    private void endGate(){if(gated){jobs.endArchive(this);gated=false;}}
    private void checkThread(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Job archive game thread");}
}
