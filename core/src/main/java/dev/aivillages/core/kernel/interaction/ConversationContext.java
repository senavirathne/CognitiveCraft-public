package dev.aivillages.core.kernel.interaction;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Bounded, ephemeral text only. J supplies authenticated keys; this store grants no authority. */
public final class ConversationContext {
    public static final int MAX_TURNS = 8;
    public static final int MAX_UTTERANCE_CHARACTERS = 1024;
    public static final int MAX_RETAINED_CHARACTERS = 4096;
    public static final int MAX_SESSIONS = 128;
    public static final long IDLE_MILLIS = 10 * 60 * 1000L;

    /** Stable server identities, never aliases or identifiers extracted from chat. */
    public record SessionKey(UUID world, UUID principal, UUID citizen) {
        public SessionKey { Objects.requireNonNull(world); Objects.requireNonNull(principal); Objects.requireNonNull(citizen); }
    }

    public enum Status { ACCEPTED, CAPACITY, REPLAY, INVALID_TEXT, INVALID_CLOCK }
    public record Turn(UUID messageId, long sequence, String text, long observedMillis) { }
    public record View(Status status, List<Turn> turns, UUID assistanceAssociation) {
        public View { turns = List.copyOf(turns); }
    }

    private static final class Session {
        final ArrayDeque<Turn> turns = new ArrayDeque<>();
        long lastActivity;
        long lastSequence = -1;
        UUID lastMessageId;
        int characters;
        UUID association;
        Session(long now) { lastActivity = now; }
        void clearText() { turns.clear(); characters = 0; }
    }

    private final Map<SessionKey, Session> sessions = new HashMap<>();
    private long lastObservedMillis = -1;

    /** Consumes a transport sequence once, including malformed extraction attempts. No world calls. */
    public synchronized Status append(SessionKey key, UUID messageId, long sequence, String text, long now) {
        Objects.requireNonNull(key); Objects.requireNonNull(messageId);
        if (!validUtterance(text) || sequence < 0) return Status.INVALID_TEXT;
        if (!observe(now)) return Status.INVALID_CLOCK;
        Session session = sessions.get(key);
        if (session == null) {
            if (sessions.size() == MAX_SESSIONS) return Status.CAPACITY;
            session = new Session(now); sessions.put(key, session);
        }
        if (sequence <= session.lastSequence || messageId.equals(session.lastMessageId)
                || session.turns.stream().anyMatch(turn -> turn.messageId().equals(messageId))) return Status.REPLAY;
        session.lastSequence = sequence; session.lastMessageId = messageId; session.lastActivity = now;
        session.turns.addLast(new Turn(messageId, sequence, text, now));
        session.characters += characters(text);
        while (session.turns.size() > MAX_TURNS || session.characters > MAX_RETAINED_CHARACTERS)
            session.characters -= characters(session.turns.removeFirst().text());
        return Status.ACCEPTED;
    }

    /** Reading does not prolong the idle deadline. Expired text is never returned. */
    public synchronized View snapshot(SessionKey key, long now) {
        Objects.requireNonNull(key);
        if (!observe(now)) return new View(Status.INVALID_CLOCK, List.of(), null);
        Session session = sessions.get(key);
        return session == null ? new View(Status.ACCEPTED, List.of(), null)
                : new View(Status.ACCEPTED, List.copyOf(session.turns), session.association);
    }

    /** Forgets this exact caller/citizen's text, retaining only the live replay fence and association. */
    public synchronized Status forget(SessionKey key, long now) {
        Objects.requireNonNull(key);
        if (!observe(now)) return Status.INVALID_CLOCK;
        Session session = sessions.get(key);
        if (session != null) session.clearText();
        return Status.ACCEPTED;
    }

    /** Atomically fence a late extractor response; a stale FORGET must not erase a newer turn. */
    public synchronized Status complete(SessionKey key, UUID messageId, long sequence, boolean forget) {
        Session session = sessions.get(Objects.requireNonNull(key));
        if (session == null || session.lastSequence != sequence || !Objects.equals(session.lastMessageId, messageId))
            return Status.REPLAY;
        if (forget) session.clearText();
        return Status.ACCEPTED;
    }

    /** Opaque C-side pin supplied by a server adapter. This is not an A activity or cancellation API. */
    public synchronized Status associate(SessionKey key, UUID association, long now) {
        Objects.requireNonNull(key);
        if (!observe(now)) return Status.INVALID_CLOCK;
        Session session = sessions.get(key);
        if (session == null) {
            if (association == null) return Status.ACCEPTED;
            if (sessions.size() == MAX_SESSIONS) return Status.CAPACITY;
            session = new Session(now); sessions.put(key, session);
        }
        session.association = association;
        return Status.ACCEPTED;
    }

    /** Server logout clears C memory only; A owns any durable activity association. */
    public synchronized int logout(UUID world, UUID principal) {
        Objects.requireNonNull(world); Objects.requireNonNull(principal);
        int before = sessions.size();
        sessions.keySet().removeIf(k -> k.world().equals(world) && k.principal().equals(principal));
        return before - sessions.size();
    }

    public synchronized int liveSessions(long now) {
        if (!observe(now)) throw new IllegalArgumentException("monotonic clock required");
        return sessions.size();
    }

    private boolean observe(long now) {
        if (now < 0 || now < lastObservedMillis) return false;
        lastObservedMillis = now;
        sessions.entrySet().removeIf(entry -> {
            Session s = entry.getValue();
            if (now - s.lastActivity < IDLE_MILLIS) return false;
            s.clearText();
            // Never silently discard a live assistance association to make room for a newcomer.
            return s.association == null;
        });
        return true;
    }

    public static boolean validUtterance(String text) {
        if (text == null || text.isBlank() || characters(text) > MAX_UTTERANCE_CHARACTERS
                || !Normalizer.isNormalized(text, Normalizer.Form.NFC)) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c) || Character.isISOControl(c)) return false;
        }
        return true;
    }

    private static int characters(String text) { return text.codePointCount(0, text.length()); }
}
