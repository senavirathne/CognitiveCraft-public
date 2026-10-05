package dev.aivillages.core.kernel;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Public P0 contracts, schema 1; these classes contain no Minecraft or model types. */
public final class Contracts {
    public static final int CONTRACT_VERSION = 1;
    public static final int IR_VERSION = 1;
    private Contracts() { }

    public enum Type { INT, BOOL, ACTOR, AREA, CONTAINER, UNIT }
    public enum Effect { OBSERVE, MOVE, HARVEST, PICKUP, TRANSFER }
    public enum Completion { ATTRIBUTABLE_CROP_DELIVERY }

    public record CapabilityId(String name, int version) {
        public CapabilityId {
            if (name == null || name.length() > 128
                    || !name.matches("[a-z0-9_.-]+:[a-z0-9_/.-]{1,96}")
                    || version < 1 || version > 1_000_000) throw new IllegalArgumentException("Capability ID");
        }
    }

    public record Parameter(String name, Type type, long minimum, long maximum) {
        public Parameter {
            if (name == null || !name.matches("[a-z][a-zA-Z0-9_]{0,63}"))
                throw new IllegalArgumentException("Parameter name");
            Objects.requireNonNull(type);
            if (minimum > maximum || (type != Type.INT && (minimum != 0 || maximum != 0))
                    || type == Type.UNIT) throw new IllegalArgumentException("Parameter bounds");
        }
    }

    public sealed interface Value permits IntValue, BoolValue, ActorValue, AreaValue, ContainerValue {
        Type type();
    }
    public record IntValue(long value) implements Value { public Type type() { return Type.INT; } }
    public record BoolValue(boolean value) implements Value { public Type type() { return Type.BOOL; } }
    public record ActorValue(ActorRef value) implements Value {
        public ActorValue { Objects.requireNonNull(value); }
        public Type type() { return Type.ACTOR; }
    }
    public record AreaValue(Cuboid value) implements Value {
        public AreaValue { Objects.requireNonNull(value); }
        public Type type() { return Type.AREA; }
    }
    public record ContainerValue(ContainerRef value) implements Value {
        public ContainerValue { Objects.requireNonNull(value); }
        public Type type() { return Type.CONTAINER; }
    }

    public record ActorRef(UUID citizenId, UUID entityId, String dimension) {
        public ActorRef {
            Objects.requireNonNull(citizenId);
            Objects.requireNonNull(entityId);
            requireDimension(dimension);
        }
    }
    public record PrincipalRef(UUID id) { public PrincipalRef { Objects.requireNonNull(id); } }
    public record ScopeRef(UUID worldId, UUID domainId) {
        public ScopeRef { Objects.requireNonNull(worldId); Objects.requireNonNull(domainId); }
    }
    /** Created by the server controller; never decoded from candidate IR or player text. */
    public record TrustedContext(PrincipalRef principal, ScopeRef scope) {
        public TrustedContext { Objects.requireNonNull(principal); Objects.requireNonNull(scope); }
    }
    public record ObservationRef(UUID snapshotId, long revision, String dimension) {
        public ObservationRef {
            Objects.requireNonNull(snapshotId);
            if (revision < 0) throw new IllegalArgumentException("Observation revision");
            requireDimension(dimension);
        }
    }

    public record Cuboid(String dimension, int minX, int minY, int minZ,
                         int maxX, int maxY, int maxZ) {
        public Cuboid {
            requireDimension(dimension);
            if (minX > maxX || minY > maxY || minZ > maxZ)
                throw new IllegalArgumentException("Inverted area");
            if ((long) maxX - minX + 1 > 32 || (long) maxY - minY + 1 > 32
                    || (long) maxZ - minZ + 1 > 32) throw new IllegalArgumentException("Area span");
            long volume = Math.multiplyExact(Math.multiplyExact((long) maxX - minX + 1,
                    (long) maxY - minY + 1), (long) maxZ - minZ + 1);
            if (volume > 4_096) throw new IllegalArgumentException("Area volume");
        }
    }
    public record ContainerRef(String dimension, int x, int y, int z) {
        public ContainerRef { requireDimension(dimension); }
    }
    private static void requireDimension(String dimension) {
        if (dimension == null || dimension.length() > 128
                || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_/.-]{1,96}"))
            throw new IllegalArgumentException("Dimension");
    }

    public record CapabilitySpec(CapabilityId id, List<Parameter> parameters, Type resultType,
                                 Set<Effect> effects, Completion completion) {
        public CapabilitySpec {
            Objects.requireNonNull(id);
            parameters = List.copyOf(parameters);
            Objects.requireNonNull(resultType);
            effects = Set.copyOf(effects);
            Objects.requireNonNull(completion);
            if (parameters.size() > 16
                    || parameters.stream().map(Parameter::name).distinct().count() != parameters.size())
                throw new IllegalArgumentException("Duplicate parameter");
        }
    }

    /** Untrusted fields only: no principal, authority, budget or completion assertion. */
    public record CapabilityRequest(CapabilityId capability, Map<String, Value> arguments) {
        public CapabilityRequest {
            Objects.requireNonNull(capability);
            arguments = Map.copyOf(arguments);
        }
    }
    public record ValidatedRequest(CapabilityRequest request, TrustedContext context,
                                   ObservationRef observation) {
        public ValidatedRequest {
            Objects.requireNonNull(request);
            Objects.requireNonNull(context);
            Objects.requireNonNull(observation);
        }
    }

    public record PrimitiveSignature(String id, int version, String fingerprint,
                                     List<Parameter> parameters, Type resultType, Set<Effect> effects) {
        public PrimitiveSignature {
            new CapabilityId(id, version);
            digest(fingerprint);
            parameters = List.copyOf(parameters);
            Objects.requireNonNull(resultType);
            effects = Set.copyOf(effects);
            if (parameters.size() > 16
                    || parameters.stream().map(Parameter::name).distinct().count() != parameters.size())
                throw new IllegalArgumentException("Duplicate primitive parameter");
        }
    }
    public record PrimitiveRequirement(String id, int version, String fingerprint) {
        public PrimitiveRequirement {
            new CapabilityId(id, version);
            digest(fingerprint);
        }
    }
    public record ArtifactRef(CapabilityId capability, String sha256) {
        public ArtifactRef { Objects.requireNonNull(capability); digest(sha256); }
    }
    public record ArtifactDescriptor(ArtifactRef ref, int irVersion, List<Parameter> parameters,
                                     Type resultType, Set<Effect> effects, List<ArtifactRef> dependencies,
                                     List<PrimitiveRequirement> primitives) {
        public ArtifactDescriptor {
            Objects.requireNonNull(ref);
            if (irVersion != IR_VERSION) throw new IllegalArgumentException("IR version");
            parameters = List.copyOf(parameters);
            Objects.requireNonNull(resultType);
            effects = Set.copyOf(effects);
            dependencies = List.copyOf(dependencies);
            primitives = List.copyOf(primitives);
            if (parameters.size() > 16 || dependencies.size() > 16 || primitives.size() > 64
                    || parameters.stream().map(Parameter::name).distinct().count() != parameters.size())
                throw new IllegalArgumentException("Descriptor limit");
        }
    }
    /** Metadata has no effect on ref generation. Publication is IMP-004's responsibility. */
    public record ArtifactMetadata(String modelDescriptor, String evidenceRef, long successCount) {
        public ArtifactMetadata {
            if (modelDescriptor != null && modelDescriptor.length() > 256) throw new IllegalArgumentException();
            if (evidenceRef != null && evidenceRef.length() > 256) throw new IllegalArgumentException();
            if (successCount < 0) throw new IllegalArgumentException();
        }
    }
    /** Diagnostic metadata only. Unknown optional origins remain null; it is never hashed. */
    public record Provenance(int generationContractVersion, String modelDescriptor,
                             String compilerVersion, String runtimeFingerprint, UUID originWorld,
                             List<ArtifactRef> actualDependencies, List<EvidenceRef> evidence) {
        public Provenance {
            if (generationContractVersion < 1 || compilerVersion == null || compilerVersion.isBlank()
                    || compilerVersion.length() > 128 || runtimeFingerprint == null
                    || runtimeFingerprint.isBlank() || runtimeFingerprint.length() > 128
                    || (modelDescriptor != null && modelDescriptor.length() > 256))
                throw new IllegalArgumentException("Provenance bounds");
            actualDependencies = List.copyOf(actualDependencies);
            evidence = List.copyOf(evidence);
            if (actualDependencies.size() > 16 || evidence.size() > 32)
                throw new IllegalArgumentException("Provenance size");
        }
    }
    public record SkillArtifact(ArtifactDescriptor descriptor, String canonicalIr,
                                ArtifactMetadata metadata) {
        public SkillArtifact {
            Objects.requireNonNull(descriptor);
            Objects.requireNonNull(canonicalIr);
            Objects.requireNonNull(metadata);
        }
        public SkillArtifact withMetadata(ArtifactMetadata replacement) {
            return new SkillArtifact(descriptor, canonicalIr, replacement);
        }
    }
    public record RunCorrelation(UUID runId, ArtifactRef artifact, UUID researchId) {
        public RunCorrelation { Objects.requireNonNull(runId); Objects.requireNonNull(artifact); }
    }
    public record EvidenceRef(String id, String validatorVersion, String scope) {
        public EvidenceRef {
            if (id == null || id.isBlank() || id.length() > 128 || validatorVersion == null
                    || validatorVersion.isBlank() || validatorVersion.length() > 128
                    || scope == null || scope.isBlank() || scope.length() > 128) {
                throw new IllegalArgumentException("Evidence");
            }
        }
    }
    public enum AdmissionStatus { CANDIDATE, ADMITTED, QUARANTINED }
    /** Repository-owned record shape. IMP-006 authorizes promotion; IMP-004 publishes it. */
    public record AdmissionRecord(ArtifactRef artifact, AdmissionStatus status,
                                  EvidenceRef evidence, Outcomes.Reason reason, long revision) {
        public AdmissionRecord {
            Objects.requireNonNull(artifact);
            Objects.requireNonNull(status);
            if (revision < 0) throw new IllegalArgumentException("Admission revision");
            if (status == AdmissionStatus.ADMITTED && (evidence == null || reason != null))
                throw new IllegalArgumentException("Admission requires evidence");
            if (status == AdmissionStatus.QUARANTINED && reason == null)
                throw new IllegalArgumentException("Quarantine reason");
            if (status == AdmissionStatus.CANDIDATE && reason != null)
                throw new IllegalArgumentException("Candidate reason");
        }
    }
    public enum CompatibilityStatus { COMPATIBLE, INCOMPATIBLE, UNKNOWN }
    /** A current assessment; it never rewrites the historical admission record. */
    public record Compatibility(ArtifactRef artifact, CompatibilityStatus status,
                                List<Outcomes.Reason> reasons, String runtimeFingerprint) {
        public Compatibility {
            Objects.requireNonNull(artifact);
            Objects.requireNonNull(status);
            reasons = List.copyOf(reasons);
            if (reasons.size() > 16 || (status == CompatibilityStatus.COMPATIBLE && !reasons.isEmpty())
                    || (status != CompatibilityStatus.COMPATIBLE && reasons.isEmpty())
                    || runtimeFingerprint == null || runtimeFingerprint.length() > 128)
                throw new IllegalArgumentException("Compatibility assessment");
        }
    }

    private static void digest(String hex) {
        if (hex == null || !hex.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("SHA-256");
    }

    public interface CapabilityCatalog { Optional<CapabilitySpec> find(CapabilityId id); }
    public interface PrimitiveCatalog { Optional<PrimitiveSignature> find(String id, int version); }
    public interface ArtifactCatalog { Optional<ArtifactDescriptor> find(ArtifactRef ref); }
    /** These methods may read runtime state; validation never mutates it. */
    public interface RequestEnvironment {
        boolean enrolled(ActorRef actor, TrustedContext context);
        boolean loaded(Cuboid source, ObservationRef observation);
        boolean available(ContainerRef destination, ObservationRef observation);
    }
    /** Knowledge eligibility is not physical authority. Current effect checks belong to IMP-002. */
    public interface EligibilityPolicy {
        boolean mayUse(ActorRef actor, ArtifactRef artifact, TrustedContext context);
    }
    public interface AuthorityPolicy {
        boolean currentlyAllows(ActorRef actor, Effect effect, TrustedContext context);
    }
    public interface GatewayPort {
        ActionHandle start(ValidatedRequest request, RunCorrelation run,
                           PrimitiveRequirement primitive, Map<String, Value> arguments,
                           Budgets.Ledger usage);
        ActionReceipt poll(ActionHandle handle);
        ActionReceipt cancel(ActionHandle handle);
    }
    public record ActionHandle(UUID id, UUID runId) {
        public ActionHandle { Objects.requireNonNull(id); Objects.requireNonNull(runId); }
    }
    /** A finite observation, never a live world reference. UNKNOWN cannot prove absence. */
    public enum ObservationStatus { PRESENT, ABSENT, UNKNOWN }
    public record ObservationSnapshot(ObservationRef reference, ObservationStatus status,
                                      String targetIdentity, long observedTick, int scannedCells,
                                      Map<String, Long> counts) {
        public ObservationSnapshot {
            Objects.requireNonNull(reference);
            Objects.requireNonNull(status);
            if (targetIdentity == null || targetIdentity.isBlank()
                    || targetIdentity.length() > 128 || observedTick < 0
                    || scannedCells < 0 || scannedCells > 4_096)
                throw new IllegalArgumentException("Observation limit");
            counts = Map.copyOf(counts);
            if (counts.size() > 32 || counts.entrySet().stream().anyMatch(entry ->
                    entry.getKey().length() > 64 || entry.getKey().isBlank()
                            || entry.getValue() < 0))
                throw new IllegalArgumentException("Observation counts");
        }
    }
    /** Typed result and observation are optional until a primitive has produced them. */
    public record ActionReceipt(ActionHandle handle, boolean terminal, long committedEffects,
                                List<CropDelivery.CropReceipt> cropReceipts, Outcomes.Reason reason,
                                Value result, ObservationSnapshot observation) {
        public ActionReceipt(ActionHandle handle, boolean terminal, long committedEffects,
                             List<CropDelivery.CropReceipt> cropReceipts, Outcomes.Reason reason) {
            this(handle, terminal, committedEffects, cropReceipts, reason, null, null);
        }
        public ActionReceipt {
            Objects.requireNonNull(handle);
            if (committedEffects < 0) throw new IllegalArgumentException("Effects");
            cropReceipts = List.copyOf(cropReceipts);
        }
    }
    @FunctionalInterface
    public interface GenerationPort {
        Generation.Handle generate(Generation.Request request, Budgets.InferenceLimits limits,
                                   Budgets.Ledger allowance);
        default Generation.Status status() { throw new UnsupportedOperationException("Adapter status unavailable"); }
        default Generation.Descriptor descriptor() { throw new UnsupportedOperationException("Adapter descriptor unavailable"); }
    }
}
