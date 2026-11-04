package dev.aivillages.fabric.runtime.evidence;

import dev.aivillages.core.kernel.runtime.evidence.*;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class EntityTrailRecordTest {
    final String dim="minecraft:overworld";
    NativePickup.Chunk c(int x){return new NativePickup.Chunk(dim,x,0);}
    EntityTrailRecord record(boolean complete){return new EntityTrailRecord(new NativePickup.Trail(UUID.randomUUID(),UUID.randomUUID(),List.of(c(0),c(1)),c(1)),
            new Marker("a".repeat(64),"b".repeat(64),MarkerPhase.RESULT,"c".repeat(64)),4,complete,true);}
    @Test void nativeTrailRoundTripRetainsEveryVisitedChunkAndIdentity(){var r=record(true);assertEquals(r,EntityTrailRecord.decode(r.encode()));}
    @Test void actualRestoredMovementKeepsDepartureAndSameTickReentryHistory(){
        var r=record(true);var trail=EntityMembershipTrail.restored(r.trail(),true);var seen=new ArrayList<NativePickup.Trail>();trail.observe(seen::add);
        trail.moved(c(2));trail.moved(c(0));assertEquals(List.of(c(0),c(1),c(2)),trail.capture().chunks());assertEquals(c(0),trail.capture().current());assertEquals(2,seen.size());
    }
    @Test void anIncompleteSavedHistoryCannotBeResetByMovingBackToItsOldChunk(){
        var r=record(false);var trail=EntityMembershipTrail.restored(r.trail(),false);trail.moved(c(0));assertFalse(trail.complete());
        assertThrows(IllegalStateException.class,trail::capture);assertFalse(EntityTrailRecord.decode(r.encode()).complete());
    }
    @Test void unknownNoncanonicalAndOversizedTrailMetadataRefuse(){
        var r=record(true);assertThrows(IllegalArgumentException.class,()->EntityTrailRecord.decode(r.encode().replace("\"schema\":1","\"schema\":2")));
        assertThrows(IllegalArgumentException.class,()->EntityTrailRecord.decode(r.encode()+"\n"));assertThrows(IllegalArgumentException.class,()->EntityTrailRecord.decode("x".repeat(16_001)));
    }
    @Test void changedDimensionAndExcessiveHistoryPermanentlyGapTheRestoredTrail(){
        var r=record(true);var trail=EntityMembershipTrail.restored(r.trail(),true);trail.moved(new NativePickup.Chunk("minecraft:the_nether",1,0));assertFalse(trail.complete());
        var bounded=EntityMembershipTrail.restored(r.trail(),true);for(int n=2;n<9;n++)bounded.moved(c(n));assertFalse(bounded.complete());
    }
}
