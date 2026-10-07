package dev.aivillages.core.kernel;

import java.time.Clock;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.WorldReferenceResolver.*;
import static dev.aivillages.core.kernel.WorldReferenceDiscovery.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;

/** Ephemeral input orchestration. Selection is read-only; naming dispatches to the existing registry owner. */
public final class WorldReferenceBinding implements LanguageRequests.ResolutionHandle {
    private static final Criteria CONTROLLED_ACTOR = new Criteria("cognitivecraft:controlled_actor");
    private static final Criteria UNNAMED_ACTOR = new Criteria("cognitivecraft:unnamed_actor");
    private static final Criteria WHEAT_REGION = new Criteria("cognitivecraft:mature_wheat_region");
    private static final Criteria WHEAT_CONTAINER = new Criteria("cognitivecraft:wheat_container");

    private enum Stage { ACTOR, SOURCE, DESTINATION, VALIDATING, NAMING, COMPLETE }

    private final WorldReferenceDiscovery.Observation world;
    private final CitizenRegistry citizens;
    private final TrustedContext caller;
    private final java.util.function.BooleanSupplier callerCurrent;
    private final LanguageRequests.Intent intent;
    private final Clock clock;
    private final Point callerAnchor;
    private final long deadlineMillis;

    private Stage stage;
    private Attempt<WorldReferenceDiscovery.CitizenTarget> actorAttempt;
    private Attempt<WorldReferenceDiscovery.WheatField> sourceAttempt;
    private Attempt<WorldReferenceDiscovery.ContainerTarget> destinationAttempt;
    private ActorRef actor;
    private Cuboid source;
    private ContainerRef destination;
    private Point actorAnchor;
    private Point sourceAnchor;
    private boolean automaticActor;
    private boolean automaticSource;
    private boolean automaticDestination;
    private boolean renameStarted;
    private boolean cancelled;
    private LanguageRequests.Resolution terminal;
    private final Allowance allowance = new Allowance(TOTAL_WORK);
    private WorldReferenceDiscovery.WheatField sourceEvidence;
    private WorldReferenceDiscovery.ContainerSample destinationEvidence;
    private WorldReferenceDiscovery.FieldValidation sourceValidation;
    private boolean sourceValidated;
    private SurvivalGateway.ActorState observedActor;
    private int initialWork;
    private int pollAllowance = WORK_PER_POLL;

    public WorldReferenceBinding(WorldReferenceDiscovery.Observation world, CitizenRegistry citizens,
                                 TrustedContext caller, Point callerAnchor,
                                 LanguageRequests.Intent intent, Clock clock,
                                 java.util.function.BooleanSupplier callerCurrent) {
        this.world = Objects.requireNonNull(world);
        this.citizens = Objects.requireNonNull(citizens);
        this.caller = Objects.requireNonNull(caller);
        this.callerCurrent = Objects.requireNonNull(callerCurrent);
        this.intent = Objects.requireNonNull(intent);
        this.clock = Objects.requireNonNull(clock);
        this.callerAnchor = Objects.requireNonNull(callerAnchor);
        long now = clock.millis();
        long deadline;
        try { deadline = Math.addExact(now, RESOLUTION_MILLIS); }
        catch (ArithmeticException overflow) { deadline = Long.MAX_VALUE; }
        deadlineMillis = deadline;
        beginActor();
        initialWork = Math.toIntExact(allowance.used());
    }

    @Override public LanguageRequests.Resolution poll() {
        if (terminal != null) return terminal;
        if (cancelled)
            return terminal = LanguageRequests.Resolution.rejected(Reason.CANCELLED,
                    "World-reference resolution cancelled");
        if (actor != null && !citizens.controls(actor,caller))
            return fail(Reason.AUTHORITY_DENIED,"Citizen control changed during reference resolution");
        if (!callerCurrent.getAsBoolean()) return fail(Reason.STALE_OBSERVATION, "Caller observation changed");
        if (clock.millis() > deadlineMillis)
            return fail(Reason.BUDGET_EXHAUSTED, "World-reference resolution deadline exhausted");
        if (allowance.remaining() == 0)
            return fail(Reason.BUDGET_EXHAUSTED, "World-reference resolution work exhausted");
        pollAllowance = WORK_PER_POLL - initialWork;
        initialWork = 0;
        return switch (stage) {
            case ACTOR -> pollActor();
            case SOURCE -> pollSource();
            case DESTINATION -> pollDestination();
            case VALIDATING -> finalizeHarvest();
            case NAMING -> pollNaming();
            case COMPLETE -> terminal;
        };
    }

    @Override public boolean cancellable() {
        return terminal == null && !renameStarted && !cancelled;
    }

    @Override public boolean cancel() {
        if (terminal != null) return true;
        if (renameStarted) return false; // Registry publication has already been requested.
        cancelled = true;
        if (actorAttempt != null) actorAttempt.cancel();
        if (sourceAttempt != null) sourceAttempt.cancel();
        if (destinationAttempt != null) destinationAttempt.cancel();
        return true;
    }

    private void beginActor() {
        WorldReferenceResolver.Reference<ActorRef> reference = intent instanceof LanguageRequests.HarvestIntent h
                ? h.actor() : ((LanguageRequests.NamingIntent)intent).actor();
        if (reference.state() == ReferenceState.EXPLICIT_CONCRETE) {
            actor = reference.concrete();
            automaticActor = false;
            if (!citizens.controls(actor, caller)) {
                fail(Reason.AUTHORITY_DENIED, "Explicit citizen is outside current control"); return;
            }
            if (!currentActorEligible(actor, false)) {
                terminal = LanguageRequests.Resolution.rejected(Reason.ACTOR_UNAVAILABLE,
                        "Explicit citizen is not currently available");
                stage = Stage.COMPLETE;
                return;
            }
            actorAnchor = actorPoint(actor);
            afterActor();
            return;
        }
        if (reference.state() != ReferenceState.OMITTED
                && reference.state() != ReferenceState.EXPLICIT_NEAREST) {
            terminal = LanguageRequests.Resolution.clarification(
                    "Choose a supported citizen reference", List.of());
            stage = Stage.COMPLETE;
            return;
        }
        automaticActor = true;
        boolean naming = intent instanceof LanguageRequests.NamingIntent;
        allowance.debit(CitizenRegistry.MAX_CITIZENS); // Registry enumeration is finite and part of this attempt.
        Registry registry = new Registry();
        Criteria criteria = naming ? UNNAMED_ACTOR : CONTROLLED_ACTOR;
        registry.register(Kind.CITIZEN, criteria, search ->
                WorldReferenceDiscovery.citizens(citizens.controlled(caller), callerAnchor, naming, world));
        actorAttempt = registry.start(search(Kind.CITIZEN, callerAnchor, criteria), clock.millis(), allowance);
        stage = Stage.ACTOR;
    }

    private LanguageRequests.Resolution pollActor() {
        Result<WorldReferenceDiscovery.CitizenTarget> result = actorAttempt.poll(clock.millis(),pollAllowance);
        if (result.phase() == Phase.RESOLVING)
            return LanguageRequests.Resolution.pending("Resolving nearest eligible citizen");
        if (result.phase() != Phase.RESOLVED)
            return fail(result.reason() == null ? Reason.ACTOR_UNAVAILABLE : result.reason(),
                    "No eligible loaded controlled citizen found");
        actor = result.value().actor();
        if (!currentActorEligible(actor, intent instanceof LanguageRequests.NamingIntent))
            return fail(Reason.STALE_OBSERVATION, "Selected citizen changed before binding");
        actorAnchor = result.value().capturedPosition();
        afterActor();
        return LanguageRequests.Resolution.pending(stage == Stage.NAMING
                ? "Citizen selected; validating name publication"
                : "Citizen selected; resolving source");
    }

    private void afterActor() {
        if (intent instanceof LanguageRequests.NamingIntent) {
            stage = Stage.NAMING;
            return;
        }
        LanguageRequests.HarvestIntent harvest = (LanguageRequests.HarvestIntent)intent;
        if (harvest.source().state() == ReferenceState.EXPLICIT_CONCRETE) {
            try {
                var a = harvest.source().concrete().from();
                var b = harvest.source().concrete().through();
                source = new Cuboid(actor.dimension(),
                        Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()),
                        Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
                if (volume(source) > MAX_REGION_CELLS)
                    throw new IllegalArgumentException("Source volume");
                automaticSource = false;
                sourceAnchor = areaAnchor(source);
                beginDestination();
            } catch (RuntimeException malformed) {
                terminal = LanguageRequests.Resolution.rejected(Reason.REQUEST_INVALID,
                        "Explicit source is invalid");
                stage = Stage.COMPLETE;
            }
            return;
        }
        if (harvest.source().state() != ReferenceState.OMITTED
                && harvest.source().state() != ReferenceState.EXPLICIT_NEAREST) {
            terminal = LanguageRequests.Resolution.clarification(
                    "Choose a supported source reference", List.of());
            stage = Stage.COMPLETE;
            return;
        }
        automaticSource = true;
        Registry registry = new Registry();
        registry.register(Kind.AREA, WHEAT_REGION,
                search -> WorldReferenceDiscovery.wheat(actor, Math.toIntExact(harvest.amount()), actorAnchor, world));
        sourceAttempt = registry.start(search(Kind.AREA, actorAnchor, WHEAT_REGION), clock.millis(), allowance);
        stage = Stage.SOURCE;
    }

    private LanguageRequests.Resolution pollSource() {
        Result<WorldReferenceDiscovery.WheatField> result = sourceAttempt.poll(clock.millis(),pollAllowance);
        if (result.phase() == Phase.RESOLVING)
            return LanguageRequests.Resolution.pending("Scanning bounded loaded area for nearest eligible wheat field");
        if (result.phase() != Phase.RESOLVED)
            return fail(result.reason() == null ? Reason.RESOURCE_MISSING : result.reason(),
                    "No sufficient eligible loaded wheat field found");
        sourceEvidence = result.value();
        source = sourceEvidence.area();
        sourceAnchor = areaAnchor(source);
        beginDestination();
        return LanguageRequests.Resolution.pending("Field selected; resolving destination container");
    }

    private void beginDestination() {
        LanguageRequests.HarvestIntent harvest = (LanguageRequests.HarvestIntent)intent;
        if (harvest.destination().state() == ReferenceState.EXPLICIT_CONCRETE) {
            var c = harvest.destination().concrete();
            destination = new ContainerRef(actor.dimension(), c.x(), c.y(), c.z());
            automaticDestination = false;
            stage = Stage.DESTINATION;
            return;
        }
        if (harvest.destination().state() != ReferenceState.OMITTED
                && harvest.destination().state() != ReferenceState.EXPLICIT_NEAREST) {
            terminal = LanguageRequests.Resolution.clarification(
                    "Choose a supported destination reference", List.of());
            stage = Stage.COMPLETE;
            return;
        }
        automaticDestination = true;
        Registry registry = new Registry();
        registry.register(Kind.CONTAINER, WHEAT_CONTAINER,
                search -> WorldReferenceDiscovery.containers(actor, Math.toIntExact(harvest.amount()), sourceAnchor, world));
        destinationAttempt = registry.start(search(
                Kind.CONTAINER, sourceAnchor, WHEAT_CONTAINER), clock.millis(), allowance);
        stage = Stage.DESTINATION;
    }

    private LanguageRequests.Resolution pollDestination() {
        if (automaticDestination) {
            Result<WorldReferenceDiscovery.ContainerTarget> result = destinationAttempt.poll(clock.millis(),pollAllowance);
            if (result.phase() == Phase.RESOLVING)
                return LanguageRequests.Resolution.pending(
                        "Scanning bounded loaded area for nearest sufficient container");
            if (result.phase() != Phase.RESOLVED)
                return fail(result.reason() == null ? Reason.FACILITY_MISSING : result.reason(),
                        "No sufficient eligible loaded container found");
            destination = result.value().container();
            destinationEvidence = result.value().evidence();
        }
        stage = Stage.VALIDATING;
        return LanguageRequests.Resolution.pending("References selected; validating bounded evidence");
    }

    private LanguageRequests.Resolution finalizeHarvest() {
        LanguageRequests.HarvestIntent harvest = (LanguageRequests.HarvestIntent)intent;
        if (!citizens.controls(actor,caller)) return fail(Reason.AUTHORITY_DENIED, "Citizen control changed");
        if (!currentActorEligible(actor, false))
            return fail(Reason.STALE_OBSERVATION, "Citizen availability changed");
        if (automaticSource && !sourceValidated) {
            if (sourceValidation == null)
                sourceValidation = new WorldReferenceDiscovery.FieldValidation(sourceEvidence,actor,world);
            int units = (int)Math.min(WORK_PER_POLL - 1, allowance.remaining());
            if (units == 0) return fail(Reason.BUDGET_EXHAUSTED,"Final source validation work exhausted");
            var checked = sourceValidation.poll(units); allowance.debit(checked.work());
            if (checked.reason() != null) return fail(checked.reason(),"Selected automatic field changed");
            if (!checked.complete()) return LanguageRequests.Resolution.pending("Revalidating selected field");
            sourceValidated = true;
            return LanguageRequests.Resolution.pending("Field evidence validated; checking destination");
        }
        int required = automaticDestination ? MAX_CONTAINER_SLOTS + 2 : 1;
        if (allowance.remaining() < required)
            return fail(Reason.BUDGET_EXHAUSTED,"Final destination validation work exhausted");
        allowance.debit(1);
        if (automaticDestination) {
            var current = world.container(actor,destination); allowance.debit(current.slotsInspected()+1);
            if (current.status() != ObservationStatus.PRESENT || current.wheatCapacity() < harvest.amount()
                    || !current.equals(destinationEvidence))
                return fail(Reason.STALE_OBSERVATION,"Selected automatic container changed");
        }
        CapabilityRequest request = new CapabilityRequest(CropDelivery.ID, Map.of(
                "actor", new ActorValue(actor), "amount", new IntValue(harvest.amount()),
                "source", new AreaValue(source), "destination", new ContainerValue(destination)));
        terminal = LanguageRequests.Resolution.request(request); stage = Stage.COMPLETE;
        return terminal;
    }

    private LanguageRequests.Resolution pollNaming() {
        LanguageRequests.NamingIntent naming = (LanguageRequests.NamingIntent)intent;
        if (!renameStarted) {
            Reason registryUnavailable = citizens.unavailableReason();
            if (registryUnavailable == Reason.BUDGET_EXHAUSTED)
                return LanguageRequests.Resolution.pending("Waiting for existing citizen registry publication");
            if (registryUnavailable != null)
                return fail(registryUnavailable,"Citizen registry unavailable for naming");
            if (!citizens.controls(actor,caller)) return fail(Reason.AUTHORITY_DENIED,"Citizen control changed during naming");
            if (!currentActorEligible(actor, automaticActor))
                return fail(Reason.STALE_OBSERVATION, "Implicit naming target changed");
            CitizenRegistry.Change change = citizens.rename(actor.citizenId(), naming.proposedName(), caller);
            if (!change.accepted())
                return fail(change.reason(), "Citizen registry rejected naming");
            renameStarted = change.pending();
            if (!change.pending()) {
                terminal = LanguageRequests.Resolution.named(
                        "Citizen named " + naming.proposedName());
                stage = Stage.COMPLETE;
                return terminal;
            }
            return LanguageRequests.Resolution.pending("Publishing citizen name through registry");
        }
        if (!citizens.ready()) {
            Reason unavailable = citizens.unavailableReason();
            if (unavailable != null && unavailable != Reason.BUDGET_EXHAUSTED)
                return fail(unavailable, "Citizen name publication failed");
            return LanguageRequests.Resolution.pending("Waiting for durable citizen name publication");
        }
        CitizenRegistry.Citizen current;
        try { current = citizens.query(actor.citizenId(), caller); }
        catch (SecurityException denied) {
            return fail(Reason.AUTHORITY_DENIED, "Citizen control changed during naming");
        }
        if (!naming.proposedName().equals(current.displayName()))
            return fail(Reason.STALE_OBSERVATION, "Published citizen identity no longer matches naming request");
        terminal = LanguageRequests.Resolution.named("Citizen named " + naming.proposedName());
        stage = Stage.COMPLETE;
        return terminal;
    }

    private LanguageRequests.Resolution fail(Reason reason, String message) {
        terminal = LanguageRequests.Resolution.rejected(
                reason == null ? Reason.TARGET_UNAVAILABLE : reason, message);
        stage = Stage.COMPLETE;
        return terminal;
    }

    private Search search(Kind kind, Point anchor, Criteria criteria) {
        return new Search(kind, Relation.NEAREST, anchor, criteria,
                new Limits(WORK_PER_POLL, TOTAL_WORK, MAX_CANDIDATES,
                        MAX_DIAGNOSTICS, deadlineMillis));
    }

    private boolean currentActorEligible(ActorRef candidate, boolean requireUnnamed) {
        if (!citizens.controls(candidate, caller)) return false;
        CitizenRegistry.Citizen record;
        try { record = citizens.query(candidate.citizenId(), caller); }
        catch (SecurityException denied) { return false; }
        if (!candidate.dimension().equals(callerAnchor.dimension()) || !record.actor().equals(candidate)
                || record.availability() != CitizenRegistry.Availability.LOADED
                || requireUnnamed && record.displayName() != null) return false;
        if (allowance.remaining() == 0) return false;
        allowance.debit(1);
        SurvivalGateway.ActorState state = observedActor = world.actor(candidate);
        return state.loaded() && state.alive() && candidate.dimension().equals(state.dimension());
    }

    private Point actorPoint(ActorRef selected) {
        SurvivalGateway.ActorState state = observedActor;
        return new Point(selected.dimension(), state.x(), state.y(), state.z());
    }

    private static Point areaAnchor(Cuboid area) {
        int x = Math.toIntExact(Math.floorDiv((long)area.minX() + area.maxX(), 2));
        int y = Math.toIntExact(Math.floorDiv((long)area.minY() + area.maxY(), 2));
        int z = Math.toIntExact(Math.floorDiv((long)area.minZ() + area.maxZ(), 2));
        return new Point(area.dimension(), x, y, z);
    }

    private static long volume(Cuboid area) { return WorldReferenceDiscovery.volume(area); }
    public long usedWork() { return allowance.used(); }
}
