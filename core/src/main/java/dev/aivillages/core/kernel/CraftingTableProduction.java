package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;

/** Inventory 2x2 manufacture only. A table item is never a usable world facility. */
public final class CraftingTableProduction {
    public static final int MAX_AMOUNT=4, MAX_BATCHES=4, INPUT_PER_BATCH=4, OUTPUT_PER_BATCH=1;
    public static final String RECIPE="minecraft:crafting_table";
    public static final CapabilityId ID=new CapabilityId("cognitivecraft:produce_crafting_tables",1);
    public static final CapabilitySpec SPEC=new CapabilitySpec(ID,List.of(
            new Parameter("actor",Type.ACTOR,0,0),new Parameter("amount",Type.INT,1,MAX_AMOUNT),
            new Parameter("source",Type.CONTAINER,0,0),new Parameter("destination",Type.CONTAINER,0,0)),
            Type.INT,Set.of(Effect.OBSERVE,Effect.MOVE,Effect.WITHDRAW,Effect.CRAFT,Effect.TRANSFER),
            Completion.ATTRIBUTABLE_TRANSFORMATION_DELIVERY);
    private CraftingTableProduction() { }
    public static int batches(long amount) {
        if(amount<1 || amount>MAX_AMOUNT)throw new IllegalArgumentException("Requested table amount");
        return Math.toIntExact(amount);
    }
    public static int inputs(long amount) {return Math.multiplyExact(batches(amount),INPUT_PER_BATCH);}
    public static Budgets.ExecutionLimits executionLimits(long now) {return OakLogDelivery.executionLimits(now);}
    public static boolean completed(ValidatedRequest bound,UUID run,List<ResourceDelivery.Receipt> receipts) {
        if(bound==null || run==null || receipts==null || !bound.request().capability().equals(ID))return false;
        try {long amount=((IntValue)bound.request().arguments().get("amount")).value();batches(amount);
            return TransformationDelivery.quantity(bound,run,receipts)==amount;
        } catch(RuntimeException invalid) {return false;}
    }
}
