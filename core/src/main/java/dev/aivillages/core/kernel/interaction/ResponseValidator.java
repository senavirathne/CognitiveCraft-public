package dev.aivillages.core.kernel.interaction;

import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A bounded phrasing language, not a claim that arbitrary natural language can be verified. */
public final class ResponseValidator {
    public static final int MAX_ENVELOPE_BYTES = 1024;
    public boolean acceptsText(ResponseFacts facts, String text) {
        return text != null && text.getBytes(StandardCharsets.UTF_8).length <= ResponseTemplates.MAX_OUTPUT_BYTES
                && ResponseTemplates.choices(facts).contains(text);
    }
    public Optional<String> validate(ResponseFacts facts, String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_ENVELOPE_BYTES) return Optional.empty();
        try {
            Object parsed = InteractionCodec.parseJson(json);
            if (!(parsed instanceof Map<?, ?> value) || !value.keySet().equals(Set.of("text", "facts_sha256"))
                    || !facts.digest().equals(value.get("facts_sha256")) || !(value.get("text") instanceof String text)
                    || !acceptsText(facts, text)) return Optional.empty();
            return Optional.of(text);
        } catch (IllegalArgumentException failure) { return Optional.empty(); }
    }
    public String envelope(ResponseFacts facts, String text) {
        if (!acceptsText(facts, text)) throw new IllegalArgumentException("ungrounded response");
        return InteractionCodec.canonical(Map.of("text", text, "facts_sha256", facts.digest()));
    }
}
