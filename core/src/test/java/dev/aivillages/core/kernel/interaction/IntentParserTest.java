package dev.aivillages.core.kernel.interaction;

import dev.aivillages.core.kernel.interaction.contract.InteractionCodec;
import dev.aivillages.core.kernel.interaction.contract.InteractionWire;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.aivillages.core.kernel.interaction.CandidateValidator.*;
import static org.junit.jupiter.api.Assertions.*;

class IntentParserTest {
    static final ConversationContext.SessionKey ADA = new ConversationContext.SessionKey(new UUID(0, 1), new UUID(0, 2), new UUID(0, 3));
    static final Selection CHEST = new Selection(new UUID(0, 4), "CHEST");
    static final Question QUESTION = new Question(new UUID(0, 5), 7);
    private final IntentParser parser = new IntentParser();
    private final ConversationContext context = new ConversationContext();
    static Transport message(String text, long sequence) {
        return new Transport(ADA, new UUID(1, sequence), sequence, text, CHEST, QUESTION);
    }
    private Candidate parse(String text, long sequence) {
        return assertInstanceOf(Candidate.class, parser.parse(message(text, sequence), context, sequence));
    }
    private void action(String text, String expected, long sequence) {
        Candidate candidate = parse(text, sequence);
        assertEquals(InteractionWire.Kind.DialogueIntent, candidate.intent().kind());
        assertEquals(expected, candidate.intent().body().get("action"));
        assertEquals(Map.of("kind", "ADDRESSED"), candidate.intent().body().get("actor"));
        assertEquals(ADA, candidate.sessionKey());
    }

    @Test void comeFollowAndOrdinaryChatKeepStableActorWithoutWorldCalls() {
        action("come here", "COME", 1); action("come to me", "COME", 2);
        action("follow me", "FOLLOW", 3); action("Hello Ada", "CHAT", 4);
        Candidate chat = parse("How are you today?", 5);
        assertEquals(Routing.CHAT, chat.routing());
        assertFalse(chat.requiresConfirmation());
        assertFalse(chat.intent().body().containsKey("activity"));
        assertEquals(5, context.snapshot(ADA, 5).turns().size());
        // Parser construction and execution take no world/effect dependency. Calling the optional
        // extractor is an explicit separate API, not a side effect of ordinary chat.
        assertEquals(0, List.of(IntentParser.class.getDeclaredFields()).stream()
                .filter(f -> f.getType().getName().contains("World") || f.getType().getName().contains("Job")).count());
    }

    @Test void allDialogueActionsProduceOnlyFrozenCandidateFields() {
        action("stop", "STOP", 1); action("status", "STATUS", 2);
        action("go to chest \"Pantry\"", "GO_NAMED", 3);
        action("deliver 12 bread from chest \"Pantry\" to citizen \"Bob\"", "DELIVER", 4);
        action("register this chest as \"Pantry\" for food", "REGISTER_STORAGE", 5);
        action("rename chest \"Pantry\" to \"Kitchen\"", "RENAME_STORAGE", 6);
        action("assign chest \"Kitchen\" as food storage", "ASSIGN_STORAGE", 7);
        action("set chest \"Kitchen\" access to public deposit", "SET_STORAGE_ACCESS", 8);
        action("forget this conversation", "FORGET", 9);
        assertTrue(context.snapshot(ADA, 9).turns().isEmpty());
    }

    @Test void exactQuantitiesAndStorageTypeRemainCandidates() {
        for (int quantity : List.of(1, 64)) {
            Candidate c = parse("deliver " + quantity + " bread from barrel \"Food\" to chest \"Output\"", quantity);
            assertEquals(List.of(Map.of("item_text", "bread", "quantity", (long) quantity)), c.intent().body().get("items"));
            assertEquals(Routing.RESOLVE, c.routing());
            assertFalse(c.requiresConfirmation());
        }
        for (String quantity : List.of("0", "-1", "65", "9223372036854775808", "999999999999999999999999")) {
            long seq = 100 + quantity.length() + (quantity.startsWith("-") ? 50 : 0);
            // Separate authenticated message streams avoid accidentally exercising replay fencing here.
            Result r = parser.parse(message("deliver " + quantity + " bread from chest \"A\" to chest \"B\"", seq), new ConversationContext(), 0);
            assertEquals(new Clarify(Code.INVALID_QUANTITY), r);
        }
    }

    @Test void registrationRenameAndAccessRequireConfirmationWithoutChangingAssociation() {
        UUID association = new UUID(0, 19); context.associate(ADA, association, 0);
        List<String> edits = List.of("register this chest as \"Pantry\"", "rename chest \"Pantry\" to \"Meals\"",
                "set chest \"Meals\" access to private", "set chest \"Meals\" access to public inspect deposit withdraw");
        for (int i = 0; i < edits.size(); i++) assertTrue(parse(edits.get(i), i + 1).requiresConfirmation());
        assertEquals(association, context.snapshot(ADA, 5).assistanceAssociation());
        assertFalse(parse("yes", 6).requiresConfirmation());
        assertEquals(association, context.snapshot(ADA, 6).assistanceAssociation());
    }

    @Test void allPlayerRepliesBindOnlyToCurrentQuestionAndNeverResumeAnActivity() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("check again", "CHECK_AGAIN"); cases.put("choose barrel \"Food\"", "CHOOSE_TARGET");
        cases.put("correct goal: use wheat instead", "CORRECT_GOAL"); cases.put("cancel", "CANCEL"); cases.put("help", "HELP");
        long sequence = 0;
        for (var entry : cases.entrySet()) {
            Candidate c = parse(entry.getKey(), ++sequence);
            assertEquals(InteractionWire.Kind.PlayerReplyIntent, c.intent().kind());
            assertEquals(entry.getValue(), c.intent().body().get("reply"));
            assertEquals(QUESTION.id().toString(), c.intent().body().get("question_id"));
            assertEquals(7L, c.intent().body().get("dialogue_revision"));
            assertEquals(entry.getValue().equals("CORRECT_GOAL"), c.requiresConfirmation());
            assertFalse(c.intent().body().containsKey("activity"));
            assertFalse(c.intent().body().containsKey("root_id"));
        }
        Transport noQuestion = new Transport(ADA, new UUID(1, 9), 9, "check again", null, null);
        assertEquals(new Clarify(Code.QUESTION_REQUIRED), parser.parse(noQuestion, context, 9));
    }

    @Test void missingAmbiguousNegatedAndUnsupportedCommandsClarify() {
        for (String text : List.of("come here and follow me", "do not follow me", "deliver some bread",
                "go to the moon", "register a new chest", "follow me or stop", "deliver 1.5 bread from chest \"A\" to chest \"B\""))
            assertInstanceOf(Clarify.class, parser.parse(message(text, 1), new ConversationContext(), 0), text);
        Candidate c = parse("rename chest \"Bread and Butter\" to \"Breakfast\"", 1);
        assertTrue(c.requiresConfirmation());
    }

    @Test void selectedStorageRequiresMatchingServerCorrelationAndType() {
        Transport absent = new Transport(ADA, new UUID(0, 10), 1, "register this chest as \"Food\"", null, null);
        assertEquals(new Clarify(Code.MISSING_SELECTION), parser.parse(absent, context, 0));
        Transport wrongType = new Transport(ADA, new UUID(0, 11), 2, absent.text(), new Selection(CHEST.id(), "BARREL"), null);
        assertEquals(new Clarify(Code.MISSING_SELECTION), parser.parse(wrongType, context, 1));
        assertEquals("REGISTER_STORAGE", parse("register this chest as \"Food\"", 3).intent().body().get("action"));
    }

    @Test void validatorRejectsForgedIdentityRootCoordinatesAndUnknownEnvelopeFields() {
        Transport t = message("follow me", 1); Draft d = IntentParser.extract(t).draft();
        for (String field : List.of("principal_id", "root_id", "activity", "coordinates")) {
            Map<String, Object> b = new LinkedHashMap<>(new InteractionCodec().decodeCandidate(d.json()).body());
            b.put(field, "forged");
            Draft forged = new Draft(t.key(), t.messageId(), InteractionCodec.canonical(Map.of("schema", InteractionWire.VERSION,
                    "kind", "DialogueIntent", "body", b)), d.spans());
            assertEquals(new Clarify(Code.MALFORMED), new CandidateValidator().validate(forged, t));
        }
        Draft extra = new Draft(t.key(), t.messageId(), d.json().replace("\"schema\":", "\"invented\":1,\"schema\":"), d.spans());
        assertEquals(new Clarify(Code.MALFORMED), new CandidateValidator().validate(extra, t));
    }

    @Test void validatorRejectsSwappedActorMessageTextAndReplyGeneration() {
        Transport t = message("follow me", 1); Draft d = IntentParser.extract(t).draft();
        var other = new ConversationContext.SessionKey(ADA.world(), new UUID(0, 99), ADA.citizen());
        assertEquals(new Clarify(Code.CORRELATION), new CandidateValidator().validate(new Draft(other, t.messageId(), d.json(), d.spans()), t));
        assertEquals(new Clarify(Code.CORRELATION), new CandidateValidator().validate(new Draft(t.key(), new UUID(9, 9), d.json(), d.spans()), t));
        assertEquals(new Clarify(Code.CORRELATION), new CandidateValidator().validate(new Draft(t.key(), t.messageId(), d.json().replace("follow me", "come here"), d.spans()), t));
        Transport reply = message("check again", 2); Draft r = IntentParser.extract(reply).draft();
        assertEquals(new Clarify(Code.CORRELATION), new CandidateValidator().validate(new Draft(r.key(), r.messageId(), r.json().replace("\"dialogue_revision\":7", "\"dialogue_revision\":8"), r.spans()), reply));
    }

    @Test void sourceSpansMustCoverExactGroundedClaimsAndUnicodeBoundaries() {
        Transport t = message("deliver 3 bread from chest \"Café\" to chest \"Output\"", 1);
        Draft good = IntentParser.extract(t).draft();
        assertInstanceOf(Candidate.class, new CandidateValidator().validate(good, t));
        Map<String, Span> missing = new LinkedHashMap<>(good.spans()); missing.remove("/items/0/quantity");
        assertEquals(new Clarify(Code.INVALID_SPAN), new CandidateValidator().validate(new Draft(t.key(), t.messageId(), good.json(), missing), t));
        assertEquals(new Clarify(Code.UNSUPPORTED), new CandidateValidator().validate(new Draft(t.key(), t.messageId(), good.json().replace("\"quantity\":3", "\"quantity\":4"), good.spans()), t));
        Map<String, Span> invalid = new LinkedHashMap<>(good.spans()); invalid.put("/action", new Span(-1, 2));
        assertEquals(new Clarify(Code.INVALID_SPAN), new CandidateValidator().validate(new Draft(t.key(), t.messageId(), good.json(), invalid), t));
        Transport emoji = message("hello 🧑", 2); Draft e = IntentParser.extract(emoji).draft();
        Map<String, Span> split = new LinkedHashMap<>(e.spans()); split.put("/action", new Span(7, 8));
        assertEquals(new Clarify(Code.INVALID_SPAN), new CandidateValidator().validate(new Draft(e.key(), e.messageId(), e.json(), split), emoji));
    }

    @Test void inferenceHasOnePrimaryAndOneBoundedCorrectionWithoutFallthroughOrReplay() {
        Transport t = message("follow me", 1); Draft good = IntentParser.extract(t).draft();
        List<IntentParser.ExtractionRequest> requests = new ArrayList<>();
        IntentParser.Extractor source = r -> { requests.add(r); return requests.size() == 1
                ? new Draft(t.key(), t.messageId(), "{}", Map.of()) : good; };
        assertInstanceOf(Candidate.class, parser.infer(t, context, 0, source));
        assertEquals(2, requests.size()); assertFalse(requests.get(0).correction()); assertTrue(requests.get(1).correction());
        assertTrue(requests.stream().allMatch(r -> r.maxOutputBytes() == MAX_CANDIDATE_BYTES && r.context().size() <= 8));
        assertEquals(new Clarify(Code.REPLAY), parser.infer(t, context, 1, source)); assertEquals(2, requests.size());
        AtomicInteger calls = new AtomicInteger();
        assertInstanceOf(Clarify.class, parser.infer(message("follow me", 2), context, 2, r -> {
            calls.incrementAndGet(); return new Draft(r.transport().key(), r.transport().messageId(), "{}", Map.of());
        }));
        assertEquals(2, calls.get());
    }

    @Test void providerExceptionAndCrossPrincipalResponseNeverTryAnotherProvider() {
        AtomicInteger calls = new AtomicInteger();
        assertEquals(new Clarify(Code.PROVIDER_UNAVAILABLE), parser.infer(message("follow me", 1), context, 0,
                r -> { calls.incrementAndGet(); throw new IllegalStateException("offline"); }));
        assertEquals(1, calls.get());
        Transport t = message("follow me", 2); Draft d = IntentParser.extract(t).draft();
        assertEquals(new Clarify(Code.CORRELATION), parser.infer(t, context, 1, r -> {
            calls.incrementAndGet(); return new Draft(new ConversationContext.SessionKey(ADA.world(), new UUID(0, 999), ADA.citizen()), t.messageId(), d.json(), d.spans());
        }));
        assertEquals(2, calls.get());
    }

    @Test void lateInferenceCannotForgetNewerConversation() {
        Transport old = message("forget", 1); Draft draft = IntentParser.extract(old).draft();
        Result r = parser.infer(old, context, 0, request -> {
            assertInstanceOf(Candidate.class, parser.parse(message("Hello again", 2), context, 1));
            return draft;
        });
        assertEquals(new Clarify(Code.REPLAY), r);
        assertEquals("Hello again", context.snapshot(ADA, 1).turns().getLast().text());
    }
}
