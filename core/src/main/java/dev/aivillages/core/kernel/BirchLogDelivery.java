package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;

/** Exact birch outcome. Physical execution remains with the shared gateway. */
public final class BirchLogDelivery {
    public static final int MAX_AMOUNT = 8;
    public static final CapabilityId ID = new CapabilityId("cognitivecraft:deliver_birch_logs", 1);
    public static final CapabilitySpec SPEC = new CapabilitySpec(ID, List.of(
            new Parameter("actor", Type.ACTOR, 0, 0), new Parameter("amount", Type.INT, 1, MAX_AMOUNT),
            new Parameter("source", Type.AREA, 0, 0), new Parameter("destination", Type.CONTAINER, 0, 0)),
            Type.INT, OakLogDelivery.SPEC.effects(), Completion.ATTRIBUTABLE_RESOURCE_DELIVERY);
    private BirchLogDelivery() { }
    public static boolean completed(ValidatedRequest request, UUID run, List<ResourceDelivery.Receipt> receipts) {
        if (request == null || run == null || receipts == null || !request.request().capability().equals(ID)) return false;
        try {
            long amount = ((IntValue)request.request().arguments().get("amount")).value();
            return amount >= 1 && amount <= MAX_AMOUNT && ResourceDelivery.quantity(request, run, receipts) >= amount;
        } catch (RuntimeException invalid) { return false; }
    }
}
