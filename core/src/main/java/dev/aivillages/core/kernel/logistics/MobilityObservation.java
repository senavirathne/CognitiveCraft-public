package dev.aivillages.core.kernel.logistics;

import java.util.Objects;

/** Server observation only. Arrival is physical proximity, never custody, a save or root settlement. */
public record MobilityObservation(Status status, Blocker blocker, long tick,
        MobilityPolicy.Point feet, MobilityPolicy.Point endpoint, double travelBlocks,
        long elapsedTicks, long lastProgressTick, MobilityPolicy.Point lastProgress,
        int repathProposals, int standingCandidates) {
    public enum Status { MOVING, PROPOSE_ROUTE, ARRIVED, BLOCKED }
    public enum Blocker { NONE, UNAUTHORIZED, IDENTITY_CHANGED, DIMENSION_CHANGED, CLOCK_INVALID,
        DEADLINE, TRAVEL_LIMIT, UNLOADED, COLLISION, NO_SAFE_STANDING_POSITION,
        UNREACHABLE, NO_PROGRESS, NAVIGATION_CHANGED, STALE_STORAGE, STALE_RECIPIENT, CANCELLED }
    public MobilityObservation {
        Objects.requireNonNull(status);Objects.requireNonNull(blocker);Objects.requireNonNull(feet);
        Objects.requireNonNull(endpoint);Objects.requireNonNull(lastProgress);
        if(tick<0 || elapsedTicks<0 || lastProgressTick<0 || !Double.isFinite(travelBlocks) || travelBlocks<0 || repathProposals<0 || standingCandidates<1 || standingCandidates>8)throw new IllegalArgumentException("observation bounds");
        if((status==Status.BLOCKED)!=(blocker!=Blocker.NONE))throw new IllegalArgumentException("blocker disposition");
    }
    public boolean terminal(){return status==Status.ARRIVED || status==Status.BLOCKED;}
}
