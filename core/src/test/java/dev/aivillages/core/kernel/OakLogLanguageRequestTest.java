package dev.aivillages.core.kernel;

import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.LanguageRequests.*;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic binding tests with explicitly injected extraction; these are not model evidence. */
class OakLogLanguageRequestTest {
    static final String TEXT="Bea, fetch seven oak logs from 4,64,0 through 10,64,0 and put them into the barrel at 13,64,0.";
    static final class Fixture extends LanguageRequestsTest.Fixture {
        Fixture() {
            visible=List.of(new CitizenRegistry.Address(actor,"Bea",CitizenRegistry.Availability.LOADED));
        }
        View interpret(String text,long amount) {
            return complete(ask(text),new Extracted(Action.GATHER_OAK_LOGS,"Bea",amount,"4,64,0","10,64,0","13,64,0",null));
        }
    }
    @Test void deliveryParaphraseHandsExactOakParametersToCanonicalSubmission() {
        var f=new Fixture();assertEquals(Phase.SUBMITTED,f.interpret(TEXT,7).phase());
        assertEquals(1,f.submissions);assertEquals(f.owner,f.attached);assertEquals(OakLogDelivery.ID,f.request.capability());
        assertEquals(new ActorValue(f.actor),f.request.arguments().get("actor"));
        assertEquals(new IntValue(7),f.request.arguments().get("amount"));
        assertEquals(new AreaValue(new Cuboid(f.actor.dimension(),4,64,0,10,64,0)),f.request.arguments().get("source"));
        assertEquals(new ContainerValue(new ContainerRef(f.actor.dimension(),13,64,0)),f.request.arguments().get("destination"));
    }
    @Test void unsupportedModifiersCannotBecomeAnOmittedOrInventedDestination() {
        for(String target:List.of("that barrel behind my house","this chest beside my farm","my barrel")) {
            var f=new Fixture();
            var ticket=f.ask("Bea, bring seven oak logs over to "+target+".");
            var result=f.complete(ticket,new Extracted(Action.GATHER_OAK_LOGS,"Bea",7L,null,null,null,null));
            assertEquals(Phase.CLARIFICATION,result.phase());assertEquals(0,f.submissions);assertEquals(0,f.discoveries);
        }
    }
    @Test void invalidOrUngroundedQuantityCausesNoResolutionOrSubmission() {
        for(String quantity:List.of("0","65","7.5","999999999999999999999","six")) {
            var f=new Fixture();var result=f.interpret(TEXT.replace("seven oak",quantity+" oak"),7);
            assertTrue(result.phase()==Phase.REJECTED || result.phase()==Phase.CLARIFICATION);
            assertEquals(0,f.submissions);assertEquals(0,f.discoveries);
        }
    }
    @Test void explicitUnknownActorAndDuplicateActorCannotDefaultToAnotherCitizen() {
        var unknown=new Fixture();assertEquals(Phase.CLARIFICATION,unknown.interpret(TEXT.replace("Bea,","Unknown,"),7).phase());
        assertEquals(0,unknown.submissions);
        var duplicate=new Fixture();var other=new ActorRef(UUID.randomUUID(),UUID.randomUUID(),duplicate.actor.dimension());
        duplicate.visible=List.of(duplicate.visible.getFirst(),new CitizenRegistry.Address(other,"Bea",CitizenRegistry.Availability.LOADED));
        assertEquals(Phase.CLARIFICATION,duplicate.interpret(TEXT,7).phase());assertEquals(0,duplicate.submissions);
    }
}
