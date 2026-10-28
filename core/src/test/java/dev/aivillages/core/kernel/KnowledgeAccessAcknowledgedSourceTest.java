package dev.aivillages.core.kernel;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.OakLogTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** The server's presence capture may lag the acknowledged journal within one native tick. */
final class KnowledgeAccessAcknowledgedSourceTest {
    static CitizenRegistry.Snapshot citizens() {
        return new CitizenRegistry.Snapshot(OWNER.scope().worldId(),1,List.of(
                new CitizenRegistry.Citizen(ACTOR,OWNER,null,CitizenRegistry.Availability.LOADED)),null);
    }
    @Test void acknowledgementAfterPresenceCaptureStillVerifiesTheOriginalCompletedDelivery() {
        var s=new OakLogJobLifecycleTest.Scene();var active=s.assign(s.create());var stale=s.memory.state;
        var source=new KnowledgeAccessSource(()->s.memory.state,KnowledgeAccessAcknowledgedSourceTest::citizens,()->null);
        source.observe(citizens(),stale,Set.of());var done=s.finish(active,s.receipts(active));
        assertEquals(Jobs.State.ACTIVE,source.job(done.id()).state());
        var work=new JobOutcomeSource.Work(8192);var verified=source.verified(done.id(),work);
        assertEquals(done,verified);assertEquals(done.revision(),verified.revision());
        assertTrue(source.learned(verified,done.current().executions().getFirst().artifact(),ACTOR,work));
        assertFalse(source.present(new KnowledgeAccess.Enrollment(ACTOR,OWNER),new KnowledgeAccess.Enrollment(ACTOR,OWNER)));
    }
    @Test void newerObservedProjectionCannotPromoteAnUnacknowledgedDelivery() {
        var s=new OakLogJobLifecycleTest.Scene();var active=s.assign(s.create());var acknowledged=new AtomicReference<>(s.memory.state);
        var source=new KnowledgeAccessSource(acknowledged::get,KnowledgeAccessAcknowledgedSourceTest::citizens,()->null);
        var done=s.finish(active,s.receipts(active));source.observe(citizens(),s.memory.state,Set.of());
        var work=new JobOutcomeSource.Work(8192);var verified=source.verified(done.id(),work);
        assertEquals(active,verified);assertFalse(source.learned(verified,done.current().executions().getFirst().artifact(),ACTOR,work));
    }
    @Test void acknowledgedTerminalStatusStillRequiresTheOriginalPhysicalReceiptChain() {
        var s=new OakLogJobLifecycleTest.Scene();var active=s.assign(s.create());var done=s.finish(active,s.receipts(active));var a=done.current();
        var forged=new Jobs.Attempt(a.id(),a.generation(),a.worker(),a.bound(),a.allowance(),a.executions(),a.usage(),a.receipts(),a.effects(),a.credited(),a.terminal(),a.uncertain(),a.cancellationDeliveries(),List.of());
        var row=new Jobs.Job(done.id(),done.submissionId(),done.revision(),done.request(),done.parent(),done.dependencies(),done.children(),done.state(),done.reason(),done.cancellationOutcome(),done.allowance(),done.usage(),done.generation(),List.of(forged),done.events(),done.preparation());
        var source=new KnowledgeAccessSource(()->new Jobs.Snapshot(s.memory.state.worldId(),s.memory.state.revision(),List.of(row),List.of()),KnowledgeAccessAcknowledgedSourceTest::citizens,()->null);
        source.observe(citizens(),s.memory.state,Set.of());
        assertThrows(JobOutcomeSource.Invalid.class,()->source.verified(done.id(),new JobOutcomeSource.Work(8192)));
    }
}
