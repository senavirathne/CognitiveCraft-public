package dev.aivillages.core.kernel;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.aivillages.core.kernel.Contracts.*;

/** One registered outcome, never a completed production program. */
public final class CropDelivery {
    public static final CapabilityId ID = new CapabilityId("cognitivecraft:deliver_wheat", 1);
    public static final CapabilitySpec SPEC = new CapabilitySpec(ID, List.of(
            new Parameter("actor", Type.ACTOR, 0, 0),
            new Parameter("amount", Type.INT, 1, 64),
            new Parameter("source", Type.AREA, 0, 0),
            new Parameter("destination", Type.CONTAINER, 0, 0)),
            Type.INT, Set.of(Effect.OBSERVE, Effect.MOVE, Effect.HARVEST,
                    Effect.PICKUP, Effect.TRANSFER), Completion.ATTRIBUTABLE_CROP_DELIVERY);

    private CropDelivery() { }

    public enum Stage { HARVEST, PICKUP, DEPOSIT }

    /** Produced by the game-thread gateway, never decoded from IR or a model response. */
    public record CropReceipt(UUID receiptId, UUID runId, UUID batchId, ActorRef actor,
                              Cuboid source, ContainerRef destination, Stage stage, long wheat) {
        public CropReceipt {
            java.util.Objects.requireNonNull(receiptId);
            java.util.Objects.requireNonNull(runId);
            java.util.Objects.requireNonNull(batchId);
            java.util.Objects.requireNonNull(actor);
            java.util.Objects.requireNonNull(source);
            java.util.Objects.requireNonNull(destination);
            java.util.Objects.requireNonNull(stage);
            if (wheat <= 0) throw new IllegalArgumentException("Positive wheat count required");
        }
    }

    /**
     * The gateway owns custody and receipt authenticity. This predicate checks that matching
     * batches from this actor, run, source and destination have every physical stage recorded.
     * Existing chest stock and unrelated receipts contribute zero.
     */
    public static boolean completed(ValidatedRequest bound, UUID runId, List<CropReceipt> receipts) {
        if (bound == null || runId == null || receipts == null || !bound.request().capability().equals(ID))
            return false;
        Map<String, Value> arguments = bound.request().arguments();
        if (!(arguments.get("actor") instanceof ActorValue actorValue)
                || !(arguments.get("source") instanceof AreaValue areaValue)
                || !(arguments.get("destination") instanceof ContainerValue destinationValue)
                || !(arguments.get("amount") instanceof IntValue amount)) return false;
        Map<UUID, long[]> batches = new HashMap<>();
        Map<UUID, CropReceipt> seen = new HashMap<>();
        try {
            for (CropReceipt receipt : receipts) {
                if (receipt == null || !receipt.runId().equals(runId)
                        || !receipt.actor().equals(actorValue.value())
                        || !receipt.source().equals(areaValue.value())
                        || !receipt.destination().equals(destinationValue.value())) continue;
                CropReceipt prior = seen.putIfAbsent(receipt.receiptId(), receipt);
                if (prior != null) {
                    if (!prior.equals(receipt)) return false;
                    continue;
                }
                long[] stages = batches.computeIfAbsent(receipt.batchId(), ignored -> new long[3]);
                int stage = receipt.stage().ordinal();
                stages[stage] = Math.addExact(stages[stage], receipt.wheat());
            }
            long credited = 0;
            for (long[] stages : batches.values()) {
                credited = Math.addExact(credited,
                        Math.min(stages[0], Math.min(stages[1], stages[2])));
            }
            return amount.value() > 0 && credited >= amount.value();
        } catch (ArithmeticException overflow) { return false; }
    }
}
