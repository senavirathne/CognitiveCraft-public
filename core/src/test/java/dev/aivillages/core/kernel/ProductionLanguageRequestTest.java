package dev.aivillages.core.kernel;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.LanguageRequests.*;
import static org.junit.jupiter.api.Assertions.*;

/** Injected extraction plus the real shared binding owner; not actual-model evidence. */
class ProductionLanguageRequestTest {
    static final String ORIGINAL="Could you turn these two oak logs into seven planks for me?";
    static final class Fixture implements Binding {
        final OakPlankBindingTest.Fixture world=new OakPlankBindingTest.Fixture();
        final LanguageRequestsTest.Fake port=new LanguageRequestsTest.Fake();
        final LanguageRequests language=new LanguageRequests(port,this,world.identity.clock);
        CapabilityRequest request;int discoveries,submissions;
        Fixture(){language.enabled(true);}
        TrustedContext owner(){return world.identity.caller;}
        public List<String> references(TrustedContext caller){return caller.equals(owner()) ? List.of("Ada") : List.of();}
        public CitizenRegistry.Addresses address(String ref,TrustedContext caller){return world.identity.registry.address(ref,caller);}
        public ResolutionHandle resolve(Intent intent,TrustedContext caller){
            discoveries++;
            return new WorldReferenceBinding(world.world,world.identity.registry,caller,
                    new WorldReferenceResolver.Point(world.actor.dimension(),0,64,0),intent,world.identity.clock,()->world.identity.current);
        }
        public BootstrapController.Submission submit(CapabilityRequest r,TrustedContext caller){
            assertEquals(owner(),caller);request=r;submissions++;return new BootstrapController.Submission(UUID.randomUUID(),true,null);
        }
        public void cancel(UUID run,TrustedContext caller){throw new AssertionError("No cancellation");}
        View interpret(String text,long amount,String citizen){
            var ticket=language.ask(text,owner());
            port.response.complete(new Result(Kind.EXTRACTED,new Extracted(Action.MAKE_OAK_PLANKS,citizen,amount,null,null,null,null),.95));
            for(int i=0;i<500;i++) {language.tick();var v=language.status(ticket.id(),owner());
                if(v.phase()!=Phase.INTERPRETING && v.phase()!=Phase.RESOLVING)return v;}
            throw new AssertionError("Unbounded binding");
        }
    }
    @Test void originalBindsSevenOutputsAndTwoRealRequiredInputUnitsThroughExistingProducer() {
        var f=new Fixture();var v=f.interpret(ORIGINAL,7,null);assertEquals(Phase.SUBMITTED,v.phase(),v.toString());
        assertEquals(OakPlankProduction.ID,f.request.capability());assertEquals(new IntValue(7),f.request.arguments().get("amount"));
        assertEquals(new ActorValue(f.world.actor),f.request.arguments().get("actor"));
        assertEquals(new ContainerValue(f.world.box(11)),f.request.arguments().get("source"));
        assertEquals(new ContainerValue(f.world.box(14)),f.request.arguments().get("destination"));
        assertEquals(2,RecipeProduction.of(f.request.capability()).inputs(7));
    }
    @Test void inputAmountOrWholeBatchOutputCannotReplaceRequestedDeliveryQuantity() {
        for(long wrong:List.of(2L,8L)) {
            var f=new Fixture();assertEquals(Phase.CLARIFICATION,f.interpret(ORIGINAL,wrong,null).phase());
            assertEquals(0,f.discoveries);assertEquals(0,f.submissions);
        }
    }
    @Test void inconsistentStatedInputsCauseNoWorldDiscoveryOrSubmission() {
        for(String text:List.of(ORIGINAL.replace("two oak","one oak"),ORIGINAL.replace("two oak","three oak"))) {
            var f=new Fixture();assertEquals(Phase.CLARIFICATION,f.interpret(text,7,null).phase());
            assertEquals(0,f.discoveries);assertEquals(0,f.submissions);
        }
    }
    @Test void invalidInputAndOutputScalarsAreRejectedBeforeDiscovery() {
        for(String bad:List.of("0","65","7.5","-2","999999999999999999999")) {
            for(boolean ingredient:List.of(true,false)) {
                String text=ORIGINAL.replace(ingredient?"two oak":"seven planks",bad+(ingredient?" oak":" planks"));
                var f=new Fixture();assertEquals(Phase.REJECTED,f.interpret(text,7,null).phase(),text);
                assertEquals(0,f.discoveries);assertEquals(0,f.submissions);
            }
        }
    }
    @Test void observedShortageRemainsAResourceFailureWithNoSubmissionOrInventedStock() {
        var f=new Fixture();f.world.stock(11,1);var before=java.util.Map.copyOf(f.world.inputs);
        var v=f.interpret(ORIGINAL,7,null);assertEquals(Phase.REJECTED,v.phase());assertEquals(Outcomes.Reason.RESOURCE_MISSING,v.reason());
        assertEquals(before,f.world.inputs);assertEquals(0,f.submissions);
    }
    @Test void explicitUnknownActorsAndQualifiedSourcesRemainAmbiguousDespiteTransformationWording() {
        var unknown=new Fixture();assertEquals(Phase.CLARIFICATION,unknown.interpret("Unknown, convert two oak logs into seven planks.",7,"Unknown").phase());
        assertEquals(0,unknown.discoveries);assertEquals(0,unknown.submissions);
        var polite=new Fixture();assertEquals(Phase.CLARIFICATION,polite.interpret("Unknown, could you turn two oak logs into seven planks?",7,"Unknown").phase());
        assertEquals(0,polite.discoveries);assertEquals(0,polite.submissions);
        var duplicate=new Fixture();duplicate.world.identity.add(2,9,0,"Ada",true,true);duplicate.world.identity.open();
        assertEquals(Phase.CLARIFICATION,duplicate.interpret("Ada, turn two oak logs into seven planks.",7,"Ada").phase());
        assertEquals(0,duplicate.discoveries);assertEquals(0,duplicate.submissions);
        var qualified=new Fixture();
        assertEquals(Phase.CLARIFICATION,qualified.interpret("Ada, turn two oak logs from this chest in my house into seven planks and put them in that barrel.",7,"Ada").phase());
        assertEquals(0,qualified.discoveries);assertEquals(0,qualified.submissions);
    }
}
