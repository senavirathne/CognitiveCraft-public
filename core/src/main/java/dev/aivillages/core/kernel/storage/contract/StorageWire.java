package dev.aivillages.core.kernel.storage.contract;

import java.util.*;

/** D-owned, immutable cc.cd projections. Decoding never creates world authority. */
public final class StorageWire {
    public static final String VERSION = "cc.cd/1.0.0";
    public static final String CONTRACT_SHA256 = "e1e97de64c8c8934d51b47bba143a1fccb49e9fedb8882380fc5cdd3fe008cb3";

    public enum Kind {
        DialogueIntent, PlayerReplyIntent, AssistanceRequest, BlockerEvidence,
        StorageSelector, StorageRecord, PhysicalStorageBinding, StorageAccessDecision,
        ResolvedEndpoint, MobilityGoal, DeliveryGoal, CustodyCheckpoint,
        ActivityResumeSignal, VersionRevisionGeneration, SelectionWitness,
        StorageRoleAssignment, StageManifest
    }

    /** Valid structure and semantic relationships, with no grant or effect capability. */
    public static final class Message {
        private final Kind kind;
        private final Map<String, Object> body;
        Message(Kind kind, Map<String, Object> body) {
            this.kind = Objects.requireNonNull(kind);
            this.body = object(freeze(body));
        }
        public Kind kind() { return kind; }
        public Map<String, Object> body() { return body; }
    }

    /** In-process observations supplied by a trusted adapter, never by wire deserialization. */
    public record Context(Long trustedGeneration, Boolean requirementsSatisfied,
                          String authenticatedPrincipal, String questionPrincipal,
                          String currentIncarnation, Long currentAclRevision,
                          Boolean aProofAuthenticated, Long eligibleRegisteredCount) {
        public static Context absent() {
            return new Context(null, null, null, null, null, null, null, null);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> copy.put((String) key, freeze(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            var copy = new ArrayList<Object>();
            list.forEach(item -> copy.add(freeze(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
    private StorageWire() { }
}
