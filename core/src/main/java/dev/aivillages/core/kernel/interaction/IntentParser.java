package dev.aivillages.core.kernel.interaction;

import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import dev.aivillages.core.kernel.interaction.contract.InteractionWire;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.aivillages.core.kernel.interaction.CandidateValidator.*;

/** Inert candidate extraction. No world/effect port, legacy action enum or production registration. */
public final class IntentParser {
    private final CandidateValidator validator = new CandidateValidator();

    /** Optional single chosen extractor. C06 supplies the qualified provider adapter, with no fallthrough. */
    @FunctionalInterface public interface Extractor { Draft extract(ExtractionRequest request) throws Exception; }
    public record ExtractionRequest(Transport transport, List<ConversationContext.Turn> context,
                                    boolean correction, Code problem, int maxOutputBytes) {
        public ExtractionRequest { context = List.copyOf(context); }
    }
    record Extraction(Draft draft, Code problem) { }

    public Result parse(Transport transport, ConversationContext context, long now) {
        Result failure = begin(transport, context, now);
        if (failure != null) return failure;
        Extraction extraction = extract(transport);
        Result result = extraction.problem() == null ? validator.validate(extraction.draft(), transport)
                : new Clarify(extraction.problem());
        return finish(result, transport, context, now);
    }

    /** One primary call and at most one bounded correction to the same extractor, with replay fencing. */
    public Result infer(Transport transport, ConversationContext context, long now, Extractor extractor) {
        Objects.requireNonNull(extractor);
        Result failure = begin(transport, context, now);
        if (failure != null) return failure;
        List<ConversationContext.Turn> turns = context.snapshot(transport.key(), now).turns();
        Result result;
        try {
            result = validator.validate(extractor.extract(new ExtractionRequest(transport, turns, false, null,
                    MAX_CANDIDATE_BYTES)), transport);
            if (result instanceof Clarify clarification && clarification.code() != Code.CORRELATION)
                result = validator.validate(extractor.extract(new ExtractionRequest(transport, turns, true,
                        clarification.code(), MAX_CANDIDATE_BYTES)), transport);
        } catch (Exception failureFromProvider) { return new Clarify(Code.PROVIDER_UNAVAILABLE); }
        return finish(result, transport, context, now);
    }

    private Result begin(Transport transport, ConversationContext context, long now) {
        Objects.requireNonNull(transport); Objects.requireNonNull(context);
        return switch (context.append(transport.key(), transport.messageId(), transport.sequence(), transport.text(), now)) {
            case ACCEPTED -> null;
            case CAPACITY -> new Clarify(Code.SESSION_LIMIT);
            case REPLAY -> new Clarify(Code.REPLAY);
            case INVALID_TEXT -> new Clarify(Code.MALFORMED);
            case INVALID_CLOCK -> new Clarify(Code.CLOCK);
        };
    }

    private Result finish(Result result, Transport transport, ConversationContext context, long now) {
        if (result instanceof Candidate candidate && context.complete(transport.key(), transport.messageId(),
                transport.sequence(), candidate.routing() == Routing.FORGET_CONTEXT) != ConversationContext.Status.ACCEPTED)
            return new Clarify(Code.REPLAY);
        return result;
    }

    private static Matcher match(String expression, String text) {
        Matcher m = Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
        return m.matches() ? m : null;
    }
    private static String type(String word) { return word.equalsIgnoreCase("storage") ? "ANY_MVP" : word.toUpperCase(Locale.ROOT); }
    private static Span span(Matcher m, int group, int offset) { return new Span(offset + m.start(group), offset + m.end(group)); }
    private static void proof(Map<String, Span> spans, String field, Matcher m, int group, int offset) {
        spans.put(field, span(m, group, offset));
    }
    private static Map<String, Object> addressed() { return Map.of("kind", "ADDRESSED"); }
    private static Extraction problem(Code code) { return new Extraction(null, code); }
    private static Draft draft(Transport t, String kind, Map<String, Object> body, Map<String, Span> spans) {
        return new Draft(t.key(), t.messageId(), InteractionCodec.canonical(Map.of("schema", InteractionWire.VERSION,
                "kind", kind, "body", body)), spans);
    }

    /** C01's finite, fully anchored grammar. Every literal claim has an exact source range. */
    static Extraction extract(Transport t) {
        if (!ConversationContext.validUtterance(t.text())) return problem(Code.MALFORMED);
        String original = t.text(), text = original.strip();
        int offset = original.indexOf(text);
        if (text.regionMatches(true, 0, "please ", 0, 7)) { text = text.substring(7); offset += 7; }
        if (text.endsWith(".") || text.endsWith("!") || text.endsWith("?")) text = text.substring(0, text.length() - 1);
        if (text.isBlank()) return problem(Code.MALFORMED);
        Map<String, Span> spans = new LinkedHashMap<>();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "CHAT"); body.put("actor", addressed()); body.put("utterance", original);
        body.put("source", null); body.put("destination", null); body.put("items", List.of());
        body.put("label", null); body.put("role", null); body.put("requested_access", null);
        spans.put("/utterance", new Span(0, original.length()));
        Matcher m;
        String action = null;
        if ((m = match("(come (?:here|to me))", text)) != null) action = "COME";
        else if ((m = match("(follow me)", text)) != null) action = "FOLLOW";
        else if ((m = match("(stop)", text)) != null) action = "STOP";
        else if ((m = match("(status|what is your status)", text)) != null) action = "STATUS";
        else if ((m = match("(forget(?: (?:our|this) conversation)?)", text)) != null) action = "FORGET";
        else if ((m = match("(go to) (.+)", text)) != null) {
            action = "GO_NAMED";
            Object endpoint = endpoint(m.group(2), offset + m.start(2), "/destination", spans, t);
            if (endpoint instanceof Code code) return problem(code);
            if (!((Map<?, ?>) endpoint).get("kind").equals("STORAGE")) return problem(Code.UNSUPPORTED);
            body.put("destination", endpoint);
        } else if ((m = match("(deliver) ([+-]?[0-9]+) (.+?) from (.+?) to (.+)", text)) != null) {
            action = "DELIVER";
            long quantity;
            try { quantity = Long.parseLong(m.group(2)); }
            catch (NumberFormatException invalid) { return problem(Code.INVALID_QUANTITY); }
            if (quantity < 1 || quantity > 64) return problem(Code.INVALID_QUANTITY);
            Object source = endpoint(m.group(4), offset + m.start(4), "/source", spans, t);
            Object destination = endpoint(m.group(5), offset + m.start(5), "/destination", spans, t);
            if (source instanceof Code code) return problem(code);
            if (destination instanceof Code code) return problem(code);
            if (m.group(3).codePointCount(0, m.group(3).length()) > 96) return problem(Code.UNSUPPORTED);
            body.put("source", source); body.put("destination", destination);
            body.put("items", List.of(Map.of("item_text", m.group(3), "quantity", quantity)));
            proof(spans, "/items/0/quantity", m, 2, offset); proof(spans, "/items/0/item_text", m, 3, offset);
        } else if ((m = match("(register) (this (?:chest|barrel|storage)) as \"([^\"\\\\]+)\"(?: for (default|food|materials|output))?", text)) != null) {
            action = "REGISTER_STORAGE";
            Object endpoint = endpoint(m.group(2), offset + m.start(2), "/destination", spans, t);
            if (endpoint instanceof Code code) return problem(code);
            body.put("destination", endpoint); body.put("label", m.group(3)); proof(spans, "/label", m, 3, offset);
            if (m.group(4) != null) { body.put("role", m.group(4).toUpperCase(Locale.ROOT)); proof(spans, "/role", m, 4, offset); }
        } else if ((m = match("(rename) (.+) to \"([^\"\\\\]+)\"", text)) != null) {
            action = "RENAME_STORAGE";
            Object endpoint = endpoint(m.group(2), offset + m.start(2), "/source", spans, t);
            if (endpoint instanceof Code code) return problem(code);
            if (!((Map<?, ?>) endpoint).get("kind").equals("STORAGE")) return problem(Code.UNSUPPORTED);
            body.put("source", endpoint); body.put("label", m.group(3)); proof(spans, "/label", m, 3, offset);
        } else if ((m = match("(assign) (.+) as (default|food|materials|output) storage", text)) != null) {
            action = "ASSIGN_STORAGE";
            Object endpoint = endpoint(m.group(2), offset + m.start(2), "/source", spans, t);
            if (endpoint instanceof Code code) return problem(code);
            if (!((Map<?, ?>) endpoint).get("kind").equals("STORAGE")) return problem(Code.UNSUPPORTED);
            body.put("source", endpoint); body.put("role", m.group(3).toUpperCase(Locale.ROOT)); proof(spans, "/role", m, 3, offset);
        } else if ((m = match("(set) (.+) access to (private|public deposit|public inspect deposit|public inspect deposit withdraw)", text)) != null) {
            action = "SET_STORAGE_ACCESS";
            Object endpoint = endpoint(m.group(2), offset + m.start(2), "/source", spans, t);
            if (endpoint instanceof Code code) return problem(code);
            if (!((Map<?, ?>) endpoint).get("kind").equals("STORAGE")) return problem(Code.UNSUPPORTED);
            body.put("source", endpoint); body.put("requested_access", m.group(3).toUpperCase(Locale.ROOT).replace(' ', '_'));
            proof(spans, "/requested_access", m, 3, offset);
        } else {
            String reply = null;
            if ((m = match("(check again)", text)) != null) reply = "CHECK_AGAIN";
            else if ((m = match("(cancel)", text)) != null) reply = "CANCEL";
            else if ((m = match("(help)", text)) != null) reply = "HELP";
            else if ((m = match("(correct goal): (.+)", text)) != null) reply = "CORRECT_GOAL";
            else if ((m = match("(choose) (.+)", text)) != null) reply = "CHOOSE_TARGET";
            if (reply != null) {
                if (t.question() == null) return problem(Code.QUESTION_REQUIRED);
                Map<String, Object> replyBody = new LinkedHashMap<>();
                replyBody.put("question_id", t.question().id().toString()); replyBody.put("dialogue_revision", t.question().revision());
                replyBody.put("reply", reply); replyBody.put("text", original); replyBody.put("selection", null);
                spans.clear(); spans.put("/text", new Span(0, original.length())); proof(spans, "/reply", m, 1, offset);
                if (reply.equals("CHOOSE_TARGET")) {
                    Object endpoint = endpoint(m.group(2), offset + m.start(2), "/selection", spans, t);
                    if (endpoint instanceof Code code) return problem(code);
                    replyBody.put("selection", endpoint);
                }
                return new Extraction(draft(t, "PlayerReplyIntent", replyBody, spans), null);
            }
            // Unsupported command-like text is never downgraded to ordinary chat.
            if (match("(?:come|follow|stop|go|deliver|register|rename|assign|set|forget|choose|correct|cancel|check)(?:\\b.*)?", text) != null)
                return problem(ambiguous(text) ? Code.AMBIGUOUS : text.toLowerCase(Locale.ROOT).startsWith("deliver")
                        ? Code.MISSING_QUANTITY : Code.UNSUPPORTED);
            if (match("(?s).*(?:\\b(?:come here|come to me|follow me|deliver|register|rename)\\b).*", text) != null)
                return problem(Code.AMBIGUOUS);
            spans.put("/action", new Span(0, original.length()));
            return new Extraction(draft(t, "DialogueIntent", body, spans), null);
        }
        if (ambiguous(text)) return problem(Code.AMBIGUOUS);
        body.put("action", action); proof(spans, "/action", m, 1, offset);
        return new Extraction(draft(t, "DialogueIntent", body, spans), null);
    }

    private static boolean ambiguous(String text) {
        String unquoted = text.replaceAll("\"[^\"]*\"", "");
        return Pattern.compile(";|\\b(?:and|then|or)\\b", Pattern.CASE_INSENSITIVE).matcher(unquoted).find();
    }

    private static Object endpoint(String text, int offset, String field, Map<String, Span> spans, Transport t) {
        Matcher m;
        Map<String, Object> selector;
        String kind = "STORAGE";
        if ((m = match("(chest|barrel|storage|citizen) \"([^\"\\\\]+)\"", text)) != null) {
            kind = m.group(1).equalsIgnoreCase("citizen") ? "CITIZEN" : "STORAGE";
            selector = new LinkedHashMap<>(); selector.put("kind", "NAMED"); selector.put("label", m.group(2));
            proof(spans, field + "/selector/label", m, 2, offset);
            if (kind.equals("STORAGE")) { selector.put("container_type", type(m.group(1))); proof(spans, field + "/selector/container_type", m, 1, offset); }
        } else if ((m = match("(this|selected) (chest|barrel|storage)", text)) != null) {
            if (t.selection() == null || (!type(m.group(2)).equals("ANY_MVP") && !type(m.group(2)).equals(t.selection().containerType())))
                return Code.MISSING_SELECTION;
            selector = new LinkedHashMap<>(Map.of("kind", "SELECTED", "selection_id", t.selection().id().toString(), "container_type", type(m.group(2))));
            proof(spans, field + "/selector/container_type", m, 2, offset);
        } else if ((m = match("(your) (default|food|materials|output) (chest|barrel|storage)", text)) != null) {
            selector = new LinkedHashMap<>(Map.of("kind", "OWNER_ROLE", "owner", addressed(), "role", m.group(2).toUpperCase(Locale.ROOT), "container_type", type(m.group(3))));
            proof(spans, field + "/selector/role", m, 2, offset); proof(spans, field + "/selector/container_type", m, 3, offset);
        } else if ((m = match("(nearest public) (chest|barrel|storage)", text)) != null) {
            selector = new LinkedHashMap<>(Map.of("kind", "NEAREST_PUBLIC", "anchor", "CALLER", "container_type", type(m.group(2))));
            proof(spans, field + "/selector/container_type", m, 2, offset);
        } else if ((m = match("(you)", text)) != null) {
            kind = "CITIZEN"; selector = addressed();
        } else return Code.MISSING_TARGET;
        proof(spans, field + "/kind", m, 1, offset); proof(spans, field + "/selector/kind", m, 1, offset);
        return Map.of("kind", kind, "selector", selector);
    }
}
