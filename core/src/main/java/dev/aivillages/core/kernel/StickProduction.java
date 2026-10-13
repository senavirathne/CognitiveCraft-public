package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;

/** Independent typed outcome; supplied oak planks, no producer or consumer artifact dependency. */
public final class StickProduction {
    public static final int MAX_AMOUNT=64, MAX_BATCHES=16, INPUT_PER_BATCH=2, OUTPUT_PER_BATCH=4;
    public static final String RECIPE="minecraft:stick";
    public static final CapabilityId ID=new CapabilityId("cognitivecraft:produce_sticks",1);
    public static final CapabilitySpec SPEC=new CapabilitySpec(ID,List.of(
            new Parameter("actor",Type.ACTOR,0,0),new Parameter("amount",Type.INT,1,MAX_AMOUNT),
            new Parameter("source",Type.CONTAINER,0,0),new Parameter("destination",Type.CONTAINER,0,0)),
            Type.INT,Set.of(Effect.OBSERVE,Effect.MOVE,Effect.WITHDRAW,Effect.CRAFT,Effect.TRANSFER),
            Completion.ATTRIBUTABLE_TRANSFORMATION_DELIVERY);
    private StickProduction() { }
    public static int batches(long amount) {
        if(amount<1 || amount>MAX_AMOUNT)throw new IllegalArgumentException("Requested stick amount");
        return Math.toIntExact(Math.addExact(amount,OUTPUT_PER_BATCH-1)/OUTPUT_PER_BATCH);
    }
    public static int inputs(long amount) {return Math.multiplyExact(batches(amount),INPUT_PER_BATCH);}
    public static long missingInputs(long amount,long available) {
        if(available<0)throw new IllegalArgumentException("Current input stock");
        return Math.max(0,inputs(amount)-available);
    }
    public static Budgets.ExecutionLimits executionLimits(long now) {return OakLogDelivery.executionLimits(now);}
    public static Budgets.ResearchLimits researchLimits(long now,Budgets.ResearchLimits base) {return OakLogDelivery.researchLimits(now,base);}
    public static boolean completed(ValidatedRequest request,UUID run,List<ResourceDelivery.Receipt> receipts) {
        if(request==null || run==null || receipts==null || !request.request().capability().equals(ID))return false;
        try {long amount=((IntValue)request.request().arguments().get("amount")).value();
            batches(amount);return TransformationDelivery.quantity(request,run,receipts)==amount;
        } catch(RuntimeException invalid) {return false;}
    }
}
