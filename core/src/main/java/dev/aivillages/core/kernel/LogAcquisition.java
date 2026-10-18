package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.GatewayPrimitives.Operation;

/** Exact registered material descriptors, not a generic tag-based harvesting policy. */
public final class LogAcquisition {
    private LogAcquisition() { }
    public static boolean is(CapabilityId id) { return OakLogDelivery.ID.equals(id) || BirchLogDelivery.ID.equals(id); }
    public static boolean log(ResourceDelivery.ItemIdentity item) {
        return ResourceDelivery.OAK_LOG.equals(item) || ResourceDelivery.BIRCH_LOG.equals(item);
    }
    public static CapabilitySpec spec(CapabilityId id) {
        if (OakLogDelivery.ID.equals(id)) return OakLogDelivery.SPEC;
        if (BirchLogDelivery.ID.equals(id)) return BirchLogDelivery.SPEC;
        throw new IllegalArgumentException("Unsupported log capability");
    }
    public static ResourceDelivery.ItemIdentity item(CapabilityId id) {
        spec(id);
        return BirchLogDelivery.ID.equals(id) ? ResourceDelivery.BIRCH_LOG : ResourceDelivery.OAK_LOG;
    }
    public static String count(CapabilityId id) { return BirchLogDelivery.ID.equals(id) ? "birch_logs" : "oak_logs"; }
    public static boolean permits(CapabilityId id, Operation op) {
        return is(id) && (op == Operation.MOVE_TO_DESTINATION || (BirchLogDelivery.ID.equals(id) ? op.birchLog() : op.oak()));
    }
    public static List<Operation> operations(CapabilityId id) {
        spec(id);
        return BirchLogDelivery.ID.equals(id)
                ? List.of(Operation.APPROACH_BIRCH_LOG, Operation.HARVEST_NEXT_BIRCH_LOG, Operation.OBSERVE_BIRCH_INVENTORY,
                    Operation.PICKUP_TRACKED_BIRCH_LOGS, Operation.MOVE_TO_DESTINATION, Operation.TRANSFER_BIRCH_LOGS)
                : List.of(Operation.APPROACH_OAK_LOG, Operation.HARVEST_NEXT_OAK_LOG, Operation.OBSERVE_OAK_INVENTORY,
                    Operation.PICKUP_TRACKED_OAK_LOGS, Operation.MOVE_TO_DESTINATION, Operation.TRANSFER_OAK_LOGS);
    }
    public static ResourceLeases.Resource blocks(UUID world, CapabilityId id, Cuboid area) {
        return new ResourceLeases.Resource(world, ResourceLeases.Kind.STOCK, area, item(id).id(), -2);
    }
}
