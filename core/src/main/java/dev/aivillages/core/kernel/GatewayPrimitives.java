package dev.aivillages.core.kernel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static dev.aivillages.core.kernel.Contracts.*;

/** The P0 physical allowlist. Fingerprints describe contracts, never implementation instances. */
public final class GatewayPrimitives implements PrimitiveCatalog {
    public static final int VERSION = 1;
    public enum Operation {
        OBSERVE_SOURCE("observe_source", Type.INT, Effect.OBSERVE,
                actor(), new Parameter("source", Type.AREA, 0, 0)),
        OBSERVE_INVENTORY("observe_inventory", Type.INT, Effect.OBSERVE, actor()),
        OBSERVE_CONTAINER("observe_container", Type.INT, Effect.OBSERVE,
                actor(), new Parameter("destination", Type.CONTAINER, 0, 0)),
        MOVE("move", Type.BOOL, Effect.MOVE, actor(), coordinate("x"), coordinate("y"), coordinate("z")),
        MOVE_TO_SOURCE("move_to_source", Type.BOOL, Effect.MOVE,
                actor(), new Parameter("source", Type.AREA, 0, 0)),
        MOVE_TO_DESTINATION("move_to_destination", Type.BOOL, Effect.MOVE,
                actor(), new Parameter("destination", Type.CONTAINER, 0, 0)),
        HARVEST_WHEAT("harvest_wheat", Type.INT, Effect.HARVEST,
                actor(), coordinate("x"), coordinate("y"), coordinate("z")),
        /** Bounded source scan followed by one ordinary harvest; no pickup, move or transfer. */
        HARVEST_NEXT_WHEAT("harvest_next_wheat", Type.INT, Effect.HARVEST,
                actor(), new Parameter("source", Type.AREA, 0, 0)),
        PICKUP_WHEAT("pickup_wheat", Type.INT, Effect.PICKUP,
                actor(), coordinate("x"), coordinate("y"), coordinate("z")),
        /** Select the first run-owned drop by insertion order, then revalidate its current cell. */
        PICKUP_TRACKED_WHEAT("pickup_tracked_wheat", Type.INT, Effect.PICKUP, actor()),
        TRANSFER_WHEAT("transfer_wheat", Type.INT, Effect.TRANSFER,
                actor(), new Parameter("destination", Type.CONTAINER, 0, 0),
                new Parameter("amount", Type.INT, 1, 64));

        private final PrimitiveSignature signature;
        Operation(String name, Type result, Effect effect, Parameter... parameters) {
            String id = "cognitivecraft:" + name;
            List<Parameter> args = List.copyOf(Arrays.asList(parameters));
            String canonical = "gateway:1|" + id + "|" + result + "|" + effect + "|" + args;
            signature = new PrimitiveSignature(id, VERSION, sha256(canonical), args, result, Set.of(effect));
        }
        public PrimitiveSignature signature() { return signature; }
        public PrimitiveRequirement requirement() {
            return new PrimitiveRequirement(signature.id(), signature.version(), signature.fingerprint());
        }
    }

    private static final class Holder {
        static final GatewayPrimitives INSTANCE = new GatewayPrimitives();
    }
    public static GatewayPrimitives instance() { return Holder.INSTANCE; }
    private final Map<Operation, PrimitiveSignature> signatures = new EnumMap<>(Operation.class);
    private GatewayPrimitives() {
        for (Operation op : Operation.values()) signatures.put(op, op.signature());
    }
    public Optional<Operation> operation(PrimitiveRequirement requirement) {
        if (requirement == null) return Optional.empty();
        return Arrays.stream(Operation.values())
                .filter(op -> op.requirement().equals(requirement)).findFirst();
    }
    @Override public Optional<PrimitiveSignature> find(String id, int version) {
        return Arrays.stream(Operation.values()).map(Operation::signature)
                .filter(signature -> signature.id().equals(id) && signature.version() == version).findFirst();
    }
    public List<PrimitiveSignature> all() { return List.copyOf(signatures.values()); }
    private static Parameter actor() { return new Parameter("actor", Type.ACTOR, 0, 0); }
    private static Parameter coordinate(String name) {
        return new Parameter(name, Type.INT, -30_000_000, 30_000_000);
    }
    private static String sha256(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) { throw new ExceptionInInitializerError(impossible); }
    }
}
