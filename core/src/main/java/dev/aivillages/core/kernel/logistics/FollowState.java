package dev.aivillages.core.kernel.logistics;
import java.util.UUID;
import dev.aivillages.core.kernel.logistics.MobilityPolicy.Point;
/** Immutable observations; no effect, scheduler, restart replay or settlement authority. */
public record FollowState(UUID session,Action action,Reason reason,long tick,long acceptedTick,long deadline,
        double traveled,long pausedTicks,int observations,int repaths,Point feet,Point target,Point proposal) {
    public enum Action { STOP, WAIT, MOVE_PROPOSAL, EXPIRED }
    public enum Reason { NONE, CANCELLED, DISCONNECTED, PAUSE_LIMIT, DEADLINE, TRAVEL_LIMIT, IDENTITY_CHANGED,
        DIMENSION_CHANGED, DEAD, UNLOADED, UNAUTHORIZED, RESOURCE_RESERVED, CLOCK_INVALID, UNREACHABLE, NO_PROGRESS }
    public boolean terminal(){return action==Action.STOP || action==Action.EXPIRED;}
}
