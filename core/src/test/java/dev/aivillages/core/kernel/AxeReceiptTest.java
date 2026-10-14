package dev.aivillages.core.kernel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AxeReceiptTest {
    AxeTestSupport.Driver complete() {var d=new AxeTestSupport.Driver(4);d.produce();return d;}
    @Test void duplicateReceiptIsIdempotent() {var d=complete();var rows=new ArrayList<>(d.receipts);rows.add(rows.getFirst());assertEquals(4,TransformationDelivery.quantity(d.bound,d.run,rows));}
    @Test void missingStickLineageRejects() {var d=complete();var rows=new ArrayList<>(d.receipts);rows.remove(1);assertThrows(IllegalArgumentException.class,()->TransformationDelivery.quantity(d.bound,d.run,rows));}
    @Test void missingPlankLineageRejects() {var d=complete();assertThrows(IllegalArgumentException.class,()->TransformationDelivery.quantity(d.bound,d.run,d.receipts.subList(1,d.receipts.size())));}
    @Test void craftWithoutWithdrawalCannotEarnCredit() {var d=complete();assertThrows(IllegalArgumentException.class,()->TransformationDelivery.quantity(d.bound,d.run,d.receipts.subList(2,d.receipts.size())));}
    @Test void anotherRunCannotClaimOutput() {var d=complete();assertThrows(IllegalArgumentException.class,()->TransformationDelivery.quantity(d.bound,UUID.randomUUID(),d.receipts));}
    @Test void otherTableCannotClaimOutput() {var d=complete();var args=new HashMap<>(d.bound.request().arguments());args.put("table",new Contracts.FacilityValue(new Contracts.FacilityRef(AxeTestSupport.TABLE.dimension(),4,0,4)));var b=new Contracts.ValidatedRequest(new Contracts.CapabilityRequest(WoodenAxeProduction.ID,args),d.bound.context(),d.bound.observation());assertThrows(IllegalArgumentException.class,()->TransformationDelivery.quantity(b,d.run,d.receipts));}
    @Test void anotherIdCannotRepeatOnePhysicalOperation() {var d=complete();var r=d.receipts.getFirst();var rows=new ArrayList<>(d.receipts);rows.add(new ResourceDelivery.Receipt(UUID.randomUUID(),r.origin(),r.runId(),r.operationId(),r.batchId(),r.actor(),r.source(),r.destination(),r.item(),r.stage(),r.quantity(),r.sourceBlock(),List.of(),r.transformation()));assertThrows(IllegalArgumentException.class,()->TransformationDelivery.quantity(d.bound,d.run,rows));}
    @Test void sticksAndPlanksCannotMasqueradeAsAxes() {var d=new StickTestSupport.Driver(4);d.produce();assertThrows(IllegalArgumentException.class,()->TransformationDelivery.quantity(AxeTestSupport.bound(4),d.run,d.receipts));}
    @Test void fiveInputsAndOneOutputAreTheOnlyEnvelope() {assertThrows(IllegalArgumentException.class,()->new RecipeTransaction.Snapshot(WoodenAxeProduction.RECIPE,"a".repeat(64),1,3,1,0));assertThrows(IllegalArgumentException.class,()->new RecipeTransaction.Snapshot(WoodenAxeProduction.RECIPE,"a".repeat(64),1,5,4,0));}
    @Test void incompleteCraftIsZeroDelivery() {var d=AxeProductionTest.withdrawn();d.action(GatewayPrimitives.Operation.CRAFT_WOODEN_AXE);assertEquals(0,TransformationDelivery.quantity(d.bound,d.run,d.receipts));}
}
