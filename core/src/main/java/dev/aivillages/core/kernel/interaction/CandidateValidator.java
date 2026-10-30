package dev.aivillages.core.kernel.interaction;

import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import dev.aivillages.core.kernel.interaction.contract.InteractionWire;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Pure validation of untrusted candidates. Correlation is necessary, never an authorization grant. */
public final class CandidateValidator {
    public static final int MAX_CANDIDATE_BYTES = 16_384;
    public enum Code {
        MALFORMED, UNSUPPORTED, AMBIGUOUS, MISSING_TARGET, MISSING_QUANTITY, INVALID_QUANTITY,
        MISSING_SELECTION, QUESTION_REQUIRED, CORRELATION, INVALID_SPAN, SESSION_LIMIT, REPLAY,
        CLOCK, PROVIDER_UNAVAILABLE
    }
    public enum Routing { CHAT, FORGET_CONTEXT, RESOLVE, CONFIRM_PROPOSAL, REVALIDATE_REPLY }
    public sealed interface Result permits Candidate, Clarify { }
    public record Clarify(Code code) implements Result { public Clarify { Objects.requireNonNull(code); } }
    /** UTF-16 half-open offsets into the exact, unnormalized transport text. */
    public record Span(int start, int end) { }
    public record Selection(UUID id, String containerType) {
        public Selection {
            Objects.requireNonNull(id);
            if (!Set.of("CHEST", "BARREL").contains(containerType)) throw new IllegalArgumentException("native container type");
        }
    }
    /** Current server-displayed question correlation only. No ActivityRef, budget or settlement. */
    public record Question(UUID id, long revision) {
        public Question {
            Objects.requireNonNull(id);
            if (revision < 1 || revision > 9_007_199_254_740_991L) throw new IllegalArgumentException("revision");
        }
    }
    /** Only the eventual authenticated J ingress may supply these correlations in production. */
    public record Transport(ConversationContext.SessionKey key, UUID messageId, long sequence,
                            String text, Selection selection, Question question) {
        public Transport { Objects.requireNonNull(key); Objects.requireNonNull(messageId); }
    }
    /** Separate extraction evidence; it does not amend the frozen cc.cd wire format. */
    public record Draft(ConversationContext.SessionKey key, UUID messageId, String json, Map<String, Span> spans) {
        public Draft { spans = Map.copyOf(spans); }
    }
    public static final class Candidate implements Result {
        private final InteractionWire.UntrustedMessage intent;
        private final Transport transport;
        private final Routing routing;
        private Candidate(InteractionWire.UntrustedMessage intent, Transport transport, Routing routing) {
            this.intent = intent; this.transport = transport; this.routing = routing;
        }
        public InteractionWire.UntrustedMessage intent() { return intent; }
        public ConversationContext.SessionKey sessionKey() { return transport.key(); }
        public UUID messageId() { return transport.messageId(); }
        public Routing routing() { return routing; }
        public boolean requiresConfirmation() { return routing == Routing.CONFIRM_PROPOSAL; }
    }

    private final InteractionCodec codec = new InteractionCodec();

    public Result validate(Draft draft, Transport transport) {
        Objects.requireNonNull(transport);
        if (!ConversationContext.validUtterance(transport.text()) || draft == null || draft.json() == null
                || draft.json().length() > MAX_CANDIDATE_BYTES
                || draft.json().getBytes(StandardCharsets.UTF_8).length > MAX_CANDIDATE_BYTES)
            return new Clarify(Code.MALFORMED);
        if (!transport.key().equals(draft.key()) || !transport.messageId().equals(draft.messageId()))
            return new Clarify(Code.CORRELATION);
        InteractionWire.UntrustedMessage intent;
        try { intent = codec.decodeCandidate(draft.json()); }
        catch (InteractionWire.DecodeException failure) { return new Clarify(Code.MALFORMED); }
        String textField = intent.kind() == InteractionWire.Kind.DialogueIntent ? "utterance" : "text";
        if (!transport.text().equals(intent.body().get(textField))) return new Clarify(Code.CORRELATION);
        if (intent.kind() == InteractionWire.Kind.PlayerReplyIntent && (transport.question() == null
                || !transport.question().id().toString().equals(intent.body().get("question_id"))
                || !Long.valueOf(transport.question().revision()).equals(intent.body().get("dialogue_revision"))))
            return new Clarify(Code.CORRELATION);
        // C01 supports the explicit finite grammar. A model may not invent omitted facts,
        // reinterpret negation or silently discard a second instruction. C07 owns language expansion.
        IntentParser.Extraction expected = IntentParser.extract(transport);
        if (expected.problem() != null) return new Clarify(expected.problem());
        for (Span span : draft.spans().values()) {
            if (span.start() < 0 || span.end() <= span.start() || span.end() > transport.text().length()
                    || splitsSurrogate(transport.text(), span.start()) || splitsSurrogate(transport.text(), span.end()))
                return new Clarify(Code.INVALID_SPAN);
        }
        if (!expected.draft().spans().equals(draft.spans())) return new Clarify(Code.INVALID_SPAN);
        InteractionWire.UntrustedMessage grounded;
        try { grounded = codec.decodeCandidate(expected.draft().json()); }
        catch (InteractionWire.DecodeException failure) { return new Clarify(Code.MALFORMED); }
        if (intent.kind() != grounded.kind() || !intent.body().equals(grounded.body()))
            return new Clarify(Code.UNSUPPORTED);
        Routing routing;
        if (intent.kind() == InteractionWire.Kind.PlayerReplyIntent)
            routing = intent.body().get("reply").equals("CORRECT_GOAL") ? Routing.CONFIRM_PROPOSAL : Routing.REVALIDATE_REPLY;
        else routing = switch ((String) intent.body().get("action")) {
            case "CHAT" -> Routing.CHAT;
            case "FORGET" -> Routing.FORGET_CONTEXT;
            case "REGISTER_STORAGE", "RENAME_STORAGE", "SET_STORAGE_ACCESS" -> Routing.CONFIRM_PROPOSAL;
            default -> Routing.RESOLVE;
        };
        return new Candidate(intent, transport, routing);
    }

    private static boolean splitsSurrogate(String text, int position) {
        return position > 0 && position < text.length() && Character.isLowSurrogate(text.charAt(position));
    }
}
