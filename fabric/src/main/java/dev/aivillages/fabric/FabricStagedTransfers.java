package dev.aivillages.fabric;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.Outcomes.Reason;
import dev.aivillages.core.kernel.runtime.claims.StagedTransfers;
import dev.aivillages.core.kernel.runtime.claims.StagedTransfers.*;
import dev.aivillages.core.kernel.runtime.claims.StagedTransfers.Plan;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;

/** A-owned loaded-only staging effects. Copy/insert/remove are read-only baseline B APIs. */
public final class FabricStagedTransfers implements StagedTransfers.World {
    private final ServerLevel level;private final FabricGatewayWorld nativeWorld;private final AuthorityPolicy authority;
    private final LeaseTargetWitnesses witnesses=new LeaseTargetWitnesses(128);
    public FabricStagedTransfers(ServerLevel level,FabricGatewayWorld nativeWorld,AuthorityPolicy authority){
        this.level=Objects.requireNonNull(level);this.nativeWorld=Objects.requireNonNull(nativeWorld);this.authority=Objects.requireNonNull(authority);
    }
    private record Native(Villager actor,Container source,Container destination,Reason blocker) { }
    private Native inspect(ValidatedRequest request,Plan plan,Operation operation){
        if(!level.getServer().isSameThread())throw new IllegalStateException("Native staging server thread");
        ActorRef actor=((ActorValue)request.request().arguments().get("actor")).value();Villager v=nativeWorld.recipeActor(actor);
        if(v==null||!v.isAlive()||v.isRemoved())return new Native(null,null,null,Reason.ACTOR_UNAVAILABLE);
        if(plan.source().equals(plan.destination()))return new Native(v,null,null,Reason.REQUEST_INVALID);
        var target=operation==Operation.RETURN?plan.source():plan.destination();
        for(var effect:List.of(Effect.OBSERVE,operation==Operation.WITHDRAW?Effect.WITHDRAW:Effect.TRANSFER))
            if(!authority.currentlyAllows(actor,effect,request.context()))return new Native(v,null,null,Reason.AUTHORITY_DENIED);
        for(var entry:List.of(Map.entry(plan.source(),operation==Operation.RETURN?Effect.TRANSFER:operation==Operation.WITHDRAW?Effect.WITHDRAW:Effect.OBSERVE),
                Map.entry(plan.destination(),operation==Operation.DEPOSIT?Effect.TRANSFER:Effect.OBSERVE))){
            var c=entry.getKey();var denied=nativeWorld.permit(request,entry.getValue(),new SurvivalGateway.Cell(c.dimension(),c.x(),c.y(),c.z()));
            if(denied!=null)return new Native(v,null,null,denied);
        }
        Container source=container(plan.source()),destination=container(plan.destination());
        if(source==null||destination==null||source==destination)return new Native(v,null,null,Reason.TARGET_INVALID);
        if(v.getInventory().getContainerSize()>8)return new Native(v,null,null,Reason.BUDGET_EXHAUSTED);
        for(Container c:List.of(source,destination,v.getInventory()))for(Units u:plan.units()){
            var item=FabricResourceItems.item(u.item());for(int i=0;i<c.getContainerSize();i++){
                var stack=c.getItem(i);if(stack.is(item)&&!FabricRecipeTransactions.plain(stack,item))return new Native(v,null,null,Reason.TARGET_INVALID);
            }
        }
        return new Native(v,source,destination,null);
    }
    private Container container(ContainerRef ref){
        var p=pos(ref);if(!ref.dimension().equals(level.dimension().identifier().toString()))return null;
        var chunk=level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4);if(chunk==null)return null;
        var block=chunk.getBlockState(p);var entity=chunk.getBlockEntities().get(p);
        if(!block.is(Blocks.CHEST)&&!block.is(Blocks.BARREL)||block.is(Blocks.CHEST)&&block.getValue(ChestBlock.TYPE)!=ChestType.SINGLE
                ||!(entity instanceof Container c)||c.getContainerSize()>128
                ||entity instanceof BaseContainerBlockEntity base&&base.isLocked()
                ||entity instanceof RandomizableContainerBlockEntity random&&random.getLootTable()!=null)return null;
        return c;
    }
    @Override public Snapshot observe(ValidatedRequest request,Plan plan,Operation operation){
        Native n=inspect(request,plan,operation);if(n.blocker()!=null)return Snapshot.blocked(level.getGameTime(),n.blocker());
        return capture(plan,operation,n);
    }
    private Snapshot capture(Plan plan,Operation operation,Native n){
        var inventory=n.actor().getInventory();Container receiving=operation==Operation.RETURN?n.source():n.destination();
        String source=witnesses.identity(plan.source().toString(),n.source()),destination=witnesses.identity(plan.destination().toString(),n.destination());
        boolean reachable=n.actor().onGround()&&nativeWorld.recipeReach(n.actor(),pos(operation==Operation.WITHDRAW||operation==Operation.RETURN?plan.source():plan.destination()));
        return new Snapshot(level.getGameTime(),source,destination,FabricRecipeTransactions.fingerprint(inventory),
                FabricRecipeTransactions.fingerprint(n.source()),FabricRecipeTransactions.fingerprint(n.destination()),stock(n.source(),plan),stock(inventory,plan),
                insert(inventory,plan)!=null,insert(receiving,plan)!=null,reachable,
                n.source().getContainerSize()+n.destination().getContainerSize()+inventory.getContainerSize(),null);
    }
    private static Map<String,Integer> stock(Container c,Plan plan){
        var out=new TreeMap<String,Integer>();for(Units u:plan.units()){
            int total=0;for(int i=0;i<c.getContainerSize();i++)if(FabricRecipeTransactions.plain(c.getItem(i),FabricResourceItems.item(u.item())))total=Math.addExact(total,c.getItem(i).getCount());
            out.put(u.item().id(),total);
        }return Map.copyOf(out);
    }
    private static List<ItemStack> insert(Container c,Plan plan){
        List<ItemStack> copy=FabricRecipeTransactions.copy(c);
        for(Units u:plan.units()){
            copy=FabricRecipeTransactions.insert(copy,c,new ItemStack(FabricResourceItems.item(u.item()),u.quantity()));if(copy==null)return null;
        }return copy;
    }
    @Override public Move move(ValidatedRequest request,Plan plan,Operation operation,Snapshot captured){
        Native n=inspect(request,plan,operation);if(n.blocker()!=null)return Move.blocked(Snapshot.blocked(level.getGameTime(),n.blocker()),n.blocker());
        Snapshot fresh=capture(plan,operation,n);
        if(!fresh.sourceIdentity().equals(captured.sourceIdentity())||!fresh.destinationIdentity().equals(captured.destinationIdentity())
                ||!fresh.actorFingerprint().equals(captured.actorFingerprint())||!fresh.sourceFingerprint().equals(captured.sourceFingerprint())
                ||!fresh.destinationFingerprint().equals(captured.destinationFingerprint()))return Move.blocked(fresh,Reason.STALE_OBSERVATION);
        if(!fresh.reachable()||!fresh.destinationFits()||operation==Operation.WITHDRAW&&!fresh.actorFits())return Move.blocked(fresh,Reason.TARGET_UNAVAILABLE);
        Container from=operation==Operation.WITHDRAW?n.source():n.actor().getInventory();
        Container to=operation==Operation.WITHDRAW?n.actor().getInventory():operation==Operation.RETURN?n.source():n.destination();
        var before=FabricRecipeTransactions.copy(from);for(Units u:plan.units())
            if(!FabricRecipeTransactions.remove(before,FabricResourceItems.item(u.item()),u.quantity()))return Move.blocked(fresh,Reason.RESOURCE_MISSING);
        var after=insert(to,plan);if(after==null)return Move.blocked(fresh,Reason.TARGET_UNAVAILABLE);
        // No callbacks, navigation, inference or asynchronous work between final read and mutation.
        // A thrown engine callback is an uncertain move at the runtime boundary, never zero-effect.
        FabricRecipeTransactions.apply(from,before);FabricRecipeTransactions.apply(to,after);
        return new Move(true,plan.quantities(),capture(plan,operation,n),null);
    }
    private static BlockPos pos(ContainerRef c){return new BlockPos(c.x(),c.y(),c.z());}
}
