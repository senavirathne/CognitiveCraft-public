package dev.aivillages.core.kernel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static dev.aivillages.core.kernel.VersionedSkillRepository.*;

/** IMP-028, policy/schema 1. Pure, finite resolution over owner-provided snapshots. */
public final class CapabilityResolver {
    private CapabilityResolver() { throw new AssertionError(); }

    public record Limits(int maxCandidates, int pageSize, int maxQueries,
                         int maxEvidenceBytes, long maxObservationAgeTicks) {
        public Limits {
            if (maxCandidates < 1 || maxCandidates > 64 || pageSize < 1 || pageSize > 32
                    || maxQueries < 1 || maxQueries > 32 || maxEvidenceBytes < 256
                    || maxEvidenceBytes > 16_384 || maxObservationAgeTicks < 0
                    || maxObservationAgeTicks > 1_200) throw new IllegalArgumentException("Resolver limits");
        }
        public static Limits defaults() { return new Limits(32, 8, 12, 8_192, 40); }
    }

    public enum Domain { DIRECT_SYNTHESIZABLE, BROAD_GOAL, UNSUPPORTED }
    /** Unavoidable effects are capability-wide, not a particular strategy's primitive list. */
    public record Envelope(Domain domain, Set<Effect> unavoidableEffects) {
        public Envelope {
            Objects.requireNonNull(domain);
            unavoidableEffects = Set.copyOf(unavoidableEffects);
        }
    }
    @FunctionalInterface public interface SupportPolicy {
        Envelope forCapability(CapabilitySpec spec);
    }
    /** Deliberately small P0 synthesis envelope. An unrelated registered outcome is unsupported. */
    public static SupportPolicy cropDeliverySupport() {
        return spec -> spec.equals(CropDelivery.SPEC)
                ? new Envelope(Domain.DIRECT_SYNTHESIZABLE,
                        Set.of(Effect.OBSERVE, Effect.HARVEST, Effect.PICKUP, Effect.TRANSFER))
                : new Envelope(Domain.UNSUPPORTED, Set.of());
    }

    /** Exact catalog; an unreadable store cannot establish a synthesis gap. An index may be primary. */
    public interface CandidateSource {
        Page page(CapabilityId capability, String afterSha256, int pageSize);
        Optional<ArtifactDescriptor> descriptor(ArtifactRef ref);
        long revision();
    }
    public static CandidateSource exactCatalog(VersionedSkillRepository repository) {
        Objects.requireNonNull(repository);
        return new CandidateSource() {
            @Override public Page page(CapabilityId id, String after, int size) {
                if (repository.status().readOnly())
                    return new Page(List.of(), "", false, repository.status().revision());
                return repository.page(id, after, size);
            }
            @Override public Optional<ArtifactDescriptor> descriptor(ArtifactRef ref) {
                ArtifactView view = repository.resolve(ref);
                return view == null || !view.usable() ? Optional.empty()
                        : Optional.of(view.artifact().descriptor());
            }
            @Override public long revision() { return repository.status().revision(); }
        };
    }

    /** These providers inspect a captured context; they cannot grant authority through an artifact. */
    @FunctionalInterface public interface ControlPolicy {
        boolean controls(ActorRef actor, TrustedContext context);
    }
    /** Initial shared skill access still requires current enrollment, independently of control. */
    public static EligibilityPolicy enrolledWorldSkills(ControlPolicy enrollment) {
        Objects.requireNonNull(enrollment);
        return (actor, artifact, context) -> enrollment.controls(actor, context);
    }
    /** Null means no observed blocker. An unknown observation must return STALE_OBSERVATION. */
    @FunctionalInterface public interface PrerequisitePolicy {
        Reason check(ValidatedRequest request, ArtifactDescriptor method, ObservationSnapshot snapshot);
    }
    public static PrerequisitePolicy cropPrerequisites() {
        return (request, method, snapshot) -> snapshot.status() == ObservationStatus.ABSENT
                && method.effects().contains(Effect.HARVEST) ? Reason.RESOURCE_MISSING : null;
    }

    public enum CandidateState { USABLE, BLOCKED, UNAUTHORIZED, INCOMPATIBLE, QUARANTINED, TEMPORARY }
    public record CandidateEvidence(ArtifactRef ref, CandidateState state, List<Reason> reasons,
                                    String runtimeFingerprint) {
        public CandidateEvidence {
            Objects.requireNonNull(ref);
            Objects.requireNonNull(state);
            reasons = List.copyOf(reasons);
            if (reasons.size() > 16 || runtimeFingerprint == null || runtimeFingerprint.length() > 128)
                throw new IllegalArgumentException("Candidate evidence");
        }
    }
    /** Selected bindings are immutable and present only for RESOLVED. Nothing here reserves an effect. */
    public record Decision(Resolution routing, ValidatedRequest selectedBindings,
                           Set<Effect> constraints, ObservationSnapshot observation,
                           long catalogRevision, List<CandidateEvidence> candidates,
                           int queriedPages, int candidateWork, boolean complete,
                           boolean fallbackUsed, Effect missingRuntimeEffect) {
        public Decision {
            Objects.requireNonNull(routing);
            constraints = Set.copyOf(constraints);
            Objects.requireNonNull(observation);
            candidates = List.copyOf(candidates);
            if ((routing.status() == ResolutionStatus.RESOLVED) != (selectedBindings != null)
                    || catalogRevision < -1 || queriedPages < 0 || candidateWork < 0)
                throw new IllegalArgumentException("Resolution evidence");
            if (missingRuntimeEffect != null && routing.status() != ResolutionStatus.UNSUPPORTED_RUNTIME)
                throw new IllegalArgumentException("Runtime evidence");
        }
    }

    /** One resolution invocation. The supplied clock counts server ticks; it is read exactly once. */
    public static final class Engine {
        private final CapabilityCatalog capabilities;
        private final PrimitiveCatalog primitives;
        private final CandidateSource primary, authoritativeFallback;
        private final SupportPolicy support;
        private final ControlPolicy control;
        private final EligibilityPolicy knowledge;
        private final AuthorityPolicy authority;
        private final PrerequisitePolicy prerequisites;
        private final LongSupplier tick;
        private final Limits limits;

        public Engine(CapabilityCatalog capabilities, PrimitiveCatalog primitives,
                      CandidateSource primary, CandidateSource authoritativeFallback,
                      SupportPolicy support, ControlPolicy control, EligibilityPolicy knowledge,
                      AuthorityPolicy authority, PrerequisitePolicy prerequisites,
                      LongSupplier tick, Limits limits) {
            this.capabilities = Objects.requireNonNull(capabilities);
            this.primitives = Objects.requireNonNull(primitives);
            this.primary = Objects.requireNonNull(primary);
            this.authoritativeFallback = authoritativeFallback;
            this.support = Objects.requireNonNull(support);
            this.control = Objects.requireNonNull(control);
            this.knowledge = Objects.requireNonNull(knowledge);
            this.authority = Objects.requireNonNull(authority);
            this.prerequisites = Objects.requireNonNull(prerequisites);
            this.tick = Objects.requireNonNull(tick);
            this.limits = Objects.requireNonNull(limits);
        }

        public Decision resolve(ValidatedRequest request, ObservationSnapshot snapshot) {
            CapabilitySpec spec = checkedRequest(request);
            Objects.requireNonNull(snapshot);
            ActorRef actor = ((ActorValue) request.request().arguments().get("actor")).value();
            long now = tick.getAsLong();
            if (now < 0) throw new IllegalArgumentException("Tick");
            if (!control.controls(actor, request.context()))
                return decision(ResolutionStatus.UNAUTHORIZED, Reason.AUTHORITY_DENIED, null,
                        request, snapshot, -1, List.of(), 0, 0, false, false);

            Envelope envelope = Objects.requireNonNull(support.forCapability(spec));
            if (envelope.domain() == Domain.UNSUPPORTED)
                return decision(ResolutionStatus.UNSUPPORTED_RUNTIME, Reason.UNSUPPORTED_PRIMITIVE,
                        null, request, snapshot, -1, List.of(), 0, 0, false, false);
            for (Effect required : Effect.values()) {
                if (envelope.unavoidableEffects().contains(required)
                        && !authority.currentlyAllows(actor, required, request.context()))
                    return decision(ResolutionStatus.UNAUTHORIZED, Reason.AUTHORITY_DENIED, null,
                            request, snapshot, -1, List.of(), 0, 0, false, false);
            }
            // Positive signature evidence for each unavoidable effect, using registered P0 signatures.
            for (Effect required : Effect.values()) {
                if (!envelope.unavoidableEffects().contains(required)) continue;
                boolean present = false;
                for (PrimitiveSignature signature : GatewayPrimitives.instance().all()) {
                    if (signature.effects().contains(required) && primitives.find(signature.id(),
                            signature.version()).filter(actual -> actual.equals(signature)).isPresent()) {
                        present = true;
                        break;
                    }
                }
                if (!present) return decision(ResolutionStatus.UNSUPPORTED_RUNTIME,
                        Reason.UNSUPPORTED_PRIMITIVE, null, request, snapshot, -1,
                        List.of(), 0, 0, false, false, required);
            }
            if (!fresh(request, snapshot, now))
                return decision(ResolutionStatus.BLOCKED, Reason.STALE_OBSERVATION, null,
                        request, snapshot, -1, List.of(), 0, 0, false, false);
            // An inspected source with no mature crop cannot support this completion predicate.
            if (spec.id().equals(CropDelivery.ID) && snapshot.status() == ObservationStatus.ABSENT)
                return decision(ResolutionStatus.BLOCKED, Reason.RESOURCE_MISSING, null,
                        request, snapshot, -1, List.of(), 0, 0, false, false);

            Counter counter = new Counter();
            Scan scan = scan(primary, spec.id(), counter);
            boolean fallback = false;
            boolean differentSource = authoritativeFallback != null && authoritativeFallback != primary;
            boolean staleIndex = differentSource && scan.complete
                    && scan.revision != authoritativeFallback.revision();
            if ((!scan.complete || staleIndex) && differentSource
                    && counter.queries < limits.maxQueries() && counter.work < limits.maxCandidates()) {
                fallback = true;
                scan = scan(authoritativeFallback, spec.id(), counter);
            }
            if (staleIndex && !fallback)
                return decision(ResolutionStatus.BLOCKED, Reason.STALE_OBSERVATION, null,
                        request, snapshot, scan.revision, List.of(), counter.queries,
                        counter.work, false, false);
            if (!scan.complete) return decision(ResolutionStatus.BLOCKED, scan.reason, null,
                    request, snapshot, scan.revision, List.of(), counter.queries, counter.work,
                    false, fallback);

            List<CandidateEvidence> evaluated = new ArrayList<>();
            for (Candidate candidate : scan.candidates) {
                CandidateEvidence evidence;
                try { evidence = evaluate(candidate, scan.source, actor, request, snapshot); }
                catch (RuntimeException unavailable) {
                    return decision(ResolutionStatus.BLOCKED, Reason.STORAGE_UNAVAILABLE, null,
                            request, snapshot, scan.revision, evaluated, counter.queries,
                            counter.work, false, fallback);
                }
                evaluated.add(evidence);
                if (evidenceBytes(evaluated) > limits.maxEvidenceBytes())
                    return decision(ResolutionStatus.BLOCKED, Reason.BUDGET_EXHAUSTED, null,
                            request, snapshot, scan.revision, List.of(), counter.queries,
                            counter.work, false, fallback);
            }
            if (scan.source.revision() != scan.revision)
                return decision(ResolutionStatus.BLOCKED, Reason.STALE_OBSERVATION, null,
                        request, snapshot, scan.revision, evaluated, counter.queries,
                        counter.work, false, fallback);
            CandidateEvidence chosen = first(evaluated, CandidateState.USABLE);
            if (chosen != null) return decision(ResolutionStatus.RESOLVED, null, chosen.ref(),
                    request, snapshot, scan.revision, evaluated, counter.queries,
                    counter.work, true, fallback);
            for (CandidateState state : List.of(CandidateState.BLOCKED, CandidateState.UNAUTHORIZED,
                    CandidateState.INCOMPATIBLE, CandidateState.QUARANTINED)) {
                chosen = first(evaluated, state);
                if (chosen != null) {
                    ResolutionStatus status = switch (state) {
                        case BLOCKED, QUARANTINED -> ResolutionStatus.BLOCKED;
                        case UNAUTHORIZED -> ResolutionStatus.UNAUTHORIZED;
                        case INCOMPATIBLE -> ResolutionStatus.INCOMPATIBLE;
                        default -> throw new AssertionError();
                    };
                    return decision(status, chosen.reasons().getFirst(), null,
                            request, snapshot, scan.revision, evaluated, counter.queries,
                            counter.work, true, fallback);
                }
            }
            return decision(envelope.domain() == Domain.BROAD_GOAL
                            ? ResolutionStatus.NEEDS_PLANNING : ResolutionStatus.MISSING_IMPLEMENTATION,
                    null, null, request, snapshot, scan.revision, evaluated, counter.queries,
                    counter.work, true, fallback);
        }

        private CapabilitySpec checkedRequest(ValidatedRequest request) {
            if (request == null) throw new IllegalArgumentException("REQUEST_INVALID");
            CapabilityRequest input = request.request();
            CapabilitySpec spec = capabilities.find(input.capability()).orElseThrow(
                    () -> new IllegalArgumentException("REQUEST_INVALID"));
            if (!spec.id().equals(input.capability()) || input.arguments().size() != spec.parameters().size()
                    || spec.parameters().stream().anyMatch(parameter -> {
                        Value value = input.arguments().get(parameter.name());
                        return value == null || value.type() != parameter.type()
                                || value instanceof IntValue integer && (integer.value() < parameter.minimum()
                                || integer.value() > parameter.maximum());
                    }) || !(input.arguments().get("actor") instanceof ActorValue actor)
                    || !actor.value().dimension().equals(request.observation().dimension()))
                throw new IllegalArgumentException("REQUEST_INVALID");
            if (spec.id().equals(CropDelivery.ID)) {
                if (!spec.equals(CropDelivery.SPEC)
                        || !(input.arguments().get("source") instanceof AreaValue area)
                        || !(input.arguments().get("destination") instanceof ContainerValue target)
                        || !area.value().dimension().equals(actor.value().dimension())
                        || !target.value().dimension().equals(actor.value().dimension()))
                    throw new IllegalArgumentException("REQUEST_INVALID");
            }
            return spec;
        }

        private boolean fresh(ValidatedRequest request, ObservationSnapshot snapshot, long now) {
            if (!snapshot.reference().equals(request.observation())
                    || snapshot.status() == ObservationStatus.UNKNOWN || now < snapshot.observedTick()
                    || now - snapshot.observedTick() > limits.maxObservationAgeTicks()) return false;
            if (!request.request().capability().equals(CropDelivery.ID)) return true;
            Cuboid source = ((AreaValue) request.request().arguments().get("source")).value();
            String expected = "source:" + java.util.UUID.nameUUIDFromBytes(
                    source.toString().getBytes(StandardCharsets.UTF_8));
            return snapshot.targetIdentity().equals(expected)
                    && snapshot.scannedCells() == (long)(source.maxX() - source.minX() + 1)
                    * (source.maxY() - source.minY() + 1) * (source.maxZ() - source.minZ() + 1)
                    && snapshot.counts().getOrDefault("unknown_cells", 0L) == 0;
        }

        private Scan scan(CandidateSource source, CapabilityId id, Counter counter) {
            List<Candidate> collected = new ArrayList<>();
            String cursor = "";
            long revision = -1;
            try {
                while (counter.queries < limits.maxQueries() && counter.work < limits.maxCandidates()) {
                    int size = Math.min(limits.pageSize(), limits.maxCandidates() - counter.work);
                    Page page = source.page(id, cursor, size);
                    counter.queries++;
                    if (page == null || page.candidates().size() > size || page.candidates().isEmpty()
                            && !page.complete() || page.revision() < 0
                            || revision >= 0 && revision != page.revision())
                        return new Scan(source, List.of(), revision, false, Reason.STORAGE_UNAVAILABLE);
                    revision = page.revision();
                    for (Candidate candidate : page.candidates()) {
                        counter.work++;
                        if (candidate == null || !candidate.ref().capability().equals(id)
                                || candidate.ref().sha256().compareTo(cursor) <= 0
                                || !collected.isEmpty() && candidate.ref().sha256().compareTo(
                                collected.getLast().ref().sha256()) <= 0
                                || candidate.compatibility() == null
                                || !candidate.compatibility().artifact().equals(candidate.ref()))
                            return new Scan(source, List.of(), revision, false, Reason.STORAGE_UNAVAILABLE);
                        collected.add(candidate);
                    }
                    if (page.complete()) {
                        if (page.nextCursor() == null || !page.nextCursor().isEmpty()
                                || source.revision() != revision)
                            return new Scan(source, List.of(), revision, false, Reason.STALE_OBSERVATION);
                        return new Scan(source, collected, revision, true, null);
                    }
                    if (page.nextCursor() == null || page.candidates().isEmpty() || !page.nextCursor()
                            .equals(collected.getLast().ref().sha256()))
                        return new Scan(source, List.of(), revision, false, Reason.STORAGE_UNAVAILABLE);
                    cursor = page.nextCursor();
                }
            } catch (RuntimeException unavailable) {
                return new Scan(source, List.of(), revision, false, Reason.STORAGE_UNAVAILABLE);
            }
            return new Scan(source, List.of(), revision, false, Reason.BUDGET_EXHAUSTED);
        }

        private CandidateEvidence evaluate(Candidate candidate, CandidateSource source, ActorRef actor,
                                           ValidatedRequest request, ObservationSnapshot snapshot) {
            ArtifactRef ref = candidate.ref();
            String fingerprint = candidate.compatibility().runtimeFingerprint();
            if (candidate.admission() == AdmissionStatus.CANDIDATE)
                return new CandidateEvidence(ref, CandidateState.TEMPORARY, List.of(), fingerprint);
            if (candidate.admission() == AdmissionStatus.QUARANTINED)
                return new CandidateEvidence(ref, CandidateState.QUARANTINED,
                        List.of(Reason.ARTIFACT_QUARANTINED), fingerprint);
            if (candidate.integrity() != Integrity.VERIFIED)
                return new CandidateEvidence(ref, CandidateState.INCOMPATIBLE,
                        List.of(candidate.integrity() == Integrity.CORRUPT
                                ? Reason.ARTIFACT_INVALID : Reason.ARTIFACT_INCOMPATIBLE), fingerprint);
            if (candidate.compatibility().status() != CompatibilityStatus.COMPATIBLE)
                return new CandidateEvidence(ref, CandidateState.INCOMPATIBLE,
                        candidate.compatibility().reasons(), fingerprint);
            ArtifactDescriptor descriptor = source.descriptor(ref).orElse(null);
            CapabilitySpec spec = capabilities.find(ref.capability()).orElseThrow();
            if (descriptor == null || !descriptor.ref().equals(ref)
                    || !descriptor.parameters().equals(spec.parameters())
                    || descriptor.resultType() != spec.resultType()
                    || !spec.effects().containsAll(descriptor.effects()))
                return new CandidateEvidence(ref, CandidateState.INCOMPATIBLE,
                        List.of(Reason.ARTIFACT_INVALID), fingerprint);
            boolean permitted = descriptor.effects().stream().allMatch(effect ->
                    authority.currentlyAllows(actor, effect, request.context()));
            boolean eligible = knowledge.mayUse(actor, ref, request.context());
            Reason unmet = prerequisites.check(request, descriptor, snapshot);
            if (unmet != null && (unmet == Reason.AUTHORITY_DENIED || unmet == Reason.KNOWLEDGE_REQUIRED
                    || unmet == Reason.REQUEST_INVALID)) throw new IllegalArgumentException("Prerequisite reason");
            List<Reason> reasons = new ArrayList<>();
            if (!eligible) reasons.add(Reason.KNOWLEDGE_REQUIRED);
            if (unmet != null) reasons.add(unmet);
            if (!permitted) reasons.add(Reason.AUTHORITY_DENIED);
            CandidateState state = !eligible || unmet != null ? CandidateState.BLOCKED
                    : !permitted ? CandidateState.UNAUTHORIZED : CandidateState.USABLE;
            return new CandidateEvidence(ref, state, reasons, fingerprint);
        }

        private Decision decision(ResolutionStatus status, Reason reason, ArtifactRef ref,
                                  ValidatedRequest request, ObservationSnapshot snapshot, long revision,
                                  List<CandidateEvidence> evidence, int queries, int work,
                                  boolean complete, boolean fallback) {
            return decision(status, reason, ref, request, snapshot, revision, evidence, queries,
                    work, complete, fallback, null);
        }
        private Decision decision(ResolutionStatus status, Reason reason, ArtifactRef ref,
                                  ValidatedRequest request, ObservationSnapshot snapshot, long revision,
                                  List<CandidateEvidence> evidence, int queries, int work,
                                  boolean complete, boolean fallback, Effect missingEffect) {
            String token = snapshot.reference().snapshotId() + ":" + snapshot.reference().revision()
                    + ":" + revision;
            return new Decision(new Resolution(status, reason, ref, token),
                    status == ResolutionStatus.RESOLVED ? request : null,
                    capabilities.find(request.request().capability()).orElseThrow().effects(), snapshot,
                    revision, evidence, queries, work, complete, fallback, missingEffect);
        }

        private static CandidateEvidence first(List<CandidateEvidence> candidates, CandidateState state) {
            return candidates.stream().filter(candidate -> candidate.state() == state)
                    .min(Comparator.comparing(candidate -> candidate.ref().sha256())).orElse(null);
        }
        private static int evidenceBytes(List<CandidateEvidence> candidates) {
            int bytes = 0;
            for (CandidateEvidence evidence : candidates) {
                bytes += 128 + evidence.runtimeFingerprint().getBytes(StandardCharsets.UTF_8).length;
                for (Reason reason : evidence.reasons()) bytes += reason.name().length();
            }
            return bytes;
        }
        private static final class Counter { int queries, work; }
        private record Scan(CandidateSource source, List<Candidate> candidates, long revision,
                            boolean complete, Reason reason) { }
    }
}
