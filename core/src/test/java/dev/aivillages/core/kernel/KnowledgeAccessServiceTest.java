package dev.aivillages.core.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.KnowledgeAccess.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeAccessServiceTest {
    @TempDir Path temp;
    @Test void gameThreadOnlyQueuesOneImmutableRecordAndNeverPublishesSynchronously()throws Exception{
        try(var f=new KnowledgeAccessStoreTest.Fixture(temp);var service=new KnowledgeAccessService(f.access)){
            var job=f.complete(f.assign(f.start()));var before=f.access.snapshot();var queue=new AccomplishmentServiceTest.Queue();
            for(int n=0;n<10;n++){service.maintain(f.f.scene.memory.state,queue);if(service.pending())break;}
            assertEquals(1,queue.work.size());assertEquals(before,f.access.snapshot());
            for(int n=0;n<50;n++)service.maintain(f.f.scene.memory.state,queue);assertEquals(1,queue.work.size());
            queue.run();for(int n=0;n<5;n++){service.maintain(f.f.scene.memory.state,queue);if(!queue.work.isEmpty())queue.run();}
            assertTrue(f.access.mayUse(f.learner,KnowledgeAccessStoreTest.METHOD,f.f.scene.owner));assertEquals(Code.DUPLICATE,f.finish(job).code());
        }
    }
    @Test void closingFencesLateCompletionAndLeavesActualJobOutcomeAlone()throws Exception{
        try(var f=new KnowledgeAccessStoreTest.Fixture(temp)){
            var job=f.complete(f.assign(f.start()));var before=f.f.scene.memory.state;var service=new KnowledgeAccessService(f.access);var queue=new AccomplishmentServiceTest.Queue();
            for(int n=0;n<5&&!service.pending();n++)service.maintain(before,queue);service.close();queue.run();
            assertTrue(f.access.snapshot().grants().isEmpty());assertEquals(before,f.f.scene.memory.state);assertEquals(Jobs.State.SUCCEEDED,job.state());
        }
    }
    @Test void refusedWorkerSubmissionRetriesAndForeignThreadCannotOwnIntake()throws Exception{
        try(var f=new KnowledgeAccessStoreTest.Fixture(temp);var service=new KnowledgeAccessService(f.access)){
            f.complete(f.assign(f.start()));for(int n=0;n<3;n++)service.maintain(f.f.scene.memory.state,task->{throw new RejectedExecutionException();});
            assertEquals(Code.UNAVAILABLE,service.last());var queue=new AccomplishmentServiceTest.Queue();
            for(int n=0;n<3&&!service.pending();n++)service.maintain(f.f.scene.memory.state,queue);assertEquals(1,queue.work.size());
            CompletableFuture.runAsync(()->assertThrows(IllegalStateException.class,()->service.maintain(f.f.scene.memory.state,queue))).get(5,TimeUnit.SECONDS);
        }
    }
    @Test void executorFencesSupervisionLossBeforePollingAPendingPrimitiveAndKeepsItsPartialReceipt(){
        var available=new AtomicBoolean(true);
        AttemptEligibilityPolicy knowledge=new AttemptEligibilityPolicy(){
            public boolean mayUse(ActorRef a,ArtifactRef r,TrustedContext c){return false;}
            public boolean mayUseInRun(ValidatedRequest request,RunCorrelation run,ArtifactRef method){return available.get();}
            public boolean mayContinue(ValidatedRequest request,RunCorrelation run,Set<ArtifactRef> pinned){return available.get();}
        };
        var f=new ExecutorJUnitTest.Fixture(knowledge);
        var artifact=f.rootDirect(List.of(ExecutorJUnitTest.primitive(GatewayPrimitives.Operation.HARVEST_WHEAT,"harvested"),
                ExecutorJUnitTest.result(ExecutorJUnitTest.param("amount"))),AdmissionStatus.ADMITTED);
        var run=((BoundedSkillExecutor.Started)f.start(artifact,f.limits(100,20,500))).run();
        f.gateway.stall=true;f.gateway.cancelPartial=true;run.tick(ExecutorJUnitTest.OWNER);assertEquals(0,f.gateway.polls);
        available.set(false);var progress=run.tick(ExecutorJUnitTest.OWNER);
        assertEquals(ExecutionStatus.BLOCKED,progress.summary().outcome().status());assertEquals(Reason.KNOWLEDGE_REQUIRED,progress.summary().outcome().reason());
        assertEquals(0,f.gateway.polls);assertEquals(1,f.gateway.cancellation);assertEquals(1,progress.summary().committedEffects());
        run.tick(ExecutorJUnitTest.OWNER);assertEquals(1,f.gateway.cancellation);
    }
}
