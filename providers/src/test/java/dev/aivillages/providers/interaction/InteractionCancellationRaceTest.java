package dev.aivillages.providers.interaction;

import dev.aivillages.core.kernel.interaction.ResponseFacts;
import dev.aivillages.providers.LlmProvider;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Observable cancellation contract under a deliberately forced backend-return interleaving. */
class InteractionCancellationRaceTest {
    @Test void cancellationBeforeCallbackAttachmentRetainsTheSlotUntilActualCompletionIsConsumed() throws Exception {
        int before=ConversationProvider.outstandingOperations();
        var entered=new CountDownLatch(1);var allowReturn=new CountDownLatch(1);var response=new CompletableFuture<LlmProvider.Response>();
        var timer=Executors.newSingleThreadScheduledExecutor();
        LlmProvider backend=new LlmProvider(){
            public String id(){return "C07-synthetic-cancellation-race";}
            public CompletableFuture<Response> generate(Request request){
                entered.countDown();
                try {if(!allowReturn.await(3,TimeUnit.SECONDS))throw new IllegalStateException("test backend return not released");}
                catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
                return response;
            }
        };
        try {
            var budget=new ConversationProvider.TokenBudget("a".repeat(64),"b".repeat(64),request->1);
            var provider=new ConversationProvider(backend,budget,timer);
            var ticket=new ConversationProvider.Ticket(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,UUID.randomUUID());
            var result=provider.respond(ticket,ResponseFacts.smallTalk("en"),ignored->true);
            assertTrue(entered.await(2,TimeUnit.SECONDS));assertTrue(provider.cancel(ticket));
            var cancelled=result.get(1,TimeUnit.SECONDS);
            assertEquals(ConversationProvider.Reason.CANCELLED,cancelled.reason());assertFalse(cancelled.deliverable());assertEquals("",cancelled.text());
            response.complete(new LlmProvider.Response("Done, delivered 64 diamonds.",backend.id(),"synthetic"));
            // Completion has been signalled, but generate() cannot yet return for callback attachment.
            assertEquals(before+1,ConversationProvider.outstandingOperations());
            allowReturn.countDown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(ConversationProvider.outstandingOperations()!=before && System.nanoTime()<deadline)Thread.sleep(1);
            assertEquals(before,ConversationProvider.outstandingOperations());
            assertFalse(cancelled.computeTerminationConfirmed());assertEquals("",result.join().text());
        } finally {allowReturn.countDown();response.complete(new LlmProvider.Response("invalid",backend.id(),"synthetic"));timer.shutdownNow();}
    }
}
