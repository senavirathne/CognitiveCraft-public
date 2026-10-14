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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Blocks;

/** One installed-recipe/custody pipeline, with atomic bounded server-thread slot commits. */
final class FabricRecipeTransactions {
    private final ServerLevel level;
    private final FabricGatewayWorld world;
    private final Map<String,RecipeManager> managers=new HashMap<>();
    private final Map<String,RecipeView> snapshots=new HashMap<>();
    FabricRecipeTransactions(ServerLevel level,FabricGatewayWorld world) {this.level=level;this.world=world;}
    private record RecipeView(CraftingRecipe recipe,RecipeTransaction.Snapshot snapshot) { }
    private static Item inputItem(RecipeProduction.Definition d) {return d.inputPerBatch()==1 ? Items.OAK_LOG : Items.OAK_PLANKS;}
    private static Item outputItem(RecipeProduction.Definition d) {return d.axe() ? Items.WOODEN_AXE : d.inputPerBatch()==1 ? Items.OAK_PLANKS : Items.STICK;}

    private RecipeView capture(String key) {
        var d=RecipeProduction.recipe(key);if(d.axe())return captureAxe(key,d);boolean stick=d.inputPerBatch()==2;
        var current=level.getServer().getRecipeManager();
        var holder=current.byKey(ResourceKey.create(Registries.RECIPE,Identifier.parse(key)));
        if(holder.isEmpty() || holder.get().value().getClass()!=(stick ? ShapedRecipe.class : ShapelessRecipe.class))return null;
        var recipe=(CraftingRecipe)holder.get().value();if(recipe.isSpecial())return null;
        var placement=recipe.placementInfo();var slots=placement.slotsToIngredientIndex();
        if(placement.isImpossibleToPlace() || placement.ingredients().isEmpty() || placement.ingredients().size()>d.inputPerBatch()
                || slots.size()>4 || slots.stream().filter(i->i!=PlacementInfo.EMPTY_SLOT).count()!=d.inputPerBatch())return null;
        var alternatives=new ArrayList<List<String>>();
        for(var ingredient:placement.ingredients()) {
            var options=ingredient.items().limit(17).toList();
            if(options.isEmpty() || options.size()>16)return null;
            var names=options.stream().map(i->i.unwrapKey().orElseThrow().identifier().toString()).sorted().toList();
            if(!names.contains(d.input().id()))return null;alternatives.add(names);
        }
        for(int position=0;position<(stick ? 2 : 4);position++) {
            var grid=new ArrayList<ItemStack>(List.of(ItemStack.EMPTY,ItemStack.EMPTY,ItemStack.EMPTY,ItemStack.EMPTY));
            grid.set(position,new ItemStack(inputItem(d)));
            if(stick)grid.set(position+2,new ItemStack(inputItem(d)));
            if(!validResult(recipe,CraftingInput.of(2,2,grid),d))return null;
        }
        if(stick) {
            // Require the declared vertical two-input layout, not a horizontal or incomplete replacement recipe.
            for(var grid:List.of(List.of(new ItemStack(Items.OAK_PLANKS),new ItemStack(Items.OAK_PLANKS),ItemStack.EMPTY,ItemStack.EMPTY),
                    List.of(new ItemStack(Items.OAK_PLANKS),ItemStack.EMPTY,ItemStack.EMPTY,ItemStack.EMPTY)))
                if(recipe.matches(CraftingInput.of(2,2,grid),level))return null;
        }
        // Preserve the accepted plank semantic fingerprint exactly.
        String semantic=stick ? "inventory-2x2|"+key+"|"+recipe.getClass().getName()+"|"+alternatives+"|"+slots
                +"|two-vertical-default-oak-planks|four-default-sticks|no-remainders"
                : "inventory-2x2|"+key+"|"+recipe.getClass().getName()+"|"+alternatives.getFirst()+"|one-default-oak-log|four-default-oak-planks|no-remainders";
        String fingerprint=RecipeTransaction.fingerprint(semantic);var old=snapshots.get(key);
        long generation=old==null ? 1 : old.snapshot().generation();
        if(old!=null && (managers.get(key)!=current || old.recipe()!=recipe || !old.snapshot().fingerprint().equals(fingerprint)))
            generation=Math.incrementExact(generation);
        var result=new RecipeView(recipe,new RecipeTransaction.Snapshot(key,fingerprint,generation,d.inputPerBatch(),4,0));
        managers.put(key,current);snapshots.put(key,result);return result;
    }
    private RecipeView captureAxe(String key,RecipeProduction.Definition d) {
        var manager=level.getServer().getRecipeManager();var holder=manager.byKey(ResourceKey.create(Registries.RECIPE,Identifier.parse(key)));
        if(holder.isEmpty() || holder.get().value().getClass()!=ShapedRecipe.class)return null;
        var recipe=(CraftingRecipe)holder.get().value();var placement=recipe.placementInfo();
        if(recipe.isSpecial() || placement.isImpossibleToPlace() || placement.ingredients().isEmpty() || placement.ingredients().size()>5
                || placement.slotsToIngredientIndex().size()>9 || placement.slotsToIngredientIndex().stream().filter(i->i!=PlacementInfo.EMPTY_SLOT).count()!=5)return null;
        var alternatives=new ArrayList<List<String>>();
        for(var ingredient:placement.ingredients()) {
            var options=ingredient.items().limit(17).toList();if(options.isEmpty() || options.size()>16)return null;
            var names=options.stream().map(i->i.unwrapKey().orElseThrow().identifier().toString()).sorted().toList();
            if(!names.contains("minecraft:oak_planks") && !names.equals(List.of("minecraft:stick")))return null;alternatives.add(names);
        }
        if(!validResult(recipe,axeInput(false),d) || !validResult(recipe,axeInput(true),d)
                || recipe.matches(CraftingInput.of(2,2,List.of(new ItemStack(Items.OAK_PLANKS),new ItemStack(Items.STICK),ItemStack.EMPTY,ItemStack.EMPTY)),level))return null;
        String semantic="table-3x3|"+key+"|"+recipe.getClass().getName()+"|"+alternatives+"|"+placement.slotsToIngredientIndex()+"|three-default-oak-planks-two-default-sticks|one-default-wooden-axe|mirrored|no-remainders";
        String fingerprint=RecipeTransaction.fingerprint(semantic);var old=snapshots.get(key);long generation=old==null ? 1 : old.snapshot().generation();
        if(old!=null && (managers.get(key)!=manager || old.recipe()!=recipe || !old.snapshot().fingerprint().equals(fingerprint)))generation=Math.incrementExact(generation);
        var view=new RecipeView(recipe,new RecipeTransaction.Snapshot(key,fingerprint,generation,5,1,0));managers.put(key,manager);snapshots.put(key,view);return view;
    }
    private static CraftingInput axeInput(boolean mirrored) {
        var slots=new ArrayList<ItemStack>(Collections.nCopies(9,ItemStack.EMPTY));
        for(int i:new int[]{0,1,3})slots.set(mirrored ? (i/3)*3+2-i%3 : i,new ItemStack(Items.OAK_PLANKS));
        for(int i:new int[]{4,7})slots.set(mirrored ? (i/3)*3+2-i%3 : i,new ItemStack(Items.STICK));
        return CraftingInput.of(3,3,slots);
    }
    private Reason tableGuard(FacilityRef table,String identity,Villager v,boolean reach) {
        if(table==null || !table.dimension().equals(level.dimension().identifier().toString()))return Reason.FACILITY_MISSING;
        var pos=new BlockPos(table.x(),table.y(),table.z());
        if(!level.hasChunkAt(pos) || !level.getBlockState(pos).is(Blocks.CRAFTING_TABLE))return Reason.FACILITY_MISSING;
        if(!level.mayInteract(v,pos) || !level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.MOB_GRIEFING))return Reason.AUTHORITY_DENIED;
        var actual=FabricFacilityIdentity.capture(level,pos);
        if(actual==null)return Reason.BUDGET_EXHAUSTED;
        if(identity!=null && !identity.equals(actual))return Reason.STALE_OBSERVATION;
        if(reach && !world.recipeReach(v,pos))return Reason.TARGET_UNAVAILABLE;return null;
    }
    private boolean validResult(CraftingRecipe recipe,CraftingInput input,RecipeProduction.Definition d) {
        if(!recipe.matches(input,level))return false;
        ItemStack actual=recipe.assemble(input);var remainders=recipe.getRemainingItems(input);
        return plain(actual,outputItem(d)) && actual.getCount()==d.outputPerBatch() && actual.getMaxStackSize()==(d.axe() ? 1 : 64) && remainders.size()<=(d.axe() ? 9 : 4) && remainders.stream().allMatch(ItemStack::isEmpty);
    }
    RecipeTransaction.Preflight preflight(ValidatedRequest bound) {
        var d=RecipeProduction.of(bound.request().capability());
        var actor=((ActorValue)bound.request().arguments().get("actor")).value();
        var source=((ContainerValue)bound.request().arguments().get("source")).value();
        var target=((ContainerValue)bound.request().arguments().get("destination")).value();
        Villager v=world.recipeActor(actor);if(v==null)return blocked(Reason.ACTOR_UNAVAILABLE);
        for(var entry:List.of(Map.entry(source,Effect.WITHDRAW),Map.entry(target,Effect.TRANSFER))) {
            var c=entry.getKey();var reason=world.permit(bound,entry.getValue(),new SurvivalGateway.Cell(c.dimension(),c.x(),c.y(),c.z()));
            if(reason!=null)return blocked(reason);
        }
        Container from=world.recipeContainer(source),to=world.recipeContainer(target);
        if(from==null || to==null)return blocked(Reason.TARGET_UNAVAILABLE);
        if(v.getInventory().getContainerSize()>8 || from.getContainerSize()>128 || to.getContainerSize()>128)return blocked(Reason.BUDGET_EXHAUSTED);
        var recipe=capture(d.recipe());if(recipe==null)return blocked(Reason.UNSUPPORTED_PRIMITIVE);
        FacilityRef facility=d.axe() && bound.request().arguments().get("table") instanceof FacilityValue f ? f.value() : null;
        String facilityIdentity="";
        if(d.axe()) {
            var denied=tableGuard(facility,null,v,false);if(denied!=null)return blocked(denied);
            facilityIdentity=FabricFacilityIdentity.capture(level,new BlockPos(facility.x(),facility.y(),facility.z()));
        }
        long available=0,secondary=0;
        for(int i=0;i<from.getContainerSize();i++) {
            var s=from.getItem(i);if(s.is(inputItem(d)) && !plain(s,inputItem(d)))return blocked(Reason.TARGET_INVALID);
            if(plain(s,inputItem(d)))available=Math.addExact(available,s.getCount());
            if(d.axe() && s.is(Items.STICK) && !plain(s,Items.STICK))return blocked(Reason.TARGET_INVALID);
            if(d.axe() && plain(s,Items.STICK))secondary=Math.addExact(secondary,s.getCount());
        }
        if(invalidRelevant(v.getInventory(),d))return blocked(Reason.TARGET_INVALID);
        var output=recipe.recipe().assemble(input(new ItemStack(inputItem(d)),d));
        var first=insert(copy(v.getInventory()),v.getInventory(),new ItemStack(inputItem(d),d.inputPerBatch()));
        if(d.axe() && first!=null)first=insert(first,v.getInventory(),new ItemStack(Items.STICK,2));
        if(first==null || !remove(first,inputItem(d),d.inputPerBatch()) || d.axe() && !remove(first,Items.STICK,2) || insert(first,v.getInventory(),output.copy())==null)
            return blocked(Reason.TARGET_UNAVAILABLE);
        return new RecipeTransaction.Preflight(ObservationStatus.PRESENT,recipe.snapshot(),available,capacity(to,output),
                identity(source,from),identity(target,to),fingerprint(from),fingerprint(v.getInventory()),
                from.getContainerSize(),to.getContainerSize(),null,secondary,facility,facilityIdentity);
    }
    private RecipeTransaction.Preflight blocked(Reason r) {
        return new RecipeTransaction.Preflight(ObservationStatus.UNKNOWN,null,0,0,"","","","",0,0,r);
    }
    RecipeTransaction.Mutation withdraw(ActorRef actor,ContainerRef source,RecipeTransaction.Preflight captured,String custody,String sourceFingerprint) {
        var d=RecipeProduction.recipe(captured.recipe().key());
        Villager v=world.recipeActor(actor);Container from=world.recipeContainer(source);
        Reason denied=guard(v,from,source,captured.sourceIdentity(),custody);
        if(denied!=null)return RecipeTransaction.Mutation.blocked(denied);
        if(!fingerprint(from).equals(sourceFingerprint))return RecipeTransaction.Mutation.blocked(Reason.STALE_OBSERVATION);
        if(!current(captured))return RecipeTransaction.Mutation.blocked(Reason.ARTIFACT_INCOMPATIBLE);
        var sourceSlots=copy(from);ItemStack actual=sourceSlots.stream().filter(s->plain(s,inputItem(d))).findFirst().orElse(ItemStack.EMPTY);
        if(actual.isEmpty() || !remove(sourceSlots,inputItem(d),d.inputPerBatch()))return RecipeTransaction.Mutation.blocked(Reason.RESOURCE_MISSING);
        if(d.axe() && !remove(sourceSlots,Items.STICK,2))return RecipeTransaction.Mutation.blocked(Reason.RESOURCE_MISSING);
        var next=insert(copy(v.getInventory()),v.getInventory(),actual.copyWithCount(d.inputPerBatch()));
        if(d.axe() && next!=null)next=insert(next,v.getInventory(),new ItemStack(Items.STICK,2));
        if(next==null)return RecipeTransaction.Mutation.blocked(Reason.TARGET_UNAVAILABLE);
        apply(from,sourceSlots);apply(v.getInventory(),next);
        return new RecipeTransaction.Mutation(0,0,d.totalInputs(),0,custody,fingerprint(v.getInventory()),fingerprint(from),null);
    }
    RecipeTransaction.Mutation craft(ActorRef actor,RecipeTransaction.Preflight captured,String custody) {
        var d=RecipeProduction.recipe(captured.recipe().key());Villager v=world.recipeActor(actor);
        if(v==null || !v.onGround() || !GatewayControl.controls(v))return RecipeTransaction.Mutation.blocked(Reason.ACTOR_UNAVAILABLE);
        if(!fingerprint(v.getInventory()).equals(custody))return RecipeTransaction.Mutation.blocked(Reason.STALE_OBSERVATION);
        if(d.axe()) {var denied=tableGuard(captured.facility(),captured.facilityIdentity(),v,true);if(denied!=null)return RecipeTransaction.Mutation.blocked(denied);}
        var recipe=capture(d.recipe());
        if(recipe==null || !recipe.snapshot().equals(captured.recipe()))return RecipeTransaction.Mutation.blocked(Reason.ARTIFACT_INCOMPATIBLE);
        var next=copy(v.getInventory());var actual=next.stream().filter(s->plain(s,inputItem(d))).findFirst().orElse(ItemStack.EMPTY);
        if(actual.isEmpty() || !remove(next,inputItem(d),d.inputPerBatch()))return RecipeTransaction.Mutation.blocked(Reason.RESOURCE_MISSING);
        if(d.axe() && !remove(next,Items.STICK,2))return RecipeTransaction.Mutation.blocked(Reason.RESOURCE_MISSING);
        var input=input(actual,d);if(!validResult(recipe.recipe(),input,d))return RecipeTransaction.Mutation.blocked(Reason.UNSUPPORTED_PRIMITIVE);
        var output=recipe.recipe().assemble(input);next=insert(next,v.getInventory(),output.copy());
        if(next==null)return RecipeTransaction.Mutation.blocked(Reason.TARGET_UNAVAILABLE);
        apply(v.getInventory(),next);output.onCraftedBySystem(level);
        return new RecipeTransaction.Mutation(d.totalInputs(),output.getCount(),0,0,custody,fingerprint(v.getInventory()),"",null);
    }
    RecipeTransaction.Mutation deposit(ActorRef actor,ContainerRef target,RecipeTransaction.Preflight captured,String custody,int quantity) {
        var d=RecipeProduction.recipe(captured.recipe().key());Villager v=world.recipeActor(actor);Container to=world.recipeContainer(target);
        Reason denied=guard(v,to,target,captured.destinationIdentity(),custody);
        if(denied!=null)return RecipeTransaction.Mutation.blocked(denied);
        if(quantity<1 || quantity>d.outputPerBatch())return RecipeTransaction.Mutation.blocked(Reason.REQUEST_INVALID);
        var inventory=copy(v.getInventory());var actual=inventory.stream().filter(s->plain(s,outputItem(d))).findFirst().orElse(ItemStack.EMPTY);
        if(actual.isEmpty() || !remove(inventory,outputItem(d),quantity))return RecipeTransaction.Mutation.blocked(Reason.STALE_OBSERVATION);
        var destination=insert(copy(to),to,actual.copyWithCount(quantity));
        if(destination==null)return RecipeTransaction.Mutation.blocked(Reason.TARGET_UNAVAILABLE);
        apply(v.getInventory(),inventory);apply(to,destination);
        return new RecipeTransaction.Mutation(0,0,quantity,0,custody,fingerprint(v.getInventory()),
                captured.sourceIdentity().equals(captured.destinationIdentity()) ? fingerprint(to) : "",null);
    }
    private Reason guard(Villager v,Container box,ContainerRef ref,String identity,String custody) {
        if(v==null || box==null || !v.onGround())return Reason.TARGET_UNAVAILABLE;
        if(!GatewayControl.controls(v))return Reason.ACTOR_UNAVAILABLE;
        if(!identity(ref,box).equals(identity) || !fingerprint(v.getInventory()).equals(custody))return Reason.STALE_OBSERVATION;
        if(!world.recipeReach(v,new BlockPos(ref.x(),ref.y(),ref.z())))return Reason.TARGET_UNAVAILABLE;return null;
    }
    private boolean current(RecipeTransaction.Preflight snapshot) {var actual=capture(snapshot.recipe().key());return actual!=null && actual.snapshot().equals(snapshot.recipe());}
    static boolean plain(ItemStack s,Item item) {return !s.isEmpty() && s.is(item) && ItemStack.isSameItemSameComponents(s,new ItemStack(item));}
    private static boolean invalidRelevant(Container c,RecipeProduction.Definition d) {
        for(int i=0;i<c.getContainerSize();i++) {var s=c.getItem(i);
            if(s.is(inputItem(d)) && !plain(s,inputItem(d)) || s.is(outputItem(d)) && !plain(s,outputItem(d)) || d.axe() && s.is(Items.STICK) && !plain(s,Items.STICK))return true;}return false;
    }
    private static CraftingInput input(ItemStack actual,RecipeProduction.Definition d) {
        if(d.axe())return axeInput(false);
        return CraftingInput.of(2,2,List.of(actual.copyWithCount(1),ItemStack.EMPTY,d.inputPerBatch()==2 ? actual.copyWithCount(1) : ItemStack.EMPTY,ItemStack.EMPTY));
    }
    private static boolean remove(List<ItemStack> slots,Item item,int count) {
        int remaining=count;for(int i=0;i<slots.size() && remaining>0;i++) {var s=slots.get(i);if(!plain(s,item))continue;
            int n=Math.min(remaining,s.getCount());slots.set(i,s.copyWithCount(s.getCount()-n));remaining-=n;}return remaining==0;
    }
    private static String identity(ContainerRef ref,Container c) {return "recipe-container:"+new BlockPos(ref.x(),ref.y(),ref.z()).asLong()+":"+System.identityHashCode(c);}
    static String fingerprint(Container c) {
        StringBuilder rows=new StringBuilder();for(int i=0;i<c.getContainerSize();i++)rows.append(i).append(':').append(ItemStack.hashItemAndComponents(c.getItem(i))).append(':').append(c.getItem(i).getCount()).append(';');
        return RecipeTransaction.fingerprint(rows.toString());
    }
    private static List<ItemStack> copy(Container c) {var result=new ArrayList<ItemStack>();for(int i=0;i<c.getContainerSize();i++)result.add(c.getItem(i).copy());return result;}
    private static void apply(Container c,List<ItemStack> contents) {for(int i=0;i<contents.size();i++)c.setItem(i,contents.get(i));c.setChanged();}
    private static int capacity(Container c,ItemStack item) {
        int result=0;for(int i=0;i<c.getContainerSize();i++) {var s=c.getItem(i);
            if(!c.canPlaceItem(i,item) || !s.isEmpty() && !ItemStack.isSameItemSameComponents(s,item))continue;
            int maximum=Math.min(item.getMaxStackSize(),c.getMaxStackSize(item));if(!s.isEmpty())maximum=Math.min(maximum,s.getMaxStackSize());
            result=Math.addExact(result,Math.max(0,maximum-s.getCount()));}return result;
    }
    private static List<ItemStack> insert(List<ItemStack> slots,Container policy,ItemStack actual) {
        int remaining=actual.getCount();for(int pass=0;pass<2;pass++)for(int i=0;i<slots.size() && remaining>0;i++) {var s=slots.get(i);
            if(!policy.canPlaceItem(i,actual) || pass==0 && (s.isEmpty() || !ItemStack.isSameItemSameComponents(s,actual)) || pass==1 && !s.isEmpty())continue;
            int maximum=Math.min(actual.getMaxStackSize(),policy.getMaxStackSize(actual));if(!s.isEmpty())maximum=Math.min(maximum,s.getMaxStackSize());
            int n=Math.min(remaining,Math.max(0,maximum-s.getCount()));if(n>0) {slots.set(i,actual.copyWithCount(s.getCount()+n));remaining-=n;}}
        return remaining==0 ? slots : null;
    }
}
