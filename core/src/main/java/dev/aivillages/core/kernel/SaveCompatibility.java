package dev.aivillages.core.kernel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import static dev.aivillages.core.kernel.Contracts.*;

/** IMP-015 policy 1. Registered readers and owner-only, off-thread, quiescent migration coordination. */
public final class SaveCompatibility {
    public static final int POLICY = 1;
    public static final String BOOTSTRAP = "bootstrap", CITIZENS = "citizens";
    private SaveCompatibility() { }

    public record Format(String domain, long version) {
        public Format {
            if (domain == null || !domain.matches("[a-z][a-z0-9-]{0,63}") || version < 1)
                throw new IllegalArgumentException("Save format");
        }
    }
    public record Reader(String domain, int current, Set<Integer> supported) {
        public Reader {
            new Format(domain, current); supported = Set.copyOf(supported);
            if (current > 1_000_000 || supported.size() > 16 || !supported.contains(current)
                    || supported.stream().anyMatch(v -> v < 1 || v > 1_000_000))
                throw new IllegalArgumentException("Registered reader");
        }
    }
    public record Rule(Format source, Format target, boolean executableSemanticsChange) {
        public Rule {
            Objects.requireNonNull(source); Objects.requireNonNull(target);
            if (!source.domain().equals(target.domain()) || source.equals(target))
                throw new IllegalArgumentException("Migration rule");
        }
    }
    /** Immutable registration: future domain owners add explicit readers/rules, never guessed defaults. */
    public record Registry(List<Reader> readers, List<Rule> rules) {
        public Registry {
            readers = List.copyOf(readers); rules = List.copyOf(rules);
            if (readers.size() > 16 || rules.size() > 16
                    || readers.stream().map(Reader::domain).distinct().count() != readers.size()
                    || rules.stream().map(Rule::source).distinct().count() != rules.size())
                throw new IllegalArgumentException("Compatibility registrations");
            for (Rule rule : rules) {
                Reader reader = readers.stream().filter(r -> r.domain().equals(rule.source().domain()))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("Missing reader"));
                if (!reader.supported().contains((int) rule.source().version())
                        || rule.source().version() > 1_000_000 || rule.target().version() != reader.current())
                    throw new IllegalArgumentException("Unregistered migration versions");
            }
        }
        public boolean readable(Format format) {
            return format.version() <= 1_000_000 && readers.stream().anyMatch(r ->
                    r.domain().equals(format.domain()) && r.supported().contains((int) format.version()));
        }
        public Optional<Rule> migration(Format format) {
            return rules.stream().filter(r -> r.source().equals(format)).findFirst();
        }
    }
    private static final Rule BOOTSTRAP_HANDOFF = new Rule(new Format(BOOTSTRAP, BootstrapJournal.SCHEMA),
            new Format(BOOTSTRAP, BootstrapJournal.IDENTITY_REFERENCE_SCHEMA), false);
    public static Registry registered() {
        return new Registry(List.of(new Reader(BOOTSTRAP, BootstrapJournal.IDENTITY_REFERENCE_SCHEMA,
                Set.of(BootstrapJournal.SCHEMA, BootstrapJournal.IDENTITY_REFERENCE_SCHEMA)),
                currentReader(CITIZENS, CitizenIdentityStore.SCHEMA), currentReader("skill-body", RepositoryCodec.SCHEMA),
                currentReader("skill-manifest", VersionedSkillRepository.MANIFEST_SCHEMA),
                currentReader("jobs", JobJournal.SCHEMA), currentReader("leases", ResourceLeaseJournal.SCHEMA),
                currentReader("primitive-diagnostics", PrimitiveDiagnostics.SCHEMA),
                currentReader("capability-request", RequestCodec.SCHEMA)), List.of(BOOTSTRAP_HANDOFF));
    }
    private static Reader currentReader(String domain, int schema) { return new Reader(domain, schema, Set.of(schema)); }
    public record Limits(int records, long inputBytes, long additionalBytes, int checkpoints, long durationNanos) {
        public Limits {
            if (records < 0 || records > 96 || inputBytes < 0 || inputBytes > 81_920
                    || additionalBytes < 0 || additionalBytes > 262_144
                    || checkpoints < 0 || checkpoints > 8 || durationNanos < 0 || durationNanos > 60_000_000_000L)
                throw new IllegalArgumentException("Migration limits");
        }
        public static Limits defaults() { return new Limits(96, 81_920, 262_144, 8, 5_000_000_000L); }
    }
    public enum Status {
        READY, NO_OP, APPLIED, UNSUPPORTED_VERSION, CORRUPT_SOURCE, RECOVERY_REQUIRED,
        AUTHORITY_DENIED, NOT_QUIESCENT, STALE_PLAN, CANCELLED, LIMIT_REACHED,
        SPACE_UNAVAILABLE, STORAGE_UNAVAILABLE
    }
    public enum Point { BEFORE_INSPECTION, BEFORE_TRANSFORM, BEFORE_VALIDATE,
        BEFORE_IDENTITY_COMMIT, AFTER_IDENTITY_COMMIT, BEFORE_JOURNAL_COMMIT, AFTER_JOURNAL_COMMIT }
    @FunctionalInterface public interface FaultInjector { void at(Point point) throws IOException; }
    public record Control(LongSupplier nanos, BooleanSupplier cancelled, BooleanSupplier quiescent,
                          LongSupplier freeBytes, FaultInjector faults) {
        public Control {
            Objects.requireNonNull(nanos); Objects.requireNonNull(cancelled); Objects.requireNonNull(quiescent);
            Objects.requireNonNull(freeBytes); Objects.requireNonNull(faults);
        }
        public static Control startup(BootstrapJournal journal, CitizenIdentityStore identities) {
            return new Control(System::nanoTime, () -> false, () -> true, () -> {
                try { return Math.min(journal.availableMigrationBytes(), identities.availableMigrationBytes()); }
                catch (IOException unavailable) { return 0; }
            }, point -> { });
        }
    }
    /** Host/private inspection only: no raw state, citizen/principal IDs, names, positions or program bodies. */
    public record Plan(Status status, long sourceVersion, long targetVersion, long sourceRevision,
                       long identityRevision, int records, long inputBytes, long additionalBytes,
                       String sourceSemanticSha256, List<Format> unsupportedFormats) {
        public Plan {
            Objects.requireNonNull(status); unsupportedFormats = List.copyOf(unsupportedFormats);
            if (sourceVersion < 0 || targetVersion < 0 || sourceRevision < 0 || identityRevision < 0
                    || records < 0 || records > 96 || inputBytes < 0 || inputBytes > 81_920
                    || additionalBytes < 0 || additionalBytes > 262_144 || unsupportedFormats.size() > 2
                    || sourceSemanticSha256 != null && !sourceSemanticSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Migration plan bounds");
        }
    }
    public record Report(Status status, Plan plan, int checkpoints, boolean publicationMayHaveSucceeded) {
        public Report {
            Objects.requireNonNull(status); Objects.requireNonNull(plan);
            if (checkpoints < 0 || checkpoints > 8) throw new IllegalArgumentException("Migration report bounds");
        }
        public boolean complete() { return status == Status.APPLIED || status == Status.NO_OP; }
    }
    private static Plan empty(Status status) { return new Plan(status, 0, 0, 0, 0, 0, 0, 0, null, List.of()); }

    /** Trusted startup/offline owners only. Holding both monitors also fences existing store writers. */
    public static Plan inspectBootstrap(BootstrapJournal journal, CitizenIdentityStore identities) {
        synchronized (journal) { synchronized (identities) { return inspect(journal, identities); } }
    }
    /** Private principal-scoped inspection denies the whole payload if any included identity is foreign. */
    public static Plan inspectBootstrap(BootstrapJournal journal, CitizenIdentityStore identities, TrustedContext viewer) {
        Objects.requireNonNull(viewer);
        synchronized (journal) { synchronized (identities) {
            var state = journal.state(); var citizens = identities.snapshot();
            if (!viewer.scope().worldId().equals(state.worldId())
                    || state.enrollment() != null && !state.enrollment().owner().equals(viewer)
                    || citizens.citizens().stream().anyMatch(c -> !c.owner().equals(viewer)))
                return empty(Status.AUTHORITY_DENIED);
            return inspect(journal, identities);
        } }
    }
    private static Plan inspect(BootstrapJournal journal, CitizenIdentityStore identities) {
        try {
            long version = journal.formatVersion();
            var unknown = List.of(new Format(BOOTSTRAP, version), new Format(CITIZENS, identities.formatVersion()))
                    .stream().filter(format -> !registered().readable(format)).toList();
            if (!unknown.isEmpty())
                return new Plan(Status.UNSUPPORTED_VERSION, version, 2, 0, 0, 0, 0, 0, null, unknown);
            if (journal.readOnly() || identities.readOnly()) return empty(Status.RECOVERY_REQUIRED);
            var state = journal.state(); var citizens = identities.snapshot();
            if (!state.worldId().equals(citizens.worldId())) return empty(Status.CORRUPT_SOURCE);
            if (state.externalIdentities()) {
                journal.previewIdentity(citizens);
                return new Plan(Status.NO_OP, 2, 2, state.revision(), citizens.revision(),
                        state.runs().size() + citizens.citizens().size(), 0, 0, null, List.of());
            }
            var transformed = identities.previewBootstrap(state);
            var next = journal.previewIdentity(transformed);
            long journalInput = journal.migrationInputBytes(), citizenInput = identities.migrationInputBytes();
            long input = Math.addExact(journalInput, citizenInput);
            // Conservative additional-file reservation: identity stage+backup, legacy archive,
            // bootstrap backup and bootstrap stage. Owners enforce their own encoded-byte caps.
            long additional = Math.addExact(Math.addExact(CitizenIdentityStore.migrationEncodingBytes(transformed),
                    citizenInput), Math.addExact(Math.multiplyExact(journalInput, 2),
                    BootstrapJournal.migrationEncodingBytes(next)));
            return new Plan(Status.READY, 1, 2, state.revision(), citizens.revision(),
                    state.runs().size() + transformed.citizens().size(), input, additional,
                    BootstrapJournal.semanticSha256(state), List.of());
        } catch (IOException unavailable) { return empty(Status.RECOVERY_REQUIRED); }
        catch (RuntimeException corrupt) { return empty(Status.CORRUPT_SOURCE); }
    }

    /** Domain publication stays in the existing stores. Call only on the owner's worker while quiescent. */
    public static Report migrateBootstrap(BootstrapJournal journal, CitizenIdentityStore identities,
                                          Plan expected, Limits limits, Control control) {
        Objects.requireNonNull(journal); Objects.requireNonNull(identities);
        Objects.requireNonNull(expected); Objects.requireNonNull(limits); Objects.requireNonNull(control);
        var progress = new Progress(limits, control);
        boolean publication = false; Plan plan = empty(Status.NOT_QUIESCENT);
        // Fixed lock order; existing owner methods use these same monitors and expected revisions.
        synchronized (journal) { synchronized (identities) {
            try {
                progress.check(Point.BEFORE_INSPECTION);
                progress.check(Point.BEFORE_TRANSFORM);
                plan = inspect(journal, identities);
                if (!plan.equals(expected)) return new Report(Status.STALE_PLAN, plan, progress.steps, false);
                if (plan.status() != Status.READY && plan.status() != Status.NO_OP)
                    return new Report(plan.status(), plan, progress.steps, false);
                progress.check(Point.BEFORE_VALIDATE);
                if (plan.status() == Status.NO_OP) return new Report(Status.NO_OP, plan, progress.steps, false);
                if (plan.records() > limits.records() || plan.inputBytes() > limits.inputBytes()
                        || plan.additionalBytes() > limits.additionalBytes())
                    return new Report(Status.LIMIT_REACHED, plan, progress.steps, false);
                if (control.freeBytes().getAsLong() < plan.additionalBytes())
                    return new Report(Status.SPACE_UNAVAILABLE, plan, progress.steps, false);
                progress.check(Point.BEFORE_IDENTITY_COMMIT);
                publication = true;
                var imported = identities.migrateBootstrap(journal.state());
                progress.check(Point.AFTER_IDENTITY_COMMIT);
                progress.check(Point.BEFORE_JOURNAL_COMMIT);
                journal.migrateIdentity(imported);
                progress.check(Point.AFTER_JOURNAL_COMMIT);
                return new Report(Status.APPLIED, plan, progress.steps, true);
            } catch (Stopped stopped) { return new Report(stopped.status, plan, progress.steps, publication); }
            catch (IOException unavailable) { return new Report(Status.STORAGE_UNAVAILABLE, plan, progress.steps, publication); }
            catch (RuntimeException unavailable) { return new Report(Status.RECOVERY_REQUIRED, plan, progress.steps, publication); }
        } }
    }
    public static Report migrateAtStartup(BootstrapJournal journal, CitizenIdentityStore identities) {
        return migrateBootstrap(journal, identities, inspectBootstrap(journal, identities), Limits.defaults(),
                Control.startup(journal, identities));
    }
    private static final class Stopped extends Exception {
        final Status status; Stopped(Status status) { this.status = status; }
    }
    private static final class Progress {
        final Limits limits; final Control control; final long started; int steps;
        Progress(Limits limits, Control control) { this.limits = limits; this.control = control; started = control.nanos().getAsLong(); }
        void check(Point point) throws Stopped, IOException {
            if (control.cancelled().getAsBoolean()) throw new Stopped(Status.CANCELLED);
            if (!control.quiescent().getAsBoolean()) throw new Stopped(Status.NOT_QUIESCENT);
            long elapsed = control.nanos().getAsLong() - started;
            if (elapsed < 0 || elapsed >= limits.durationNanos() || steps == limits.checkpoints())
                throw new Stopped(Status.LIMIT_REACHED);
            steps++;
            control.faults().at(point);
        }
    }
    /** Bounded owner read hook; no fallback or write is performed by format probing. */
    static long readVersion(Path current, int maximum, int initial) throws IOException {
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return initial;
        try (var input = Files.newInputStream(current, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IOException("Format probe input quota");
            Map<String, Object> object = StrictJson.object(StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(bytes)).toString());
            if (!(object.get("schema") instanceof Long version) || version < 1)
                throw new IOException("Invalid format version");
            return version;
        } catch (StrictJson.Invalid malformed) { throw new IOException("Invalid format header", malformed); }
    }
}
