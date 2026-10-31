package dev.aivillages.core.kernel.interaction;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Read-only J04 seam. An observation is neither effect authority nor a storage/citizen lease. */
public final class ReferenceClarification {
    public enum Operation { INSPECT, DEPOSIT, WITHDRAW }
    public enum Status { MATCHES, DENIED, UNKNOWN, UNSUPPORTED }
    public enum Reason { AMBIGUOUS, UNAVAILABLE, UNSUPPORTED, TYPE_CONFLICT, DEFAULT_UNCONFIRMED, STALE }
    /** J constructs this from authenticated ingress; never infer any member from a label. */
    public record Query(ConversationContext.SessionKey session, UUID request, Operation operation, long tick) {
        public Query { Objects.requireNonNull(session); Objects.requireNonNull(request); Objects.requireNonNull(operation); if (tick < 0 || tick > 9_007_199_254_740_989L) throw new IllegalArgumentException("tick"); }
    }
    /** Only already-visible metadata. No contents, coordinates, ACLs or native references. */
    public record VisibleCandidate(UUID identity, long generation, String label, String containerType) {
        public VisibleCandidate {
            Objects.requireNonNull(identity); Objects.requireNonNull(label);
            if (generation < 1 || generation > 9_007_199_254_740_991L || !ConversationContext.validUtterance(label)
                    || label.codePointCount(0,label.length()) > 64 || !List.of("CHEST","BARREL","CITIZEN").contains(containerType)) throw new IllegalArgumentException("visible metadata");
        }
    }
    public record Observation(Query query, Status status, List<VisibleCandidate> visible, boolean more, boolean configuredDefault, long validThroughTick) {
        public Observation { Objects.requireNonNull(query); Objects.requireNonNull(status); visible = List.copyOf(visible); if (visible.size() > 2) throw new IllegalArgumentException("two visible choices maximum"); }
    }
    @FunctionalInterface public interface Resolver { Observation read(Query query, Map<String,Object> candidate); }
    public sealed interface Result permits CandidateReference, Clarify { }
    /** Exact identity/generation for the *next* J admission check; confers no permission. */
    public record CandidateReference(Map<String,Object> selector, UUID identity, long generation, Query query, long validThroughTick) implements Result { }
    public record Clarify(Reason reason, List<VisibleCandidate> choices) implements Result { public Clarify { choices = List.copyOf(choices); } }

    public Result check(SelectorParser.Parsed parsed, Query query, Resolver resolver) {
        Objects.requireNonNull(parsed); Objects.requireNonNull(query);
        if (resolver == null) return clarify(Reason.UNAVAILABLE);
        Observation observed;
        try { observed = resolver.read(query, parsed.endpoint()); }
        catch (RuntimeException unavailable) { return clarify(Reason.UNAVAILABLE); }
        if (observed == null || !query.equals(observed.query()) || observed.validThroughTick() < query.tick()
                || observed.validThroughTick() > query.tick() + 2) return clarify(Reason.STALE);
        if (observed.status() != Status.MATCHES) return clarify(observed.status() == Status.UNSUPPORTED ? Reason.UNSUPPORTED : Reason.UNAVAILABLE);
        if (observed.more() || observed.visible().size() > 1) return new Clarify(Reason.AMBIGUOUS, observed.visible());
        if (observed.visible().isEmpty()) return clarify(Reason.UNAVAILABLE);
        var choice = observed.visible().get(0);
        @SuppressWarnings("unchecked") Map<String,Object> selector = (Map<String,Object>) parsed.endpoint().get("selector");
        String kind = (String) selector.get("kind");
        if (kind.equals("DEFAULT") && !observed.configuredDefault()) return clarify(Reason.DEFAULT_UNCONFIRMED);
        String type = (String) selector.get("container_type");
        if ((parsed.endpoint().get("kind").equals("CITIZEN") && !choice.containerType().equals("CITIZEN"))
                || (type != null && (choice.containerType().equals("CITIZEN") || (!type.equals("ANY_MVP") && !type.equals(choice.containerType()))))) return clarify(Reason.TYPE_CONFLICT);
        if (kind.equals("NAMED") && !SelectorNames.fold((String) selector.get("label")).equals(SelectorNames.fold(choice.label()))) return clarify(Reason.UNAVAILABLE);
        return new CandidateReference(parsed.endpoint(), choice.identity(), choice.generation(), query, observed.validThroughTick());
    }
    /** An omitted source stays absent until the resolver confirms the configured role default. */
    public Result omittedSource(Query query, String containerType, Resolver resolver) {
        Objects.requireNonNull(query);
        if (containerType == null || !List.of("CHEST","BARREL","ANY_MVP").contains(containerType) || resolver == null) return clarify(Reason.DEFAULT_UNCONFIRMED);
        Map<String,Object> endpoint = Map.of("kind","STORAGE","selector",Map.of("kind","DEFAULT","owner",Map.of("kind","ADDRESSED"),"container_type",containerType));
        Observation observed;
        try { observed = resolver.read(query, endpoint); } catch (RuntimeException unavailable) { return clarify(Reason.UNAVAILABLE); }
        if (observed == null || !observed.configuredDefault()) return clarify(Reason.DEFAULT_UNCONFIRMED);
        // A private factory prevents arbitrary maps entering the normal parsed-selector API.
        return checkDefault(endpoint, query, observed);
    }
    private Result checkDefault(Map<String,Object> endpoint, Query query, Observation o) {
        if (!query.equals(o.query()) || o.validThroughTick() < query.tick() || o.validThroughTick() > query.tick()+2) return clarify(Reason.STALE);
        if (o.status() != Status.MATCHES) return clarify(Reason.UNAVAILABLE);
        if (o.more() || o.visible().size()>1) return new Clarify(Reason.AMBIGUOUS,o.visible());
        if (o.visible().isEmpty()) return clarify(Reason.UNAVAILABLE);
        var c=o.visible().get(0); var type=((Map<?,?>)endpoint.get("selector")).get("container_type");
        if (c.containerType().equals("CITIZEN") || (!type.equals("ANY_MVP") && !type.equals(c.containerType()))) return clarify(Reason.TYPE_CONFLICT);
        return new CandidateReference(endpoint,c.identity(),c.generation(),query,o.validThroughTick());
    }
    private static Clarify clarify(Reason reason) { return new Clarify(reason,List.of()); }
}
