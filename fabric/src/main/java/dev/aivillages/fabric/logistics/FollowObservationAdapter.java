package dev.aivillages.fabric.logistics;
import java.util.*;
import dev.aivillages.core.kernel.logistics.*;
import dev.aivillages.core.kernel.logistics.MobilityPolicy.*;
import static dev.aivillages.core.kernel.logistics.FollowState.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;
/** Inert, externally ticked port. A/J02 supplies exclusive custody and current cumulative allowance. */
public final class FollowObservationAdapter {
    public record Control(Actor actor,boolean authorized,boolean reserved,long deadline,double remainingTravel){}
    @FunctionalInterface public interface Authority {Control observe(Actor actor,long tick);}
    private final ServerLevel level;private final Mob actor;private final Authority authority;private final java.util.function.Function<UUID,ServerPlayer> authenticatedPlayers;
    public FollowObservationAdapter(ServerLevel level,Mob actor,Authority authority){this(level,actor,authority,id->level.getServer().getPlayerList().getPlayer(id));}
    /** Trusted native owner lookup, never a decoded player claim. */
    public FollowObservationAdapter(ServerLevel level,Mob actor,Authority authority,java.util.function.Function<UUID,ServerPlayer> authenticatedPlayers){this.level=Objects.requireNonNull(level);this.actor=Objects.requireNonNull(actor);this.authority=Objects.requireNonNull(authority);this.authenticatedPlayers=Objects.requireNonNull(authenticatedPlayers);}
    public Route start(UUID session,Actor identity,ServerPlayer player){thread();Control c=authority.observe(identity,level.getGameTime());
        if(c==null || !c.authorized() || c.reserved() || !identity.equals(c.actor()) || !nativeIdentity(identity) || !player.getUUID().equals(identity.principal()) || player.level()!=level || !player.isAlive() || authenticatedPlayers.apply(player.getUUID())!=player)throw new SecurityException("current authenticated player and exclusive authority");
        return new Route(FollowPolicy.start(session,identity,feet(),point(player),level.getGameTime(),c.deadline(),c.remainingTravel()));
    }
    public final class Route {
        private final FollowPolicy.Session session;private Path owned;private long lastStep=-1,lastTargetTick=-1;private Point observedTarget;private int pathNodes;private double pathLength;private String diagnostic="";
        private Route(FollowPolicy.Session session){this.session=session;}
        public FollowState state(){return session.state();}public FollowPolicy.Goal goal(){return session.goal();}public int pathNodes(){return pathNodes;}public double pathLength(){return pathLength;}public String diagnostic(){return diagnostic;}
        public FollowState step(){thread();if(state().terminal())return state();long tick=level.getGameTime();if(lastStep==tick)return state();lastStep=tick;
            try{Actor key=goal().actor();Control c=authority.observe(key,tick);ServerPlayer player=authenticatedPlayers.apply(key.principal());
                if(c==null || !nativeIdentity(key))return stop(Reason.IDENTITY_CHANGED);
                if(c.reserved() || !actor.getNavigation().isDone() && actor.getNavigation().getPath()!=owned)return stop(Reason.RESOURCE_RESERVED);
                Point target=observedTarget==null?state().target():observedTarget;
                if(player!=null){String dimension=player.level().dimension().identifier().toString();if(!dimension.equals(key.dimension()))return stop(Reason.DIMENSION_CHANGED);if(lastTargetTick<0 || tick-lastTargetTick>=10){target=point(player);observedTarget=target;lastTargetTick=tick;}}
                var result=session.advance(new FollowPolicy.Facts(tick,c.actor(),feet(),key.principal(),target,player!=null,actor.isAlive() && (player==null || player.isAlive()),loaded(feet()) && (player==null || player.level()!=level || loaded(target)),c.authorized(),c.reserved(),c.deadline(),c.remainingTravel(),actor.getNavigation().isDone()));
                if(result.terminal() || result.reason()==Reason.DISCONNECTED || result.feet().distance(result.target())<=4){stopOwned();return result;}
                if(result.action()!=Action.MOVE_PROPOSAL)return result;
                Point end=result.proposal();if(!safe(end))return stop(loaded(end)?Reason.UNREACHABLE:Reason.UNLOADED);
                Path path=actor.getNavigation().createPath(BlockPos.containing(end.x(),end.y(),end.z()),0,16);
                if(path==null || path.getNodeCount()==0)return stop(session.remainingTravel()<1?Reason.TRAVEL_LIMIT:Reason.UNREACHABLE);
                // Native navigation may return its current Path, including already walked nodes.
                // Validate a bounded unwalked suffix without truncating that live shared object.
                var remainingNodes=new ArrayList<net.minecraft.world.level.pathfinder.Node>();
                for(int i=path.getNextNodeIndex();i<path.getNodeCount() && remainingNodes.size()<32;i++)remainingNodes.add(path.getNode(i));
                path=new Path(remainingNodes,path.getTarget(),path.canReach());
                // Every accepted path segment is <=8 loaded blocks, including obstacle detours.
                double length=0;Point previous=feet();int keep=0;boolean limitedByOwner=false;
                for(int i=0;i<path.getNodeCount() && i<32;i++){var p=path.getNodePos(i);var node=new Point(end.dimension(),p.getX()+.5,p.getY(),p.getZ()+.5);if(!safe(node))return stop(loaded(node)?Reason.UNREACHABLE:Reason.UNLOADED);double next=previous.distance(node);if(length+next>Math.min(8,session.remainingTravel())){limitedByOwner=session.remainingTravel()<8;break;}length+=next;previous=node;keep++;}
                // A cached path can have one unwalked waypoint. Native moveTo
                // accepts that nonempty suffix; requiring a start node again
                // would reject ordinary progress before the inherited cap.
                if(keep==0)return stop(limitedByOwner?Reason.TRAVEL_LIMIT:Reason.UNREACHABLE);path.truncateNodes(keep);pathLength=length;pathNodes=keep;
                Control fresh=authority.observe(key,tick);if(!c.equals(fresh) || !fresh.authorized() || fresh.reserved() || !nativeIdentity(key))return stop(Reason.UNAUTHORIZED);
                if(!actor.getNavigation().moveTo(path,.3))return stop(session.remainingTravel()<1?Reason.TRAVEL_LIMIT:Reason.UNREACHABLE);owned=actor.getNavigation().getPath();return result;
            }catch(RuntimeException unavailable){diagnostic=unavailable.toString();return stop(Reason.UNREACHABLE);}
        }
        public FollowState cancel(){thread();return stop(Reason.CANCELLED);}
        private FollowState stop(Reason reason){session.stop(reason);stopOwned();return state();}
        private void stopOwned(){if(owned!=null && actor.getNavigation().getPath()==owned)actor.getNavigation().stop();}
    }
    private boolean nativeIdentity(Actor key){return actor.isAlive() && actor.level()==level && actor.getUUID().equals(key.entity()) && level.dimension().identifier().toString().equals(key.dimension());}
    private Point point(ServerPlayer p){return new Point(p.level().dimension().identifier().toString(),p.getX(),p.getY(),p.getZ());}
    private Point feet(){return new Point(level.dimension().identifier().toString(),actor.getX(),actor.getY(),actor.getZ());}
    private boolean loaded(Point p){var b=BlockPos.containing(p.x(),p.y(),p.z());return p.dimension().equals(level.dimension().identifier().toString()) && level.getChunkSource().getChunkNow(b.getX()>>4,b.getZ()>>4)!=null;}
    private boolean safe(Point p){if(!loaded(p))return false;var b=BlockPos.containing(p.x(),p.y(),p.z());return level.getWorldBorder().isWithinBounds(b) && level.getFluidState(b).isEmpty() && !level.getBlockState(b.below()).getCollisionShape(level,b.below()).isEmpty() && !level.getBlockCollisions(actor,actor.getBoundingBox().move(p.x()-actor.getX(),p.y()-actor.getY(),p.z()-actor.getZ()).deflate(.001)).iterator().hasNext();}
    private void thread(){if(!level.getServer().isSameThread())throw new IllegalStateException("exclusive server owner required");}
}
