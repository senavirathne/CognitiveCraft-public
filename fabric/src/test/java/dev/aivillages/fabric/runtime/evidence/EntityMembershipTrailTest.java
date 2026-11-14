package dev.aivillages.fabric.runtime.evidence;

import dev.aivillages.core.kernel.runtime.evidence.NativePickup;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** In-memory complete-lifetime tracking contracts; no storage hooks or native pickup proof. */
final class EntityMembershipTrailTest {
    static final String DIM="minecraft:overworld";
    static NativePickup.Chunk chunk(int n){return new NativePickup.Chunk(DIM,n,0);}
    static EntityMembershipTrail live(){return new EntityMembershipTrail(UUID.randomUUID(),UUID.randomUUID(),chunk(0));}
    @Test void anExplicitBirthStartsOneBoundedObservedLifetime() {
        var live=live();var captured=live.capture();assertTrue(live.complete());
        assertEquals(List.of(chunk(0)),captured.chunks());assertEquals(chunk(0),captured.current());
    }
    @Test void everyChunkChangeAndReturnKeepsAllEarlierDepartureChunks() {
        var live=live();live.moved(chunk(2));live.moved(chunk(1));live.moved(chunk(0));
        assertEquals(List.of(chunk(0),chunk(1),chunk(2)),live.capture().chunks());assertEquals(chunk(0),live.capture().current());
    }
    @Test void anUnobservedGapCannotBeHealedByReturningToTheOriginalCell() {
        var live=live();live.moved(chunk(1));live.gap();live.moved(chunk(0));
        assertFalse(live.complete());assertThrows(IllegalStateException.class,live::capture);
    }
    @Test void anotherDimensionPermanentlyRefusesThisFirstPickupEnvelope() {
        var live=live();live.moved(new NativePickup.Chunk("minecraft:the_nether",0,0));live.moved(chunk(0));
        assertFalse(live.complete());assertThrows(IllegalStateException.class,live::capture);
    }
    @Test void theNinthChunkRefusesWithoutEvictionOrAReplacementAllowance() {
        var live=live();for(int n=1;n<NativePickup.MAX_TRAIL_CHUNKS;n++)live.moved(chunk(n));
        assertEquals(NativePickup.MAX_TRAIL_CHUNKS,live.capture().chunks().size());
        live.moved(chunk(NativePickup.MAX_TRAIL_CHUNKS));live.moved(chunk(0));
        assertFalse(live.complete());assertThrows(IllegalStateException.class,live::capture);
    }
    @Test void detachedCapturesAndTheirListsDoNotChangeWithLaterMovement() {
        var live=live();live.moved(chunk(1));var old=live.capture();live.moved(chunk(2));
        assertEquals(2,old.chunks().size());assertEquals(3,live.capture().chunks().size());
        assertThrows(UnsupportedOperationException.class,()->old.chunks().add(chunk(7)));
    }
    @Test void anotherThreadCannotRewriteTheObservedNativeLifetime() throws Exception {
        var live=live();var old=live.capture();var failure=new AtomicReference<Throwable>();
        Thread other=new Thread(()->{try{live.moved(chunk(1));}catch(Throwable rejected){failure.set(rejected);}});
        other.start();other.join();assertInstanceOf(IllegalStateException.class,failure.get());assertEquals(old,live.capture());
    }
    @Test void serializedTrailDataDoesNotRecreateTheEarlierObservedLifetime() {
        var original=live();original.moved(chunk(1));var data=original.capture();
        var fresh=new EntityMembershipTrail(data.entity(),data.incarnation(),data.current());
        assertNotEquals(data,fresh.capture());assertEquals(List.of(data.current()),fresh.capture().chunks());
        assertThrows(NullPointerException.class,()->new EntityMembershipTrail(null,data.incarnation(),chunk(0)));
    }
}
