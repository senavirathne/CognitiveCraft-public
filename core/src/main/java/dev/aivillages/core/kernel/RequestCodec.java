package dev.aivillages.core.kernel;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static dev.aivillages.core.kernel.Contracts.*;

/** Versioned untrusted request data. Principal/scope/limits are deliberately absent. */
public final class RequestCodec {
    public static final int SCHEMA = 1;
    public static final int MAX_BYTES = 4_096;
    private RequestCodec() { }

    public sealed interface Decoded permits Valid, Invalid { }
    public record Valid(CapabilityRequest request) implements Decoded { }
    public record Invalid(Outcomes.Reason reason, String path) implements Decoded { }

    public static Decoded decode(String json) {
        if (json == null || json.length() > MAX_BYTES
                || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES)
            return new Invalid(Outcomes.Reason.REQUEST_INVALID, "$");
        try {
            Map<String, Object> root = StrictJson.object(json);
            Outcomes.exact(root, "schema", "capability", "version", "arguments");
            if (Outcomes.number(root, "schema", "$") != SCHEMA)
                throw new StrictJson.Invalid("$.schema", "UNKNOWN_SCHEMA");
            CapabilityId id = new CapabilityId(Outcomes.string(root, "capability", "$"),
                    Math.toIntExact(Outcomes.number(root, "version", "$")));
            Map<String, Object> arguments = Outcomes.object(root, "arguments", "$");
            if (arguments.size() > 16) throw new StrictJson.Invalid("$.arguments", "INPUT_LIMIT");
            Map<String, Value> typed = new HashMap<>();
            for (var entry : arguments.entrySet()) {
                String path = "$.arguments." + entry.getKey();
                Map<String, Object> value = map(entry.getValue(), path);
                String type = Outcomes.string(value, "type", path);
                Value parsed = switch (type) {
                    case "INT" -> {
                        Outcomes.exact(value, "type", "value");
                        yield new IntValue(Outcomes.number(value, "value", path));
                    }
                    case "BOOL" -> {
                        Outcomes.exact(value, "type", "value");
                        yield new BoolValue(Outcomes.bool(value, "value", path));
                    }
                    case "ACTOR" -> {
                        Outcomes.exact(value, "type", "citizen", "entity", "dimension");
                        yield new ActorValue(new ActorRef(uuid(value, "citizen", path),
                                uuid(value, "entity", path), Outcomes.string(value, "dimension", path)));
                    }
                    case "AREA" -> {
                        Outcomes.exact(value, "type", "dimension", "minX", "minY", "minZ",
                                "maxX", "maxY", "maxZ");
                        yield new AreaValue(new Cuboid(Outcomes.string(value, "dimension", path),
                                coord(value, "minX", path), coord(value, "minY", path),
                                coord(value, "minZ", path), coord(value, "maxX", path),
                                coord(value, "maxY", path), coord(value, "maxZ", path)));
                    }
                    case "CONTAINER" -> {
                        Outcomes.exact(value, "type", "dimension", "x", "y", "z");
                        yield new ContainerValue(new ContainerRef(Outcomes.string(value, "dimension", path),
                                coord(value, "x", path), coord(value, "y", path), coord(value, "z", path)));
                    }
                    default -> throw new StrictJson.Invalid(path + ".type", "UNKNOWN_TYPE");
                };
                typed.put(entry.getKey(), parsed);
            }
            return new Valid(new CapabilityRequest(id, typed));
        } catch (StrictJson.Invalid invalid) {
            return new Invalid(Outcomes.Reason.REQUEST_INVALID, invalid.path());
        } catch (IllegalArgumentException invalid) {
            return new Invalid(Outcomes.Reason.REQUEST_INVALID, "$");
        }
    }

    public static String encode(CapabilityRequest request) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (var entry : request.arguments().entrySet()) {
            Value value = entry.getValue();
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("type", value.type().name());
            if (value instanceof IntValue number) fields.put("value", number.value());
            else if (value instanceof BoolValue flag) fields.put("value", flag.value());
            else if (value instanceof ActorValue actor) {
                fields.put("citizen", actor.value().citizenId().toString());
                fields.put("entity", actor.value().entityId().toString());
                fields.put("dimension", actor.value().dimension());
            } else if (value instanceof AreaValue area) {
                Cuboid a = area.value();
                fields.put("dimension", a.dimension());
                fields.put("minX", (long) a.minX()); fields.put("minY", (long) a.minY());
                fields.put("minZ", (long) a.minZ()); fields.put("maxX", (long) a.maxX());
                fields.put("maxY", (long) a.maxY()); fields.put("maxZ", (long) a.maxZ());
            } else if (value instanceof ContainerValue container) {
                ContainerRef c = container.value();
                fields.put("dimension", c.dimension());
                fields.put("x", (long) c.x()); fields.put("y", (long) c.y());
                fields.put("z", (long) c.z());
            } else throw new IllegalArgumentException("Unsupported value");
            arguments.put(entry.getKey(), fields);
        }
        return StrictJson.canonical(Map.of("schema", (long) SCHEMA,
                "capability", request.capability().name(),
                "version", (long) request.capability().version(), "arguments", arguments));
    }

    private static Map<String, Object> map(Object value, String path) throws StrictJson.Invalid {
        if (!(value instanceof Map<?, ?> raw)) throw new StrictJson.Invalid(path, "EXPECTED_OBJECT");
        @SuppressWarnings("unchecked") Map<String, Object> cast = (Map<String, Object>) raw;
        return cast;
    }
    private static int coord(Map<String, Object> map, String key, String path) throws StrictJson.Invalid {
        long value = Outcomes.number(map, key, path);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new StrictJson.Invalid(path + "." + key, "COORDINATE_LIMIT");
        return (int) value;
    }
    private static UUID uuid(Map<String, Object> map, String key, String path) throws StrictJson.Invalid {
        String value = Outcomes.string(map, key, path);
        if (!value.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            throw new StrictJson.Invalid(path + "." + key, "INVALID_UUID");
        return UUID.fromString(value);
    }
}
