package dev.aivillages.core.kernel;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;
import static dev.aivillages.core.kernel.Outcomes.Reason;

/** Shared owner regressions and species-substitution negatives. Native effects have separate gates. */
class BirchResourceFamilyTest {
    static Operation birch(Operation oak) {
        if(oak==Operation.MOVE_TO_INPUT || oak==Operation.MOVE_TO_DESTINATION)return oak;
        return Arrays.stream(Operation.values()).filter(o->(o.birchLog() || o.birchPlank()) && o.shared()==oak).findFirst().orElseThrow();
    }
    static String program(String oak,CapabilityId original,CapabilityId target) {
        String result=oak.replace(original.name(),target.name());
        for(var op:Operation.values())if(op.birchLog() || op.birchPlank())result=result.replace(op.shared().signature().id(),op.signature().id()).replace(op.shared().signature().fingerprint(),op.signature().fingerprint());
        return result;
    }
    @ParameterizedTest @ValueSource(ints={1,4,8}) void logAcquisitionHasIndependentExactBirchLineage(int amount) {
        var s=new OakLogTestSupport.GatewayScene(amount,5,9,BirchLogDelivery.ID);
        for(int i=0;i<amount;i++)s.deliverOne();
        assertTrue(BirchLogDelivery.completed(s.request,s.run,s.receipts));
        assertEquals(5,s.world.actorLogs);assertEquals(9+amount,s.world.chestLogs);
        assertTrue(s.receipts.stream().allMatch(r->r.item().equals(ResourceDelivery.BIRCH_LOG)));
        assertFalse(OakLogDelivery.completed(s.request,s.run,s.receipts));
    }
    @ParameterizedTest @ValueSource(ints={1,4,5,8,64}) void stockedBirchRecipeAccountsForEveryRealBatchAndSurplus(int amount) {
        var d=new OakPlankTestSupport.Driver(amount,new OakPlankTestSupport.World(BirchPlankProduction.ID));d.produce();
        assertTrue(BirchPlankProduction.completed(d.bound,d.run,d.receipts));
        int batches=BirchPlankProduction.batches(amount);
        assertEquals(amount,d.world.chestPlanks);assertEquals(batches*4-amount,d.world.actorPlanks);assertEquals(16-batches,d.world.logs);
        assertTrue(d.receipts.stream().allMatch(r->r.transformation().recipe().key().equals(BirchPlankProduction.RECIPE)));
        assertEquals(batches*3,d.receipts.size());assertFalse(OakPlankProduction.completed(d.bound,d.run,d.receipts));
    }
    @ParameterizedTest @ValueSource(longs={Long.MIN_VALUE,-1,0,65,Long.MAX_VALUE}) void invalidAmountsNeverWrapIntoBirchBatches(long amount) {
        assertThrows(IllegalArgumentException.class,()->BirchPlankProduction.batches(amount));
    }
    @Test void birchPrimitivesDoNotAuthorizeOakAndOakPrimitivesDoNotAuthorizeBirch() {
        var b=new OakLogTestSupport.GatewayScene(1,0,0,BirchLogDelivery.ID);
        assertEquals(Reason.REQUEST_INVALID,b.action(Operation.HARVEST_NEXT_OAK_LOG).reason());assertEquals(0,b.world.harvests);
        var o=new OakLogTestSupport.GatewayScene(1,0,0);assertEquals(Reason.REQUEST_INVALID,o.action(Operation.HARVEST_NEXT_BIRCH_LOG).reason());
        var p=new OakPlankTestSupport.Driver(4,new OakPlankTestSupport.World(BirchPlankProduction.ID));
        assertEquals(Reason.REQUEST_INVALID,p.action(Operation.OBSERVE_PLANK_INPUTS).reason());assertEquals(0,p.world.withdrawals);
    }
    @Test void generatedMethodsCompileIndependentlyWithoutAnAcquisitionOrConsumerDependency() {
        var compiler=new SkillCompiler(PlanningGoals.catalog(),GatewayPrimitives.instance(),ref->Optional.empty());
        for(var row:List.of(Map.entry(OakLogDelivery.ID,BirchLogDelivery.ID),Map.entry(OakPlankProduction.ID,BirchPlankProduction.ID))) {
            String ir=program(row.getKey().equals(OakLogDelivery.ID)?OakLogTestSupport.program():OakPlankTestSupport.program(),row.getKey(),row.getValue());
            var result=compiler.compile(ir);assertInstanceOf(SkillCompiler.Success.class,result);
            var artifact=((SkillCompiler.Success)result).skill().artifact();assertEquals(row.getValue(),artifact.descriptor().ref().capability());
            assertTrue(artifact.descriptor().dependencies().isEmpty());
        }
    }
    @Test void stockPoolsAreSpeciesSpecificButBlockPositionsShareTheWorld() {
        UUID world=UUID.randomUUID();var source=OakLogTestSupport.SOURCE;var box=OakLogTestSupport.BOX;
        var oak=ResourceLeases.Resource.oakBlocks(world,source);var birch=LogAcquisition.blocks(world,BirchLogDelivery.ID,source);
        assertNotEquals(oak,birch);assertTrue(oak.logBlocks() && birch.logBlocks());assertFalse(birch.oakBlocks());
        assertNotEquals(RecipeProduction.of(OakPlankProduction.ID).stock(world,box),RecipeProduction.of(BirchPlankProduction.ID).stock(world,box));
    }
    @ParameterizedTest @CsvSource({"0,2","1,1","2,0","64,0"}) void compositionUsesOnlyTheActualBirchLogDeficit(long stock,long deficit) {
        var f=new ResourceCompositionTest.Fixture(8,BirchPlankProduction.ID);f.stock.put(ResourceDelivery.BIRCH_LOG.id(),stock);
        f.stock.put(ResourceDelivery.OAK_LOG.id(),64L);f.stock.put(ResourceDelivery.BIRCH_PLANKS.id(),64L);
        var p=f.resolve();assertTrue(p.accepted(),p.reason()+" "+p.qualification());
        assertEquals(deficit,f.produced(p,ResourceDelivery.BIRCH_LOG.id()));assertEquals(8,f.produced(p,ResourceDelivery.BIRCH_PLANKS.id()));
        assertTrue(p.nodes().stream().allMatch(n->n.production().capability().equals(BirchLogDelivery.ID)||n.production().capability().equals(BirchPlankProduction.ID)));
        assertFalse(f.inspections.containsKey(ResourceDelivery.OAK_LOG.id()));
    }
    @Test void crossFamilyProjectionIsRejectedBeforePublication() {
        var b=new ResourceCompositionTest.Fixture(4,BirchPlankProduction.ID);var o=new ResourceCompositionTest.Fixture(1);
        assertThrows(IllegalArgumentException.class,()->ResourceComposition.project(b.need,OakLogDelivery.ID,1));
        assertThrows(IllegalArgumentException.class,()->ResourceComposition.project(o.need,BirchLogDelivery.ID,1));
    }
    @Test void unknownInputsCannotTriggerAcquisition() {
        var f=new ResourceCompositionTest.Fixture(8,BirchPlankProduction.ID);f.stockStatus=ObservationStatus.UNKNOWN;
        var p=f.resolve();assertEquals(Reason.STALE_OBSERVATION,p.reason());assertTrue(p.nodes().isEmpty());
    }
    @Test void typedBirchReceiptsRejectForgedOakItemsAndRetainExactReplayIdempotence() {
        var d=new OakPlankTestSupport.Driver(4,new OakPlankTestSupport.World(BirchPlankProduction.ID));d.produce();
        var rows=new ArrayList<>(d.receipts);rows.addAll(d.receipts);assertEquals(4,TransformationDelivery.quantity(d.bound,d.run,rows));
        var r=d.receipts.getLast();var forged=new ResourceDelivery.Receipt(r.receiptId(),r.origin(),r.runId(),r.operationId(),r.batchId(),r.actor(),r.source(),r.destination(),ResourceDelivery.OAK_PLANKS,r.stage(),r.quantity(),r.sourceBlock(),r.dropIds(),r.transformation());
        rows=new ArrayList<>(d.receipts);rows.set(rows.size()-1,forged);var finalRows=rows;
        assertThrows(IllegalArgumentException.class,()->TransformationDelivery.quantity(d.bound,d.run,finalRows));
    }
    @Test void cancellationAndRecipeChangeKeepActualBirchCustodyWithoutCompletion() {
        var d=new OakPlankTestSupport.Driver(4,new OakPlankTestSupport.World(BirchPlankProduction.ID));
        for(var op:List.of(Operation.OBSERVE_BIRCH_PLANK_INPUTS,Operation.MOVE_TO_INPUT,Operation.WITHDRAW_BIRCH_LOG))assertNull(d.action(op).reason());
        d.world.recipeChanged=true;assertEquals(Reason.ARTIFACT_INCOMPATIBLE,d.action(Operation.CRAFT_BIRCH_PLANKS).reason());assertEquals(1,d.world.actorLogs);
        d.world.recipeChanged=false;assertNull(d.action(Operation.CRAFT_BIRCH_PLANKS).reason());d.gateway.releaseRun(d.run);
        assertEquals(4,d.world.actorPlanks);assertEquals(0,d.world.chestPlanks);assertFalse(BirchPlankProduction.completed(d.bound,d.run,d.receipts));
    }
    @Test void unsupportedBirchIngredientsNeverBroadenOldRecipes() {
        for(var cap:List.of(StickProduction.ID,WoodenAxeProduction.ID,CraftingTableProduction.ID))assertEquals(ResourceDelivery.OAK_PLANKS,RecipeProduction.of(cap).input());
        assertEquals(ResourceDelivery.BIRCH_LOG,RecipeProduction.of(BirchPlankProduction.ID).input());
    }
    @ParameterizedTest @CsvSource({"Ada, gather 4 birch logs,GATHER_BIRCH_LOGS","Ada, make 5 birch planks,MAKE_BIRCH_PLANKS","Ada, make birch sticks,UNKNOWN","Ada, gather oak and birch logs,UNKNOWN","Ada, gather stripped birch logs,UNKNOWN"})
    void narrowLanguageKeepsMaterialSelectionExplicit(String actor,String text,String expected) {
        assertEquals(expected,LanguageReferenceClassifier.classify(actor+","+text,List.of("Ada")).intent().name());
    }
}
