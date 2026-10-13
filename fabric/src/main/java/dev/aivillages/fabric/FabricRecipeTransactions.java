package dev.aivillages.fabric;

import java.util.*;
import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.Outcomes.Reason;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;

/** Installed ordinary recipe validation and atomic server-thread slot commits, called only by the gateway. */
final class FabricRecipeTransactions {
    private final ServerLevel level;
    private final FabricGatewayWorld world;
    private RecipeManager manager;
    private CraftingRecipe installed;
    private String semantics="";
    private long generation;
    FabricRecipeTransactions(ServerLevel level,FabricGatewayWorld world) {this.level=level;this.world=world;}

    private record RecipeView(CraftingRecipe recipe,RecipeTransaction.Snapshot snapshot) { }
    private RecipeView capture() {
        var current=level.getServer().getRecipeManager();
        var holder=current.byKey(ResourceKey.create(Registries.RECIPE,Identifier.parse(OakPlankProduction.RECIPE)));
        if(holder.isEmpty() || holder.get().value().getClass()!=ShapelessRecipe.class)return null;
        var recipe=(CraftingRecipe)holder.get().value();
        if(recipe.isSpecial())return null;
        var placement=recipe.placementInfo();
        if(placement.isImpossibleToPlace() || placement.ingredients().size()!=1
                || placement.slotsToIngredientIndex().size()>4
                || placement.slotsToIngredientIndex().stream().filter(i->i!=PlacementInfo.EMPTY_SLOT).count()!=1)return null;
        var options=placement.ingredients().getFirst().items().limit(17).toList();
        if(options.isEmpty() || options.size()>16)return null;
        List<String> names=options.stream().map(i->i.unwrapKey().orElseThrow().identifier().toString()).sorted().toList();
        if(!names.contains("minecraft:oak_log"))return null;
        for(int position=0;position<4;position++) {
            var grid=new ArrayList<ItemStack>(List.of(ItemStack.EMPTY,ItemStack.EMPTY,ItemStack.EMPTY,ItemStack.EMPTY));
            grid.set(position,new ItemStack(Items.OAK_LOG));
            var input=CraftingInput.of(2,2,grid);
            if(!recipe.matches(input,level))return null;
            ItemStack actual=recipe.assemble(input);
            var remainder=recipe.getRemainingItems(input);
            if(!plain(actual,Items.OAK_PLANKS) || actual.getCount()!=4 || remainder.size()>4
                    || remainder.stream().anyMatch(s->!s.isEmpty()))return null;
        }
        String fingerprint=RecipeTransaction.fingerprint("inventory-2x2|"+OakPlankProduction.RECIPE
                +"|"+recipe.getClass().getName()+"|"+names+"|one-default-oak-log|four-default-oak-planks|no-remainders");
        if(manager!=current || installed!=recipe || !semantics.equals(fingerprint)) {
            generation=Math.incrementExact(generation);manager=current;installed=recipe;semantics=fingerprint;
        }
        return new RecipeView(recipe,new RecipeTransaction.Snapshot(OakPlankProduction.RECIPE,fingerprint,generation,1,4,0));
    }
    RecipeTransaction.Preflight preflight(ValidatedRequest bound) {
        var actor=((ActorValue)bound.request().arguments().get("actor")).value();
        var source=((ContainerValue)bound.request().arguments().get("source")).value();
        var target=((ContainerValue)bound.request().arguments().get("destination")).value();
        Villager v=world.recipeActor(actor);
        if(v==null)return blocked(Reason.ACTOR_UNAVAILABLE);
        for(var entry:List.of(Map.entry(source,Effect.WITHDRAW),Map.entry(target,Effect.TRANSFER))) {
            var c=entry.getKey();var reason=world.permit(bound,entry.getValue(),new SurvivalGateway.Cell(c.dimension(),c.x(),c.y(),c.z()));
            if(reason!=null)return blocked(reason);
        }
        Container from=world.recipeContainer(source),to=world.recipeContainer(target);
        if(from==null || to==null)return blocked(Reason.TARGET_UNAVAILABLE);
        if(v.getInventory().getContainerSize()>8)return blocked(Reason.BUDGET_EXHAUSTED);
        RecipeView recipe=capture();if(recipe==null)return blocked(Reason.UNSUPPORTED_PRIMITIVE);
        long available=0;
        for(int i=0;i<from.getContainerSize();i++) {
            ItemStack s=from.getItem(i);
            if(s.is(Items.OAK_LOG) && !plain(s,Items.OAK_LOG))return blocked(Reason.TARGET_INVALID);
            if(plain(s,Items.OAK_LOG))available=Math.addExact(available,s.getCount());
        }
        if(invalidRelevant(v.getInventory()))return blocked(Reason.TARGET_INVALID);
        var output=recipe.recipe().assemble(input(new ItemStack(Items.OAK_LOG)));
        int capacity=capacity(to,output);
        var first=insert(copy(v.getInventory()),v.getInventory(),new ItemStack(Items.OAK_LOG));
        if(first==null)return blocked(Reason.TARGET_UNAVAILABLE);
        for(int i=0;i<first.size();i++)if(plain(first.get(i),Items.OAK_LOG)) {
            first.set(i,first.get(i).copyWithCount(first.get(i).getCount()-1));break;
        }
        if(insert(first,v.getInventory(),output.copy())==null)return blocked(Reason.TARGET_UNAVAILABLE);
        return new RecipeTransaction.Preflight(ObservationStatus.PRESENT,recipe.snapshot(),available,capacity,
                identity(source,from),identity(target,to),fingerprint(from),fingerprint(v.getInventory()),
                from.getContainerSize(),to.getContainerSize(),null);
    }
    private RecipeTransaction.Preflight blocked(Reason r) {
        return new RecipeTransaction.Preflight(ObservationStatus.UNKNOWN,null,0,0,"","","","",0,0,r);
    }
    RecipeTransaction.Mutation withdraw(ActorRef actor,ContainerRef source,RecipeTransaction.Preflight captured,
                                        String custody,String sourceFingerprint) {
        Villager v=world.recipeActor(actor);Container from=world.recipeContainer(source);
        Reason denied=guard(v,from,source,captured.sourceIdentity(),custody);
        if(denied!=null)return RecipeTransaction.Mutation.blocked(denied);
        if(!fingerprint(from).equals(sourceFingerprint))return RecipeTransaction.Mutation.blocked(Reason.STALE_OBSERVATION);
        if(!current(captured))return RecipeTransaction.Mutation.blocked(Reason.ARTIFACT_INCOMPATIBLE);
        int slot=-1;
        for(int i=0;i<from.getContainerSize();i++)if(plain(from.getItem(i),Items.OAK_LOG) && from.getItem(i).getCount()>0) {slot=i;break;}
        if(slot<0)return RecipeTransaction.Mutation.blocked(Reason.RESOURCE_MISSING);
        ItemStack actual=from.getItem(slot);var next=insert(copy(v.getInventory()),v.getInventory(),actual.copyWithCount(1));
        if(next==null)return RecipeTransaction.Mutation.blocked(Reason.TARGET_UNAVAILABLE);
        // The supported containers and SimpleContainer slots commit on the same server thread.
        from.setItem(slot,actual.copyWithCount(actual.getCount()-1));apply(v.getInventory(),next);from.setChanged();
        return new RecipeTransaction.Mutation(0,0,1,0,custody,fingerprint(v.getInventory()),fingerprint(from),null);
    }
    RecipeTransaction.Mutation craft(ActorRef actor,RecipeTransaction.Preflight captured,String custody) {
        Villager v=world.recipeActor(actor);
        if(v==null || !v.onGround() || !GatewayControl.controls(v))return RecipeTransaction.Mutation.blocked(Reason.ACTOR_UNAVAILABLE);
        if(!fingerprint(v.getInventory()).equals(custody))return RecipeTransaction.Mutation.blocked(Reason.STALE_OBSERVATION);
        RecipeView recipe=capture();
        if(recipe==null || !recipe.snapshot().equals(captured.recipe()))return RecipeTransaction.Mutation.blocked(Reason.ARTIFACT_INCOMPATIBLE);
        var next=copy(v.getInventory());int slot=-1;
        for(int i=0;i<next.size();i++)if(plain(next.get(i),Items.OAK_LOG)) {slot=i;break;}
        if(slot<0)return RecipeTransaction.Mutation.blocked(Reason.RESOURCE_MISSING);
        ItemStack one=next.get(slot).copyWithCount(1);var input=input(one);
        if(!recipe.recipe().matches(input,level))return RecipeTransaction.Mutation.blocked(Reason.TARGET_INVALID);
        ItemStack result=recipe.recipe().assemble(input);var remainders=recipe.recipe().getRemainingItems(input);
        if(!plain(result,Items.OAK_PLANKS) || result.getCount()!=4 || remainders.size()>4
                || remainders.stream().anyMatch(s->!s.isEmpty()))return RecipeTransaction.Mutation.blocked(Reason.UNSUPPORTED_PRIMITIVE);
        next.set(slot,next.get(slot).copyWithCount(next.get(slot).getCount()-1));
        next=insert(next,v.getInventory(),result.copy());
        if(next==null)return RecipeTransaction.Mutation.blocked(Reason.TARGET_UNAVAILABLE);
        apply(v.getInventory(),next);result.onCraftedBySystem(level);
        return new RecipeTransaction.Mutation(1,result.getCount(),0,0,custody,fingerprint(v.getInventory()),"",null);
    }
    RecipeTransaction.Mutation deposit(ActorRef actor,ContainerRef target,RecipeTransaction.Preflight captured,
                                       String custody,int quantity) {
        Villager v=world.recipeActor(actor);Container to=world.recipeContainer(target);
        Reason denied=guard(v,to,target,captured.destinationIdentity(),custody);
        if(denied!=null)return RecipeTransaction.Mutation.blocked(denied);
        if(quantity<1 || quantity>4)return RecipeTransaction.Mutation.blocked(Reason.REQUEST_INVALID);
        var inventory=copy(v.getInventory());ItemStack output=null;int remaining=quantity;
        for(int i=0;i<inventory.size() && remaining>0;i++) {
            ItemStack s=inventory.get(i);if(!plain(s,Items.OAK_PLANKS))continue;
            int n=Math.min(remaining,s.getCount());if(output==null)output=s.copyWithCount(quantity);
            inventory.set(i,s.copyWithCount(s.getCount()-n));remaining-=n;
        }
        if(remaining!=0 || output==null)return RecipeTransaction.Mutation.blocked(Reason.STALE_OBSERVATION);
        var destination=insert(copy(to),to,output);
        if(destination==null)return RecipeTransaction.Mutation.blocked(Reason.TARGET_UNAVAILABLE);
        apply(v.getInventory(),inventory);apply(to,destination);
        return new RecipeTransaction.Mutation(0,0,quantity,0,custody,fingerprint(v.getInventory()),
                captured.sourceIdentity().equals(captured.destinationIdentity()) ? fingerprint(to) : "",null);
    }
    private Reason guard(Villager v,Container box,ContainerRef ref,String identity,String custody) {
        if(v==null || box==null || !v.onGround())return Reason.TARGET_UNAVAILABLE;
        if(!GatewayControl.controls(v))return Reason.ACTOR_UNAVAILABLE;
        if(!identity(ref,box).equals(identity) || !fingerprint(v.getInventory()).equals(custody))return Reason.STALE_OBSERVATION;
        if(!world.recipeReach(v,new BlockPos(ref.x(),ref.y(),ref.z())))return Reason.TARGET_UNAVAILABLE;
        return null;
    }
    private boolean current(RecipeTransaction.Preflight snapshot) {
        var actual=capture();return actual!=null && actual.snapshot().equals(snapshot.recipe());
    }
    static boolean plain(ItemStack stack,net.minecraft.world.item.Item item) {
        return !stack.isEmpty() && stack.is(item) && ItemStack.isSameItemSameComponents(stack,new ItemStack(item));
    }
    private static boolean invalidRelevant(Container c) {
        for(int i=0;i<c.getContainerSize();i++) {
            var s=c.getItem(i);
            if(s.is(Items.OAK_LOG) && !plain(s,Items.OAK_LOG) || s.is(Items.OAK_PLANKS) && !plain(s,Items.OAK_PLANKS))return true;
        }
        return false;
    }
    private static CraftingInput input(ItemStack one) {
        return CraftingInput.of(2,2,List.of(one.copyWithCount(1),ItemStack.EMPTY,ItemStack.EMPTY,ItemStack.EMPTY));
    }
    private static String identity(ContainerRef ref,Container c) {
        return "recipe-container:"+new BlockPos(ref.x(),ref.y(),ref.z()).asLong()+":"+System.identityHashCode(c);
    }
    static String fingerprint(Container c) {
        StringBuilder rows=new StringBuilder();
        for(int i=0;i<c.getContainerSize();i++)rows.append(i).append(':').append(ItemStack.hashItemAndComponents(c.getItem(i)))
                .append(':').append(c.getItem(i).getCount()).append(';');
        return RecipeTransaction.fingerprint(rows.toString());
    }
    private static List<ItemStack> copy(Container c) {
        var result=new ArrayList<ItemStack>();for(int i=0;i<c.getContainerSize();i++)result.add(c.getItem(i).copy());return result;
    }
    private static void apply(Container c,List<ItemStack> contents) {
        for(int i=0;i<contents.size();i++)c.setItem(i,contents.get(i));c.setChanged();
    }
    private static int capacity(Container c,ItemStack item) {
        int result=0;
        for(int i=0;i<c.getContainerSize();i++) {
            ItemStack s=c.getItem(i);
            if(!c.canPlaceItem(i,item) || !s.isEmpty() && !ItemStack.isSameItemSameComponents(s,item))continue;
            int maximum=Math.min(item.getMaxStackSize(),c.getMaxStackSize(item));
            if(!s.isEmpty())maximum=Math.min(maximum,s.getMaxStackSize());
            result=Math.addExact(result,Math.max(0,maximum-s.getCount()));
        }
        return result;
    }
    private static List<ItemStack> insert(List<ItemStack> slots,Container policy,ItemStack actual) {
        int remaining=actual.getCount();
        for(int pass=0;pass<2;pass++)for(int i=0;i<slots.size() && remaining>0;i++) {
            ItemStack s=slots.get(i);
            if(!policy.canPlaceItem(i,actual) || pass==0 && (s.isEmpty() || !ItemStack.isSameItemSameComponents(s,actual))
                    || pass==1 && !s.isEmpty())continue;
            int maximum=Math.min(actual.getMaxStackSize(),policy.getMaxStackSize(actual));
            if(!s.isEmpty())maximum=Math.min(maximum,s.getMaxStackSize());
            int n=Math.min(remaining,Math.max(0,maximum-s.getCount()));
            if(n>0) {slots.set(i,actual.copyWithCount(s.getCount()+n));remaining-=n;}
        }
        return remaining==0 ? slots : null;
    }
}
