package dev.aivillages.core.kernel;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;
import static dev.aivillages.core.kernel.WorldReferenceResolver.ReferenceState;

/** Ephemeral NLU only. Binding and submission run on the owning game thread. */
public final class LanguageRequests {
    public static final int MAX_TEXT_BYTES = 1024, MAX_OUTPUT_BYTES = 8192;
    public static final long DEADLINE_MILLIS = 30_000;
    public static final double MIN_CONFIDENCE = 0.35;

    public record Input(String text, List<String> references) {
        public Input {
            if (text == null || text.isBlank() || text.length() > MAX_TEXT_BYTES
                    || text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES
                    || text.codePoints().anyMatch(c -> Character.isISOControl(c)))
                throw new IllegalArgumentException("Language input limit");
            if (references == null || references.size() > 32) throw new IllegalArgumentException("Context limit");
            references = List.copyOf(references);
            if (references.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 128))
                throw new IllegalArgumentException("Context limit");
        }
    }

    public enum Action { HARVEST_WHEAT, NAME_CITIZEN }

    /** Untrusted semantic extraction. Reference provenance is derived independently from raw text. */
    public record Extracted(Action action, String citizen, Long amount, String from, String through,
                            String destination, String proposedName) {
        public Extracted {
            Objects.requireNonNull(action);
            if (citizen != null && citizen.length() > 128 || from != null && from.length() > 64
                    || through != null && through.length() > 64 || destination != null && destination.length() > 64
                    || proposedName != null && proposedName.length() > CitizenRegistry.MAX_NAME_BYTES)
                throw new IllegalArgumentException("Extraction bounds");
        }
        public Extracted(String citizen, Long amount, String from, String through, String destination) {
            this(Action.HARVEST_WHEAT, citizen, amount, from, through, destination, null);
        }
    }

    public record Coordinates(int x, int y, int z) {
        public Coordinates {
            if (Math.abs((long)x) > 30_000_000 || Math.abs((long)y) > 30_000_000 || Math.abs((long)z) > 30_000_000)
                throw new IllegalArgumentException("Coordinate bounds");
        }
    }
    public record AreaCoordinates(Coordinates from, Coordinates through) {
        public AreaCoordinates {
            Objects.requireNonNull(from); Objects.requireNonNull(through);
            long width = Math.abs((long)from.x()-through.x())+1;
            long height = Math.abs((long)from.y()-through.y())+1;
            long depth = Math.abs((long)from.z()-through.z())+1;
            if (width > 32 || height > 32 || depth > 32 || width * height * depth > 256)
                throw new IllegalArgumentException("Source bounds");
        }
    }

    public sealed interface Intent permits HarvestIntent, NamingIntent { }
    public record HarvestIntent(WorldReferenceResolver.Reference<ActorRef> actor, long amount,
                                WorldReferenceResolver.Reference<AreaCoordinates> source,
                                WorldReferenceResolver.Reference<Coordinates> destination) implements Intent {
        public HarvestIntent {
            Objects.requireNonNull(actor); Objects.requireNonNull(source); Objects.requireNonNull(destination);
            if (amount < 1 || amount > 64) throw new IllegalArgumentException("Quantity");
        }
    }
    public record NamingIntent(WorldReferenceResolver.Reference<ActorRef> actor,
                               String proposedName) implements Intent {
        public NamingIntent {
            Objects.requireNonNull(actor);
            proposedName = CitizenRegistry.normalizeName(proposedName);
        }
    }

    public enum ResolutionKind { PENDING, REQUEST, NAMED, CLARIFICATION, REJECTED }
    public record Resolution(ResolutionKind kind, CapabilityRequest request, Reason reason,
                             String message, List<UUID> candidates) {
        public Resolution {
            Objects.requireNonNull(kind); candidates = List.copyOf(candidates);
            if (kind == ResolutionKind.REQUEST && request == null
                    || kind != ResolutionKind.REQUEST && request != null)
                throw new IllegalArgumentException("Resolution request shape");
            if (kind == ResolutionKind.REJECTED && reason == null
                    || kind != ResolutionKind.REJECTED && reason != null)
                throw new IllegalArgumentException("Resolution reason shape");
        }
        public static Resolution pending(String message) {
            return new Resolution(ResolutionKind.PENDING, null, null, message, List.of());
        }
        public static Resolution request(CapabilityRequest request) {
            return new Resolution(ResolutionKind.REQUEST, request, null, "References resolved", List.of());
        }
        public static Resolution named(String message) {
            return new Resolution(ResolutionKind.NAMED, null, null, message, List.of());
        }
        public static Resolution clarification(String message, List<UUID> candidates) {
            return new Resolution(ResolutionKind.CLARIFICATION, null, null, message, candidates);
        }
        public static Resolution rejected(Reason reason, String message) {
            return new Resolution(ResolutionKind.REJECTED, null, reason, message, List.of());
        }
    }

    public interface ResolutionHandle {
        Resolution poll();
        /** Read only; omit unknown resolution states from cancellation completion. */
        default boolean cancellable() { return false; }
        /** True only when cancellation fenced all future resolution-side dispatch. */
        boolean cancel();
    }

    public enum Kind { EXTRACTED, CLARIFICATION, INVALID, UNAVAILABLE }
    public record Result(Kind kind, Extracted extracted, double confidence) {
        public Result { Objects.requireNonNull(kind); }
        public static Result unavailable() { return new Result(Kind.UNAVAILABLE, null, 0); }
        public static Result invalid() { return new Result(Kind.INVALID, null, 0); }
        public static Result clarification() { return new Result(Kind.CLARIFICATION, null, 0); }
    }
    public interface Handle {
        CompletionStage<Result> result();
        void cancel();
    }
    public interface Port { Handle interpret(Input input); }

    public interface Binding {
        List<String> references(TrustedContext caller);
        CitizenRegistry.Addresses address(String reference, TrustedContext caller);
        BootstrapController.Submission submit(CapabilityRequest request, TrustedContext caller);
        void cancel(UUID run, TrustedContext caller);

        /** Scoped read only; bindings without run-status access expose no submitted cancellations. */
        default List<UUID> cancellableRunIds(TrustedContext caller) { return List.of(); }

        /**
         * Default adapter preserves the former explicit-coordinate path. Runtime integrations
         * override this for bounded nearest-world discovery and naming.
         */
        default ResolutionHandle resolve(Intent intent, TrustedContext caller) {
            Resolution immediate;
            if (intent instanceof HarvestIntent harvest
                    && harvest.actor().state() == ReferenceState.EXPLICIT_CONCRETE
                    && harvest.source().state() == ReferenceState.EXPLICIT_CONCRETE
                    && harvest.destination().state() == ReferenceState.EXPLICIT_CONCRETE) {
                ActorRef actor = harvest.actor().concrete();
                AreaCoordinates area = harvest.source().concrete();
                Coordinates a = area.from(), b = area.through(), c = harvest.destination().concrete();
                try {
                    Cuboid source = new Cuboid(actor.dimension(),
                            Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()),
                            Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
                    long cells = Math.multiplyExact(Math.multiplyExact(
                            (long)source.maxX() - source.minX() + 1,
                            (long)source.maxY() - source.minY() + 1),
                            (long)source.maxZ() - source.minZ() + 1);
                    immediate = cells > 256 ? Resolution.rejected(Reason.REQUEST_INVALID, "Source exceeds request limit")
                            : Resolution.request(new CapabilityRequest(CropDelivery.ID, Map.of(
                                    "actor", new ActorValue(actor), "amount", new IntValue(harvest.amount()),
                                    "source", new AreaValue(source),
                                    "destination", new ContainerValue(new ContainerRef(
                                            actor.dimension(), c.x(), c.y(), c.z())))));
                } catch (RuntimeException invalid) {
                    immediate = Resolution.rejected(Reason.REQUEST_INVALID, "Invalid concrete references");
                }
            } else immediate = Resolution.rejected(Reason.TARGET_UNAVAILABLE,
                    "World-reference resolution is unavailable");
            Resolution fixed = immediate;
            return new ResolutionHandle() {
                @Override public Resolution poll() { return fixed; }
                @Override public boolean cancel() { return true; }
            };
        }
    }

    public enum Phase {
        INTERPRETING, RESOLVING, CLARIFICATION, REJECTED, UNAVAILABLE, SUBMITTED, NAMED, CANCELLED
    }
    public record View(UUID id, Phase phase, Reason reason, String message, List<UUID> candidates,
                       UUID runId) {
        public View { candidates = List.copyOf(candidates); }
        public String describe() {
            return "interpretation=" + id + " phase=" + phase
                    + (reason == null ? "" : " reason=" + reason) + " " + message
                    + (candidates.isEmpty() ? "" : " candidates=" + candidates)
                    + (runId == null ? "" : " run=" + runId);
        }
    }

    private record Timed(Result result, long receivedAt) { }
    private static final class Ticket {
        final UUID id = UUID.randomUUID();
        final TrustedContext owner;
        final long deadline;
        Input input;
        Map<String, CitizenRegistry.Addresses> initialAddresses;
        Handle handle;
        CompletionStage<Timed> result;
        ResolutionHandle resolution;
        View view;
        Ticket(TrustedContext owner, long deadline) {
            this.owner = owner; this.deadline = deadline;
            view = new View(id, Phase.INTERPRETING, null, "Interpreting", List.of(), null);
        }
    }

    private final Thread ownerThread = Thread.currentThread();
    private final Port port;
    private final Binding binding;
    private final Clock clock;
    private final LinkedHashMap<UUID, Ticket> tickets = new LinkedHashMap<>();
    private Ticket active;
    private boolean enabled, closed;

    public LanguageRequests(Port port, Binding binding, Clock clock) {
        this.port = port; this.binding = Objects.requireNonNull(binding); this.clock = clock;
    }

    private void thread() {
        if (Thread.currentThread() != ownerThread) throw new IllegalStateException("Game thread required");
    }

    public void enabled(boolean value) {
        thread(); enabled = value;
        if (!value && active != null) {
            if (active.resolution != null) {
                if (!active.resolution.cancel()) return;
                active.resolution = null;
            }
            finish(active, Phase.UNAVAILABLE, Reason.MODEL_UNAVAILABLE,
                    "Language inference disabled", List.of(), null, true);
        }
    }

    public View ask(String text, TrustedContext caller) {
        thread(); Objects.requireNonNull(caller);
        long deadline;
        try { deadline = Math.addExact(clock.millis(), DEADLINE_MILLIS); }
        catch (ArithmeticException overflow) { deadline = clock.millis(); }
        Ticket t = new Ticket(caller, deadline);
        if (tickets.size() == 16) {
            var evict = tickets.values().stream().filter(v -> v != active).findFirst().orElseThrow();
            tickets.remove(evict.id);
        }
        tickets.put(t.id, t);
        try { t.input = new Input(text, binding.references(caller)); }
        catch (RuntimeException malformed) {
            return finish(t, Phase.REJECTED, Reason.REQUEST_INVALID, "Input/context limit", List.of(), null, false);
        }
        if (closed || !enabled || port == null) return finish(t, Phase.UNAVAILABLE,
                Reason.MODEL_UNAVAILABLE, "Language interpreter unavailable", List.of(), null, false);
        if (active != null || deadline <= clock.millis()) return finish(t, Phase.REJECTED,
                Reason.BUDGET_EXHAUSTED, "One interpretation at a time", List.of(), null, false);
        try {
            t.initialAddresses = new HashMap<>();
            for (String ref : t.input.references())
                t.initialAddresses.put(ref.toLowerCase(Locale.ROOT), binding.address(ref, caller));
            var raw = LanguageReferenceClassifier.classify(t.input.text(), t.input.references());
            if (raw.actor().state() == ReferenceState.EXPLICIT_CONCRETE)
                t.initialAddresses.putIfAbsent(raw.actor().first().toLowerCase(Locale.ROOT),
                        binding.address(raw.actor().first(), caller));
            active = t;
            t.handle = Objects.requireNonNull(port.interpret(t.input));
            t.result = t.handle.result().handle((result, failure) -> new Timed(
                    failure == null && result != null ? result : Result.unavailable(), clock.millis()));
        } catch (RuntimeException unavailable) {
            finish(t, Phase.UNAVAILABLE, Reason.MODEL_UNAVAILABLE, "Language interpreter unavailable",
                    List.of(), null, true);
        }
        return t.view;
    }

    public boolean pending() { thread(); return active != null; }

    public void tick() {
        thread(); Ticket t = active; if (t == null) return;
        if (!enabled || closed) {
            if (t.resolution != null && !t.resolution.cancel()) {
                pollResolution(t);
                return;
            }
            finish(t, Phase.UNAVAILABLE, Reason.MODEL_UNAVAILABLE,
                    "Language inference disabled", List.of(), null, true); return;
        }
        if (t.resolution != null) {
            if (clock.millis() > t.deadline) {
                finish(t, Phase.UNAVAILABLE, Reason.ACTION_TIMEOUT,
                        "Reference-resolution deadline", List.of(), null, true);
            } else pollResolution(t);
            return;
        }
        var future = t.result.toCompletableFuture();
        if (!future.isDone()) {
            if (clock.millis() >= t.deadline) finish(t, Phase.UNAVAILABLE, Reason.ACTION_TIMEOUT,
                    "Interpretation deadline", List.of(), null, true);
            return;
        }
        Timed timed = future.join();
        if (timed.receivedAt() > t.deadline) {
            finish(t, Phase.UNAVAILABLE, Reason.ACTION_TIMEOUT,
                    "Late interpretation discarded", List.of(), null, true); return;
        }
        Result result = timed.result();
        if (result.kind() == Kind.UNAVAILABLE) {
            finish(t, Phase.UNAVAILABLE, Reason.MODEL_UNAVAILABLE,
                    "Language interpreter unavailable", List.of(), null, false);
        } else if (result.kind() == Kind.INVALID) {
            finish(t, Phase.REJECTED, Reason.REQUEST_INVALID,
                    "Invalid interpreter output", List.of(), null, false);
        } else if (result.kind() == Kind.CLARIFICATION || !Double.isFinite(result.confidence())
                || result.confidence() < MIN_CONFIDENCE || result.confidence() > 1) {
            clarify(t, "Use a supported harvest or naming request", List.of());
        } else bind(t, result.extracted());
    }

    private void bind(Ticket t, Extracted e) {
        if (e == null) { invalid(t); return; }
        LanguageReferenceClassifier.Classification classified;
        try { classified = LanguageReferenceClassifier.classify(t.input.text(), t.input.references()); }
        catch (RuntimeException invalid) { invalid(t); return; }

        if (classified.intent() == LanguageReferenceClassifier.Intent.NAME_CITIZEN) {
            bindNaming(t, e, classified); return;
        }
        if (classified.intent() != LanguageReferenceClassifier.Intent.HARVEST_WHEAT
                || e.action() != Action.HARVEST_WHEAT) {
            clarify(t, "Only registered wheat-delivery and citizen-naming intents are supported", List.of()); return;
        }
        if (invalidNumericQuantity(t.input.text())) { invalid(t); return; }
        if (e.amount() == null) {
            clarify(t, "Specify a wheat quantity", List.of()); return;
        }
        if (e.amount() < 1 || e.amount() > 64) { invalid(t); return; }
        if (!containsReference(t.input.text(), "wheat") || !groundedAmount(t.input.text(), e.amount())) {
            clarify(t, "Wheat quantity must be stated explicitly", List.of()); return;
        }

        WorldReferenceResolver.Reference<ActorRef> actor = actorReference(t, e, classified.actor());
        if (actor == null) return;
        WorldReferenceResolver.Reference<AreaCoordinates> source = areaReference(t, e, classified.source());
        if (source == null) return;
        WorldReferenceResolver.Reference<Coordinates> destination = coordinateReference(t, e, classified.destination());
        if (destination == null) return;

        try {
            t.handle = null; t.result = null;
            t.resolution = Objects.requireNonNull(binding.resolve(
                    new HarvestIntent(actor, e.amount(), source, destination), t.owner));
            t.view = new View(t.id, Phase.RESOLVING, null, "Resolving world references", List.of(), null);
            pollResolution(t);
        } catch (SecurityException denied) {
            finish(t, Phase.REJECTED, Reason.AUTHORITY_DENIED, "Current authority denied", List.of(), null, true);
        } catch (RuntimeException failed) {
            finish(t, Phase.REJECTED, Reason.STALE_OBSERVATION, "Reference resolution unavailable", List.of(), null, true);
        }
    }

    private void bindNaming(Ticket t, Extracted e,
                            LanguageReferenceClassifier.Classification classified) {
        if (e.action() != Action.NAME_CITIZEN) {
            clarify(t, "Naming request was not interpreted as naming", List.of()); return;
        }
        String proposed;
        try {
            proposed = CitizenRegistry.normalizeName(classified.proposedName());
            if (e.proposedName() == null
                    || !proposed.equals(CitizenRegistry.normalizeName(e.proposedName()))) {
                clarify(t, "The proposed citizen name must be grounded in the request", List.of()); return;
            }
        } catch (IllegalArgumentException invalid) { invalid(t); return; }
        if (classified.actor().state() == ReferenceState.EXPLICIT_UNSUPPORTED
                || classified.actor().state() == ReferenceState.AMBIGUOUS
                || classified.actor().state() == ReferenceState.INVALID) {
            clarify(t, "Choose a supported citizen reference", List.of()); return;
        }
        WorldReferenceResolver.Reference<ActorRef> actor = actorReference(t, e, classified.actor());
        if (actor == null) return;
        try {
            t.handle = null; t.result = null;
            t.resolution = Objects.requireNonNull(binding.resolve(new NamingIntent(actor, proposed), t.owner));
            t.view = new View(t.id, Phase.RESOLVING, null, "Resolving citizen to name", List.of(), null);
            pollResolution(t);
        } catch (SecurityException denied) {
            finish(t, Phase.REJECTED, Reason.AUTHORITY_DENIED, "Current authority denied", List.of(), null, true);
        }
    }

    private WorldReferenceResolver.Reference<ActorRef> actorReference(
            Ticket t, Extracted e, LanguageReferenceClassifier.Slot slot) {
        return switch (slot.state()) {
            case OMITTED -> {
                if (e.citizen() != null) {
                    clarify(t, "Interpreter cannot invent an omitted citizen binding", List.of()); yield null;
                }
                yield WorldReferenceResolver.Reference.omitted();
            }
            case EXPLICIT_NEAREST -> {
                if (e.citizen() != null) {
                    clarify(t, "Nearest citizen selection belongs to deterministic world resolution", List.of()); yield null;
                }
                yield WorldReferenceResolver.Reference.nearest(slot.evidence());
            }
            case EXPLICIT_CONCRETE -> {
                if (e.citizen() == null || !e.citizen().equalsIgnoreCase(slot.first())) {
                    clarify(t, "Citizen extraction did not match the explicit player reference", List.of()); yield null;
                }
                var addressed = binding.address(slot.first(), t.owner);
                if (addressed.status() != CitizenRegistry.AddressStatus.FOUND || addressed.more()
                        || addressed.candidates().size() != 1) {
                    clarify(t, "Choose a current permitted citizen ID", addressed.candidates().stream()
                            .map(a -> a.actor().citizenId()).toList()); yield null;
                }
                var initial = t.initialAddresses.get(slot.first().toLowerCase(Locale.ROOT));
                if (initial == null || initial.status() != CitizenRegistry.AddressStatus.FOUND
                        || initial.candidates().size() != 1
                        || !initial.candidates().getFirst().actor().equals(addressed.candidates().getFirst().actor())) {
                    clarify(t, "Citizen binding changed; submit a new complete utterance", List.of()); yield null;
                }
                yield WorldReferenceResolver.Reference.concrete(
                        addressed.candidates().getFirst().actor(), slot.evidence());
            }
            case EXPLICIT_UNSUPPORTED, AMBIGUOUS -> {
                clarify(t, "Unsupported or ambiguous explicit citizen reference", List.of()); yield null;
            }
            case INVALID -> { invalid(t); yield null; }
        };
    }

    private WorldReferenceResolver.Reference<AreaCoordinates> areaReference(
            Ticket t, Extracted e, LanguageReferenceClassifier.Slot slot) {
        return switch (slot.state()) {
            case OMITTED -> {
                if (e.from() != null || e.through() != null) {
                    clarify(t, "Interpreter cannot invent an omitted source binding", List.of());
                    yield null;
                }
                yield WorldReferenceResolver.Reference.omitted();
            }
            case EXPLICIT_NEAREST -> {
                if (e.from() != null || e.through() != null) {
                    clarify(t, "Nearest source selection belongs to deterministic world resolution", List.of());
                    yield null;
                }
                yield WorldReferenceResolver.Reference.nearest(slot.evidence());
            }
            case EXPLICIT_CONCRETE -> {
                if (e.from() == null || e.through() == null) {
                    clarify(t, "Explicit source coordinates were not retained by interpretation", List.of());
                    yield null;
                }
                try {
                    Coordinates rawFrom = coordinate(slot.first()), rawThrough = coordinate(slot.second());
                    Coordinates decodedFrom = coordinate(e.from()), decodedThrough = coordinate(e.through());
                    if (!rawFrom.equals(decodedFrom) || !rawThrough.equals(decodedThrough)) {
                        clarify(t, "Source coordinates did not match the player text", List.of());
                        yield null;
                    }
                    long volume = Math.multiplyExact(Math.multiplyExact(
                            Math.abs((long)rawFrom.x() - rawThrough.x()) + 1,
                            Math.abs((long)rawFrom.y() - rawThrough.y()) + 1),
                            Math.abs((long)rawFrom.z() - rawThrough.z()) + 1);
                    if (volume > 256) { invalid(t); yield null; }
                    yield WorldReferenceResolver.Reference.concrete(
                            new AreaCoordinates(rawFrom, rawThrough), slot.evidence());
                } catch (RuntimeException invalid) { invalid(t); yield null; }
            }
            case EXPLICIT_UNSUPPORTED, AMBIGUOUS -> {
                clarify(t, "Unsupported or ambiguous explicit source reference", List.of()); yield null;
            }
            case INVALID -> { invalid(t); yield null; }
        };
    }

    private WorldReferenceResolver.Reference<Coordinates> coordinateReference(
            Ticket t, Extracted e, LanguageReferenceClassifier.Slot slot) {
        return switch (slot.state()) {
            case OMITTED -> {
                if (e.destination() != null) {
                    clarify(t, "Interpreter cannot invent an omitted destination binding", List.of());
                    yield null;
                }
                yield WorldReferenceResolver.Reference.omitted();
            }
            case EXPLICIT_NEAREST -> {
                if (e.destination() != null) {
                    clarify(t, "Nearest destination selection belongs to deterministic world resolution", List.of());
                    yield null;
                }
                yield WorldReferenceResolver.Reference.nearest(slot.evidence());
            }
            case EXPLICIT_CONCRETE -> {
                if (e.destination() == null) {
                    clarify(t, "Explicit destination coordinates were not retained by interpretation", List.of());
                    yield null;
                }
                try {
                    Coordinates raw = coordinate(slot.first()), decoded = coordinate(e.destination());
                    if (!raw.equals(decoded)) {
                        clarify(t, "Destination coordinate did not match the player text", List.of());
                        yield null;
                    }
                    yield WorldReferenceResolver.Reference.concrete(raw, slot.evidence());
                } catch (RuntimeException invalid) { invalid(t); yield null; }
            }
            case EXPLICIT_UNSUPPORTED, AMBIGUOUS -> {
                clarify(t, "Unsupported or ambiguous explicit destination reference", List.of()); yield null;
            }
            case INVALID -> { invalid(t); yield null; }
        };
    }

    private void pollResolution(Ticket t) {
        Resolution resolved;
        try { resolved = Objects.requireNonNull(t.resolution.poll()); }
        catch (RuntimeException failed) {
            finish(t, Phase.REJECTED, Reason.STALE_OBSERVATION,
                    "Reference resolution failed", List.of(), null, true); return;
        }
        switch (resolved.kind()) {
            case PENDING -> t.view = new View(t.id, Phase.RESOLVING, null,
                    resolved.message(), List.of(), null);
            case CLARIFICATION -> finish(t, Phase.CLARIFICATION, null,
                    resolved.message(), resolved.candidates(), null, false);
            case REJECTED -> finish(t, Phase.REJECTED, resolved.reason(),
                    resolved.message(), List.of(), null, false);
            case NAMED -> finish(t, Phase.NAMED, null, resolved.message(), List.of(), null, false);
            case REQUEST -> {
                BootstrapController.Submission submitted;
                try { submitted = binding.submit(resolved.request(), t.owner); }
                catch (SecurityException denied) {
                    finish(t, Phase.REJECTED, Reason.AUTHORITY_DENIED,
                            "Current authority denied", List.of(), null, false); return;
                }
                finish(t, submitted.accepted() ? Phase.SUBMITTED : Phase.REJECTED, submitted.reason(),
                        submitted.accepted() ? "Request submitted; query run status" : "Controller rejected request",
                        List.of(), submitted.id(), false);
            }
        }
    }

    /** Immutable identifiers from the existing bounded ticket retention. */
    public List<UUID> ticketIds(TrustedContext caller) {
        thread(); Objects.requireNonNull(caller);
        return tickets.values().stream().filter(t -> t.owner.equals(caller))
                .map(t -> t.id).toList();
    }

    public List<UUID> cancellableTicketIds(TrustedContext caller) {
        thread(); Objects.requireNonNull(caller);
        List<UUID> runs = binding.cancellableRunIds(caller);
        return tickets.values().stream().filter(t -> t.owner.equals(caller))
                .filter(t -> t == active && (t.resolution == null || t.resolution.cancellable())
                        || t.view.phase() == Phase.SUBMITTED
                        && t.view.runId() != null && runs.contains(t.view.runId()))
                .map(t -> t.id).toList();
    }

    public View status(UUID id, TrustedContext caller) {
        thread(); Ticket t = tickets.get(id); if (t == null) return null;
        if (!t.owner.equals(caller)) throw new SecurityException("Private interpretation");
        return t.view;
    }

    public View cancel(UUID id, TrustedContext caller) {
        thread(); View view = status(id, caller); if (view == null) return null;
        Ticket t = tickets.get(id);
        if (t == active) {
            if (t.resolution != null) {
                if (!t.resolution.cancel()) return t.view;
                t.resolution = null;
            }
            return finish(t, Phase.CANCELLED, Reason.CANCELLED,
                    "Interpretation cancelled", List.of(), null, true);
        }
        if (view.phase() == Phase.SUBMITTED) binding.cancel(view.runId(), caller);
        return t.view;
    }

    public void close() {
        thread(); closed = true; enabled(false); tickets.clear();
    }

    private void clarify(Ticket t, String message, List<UUID> candidates) {
        finish(t, Phase.CLARIFICATION, null, message, candidates, null, false);
    }

    private void invalid(Ticket t) {
        finish(t, Phase.REJECTED, Reason.REQUEST_INVALID,
                "Invalid quantity, coordinates, name or bounds", List.of(), null, false);
    }

    private View finish(Ticket t, Phase phase, Reason reason, String message,
                        List<UUID> candidates, UUID run, boolean cancel) {
        Handle handle = t.handle; ResolutionHandle resolution = t.resolution;
        t.view = new View(t.id, phase, reason, message, candidates, run);
        t.input = null; t.initialAddresses = null; t.result = null; t.handle = null; t.resolution = null;
        if (active == t) active = null;
        if (cancel) {
            if (handle != null) try { handle.cancel(); } catch (RuntimeException ignored) { }
            if (resolution != null) try { resolution.cancel(); } catch (RuntimeException ignored) { }
        }
        return t.view;
    }

    private static boolean containsReference(String text, String ref) {
        return ref.length() <= 128 && Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(ref)
                + "(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find();
    }

    private static Coordinates coordinate(String text) {
        if (text == null || text.length() > 64
                || !text.matches("-?[0-9]+\\s*,\\s*-?[0-9]+\\s*,\\s*-?[0-9]+"))
            throw new IllegalArgumentException("Coordinate");
        int[] result = Arrays.stream(text.split(",")).map(String::strip).mapToInt(Integer::parseInt).toArray();
        for (int value : result) if (Math.abs((long)value) > 30_000_000)
            throw new IllegalArgumentException("Coordinate bound");
        return new Coordinates(result[0], result[1], result[2]);
    }

    private static boolean quantityRole(String text, String quantity) {
        return Pattern.compile("(?<![\\p{L}\\p{N}_,.+/\\-])" + Pattern.quote(quantity)
                + "\\s+(?:units?\\s+of\\s+)?wheat\\b", Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    private static boolean invalidNumericQuantity(String text) {
        var match = Pattern.compile("(\\S+)\\s+(?:units?\\s+of\\s+)?wheat\\b", Pattern.CASE_INSENSITIVE).matcher(text);
        while (match.find()) {
            String raw = match.group(1);
            if (raw.chars().noneMatch(Character::isDigit)) continue;
            if (!raw.matches("[0-9]+")) return true;
            try {
                long amount = Long.parseLong(raw);
                if (amount < 1 || amount > 64) return true;
            } catch (NumberFormatException overflow) { return true; }
        }
        return false;
    }

    private static boolean groundedAmount(String text, long amount) {
        if (quantityRole(text, String.valueOf(amount))) return true;
        String[] words = {"", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
                "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
        if (amount < words.length) return quantityRole(text, words[(int)amount]);
        String[] tens = {"", "", "twenty", "thirty", "forty", "fifty", "sixty"};
        String word = tens[(int)amount / 10] + (amount % 10 == 0 ? "" : " " + words[(int)amount % 10]);
        return quantityRole(text.replace('-', ' '), word);
    }
}
