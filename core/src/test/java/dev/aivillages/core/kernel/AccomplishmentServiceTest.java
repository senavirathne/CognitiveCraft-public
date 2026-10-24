package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static dev.aivillages.core.kernel.Accomplishments.*;
import static org.junit.jupiter.api.Assertions.*;

class AccomplishmentServiceTest {
    @TempDir Path temp;
    static final class Queue implements Executor {
        final ArrayDeque<Runnable> work=new ArrayDeque<>();
        public void execute(Runnable task){work.add(task);}
        void run(){work.remove().run();}
    }
    @Test void immutableIntakeNeverReadsOrPublishesLedgerOnTheTickThread()throws Exception{
        var f=new AccomplishmentLedgerTest.Fixture();f.successful(2);
        try(var ledger=f.open(temp);var service=new AccomplishmentService(ledger)){
            var queue=new Queue();service.maintain(f.scene.memory.state,queue);
            assertEquals(1,queue.work.size());assertEquals(0,ledger.snapshot().revision());assertTrue(service.pending());
            for(int n=0;n<50;n++)service.maintain(f.scene.memory.state,queue);assertEquals(1,queue.work.size());
            queue.run();service.maintain(f.scene.memory.state,queue);assertEquals(Code.APPLIED,service.last());assertEquals(2,AccomplishmentLedgerTest.quantity(ledger));assertTrue(queue.work.isEmpty());
        }
    }
    @Test void newSourceRevisionIsObservedWithoutHistoricalTickScan()throws Exception{
        var f=new AccomplishmentLedgerTest.Fixture();f.successful(2);
        try(var ledger=f.open(temp);var service=new AccomplishmentService(ledger)){
            var queue=new Queue();service.maintain(f.scene.memory.state,queue);queue.run();service.maintain(f.scene.memory.state,queue);
            f.successful(2);for(int n=0;n<5;n++){service.maintain(f.scene.memory.state,queue);if(!queue.work.isEmpty())queue.run();}
            assertEquals(4,AccomplishmentLedgerTest.quantity(ledger));assertEquals(2,ledger.snapshot().progress().size());
        }
    }
    @Test void closeFencesQueuedPublicationWithoutCancellingTheJob()throws Exception{
        var f=new AccomplishmentLedgerTest.Fixture();f.successful(2);var before=f.scene.memory.state;
        try(var ledger=f.open(temp)){
            var service=new AccomplishmentService(ledger);var queue=new Queue();service.maintain(before,queue);service.close();queue.run();
            assertEquals(0,AccomplishmentLedgerTest.quantity(ledger));assertEquals(before,f.scene.memory.state);
        }
    }
    @Test void rejectedWorkerSubmissionDoesNotLoseTheSourceReference()throws Exception{
        var f=new AccomplishmentLedgerTest.Fixture();f.successful(2);
        try(var ledger=f.open(temp);var service=new AccomplishmentService(ledger)){
            service.maintain(f.scene.memory.state,task->{throw new RejectedExecutionException();});assertEquals(Code.UNAVAILABLE,service.last());
            var queue=new Queue();service.maintain(f.scene.memory.state,queue);assertEquals(1,queue.work.size());queue.run();assertEquals(2,AccomplishmentLedgerTest.quantity(ledger));
        }
    }
    @Test void transientQuotaFailureRetriesWithBoundedTickBackoff()throws Exception{
        var f=new AccomplishmentLedgerTest.Fixture();f.successful(2);
        try(var ledger=f.open(temp);var service=new AccomplishmentService(ledger)){
            ledger.capacity(a->false);var queue=new Queue();service.maintain(f.scene.memory.state,queue);queue.run();service.maintain(f.scene.memory.state,queue);assertEquals(Code.LIMIT,service.last());
            ledger.capacity(a->true);for(int n=0;n<22;n++){service.maintain(f.scene.memory.state,queue);if(!queue.work.isEmpty())queue.run();}
            assertEquals(2,AccomplishmentLedgerTest.quantity(ledger));
        }
    }
    @Test void ownerThreadFenceRejectsForeignTickCalls()throws Exception{
        var f=new AccomplishmentLedgerTest.Fixture();try(var ledger=f.open(temp);var service=new AccomplishmentService(ledger)){
            var result=CompletableFuture.supplyAsync(()->assertThrows(IllegalStateException.class,()->service.maintain(f.scene.memory.state,Runnable::run)));result.get(5,TimeUnit.SECONDS);
        }
    }
}
