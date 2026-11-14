package dev.aivillages.fabric.runtime.evidence;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Context isolation controls; the positive birth path is checked in the actual native H trial. */
final class HarvestBirthScopeTest {
    @Test void ordinaryUnscopedHarvestCannotActivateTracking() {
        assertFalse(HarvestBirthScope.active());HarvestBirthScope.spawned(null);assertFalse(HarvestBirthScope.active());
    }
    @Test void nestedScopesAndNullBirthsCannotReplaceTheOriginalCallback() {
        var calls=new AtomicInteger();try(var scope=HarvestBirthScope.open(drop->calls.incrementAndGet())) {
            assertThrows(IllegalStateException.class,()->HarvestBirthScope.open(drop->calls.addAndGet(100)));
            assertThrows(NullPointerException.class,()->HarvestBirthScope.spawned(null));assertTrue(HarvestBirthScope.active());assertEquals(0,calls.get());
        }
        assertFalse(HarvestBirthScope.active());
    }
    @Test void anotherThreadCannotBorrowOrCloseTheOriginalContext() throws Exception {
        var calls=new AtomicInteger();var error=new AtomicReference<Throwable>();var scope=HarvestBirthScope.open(drop->calls.incrementAndGet());
        try {
            var thread=new Thread(()->{assertFalse(HarvestBirthScope.active());HarvestBirthScope.spawned(null);try{scope.close();}catch(Throwable t){error.set(t);}});
            thread.start();thread.join();assertInstanceOf(IllegalStateException.class,error.get());assertEquals(0,calls.get());assertTrue(HarvestBirthScope.active());
        }finally{scope.close();}
    }
    @Test void repeatedClosureCannotRemoveALaterIndependentScope() {
        var first=HarvestBirthScope.open(drop->{});first.close();try(var second=HarvestBirthScope.open(drop->{})) {
            assertThrows(IllegalStateException.class,first::close);assertTrue(HarvestBirthScope.active());
        }
        assertFalse(HarvestBirthScope.active());
    }
}
