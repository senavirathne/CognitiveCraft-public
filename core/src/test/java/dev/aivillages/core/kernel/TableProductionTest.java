package dev.aivillages.core.kernel;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;
import static org.junit.jupiter.api.Assertions.*;

class TableProductionTest {
    @ParameterizedTest @ValueSource(ints={1,4}) void suppliedPlanksMakeExactlyTheRequestedNewTables(int amount) {
        var d=new TableTestSupport.Driver(amount);d.produce();
        assertEquals(amount,d.world.chestTables);assertEquals(32-4*amount,d.world.planks);
        assertEquals(0,d.world.actorTables);assertEquals(amount*3,d.effects);
        assertEquals(amount*3,d.receipts.size());assertTrue(CraftingTableProduction.completed(d.bound,d.run,d.receipts));
    }
    @ParameterizedTest @ValueSource(longs={-1,0,5,Long.MAX_VALUE}) void invalidAmountsCannotWrapOrClamp(long amount) {
        assertThrows(IllegalArgumentException.class,()->CraftingTableProduction.inputs(amount));
    }
    @Test void tableItemHasNoFacilityOrProducerDependency() {
        assertEquals(4,CraftingTableProduction.SPEC.parameters().size());
        assertFalse(CraftingTableProduction.SPEC.parameters().stream().anyMatch(p->p.type()==Type.FACILITY));
        assertEquals(16,CraftingTableProduction.inputs(4));
        var result=new SkillCompiler(PlanningGoals.catalog(),GatewayPrimitives.instance(),ref->Optional.empty()).compile(TableTestSupport.program());
        var artifact=assertInstanceOf(SkillCompiler.Success.class,result).skill().artifact();
        assertTrue(artifact.descriptor().dependencies().isEmpty());assertEquals(CraftingTableProduction.ID,artifact.descriptor().ref().capability());
    }
    @Test void wrongRecipePrimitivesCannotEnterTableArtifact() {
        String program=TableTestSupport.program().replace("cognitivecraft:craft_crafting_table","cognitivecraft:craft_sticks")
                .replace(CRAFT_CRAFTING_TABLE.signature().fingerprint(),CRAFT_STICKS.signature().fingerprint());
        assertFalse(new SkillCompiler(PlanningGoals.catalog(),GatewayPrimitives.instance(),r->Optional.empty()).compile(program) instanceof SkillCompiler.Success);
    }
    @Test void existingTablesCannotProveMake() {
        var world=new TableTestSupport.World();world.actorTables=7;world.chestTables=11;var d=new TableTestSupport.Driver(1,world);
        assertEquals(Reason.REQUEST_INVALID,d.action(TRANSFER_CRAFTING_TABLES).reason());assertEquals(0,d.effects);
        d.produce();assertEquals(7,world.actorTables);assertEquals(12,world.chestTables);
    }
    @Test void shortageAndUnknownStockHaveNoEffects() {
        var world=new TableTestSupport.World();world.planks=3;var d=new TableTestSupport.Driver(1,world);
        assertEquals(Reason.RESOURCE_MISSING,d.action(OBSERVE_TABLE_INPUTS).reason());assertEquals(0,d.effects);
        world.known=false;var unknown=new TableTestSupport.Driver(1,world);
        assertEquals(Reason.STALE_OBSERVATION,unknown.action(OBSERVE_TABLE_INPUTS).reason());assertEquals(0,world.withdrawals);
    }
    static TableTestSupport.Driver withdrawn(int amount) {
        var d=new TableTestSupport.Driver(amount);assertNull(d.action(OBSERVE_TABLE_INPUTS).reason());
        assertNull(d.action(MOVE_TO_INPUT).reason());assertNull(d.action(WITHDRAW_TABLE_PLANKS).reason());return d;
    }
    @Test void revocationAfterWithdrawalKeepsFourRealInputs() {
        var d=withdrawn(1);d.world.authorized=false;assertEquals(Reason.AUTHORITY_DENIED,d.action(CRAFT_CRAFTING_TABLE).reason());
        assertEquals(4,d.world.actorPlanks);assertEquals(0,d.world.actorTables);
    }
    @Test void reloadAndCustodyMutationFenceCraft() {
        var d=withdrawn(1);d.world.recipeChanged=true;assertEquals(Reason.ARTIFACT_INCOMPATIBLE,d.action(CRAFT_CRAFTING_TABLE).reason());assertEquals(4,d.world.actorPlanks);
        d.world.recipeChanged=false;d.world.other++;assertEquals(Reason.STALE_OBSERVATION,d.action(CRAFT_CRAFTING_TABLE).reason());
    }
    @Test void cancellationAfterCraftRetainsTheActualTableWithoutCompletion() {
        var d=withdrawn(1);assertNull(d.action(CRAFT_CRAFTING_TABLE).reason());d.gateway.releaseRun(d.run);
        assertEquals(1,d.world.actorTables);assertEquals(0,d.world.chestTables);assertFalse(CraftingTableProduction.completed(d.bound,d.run,d.receipts));
    }
    @Test void lostCapacityAfterPartialDeliveryKeepsOutputAndExactCredit() {
        var d=withdrawn(4);d.action(CRAFT_CRAFTING_TABLE);d.action(MOVE_TO_DESTINATION);d.action(TRANSFER_CRAFTING_TABLES);
        d.action(MOVE_TO_INPUT);d.action(WITHDRAW_TABLE_PLANKS);d.action(CRAFT_CRAFTING_TABLE);d.world.outputCapacity=0;
        assertEquals(Reason.TARGET_UNAVAILABLE,d.action(TRANSFER_CRAFTING_TABLES).reason());
        assertEquals(1,d.world.actorTables);assertEquals(1,d.world.chestTables);assertEquals(1,TransformationDelivery.quantity(d.bound,d.run,d.receipts));
    }
    @Test void duplicateReceiptsAreIdempotentAndChangedOperationsRejected() {
        var d=new TableTestSupport.Driver(4);d.produce();var rows=new ArrayList<>(d.receipts);rows.add(d.receipts.getFirst());
        assertEquals(4,TransformationDelivery.quantity(d.bound,d.run,rows));var x=d.receipts.getFirst();
        rows.add(new ResourceDelivery.Receipt(UUID.randomUUID(),x.origin(),x.runId(),x.operationId(),x.batchId(),x.actor(),x.source(),x.destination(),x.item(),x.stage(),x.quantity(),x.sourceBlock(),x.dropIds(),x.transformation()));
        assertThrows(IllegalArgumentException.class,()->TransformationDelivery.quantity(d.bound,d.run,rows));
    }
    @Test void missingCraftAndForeignRunCannotClaimDeposits() {
        var d=new TableTestSupport.Driver(1);d.produce();var rows=new ArrayList<>(d.receipts);rows.remove(1);
        assertThrows(IllegalArgumentException.class,()->TransformationDelivery.quantity(d.bound,d.run,rows));
        assertThrows(IllegalArgumentException.class,()->TransformationDelivery.quantity(d.bound,UUID.randomUUID(),d.receipts));
    }
}
