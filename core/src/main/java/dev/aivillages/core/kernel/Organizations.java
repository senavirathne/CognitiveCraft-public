package dev.aivillages.core.kernel;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

import static dev.aivillages.core.kernel.Contracts.*;

/** IMP-023 contract 1, additive to architecture 0.5. Data scopes confer no action authority. */
public final class Organizations {
    public static final int SCHEMA = 1, POLICY_VERSION = 1;
    public static final String DOMAIN = "organization-scopes";
    public static final long MAX_TTL_MILLIS = 604_800_000;
    private Organizations() { }

    public enum Kind { ORGANIZATION, TEMPORARY_PARTY }
    public enum Participation { ACTIVE, UNKNOWN, UNLOADED, UNAVAILABLE, DEPARTED, MISSING, EXPIRED }
    public enum EventKind { CREATED, UPDATED, JOINED, LEFT, ROLE, JOB_LINKED, JOB_UNLINKED, EXPIRED }
    /** Only these host-defined vocabularies are readable; model-generated policy text is not a policy. */
    public enum PolicyId { PRIVATE, NAMED_SHARING }
    public enum Status { APPLIED, PENDING, UNCHANGED, CONFLICT, DENIED, INVALID, MISSING_REFERENCE,
        EXPIRED, LIMIT_REACHED, UNAVAILABLE }

    public record Limits(int scopes, int temporary, int members, int jobs, int grants,
                         int policyBytes, int queryResults, int history, int expirySlice,
                         long publicationMillis) {
        public Limits {
            if (scopes < 0 || scopes > 16 || temporary < 0 || temporary > 8
                    || members < 0 || members > 16 || jobs < 0 || jobs > 16
                    || grants < 0 || grants > 8 || policyBytes < 0 || policyBytes > 4096
                    || queryResults < 0 || queryResults > 8 || history < 0 || history > 8
                    || expirySlice < 0 || expirySlice > 4 || publicationMillis < 0
                    || publicationMillis > 30_000) throw new IllegalArgumentException("Organization limits");
        }
        public static Limits defaults() { return new Limits(16, 8, 16, 16, 8, 4096, 8, 8, 4, 30_000); }
    }

    /** Exact named membership invitations and view eligibility. Joining still needs the citizen owner's consent. */
    public record PolicyRef(PolicyId id, int version, Set<TrustedContext> memberOwners,
                            Set<TrustedContext> viewers) {
        public PolicyRef {
            Objects.requireNonNull(id);
            if (memberOwners.size() > 8 || viewers.size() > 8)
                throw new IllegalArgumentException("Named policy context cap");
            memberOwners = Set.copyOf(memberOwners); viewers = Set.copyOf(viewers);
            if (version != POLICY_VERSION || memberOwners.size() > 8 || viewers.size() > 8
                    || id == PolicyId.PRIVATE && (!memberOwners.isEmpty() || !viewers.isEmpty()))
                throw new IllegalArgumentException("Unknown or invalid organization policy");
        }
        public static PolicyRef privateScope() { return new PolicyRef(PolicyId.PRIVATE, POLICY_VERSION, Set.of(), Set.of()); }
        public boolean invites(TrustedContext owner, TrustedContext caller) {
            return owner.equals(caller) || id == PolicyId.NAMED_SHARING && memberOwners.contains(caller);
        }
        public boolean views(TrustedContext owner, TrustedContext caller) {
            return owner.equals(caller) || id == PolicyId.NAMED_SHARING && viewers.contains(caller);
        }
    }
    /** Actor/owner references only; citizen profiles and their availability remain with CitizenRegistry. */
    public record Member(ActorRef actor, TrustedContext owner, String role) {
        public Member { Objects.requireNonNull(actor); Objects.requireNonNull(owner); role = label(role, 32, 128, true); }
    }
    /** Explicit per-job disclosure consent from the original job owner. No copied work or control grant. */
    public record JobLink(UUID id, TrustedContext origin, Set<TrustedContext> readers) {
        public JobLink {
            Objects.requireNonNull(id); Objects.requireNonNull(origin);
            if (readers.size() > 8) throw new IllegalArgumentException("Named job reader cap");
            readers = Set.copyOf(readers);
            if (readers.size() > 8 || readers.stream().anyMatch(c -> !c.scope().worldId().equals(origin.scope().worldId())))
                throw new IllegalArgumentException("Named job readers");
        }
    }
    public record Event(long revision, EventKind kind, UUID reference) {
        public Event { Objects.requireNonNull(kind); Objects.requireNonNull(reference);
            if (revision < 1) throw new IllegalArgumentException("Organization event revision"); }
    }
    public record Scope(UUID id, long revision, TrustedContext owner, String name, Kind kind,
                        long createdAt, long expiresAt, boolean expired, PolicyRef policy,
                        ContainerRef headquarters, List<Member> members, List<JobLink> jobs,
                        List<Event> history) {
        public Scope {
            Objects.requireNonNull(id); Objects.requireNonNull(owner); Objects.requireNonNull(kind);
            Objects.requireNonNull(policy); name = label(name, 64, 256, false);
            if (members.size() > 16 || jobs.size() > 16 || history.size() > 8)
                throw new IllegalArgumentException("Organization reference cap");
            members = List.copyOf(members); jobs = List.copyOf(jobs); history = List.copyOf(history);
            if (revision < 1 || createdAt < 0 || kind == Kind.ORGANIZATION && (expiresAt != 0 || expired)
                    || kind == Kind.TEMPORARY_PARTY && (expiresAt <= createdAt
                    || expiresAt - createdAt > MAX_TTL_MILLIS)
                    || expired && !members.isEmpty() || members.size() > 16 || jobs.size() > 16 || history.size() > 8
                    || members.stream().map(m -> m.actor().citizenId()).distinct().count() != members.size()
                    || jobs.stream().map(JobLink::id).distinct().count() != jobs.size()
                    || history.stream().anyMatch(e -> e.revision() > revision)
                    || !ordered(history)) throw new IllegalArgumentException("Organization record");
            var contexts = new ArrayList<TrustedContext>();
            contexts.addAll(policy.memberOwners()); contexts.addAll(policy.viewers());
            members.forEach(m -> contexts.add(m.owner())); jobs.forEach(j -> contexts.add(j.origin()));
            if (contexts.stream().anyMatch(c -> !c.scope().worldId().equals(owner.scope().worldId())))
                throw new IllegalArgumentException("Foreign world organization reference");
        }
        public boolean expiredAt(long now) { return expired || kind == Kind.TEMPORARY_PARTY && now >= expiresAt; }
    }
    public record Snapshot(UUID worldId, long revision, List<Scope> scopes) {
        public Snapshot {
            Objects.requireNonNull(worldId);
            if (scopes.size() > 16) throw new IllegalArgumentException("Organization snapshot cap");
            scopes = scopes.stream().sorted(Comparator.comparing(s -> s.id().toString())).toList();
            if (revision < 0 || scopes.size() > 16 || scopes.stream().filter(s -> s.kind() == Kind.TEMPORARY_PARTY).count() > 8
                    || scopes.stream().map(Scope::id).distinct().count() != scopes.size()
                    || scopes.stream().anyMatch(s -> s.revision() > revision || !s.owner().scope().worldId().equals(worldId)))
                throw new IllegalArgumentException("Organization snapshot");
        }
        public static Snapshot empty(UUID world) { return new Snapshot(world, 0, List.of()); }
    }
    public record Change(Status status, Scope scope, Outcomes.Reason reason) {
        public boolean accepted() { return status == Status.PENDING || status == Status.APPLIED || status == Status.UNCHANGED; }
    }
    public record MemberView(ActorRef actor, String role, Participation participation) { }
    /** Only the organization controller can inspect private membership/policy metadata. */
    public record Inspection(Status status, Scope scope, List<MemberView> members, boolean headquartersPresent) {
        public Inspection { members = List.copyOf(members); }
    }
    /** Cursor and inspected count concern linked references in this authorized scope, never global jobs. */
    public record JobPage(Status status, List<Jobs.Job> jobs, int nextOffset, boolean more,
                          int inspected, int missingReferences) {
        public JobPage { jobs = List.copyOf(jobs); }
    }
    public record Expiry(Status status, int inspected, int changed) { }
    public static String label(String value, int points, int bytes, boolean empty) {
        Objects.requireNonNull(value);
        if (points < 0 || points > 64 || bytes < 0 || bytes > 256 || value.length() > points * 2)
            throw new IllegalArgumentException("Organization label input cap");
        if ((!empty && value.isBlank()) || value.codePointCount(0, value.length()) > points
                || value.getBytes(StandardCharsets.UTF_8).length > bytes
                || !Normalizer.isNormalized(value, Normalizer.Form.NFC)
                || value.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xD800 && c <= 0xDFFF))
            throw new IllegalArgumentException("Organization display label");
        return value;
    }
    private static boolean ordered(List<Event> history) {
        long prior = 0;
        for (Event e : history) { if (e.revision() <= prior) return false; prior = e.revision(); }
        return true;
    }
}
