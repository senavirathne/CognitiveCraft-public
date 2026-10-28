package dev.aivillages.core.kernel.logistics;
import java.util.*;
import dev.aivillages.core.kernel.logistics.MobilityPolicy.*;
import static dev.aivillages.core.kernel.logistics.FollowState.*;
/** Finite pure session, advanced only by the current exclusive owner. STOP needs no provider. */
public final class FollowPolicy {
    public static final int MAX_TICKS=1200,MAX_PAUSED=100,INTERVAL=10;
    public static final double MAX_TRAVEL=128,LOOKAHEAD=8,INITIAL_RANGE=16;
    private FollowPolicy(){}
    public record Goal(UUID session,Actor actor,long acceptedTick,long deadline,double travelCap){
        public Goal{Objects.requireNonNull(session);Objects.requireNonNull(actor);if(acceptedTick<0 || acceptedTick>Long.MAX_VALUE-MAX_TICKS || deadline<=acceptedTick || deadline>acceptedTick+MAX_TICKS || !Double.isFinite(travelCap) || travelCap<=0 || travelCap>MAX_TRAVEL)throw new IllegalArgumentException("finite follow allowance");}
    }
    public record Facts(long tick,Actor actor,Point feet,UUID player,Point playerFeet,boolean connected,boolean alive,
        boolean loaded,boolean authorized,boolean reserved,long ownerDeadline,double remainingTravel,boolean navigationDone){
        public Facts{Objects.requireNonNull(actor);Objects.requireNonNull(feet);Objects.requireNonNull(player);Objects.requireNonNull(playerFeet);if(!Double.isFinite(remainingTravel) || remainingTravel<0)throw new IllegalArgumentException("remaining travel");}
    }
    public static Session start(UUID session,Actor actor,Point feet,Point authenticatedPlayer,long tick,long deadline,double remaining){
        if(!Double.isFinite(remaining) || remaining<=0 || tick<0 || tick>Long.MAX_VALUE-MAX_TICKS)throw new IllegalArgumentException("finite inherited allowance");
        if(!feet.dimension().equals(actor.dimension()) || !authenticatedPlayer.dimension().equals(actor.dimension()) || Math.hypot(feet.x()-authenticatedPlayer.x(),feet.z()-authenticatedPlayer.z())>INITIAL_RANGE)throw new IllegalArgumentException("same dimension and initial 16 horizontal blocks");
        return new Session(new Goal(session,actor,tick,Math.min(deadline,tick+MAX_TICKS),Math.min(remaining,MAX_TRAVEL)),feet,authenticatedPlayer);
    }
    public static final class Session {
        private final Goal goal;private Point previous,target,lastRouteTarget,lastProgress;private long lastTick,lastObserve=-1,lastRepath=-1,lastProgressTick,deadline,paused;private double traveled,cap;private int observations,repaths;private FollowState state;
        private Session(Goal goal,Point feet,Point player){this.goal=goal;previous=feet;lastProgress=feet;target=player;lastTick=goal.acceptedTick()-1;lastProgressTick=goal.acceptedTick();deadline=goal.deadline();cap=goal.travelCap();state=emit(Action.WAIT,Reason.NONE,null);}
        public Goal goal(){return goal;}public FollowState state(){return state;}public double remainingTravel(){return Math.max(0,cap-traveled); }
        public FollowState advance(Facts f){
            if(state.terminal())return state;
            if(f.tick()<goal.acceptedTick() || f.tick()<lastTick || lastTick>=goal.acceptedTick() && f.tick()-lastTick>1)return stop(Reason.CLOCK_INVALID);
            if(f.tick()==lastTick)return state;
            if(!f.feet().dimension().equals(goal.actor().dimension()) || !f.playerFeet().dimension().equals(goal.actor().dimension()))return stop(Reason.DIMENSION_CHANGED);
            if(!f.actor().equals(goal.actor()) || !f.player().equals(goal.actor().principal()))return stop(Reason.IDENTITY_CHANGED);
            double delta=previous.distance(f.feet());traveled+=delta;previous=f.feet();lastTick=f.tick();
            if(delta>=.01){lastProgress=f.feet();lastProgressTick=f.tick();}
            deadline=Math.min(deadline,f.ownerDeadline());cap=Math.min(cap,traveled+f.remainingTravel());
            if(!f.authorized())return stop(Reason.UNAUTHORIZED);
            if(f.reserved())return stop(Reason.RESOURCE_RESERVED);
            if(!f.alive())return stop(Reason.DEAD);
            if(f.tick()>=deadline)return state=emit(Action.EXPIRED,Reason.DEADLINE,null);
            if(traveled>=cap)return state=emit(Action.EXPIRED,Reason.TRAVEL_LIMIT,null);
            if(!f.loaded())return stop(Reason.UNLOADED);
            if(!f.connected()){paused++;lastProgressTick=f.tick();if(paused>=MAX_PAUSED)return stop(Reason.PAUSE_LIMIT);return state=emit(Action.WAIT,Reason.DISCONNECTED,null);}
            // Resume never renews deadline, travel or accumulated pause time.
            if(lastObserve<0 || f.tick()-lastObserve>=INTERVAL){target=f.playerFeet();lastObserve=f.tick();observations++;}
            if(previous.distance(target)<=4){lastProgressTick=f.tick();return state=emit(Action.WAIT,Reason.NONE,null);}
            if(f.tick()-lastProgressTick>=100)return stop(Reason.NO_PROGRESS);
            boolean meaningful=lastRouteTarget==null || lastRouteTarget.distance(target)>=1;
            if((lastRepath<0 || f.tick()-lastRepath>=INTERVAL) && (meaningful || f.navigationDone())){
                double distance=previous.distance(target),step=Math.min(Math.min(LOOKAHEAD,cap-traveled),distance-3);
                Point proposal=new Point(target.dimension(),previous.x()+(target.x()-previous.x())*step/distance,previous.y()+(target.y()-previous.y())*step/distance,previous.z()+(target.z()-previous.z())*step/distance);
                lastRepath=f.tick();lastRouteTarget=target;repaths++;return state=emit(Action.MOVE_PROPOSAL,Reason.NONE,proposal);
            }
            return state=emit(Action.WAIT,Reason.NONE,null);
        }
        public FollowState stop(Reason reason){if(reason==Reason.NONE)throw new IllegalArgumentException("stop reason");if(!state.terminal())state=emit(Action.STOP,reason,null);return state;}
        private FollowState emit(Action action,Reason reason,Point proposal){return new FollowState(goal.session(),action,reason,Math.max(goal.acceptedTick(),lastTick),goal.acceptedTick(),deadline,traveled,paused,observations,repaths,previous,target,proposal);}
    }
}
