package dev.aivillages.core.kernel.interaction;

import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Language-only projection. This class grants no authority; J must revalidate disclosure before use. */
public final class ResponseFacts {
    public enum Mode { TASK, SMALL_TALK }
    public record Material(String registryId, long missing) {
        public Material {
            if (registryId == null || registryId.length() > 96 || !registryId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || missing < 1 || missing > 64) throw new IllegalArgumentException("bounded material fact");
        }
    }
    private final Mode mode;
    private final BlockerProjection.Code blocker;
    private final List<Material> materials;

    private ResponseFacts(Mode mode, BlockerProjection.Code blocker, List<Material> materials) {
        this.mode = mode; this.blocker = blocker; this.materials = List.copyOf(materials);
        if (materials.size() > 16 || materials.stream().map(Material::registryId).distinct().count() != materials.size()
                || materials.stream().mapToLong(Material::missing).sum() > 64)
            throw new IllegalArgumentException("bounded distinct material facts");
    }

    /** Only consume the C02 projection after redaction; raw labels, roots, coordinates and chat never enter. */
    public static ResponseFacts forBlocker(BlockerProjection.Projection projection, String requestedLocale) {
        Objects.requireNonNull(projection);
        var body = projection.wire().body();
        BlockerProjection.Code code = projection.code();
        List<Material> items = new ArrayList<>();
        if (code == BlockerProjection.Code.INSUFFICIENT_MATERIAL
                && "AUTHORIZED_FACTS".equals(body.get("visibility")) && "KNOWN".equals(body.get("knowledge"))
                && "FEASIBLE_AFTER_ASSISTANCE".equals(body.get("continuation"))) {
            for (Object value : (List<?>) body.get("requirements")) {
                Map<?, ?> row = (Map<?, ?>) value;
                Map<?, ?> item = (Map<?, ?>) row.get("item");
                items.add(new Material((String) item.get("registry_id"), ((Number) row.get("missing")).longValue()));
            }
        }
        if (code == BlockerProjection.Code.INSUFFICIENT_MATERIAL && items.isEmpty())
            throw new IllegalArgumentException("material explanation requires proven authorized facts");
        items.sort(Comparator.comparing(Material::registryId));
        return new ResponseFacts(Mode.TASK, code, items);
    }

    /** Locale hints do not enter the fact packet; English is the explicit current fallback. */
    public static ResponseFacts smallTalk(String requestedLocale) { return new ResponseFacts(Mode.SMALL_TALK, null, List.of()); }
    public Mode mode() { return mode; }
    public BlockerProjection.Code blocker() { return blocker; }
    public List<Material> materials() { return materials; }
    public String language() { return "en"; }
    public String languageKey() { return mode == Mode.SMALL_TALK ? "conversation.small_talk" : "blocker." + blocker.name().toLowerCase(java.util.Locale.ROOT); }
    public Map<String, Object> packet() {
        return Map.of("mode", mode.name(), "language", language(), "key", languageKey(),
                "materials", materials.stream().map(m -> Map.of("item", m.registryId(), "missing", m.missing())).toList());
    }
    public String digest() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(InteractionCodec.canonical(packet()).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
