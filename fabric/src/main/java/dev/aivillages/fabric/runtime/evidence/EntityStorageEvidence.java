package dev.aivillages.fabric.runtime.evidence;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

/** Bounded native-storage adapter; metadata serialization is outside effect preparation. */
public final class EntityStorageEvidence {
    public static final int MAX_CHUNKS=128,MAX_ROWS=512,MAX_PENDING_WRITES=16;
    public interface Access {
        CompletableFuture<Optional<CompoundTag>> read(ChunkPos position);
        CompletableFuture<Void> write(ChunkPos position,CompoundTag root);
    }
    private final Thread owner=Thread.currentThread();private final Access access;private final String dimension;private final Executor metadataWorker;
    private final Map<ChunkPos,List<EntityChunkRemovalState.Removal>> desired=new HashMap<>();
    private final Set<ChunkPos> retained=ConcurrentHashMap.newKeySet();
    private final Map<ChunkPos,CompletableFuture<Void>> writes=new HashMap<>();
    private volatile boolean overflow;private volatile Throwable failure;private int rows,pendingWrites;
    public EntityStorageEvidence(Access access,String dimension){this(access,dimension,ForkJoinPool.commonPool());}
    EntityStorageEvidence(Access access,String dimension,Executor worker){this.access=Objects.requireNonNull(access);this.dimension=Objects.requireNonNull(dimension);metadataWorker=Objects.requireNonNull(worker);}
    private void thread(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Native entity metadata owner");}
    public boolean available(){return !overflow&&failure==null;}
    public boolean needsRetention(ChunkPos pos){synchronized(desired){return overflow||retained.contains(pos)||desired.containsKey(pos);}}
    private void retain(ChunkPos pos){if(retained.contains(pos))return;synchronized(retained){if(retained.size()<MAX_CHUNKS)retained.add(pos);else overflow=true;}}
    /** Descriptive result data only; the future pickup producer must reserve/validate before effects. */
    public boolean stageRemoval(ChunkPos pos,EntityChunkRemovalState.Removal row) {
        thread();Objects.requireNonNull(pos);Objects.requireNonNull(row);
        synchronized(desired) {
            if(!available())return false;var existing=desired.getOrDefault(pos,List.of());
            for(var prior:existing)if(prior.marker().effect().equals(row.marker().effect()))return prior.equals(row);
            if(existing.size()==EntityChunkRemovalState.MAX_REMOVALS||rows==MAX_ROWS||!desired.containsKey(pos)&&desired.size()==MAX_CHUNKS)return false;
            synchronized(retained){if(!retained.contains(pos)&&retained.size()==MAX_CHUNKS)return false;retain(pos);}
            var next=new ArrayList<EntityChunkRemovalState.Removal>(existing);next.add(row);desired.put(pos,List.copyOf(next));rows++;return true;
        }
    }
    private List<EntityChunkRemovalState.Removal> snapshot(ChunkPos pos){synchronized(desired){return desired.getOrDefault(pos,List.of());}}
    private CompletableFuture<Void> preceding(ChunkPos pos){synchronized(writes){return writes.getOrDefault(pos,CompletableFuture.completedFuture(null));}}
    public CompletableFuture<Optional<CompoundTag>> readForLoad(ChunkPos pos) {
        return preceding(pos).thenCompose(ignored->access.read(pos)).thenApply(value->{value.filter(EntityChunkRecord::hasMetadata).ifPresent(root->retain(pos));return value;});
    }
    public CompletableFuture<EntityChunkRecord.Frame> readFrame(ChunkPos pos){return readForLoad(pos).thenApplyAsync(value->EntityChunkRecord.read(value.orElse(null),dimension,pos),metadataWorker);}
    public CompletableFuture<Void> write(ChunkPos pos,CompoundTag root){return write(pos,()->root);}
    public CompletableFuture<Void> write(ChunkPos pos,Supplier<CompoundTag> root) {
        if(!needsRetention(pos))return access.write(pos,root.get());
        final var wanted=snapshot(pos);
        synchronized(writes) {
            if(failure!=null)return CompletableFuture.failedFuture(failure);
            writes.entrySet().removeIf(e->e.getValue().isDone()&&!e.getValue().isCompletedExceptionally());
            if(pendingWrites==MAX_PENDING_WRITES){failure=new IOException("Native metadata queue full; previous file retained");return CompletableFuture.failedFuture(failure);}
            var prior=writes.getOrDefault(pos,CompletableFuture.completedFuture(null));pendingWrites++;
            var next=prior.thenCompose(ignored->access.read(pos)).thenComposeAsync(previous->{
                CompoundTag merged=EntityChunkRecord.merge(previous.orElse(null),root.get(),dimension,pos,wanted);
                if(EntityChunkRecord.hasMetadata(merged))retain(pos);return access.write(pos,merged);
            },metadataWorker);
            writes.put(pos,next);next.whenComplete((ignored,error)->{synchronized(writes){pendingWrites--;if(error!=null)failure=error;}});return next;
        }
    }
    /** Only Minecraft's already blocking flush/close boundary waits for metadata I/O. */
    public void awaitWrites() {
        for(;;) {
            CompletableFuture<?>[] pending;
            synchronized(writes) {
                if(failure!=null)throw new CompletionException(failure);
                writes.entrySet().removeIf(e->e.getValue().isDone()&&!e.getValue().isCompletedExceptionally());
                if(writes.isEmpty())return;pending=writes.values().toArray(CompletableFuture<?>[]::new);
            }
            CompletableFuture.allOf(pending).join();
        }
    }
}
