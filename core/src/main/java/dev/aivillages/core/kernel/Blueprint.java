package dev.aivillages.core.kernel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Structural schema 1. Values contain no programs, world handles, permissions or execution state. */
public final class Blueprint {
    public static final int SCHEMA = 1;
    public static final String DOMAIN = "blueprint-body";
    private Blueprint() { }
    public static String identifier(String value) {
        if (value == null || value.length() > 128 || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("Registry identifier");
        return value;
    }
    public record Ref(int schema, String sha256) {
        public Ref {
            if (schema != SCHEMA || sha256 == null || !sha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Blueprint reference");
        }
    }
    public record Dimensions(int x, int y, int z) {
        public Dimensions {
            if (x < 1 || y < 1 || z < 1 || x > 64 || y > 64 || z > 64)
                throw new IllegalArgumentException("Blueprint dimensions");
        }
        public long volume() { return (long)x * y * z; }
    }
    /** Requirements to be checked by a future consumer, never a site approval. */
    public record Constraints(boolean supportedGround, boolean nonHazardous, int perimeter) {
        public Constraints {
            if (perimeter < 0 || perimeter > 4) throw new IllegalArgumentException("Blueprint perimeter");
        }
    }
    public record State(String block, Map<String, String> properties) {
        public State {
            identifier(block);
            if (properties == null || properties.size() > 16) throw new IllegalArgumentException("State properties");
            properties.forEach((k,v) -> {
                if (k == null || !k.matches("[a-z_]{1,32}") || v == null || !v.matches("[a-z0-9_-]{1,32}"))
                    throw new IllegalArgumentException("State property");
            });
            properties = Map.copyOf(properties);
        }
        public String key() { return StrictJson.canonical(Map.of("block", block, "properties", properties)); }
    }
    public record Cell(int x, int y, int z, int palette) { }
    public record Body(Dimensions dimensions, Constraints constraints, List<State> palette, List<Cell> cells) {
        public Body {
            Objects.requireNonNull(dimensions); Objects.requireNonNull(constraints);
            if (palette == null || cells == null || palette.isEmpty() || palette.size() > 64
                    || cells.isEmpty() || cells.size() > 512) throw new IllegalArgumentException("Blueprint shape bounds");
            palette = List.copyOf(palette); cells = List.copyOf(cells);
        }
        public String canonical() {
            var states = palette.stream().map(s -> Map.of("block", s.block(), "properties", s.properties())).toList();
            var geometry = cells.stream().map(c -> Map.of("x",c.x(),"y",c.y(),"z",c.z(),"palette",c.palette())).toList();
            return StrictJson.canonical(Map.of("schema", SCHEMA,
                    "dimensions",Map.of("x",dimensions.x(),"y",dimensions.y(),"z",dimensions.z()),
                    "constraints",Map.of("supportedGround",constraints.supportedGround(),
                            "nonHazardous",constraints.nonHazardous(),"perimeter",constraints.perimeter()),
                    "palette",states,"cells",geometry));
        }
        public Ref ref() { return new Ref(SCHEMA, digest(canonical())); }
    }
    /** The source body remains immutable. Origin and rotation grant no placement authority. */
    public record SiteBinding(Ref source, String dimension, int x, int y, int z, int quarterTurns) {
        public SiteBinding {
            Objects.requireNonNull(source); identifier(dimension);
            if (quarterTurns < 0 || quarterTurns > 3) throw new IllegalArgumentException("Quarter turns");
        }
    }
    public record View(Ref source, Body geometry, SiteBinding binding) {
        public View {
            Objects.requireNonNull(source); Objects.requireNonNull(geometry); Objects.requireNonNull(binding);
            if (!source.equals(binding.source())) throw new IllegalArgumentException("Binding source");
            Math.addExact(binding.x(), geometry.dimensions().x()-1);
            Math.addExact(binding.y(), geometry.dimensions().y()-1);
            Math.addExact(binding.z(), geometry.dimensions().z()-1);
        }
    }
    public static WorldRetentionManager.Key retentionKey(Ref ref) {
        return new WorldRetentionManager.Key("blueprints", ref.sha256());
    }
    public static String digest(String text) {
        return digest(text.getBytes(StandardCharsets.UTF_8));
    }
    public static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
