package dev.aivillages.core.kernel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;
class AxeProductionTest {
    @Test void oneAndFourConsumeBothExactIngredients() {assertEquals(3,WoodenAxeProduction.planks(1));assertEquals(2,WoodenAxeProduction.sticks(1));assertEquals(12,WoodenAxeProduction.planks(4));assertEquals(8,WoodenAxeProduction.sticks(4));}
    @Test void zeroRejects() {assertThrows(IllegalArgumentException.class,()->WoodenAxeProduction.batches(0));}
    @Test void fiveRejects() {assertThrows(IllegalArgumentException.class,()->WoodenAxeProduction.batches(5));}
    @Test void negativeRejects() {assertThrows(IllegalArgumentException.class,()->WoodenAxeProduction.batches(-1));}
    @Test void arithmeticOverflowCannotBecomeAValidRequest() {assertThrows(IllegalArgumentException.class,()->WoodenAxeProduction.planks(Long.MAX_VALUE));}
    @Test void typedTableRoundTripIsNotAContainer() {var r=AxeTestSupport.bound(4).request();assertEquals(r,((RequestCodec.Valid)RequestCodec.decode(RequestCodec.encode(r))).request());assertEquals(Type.FACILITY,r.arguments().get("table").type());}
    @Test void oneCreatesOneNewAxeWithoutGathering() {var d=new AxeTestSupport.Driver(1);d.produce();assertEquals(1,d.world.chestAxes);assertEquals(9,d.world.logs);assertEquals(6,d.world.sticks);assertEquals(3,d.effects);assertEquals(4,d.receipts.size());assertTrue(WoodenAxeProduction.completed(d.bound,d.run,d.receipts));}
    @Test void maximumProducesFourAndKeepsOldAxes() {var w=new AxeTestSupport.World();w.actorAxes=2;w.chestAxes=3;var d=new AxeTestSupport.Driver(4,w);d.produce();assertEquals(7,w.chestAxes);assertEquals(2,w.actorAxes);assertEquals(0,w.logs);assertEquals(0,w.sticks);assertEquals(16,d.receipts.size());assertEquals(12,d.effects);}
    @Test void oldAxesAloneCannotEarnManufactureCredit() {var w=new AxeTestSupport.World();w.actorAxes=4;var d=new AxeTestSupport.Driver(4,w);assertEquals(Reason.REQUEST_INVALID,d.action(TRANSFER_WOODEN_AXES).reason());assertEquals(4,w.actorAxes);assertEquals(0,d.effects);}
    @Test void missingPrimaryHasZeroEffects() {var w=new AxeTestSupport.World();w.logs=2;var d=new AxeTestSupport.Driver(1,w);assertEquals(Reason.RESOURCE_MISSING,d.action(OBSERVE_AXE_INPUTS).reason());assertEquals(0,d.effects);}
    @Test void missingSecondaryHasZeroEffects() {var w=new AxeTestSupport.World();w.sticks=1;var d=new AxeTestSupport.Driver(1,w);assertEquals(Reason.RESOURCE_MISSING,d.action(OBSERVE_AXE_INPUTS).reason());assertEquals(12,w.logs);assertEquals(0,d.effects);}
    @Test void unknownSupplyIsNotZeroStock() {var w=new AxeTestSupport.World();w.known=false;var d=new AxeTestSupport.Driver(1,w);assertEquals(Reason.STALE_OBSERVATION,d.action(OBSERVE_AXE_INPUTS).reason());}
    @Test void fourRequireFourOutputSlotsBeforeWithdrawal() {var w=new AxeTestSupport.World();w.outputCapacity=3;var d=new AxeTestSupport.Driver(4,w);assertEquals(Reason.TARGET_UNAVAILABLE,d.action(OBSERVE_AXE_INPUTS).reason());assertEquals(0,w.withdrawals);}
    static AxeTestSupport.Driver withdrawn() {var d=new AxeTestSupport.Driver(1);d.require(d.action(OBSERVE_AXE_INPUTS));d.require(d.action(MOVE_TO_INPUT));d.require(d.action(WITHDRAW_AXE_INPUTS));d.require(d.action(MOVE_TO_TABLE));return d;}
    @Test void cancellationAfterWithdrawalRetainsBothIngredients() {var d=withdrawn();d.gateway.releaseRun(d.run);assertEquals(3,d.world.actorPlanks);assertEquals(2,d.world.actorSticks);assertEquals(0,d.world.crafts);}
    @Test void cancellationAfterCraftRetainsAxe() {var d=withdrawn();d.require(d.action(CRAFT_WOODEN_AXE));d.gateway.releaseRun(d.run);assertEquals(1,d.world.actorAxes);assertEquals(0,d.world.chestAxes);}
    @Test void authorityRevocationFencesNextCraft() {var d=withdrawn();d.world.authorized=false;assertEquals(Reason.AUTHORITY_DENIED,d.action(CRAFT_WOODEN_AXE).reason());assertEquals(3,d.world.actorPlanks);}
    @Test void recipeDriftPreservesInputs() {var d=withdrawn();d.world.recipeChanged=true;assertEquals(Reason.ARTIFACT_INCOMPATIBLE,d.action(CRAFT_WOODEN_AXE).reason());assertEquals(2,d.world.actorSticks);}
    @Test void custodyContaminationPreservesInputs() {var d=withdrawn();d.world.other++;assertEquals(Reason.STALE_OBSERVATION,d.action(CRAFT_WOODEN_AXE).reason());assertEquals(0,d.world.crafts);}
    @Test void destinationReplacementCannotEarnCredit() {var d=withdrawn();d.require(d.action(CRAFT_WOODEN_AXE));d.world.targetChanged=true;assertEquals(Reason.STALE_OBSERVATION,d.action(TRANSFER_WOODEN_AXES).reason());assertEquals(1,d.world.actorAxes);}
    @Test void stockRelationshipIsDataForTwoIngredients() {var r=RecipeProduction.of(WoodenAxeProduction.ID).relationship();assertEquals(1,r.outputPerBatch());assertEquals(2,r.inputs().size());assertEquals(3,r.inputs().getFirst().quantity());assertEquals(2,r.inputs().getLast().quantity());}
    @Test void languageClassifiesGroundedAxeIntent() {var c=LanguageReferenceClassifier.classify("Ada, make one wooden axe using available materials and store it in the nearest chest",List.of("Ada"));assertEquals(LanguageReferenceClassifier.Intent.MAKE_WOODEN_AXES,c.intent());}
    @Test void tableCoordinatesComeFromRawProvenance() {var c=LanguageReferenceClassifier.classify("Ada, make one wooden axe at table 2,0,2 and store it in the nearest chest",List.of("Ada"));assertEquals("2,0,2",c.table().first());}
}
