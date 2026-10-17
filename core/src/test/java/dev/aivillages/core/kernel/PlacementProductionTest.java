package dev.aivillages.core.kernel;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;

final class PlacementProductionTest {
    @Test void actualPairedLineageCompletesOnePlacement(){var d=new PlacementTestSupport.Driver();d.place();assertEquals(2,d.world.source);assertEquals(7,d.world.held);assertTrue(d.world.occupied);assertEquals(2,d.effects);assertEquals(1,ResourceDelivery.quantity(d.bound,d.run,d.receipts));}
    @Test void withdrawalAloneEarnsZero(){var d=new PlacementTestSupport.Driver();d.prepare();assertEquals(0,ResourceDelivery.quantity(d.bound,d.run,d.receipts));assertFalse(ResourceDelivery.completed(d.bound,d.run,List.of(),d.receipts));}
    @Test void oldActorStockIsPreserved(){var d=new PlacementTestSupport.Driver();d.world.held=42;d.place();assertEquals(42,d.world.held);}
    @Test void missingItemHasNoEffect(){var d=new PlacementTestSupport.Driver();d.world.source=0;assertEquals(Reason.RESOURCE_MISSING,d.action(OBSERVE_TABLE_SITE).reason());assertEquals(0,d.effects);assertFalse(d.world.occupied);}
    @Test void unknownSiteHasNoEffect(){var d=new PlacementTestSupport.Driver();d.world.known=false;assertEquals(Reason.STALE_OBSERVATION,d.action(OBSERVE_TABLE_SITE).reason());assertEquals(0,d.effects);}
    @Test void occupiedSiteHasNoEffect(){var d=new PlacementTestSupport.Driver();d.world.occupied=true;assertEquals(Reason.TARGET_INVALID,d.action(OBSERVE_TABLE_SITE).reason());assertEquals(3,d.world.source);}
    @Test void unsupportedSiteHasNoEffect(){var d=new PlacementTestSupport.Driver();d.world.unsupported=true;assertEquals(Reason.TARGET_INVALID,d.action(OBSERVE_TABLE_SITE).reason());assertEquals(0,d.effects);}
    @Test void replacementAfterWithdrawalRetainsActualItem(){var d=new PlacementTestSupport.Driver();d.prepare();d.world.changed=true;assertEquals(Reason.STALE_OBSERVATION,d.action(PLACE_CRAFTING_TABLE).reason());assertEquals(8,d.world.held);assertFalse(d.world.occupied);assertEquals(0,ResourceDelivery.quantity(d.bound,d.run,d.receipts));}
    @Test void revocationAfterWithdrawalHasNoPlacement(){var d=new PlacementTestSupport.Driver();d.prepare();d.world.authorized=false;assertEquals(Reason.AUTHORITY_DENIED,d.action(PLACE_CRAFTING_TABLE).reason());assertEquals(8,d.world.held);}
    @Test void cancellationReleaseNeverRefundsWithdrawal(){var d=new PlacementTestSupport.Driver();d.prepare();d.gateway.releaseRun(d.run);assertEquals(2,d.world.source);assertEquals(8,d.world.held);assertFalse(d.world.occupied);}
    @Test void repeatedPlacementCannotConsumeAgain(){var d=new PlacementTestSupport.Driver();d.place();assertNotNull(d.action(PLACE_CRAFTING_TABLE).reason());assertEquals(7,d.world.held);assertEquals(2,d.effects);}
    @Test void inconsistentNativeEffectCountsButCannotComplete(){var d=new PlacementTestSupport.Driver();d.prepare();d.world.inconsistent=true;assertEquals(Reason.ACTION_FAILED,d.action(PLACE_CRAFTING_TABLE).reason());assertTrue(d.world.occupied);assertEquals(2,d.effects);assertEquals(0,ResourceDelivery.quantity(d.bound,d.run,d.receipts));}
    @Test void identicalReceiptReplayIsIdempotent(){var d=new PlacementTestSupport.Driver();d.place();var receipts=new ArrayList<>(d.receipts);receipts.addAll(d.receipts);assertEquals(1,ResourceDelivery.quantity(d.bound,d.run,receipts));}
    @Test void crossRunEvidenceRejected(){var d=new PlacementTestSupport.Driver();d.place();assertThrows(IllegalArgumentException.class,()->ResourceDelivery.quantity(d.bound,UUID.randomUUID(),d.receipts));}
    @Test void outOfOrderEvidenceRejected(){var d=new PlacementTestSupport.Driver();d.place();var r=new ArrayList<>(d.receipts);Collections.reverse(r);assertThrows(IllegalArgumentException.class,()->ResourceDelivery.quantity(d.bound,d.run,r));}
    @ParameterizedTest @ValueSource(ints={-1,0,2,4,Integer.MAX_VALUE}) void directPlaceIsExactlyOne(int q){assertFalse(CraftingTablePlacement.valid(PlacementTestSupport.bound(q).request()));}
    @Test void newMethodCompilesWithOnlyPlacementPrimitives(){assertInstanceOf(SkillCompiler.Success.class,new SkillCompiler(PlanningGoals.catalog(),GatewayPrimitives.instance(),r->Optional.empty()).compile(PlacementTestSupport.program()));}
    @Test void recipePrimitiveCannotEnterPlacement(){assertInstanceOf(SkillCompiler.Failure.class,new SkillCompiler(PlanningGoals.catalog(),GatewayPrimitives.instance(),r->Optional.empty()).compile(PlacementTestSupport.program().replace(PLACE_CRAFTING_TABLE.signature().id(),CRAFT_CRAFTING_TABLE.signature().id())));}
    @Test void facilityAndItemProjectionsCannotCycle(){var source=new ContainerValue(PlacementTestSupport.SOURCE);var root=new CapabilityRequest(WoodenAxeProduction.ID,Map.of("actor",new ActorValue(PlacementTestSupport.ACTOR),"source",source,"destination",source,"table",new FacilityValue(PlacementTestSupport.TARGET),"amount",new IntValue(1)));assertEquals(CraftingTableProduction.ID,FacilityPrerequisites.item(root).capability());assertFalse(FacilityPrerequisites.item(root).arguments().containsKey("table"));assertEquals(CraftingTablePlacement.ID,FacilityPrerequisites.placement(root).capability());}
}
