package dev.aivillages.core.kernel.interaction;

import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import dev.aivillages.core.kernel.interaction.contract.InteractionWire;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.aivillages.core.kernel.interaction.CandidateValidator.*;

/** Finite C05 grammar. All results remain candidates; labels never establish identity or access. */
public final class SelectorParser {
    public sealed interface Result permits Parsed, Clarification { }
    public record Clarification(Code code) implements Result { }
    public static final class Parsed implements Result {
        private final Map<String, Object> endpoint;
        private final Map<String, Span> spans;
        private Parsed(Map<String, Object> endpoint, Map<String, Span> spans) {
            this.endpoint = endpoint; this.spans = Map.copyOf(spans);
        }
        public Map<String, Object> endpoint() { return endpoint; }
        /** Relative UTF-16 offsets into the exact phrase, before NFC normalization of labels. */
        public Map<String, Span> spans() { return spans; }
    }
    private static final String TYPE = "(chest|barrel|storage|warehouse)";
    private static final String QUOTED = "(?:\"([^\"\\\\]+)\"|'([^'\\\\]+)')";

    public Result parse(String phrase, Selection selection) {
        if (phrase == null || phrase.isBlank() || phrase.codePointCount(0, phrase.length()) > 1024
                || !ConversationContext.validUtterance(Normalizer.normalize(phrase, Normalizer.Form.NFC)))
            return new Clarification(Code.MALFORMED);
        String text = phrase.strip(); int offset = phrase.indexOf(text);
        Matcher m; String endpointKind = "STORAGE";
        Map<String, Object> s = new LinkedHashMap<>(); Map<String, Span> spans = new LinkedHashMap<>();
        if ((m = match("(this|selected) " + TYPE, text)) != null) {
            String type = type(m.group(2));
            if (m.group(2).equalsIgnoreCase("warehouse")) return new Clarification(Code.UNSUPPORTED);
            if (selection == null || (!type.equals("ANY_MVP") && !type.equals(selection.containerType())))
                return new Clarification(Code.MISSING_SELECTION);
            s.putAll(Map.of("kind", "SELECTED", "selection_id", selection.id().toString(), "container_type", type));
            span(spans, "/selector/container_type", m, 2, offset);
        } else if ((m = match("(nearest public) " + TYPE + "(?: (to me|to you))?", text)) != null) {
            if (m.group(2).equalsIgnoreCase("warehouse")) return new Clarification(Code.UNSUPPORTED);
            s.putAll(Map.of("kind", "NEAREST_PUBLIC", "container_type", type(m.group(2)),
                    "anchor", "to you".equalsIgnoreCase(m.group(3)) ? "ACTOR" : "CALLER"));
            span(spans, "/selector/container_type", m, 2, offset);
            if (m.group(3) != null) span(spans, "/selector/anchor", m, 3, offset);
        } else if ((m = match("(your) (default|food|materials|output) " + TYPE, text)) != null) {
            if (m.group(3).equalsIgnoreCase("warehouse")) return new Clarification(Code.UNSUPPORTED);
            s.putAll(Map.of("kind", "OWNER_ROLE", "owner", Map.of("kind", "ADDRESSED"),
                    "role", m.group(2).toUpperCase(Locale.ROOT), "container_type", type(m.group(3))));
            span(spans, "/selector/role", m, 2, offset); span(spans, "/selector/container_type", m, 3, offset);
        } else if ((m = match("(.+)[’']s (?:(default|food|materials|output) )?" + TYPE, text)) != null) {
            if (m.group(3).equalsIgnoreCase("warehouse")) return new Clarification(Code.UNSUPPORTED);
            String owner = unquote(m.group(1));
            if (!validName(owner) || (!isQuoted(m.group(1)) && (ambiguous(m.group(1)) || !m.group(1).matches("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N} ’'_ -]*")))) return new Clarification(Code.AMBIGUOUS);
            s.putAll(Map.of("kind", "OWNER_ROLE", "owner", Map.of("kind", "NAMED", "label", nfc(owner)),
                    "role", m.group(2) == null ? "DEFAULT" : m.group(2).toUpperCase(Locale.ROOT), "container_type", type(m.group(3))));
            span(spans, "/selector/owner/label", m, 1, offset); span(spans, "/selector/container_type", m, 3, offset);
            if (m.group(2) != null) span(spans, "/selector/role", m, 2, offset);
        } else if ((m = match("((?:public )?warehouse|chest|barrel|storage|citizen|villager) " + QUOTED, text)) != null) {
            endpointKind = m.group(1).equalsIgnoreCase("citizen") || m.group(1).equalsIgnoreCase("villager") ? "CITIZEN" : "STORAGE";
            int label = m.group(2) != null ? 2 : 3;
            if (!validName(m.group(label))) return new Clarification(Code.MALFORMED);
            s.putAll(Map.of("kind", "NAMED", "label", nfc(m.group(label)))); span(spans, "/selector/label", m, label, offset);
            if (endpointKind.equals("STORAGE")) { s.put("container_type", type(m.group(1))); span(spans, "/selector/container_type", m, 1, offset); }
        } else if ((m = match(QUOTED + "(?: " + TYPE + ")?", text)) != null) {
            int label = m.group(1) != null ? 1 : 2;
            if (!validName(m.group(label))) return new Clarification(Code.MALFORMED);
            s.putAll(Map.of("kind", "NAMED", "label", nfc(m.group(label)), "container_type", m.group(3) == null ? "ANY_MVP" : type(m.group(3))));
            span(spans, "/selector/label", m, label, offset);
            if (m.group(3) != null) span(spans, "/selector/container_type", m, 3, offset);
        } else if ((m = match("(you)", text)) != null) {
            endpointKind = "CITIZEN"; s.put("kind", "ADDRESSED");
        } else if ((m = match("(citizen|villager) ([\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N} ’'_-]*)", text)) != null) {
            if (!validName(m.group(2)) || ambiguous(m.group(2))) return new Clarification(Code.AMBIGUOUS);
            endpointKind = "CITIZEN"; s.putAll(Map.of("kind", "NAMED", "label", nfc(m.group(2)))); span(spans, "/selector/label", m, 2, offset);
        } else return new Clarification(ambiguous(text) ? Code.AMBIGUOUS : Code.MISSING_TARGET);
        int first = m.group(1) != null ? 1 : 2;
        span(spans, "/kind", m, first, offset); span(spans, "/selector/kind", m, first, offset);
        // Reuse strict frozen codecs, including a complete DialogueIntent for CitizenSelector.
        Map<String, Object> endpoint;
        try {
            if (endpointKind.equals("STORAGE")) {
                var wire = new InteractionCodec().decode(InteractionCodec.canonical(Map.of("schema", InteractionWire.VERSION,
                        "kind", "StorageSelector", "body", s)));
                endpoint = Map.of("kind", endpointKind, "selector", wire.body());
            } else endpoint = Map.of("kind", endpointKind, "selector", Map.copyOf(s));
        } catch (RuntimeException invalid) { return new Clarification(Code.MALFORMED); }
        return new Parsed(endpoint, spans);
    }

    public record DeliveryParts(String item, Span itemSpan, String source, Span sourceSpan, String destination, Span destinationSpan) { }
    /** Delimiters inside quoted labels are data, including instruction-like labels. */
    public static DeliveryParts delivery(String tail) {
        String mask = outsideQuotes(tail);
        Matcher anchors = Pattern.compile("(?i) to (?:me|you)\\b").matcher(mask);
        StringBuilder structural = new StringBuilder(mask);
        while (anchors.find()) for (int i=anchors.start(); i<anchors.end(); i++) structural.setCharAt(i, '_');
        mask = structural.toString(); Matcher m = match("(.+?) from (.+?) to (.+)", mask); boolean reverse = false;
        if (m == null) { m = match("(.+?) to (.+?) from (.+)", mask); reverse = true; }
        if (m == null) return null;
        // Exactly one source and destination separator. No silent discard of extra instructions.
        if (Pattern.compile("(?i)\\b(?:from|to)\\b").matcher(mask).results().count() != 2) return null;
        int src = reverse ? 3 : 2, dst = reverse ? 2 : 3;
        return new DeliveryParts(tail.substring(m.start(1), m.end(1)), new Span(m.start(1), m.end(1)),
                tail.substring(m.start(src), m.end(src)), new Span(m.start(src), m.end(src)),
                tail.substring(m.start(dst), m.end(dst)), new Span(m.start(dst), m.end(dst)));
    }
    static boolean ambiguous(String text) { return Pattern.compile("(?i);|\\b(?:and|then|or)\\b").matcher(outsideQuotes(text)).find(); }
    static String outsideQuotes(String text) {
        StringBuilder mask = new StringBuilder(text); char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; else mask.setCharAt(i, '_'); }
            else if ((c == '\"' || c == '\'') && (i == 0 || Character.isWhitespace(text.charAt(i - 1)))) quote = c;
        }
        return mask.toString();
    }
    private static Matcher match(String pattern, String text) { Matcher m = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text); return m.matches() ? m : null; }
    private static void span(Map<String, Span> out, String field, Matcher m, int group, int offset) { out.put(field, new Span(offset + m.start(group), offset + m.end(group))); }
    private static String type(String text) { return text.equalsIgnoreCase("storage") || text.toLowerCase(Locale.ROOT).endsWith("warehouse") ? "ANY_MVP" : text.toUpperCase(Locale.ROOT); }
    private static boolean isQuoted(String text) { return text.length() >= 2 && ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'"))); }
    private static String unquote(String text) { return isQuoted(text) ? text.substring(1, text.length() - 1) : text; }
    private static String nfc(String text) { return Normalizer.normalize(text, Normalizer.Form.NFC); }
    private static boolean validName(String text) { return text != null && !text.isBlank() && text.codePointCount(0, text.length()) <= 64 && ConversationContext.validUtterance(nfc(text)); }
}
