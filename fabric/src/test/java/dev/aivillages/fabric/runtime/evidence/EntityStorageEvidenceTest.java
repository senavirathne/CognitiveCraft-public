package dev.aivillages.fabric.runtime.evidence;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static dev.aivillages.fabric.runtime.evidence.EntityChunkRemovalStateTest.*;
import static org.junit.jupiter.api.Assertions.*;

final class EntityStorageEvidenceTest {
    @BeforeAll static void version(){SharedConstants.tryDetectVersion();}
    static final class Access implements EntityStorageEvidence.Access {
        final Map<ChunkPos,CompoundTag> files=new ConcurrentHashMap<>();final AtomicInteger reads=new AtomicInteger(),writes=new AtomicInteger();volatile boolean fail;
        public CompletableFuture<Optional<CompoundTag>> read(ChunkPos p){reads.incrementAndGet();return CompletableFuture.completedFuture(Optional.ofNullable(files.get(p)).map(CompoundTag::copy));}
        public CompletableFuture<Void> write(ChunkPos p,CompoundTag root) {
            writes.incrementAndGet();if(fail)return CompletableFuture.failedFuture(new IOException("native write failed"));
            if(root==null)files.remove(p);else files.put(p,root.copy());return CompletableFuture.completedFuture(null);
        }
    }
    static final class Queue implements Executor {
        final java.util.Queue<Runnable> tasks=new ConcurrentLinkedQueue<>();
        public void execute(Runnable task){tasks.add(task);}void run(){for(Runnable r;(r=tasks.poll())!=null;)r.run();}
    }
    @Test void unmarkedLegacyNativeWriteHasNoExtraRead() {
        var a=new Access();var e=new EntityStorageEvidence(a,DIM,Runnable::run);var root=EntityChunkRecordTest.record(UUID.randomUUID());
        e.write(POS,root).join();assertEquals(root,a.files.get(POS));assertEquals(0,a.reads.get());
        e.write(POS,(CompoundTag)null).join();assertFalse(a.files.containsKey(POS));assertTrue(e.available());
    }
    @Test void removalAckWaitsForActualAccessWrite() {
        var a=new Access();var q=new Queue();var e=new EntityStorageEvidence(a,DIM,q);
        a.files.put(POS,EntityChunkRecordTest.record(row(0).entity()));assertTrue(e.stageRemoval(POS,row(0)));
        var write=e.write(POS,(CompoundTag)null);assertFalse(write.isDone());assertEquals(0,a.writes.get());q.run();write.join();
        assertTrue(EntityChunkRecordTest.restored(a.files.get(POS)).restored(row(0),Set.of()));assertEquals(1,a.reads.get());assertEquals(1,a.writes.get());
    }
    @Test void sameChunkWritesRetainAllOrderedResults() {
        var a=new Access();var q=new Queue();var e=new EntityStorageEvidence(a,DIM,q);e.stageRemoval(POS,row(0));var first=e.write(POS,(CompoundTag)null);
        e.stageRemoval(POS,row(1));var second=e.write(POS,(CompoundTag)null);assertEquals(1,a.reads.get());q.run();first.join();second.join();
        var state=EntityChunkRecordTest.restored(a.files.get(POS));assertEquals(2,state.size());assertTrue(state.restored(row(0),Set.of()));assertTrue(state.restored(row(1),Set.of()));
    }
    @Test void nativeFlushCannotAckBeforeMetadataQueueCompletes() throws Exception {
        var a=new Access();var q=new Queue();var e=new EntityStorageEvidence(a,DIM,q);e.stageRemoval(POS,row(0));e.write(POS,(CompoundTag)null);
        var started=new CountDownLatch(1);var flush=CompletableFuture.runAsync(()->{started.countDown();e.awaitWrites();});
        assertTrue(started.await(5,TimeUnit.SECONDS));assertFalse(flush.isDone());assertEquals(0,a.writes.get());q.run();flush.get(5,TimeUnit.SECONDS);
        assertTrue(a.files.get(POS).contains(EntityChunkRemovalState.TAG));
    }
    @Test void sameChunkLoadWaitsForItsQueuedNativeWrite() {
        var a=new Access();var q=new Queue();var e=new EntityStorageEvidence(a,DIM,q);e.stageRemoval(POS,row(0));e.write(POS,(CompoundTag)null);
        var read=e.readForLoad(POS);assertFalse(read.isDone());assertEquals(1,a.reads.get());q.run();
        assertTrue(read.join().orElseThrow().contains(EntityChunkRemovalState.TAG));assertEquals(2,a.reads.get());
    }
    @Test void nativeWriteFailurePreservesOldRecordAndRejectsFlushAck() {
        var a=new Access();var old=EntityChunkRecordTest.record(UUID.randomUUID());a.files.put(POS,old.copy());var e=new EntityStorageEvidence(a,DIM,Runnable::run);
        e.stageRemoval(POS,row(0));a.fail=true;assertThrows(CompletionException.class,()->e.write(POS,(CompoundTag)null).join());
        assertEquals(old,a.files.get(POS));assertFalse(e.available());assertFalse(e.stageRemoval(POS,row(1)));assertThrows(CompletionException.class,e::awaitWrites);
    }
    @Test void everyRepeatedWriteToOneChunkConsumesTheGlobalQueueLimit() {
        var a=new Access();var q=new Queue();var e=new EntityStorageEvidence(a,DIM,q);e.stageRemoval(POS,row(0));var accepted=new ArrayList<CompletableFuture<Void>>();
        for(int n=0;n<EntityStorageEvidence.MAX_PENDING_WRITES;n++)accepted.add(e.write(POS,(CompoundTag)null));
        assertThrows(CompletionException.class,()->e.write(POS,(CompoundTag)null).join());assertFalse(e.available());assertEquals(0,a.writes.get());
        q.run();accepted.forEach(CompletableFuture::join);assertEquals(EntityStorageEvidence.MAX_PENDING_WRITES,a.writes.get());assertThrows(CompletionException.class,e::awaitWrites);
    }
    @Test void rowChunkLimitsDoNotEvictOriginalsAndExactDuplicatesConsumeNothing() {
        var a=new Access();var e=new EntityStorageEvidence(a,DIM,Runnable::run);
        for(int n=0;n<64;n++)assertTrue(e.stageRemoval(POS,row(n)));assertTrue(e.stageRemoval(POS,row(0)));assertFalse(e.stageRemoval(POS,row(64)));
        for(int chunk=1;chunk<=7;chunk++)for(int n=0;n<64;n++)assertTrue(e.stageRemoval(new ChunkPos(chunk,0),row(1000+chunk*64+n)));
        assertFalse(e.stageRemoval(new ChunkPos(9,0),row(9999)));assertEquals(0,a.writes.get());
        var other=new EntityStorageEvidence(a,DIM,Runnable::run);
        for(int chunk=0;chunk<EntityStorageEvidence.MAX_CHUNKS;chunk++)assertTrue(other.stageRemoval(new ChunkPos(chunk,0),row(chunk)));
        assertFalse(other.stageRemoval(new ChunkPos(129,0),row(1000)));
    }
    @Test void opaqueLoadedMetadataSurvivesNativeEmptyDeleteWithoutBecomingProof() {
        var a=new Access();var unknown=EntityChunkRecordTest.record();unknown.putInt(EntityChunkRemovalState.TAG,77);a.files.put(POS,unknown);
        var e=new EntityStorageEvidence(a,DIM,Runnable::run);e.readForLoad(POS).join();assertTrue(e.needsRetention(POS));e.write(POS,(CompoundTag)null).join();
        assertEquals(77,a.files.get(POS).getInt(EntityChunkRemovalState.TAG).orElseThrow());assertFalse(e.readFrame(POS).join().supported());
    }
    @Test void metadataSerializationRunsOnWorkerAndForeignLiveWritersRefuse() throws Exception {
        var a=new Access();var worker=Executors.newSingleThreadExecutor();var observed=new AtomicReference<Thread>();Thread game=Thread.currentThread();
        try {
            var e=new EntityStorageEvidence(a,DIM,worker);e.stageRemoval(POS,row(0));
            e.write(POS,(Supplier<CompoundTag>)()->{observed.set(Thread.currentThread());return null;}).get(5,TimeUnit.SECONDS);
            assertNotNull(observed.get());assertNotEquals(game,observed.get());e.awaitWrites();
            var refused=new AtomicReference<Throwable>();var foreign=new Thread(()->{try{e.stageRemoval(POS,row(1));}catch(Throwable error){refused.set(error);}});
            foreign.start();foreign.join();assertInstanceOf(IllegalStateException.class,refused.get());
        }finally{worker.shutdownNow();}
    }
    @Test void conflictingLiveResultCannotReplaceTheOriginalStagedRow() {
        var a=new Access();var e=new EntityStorageEvidence(a,DIM,Runnable::run);var r=row(0);assertTrue(e.stageRemoval(POS,r));
        assertFalse(e.stageRemoval(POS,new EntityChunkRemovalState.Removal(r.entity(),r.incarnation(),r.revision()+1,r.marker())));
        assertEquals(0,a.writes.get());e.write(POS,(CompoundTag)null).join();assertTrue(EntityChunkRecordTest.restored(a.files.get(POS)).restored(r,Set.of()));
    }
    @Test void loadedChunkOverflowRefusesProducersWhilePreservingEveryOpaqueRecord() {
        var a=new Access();var e=new EntityStorageEvidence(a,DIM,Runnable::run);
        for(int n=0;n<=EntityStorageEvidence.MAX_CHUNKS;n++) {
            var pos=new ChunkPos(n,0);var root=EntityChunkRecord.empty(pos);root.putString(EntityChunkRecord.MEMBERSHIP_TAG,"future"+n);a.files.put(pos,root);e.readForLoad(pos).join();
            if(n==EntityStorageEvidence.MAX_CHUNKS-1){assertTrue(e.available());assertFalse(e.stageRemoval(new ChunkPos(1000,0),row(0)));}
        }
        assertFalse(e.available());assertFalse(e.stageRemoval(POS,row(0)));var last=new ChunkPos(EntityStorageEvidence.MAX_CHUNKS,0);
        e.write(last,(CompoundTag)null).join();assertEquals("future"+EntityStorageEvidence.MAX_CHUNKS,a.files.get(last).getString(EntityChunkRecord.MEMBERSHIP_TAG).orElseThrow());
    }
}
