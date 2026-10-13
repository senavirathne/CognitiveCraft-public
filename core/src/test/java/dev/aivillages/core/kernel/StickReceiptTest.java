package dev.aivillages.core.kernel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class StickReceiptTest {
    StickTestSupport.Driver complete() {var d=new StickTestSupport.Driver(5);d.produce();return d;}
    @Test void exactDuplicateIsIdempotent() {
        var d=complete();var rows=new ArrayList<>(d.receipts);rows.add(d.receipts.get(0));assertEquals(5,TransformationDelivery.quantity(d.bound,d.run,rows));
    }
    @Test void withdrawalAndCraftingAloneAreNotDelivery() {
        var d=StickGatewayTest.withdrawn();d.action(GatewayPrimitives.Operation.CRAFT_STICKS);
        assertEquals(0,TransformationDelivery.quantity(d.bound,d.run,d.receipts));
    }
    @Test void missingIngredientWithdrawalRejectsOutput() {
        var d=complete();assertThrows(IllegalArgumentException.class,()->TransformationDelivery.quantity(d.bound,d.run,d.receipts.subList(1,d.receipts.size())));
    }
    @Test void missingCraftRejectsDeposit() {
        var d=complete();var rows=new ArrayList<>(d.receipts);rows.remove(1);assertThrows(IllegalArgumentException.class,()->TransformationDelivery.quantity(d.bound,d.run,rows));
    }
    @Test void anotherRunCannotClaimOutput() {
        var d=complete();assertThrows(IllegalArgumentException.class,()->TransformationDelivery.quantity(d.bound,UUID.randomUUID(),d.receipts));
    }
    @Test void differentSourceCannotClaimOutput() {
        var d=complete();var args=new HashMap<>(d.bound.request().arguments());args.put("source",d.bound.request().arguments().get("destination"));
        var bound=new Contracts.ValidatedRequest(new Contracts.CapabilityRequest(StickProduction.ID,args),d.bound.context(),d.bound.observation());
        assertThrows(IllegalArgumentException.class,()->TransformationDelivery.quantity(bound,d.run,d.receipts));
    }
    @Test void changedReceiptIdCannotRepeatOnePhysicalOperation() {
        var d=complete();var r=d.receipts.get(0);var copy=new ResourceDelivery.Receipt(UUID.randomUUID(),r.origin(),r.runId(),r.operationId(),r.batchId(),r.actor(),
                r.source(),r.destination(),r.item(),r.stage(),r.quantity(),r.sourceBlock(),r.dropIds(),r.transformation());
        var rows=new ArrayList<>(d.receipts);rows.add(copy);assertThrows(IllegalArgumentException.class,()->TransformationDelivery.quantity(d.bound,d.run,rows));
    }
    @Test void recipeEnvelopeCannotFabricateFiveOutputs() {
        assertThrows(IllegalArgumentException.class,()->new RecipeTransaction.Snapshot(StickProduction.RECIPE,"a".repeat(64),1,2,5,0));
    }
    @Test void changedTransformationCannotReuseReceiptIdentity() {
        var d=complete();var r=d.receipts.get(2);var t=r.transformation();
        var changed=new RecipeTransaction.Transformation(t.recipe(),t.consumedInputs(),t.producedOutputs(),t.remainders(),t.retainedOutputs(),
                t.custodyBefore(),"f".repeat(64),t.sourceIdentity(),t.destinationIdentity());
        var copy=new ResourceDelivery.Receipt(r.receiptId(),r.origin(),r.runId(),r.operationId(),r.batchId(),r.actor(),r.source(),r.destination(),r.item(),
                r.stage(),r.quantity(),r.sourceBlock(),r.dropIds(),changed);
        var rows=new ArrayList<>(d.receipts);rows.add(copy);assertThrows(IllegalArgumentException.class,()->TransformationDelivery.quantity(d.bound,d.run,rows));
    }
    @Test void forgedCustodyBreaksTheConsumedInputChain() {
        var d=complete();var r=d.receipts.get(1);var t=r.transformation();
        var changed=new RecipeTransaction.Transformation(t.recipe(),2,4,0,4,"b".repeat(64),t.custodyAfter(),t.sourceIdentity(),t.destinationIdentity());
        var copy=new ResourceDelivery.Receipt(r.receiptId(),r.origin(),r.runId(),r.operationId(),r.batchId(),r.actor(),r.source(),r.destination(),r.item(),
                r.stage(),r.quantity(),r.sourceBlock(),r.dropIds(),changed);
        var rows=new ArrayList<>(d.receipts);rows.set(1,copy);assertThrows(IllegalArgumentException.class,()->TransformationDelivery.quantity(d.bound,d.run,rows));
    }
    @Test void anotherBatchCannotChangeTheInvocationRecipe() {
        var d=complete();var rows=new ArrayList<>(d.receipts);
        for(int i=3;i<rows.size();i++) {
            var r=rows.get(i);var t=r.transformation();
            var other=new RecipeTransaction.Snapshot(t.recipe().key(),"f".repeat(64),t.recipe().generation()+1,2,4,0);
            var changed=new RecipeTransaction.Transformation(other,t.consumedInputs(),t.producedOutputs(),0,t.retainedOutputs(),
                    t.custodyBefore(),t.custodyAfter(),t.sourceIdentity(),t.destinationIdentity());
            rows.set(i,new ResourceDelivery.Receipt(r.receiptId(),r.origin(),r.runId(),r.operationId(),r.batchId(),r.actor(),r.source(),r.destination(),r.item(),r.stage(),r.quantity(),r.sourceBlock(),r.dropIds(),changed));
        }
        assertThrows(IllegalArgumentException.class,()->TransformationDelivery.quantity(d.bound,d.run,rows));
    }
    @Test void incompleteBatchCannotAuthorizeLaterProduction() {
        var d=complete();var rows=new ArrayList<>(d.receipts);rows.remove(2);
        assertThrows(IllegalArgumentException.class,()->TransformationDelivery.quantity(d.bound,d.run,rows));
    }
    @Test void plankReceiptsCannotClaimStickProduction() {var old=new OakPlankTestSupport.Driver(5);old.produce();assertThrows(IllegalArgumentException.class,()->TransformationDelivery.quantity(StickTestSupport.bound(5),old.run,old.receipts));}
    @Test void stickRecipeRejectsOneInputSnapshot() {assertThrows(IllegalArgumentException.class,()->new RecipeTransaction.Snapshot(StickProduction.RECIPE,"a".repeat(64),1,1,4,0));}
}
