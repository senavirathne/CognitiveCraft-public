package dev.aivillages.core.kernel;

import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.SkillIr.*;
import static org.junit.jupiter.api.Assertions.*;

/** Structural analysis only; synthetic nodes and counts are never native continuation evidence. */
final class RecipeActivityContinuationTest {
    static Call call(GatewayPrimitives.Operation op) {
        var s=op.signature();var args=new HashMap<String,Expr>();
        for(var p:s.parameters())args.put(p.name(),new ParameterExpr(p.name(),p.type()));
        return new Call(s.id(),s.version(),s.fingerprint(),null,args,op.name(),s.resultType());
    }
    static Program program(CapabilityId id) {
        var ops=RecipeProduction.operations(id);int yield=RecipeProduction.of(id).outputPerBatch();
        var count=new BinaryExpr(Operator.DIV,new BinaryExpr(Operator.ADD,new ParameterExpr("amount",Type.INT),
                new IntegerExpr(yield-1),Type.INT),new IntegerExpr(yield),Type.INT);
        var loop=new Repeat(count,RecipeProduction.of(id).maximumBatches(),ops.subList(1,ops.size()).stream().map(op->(Node)call(op)).toList());
        return new Program(id,List.of(),List.of(call(ops.getFirst()),loop,new Result(new ParameterExpr("amount",Type.INT))));
    }
    static CapabilityRequest request(CapabilityId id,int amount) {
        return new CapabilityRequest(id,Map.of("amount",new IntValue(amount)));
    }
    static Program loop(Program p,List<Node> body) {
        var old=(Repeat)p.body().get(1);
        return new Program(p.capability(),p.dependencies(),List.of(p.body().getFirst(),new Repeat(old.count(),old.maximumCount(),body),p.body().getLast()));
    }
    @Test void supportedRecipeShapesPointIntoTheirOriginalLoopAfterTheUniqueCraft() {
        var ids=new ArrayList<CapabilityId>();
        for(var family:RecipeProduction.PlankMaterial.values())ids.add(family.capability(1));
        ids.addAll(List.of(StickProduction.ID,CraftingTableProduction.ID,WoodenAxeProduction.ID));
        for(var id:ids) {
            var point=RecipeActivityContinuation.afterCraft(program(id),request(id,1),2);
            assertEquals(2,point.rootPosition());assertEquals(1,point.batchesRemaining());
            assertEquals(id.equals(WoodenAxeProduction.ID)?4:3,point.batchPosition());
            var repeat=(Repeat)program(id).body().get(1);
            assertEquals(GatewayPrimitives.Operation.MOVE_TO_DESTINATION.signature().id(),
                    ((Call)repeat.body().get(point.batchPosition())).primitiveId());
        }
    }
    @Test void completedBatchesAreSkippedAndTheCurrentBatchKeepsItsOriginalDelivery() {
        var p=program(OakPlankProduction.ID);var q=request(OakPlankProduction.ID,9);
        assertEquals(3,RecipeActivityContinuation.afterCraft(p,q,2).batchesRemaining());
        assertEquals(2,RecipeActivityContinuation.afterCraft(p,q,5).batchesRemaining());
        assertEquals(1,RecipeActivityContinuation.afterCraft(p,q,8).batchesRemaining());
        for(int prefix:new int[]{0,1,3,4,6,7,9,11})assertThrows(IllegalArgumentException.class,
                ()->RecipeActivityContinuation.afterCraft(p,q,prefix));
    }
    @Test void extraCraftsChangedOrderAndDependenciesCannotSupplyTheCut() {
        var p=program(WoodenAxeProduction.ID);var q=request(WoodenAxeProduction.ID,1);
        var rows=new ArrayList<>(((Repeat)p.body().get(1)).body());
        rows.add(4,rows.get(3));var duplicate=loop(p,rows);
        assertThrows(IllegalArgumentException.class,()->RecipeActivityContinuation.afterCraft(duplicate,q,2));
        rows=new ArrayList<>(((Repeat)p.body().get(1)).body());Collections.swap(rows,1,3);
        var reversed=loop(p,rows);assertThrows(IllegalArgumentException.class,()->RecipeActivityContinuation.afterCraft(reversed,q,2));
        var dependent=new Program(p.capability(),List.of(new ArtifactRef(p.capability(),"a".repeat(64))),p.body());
        assertThrows(IllegalArgumentException.class,()->RecipeActivityContinuation.afterCraft(dependent,q,2));
    }
    @Test void localDependentControlFlowAndDifferentMethodContractsRemainUnsupported() {
        var p=program(OakPlankProduction.ID);var q=request(OakPlankProduction.ID,3);
        var old=(Repeat)p.body().get(1);
        var conditional=new Program(p.capability(),List.of(),List.of(p.body().getFirst(),
                new Branch(new LocalExpr("observed",Type.BOOL),List.of(old),List.of()),p.body().getLast()));
        assertThrows(IllegalArgumentException.class,()->RecipeActivityContinuation.afterCraft(conditional,q,2));
        var nodes=new ArrayList<>(old.body());var c=(Call)nodes.getLast();var args=new HashMap<>(c.arguments());
        args.put("amount",new LocalExpr("crafted",Type.INT));
        nodes.set(nodes.size()-1,new Call(c.primitiveId(),c.primitiveVersion(),c.fingerprint(),null,args,c.into(),c.resultType()));
        assertThrows(IllegalArgumentException.class,()->RecipeActivityContinuation.afterCraft(loop(p,nodes),q,2));
        assertThrows(IllegalArgumentException.class,()->RecipeActivityContinuation.afterCraft(p,request(StickProduction.ID,3),2));
    }

    @Test void singleLogContinuationBeginsAfterPickupAndCannotReachHarvestAgain() {
        for (var cap : List.of(OakLogDelivery.ID, BirchLogDelivery.ID)) {
            var ops = LogAcquisition.operations(cap);
            var nodes = new ArrayList<Node>(List.of(call(ops.get(0)), call(ops.get(1)),
                    new Repeat(new IntegerExpr(20), 20, List.of(call(ops.get(2)))),
                    call(ops.get(0)), call(ops.get(3)), call(ops.get(4)), call(ops.get(5))));
            var q = new ParameterExpr("amount", Type.INT);
            var method = new Program(cap, List.of(), List.of(new Repeat(q, 64, nodes), new Result(q)));
            assertEquals(new RecipeActivityContinuation.Point(1, 5, 1), RecipeActivityContinuation.afterPickup(method, request(cap, 1), 2));
            assertThrows(IllegalArgumentException.class, () -> RecipeActivityContinuation.afterPickup(method, request(cap, 2), 2));
            assertThrows(IllegalArgumentException.class, () -> RecipeActivityContinuation.afterPickup(method, request(cap, 1), 1));
            nodes.set(5, call(ops.get(1)));
            var replay = new Program(cap, List.of(), List.of(new Repeat(q, 64, nodes), new Result(q)));
            assertThrows(IllegalArgumentException.class, () -> RecipeActivityContinuation.afterPickup(replay, request(cap, 1), 2));
        }
    }
}
