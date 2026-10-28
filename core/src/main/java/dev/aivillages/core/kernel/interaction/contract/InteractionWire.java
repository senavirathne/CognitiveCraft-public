package dev.aivillages.core.kernel.interaction.contract;

import java.util.Map;

/** Inert transport values. Shape validity never establishes identity, permission or settlement. */
public final class InteractionWire {
    private InteractionWire() { }

    public static final String VERSION = "cc.cd/1.0.0";
    public static final String CONTRACT_SHA256 = "e1e97de64c8c8934d51b47bba143a1fccb49e9fedb8882380fc5cdd3fe008cb3";
    public static final String SCHEMA_SHA256 = "99901b31634957fa8d1c3ebeaa668a5ab4297139a98a015bde81f7e5b89c6589";

    public enum Kind {
        DialogueIntent, PlayerReplyIntent, AssistanceRequest, BlockerEvidence, StorageSelector,
        StorageRecord, PhysicalStorageBinding, StorageAccessDecision, ResolvedEndpoint,
        MobilityGoal, DeliveryGoal, CustodyCheckpoint, ActivityResumeSignal,
        VersionRevisionGeneration, SelectionWitness, StorageRoleAssignment, StageManifest
    }

    public enum Disposition { VALID, INVALID_SCHEMA, INVALID_SEMANTICS, REFUSED_EXPECTED }

    /** All decoded projections, including fields named TrustedScope, remain untrusted claims. */
    public static final class UntrustedMessage {
        private final Kind kind;
        private final Map<String, Object> body;
        // Only the validating codec in this package constructs these immutable values.
        UntrustedMessage(Kind kind, Map<String, Object> body) { this.kind = kind; this.body = body; }
        public Kind kind() { return kind; }
        public Map<String, Object> body() { return body; }
        public boolean isCandidateIntent() {
            return kind == Kind.DialogueIntent || kind == Kind.PlayerReplyIntent;
        }
    }

    /** A separate type, intentionally with no public or codec constructor/factory.
     * J must supply a qualified authenticated adapter before this can ever be constructed.
     * JSON and synthetic context checks cannot mint an instance of this type.
     */
    public static final class TrustedProjection {
        private final UntrustedMessage projection;
        private TrustedProjection(UntrustedMessage projection) { this.projection = projection; }
        public UntrustedMessage claims() { return projection; }
    }

    public static final class DecodeException extends IllegalArgumentException {
        private final Disposition disposition;
        DecodeException(Disposition disposition, String rule) { super(rule); this.disposition = disposition; }
        public Disposition disposition() { return disposition; }
    }
}
