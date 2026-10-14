package dev.aivillages.core.kernel;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
/** Independent supplied-ingredient outcome. No trusted program or upstream method dependency. */
public final class WoodenAxeProduction {
    public static final int MAX_AMOUNT=4, MAX_BATCHES=4, PLANKS_PER_BATCH=3, STICKS_PER_BATCH=2;
    public static final String RECIPE="minecraft:wooden_axe";
    public static final CapabilityId ID=new CapabilityId("cognitivecraft:produce_wooden_axes",1);
    public static final CapabilitySpec SPEC=new CapabilitySpec(ID,List.of(
            new Parameter("actor",Type.ACTOR,0,0),new Parameter("amount",Type.INT,1,MAX_AMOUNT),
            new Parameter("source",Type.CONTAINER,0,0),new Parameter("destination",Type.CONTAINER,0,0),
            new Parameter("table",Type.FACILITY,0,0)),Type.INT,
            Set.of(Effect.OBSERVE,Effect.MOVE,Effect.WITHDRAW,Effect.CRAFT,Effect.TRANSFER),Completion.ATTRIBUTABLE_TRANSFORMATION_DELIVERY);
    private WoodenAxeProduction() { }
    public static int batches(long amount) {
        if(amount<1 || amount>MAX_AMOUNT)throw new IllegalArgumentException("Requested axe amount");
        return Math.toIntExact(amount);
    }
    public static int planks(long amount) {return Math.multiplyExact(batches(amount),PLANKS_PER_BATCH);}
    public static int sticks(long amount) {return Math.multiplyExact(batches(amount),STICKS_PER_BATCH);}
    public static Budgets.ExecutionLimits executionLimits(long now) {return OakLogDelivery.executionLimits(now);}
    public static long missingInputs(long amount,long available) {if(available<0)throw new IllegalArgumentException("Stock");return Math.max(0,planks(amount)-available);}
    public static boolean completed(ValidatedRequest bound,UUID run,List<ResourceDelivery.Receipt> receipts) {
        if(bound==null || run==null || receipts==null || !bound.request().capability().equals(ID))return false;
        try {long q=((IntValue)bound.request().arguments().get("amount")).value();batches(q);
            return TransformationDelivery.quantity(bound,run,receipts)==q;
        } catch(RuntimeException invalid) {return false;}
    }
}
