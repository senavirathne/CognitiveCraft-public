package dev.aivillages.core.kernel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.VersionedSkillRepository.*;

/** IMP-010. Disposable metadata only; the repository and resolver retain all authoritative decisions. */
public final class CapabilityRetrievalIndex implements AutoCloseable {
    public static final String WORLD_RELATIVE_PATH="data/cognitivecraft/cache/retrieval/v1";
    public record Limits(int entries,int pageSize,int querySlice,int rebuildSlice,
                         int responseBytes,int entryBytes,long cacheBytes,long deadlineMillis) {
        public Limits {
            if (entries<1 || entries>4096 || pageSize<1 || pageSize>16 || querySlice<1 || querySlice>64
                    || rebuildSlice<1 || rebuildSlice>128 || responseBytes<1024 || responseBytes>65536
                    || entryBytes<128 || entryBytes>4096 || cacheBytes<512 || cacheBytes>4_194_304
                    || deadlineMillis<1 || deadlineMillis>30_000) throw new IllegalArgumentException("Retrieval limits");
        }
        public static Limits defaults() { return new Limits(4096,16,64,128,65536,4096,4_194_304,12_000); }
    }
    public record Identity(UUID world,UUID instance) {
        public Identity { Objects.requireNonNull(world); Objects.requireNonNull(instance); }
    }
    public record Epoch(long catalog,long runtime,long visibility,boolean available) {
        public Epoch {
            if (catalog<0 || runtime<0 || visibility<0) throw new IllegalArgumentException("Retrieval epoch");
        }
    }
    /** Shared metadata contains no body, private origins, observations, query text or permissions. */
    public record Metadata(Candidate candidate,Set<String> tags) {
        public Metadata {
            Objects.requireNonNull(candidate); Objects.requireNonNull(candidate.ref());
            Objects.requireNonNull(candidate.admission()); Objects.requireNonNull(candidate.integrity());
            if (candidate.compatibility()==null || !candidate.compatibility().artifact().equals(candidate.ref()))
                throw new IllegalArgumentException("Metadata reference");
            if (tags==null || tags.size()>8) throw new IllegalArgumentException("Metadata tags");
            tags=Set.copyOf(tags);
            for (String tag:tags) if (!tag.equals(label(tag))) throw new IllegalArgumentException("Normalized metadata tag");
        }
    }
    /** Capture is consistent; next is finite and never reads executable body content. */
    public interface CatalogSnapshot {
        Epoch epoch(); int size(); List<Metadata> next(int maximum); boolean complete();
    }
    public interface Source {
        Epoch epoch(); CatalogSnapshot snapshot(); CapabilityResolver.CandidateSource exact();
    }
    public static Source repositorySource(VersionedSkillRepository repository,LookupRegistry registry,
                                          LongSupplier visibilityRevision) {
        Objects.requireNonNull(repository); Objects.requireNonNull(registry); Objects.requireNonNull(visibilityRevision);
        return new Source() {
            private Epoch stamp(CatalogRevision revision,long visibility) {
                return new Epoch(revision.catalog(),revision.runtime(),visibility,revision.available());
            }
            @Override public Epoch epoch() { return stamp(repository.catalogRevision(),visibilityRevision.getAsLong()); }
            @Override public CatalogSnapshot snapshot() {
                var captured=repository.metadataSnapshot();
                Epoch epoch=stamp(captured.revision(),visibilityRevision.getAsLong());
                return new CatalogSnapshot() {
                    @Override public Epoch epoch() { return epoch; }
                    @Override public int size() { return captured.size(); }
                    @Override public boolean complete() { return captured.complete(); }
                    @Override public List<Metadata> next(int maximum) {
                        return captured.next(maximum).stream().map(candidate->
                                new Metadata(candidate,registry.tags(candidate.ref().capability()))).toList();
                    }
                };
            }
            @Override public CapabilityResolver.CandidateSource exact() { return CapabilityResolver.exactCatalog(repository); }
        };
    }

    /** Trusted lookup aids refer only to already registered outcome contracts. */
    public static final class LookupRegistry {
        private final Map<String,CapabilityId> aliases;
        private final Map<CapabilityId,Set<String>> tags;
        private final Set<String> knownTags;
        private final String fingerprint;
        public LookupRegistry(Map<String,CapabilityId> aliases,Map<CapabilityId,Set<String>> tags,
                              CapabilityCatalog capabilities) {
            Objects.requireNonNull(aliases); Objects.requireNonNull(tags); Objects.requireNonNull(capabilities);
            if (aliases.size()>128 || tags.size()>128) throw new IllegalArgumentException("Lookup registry limit");
            var names=new TreeMap<String,CapabilityId>(); var assigned=new HashMap<CapabilityId,Set<String>>();
            var allTags=new java.util.HashSet<String>();
            for (var entry:aliases.entrySet()) {
                checkedCapability(entry.getValue(),capabilities);
                if (names.put(label(entry.getKey()),entry.getValue())!=null) throw new IllegalArgumentException("Duplicate alias");
            }
            for (var entry:tags.entrySet()) {
                checkedCapability(entry.getKey(),capabilities);
                if (entry.getValue()==null || entry.getValue().size()>8) throw new IllegalArgumentException("Tag assignment limit");
                var normalized=new java.util.HashSet<String>();
                for (String tag:entry.getValue()) normalized.add(label(tag));
                assigned.put(entry.getKey(),Set.copyOf(normalized)); allTags.addAll(normalized);
            }
            if (allTags.size()>128) throw new IllegalArgumentException("Registered tag limit");
            this.aliases=Map.copyOf(names); this.tags=Map.copyOf(assigned); knownTags=Set.copyOf(allTags);
            var semantic=new StringBuilder();
            names.forEach((alias,id)->semantic.append(alias).append('=').append(id).append('\n'));
            assigned.entrySet().stream().sorted(Map.Entry.comparingByKey(REF_CAPABILITY_ORDER)).forEach(entry->
                    semantic.append(entry.getKey()).append('=').append(entry.getValue().stream().sorted().toList()).append('\n'));
            fingerprint=hash(semantic.toString());
        }
        private static void checkedCapability(CapabilityId id,CapabilityCatalog catalog) {
            if (id==null || catalog.find(id).filter(spec->spec.id().equals(id)).isEmpty())
                throw new IllegalArgumentException("Unregistered lookup capability");
        }
        Set<String> tags(CapabilityId id) { return tags.getOrDefault(id,Set.of()); }
    }
    public record Filters(Set<AdmissionStatus> admission,Set<CompatibilityStatus> compatibility,Set<Integrity> integrity) {
        public Filters {
            admission=Set.copyOf(admission); compatibility=Set.copyOf(compatibility); integrity=Set.copyOf(integrity);
        }
        public static Filters all() { return new Filters(Set.of(),Set.of(),Set.of()); }
        boolean matches(Candidate row) {
            return (admission.isEmpty() || admission.contains(row.admission()))
                    && (compatibility.isEmpty() || compatibility.contains(row.compatibility().status()))
                    && (integrity.isEmpty() || integrity.contains(row.integrity()));
        }
    }
    public record Query(CapabilityId capability,String alias,Set<String> tags,Filters filters,
                        int pageSize,int maximumWork,String cursor) {
        public Query {
            alias=alias==null || alias.isEmpty()?"":label(alias);
            if ((capability==null)==alias.isEmpty() || tags==null || tags.size()>8
                    || pageSize<1 || pageSize>16 || maximumWork<1 || maximumWork>64
                    || cursor==null || cursor.length()>512) throw new IllegalArgumentException("Retrieval query");
            var normalized=new java.util.HashSet<String>();
            for (String tag:tags) normalized.add(label(tag)); tags=Set.copyOf(normalized);
            Objects.requireNonNull(filters);
        }
        public static Query capability(CapabilityId id) { return new Query(id,"",Set.of(),Filters.all(),16,64,""); }
        public Query continueWith(String token) { return new Query(capability,alias,tags,filters,pageSize,maximumWork,token); }
    }
    public enum State { READY, REBUILDING, STALE, UNAVAILABLE, LIMIT, CANCELLED, INVALID_CURSOR, UNKNOWN_LOOKUP, DENIED, CLOSED }
    public enum Phase { RESTORE, CAPTURE, COLLECT, WRITE, COMMIT, TERMINAL }
    public record Result(List<Metadata> entries,String nextCursor,boolean complete,State state,
                         long catalogRevision,int examined,int responseBytes) {
        public Result { entries=List.copyOf(entries); }
    }
    public record Status(State state,Epoch epoch,int entries,boolean rebuilding) { }
    public record Progress(State state,Phase phase,int examined,int total,int slices,long bytes,boolean terminal) { }
    public record Counters(long queries,long examined,long pages,long descriptors,long fallbackPages,
                           long rebuildExamined,long rebuildSlices,long bodyLoads,long responseBytes,long rebuiltBytes) { }
    public enum FaultPoint { BEFORE_REPLACE, AFTER_REPLACE }
    @FunctionalInterface public interface FaultInjector {
        void check(FaultPoint point) throws IOException;
        static FaultInjector none() { return point->{}; }
    }
    private static final Comparator<CapabilityId> REF_CAPABILITY_ORDER=Comparator.comparing(CapabilityId::name)
            .thenComparingInt(CapabilityId::version);
    private static final Comparator<ArtifactRef> REF_ORDER=Comparator.comparing(ArtifactRef::capability,REF_CAPABILITY_ORDER)
            .thenComparing(ArtifactRef::sha256);
    private static final class Generation {
        final Epoch epoch; final UUID id; final Map<CapabilityId,NavigableMap<String,Metadata>> rows; final int size;
        Generation(Epoch epoch,UUID id,Map<CapabilityId,NavigableMap<String,Metadata>> rows,int size) {
            this.epoch=epoch; this.id=id; this.rows=rows; this.size=size;
        }
    }
    private record Holder(Generation generation,Rebuild operation,boolean cancelled,boolean closed) { }
    private final AtomicReference<Holder> holder=new AtomicReference<>(new Holder(null,null,false,false));
    private final AtomicBoolean deleting=new AtomicBoolean();
    private final Path root;
    private final Identity identity;
    private final Source source;
    private final LookupRegistry registry;
    private final CapabilityCatalog capabilities;
    private final Limits limits;
    private final LongSupplier clock;
    private final Predicate<TrustedContext> visibility;
    private final FaultInjector faults;
    private final byte[] cursorKey=new byte[32];
    private volatile Epoch lastAttempt;
    private volatile State lastFailure=State.UNAVAILABLE;
    private final AtomicLong queries=new AtomicLong(),examined=new AtomicLong(),pages=new AtomicLong(),descriptors=new AtomicLong(),
            fallbackPages=new AtomicLong(),rebuildExamined=new AtomicLong(),rebuildSlices=new AtomicLong(),
            responseBytes=new AtomicLong(),rebuiltBytes=new AtomicLong();

    public CapabilityRetrievalIndex(Path root,Identity identity,Source source,LookupRegistry registry,
                                   CapabilityCatalog capabilities,Limits limits,LongSupplier clock) {
        this(root,identity,source,registry,capabilities,limits,clock,context->true,FaultInjector.none());
    }
    public CapabilityRetrievalIndex(Path root,Identity identity,Source source,LookupRegistry registry,
                                   CapabilityCatalog capabilities,Limits limits,LongSupplier clock,
                                   Predicate<TrustedContext> visibility,FaultInjector faults) {
        this.root=Objects.requireNonNull(root); this.identity=Objects.requireNonNull(identity);
        this.source=Objects.requireNonNull(source); this.registry=Objects.requireNonNull(registry);
        this.capabilities=Objects.requireNonNull(capabilities); this.limits=Objects.requireNonNull(limits);
        this.clock=Objects.requireNonNull(clock); this.visibility=Objects.requireNonNull(visibility); this.faults=Objects.requireNonNull(faults);
        new SecureRandom().nextBytes(cursorKey);
    }
    public Status status() {
        Holder current=holder.get(); Epoch epoch=currentEpoch();
        State state=current.closed()?State.CLOSED:fresh(current.generation(),epoch)?State.READY:
                current.generation()!=null?State.STALE:current.operation()!=null?State.REBUILDING:lastFailure;
        return new Status(state,epoch,current.generation()==null?0:current.generation().size,current.operation()!=null);
    }
    public Counters counters() { return new Counters(queries.get(),examined.get(),pages.get(),descriptors.get(),
            fallbackPages.get(),rebuildExamined.get(),rebuildSlices.get(),0,responseBytes.get(),rebuiltBytes.get()); }
    private Epoch currentEpoch() {
        try { return Objects.requireNonNull(source.epoch()); }
        catch (RuntimeException unavailable) { return new Epoch(0,0,0,false); }
    }
    private static boolean fresh(Generation generation,Epoch epoch) {
        return generation!=null && epoch.available() && generation.epoch.equals(epoch);
    }
    public Result query(Query query,TrustedContext context) {
        Objects.requireNonNull(query); Objects.requireNonNull(context); increment(queries,1);
        Epoch epoch=currentEpoch(); Holder state=holder.get();
        if (!context.scope().worldId().equals(identity.world()) || !visibility.test(context))
            return new Result(List.of(),"",false,State.DENIED,-1,0,1024);
        if (state.closed()) return result(State.CLOSED,epoch,0);
        Generation generation=state.generation();
        if (!fresh(generation,epoch)) return result(generation==null?lastFailure:State.STALE,epoch,0);
        CapabilityId id=query.capability()!=null?query.capability():registry.aliases.get(query.alias());
        if (id==null || capabilities.find(id).filter(spec->spec.id().equals(id)).isEmpty()
                || !registry.knownTags.containsAll(query.tags())) return result(State.UNKNOWN_LOOKUP,epoch,0);
        if (query.pageSize()>limits.pageSize() || query.maximumWork()>limits.querySlice()) return result(State.LIMIT,epoch,0);
        String signature=hash(id+"|"+query.alias()+"|"+query.tags().stream().sorted().toList()+"|"
                +query.filters().admission().stream().sorted().toList()+"|"+query.filters().compatibility().stream().sorted().toList()
                +"|"+query.filters().integrity().stream().sorted().toList()+"|"+query.pageSize()+"|"+query.maximumWork());
        String caller=hash(context.principal().id()+"|"+context.scope().worldId()+"|"+context.scope().domainId());
        String after=cursor(query.cursor(),generation,signature,caller);
        if (after==null) return result(State.INVALID_CURSOR,epoch,0);
        NavigableMap<String,Metadata> table=generation.rows.get(id);
        if (table==null) return completeEmpty(epoch,generation,context);
        var iterator=table.tailMap(after,false).entrySet().iterator(); var rows=new ArrayList<Metadata>();
        int work=0,bytes=1024; State outcome=State.READY;
        while (iterator.hasNext() && rows.size()<query.pageSize() && work<query.maximumWork()) {
            var entry=iterator.next(); work++;
            Metadata row=entry.getValue();
            if (!row.tags().containsAll(query.tags()) || !query.filters().matches(row.candidate())) { after=entry.getKey(); continue; }
            int size;
            try { size=RetrievalIndexCache.encode(row).length+4; }
            catch (IOException invalid) { return result(State.UNAVAILABLE,epoch,work); }
            if (size>limits.responseBytes()-bytes) { outcome=State.LIMIT; break; }
            rows.add(row); bytes+=size; after=entry.getKey();
        }
        boolean complete=outcome!=State.LIMIT && !iterator.hasNext();
        if (!complete && work==query.maximumWork() && rows.size()<query.pageSize()) outcome=State.LIMIT;
        increment(examined,work); increment(pages,1); increment(responseBytes,bytes);
        if (!epoch.equals(currentEpoch()) || holder.get().generation()!=generation || holder.get().closed() || !visibility.test(context))
            return result(State.STALE,epoch,work);
        String token=complete?"":token(generation,signature,caller,after);
        return new Result(rows,token,complete,outcome,epoch.catalog(),work,bytes);
    }
    private Result result(State state,Epoch epoch,int work) { return new Result(List.of(),"",false,state,epoch.catalog(),work,1024); }
    private Result completeEmpty(Epoch epoch,Generation generation,TrustedContext context) {
        increment(pages,1); increment(responseBytes,1024);
        return epoch.equals(currentEpoch()) && holder.get().generation()==generation && !holder.get().closed() && visibility.test(context)
                ?new Result(List.of(),"",true,State.READY,epoch.catalog(),0,1024):result(State.STALE,epoch,0);
    }

    /** Internal trusted resolver pagination preserves its exact SHA cursor contract and all alternatives. */
    public CapabilityResolver.CandidateSource candidates() {
        return new CapabilityResolver.CandidateSource() {
            @Override public Page page(CapabilityId id,String after,int maximum) {
                if (after==null || !after.isEmpty() && !after.matches("[0-9a-f]{64}") || maximum<1 || maximum>limits.pageSize())
                    throw new IllegalArgumentException("Candidate page");
                Holder current=holder.get(); Epoch epoch=currentEpoch(); Generation generation=current.generation();
                increment(pages,1);
                if (current.closed() || !fresh(generation,epoch)) return new Page(List.of(),"",false,epoch.catalog());
                var table=generation.rows.get(id); var selected=new ArrayList<Candidate>(); boolean complete=true;
                if (table!=null) {
                    var it=table.tailMap(after,false).values().iterator();
                    while (it.hasNext() && selected.size()<maximum) selected.add(it.next().candidate());
                    complete=!it.hasNext();
                }
                increment(examined,selected.size());
                if (!epoch.equals(currentEpoch()) || holder.get().generation()!=generation)
                    return new Page(List.of(),"",false,epoch.catalog());
                return new Page(selected,complete?"":selected.getLast().ref().sha256(),complete,epoch.catalog());
            }
            @Override public Optional<ArtifactDescriptor> descriptor(ArtifactRef ref) {
                increment(descriptors,1); return source.exact().descriptor(ref);
            }
            @Override public long revision() {
                Epoch epoch=currentEpoch(); return fresh(holder.get().generation(),epoch)?epoch.catalog():-1;
            }
            @Override public Outcomes.Reason incompleteReason() {
                State state=status().state();
                return state==State.STALE?Outcomes.Reason.STALE_OBSERVATION:
                        state==State.LIMIT?Outcomes.Reason.BUDGET_EXHAUSTED:Outcomes.Reason.STORAGE_UNAVAILABLE;
            }
        };
    }
    public CapabilityResolver.CandidateSource authoritativeFallback() {
        CapabilityResolver.CandidateSource exact=source.exact();
        return new CapabilityResolver.CandidateSource() {
            private Epoch scanned;
            @Override public Page page(CapabilityId id,String after,int size) {
                increment(fallbackPages,1); Epoch before=currentEpoch(); Page page=exact.page(id,after,size);
                if (!before.equals(currentEpoch()) || !before.available() || !after.isEmpty() && !before.equals(scanned))
                    return new Page(List.of(),"",false,before.catalog());
                scanned=before; return page;
            }
            @Override public Optional<ArtifactDescriptor> descriptor(ArtifactRef ref) { increment(descriptors,1); return exact.descriptor(ref); }
            @Override public long revision() {
                return scanned==null || scanned.equals(currentEpoch())?exact.revision():-1;
            }
            @Override public Outcomes.Reason incompleteReason() { return exact.incompleteReason(); }
        };
    }

    /** Nonblocking tick hook: schedule at most one recovery/rebuild per observed epoch. */
    public void maintain(Executor worker) {
        Objects.requireNonNull(worker); Holder current=holder.get(); if (current.closed() || deleting.get()) return;
        if (current.operation()!=null) { if (current.operation().expired()) current.operation().cancel(); return; }
        Epoch epoch=currentEpoch();
        if (fresh(current.generation(),epoch) || epoch.equals(lastAttempt)) return;
        boolean recover=lastAttempt==null;
        Rebuild rebuild=begin(recover);
        try { CompletableFuture.runAsync(()->{ while (!rebuild.advance().terminal()) { /* bounded worker batches */ } },worker); }
        catch (RuntimeException rejected) { rebuild.cancel(); rebuild.cleanup(); }
    }
    /** Worker-only lifecycle. A cancelled batch must be advanced once more for bounded I/O cleanup. */
    public Rebuild beginRebuild() { return begin(false); }
    public Rebuild beginRecovery() { return begin(true); }
    private Rebuild begin(boolean recovery) {
        Holder current=holder.get();
        if (current.closed() || current.operation()!=null || deleting.get()) throw new IllegalStateException("Retrieval worker busy/closed");
        Epoch epoch=currentEpoch(); Rebuild work=new Rebuild(epoch,recovery);
        if (!holder.compareAndSet(current,new Holder(current.generation(),work,false,false)))
            throw new IllegalStateException("Retrieval worker changed");
        lastAttempt=epoch; return work;
    }
    public final class Rebuild {
        private final Epoch epoch;
        private final UUID generation=UUID.randomUUID();
        private final long started=clock.getAsLong();
        private TreeMap<ArtifactRef,Metadata> rows=new TreeMap<>(REF_ORDER);
        private Map<CapabilityId,NavigableMap<String,Metadata>> byCapability=new HashMap<>();
        private volatile Progress progress;
        private CatalogSnapshot snapshot;
        private RetrievalIndexCache.Reader reader;
        private RetrievalIndexCache.Writer writer;
        private Iterator<Metadata> output;
        private UUID restored;
        private int total,read,slices,examinedRows;
        private long encodedBytes;
        private Rebuild(Epoch epoch,boolean recover) {
            this.epoch=epoch; progress=new Progress(State.REBUILDING,recover?Phase.RESTORE:Phase.CAPTURE,0,0,0,0,false);
        }
        public Progress progress() { return progress; }
        private boolean expired() {
            long now=clock.getAsLong();
            return now<started || now-started<0 || now-started>limits.deadlineMillis();
        }
        public void cancel() {
            while (true) {
                Holder current=holder.get(); if (current.operation()!=this || current.cancelled()) return;
                if (holder.compareAndSet(current,new Holder(current.generation(),this,true,current.closed()))) return;
            }
        }
        private boolean active() { Holder h=holder.get(); return h.operation()==this && !h.cancelled() && !h.closed(); }
        public Progress advance() {
            if (progress.terminal()) return progress;
            if (!active()) return finish(State.CANCELLED);
            if (expired()) return finish(State.LIMIT);
            if (!epoch.available()) return finish(State.UNAVAILABLE);
            if (!epoch.equals(currentEpoch())) return finish(State.STALE);
            int work=0; Phase next=progress.phase(); slices++; increment(rebuildSlices,1);
            try {
                switch (next) {
                    case RESTORE -> {
                        if (reader==null) {
                            try { reader=new RetrievalIndexCache.Reader(root,identity,epoch,registry.fingerprint,limits); }
                            catch (IOException invalid) { next=Phase.CAPTURE; break; }
                            total=reader.header.entries(); restored=reader.header.generation();
                        }
                        try {
                            List<Metadata> batch=reader.next(limits.rebuildSlice()); work=batch.size(); charge(work);
                            for (Metadata row:batch) add(row);
                            if (reader.done()) { reader.verify(); publish(restored); return progress; }
                        } catch (IOException | RuntimeException corrupt) {
                            reader.close(); reader=null;
                            rows=new TreeMap<>(REF_ORDER); byCapability=new HashMap<>(); encodedBytes=0; read=0; total=0;
                            next=Phase.CAPTURE;
                        }
                    }
                    case CAPTURE -> {
                        snapshot=source.snapshot();
                        if (snapshot==null || !snapshot.epoch().equals(epoch)) return finish(State.STALE);
                        total=snapshot.size();
                        if (total<0 || total>limits.entries()) return finish(State.LIMIT);
                        next=Phase.COLLECT;
                    }
                    case COLLECT -> {
                        List<Metadata> batch=snapshot.next(limits.rebuildSlice());
                        if (batch==null || batch.size()>limits.rebuildSlice() || batch.isEmpty() && !snapshot.complete())
                            return finish(State.UNAVAILABLE);
                        work=batch.size();
                        charge(work);
                        for (Metadata row:batch) add(row);
                        if (rows.size()>total || rows.size()>limits.entries()) return finish(State.UNAVAILABLE);
                        if (snapshot.complete()) {
                            if (rows.size()!=total) return finish(State.UNAVAILABLE);
                            writer=new RetrievalIndexCache.Writer(root,new RetrievalIndexCache.Header(identity,epoch,
                                    registry.fingerprint,generation,total),limits);
                            output=rows.values().iterator(); next=Phase.WRITE;
                        }
                    }
                    case WRITE -> {
                        while (output.hasNext() && work<limits.rebuildSlice()) {
                            Metadata row=output.next(); work++; charge(1); writer.row(row);
                        }
                        if (!output.hasNext()) { writer.finish(); next=Phase.COMMIT; }
                    }
                    case COMMIT -> {
                        faults.check(FaultPoint.BEFORE_REPLACE);
                        if (!active()) return finish(State.CANCELLED);
                        if (!epoch.equals(currentEpoch())) return finish(State.STALE);
                        writer.replace(); faults.check(FaultPoint.AFTER_REPLACE); publish(generation); return progress;
                    }
                    case TERMINAL -> throw new AssertionError();
                }
            } catch (RetrievalIndexCache.LimitReached limit) { return finish(State.LIMIT); }
            catch (IOException | RuntimeException unavailable) { return finish(State.UNAVAILABLE); }
            progress=new Progress(State.REBUILDING,next,examinedRows,total,slices,encodedBytes,false);
            return progress;
        }
        private void charge(int work) { examinedRows+=work; increment(rebuildExamined,work); }
        private void add(Metadata metadata) throws IOException {
            if (metadata==null || !registry.knownTags.containsAll(metadata.tags())) throw new IOException("Unregistered metadata tag");
            int bytes=RetrievalIndexCache.encode(metadata).length+4;
            if (bytes>limits.entryBytes()+4 || encodedBytes>limits.cacheBytes()-bytes || rows.size()>=limits.entries())
                throw new RetrievalIndexCache.LimitReached();
            if (rows.putIfAbsent(metadata.candidate().ref(),metadata)!=null) throw new IOException("Duplicate artifact reference");
            byCapability.computeIfAbsent(metadata.candidate().ref().capability(),ignored->new TreeMap<>())
                    .put(metadata.candidate().ref().sha256(),metadata);
            encodedBytes+=bytes; increment(rebuiltBytes,bytes); read++;
        }
        private void publish(UUID id) {
            if (!active()) { finish(State.CANCELLED); return; }
            if (!epoch.equals(currentEpoch())) { finish(State.STALE); return; }
            Holder current=holder.get();
            var ready=new Generation(epoch,id,byCapability,rows.size());
            if (current.operation()!=this || current.cancelled() || current.closed()
                    || !holder.compareAndSet(current,new Holder(ready,null,false,false))) { finish(State.CANCELLED); return; }
            lastFailure=State.UNAVAILABLE;
            progress=new Progress(State.READY,Phase.TERMINAL,examinedRows,total,slices,encodedBytes,true); cleanup();
        }
        private Progress finish(State state) {
            lastFailure=state;
            progress=new Progress(state,Phase.TERMINAL,examinedRows,total,slices,encodedBytes,true); cleanup();
            return progress;
        }
        private void cleanup() {
            try { if (reader!=null) reader.close(); } catch (IOException ignored) { /* disposable read handle */ }
            try { if (writer!=null) writer.close(); } catch (IOException ignored) { /* one bounded pending file */ }
            reader=null; writer=null;
            snapshot=null; output=null; rows=null; byCapability=null;
            while (true) {
                Holder current=holder.get(); if (current.operation()!=this) break;
                if (holder.compareAndSet(current,new Holder(current.generation(),null,false,current.closed()))) break;
            }
        }
    }
    /** Worker-only explicit cache deletion; no repository, admission, identity or policy writes. */
    public void deleteCache() throws IOException {
        if (!deleting.compareAndSet(false,true)) throw new IllegalStateException("Retrieval deletion busy");
        try {
            Holder current=holder.get();
            if (current.operation()!=null) throw new IllegalStateException("Retrieval worker busy");
            if (!holder.compareAndSet(current,new Holder(null,null,false,current.closed())))
                throw new IllegalStateException("Retrieval worker changed");
            lastAttempt=null; lastFailure=State.UNAVAILABLE; RetrievalIndexCache.delete(root);
        } finally { deleting.set(false); }
    }
    @Override public void close() {
        while (true) {
            Holder current=holder.get(); if (current.closed()) return;
            if (holder.compareAndSet(current,new Holder(current.generation(),current.operation(),true,true))) return;
        }
    }
    private String token(Generation generation,String query,String context,String after) {
        String payload=generation.id+"|"+query+"|"+context+"|"+after;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.US_ASCII))
                +"."+Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
    }
    private String cursor(String token,Generation generation,String query,String context) {
        if (token.isEmpty()) return "";
        try {
            String[] parts=token.split("\\.",-1); if (parts.length!=2) return null;
            String payload=new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(sign(payload),Base64.getUrlDecoder().decode(parts[1]))) return null;
            String[] fields=payload.split("\\|",-1);
            return fields.length==4 && fields[0].equals(generation.id.toString()) && fields[1].equals(query)
                    && fields[2].equals(context) && (fields[3].isEmpty() || fields[3].matches("[0-9a-f]{64}"))?fields[3]:null;
        } catch (IllegalArgumentException invalid) { return null; }
    }
    private byte[] sign(String text) {
        try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(cursorKey,"HmacSHA256"));
            return mac.doFinal(text.getBytes(StandardCharsets.US_ASCII)); }
        catch (java.security.GeneralSecurityException unavailable) { throw new IllegalStateException(unavailable); }
    }
    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hash(String value) {
        return java.util.HexFormat.of().formatHex(sha256().digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static String label(String value) {
        if (value==null || value.length()>128) throw new IllegalArgumentException("Lookup label");
        String normalized=Normalizer.normalize(value,Normalizer.Form.NFC).trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9 _:/.-]{0,63}")) throw new IllegalArgumentException("Lookup label");
        return normalized;
    }
    private static void increment(AtomicLong counter,long amount) {
        counter.updateAndGet(current->current>Long.MAX_VALUE-amount?Long.MAX_VALUE:current+amount);
    }
}
