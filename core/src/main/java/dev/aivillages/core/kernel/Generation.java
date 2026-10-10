package dev.aivillages.core.kernel;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static dev.aivillages.core.kernel.Contracts.*;

/** IMP-005 transport contract, version 1. Candidate bytes are untrusted, never admitted. */
public final class Generation {
    public static final int SCHEMA = 1;
    private Generation() { }

    public enum Role { INITIAL, REPAIR }
    public enum State { DISABLED, UNAVAILABLE, READY, STARTING, IN_FLIGHT, CANCELLING, CLOSED }
    public enum Outcome { CANDIDATE, MALFORMED, LIMIT, MODEL_UNAVAILABLE, TRANSPORT_FAILED,
                          ABANDONED, CANCELLED }
    public enum Compute { NOT_STARTED, RUNNING, STOP_UNCONFIRMED, STOP_CONFIRMED, COMPLETED }
    public enum Precision { MEASURED, UNKNOWN }

    public record Descriptor(String protocol, String model, String digest) {
        public Descriptor {
            if (protocol == null || !protocol.matches("[a-z0-9][a-z0-9._-]{0,63}")
                    || model == null || model.isBlank()
                    || model.length() > 128 || (digest != null && digest.length() > 128))
                throw new IllegalArgumentException("Model descriptor");
        }
    }
    /** The trusted request is retained for routing, never serialized into the backend prompt. */
    public record Request(UUID id, ValidatedRequest bound, CapabilitySpec capability,
                          List<PrimitiveSignature> primitives, List<ArtifactDescriptor> dependencies,
                          Role role, String context) {
        public Request {
            Objects.requireNonNull(id);
            Objects.requireNonNull(bound);
            Objects.requireNonNull(capability);
            Objects.requireNonNull(role);
            primitives = List.copyOf(primitives);
            dependencies = List.copyOf(dependencies);
            if (!capability.id().equals(bound.request().capability()) || primitives.size() > 32
                    || dependencies.size() > 16 || primitives.stream().distinct().count() != primitives.size()
                    || dependencies.stream().map(d -> d.ref()).distinct().count() != dependencies.size()
                    || context == null || context.getBytes(StandardCharsets.UTF_8).length > 8_192)
                throw new IllegalArgumentException("Generation request envelope");
        }
    }
    /** Token counts are UNKNOWN unless supplied by the backend; byte counts are measured. */
    public record Usage(long inputBytes, long outputBytes, long promptTokens, long outputTokens,
                        Precision tokenPrecision) {
        public Usage {
            if (inputBytes < 0 || outputBytes < 0 || promptTokens < -1 || outputTokens < -1
                    || tokenPrecision == Precision.MEASURED && (promptTokens < 0 || outputTokens < 0))
                throw new IllegalArgumentException("Generation usage");
            Objects.requireNonNull(tokenPrecision);
        }
    }
    public record Result(UUID id, TrustedContext owner, Role role, Outcome outcome,
                         Outcomes.Reason reason, String candidateIr, Descriptor descriptor,
                         Usage usage, Compute compute) {
        public Result {
            Objects.requireNonNull(id); Objects.requireNonNull(owner); Objects.requireNonNull(role);
            Objects.requireNonNull(outcome); Objects.requireNonNull(descriptor);
            Objects.requireNonNull(usage); Objects.requireNonNull(compute);
            if (outcome == Outcome.CANDIDATE ? candidateIr == null || reason != null
                    : candidateIr != null || reason == null)
                throw new IllegalArgumentException("Generation result shape");
        }
    }
    public record Status(State state, int waiting, UUID active, Compute compute,
                         boolean loadSupported, boolean unloadSupported) { }

    public interface Handle {
        UUID id();
        CompletionStage<Result> result();
        /** Abandons caller interest at once. True means this call changed its state. */
        boolean cancel();
        /** Current compute status can change after a terminal caller result. */
        Compute compute();
    }
}
