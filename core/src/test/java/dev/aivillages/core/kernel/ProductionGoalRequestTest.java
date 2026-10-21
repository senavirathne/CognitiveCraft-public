package dev.aivillages.core.kernel;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.LanguageRequests.*;
import static org.junit.jupiter.api.Assertions.*;

/** Injected extraction with the real binding owner; not genuine model evidence. */
class ProductionGoalRequestTest {
    static final class Fixture implements Binding {
        final AxeBindingTest.Fixture world=new AxeBindingTest.Fixture();
        final LanguageRequestsTest.Fake port=new LanguageRequestsTest.Fake();
        final LanguageRequests language=new LanguageRequests(port,this,world.identity.clock);
        CapabilityRequest request;int discoveries,submissions;
        Fixture(){language.enabled(true);}
        public List<String> references(TrustedContext caller){return List.of("Ada");}
        public CitizenRegistry.Addresses address(String ref,TrustedContext caller){return world.identity.registry.address(ref,caller);}
        public ResolutionHandle resolve(Intent intent,TrustedContext caller){discoveries++;return new WorldReferenceBinding(world.world,world.identity.registry,caller,
                new WorldReferenceResolver.Point(world.actor.dimension(),0,64,0),intent,world.identity.clock,()->world.identity.current);}
        public BootstrapController.Submission submit(CapabilityRequest r,TrustedContext caller){assertEquals(world.identity.caller,caller);request=r;submissions++;return new BootstrapController.Submission(UUID.randomUUID(),true,null);}
        public void cancel(UUID run,TrustedContext caller){throw new AssertionError("No cancel");}
        View interpret(String text,long amount,String citizen){
            var ticket=language.ask(text,world.identity.caller);port.response.complete(new Result(Kind.EXTRACTED,
                    new Extracted(Action.MAKE_WOODEN_AXES,citizen,amount,null,null,null,null),.95));
            for(int i=0;i<500;i++){language.tick();var v=language.status(ticket.id(),world.identity.caller);
                if(v.phase()!=Phase.INTERPRETING && v.phase()!=Phase.RESOLVING)return v;}
            throw new AssertionError("Unbounded goal resolution");
        }
    }
    @Test void originalBindsExistingAxeProducerWithExactQuantityAndRealReferenceOwner() {
        var f=new Fixture();var v=f.interpret(ProductionGoalLanguageTest.ORIGINAL,3,null);assertEquals(Phase.SUBMITTED,v.phase(),v.toString());
        assertEquals(WoodenAxeProduction.ID,f.request.capability());assertEquals(new IntValue(3),f.request.arguments().get("amount"));
        assertEquals(new ActorValue(f.world.actor),f.request.arguments().get("actor"));
        assertEquals(new ContainerValue(f.world.box(11)),f.request.arguments().get("source"));
        assertEquals(new ContainerValue(f.world.box(14)),f.request.arguments().get("destination"));
        assertEquals(new FacilityValue(f.world.table(12)),f.request.arguments().get("table"));
        var recipe=RecipeProduction.of(f.request.capability());assertEquals(9,recipe.inputs(3));assertEquals(6,recipe.secondaryInputs(3));
    }
    @Test void aDifferentModelAmountCannotReplaceTheRequestedGoal() {
        var f=new Fixture();assertEquals(Phase.CLARIFICATION,f.interpret(ProductionGoalLanguageTest.ORIGINAL,2,null).phase());
        assertEquals(0,f.discoveries);assertEquals(0,f.submissions);
    }
    @Test void invalidGoalQuantityCannotDiscoverOrSubmit() {
        for(String quantity:List.of("0","5","-1","1.5","999999999999999999999")) {
            var f=new Fixture();assertEquals(Phase.REJECTED,f.interpret("We need "+quantity+" wooden axes.",3,null).phase());
            assertEquals(0,f.discoveries);assertEquals(0,f.submissions);
        }
    }
    @Test void unknownAndDuplicateNamedActorsDoNotBecomeOmitted() {
        var f=new Fixture();assertEquals(Phase.CLARIFICATION,f.interpret("Unknown, we need three wooden axes.",3,"Unknown").phase());
        assertEquals(0,f.discoveries);assertEquals(0,f.submissions);
        var duplicate=new Fixture();duplicate.world.identity.add(2,9,0,"Ada",true,true);duplicate.world.identity.open();
        assertEquals(Phase.CLARIFICATION,duplicate.interpret("Ada, we need three wooden axes.",3,"Ada").phase());assertEquals(0,duplicate.submissions);
    }
    @Test void existingStockCueCannotInventSufficientObservedInputsOrMissingFacility() {
        var f=new Fixture();f.world.stock(11,8);var v=f.interpret(ProductionGoalLanguageTest.ORIGINAL,3,null);
        assertEquals(Phase.REJECTED,v.phase());assertEquals(Outcomes.Reason.RESOURCE_MISSING,v.reason());assertEquals(0,f.submissions);
        var missing=new Fixture();missing.world.tables.clear();v=missing.interpret(ProductionGoalLanguageTest.ORIGINAL,3,null);
        assertEquals(Phase.REJECTED,v.phase());assertEquals(Outcomes.Reason.FACILITY_MISSING,v.reason());assertEquals(0,missing.submissions);
    }
    @Test void unknownSourcesAndUnmodeledFollowupsDoNotReachWorldDiscovery() {
        for(String text:List.of("We need three wooden axes from my northern chest.","We need three wooden axes using my stock.",
                "We need three wooden axes. Use available materials. Buy more.")) {
            var f=new Fixture();assertEquals(Phase.CLARIFICATION,f.interpret(text,3,null).phase(),text);
            assertEquals(0,f.discoveries);assertEquals(0,f.submissions);
        }
    }
}
