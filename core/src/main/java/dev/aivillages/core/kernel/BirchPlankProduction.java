package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;

/** Independent birch recipe outcome, without a producer or consumer artifact dependency. */
public final class BirchPlankProduction {
    public static final int MAX_AMOUNT = 64, MAX_BATCHES = 16, OUTPUT_PER_BATCH = 4;
    public static final String RECIPE = "minecraft:birch_planks";
    public static final CapabilityId ID = new CapabilityId("cognitivecraft:produce_birch_planks", 1);
    public static final CapabilitySpec SPEC = new CapabilitySpec(ID, List.of(
            new Parameter("actor", Type.ACTOR, 0, 0), new Parameter("amount", Type.INT, 1, MAX_AMOUNT),
            new Parameter("source", Type.CONTAINER, 0, 0), new Parameter("destination", Type.CONTAINER, 0, 0)),
            Type.INT, OakPlankProduction.SPEC.effects(), Completion.ATTRIBUTABLE_TRANSFORMATION_DELIVERY);
    private BirchPlankProduction() { }
    public static int batches(long amount) { return OakPlankProduction.batches(amount); }
    public static boolean completed(ValidatedRequest request, UUID run, List<ResourceDelivery.Receipt> receipts) {
        if (request == null || run == null || receipts == null || !request.request().capability().equals(ID)) return false;
        try {
            long amount = ((IntValue)request.request().arguments().get("amount")).value();
            batches(amount);
            return TransformationDelivery.quantity(request, run, receipts) == amount;
        } catch (RuntimeException invalid) { return false; }
    }
}
