package dev.aivillages.fabric;

import java.util.*;
import dev.aivillages.core.kernel.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;

/** Table-only server-thread survival placement; no inventory, site-search or recovery owner. */
final class FabricTablePlacement {
    private final ServerLevel level;private final FabricGatewayWorld world;
    FabricTablePlacement(ServerLevel level,FabricGatewayWorld world){this.level=level;this.world=world;}
    private record Site(String fingerprint,Reason reason) { }
    private List<BlockPos> cells(BlockPos target){
        var result=new LinkedHashSet<BlockPos>();result.add(target);result.add(target.below());
        for(Direction direction:List.of(Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST)){
            var foot=target.relative(direction);result.add(foot);result.add(foot.above());result.add(foot.below());
        }
        return List.copyOf(result);
    }
    private boolean support(BlockPos p){var state=level.getBlockState(p);
        return state.isFaceSturdy(level,p,Direction.UP) && state.getFluidState().isEmpty()
                && !state.is(Blocks.MAGMA_BLOCK) && !state.is(Blocks.CACTUS) && !state.is(Blocks.CAMPFIRE) && !state.is(Blocks.SOUL_CAMPFIRE);
    }
    private Site site(ActorRef actor,FacilityRef target){
        var v=world.recipeActor(actor);if(v==null)return new Site("",Reason.ACTOR_UNAVAILABLE);
        if(!target.dimension().equals(actor.dimension()))return new Site("",Reason.TARGET_INVALID);
        var p=new BlockPos(target.x(),target.y(),target.z());var cells=cells(p);
        if(cells.size()!=14 || level.isOutsideBuildHeight(p))return new Site("",Reason.TARGET_INVALID);
        var tokens=new StringBuilder();
        for(var cell:cells){
            if(!world.loadedForDiscovery(cell))return new Site("",Reason.TARGET_UNAVAILABLE);
            if(!world.discoverable(actor,cell,Effect.PLACE))return new Site("",Reason.AUTHORITY_DENIED);
            String token=FabricFacilityIdentity.capture(level,cell);if(token==null)return new Site("",Reason.BUDGET_EXHAUSTED);
            tokens.append(cell.asLong()).append(':').append(level.getBlockState(cell)).append(':').append(token).append(';');
        }
        if(!level.getBlockState(p).isAir() || !level.getFluidState(p).isEmpty() || !support(p.below()))return new Site("",Reason.TARGET_INVALID);
        if(!level.getEntities((Entity)null,new AABB(p),e->e.isAlive() && !e.isSpectator()).isEmpty())return new Site("",Reason.TARGET_UNAVAILABLE);
        boolean working=false;
        for(Direction direction:List.of(Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST)){
            var foot=p.relative(direction);
            if(level.getBlockState(foot).isAir() && level.getBlockState(foot.above()).isAir()
                    && level.getFluidState(foot).isEmpty() && level.getFluidState(foot.above()).isEmpty() && support(foot.below()))working=true;
        }
        return working ? new Site(RecipeTransaction.fingerprint(tokens.toString()),null) : new Site("",Reason.TARGET_UNAVAILABLE);
    }
    PlacementTransaction.Preflight preflight(ValidatedRequest bound){
        if(!CraftingTablePlacement.valid(bound.request()))return PlacementTransaction.Preflight.blocked(Reason.REQUEST_INVALID);
        var actor=((ActorValue)bound.request().arguments().get("actor")).value();var source=((ContainerValue)bound.request().arguments().get("source")).value();
        var target=CraftingTablePlacement.target(bound.request());var checked=site(actor,target);
        if(checked.reason()!=null)return PlacementTransaction.Preflight.blocked(checked.reason());
        var p=new BlockPos(source.x(),source.y(),source.z());
        if(!world.loadedForDiscovery(p))return PlacementTransaction.Preflight.blocked(Reason.TARGET_UNAVAILABLE);
        if(!world.discoverable(actor,p,Effect.WITHDRAW))return PlacementTransaction.Preflight.blocked(Reason.AUTHORITY_DENIED);
        Villager v=world.recipeActor(actor);Container from=world.recipeContainer(source);
        if(from==null)return PlacementTransaction.Preflight.blocked(Reason.TARGET_UNAVAILABLE);
        if(from.getContainerSize()>128 || v.getInventory().getContainerSize()>8)return PlacementTransaction.Preflight.blocked(Reason.BUDGET_EXHAUSTED);
        long items=0;
        for(int i=0;i<from.getContainerSize();i++){var item=from.getItem(i);if(item.is(Items.CRAFTING_TABLE)){
            if(!FabricRecipeTransactions.plain(item,Items.CRAFTING_TABLE))return PlacementTransaction.Preflight.blocked(Reason.TARGET_INVALID);items=Math.addExact(items,item.getCount());}}
        for(int i=0;i<v.getInventory().getContainerSize();i++){var item=v.getInventory().getItem(i);if(item.is(Items.CRAFTING_TABLE) && !FabricRecipeTransactions.plain(item,Items.CRAFTING_TABLE))return PlacementTransaction.Preflight.blocked(Reason.TARGET_INVALID);}
        if(FabricRecipeTransactions.insert(FabricRecipeTransactions.copy(v.getInventory()),v.getInventory(),new ItemStack(Items.CRAFTING_TABLE))==null)return PlacementTransaction.Preflight.blocked(Reason.TARGET_UNAVAILABLE);
        return new PlacementTransaction.Preflight(ObservationStatus.PRESENT,items,from.getContainerSize(),FabricRecipeTransactions.identity(source,from),
                FabricRecipeTransactions.fingerprint(from),FabricRecipeTransactions.fingerprint(v.getInventory()),checked.fingerprint(),target,source,null);
    }
    private Reason guard(ActorRef actor,PlacementTransaction.Preflight captured,String custody,String sourceFingerprint,boolean sourceReach){
        Villager v=world.recipeActor(actor);if(v==null || !v.onGround() || !GatewayControl.controls(v))return Reason.ACTOR_UNAVAILABLE;
        var source=captured.source();var p=new BlockPos(source.x(),source.y(),source.z());
        if(!world.loadedForDiscovery(p))return Reason.TARGET_UNAVAILABLE;
        if(!world.discoverable(actor,p,Effect.WITHDRAW))return Reason.AUTHORITY_DENIED;
        Container from=world.recipeContainer(source);if(from==null)return Reason.TARGET_UNAVAILABLE;
        if(!FabricRecipeTransactions.identity(source,from).equals(captured.sourceIdentity())
                || !FabricRecipeTransactions.fingerprint(from).equals(sourceFingerprint)
                || !FabricRecipeTransactions.fingerprint(v.getInventory()).equals(custody))return Reason.STALE_OBSERVATION;
        if(sourceReach && !world.recipeReach(v,p))return Reason.TARGET_UNAVAILABLE;
        var checked=site(actor,captured.target());if(checked.reason()!=null)return checked.reason();
        return checked.fingerprint().equals(captured.siteFingerprint()) ? null : Reason.STALE_OBSERVATION;
    }
    PlacementTransaction.Mutation withdraw(ActorRef actor,PlacementTransaction.Preflight captured,String custody,String sourceFingerprint){
        var denied=guard(actor,captured,custody,sourceFingerprint,true);if(denied!=null)return PlacementTransaction.Mutation.blocked(denied);
        var v=world.recipeActor(actor);var from=world.recipeContainer(captured.source());var next=FabricRecipeTransactions.copy(from);
        var actual=next.stream().filter(i->FabricRecipeTransactions.plain(i,Items.CRAFTING_TABLE)).findFirst().orElse(ItemStack.EMPTY);
        if(actual.isEmpty() || !FabricRecipeTransactions.remove(next,Items.CRAFTING_TABLE,1))return PlacementTransaction.Mutation.blocked(Reason.RESOURCE_MISSING);
        var held=FabricRecipeTransactions.insert(FabricRecipeTransactions.copy(v.getInventory()),v.getInventory(),actual.copyWithCount(1));
        if(held==null)return PlacementTransaction.Mutation.blocked(Reason.TARGET_UNAVAILABLE);
        FabricRecipeTransactions.apply(from,next);FabricRecipeTransactions.apply(v.getInventory(),held);
        return new PlacementTransaction.Mutation(1,0,false,custody,FabricRecipeTransactions.fingerprint(v.getInventory()),FabricRecipeTransactions.fingerprint(from),"",null);
    }
    PlacementTransaction.Mutation place(ActorRef actor,PlacementTransaction.Preflight captured,String custody,String sourceFingerprint){
        var denied=guard(actor,captured,custody,sourceFingerprint,false);if(denied!=null)return PlacementTransaction.Mutation.blocked(denied);
        var v=world.recipeActor(actor);var target=captured.target();var p=new BlockPos(target.x(),target.y(),target.z());
        if(!world.recipeReach(v,p))return PlacementTransaction.Mutation.blocked(Reason.TARGET_UNAVAILABLE);
        ItemStack actual=ItemStack.EMPTY;
        for(int i=0;i<v.getInventory().getContainerSize();i++){var stack=v.getInventory().getItem(i);if(FabricRecipeTransactions.plain(stack,Items.CRAFTING_TABLE)){actual=stack;break;}}
        if(actual.isEmpty() || !(actual.getItem() instanceof BlockItem item) || item.getBlock()!=Blocks.CRAFTING_TABLE)return PlacementTransaction.Mutation.blocked(Reason.RESOURCE_MISSING);
        int before=actual.getCount();Reason failure=null;var support=p.below();
        var hit=new BlockHitResult(new Vec3(p.getX()+.5,p.getY(),p.getZ()+.5),Direction.UP,support,false);
        var context=new BlockPlaceContext(level,null,InteractionHand.MAIN_HAND,actual,hit) { };
        try{item.place(context);}catch(RuntimeException uncertain){failure=Reason.ACTION_FAILED;}
        int consumed=before-actual.getCount();boolean placed=level.getBlockState(p).is(Blocks.CRAFTING_TABLE);
        String identity=placed?FabricFacilityIdentity.capture(level,p):"";
        if(consumed<0 || consumed>1 || identity==null)failure=Reason.STALE_OBSERVATION;
        if(!placed || consumed!=1)failure=failure==null?Reason.ACTION_FAILED:failure;
        v.getInventory().setChanged();
        return new PlacementTransaction.Mutation(0,Math.max(0,Math.min(consumed,1)),placed,custody,FabricRecipeTransactions.fingerprint(v.getInventory()),"",identity==null?"":identity,failure);
    }
}
