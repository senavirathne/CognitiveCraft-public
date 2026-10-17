package dev.aivillages.fabric;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MovementTravelTest {
    @Test void delayedPollsChargeEverySampleBeforeReach() {
        var travel=new MovementTravel(0,0,0,0);
        for(int tick=1;tick<=8;tick++)travel.observe(tick,tick,0,0);
        assertFalse(travel.drained());
        long charged=0;
        for(int poll=0;poll<8;poll++) {charged+=travel.charge();assertEquals(poll==7,travel.drained());}
        assertEquals(8,charged);assertEquals(0,travel.charge());
    }
    @Test void stationaryAndRepeatedSameTickSamplesDoNotDoubleCharge() {
        var travel=new MovementTravel(0,0,0,0);
        travel.observe(1,1,0,0);travel.observe(1,1,0,0);travel.observe(2,1,0,0);
        assertEquals(1,travel.charge());assertEquals(0,travel.charge());assertTrue(travel.drained());
    }
    @Test void oneTickTeleportAndMissingSamplesFailClosed() {
        var direct=new MovementTravel(0,0,0,0);direct.observe(1,2,0,0);
        assertEquals(2,direct.charge());assertFalse(direct.drained());
        var unknown=new MovementTravel(0,0,0,0);unknown.observe(8,2,0,0);
        assertEquals(2,unknown.charge());
    }
    @Test void capAndCapPlusOneRemainFinite() {
        var travel=new MovementTravel(0,0,0,0);
        for(int i=1;i<=MovementTravel.MAX_PENDING;i++)travel.observe(i,i,0,0);
        assertEquals(1,travel.charge()); // N is representable and charged, never discarded.
        travel.observe(130,130,0,0);travel.observe(131,131,0,0);
        assertEquals(2,travel.charge());assertFalse(travel.drained());
    }
    @Test void backwardsClockAndCoordinateOverflowFailClosed() {
        var backwards=new MovementTravel(3,0,0,0);backwards.observe(2,0,0,0);
        assertEquals(2,backwards.charge());
        var overflow=new MovementTravel(0,Integer.MIN_VALUE,0,0);overflow.observe(1,Integer.MAX_VALUE,0,0);
        assertEquals(2,overflow.charge());
    }
}
