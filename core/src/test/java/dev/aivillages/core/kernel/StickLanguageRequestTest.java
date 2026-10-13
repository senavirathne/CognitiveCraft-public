package dev.aivillages.core.kernel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.LanguageRequests.*;
import static org.junit.jupiter.api.Assertions.*;

/** Extraction, quantity grounding, private addresses, world resolution and canonical dispatch together. */
class StickLanguageRequestTest {
    static final String TEXT="Ada, make 8 sticks from the available oak planks and store them.";
    static final class Fixture implements Binding {
        final StickBindingTest.Fixture world=new StickBindingTest.Fixture();
        final LanguageRequestsTest.Fake port=new LanguageRequestsTest.Fake();
        final LanguageRequests language=new LanguageRequests(port,this,world.identity.clock);
        CapabilityRequest request;int submissions;
        Fixture(){language.enabled(true);}
        TrustedContext owner(){return world.identity.caller;}
        public List<String> references(TrustedContext caller){return caller.equals(owner()) ? List.of("Ada",world.actor.citizenId().toString()) : List.of();}
        public CitizenRegistry.Addresses address(String ref,TrustedContext caller){
            try{return world.identity.registry.address(UUID.fromString(ref),caller);}
            catch(IllegalArgumentException name){return world.identity.registry.address(ref,caller);}
        }
        public ResolutionHandle resolve(Intent intent,TrustedContext caller){
            return new WorldReferenceBinding(world.world,world.identity.registry,caller,new WorldReferenceResolver.Point(world.actor.dimension(),0,64,0),
                    intent,world.identity.clock,()->world.identity.current);
        }
        public BootstrapController.Submission submit(CapabilityRequest r,TrustedContext caller){assertEquals(owner(),caller);request=r;submissions++;return new BootstrapController.Submission(UUID.randomUUID(),true,null);}
        public void cancel(UUID run,TrustedContext caller){throw new AssertionError("No canonical cancellation expected in this fixture");}
        View interpret(String text,long amount){
            var ticket=language.ask(text,owner());port.response.complete(new Result(Kind.EXTRACTED,new Extracted(Action.MAKE_STICKS,"Ada",amount,null,null,null,null),.95));
            for(int i=0;i<500;i++){language.tick();world.identity.registry.tick();var v=language.status(ticket.id(),owner());if(v.phase()!=Phase.INTERPRETING && v.phase()!=Phase.RESOLVING)return v;}
            throw new AssertionError("Unbounded language resolution");
        }
    }
    @Test void requiredPhraseDispatchesTheIndependentTypedRequest(){
        var f=new Fixture();var result=f.interpret(TEXT,8);assertEquals(Phase.SUBMITTED,result.phase(),result.toString());assertEquals(1,f.submissions);
        assertEquals(StickProduction.ID,f.request.capability());assertEquals(new IntValue(8),f.request.arguments().get("amount"));
        assertEquals(new ContainerValue(f.world.box(11)),f.request.arguments().get("source"));assertEquals(new ContainerValue(f.world.box(14)),f.request.arguments().get("destination"));
    }
    @Test void numberWordIsGroundedWithoutInventingAnAmount(){var f=new Fixture();assertEquals(Phase.SUBMITTED,f.interpret(TEXT.replace("8 sticks","eight sticks"),8).phase());}
    @Test void extractedDifferentQuantityCannotDispatch(){var f=new Fixture();assertEquals(Phase.CLARIFICATION,f.interpret(TEXT,9).phase());assertEquals(0,f.submissions);}
    @Test void invalidExplicitProductionAmountsCannotDispatch(){
        for(String amount:List.of("0","65","8.5","999999999999999999999")){var f=new Fixture();assertEquals(Phase.REJECTED,f.interpret(TEXT.replace("8 sticks",amount+" sticks"),8).phase());assertEquals(0,f.submissions);}
    }
    @Test void duplicatePrivateNameRequiresExplicitCitizenSelection(){
        var f=new Fixture();f.world.identity.add(2,9,0,"Ada",true,true);f.world.identity.open();
        assertEquals(Phase.CLARIFICATION,f.interpret(TEXT,8).phase());assertEquals(0,f.submissions);
    }
}
