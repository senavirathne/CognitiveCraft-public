package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;

/** Additive authority schema 1. No Minecraft objects, executable code or social authority. */
public final class Mandates {
    public static final int SCHEMA = 1, POLICY_VERSION = 1;
    public static final long MAX_TTL_MILLIS = 604_800_000, MAX_BUDGET = 1_000_000;
    private Mandates() { }

    public record Limits(int mandates, int depth, int bindings, int cleanup, int query, int history,
                         long publicationMillis) {
        public Limits {
            if (mandates < 1 || mandates > 32 || depth < 1 || depth > 4 || bindings < 1 || bindings > 64
                    || cleanup < 1 || cleanup > 4 || query < 1 || query > 8 || history < 1 || history > 8
                    || publicationMillis < 1 || publicationMillis > 30_000)
                throw new IllegalArgumentException("Finite mandate limits");
        }
        public static Limits defaults() { return new Limits(32, 4, 64, 4, 8, 8, 30_000); }
    }
    /** Area constrains bound source, named destination and actual effect targets; no path ownership. */
    public record Rights(Set<ActorRef> workers, Set<Effect> actions, Set<String> resources,
                         Cuboid area, Set<ContainerRef> containers) {
        public Rights {
            workers = Set.copyOf(workers); actions = Set.copyOf(actions);
            resources = Set.copyOf(resources); containers = Set.copyOf(containers);
            Objects.requireNonNull(area);
            if (workers.isEmpty() || workers.size() > 8 || actions.isEmpty() || actions.size() > Effect.values().length
                    || resources.isEmpty() || resources.size() > 4 || containers.isEmpty() || containers.size() > 4
                    || workers.stream().map(ActorRef::citizenId).distinct().count() != workers.size()
                    || workers.stream().anyMatch(a -> !a.dimension().equals(area.dimension()))
                    || resources.stream().anyMatch(r -> !r.matches("[a-z0-9_.-]+:[a-z0-9_/.-]{1,96}"))
                    || containers.stream().anyMatch(c -> !contains(area, c.dimension(), c.x(), c.y(), c.z())))
                throw new IllegalArgumentException("Bounded mandate rights");
        }
        public boolean subsetOf(Rights parent) {
            return parent.workers.containsAll(workers) && parent.actions.containsAll(actions)
                    && parent.resources.containsAll(resources) && parent.containers.containsAll(containers)
                    && contains(parent.area, area);
        }
        public boolean matches(ValidatedRequest request) {
            var args = request.request().arguments();
            return args.get("actor") instanceof ActorValue actor && workers.contains(actor.value())
                    && (args.get("source") instanceof AreaValue source && contains(area, source.value())
                        || args.get("source") instanceof ContainerValue box && containers.contains(box.value()))
                    && args.get("destination") instanceof ContainerValue destination && containers.contains(destination.value())
                    && args.get("amount") instanceof IntValue amount && amount.value() > 0
                    && !Mandates.resources(request).isEmpty() && resources.containsAll(Mandates.resources(request));
        }
    }
    public enum EventKind { GRANTED, RESERVED, REVALIDATED, REVOKED, EXPIRED }
    public record Event(long revision, EventKind kind, UUID subject, TrustedContext actor) {
        public Event { Objects.requireNonNull(kind); Objects.requireNonNull(subject); Objects.requireNonNull(actor);
            if (revision < 1) throw new IllegalArgumentException("Mandate event revision"); }
    }
    public record Grant(UUID id, long revision, TrustedContext issuer, TrustedContext grantee,
                        UUID parent, UUID organization, Rights rights, long issuedAt, long expiresAt,
                        Budgets.Limits budget, Map<Budgets.Kind, Long> reserved,
                        boolean revoked, List<Event> events) {
        public Grant {
            Objects.requireNonNull(id); Objects.requireNonNull(issuer); Objects.requireNonNull(grantee);
            Objects.requireNonNull(rights); Objects.requireNonNull(budget);
            reserved = Map.copyOf(reserved); events = List.copyOf(events);
            if (revision < 1 || issuedAt < 0 || expiresAt <= issuedAt
                    || expiresAt - issuedAt > MAX_TTL_MILLIS || issuer.equals(grantee)
                    || !issuer.scope().worldId().equals(grantee.scope().worldId())
                    || budget.deadlineEpochMillis() > expiresAt || events.size() > 8)
                throw new IllegalArgumentException("Mandate identity/duration");
            for (var kind : Budgets.Kind.values()) {
                long value = reserved.getOrDefault(kind, 0L);
                if (budget.maximum(kind) > MAX_BUDGET || value < 0 || value > budget.maximum(kind))
                    throw new IllegalArgumentException("Mandate cumulative budget");
            }
        }
    }
    /** One immutable reservation, tied to the original Job assignment and admitted method. */
    public record Binding(UUID run, UUID job, long generation, UUID mandate, ArtifactRef artifact,
                          ValidatedRequest request, Budgets.Limits allowance) {
        public Binding {
            Objects.requireNonNull(run); Objects.requireNonNull(job); Objects.requireNonNull(mandate);
            Objects.requireNonNull(artifact); Objects.requireNonNull(request); Objects.requireNonNull(allowance);
            if (generation < 1) throw new IllegalArgumentException("Assignment generation");
        }
    }
    public record Snapshot(UUID worldId, long revision, List<Grant> grants, List<Binding> bindings) {
        public Snapshot {
            Objects.requireNonNull(worldId); grants = List.copyOf(grants); bindings = List.copyOf(bindings);
            if (revision < 0 || grants.size() > 32 || bindings.size() > 64
                    || grants.stream().map(Grant::id).distinct().count() != grants.size()
                    || bindings.stream().map(Binding::run).distinct().count() != bindings.size())
                throw new IllegalArgumentException("Mandate snapshot");
        }
        public static Snapshot empty(UUID world) { return new Snapshot(world, 0, List.of(), List.of()); }
    }
    public enum Status { PENDING, APPLIED, DUPLICATE, DENIED, INVALID, EXPIRED, UNAVAILABLE, LIMIT_REACHED }
    public record Change(Status status, Reason reason, UUID id, long revision) {
        public boolean accepted() { return status == Status.PENDING || status == Status.APPLIED || status == Status.DUPLICATE; }
    }
    public record Evidence(UUID id, long revision, Status status, List<UUID> chain, int inspected) {
        public Evidence { chain = List.copyOf(chain); if (chain.size() > 4 || inspected < 0 || inspected > 4)
            throw new IllegalArgumentException("Bounded authority evidence"); }
    }
    public static boolean contains(Cuboid outer, Cuboid inner) {
        return outer.dimension().equals(inner.dimension()) && outer.minX() <= inner.minX() && outer.maxX() >= inner.maxX()
                && outer.minY() <= inner.minY() && outer.maxY() >= inner.maxY()
                && outer.minZ() <= inner.minZ() && outer.maxZ() >= inner.maxZ();
    }
    public static boolean contains(Cuboid area, String dimension, int x, int y, int z) {
        return area.dimension().equals(dimension) && x >= area.minX() && x <= area.maxX()
                && y >= area.minY() && y <= area.maxY() && z >= area.minZ() && z <= area.maxZ();
    }
    public static String resource(ValidatedRequest request) {
        if (request.request().capability().equals(CropDelivery.ID)) return "minecraft:wheat";
        if (request.request().capability().equals(OakLogDelivery.ID)) return "minecraft:oak_log";
        return "";
    }
    public static Set<String> resources(ValidatedRequest request) {
        if (request.request().capability().equals(OakPlankProduction.ID)) return Set.of("minecraft:oak_log", "minecraft:oak_planks");
        String resource = resource(request); return resource.isEmpty() ? Set.of() : Set.of(resource);
    }
}
