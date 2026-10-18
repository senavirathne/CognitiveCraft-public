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
                new Parameter("amount", Type.INT, 1, 64)),
        OBSERVE_OAK_SOURCE("observe_oak_source", Type.INT, Effect.OBSERVE,
                actor(), new Parameter("source", Type.AREA, 0, 0)),
        OBSERVE_OAK_INVENTORY("observe_oak_inventory", Type.INT, Effect.OBSERVE, actor()),
        OBSERVE_OAK_CONTAINER("observe_oak_container", Type.INT, Effect.OBSERVE,
                actor(), new Parameter("destination", Type.CONTAINER, 0, 0)),
        APPROACH_OAK_LOG("approach_oak_log", Type.BOOL, Effect.MOVE,
                actor(), new Parameter("source", Type.AREA, 0, 0)),
        HARVEST_NEXT_OAK_LOG("harvest_next_oak_log", Type.INT, Effect.HARVEST,
                actor(), new Parameter("source", Type.AREA, 0, 0)),
        PICKUP_TRACKED_OAK_LOGS("pickup_tracked_oak_logs", Type.INT, Effect.PICKUP, actor()),
        TRANSFER_OAK_LOGS("transfer_oak_logs", Type.INT, Effect.TRANSFER,
                actor(), new Parameter("destination", Type.CONTAINER, 0, 0),
                new Parameter("amount", Type.INT, 1, OakLogDelivery.MAX_AMOUNT)),
        OBSERVE_PLANK_INPUTS("observe_plank_inputs",Type.BOOL,Effect.OBSERVE,actor(),
                new Parameter("source",Type.CONTAINER,0,0),new Parameter("amount",Type.INT,1,64)),
        MOVE_TO_INPUT("move_to_input",Type.BOOL,Effect.MOVE,actor(),new Parameter("source",Type.CONTAINER,0,0)),
        WITHDRAW_OAK_LOG("withdraw_oak_log",Type.INT,Effect.WITHDRAW,actor(),new Parameter("source",Type.CONTAINER,0,0)),
        CRAFT_OAK_PLANKS("craft_oak_planks",Type.INT,Effect.CRAFT,actor()),
        TRANSFER_OAK_PLANKS("transfer_oak_planks",Type.INT,Effect.TRANSFER,actor(),
                new Parameter("destination",Type.CONTAINER,0,0),new Parameter("amount",Type.INT,1,64)),
        OBSERVE_STICK_INPUTS("observe_stick_inputs",Type.BOOL,Effect.OBSERVE,actor(),
                new Parameter("source",Type.CONTAINER,0,0),new Parameter("amount",Type.INT,1,64)),
        WITHDRAW_OAK_PLANKS("withdraw_oak_planks",Type.INT,Effect.WITHDRAW,actor(),new Parameter("source",Type.CONTAINER,0,0)),
        CRAFT_STICKS("craft_sticks",Type.INT,Effect.CRAFT,actor()),
        TRANSFER_STICKS("transfer_sticks",Type.INT,Effect.TRANSFER,actor(),
                new Parameter("destination",Type.CONTAINER,0,0),new Parameter("amount",Type.INT,1,64)),
        OBSERVE_AXE_INPUTS("observe_axe_inputs",Type.BOOL,Effect.OBSERVE,actor(),new Parameter("source",Type.CONTAINER,0,0),
                new Parameter("amount",Type.INT,1,4),new Parameter("table",Type.FACILITY,0,0)),
        WITHDRAW_AXE_INPUTS("withdraw_axe_inputs",Type.INT,Effect.WITHDRAW,actor(),new Parameter("source",Type.CONTAINER,0,0)),
        MOVE_TO_TABLE("move_to_table",Type.BOOL,Effect.MOVE,actor(),new Parameter("table",Type.FACILITY,0,0)),
        CRAFT_WOODEN_AXE("craft_wooden_axe",Type.INT,Effect.CRAFT,actor(),new Parameter("table",Type.FACILITY,0,0)),
        TRANSFER_WOODEN_AXES("transfer_wooden_axes",Type.INT,Effect.TRANSFER,actor(),new Parameter("destination",Type.CONTAINER,0,0),
                new Parameter("amount",Type.INT,1,4)),
        OBSERVE_TABLE_INPUTS("observe_table_inputs",Type.BOOL,Effect.OBSERVE,actor(),new Parameter("source",Type.CONTAINER,0,0),
                new Parameter("amount",Type.INT,1,4)),
        WITHDRAW_TABLE_PLANKS("withdraw_table_planks",Type.INT,Effect.WITHDRAW,actor(),new Parameter("source",Type.CONTAINER,0,0)),
        CRAFT_CRAFTING_TABLE("craft_crafting_table",Type.INT,Effect.CRAFT,actor()),
        TRANSFER_CRAFTING_TABLES("transfer_crafting_tables",Type.INT,Effect.TRANSFER,actor(),new Parameter("destination",Type.CONTAINER,0,0),
                new Parameter("amount",Type.INT,1,4)),
        OBSERVE_TABLE_SITE("observe_table_site",Type.BOOL,Effect.OBSERVE,actor(),new Parameter("source",Type.CONTAINER,0,0),new Parameter("target",Type.FACILITY,0,0)),
        WITHDRAW_TABLE_ITEM("withdraw_table_item",Type.INT,Effect.WITHDRAW,actor(),new Parameter("source",Type.CONTAINER,0,0)),
        MOVE_TO_PLACEMENT("move_to_placement",Type.BOOL,Effect.MOVE,actor(),new Parameter("target",Type.FACILITY,0,0)),
        PLACE_CRAFTING_TABLE("place_crafting_table",Type.INT,Effect.PLACE,actor(),new Parameter("target",Type.FACILITY,0,0)),
        OBSERVE_BIRCH_SOURCE("observe_birch_source",Type.INT,Effect.OBSERVE,actor(),new Parameter("source",Type.AREA,0,0)),
        OBSERVE_BIRCH_INVENTORY("observe_birch_inventory",Type.INT,Effect.OBSERVE,actor()),
        OBSERVE_BIRCH_CONTAINER("observe_birch_container",Type.INT,Effect.OBSERVE,actor(),new Parameter("destination",Type.CONTAINER,0,0)),
        APPROACH_BIRCH_LOG("approach_birch_log",Type.BOOL,Effect.MOVE,actor(),new Parameter("source",Type.AREA,0,0)),
        HARVEST_NEXT_BIRCH_LOG("harvest_next_birch_log",Type.INT,Effect.HARVEST,actor(),new Parameter("source",Type.AREA,0,0)),
        PICKUP_TRACKED_BIRCH_LOGS("pickup_tracked_birch_logs",Type.INT,Effect.PICKUP,actor()),
        TRANSFER_BIRCH_LOGS("transfer_birch_logs",Type.INT,Effect.TRANSFER,actor(),new Parameter("destination",Type.CONTAINER,0,0),new Parameter("amount",Type.INT,1,8)),
        OBSERVE_BIRCH_PLANK_INPUTS("observe_birch_plank_inputs",Type.BOOL,Effect.OBSERVE,actor(),new Parameter("source",Type.CONTAINER,0,0),new Parameter("amount",Type.INT,1,64)),
        WITHDRAW_BIRCH_LOG("withdraw_birch_log",Type.INT,Effect.WITHDRAW,actor(),new Parameter("source",Type.CONTAINER,0,0)),
        CRAFT_BIRCH_PLANKS("craft_birch_planks",Type.INT,Effect.CRAFT,actor()),
        TRANSFER_BIRCH_PLANKS("transfer_birch_planks",Type.INT,Effect.TRANSFER,actor(),new Parameter("destination",Type.CONTAINER,0,0),new Parameter("amount",Type.INT,1,64));

        private final PrimitiveSignature signature;
        Operation(String name, Type result, Effect effect, Parameter... parameters) {
            String id = "cognitivecraft:" + name;
            List<Parameter> args = List.copyOf(Arrays.asList(parameters));
            String canonical = "gateway:1|" + id + "|" + result + "|" + effect + "|" + args;
            signature = new PrimitiveSignature(id, VERSION, sha256(canonical), args, result, Set.of(effect));
        }
        public PrimitiveSignature signature() { return signature; }
        public boolean birchPlank() {return this==OBSERVE_BIRCH_PLANK_INPUTS || this==WITHDRAW_BIRCH_LOG || this==CRAFT_BIRCH_PLANKS || this==TRANSFER_BIRCH_PLANKS;}
        public boolean birchLog() {return name().contains("BIRCH") && !birchPlank();}
        /** Shared control flow only, after item-specific request/signature validation. */
        public Operation shared() {
            return switch(this) {
                case OBSERVE_BIRCH_SOURCE -> OBSERVE_OAK_SOURCE;
                case OBSERVE_BIRCH_INVENTORY -> OBSERVE_OAK_INVENTORY;
                case OBSERVE_BIRCH_CONTAINER -> OBSERVE_OAK_CONTAINER;
                case APPROACH_BIRCH_LOG -> APPROACH_OAK_LOG;
                case HARVEST_NEXT_BIRCH_LOG -> HARVEST_NEXT_OAK_LOG;
                case PICKUP_TRACKED_BIRCH_LOGS -> PICKUP_TRACKED_OAK_LOGS;
                case TRANSFER_BIRCH_LOGS -> TRANSFER_OAK_LOGS;
                case OBSERVE_BIRCH_PLANK_INPUTS -> OBSERVE_PLANK_INPUTS;
                case WITHDRAW_BIRCH_LOG -> WITHDRAW_OAK_LOG;
                case CRAFT_BIRCH_PLANKS -> CRAFT_OAK_PLANKS;
                case TRANSFER_BIRCH_PLANKS -> TRANSFER_OAK_PLANKS;
                default -> this;
            };
        }
        public boolean plank() {return this==OBSERVE_PLANK_INPUTS || this==MOVE_TO_INPUT
                || this==WITHDRAW_OAK_LOG || this==CRAFT_OAK_PLANKS || this==TRANSFER_OAK_PLANKS;}
        public boolean stick() {return this==OBSERVE_STICK_INPUTS || this==WITHDRAW_OAK_PLANKS || this==CRAFT_STICKS || this==TRANSFER_STICKS;}
        public boolean axe() {return this==OBSERVE_AXE_INPUTS || this==WITHDRAW_AXE_INPUTS || this==MOVE_TO_TABLE || this==CRAFT_WOODEN_AXE || this==TRANSFER_WOODEN_AXES;}
        public boolean table() {return this==OBSERVE_TABLE_INPUTS || this==WITHDRAW_TABLE_PLANKS || this==CRAFT_CRAFTING_TABLE || this==TRANSFER_CRAFTING_TABLES;}
        public boolean placement(){return this==OBSERVE_TABLE_SITE || this==WITHDRAW_TABLE_ITEM || this==MOVE_TO_PLACEMENT || this==PLACE_CRAFTING_TABLE;}
        public boolean oak() { return !plank() && !stick() && !axe() && name().contains("OAK"); }
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
