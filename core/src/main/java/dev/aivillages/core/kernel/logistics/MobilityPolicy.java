package dev.aivillages.core.kernel.logistics;

import java.util.*;
import static dev.aivillages.core.kernel.logistics.MobilityObservation.*;

/** Pure finite proposals. Trusted owner supplies observations and advances once per tick; no executor. */
public final class MobilityPolicy {
    public static final int MAX_TICKS=600,MAX_CANDIDATES=8,MIN_REPATH_TICKS=10;
    public static final double MAX_TRAVEL=64,MAX_ARRIVAL=1.5;
    private MobilityPolicy(){}
    public enum Mode { COME_TO_PLAYER, GO_NAMED_STORAGE }
    public enum PlaceDisposition { CLARIFY_UNSUPPORTED_LANDMARK }
    public static PlaceDisposition unsupportedLandmark(){return PlaceDisposition.CLARIFY_UNSUPPORTED_LANDMARK;}
    public record Point(String dimension,double x,double y,double z){
        public Point{Objects.requireNonNull(dimension);if(!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || dimension.length()>96 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || Math.abs(x)>29999984 || Math.abs(z)>29999984 || y< -2048 || y>2047)throw new IllegalArgumentException("position bounds");}
        public double distance(Point other){if(!dimension.equals(other.dimension))return Double.POSITIVE_INFINITY;return Math.sqrt(Math.pow(x-other.x,2)+Math.pow(y-other.y,2)+Math.pow(z-other.z,2));}
    }
    /** Stable native identity and control revision supplied by the authenticated owner, never decoded claims. */
    public record Actor(UUID world,UUID principal,UUID citizen,UUID entity,long generation,long controlRevision,String dimension){
        public Actor{Objects.requireNonNull(world);Objects.requireNonNull(principal);Objects.requireNonNull(citizen);Objects.requireNonNull(entity);new Point(dimension,0,0,0);if(generation<1 || controlRevision<0)throw new IllegalArgumentException("identity bounds");}
    }
    /** Remaining A allowance at this observation. Wider values can never widen a started session. */
    public record Limits(int maxTicks,double maxTravel,double arrivalRadius,int maxCandidates,int repathTicks){
        public Limits{if(maxTicks<1 || !Double.isFinite(maxTravel) || maxTravel<=0 || !Double.isFinite(arrivalRadius) || arrivalRadius<=0 || maxCandidates<1 || repathTicks<1)throw new IllegalArgumentException("finite positive allowance required");
            maxTicks=Math.min(MAX_TICKS,maxTicks);maxTravel=Math.min(MAX_TRAVEL,maxTravel);arrivalRadius=Math.min(MAX_ARRIVAL,arrivalRadius);maxCandidates=Math.min(MAX_CANDIDATES,maxCandidates);repathTicks=Math.max(MIN_REPATH_TICKS,repathTicks);}
        public static Limits maximum(){return new Limits(MAX_TICKS,MAX_TRAVEL,MAX_ARRIVAL,MAX_CANDIDATES,MIN_REPATH_TICKS);}
    }
    public record Goal(Mode mode,Actor actor,Point endpoint,List<Point> standing,long acceptedTick,long deadline,Limits limits){
        public Goal{Objects.requireNonNull(mode);Objects.requireNonNull(actor);Objects.requireNonNull(endpoint);Objects.requireNonNull(limits);standing=List.copyOf(standing);
            if(acceptedTick<0 || acceptedTick>Long.MAX_VALUE-MAX_TICKS || deadline<=acceptedTick || deadline>acceptedTick+limits.maxTicks || standing.isEmpty() || standing.size()>limits.maxCandidates || !standing.contains(endpoint) || standing.stream().anyMatch(p->!p.dimension.equals(actor.dimension)) || !endpoint.dimension.equals(actor.dimension))throw new IllegalArgumentException("goal bounds");
            if(mode==Mode.COME_TO_PLAYER && (standing.size()!=1 || !standing.getFirst().equals(endpoint)))throw new IllegalArgumentException("COME is one captured position");}
    }
    public static Goal come(Actor actor,Point authenticatedServerPosition,long tick,long ownerDeadline,Limits limits){return goal(Mode.COME_TO_PLAYER,actor,authenticatedServerPosition,List.of(authenticatedServerPosition),tick,ownerDeadline,limits);}
    /** Caller already selected a safe position from at most eight points around one authorized binding. */
    public static Goal storage(Actor actor,Point safeInteraction,List<Point> candidates,long tick,long ownerDeadline,Limits limits){return goal(Mode.GO_NAMED_STORAGE,actor,safeInteraction,candidates,tick,ownerDeadline,limits);}
    private static Goal goal(Mode mode,Actor actor,Point endpoint,List<Point> candidates,long tick,long deadline,Limits limits){if(tick<0 || tick>Long.MAX_VALUE-MAX_TICKS)throw new IllegalArgumentException("acceptance clock");return new Goal(mode,actor,endpoint,candidates,tick,Math.min(deadline,tick+limits.maxTicks),limits);}
    public record Facts(long tick,Actor actor,Point feet,boolean authorized,boolean loaded,boolean collisionFree,Limits remaining,long ownerDeadline){public Facts{Objects.requireNonNull(actor);Objects.requireNonNull(feet);Objects.requireNonNull(remaining);}}
    public static final class Session {
        private final Goal goal;private Point previous,lastProgress;private long lastTick,lastProgressTick,lastRepath=-1,deadline;private double traveled,travelCap,arrival;private int repaths,repathInterval;private MobilityObservation result;
        public Session(Goal goal,Point initialFeet){this.goal=Objects.requireNonNull(goal);previous=Objects.requireNonNull(initialFeet);if(!initialFeet.dimension.equals(goal.actor.dimension))throw new IllegalArgumentException("initial dimension");lastProgress=initialFeet;lastTick=goal.acceptedTick-1;lastProgressTick=goal.acceptedTick;deadline=goal.deadline;travelCap=goal.limits.maxTravel;arrival=goal.limits.arrivalRadius;repathInterval=goal.limits.repathTicks;result=observation(Status.MOVING,Blocker.NONE,goal.acceptedTick,initialFeet);}
        public Goal goal(){return goal;}
        public MobilityObservation observation(){return result;}
        public double remainingTravel(){return Math.max(0,travelCap-traveled);}
        public MobilityObservation advance(Facts f,boolean navigationDone){
            if(result.terminal())return result;
            if(f.tick<goal.acceptedTick || f.tick<lastTick)return block(Blocker.CLOCK_INVALID);
            if(f.tick==lastTick)return result;
            if(!f.actor.equals(goal.actor))return block(Blocker.IDENTITY_CHANGED);
            if(!f.feet.dimension.equals(goal.endpoint.dimension))return block(Blocker.DIMENSION_CHANGED);
            double delta=previous.distance(f.feet);traveled+=delta;previous=f.feet;lastTick=f.tick;
            if(delta>=.01){lastProgress=f.feet;lastProgressTick=f.tick;}
            deadline=Math.min(deadline,Math.min(f.ownerDeadline,f.tick>Long.MAX_VALUE-f.remaining.maxTicks?Long.MAX_VALUE:f.tick+f.remaining.maxTicks));
            travelCap=Math.min(travelCap,traveled+f.remaining.maxTravel);arrival=Math.min(arrival,f.remaining.arrivalRadius);repathInterval=Math.max(repathInterval,f.remaining.repathTicks);
            if(!f.authorized)return block(Blocker.UNAUTHORIZED);
            if(f.tick>=deadline)return block(Blocker.DEADLINE);
            if(traveled>travelCap)return block(Blocker.TRAVEL_LIMIT);
            if(!f.loaded)return block(Blocker.UNLOADED);
            if(!f.collisionFree)return block(Blocker.COLLISION);
            if(f.feet.distance(goal.endpoint)<=arrival)return result=observation(Status.ARRIVED,Blocker.NONE,f.tick,f.feet);
            if(traveled>=travelCap)return block(Blocker.TRAVEL_LIMIT);
            if(f.tick-lastProgressTick>=100)return block(Blocker.NO_PROGRESS);
            if(navigationDone && (lastRepath<0 || f.tick-lastRepath>=repathInterval)){lastRepath=f.tick;repaths++;return result=observation(Status.PROPOSE_ROUTE,Blocker.NONE,f.tick,f.feet);}
            return result=observation(Status.MOVING,Blocker.NONE,f.tick,f.feet);
        }
        public MobilityObservation block(Blocker reason){if(reason==Blocker.NONE)throw new IllegalArgumentException("reason required");if(!result.terminal())result=observation(Status.BLOCKED,reason,Math.max(goal.acceptedTick,lastTick),previous);return result;}
        private MobilityObservation observation(Status state,Blocker reason,long tick,Point feet){return new MobilityObservation(state,reason,tick,feet,goal.endpoint,traveled,tick-goal.acceptedTick,lastProgressTick,lastProgress,repaths,goal.standing.size());}
    }
}
