package dev.aivillages.core.kernel.interaction;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static dev.aivillages.core.kernel.interaction.ConversationContext.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationContextTest {
    private final ConversationContext context = new ConversationContext();
    private final SessionKey alice = new SessionKey(new UUID(0, 1), new UUID(0, 2), new UUID(0, 3));
    private final SessionKey bob = new SessionKey(alice.world(), new UUID(0, 4), alice.citizen());
    private Status append(SessionKey key, long sequence, String text, long now) {
        return context.append(key, new UUID(1, sequence), sequence, text, now);
    }

    @Test void principalsWorldsAndCitizensHaveDisjointContext() {
        SessionKey otherWorld = new SessionKey(new UUID(0, 9), alice.principal(), alice.citizen());
        SessionKey otherCitizen = new SessionKey(alice.world(), alice.principal(), new UUID(0, 10));
        append(alice, 1, "Alice private text", 0); append(bob, 1, "Bob private text", 0);
        append(otherWorld, 1, "Other world", 0); append(otherCitizen, 1, "Other citizen", 0);
        assertEquals(List.of("Alice private text"), context.snapshot(alice, 0).turns().stream().map(Turn::text).toList());
        assertEquals(List.of("Bob private text"), context.snapshot(bob, 0).turns().stream().map(Turn::text).toList());
        assertEquals(4, context.liveSessions(0));
    }

    @Test void ninthTurnKeepsOnlyLastEightAndSnapshotsAreImmutable() {
        for (int i = 1; i <= 9; i++) {
            assertEquals(Status.ACCEPTED, append(alice, i, "Alice " + i, i));
            assertEquals(Status.ACCEPTED, append(bob, i, "Bob " + i, i));
        }
        var view = context.snapshot(alice, 9);
        assertEquals(8, view.turns().size()); assertEquals("Alice 2", view.turns().getFirst().text());
        assertEquals("Alice 9", view.turns().getLast().text());
        assertTrue(context.snapshot(bob, 9).turns().stream().allMatch(t -> t.text().startsWith("Bob")));
        assertThrows(UnsupportedOperationException.class, () -> view.turns().clear());
        append(alice, 10, "Alice 10", 10); assertEquals("Alice 9", view.turns().getLast().text());
    }

    @Test void characterBudgetDropsOldestWholeTurnsAndRejectsOversizeWithoutTruncating() {
        for (int i = 1; i <= 5; i++) assertEquals(Status.ACCEPTED, append(alice, i, "x".repeat(1024), i));
        assertEquals(4, context.snapshot(alice, 5).turns().size());
        assertEquals(2, context.snapshot(alice, 5).turns().getFirst().sequence());
        assertEquals(Status.INVALID_TEXT, append(alice, 6, "y".repeat(1025), 6));
        assertEquals(5, context.snapshot(alice, 6).turns().getLast().sequence());
    }

    @Test void tenMinuteIdleLimitIsExactAndReadingDoesNotRenewIt() {
        append(alice, 1, "ephemeral", 0);
        assertEquals(1, context.snapshot(alice, IDLE_MILLIS - 1).turns().size());
        assertTrue(context.snapshot(alice, IDLE_MILLIS).turns().isEmpty());
        assertEquals(0, context.liveSessions(IDLE_MILLIS));
        assertEquals(Status.ACCEPTED, append(alice, 1, "new authenticated session", IDLE_MILLIS));
    }

    @Test void fullServerRefusesNewSessionAndNeverEvictsAnActiveAssociation() {
        UUID association = new UUID(0, 80);
        for (int i = 1; i <= 128; i++) {
            SessionKey key = new SessionKey(alice.world(), new UUID(0, i), alice.citizen());
            assertEquals(Status.ACCEPTED, append(key, 1, "text", 0));
            assertEquals(Status.ACCEPTED, context.associate(key, association, 0));
        }
        SessionKey newcomer = new SessionKey(alice.world(), new UUID(0, 1000), alice.citizen());
        assertEquals(Status.CAPACITY, append(newcomer, 1, "new", 0));
        assertEquals(Status.CAPACITY, append(newcomer, 1, "new", IDLE_MILLIS));
        assertEquals(128, context.liveSessions(IDLE_MILLIS));
        assertTrue(context.snapshot(alice, IDLE_MILLIS).turns().isEmpty());
        assertEquals(association, context.snapshot(alice, IDLE_MILLIS).assistanceAssociation());
        context.associate(alice, null, IDLE_MILLIS);
        assertEquals(Status.ACCEPTED, append(newcomer, 1, "new", IDLE_MILLIS));
        assertEquals(128, context.liveSessions(IDLE_MILLIS));
    }

    @Test void forgetIsScopedAndKeepsReplayFenceAndAssistancePin() {
        UUID pin = new UUID(0, 22); append(alice, 1, "secret", 0); append(bob, 1, "other secret", 0);
        context.associate(alice, pin, 0);
        assertEquals(Status.ACCEPTED, context.forget(alice, 0));
        assertTrue(context.snapshot(alice, 0).turns().isEmpty());
        assertEquals(pin, context.snapshot(alice, 0).assistanceAssociation());
        assertEquals("other secret", context.snapshot(bob, 0).turns().getFirst().text());
        assertEquals(Status.REPLAY, append(alice, 1, "replayed", 0));
    }

    @Test void logoutClearsOnlyThatPrincipalInThatWorldIncludingEphemeralPins() {
        SessionKey anotherCitizen = new SessionKey(alice.world(), alice.principal(), new UUID(0, 33));
        SessionKey anotherWorld = new SessionKey(new UUID(0, 44), alice.principal(), alice.citizen());
        for (SessionKey key : List.of(alice, bob, anotherCitizen, anotherWorld)) append(key, 1, "text", 0);
        context.associate(alice, new UUID(0, 55), 0);
        assertEquals(2, context.logout(alice.world(), alice.principal()));
        assertTrue(context.snapshot(alice, 0).turns().isEmpty()); assertNull(context.snapshot(alice, 0).assistanceAssociation());
        assertEquals(1, context.snapshot(bob, 0).turns().size()); assertEquals(1, context.snapshot(anotherWorld, 0).turns().size());
    }

    @Test void monotonicClockAndReplayFailuresDoNotRenewOrMutateText() {
        UUID message = new UUID(0, 60);
        assertEquals(Status.ACCEPTED, context.append(alice, message, 1, "original", 100));
        assertEquals(Status.REPLAY, context.append(alice, message, 2, "duplicate ID", 101));
        assertEquals(Status.REPLAY, append(alice, 1, "duplicate sequence", 102));
        assertEquals(Status.INVALID_CLOCK, append(alice, 2, "clock rollback", 99));
        assertEquals("original", context.snapshot(alice, 102).turns().getFirst().text());
        assertEquals(Status.INVALID_CLOCK, context.snapshot(alice, 100).status());
        assertTrue(context.snapshot(alice, 100 + IDLE_MILLIS).turns().isEmpty());
    }

    @Test void unicodeLimitsCountCharactersAndNeverRepairMalformedInput() {
        assertEquals(Status.ACCEPTED, append(alice, 1, "🧑".repeat(1024), 0));
        assertEquals(Status.INVALID_TEXT, append(alice, 2, "🧑".repeat(1025), 0));
        for (String text : List.of("e\u0301", "\ud800", "\udc00", "hello\u0000", " "))
            assertEquals(Status.INVALID_TEXT, append(alice, 2, text, 0));
        assertEquals("🧑".repeat(1024), context.snapshot(alice, 0).turns().getFirst().text());
    }
}
