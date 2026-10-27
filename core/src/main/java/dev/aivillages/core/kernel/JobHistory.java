package dev.aivillages.core.kernel;

import dev.aivillages.core.kernel.runtime.persistence.*;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import static dev.aivillages.core.kernel.Jobs.*;

/** Bounded immutable read model. It is neither a Job snapshot nor a lifecycle writer. */
public final class JobHistory {
    public static final int JOBS=2048, ALLOCATIONS=4096;
    @FunctionalInterface public interface Source {
        View read();
        default Job archived(UUID id){return read().job(id);}
    }
    public static final class View {
        private final UUID world;
        private final Map<UUID,Job> jobs;
        private final Map<UUID,UUID> receiptOwners;
        private View(UUID world,Map<UUID,Job> jobs,Map<UUID,UUID> receiptOwners) {
            this.world=world;this.jobs=Map.copyOf(jobs);this.receiptOwners=Map.copyOf(receiptOwners);
        }
        public UUID worldId(){return world;}
        public Job job(UUID id){return jobs.get(id);}
        public Collection<Job> jobs(){return jobs.values();}
        public UUID receiptOwner(UUID id){return receiptOwners.get(id);}
        public static View hot(Snapshot hot) {
            var jobs=new HashMap<UUID,Job>();var receipts=new HashMap<UUID,UUID>();
            for(Job job:hot.jobs()){jobs.put(job.id(),job);receipts(job,receipts);}
            return new View(hot.worldId(),jobs,receipts);
        }
    }
    static final class Index {
        final long revision;
        final Map<UUID,Job> jobs,submissions;
        final Map<UUID,Allocation> allocations;
        final Map<UUID,UUID> receipts,attempts;
        final Map<UUID,Snapshot> components;
        final Map<Contracts.TrustedContext,List<Job>> rootsByOrigin;
        final Roots roots;
        Index(long revision,Map<UUID,Job> jobs,Map<UUID,Job> submissions,Map<UUID,Allocation> allocations,
              Map<UUID,UUID> receipts,Map<UUID,UUID> attempts,Map<UUID,Snapshot> components,Roots roots) {
            this.revision=revision;this.jobs=Map.copyOf(jobs);this.submissions=Map.copyOf(submissions);
            this.allocations=Map.copyOf(allocations);this.receipts=Map.copyOf(receipts);this.attempts=Map.copyOf(attempts);
            this.components=Map.copyOf(components);this.roots=roots;
            var groups=new HashMap<Contracts.TrustedContext,List<Job>>();
            for(Job job:jobs.values())if(job.parent()==null)groups.computeIfAbsent(job.origin(),ignored->new ArrayList<>()).add(job);
            groups.replaceAll((origin,values)->values.stream().sorted(Comparator.comparing(j->j.id().toString())).toList());
            this.rootsByOrigin=Map.copyOf(groups);
        }
    }
    private final Supplier<Snapshot> hot;
    private final ArchiveWriter writer;
    private volatile Index index;
    private volatile boolean failed;
    private Snapshot cachedHot;
    private Index cachedIndex;
    private View cachedView;
    public JobHistory(Supplier<Snapshot> hot,ArchiveWriter writer) {
        this.hot=Objects.requireNonNull(hot);this.writer=Objects.requireNonNull(writer);
    }
    public boolean ready(){return index!=null && !failed && !writer.readOnly();}
    public Source source() {
        return new Source() {
            public View read(){return JobHistory.this.read();}
            public Job archived(UUID id){return JobHistory.this.archived(id);}
        };
    }
    public boolean failed(){return failed || writer.readOnly();}
    void fence(){failed=true;}
    public Job archived(UUID id){require();return index.jobs.get(id);}
    public Job submission(UUID id){require();return index.submissions.get(id);}
    public Allocation allocation(UUID id){require();return index.allocations.get(id);}
    public UUID receiptOwner(UUID id){require();return index.receipts.get(id);}
    public Roots roots(){require();return index.roots;}
    public int size(){return index==null?0:index.jobs.size();}
    int allocationCount(){return index==null?0:index.allocations.size();}
    long revision(){return index==null?-1:index.revision;}
    private void require(){if(!ready())throw new IllegalStateException("Job history is unavailable");}

    /** Called by trusted off-thread outcome/access owners. Cached by exact immutable input identities. */
    public synchronized View read() {
        require();Snapshot current=hot.get();Index archived=index;
        if(current==cachedHot && archived==cachedIndex)return cachedView;
        validateHot(current,archived);
        var jobs=new HashMap<>(archived.jobs);var receipts=new HashMap<>(archived.receipts);
        for(Job job:current.jobs()){jobs.putIfAbsent(job.id(),job);receipts(job,receipts);}
        cachedView=new View(current.worldId(),jobs,receipts);cachedHot=current;cachedIndex=archived;
        return cachedView;
    }
    /** Off-thread strict decoder; no receipt, authority or result is synthesized here. */
    public void refresh(Snapshot captured)throws IOException {
        if(writer.readOnly())throw new IOException("Fenced Job archive");
        try {
            Index previous=index;
            if(previous!=null && previous.revision==writer.snapshot().revision()){validateHot(captured,previous);return;}
            var jobs=new LinkedHashMap<UUID,Job>();var allocations=new LinkedHashMap<UUID,Allocation>();
            for(var row:writer.historyView().rows(ArchiveFormat.Owner.JOBS,ArchiveFormat.Kind.HISTORY)) {
                if(!row.id().equals(JobHistoryCodec.id(row.payload())))throw new IllegalArgumentException("Job component identity");
                Snapshot component=JobHistoryCodec.decode(row.payload(),captured.worldId());
                for(Job job:component.jobs())exact(jobs,job.id(),job);
                for(Allocation allocation:component.allocations())exact(allocations,allocation.id(),allocation);
                if(jobs.size()>JOBS || allocations.size()>ALLOCATIONS)throw new IllegalArgumentException("Job history index capacity");
            }
            var submissions=new HashMap<UUID,Job>();var receipts=new HashMap<UUID,UUID>();var attempts=new HashMap<UUID,UUID>();
            var artifacts=new HashSet<Contracts.ArtifactRef>();var runs=new HashSet<UUID>();
            for(Job job:jobs.values()) {
                exact(submissions,job.submissionId(),job);receipts(job,receipts);
                if(job.preparation()!=null && job.preparation().method()!=null)artifacts.add(job.preparation().method());
                for(Attempt attempt:job.attempts()) {
                    exact(attempts,attempt.id(),job.id());runs.add(attempt.id());
                    for(var execution:attempt.executions()){runs.add(execution.runId());artifacts.addAll(execution.pinned());}
                }
            }
            if(artifacts.size()>RetentionRoots.MAX_ROOTS)throw new IllegalArgumentException("History artifact roots capacity");
            Map<UUID,Snapshot> components=components(captured.worldId(),jobs.values(),allocations.values());
            Index next=new Index(writer.snapshot().revision(),jobs,submissions,allocations,receipts,attempts,components,
                    new Roots(artifacts,runs,jobs.keySet(),true));
            validateHot(captured,next);index=next;
        }catch(StrictJson.Invalid | RuntimeException invalid){failed=true;throw new IOException("Invalid Job domain history",invalid);}
    }
    /** A caller importing an old dependency must import its complete original allocation/reference closure. */
    Snapshot component(UUID id){require();return index.components.get(id);}
    List<Job> rootRows(Contracts.TrustedContext caller){require();return index.rootsByOrigin.getOrDefault(caller,List.of());}
    void validateHot(Snapshot captured){require();validateHot(captured,index);}
    private static void validateHot(Snapshot hot,Index archived) {
        if(hot.jobs().size()+archived.jobs.size()>JOBS+64)throw new IllegalArgumentException("Bounded combined history");
        for(Job job:hot.jobs()) {
            Job prior=archived.jobs.get(job.id()),submission=archived.submissions.get(job.submissionId());
            if(prior!=null && !prior.equals(job) || submission!=null && !submission.id().equals(job.id()))
                throw new IllegalArgumentException("Hot/archive Job identity conflict");
            for(Attempt attempt:job.attempts()) {
                UUID owner=archived.attempts.get(attempt.id());
                if(owner!=null && !owner.equals(job.id()))throw new IllegalArgumentException("Reused historical assignment");
            }
            var receipts=new HashMap<UUID,UUID>();receipts(job,receipts);
            for(UUID receipt:receipts.keySet())if(archived.receipts.containsKey(receipt) && !archived.receipts.get(receipt).equals(job.id()))
                throw new IllegalArgumentException("Reused historical receipt");
        }
        for(Allocation allocation:hot.allocations()) {
            Allocation prior=archived.allocations.get(allocation.id());
            if(prior!=null && !prior.equals(allocation))throw new IllegalArgumentException("Hot/archive allocation conflict");
        }
    }
    static Map<UUID,Snapshot> components(UUID world,Collection<Job> rows,Collection<Allocation> allocations) {
        var jobs=new LinkedHashMap<UUID,Job>();var edges=new HashMap<UUID,Set<UUID>>();
        for(Job job:rows){jobs.put(job.id(),job);edges.put(job.id(),new HashSet<>());}
        for(Job job:rows) {
            if(job.parent()!=null)link(edges,job.id(),job.parent());
            for(UUID id:job.dependencies())link(edges,job.id(),id);
            for(UUID id:job.children())link(edges,job.id(),id);
            if(job.preparation()!=null)for(var material:job.preparation().materials())link(edges,job.id(),material.producer());
        }
        for(Allocation allocation:allocations)link(edges,allocation.producer(),allocation.demand());
        var result=new HashMap<UUID,Snapshot>();
        for(Job start:rows)if(!result.containsKey(start.id())) {
            var visited=new LinkedHashSet<UUID>();var queue=new ArrayDeque<UUID>();queue.add(start.id());
            while(!queue.isEmpty()) {
                UUID id=queue.remove();if(!visited.add(id))continue;
                if(visited.size()>64)throw new IllegalArgumentException("Bounded closed dependency component");
                queue.addAll(edges.get(id));
            }
            Snapshot component=new Snapshot(world,0,visited.stream().map(jobs::get).toList(),
                    allocations.stream().filter(a->visited.contains(a.producer())).toList());
            JobLifecycleStore.validateSnapshot(component,Settings.defaults());
            for(UUID id:visited)result.put(id,component);
        }
        return Map.copyOf(result);
    }
    private static void link(Map<UUID,Set<UUID>> edges,UUID a,UUID b) {
        if(!edges.containsKey(a) || !edges.containsKey(b))throw new IllegalArgumentException("Incomplete Job history closure");
        edges.get(a).add(b);edges.get(b).add(a);
    }
    private static <T> void exact(Map<UUID,T> index,UUID id,T value) {
        T old=index.putIfAbsent(id,value);
        if(old!=null && !old.equals(value))throw new IllegalArgumentException("Conflicting exact historical UUID");
    }
    private static void receipts(Job job,Map<UUID,UUID> index) {
        for(Attempt attempt:job.attempts()) {
            for(var receipt:attempt.receipts())exact(index,receipt.receiptId(),job.id());
            for(var receipt:attempt.resourceReceipts())exact(index,receipt.receiptId(),job.id());
        }
    }
}
