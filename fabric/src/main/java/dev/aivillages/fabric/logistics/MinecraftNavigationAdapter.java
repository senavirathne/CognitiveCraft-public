package dev.aivillages.fabric.logistics;

import java.util.*;
import dev.aivillages.core.kernel.logistics.*;
import dev.aivillages.core.kernel.logistics.MobilityPolicy.*;
import dev.aivillages.core.kernel.logistics.MobilityObservation.*;
import dev.aivillages.core.kernel.storage.*;
import dev.aivillages.fabric.storage.MinecraftStorageAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.*;
import net.minecraft.world.level.pathfinder.Path;

/** Inert public native navigation port. A/J02 owns ticks, actor custody and charging; no hooks or Jobs. */
public final class MinecraftNavigationAdapter {
    public record Control(Actor identity,boolean authorized,Limits remaining,long deadline){public Control{Objects.requireNonNull(identity);Objects.requireNonNull(remaining);}}
    @FunctionalInterface public interface Authority { Control observe(Actor requested,long serverTick); }
    private final ServerLevel level;private final Mob actor;private final Authority authority;
    public MinecraftNavigationAdapter(ServerLevel level,Mob actor,Authority authority){this.level=Objects.requireNonNull(level);this.actor=Objects.requireNonNull(actor);this.authority=Objects.requireNonNull(authority);}
    /** ServerPlayer is supplied by authenticated command/owner context. Later locations are never read. */
    public Route come(Actor identity,ServerPlayer player){
        thread();Control control=admit(identity);if(!player.getUUID().equals(identity.principal()) || player.level()!=level || !player.isAlive())throw new SecurityException("authenticated same-level principal required");
        var goal=MobilityPolicy.come(identity,point(player.getX(),player.getY(),player.getZ()),level.getGameTime(),control.deadline(),control.remaining());return new Route(goal,null,null,null);
    }
    /** One enrolled recipient and one captured position; no automatic name lookup or target following. */
    public Route recipient(Actor identity,StorageAccessPolicy.Scope scope,RecipientResolver.Bound endpoint){
        thread();Control control=admit(identity);
        if(!scope.world().equals(identity.world()) || !scope.principal().equals(identity.principal()) || !Objects.equals(scope.actorCitizen(),identity.citizen()))throw new SecurityException("current recipient caller required");
        var accepted=endpoint.fresh(scope);var nativeTarget=level.getEntity(endpoint.entityId());
        if(accepted==null || !accepted.permitted().contains(StorageRegistry.Permission.DEPOSIT) || !(nativeTarget instanceof net.minecraft.world.entity.npc.villager.Villager target) || !target.isAlive() || target.level()!=level || !accepted.dimension().equals(identity.dimension()) || !level.hasChunkAt(target.blockPosition()))throw new SecurityException("current enrolled deposit recipient required");
        Point captured=point(target.getX(),target.getY(),target.getZ());
        var route=new Route(MobilityPolicy.recipient(identity,captured,level.getGameTime(),control.deadline(),control.remaining()),null,null,()->{
            var fresh=endpoint.fresh(scope);return fresh!=null && fresh.permitted().contains(StorageRegistry.Permission.DEPOSIT) && level.getEntity(endpoint.entityId())==target && target.isAlive() && !target.isRemoved() && target.level()==level && level.hasChunkAt(target.blockPosition()) && captured.distance(point(target.getX(),target.getY(),target.getZ()))<=.25;
        });route.targetChanged=Blocker.STALE_RECIPIENT;return route;
    }
    /** Resolve the name once in D04. Retain this binding; fresh permission/incarnations are checked per tick. */
    public Route storage(Actor identity,StorageAccessPolicy.Scope scope,StorageResolver.Bound endpoint,StorageRegistry.Permission operation,MinecraftStorageAdapter storage){
        thread();Control control=admit(identity);Objects.requireNonNull(storage);
        if(!scope.world().equals(identity.world()) || !scope.principal().equals(identity.principal()) || !Objects.equals(scope.actorCitizen(),identity.citizen()) || !endpoint.current(scope))throw new SecurityException("current authorized bound storage required");
        var binding=endpoint.binding();var check=storage.check(scope,endpoint.storageId(),operation,new ItemStack(Items.STONE));
        if(!check.eligible() || !StorageBindingRules.currentEndpoint(binding,check.binding()))throw new SecurityException("current native eligibility required");
        var candidates=new ArrayList<Point>();for(var m:binding.topology().members())for(int[] d:List.of(new int[]{1,0},new int[]{-1,0},new int[]{0,1},new int[]{0,-1})){var p=point(m.x()+.5+d[0],m.y(),m.z()+.5+d[1]);if(!candidates.contains(p) && candidates.size()<control.remaining.maxCandidates())candidates.add(p);}
        Point safe=candidates.stream().filter(this::safe).findFirst().orElse(candidates.getFirst());
        var route=new Route(MobilityPolicy.storage(identity,safe,candidates,level.getGameTime(),control.deadline(),control.remaining()),scope,binding,()->{var current=storage.check(scope,binding.storage(),operation,new ItemStack(Items.STONE));return current.eligible() && StorageBindingRules.currentEndpoint(binding,current.binding());});
        if(!safe(safe))route.session.block(loaded(safe)?Blocker.NO_SAFE_STANDING_POSITION:Blocker.UNLOADED);return route;
    }
    private Control admit(Actor identity){Control c=authority.observe(identity,level.getGameTime());if(c==null || !c.authorized || !c.identity.equals(identity) || !nativeIdentity(identity) || c.deadline<=level.getGameTime())throw new SecurityException("fresh movement owner authority required");return c;}
    private boolean nativeIdentity(Actor key){return actor.isAlive() && actor.level()==level && actor.getUUID().equals(key.entity()) && level.dimension().identifier().toString().equals(key.dimension());}
    public final class Route {
        private final Session session;private final java.util.function.BooleanSupplier storageCurrent;private Blocker targetChanged=Blocker.STALE_STORAGE;private Path ownedPath;private long lastStep=-1;private int checkedChunks,pathNodes;private double pathLength;private String diagnostic="";
        private Route(Goal goal,StorageAccessPolicy.Scope scope,StorageBindingRules.Binding binding,java.util.function.BooleanSupplier storageCurrent){session=new Session(goal,feet());this.storageCurrent=storageCurrent;}
        public Goal goal(){return session.goal();}public MobilityObservation observation(){return session.observation();}
        public String diagnostic(){return diagnostic;}public int checkedChunks(){return checkedChunks;}public int pathNodes(){return pathNodes;}public double pathLength(){return pathLength;}
        /** Must be called by the exclusive native movement owner every tick. Repeated same-tick calls are inert. */
        public MobilityObservation step(){thread();long now=level.getGameTime();if(session.observation().terminal())return session.observation();if(now==lastStep)return session.observation();if(lastStep>=0 && now-lastStep>1)return stop(Blocker.CLOCK_INVALID);lastStep=now;
            try{Actor identity=goal().actor();Control c=authority.observe(identity,now);
                if(c==null || !nativeIdentity(identity))return stop(Blocker.IDENTITY_CHANGED);
                if(!c.identity.equals(identity))return stop(Blocker.IDENTITY_CHANGED);
                if(!actor.getNavigation().isDone() && (ownedPath==null || actor.getNavigation().getPath()!=ownedPath))return stop(Blocker.NAVIGATION_CHANGED);
                if(storageCurrent!=null && !storageCurrent.getAsBoolean())return stop(targetChanged);
                boolean loaded=loaded(feet()) && loaded(goal().endpoint());
                var result=session.advance(new Facts(now,c.identity,feet(),c.authorized,loaded,loaded && clear(feet()) && safe(goal().endpoint()),c.remaining,c.deadline),actor.getNavigation().isDone());
                if(result.terminal())return stopOwned();
                if(result.status()!=Status.PROPOSE_ROUTE)return result;
                int search=(int)Math.ceil(Math.min(session.remainingTravel(),Math.max(16,feet().distance(goal().endpoint())*2+8)));
                boolean completeSearch=regionLoaded(search+8);
                Path path=actor.getNavigation().createPath(BlockPos.containing(goal().endpoint().x(),goal().endpoint().y(),goal().endpoint().z()),0,search);
                if(path==null || !path.canReach() || path.getNodeCount()<1){diagnostic="native_path="+(path==null?"null":path.toString())+",on_ground="+actor.onGround()+",entity_ticks="+actor.tickCount;return stop(completeSearch?Blocker.UNREACHABLE:Blocker.UNLOADED);}
                pathNodes=path.getNodeCount();if(pathNodes>256)return stop(Blocker.TRAVEL_LIMIT);pathLength=0;Point previous=feet();
                for(int i=0;i<pathNodes;i++){BlockPos p=path.getNodePos(i);Point node=point(p.getX()+.5,p.getY(),p.getZ()+.5);if(!loaded(node))return stop(Blocker.UNLOADED);if(!safe(node))return stop(Blocker.COLLISION);pathLength+=previous.distance(node);previous=node;}
                if(pathLength>session.remainingTravel())return stop(Blocker.TRAVEL_LIMIT);
                // Revalidate authority, current feet and endpoint immediately at the native call boundary.
                Control fresh=authority.observe(identity,now);if(fresh==null || !fresh.authorized || !fresh.identity.equals(identity) || !fresh.equals(c) || !nativeIdentity(identity) || !feet().equals(result.feet()) || !safe(goal().endpoint()) || storageCurrent!=null && !storageCurrent.getAsBoolean())return stop(Blocker.UNAUTHORIZED);
                if(!actor.getNavigation().moveTo(path,.3))return stop(Blocker.UNREACHABLE);ownedPath=path;return result;
            }catch(RuntimeException unavailable){diagnostic=unavailable.toString();return stop(Blocker.UNREACHABLE);}
        }
        public MobilityObservation cancel(){thread();return stop(Blocker.CANCELLED);}
        private MobilityObservation stop(Blocker reason){session.block(reason);return stopOwned();}
        private MobilityObservation stopOwned(){if(ownedPath!=null && actor.getNavigation().getPath()==ownedPath)actor.getNavigation().stop();return session.observation();}
        private boolean regionLoaded(int radius){checkedChunks=0;BlockPos p=actor.blockPosition();for(int x=(p.getX()-radius)>>4;x<=((p.getX()+radius)>>4);x++)for(int z=(p.getZ()-radius)>>4;z<=((p.getZ()+radius)>>4);z++){if(++checkedChunks>121 || level.getChunkSource().getChunkNow(x,z)==null)return false;}return true;}
    }
    private Point feet(){return point(actor.getX(),actor.getY(),actor.getZ());}
    private Point point(double x,double y,double z){return new Point(level.dimension().identifier().toString(),x,y,z);}
    private boolean loaded(Point p){BlockPos b=BlockPos.containing(p.x(),p.y(),p.z());return p.dimension().equals(level.dimension().identifier().toString()) && level.getChunkSource().getChunkNow(b.getX()>>4,b.getZ()>>4)!=null;}
    private boolean clear(Point p){if(!loaded(p))return false;var box=actor.getBoundingBox().move(p.x()-actor.getX(),p.y()-actor.getY(),p.z()-actor.getZ()).deflate(.001);return !level.getBlockCollisions(actor,box).iterator().hasNext();}
    private boolean safe(Point p){if(!clear(p))return false;BlockPos b=BlockPos.containing(p.x(),p.y(),p.z());return level.getWorldBorder().isWithinBounds(b) && level.getFluidState(b).isEmpty() && !level.getBlockState(b.below()).getCollisionShape(level,b.below()).isEmpty();}
    private void thread(){if(!level.getServer().isSameThread())throw new IllegalStateException("exclusive server movement owner required");}
}
