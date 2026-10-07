package dev.aivillages.fabric;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LeaseTargetWitnessesTest {
    @Test void sameRuntimeObjectRetainsIdentityButReplacementDoesNot(){
        var witnesses=new LeaseTargetWitnesses(2);Object block=new Object();
        String first=witnesses.identity("container",block);
        assertEquals(first,witnesses.identity("container",block));
        assertNotEquals(first,witnesses.identity("container",new Object()));
        assertEquals(1,witnesses.retained());
    }
    @Test void equalObjectsDoNotConcealReplacement(){
        var witnesses=new LeaseTargetWitnesses(2);
        assertNotEquals(witnesses.identity("chest",new String("same")),witnesses.identity("chest",new String("same")));
    }
    @Test void capacityNeverEvictsAStillReferencedWitness(){
        var witnesses=new LeaseTargetWitnesses(2);Object a=new Object();String first=witnesses.identity("a",a);
        witnesses.identity("b",new Object());
        assertThrows(IllegalStateException.class,()->witnesses.identity("c",new Object()));
        assertEquals(first,witnesses.identity("a",a));assertEquals(2,witnesses.retained());
    }
    @Test void invalidConfigurationAndOversizedKeysAreRejected(){
        assertThrows(IllegalArgumentException.class,()->new LeaseTargetWitnesses(0));
        assertThrows(IllegalArgumentException.class,()->new LeaseTargetWitnesses(129));
        var bounded=new LeaseTargetWitnesses(128);
        assertThrows(IllegalArgumentException.class,()->bounded.identity("x".repeat(129),new Object()));
        assertThrows(IllegalArgumentException.class,()->bounded.identity("a",null));
    }
}
