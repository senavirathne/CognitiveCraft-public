package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;

/** RB-001 outcome only: the runtime never supplies an executable acquisition method. */
public final class OakLogDelivery {
    public static final int MAX_AMOUNT = 8;
    public static final CapabilityId ID = new CapabilityId("cognitivecraft:deliver_oak_logs", 1);
    public static final CapabilitySpec SPEC = new CapabilitySpec(ID, List.of(
            new Parameter("actor", Type.ACTOR, 0, 0), new Parameter("amount", Type.INT, 1, MAX_AMOUNT),
            new Parameter("source", Type.AREA, 0, 0), new Parameter("destination", Type.CONTAINER, 0, 0)),
            Type.INT, CropDelivery.SPEC.effects(), Completion.ATTRIBUTABLE_RESOURCE_DELIVERY);
    private OakLogDelivery() { }
    public static Budgets.ExecutionLimits executionLimits(long now) {
        return new Budgets.ExecutionLimits(new Budgets.Limits(Map.of(
                Budgets.Kind.CALLS,256L,Budgets.Kind.INSTRUCTIONS,4096L,Budgets.Kind.OBSERVATIONS,4096L,
                Budgets.Kind.TRAVEL_BLOCKS,128L,Budgets.Kind.ATTEMPTED_EFFECTS,64L,
                Budgets.Kind.COMMITTED_EFFECTS,64L,Budgets.Kind.ELAPSED_TICKS,1200L),Math.addExact(now,600000)),15000);
    }
    public static Budgets.ResearchLimits researchLimits(long now,Budgets.ResearchLimits base) {
        var maxima=new EnumMap<Budgets.Kind,Long>(Budgets.Kind.class); maxima.putAll(base.total().maxima());
        maxima.put(Budgets.Kind.INSTRUCTIONS,10000L); maxima.put(Budgets.Kind.OBSERVATIONS,10000L);
        maxima.put(Budgets.Kind.TRAVEL_BLOCKS,256L); maxima.put(Budgets.Kind.ELAPSED_TICKS,2400L);
        var execution=executionLimits(now);
        return new Budgets.ResearchLimits(base.inference(),execution,new Budgets.Limits(maxima,base.total().deadlineEpochMillis()));
    }
    public static boolean completed(ValidatedRequest request, UUID run, List<ResourceDelivery.Receipt> receipts) {
        if (request == null || run == null || receipts == null || !request.request().capability().equals(ID)
                || !(request.request().arguments().get("amount") instanceof IntValue amount)
                || amount.value() < 1 || amount.value() > MAX_AMOUNT) return false;
        try { return ResourceDelivery.quantity(request, run, receipts) >= amount.value(); }
        catch (RuntimeException invalid) { return false; }
    }
}
