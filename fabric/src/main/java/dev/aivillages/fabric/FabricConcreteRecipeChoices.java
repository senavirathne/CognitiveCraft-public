package dev.aivillages.fabric;

import com.mojang.serialization.JsonOps;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.production.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.RecipeTransaction.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;

/** Version-2 native host, deliberately not registered on a player/runtime route before I00. */
final class FabricConcreteRecipeChoices {
    private final ServerLevel level;
    private final FabricGatewayWorld world;
    private final Map<String,View> views=new HashMap<>();
    private record View(CraftingRecipe recipe,RecipeManager manager,String semantic,long epoch){}
    FabricConcreteRecipeChoices(ServerLevel level,FabricGatewayWorld world){this.level=level;this.world=world;}

    /** Opaque per-operation context. No global step/effect registry, journal or replay store. */
    static final class Context {
        final String token;final RecipeChoice choice;final int batch;final ValidatedRequest request;final UUID run;
        final ChoiceAuthority authority;final View recipe;final String custody;final String source;final String sourceIdentity;final String destinationIdentity;final ChoicePhase next;
        final Villager nativeActor;final Container nativeSource;final Container nativeDestination;
        final AtomicBoolean used=new AtomicBoolean();
        Context(String token,RecipeChoice choice,int batch,ValidatedRequest request,UUID run,ChoiceAuthority authority,
                View recipe,String custody,String source,String sourceIdentity,String destinationIdentity,ChoicePhase next,
                Villager nativeActor,Container nativeSource,Container nativeDestination) {
            this.token=token;this.choice=choice;this.batch=batch;this.request=request;this.run=run;this.authority=authority;
            this.recipe=recipe;this.custody=custody;this.source=source;this.sourceIdentity=sourceIdentity;this.destinationIdentity=destinationIdentity;this.next=next;
            this.nativeActor=nativeActor;this.nativeSource=nativeSource;this.nativeDestination=nativeDestination;
        }
    }
    record Result(Context continuation,ChoiceObservation observation){}
    private static final class Refused extends RuntimeException {
        final Reason reason;Refused(Reason reason){super(reason.name());this.reason=reason;}
    }
    private static Refused refuse(Reason reason){return new Refused(reason);}
    private void thread(){if(!level.getServer().isSameThread())throw new IllegalStateException("Native recipe host requires the server thread");}

    private View view(CapabilityId capability) {
        var d=RecipeProduction.choiceDefinition(capability);
        // B03 admits only the existing oak/birch plank outcome families. Other
        // recipe families remain disabled until their own subsequent stage.
        if(!d.output().id().endsWith("_planks"))throw refuse(Reason.UNSUPPORTED_PRIMITIVE);
        var manager=level.getServer().getRecipeManager();
        var holder=manager.byKey(ResourceKey.create(Registries.RECIPE,Identifier.parse(d.recipe())));
        if(holder.isEmpty() || holder.get().value().getClass()!=ShapelessRecipe.class)throw refuse(Reason.UNSUPPORTED_PRIMITIVE);
        var recipe=(CraftingRecipe)holder.get().value();var placement=recipe.placementInfo();
        if(recipe.isSpecial() || placement.isImpossibleToPlace() || placement.ingredients().size()!=1
                || placement.slotsToIngredientIndex().stream().filter(i->i!=PlacementInfo.EMPTY_SLOT).count()!=1)
            throw refuse(Reason.UNSUPPORTED_PRIMITIVE);
        var alternatives=placement.ingredients().getFirst().items().map(i->i.unwrapKey().orElseThrow().identifier().toString()).sorted().toList();
        if(alternatives.isEmpty() || alternatives.size()>32)throw refuse(Reason.UNSUPPORTED_PRIMITIVE);
        var ops=RegistryOps.create(JsonOps.INSTANCE,level.registryAccess());
        var encoded=net.minecraft.world.item.crafting.Recipe.DIRECT_CODEC.encodeStart(ops,recipe).getOrThrow();
        String semantic=RecipeTransaction.fingerprint(StrictJson.canonical(Map.of("compatibility",RecipeChoice.RUNTIME,
                "recipe",d.recipe(),"class",recipe.getClass().getName(),"json",RecipeChoice.canonicalComponents(encoded.toString()),
                "expandedIngredients",alternatives,"context","inventory-2x2")));
        var old=views.get(d.recipe());long epoch=old==null?0:old.epoch();
        if(old!=null && (old.manager()!=manager || old.recipe()!=recipe || !old.semantic().equals(semantic)))epoch=Math.incrementExact(epoch);
        var current=new View(recipe,manager,semantic,epoch);views.put(d.recipe(),current);return current;
    }
    static RecipeChoice.ItemKey itemKey(ServerLevel level,ItemStack stack) {
        if(stack.isEmpty())return RecipeChoice.ItemKey.EMPTY;
        var ops=RegistryOps.create(JsonOps.INSTANCE,level.registryAccess());
        var encoded=DataComponentMap.CODEC.encodeStart(ops,stack.getComponents()).getOrThrow();
        return new RecipeChoice.ItemKey(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),RecipeChoice.canonicalComponents(encoded.toString()));
    }
    private ItemStack stack(RecipeChoice.ItemKey key) {
        if(key.empty())return ItemStack.EMPTY;
        Item item=BuiltInRegistries.ITEM.getValue(Identifier.parse(key.id()));var value=new ItemStack(item);
        if(item==Items.AIR || !itemKey(level,value).equals(key))throw refuse(Reason.TARGET_INVALID);
        return value;
    }
    private boolean permittedInput(RecipeProduction.Definition definition,ItemStack value) {
        if(!FabricRecipeTransactions.plain(value,value.getItem()))return false;
        String family=definition.output().id().replace("_planks","");
        String namespace=family.substring(0,family.indexOf(':')+1),material=family.substring(family.indexOf(':')+1);
        String id=BuiltInRegistries.ITEM.getKey(value.getItem()).toString();
        return Set.of(family+"_log",family+"_wood",namespace+"stripped_"+material+"_log",namespace+"stripped_"+material+"_wood").contains(id);
    }
    private CraftingInput input(RecipeChoice choice,int index) {
        return CraftingInput.of(choice.width(),choice.height(),choice.batch(index).grid().stream().map(this::stack).toList());
    }
    private void nativeMatch(RecipeChoice choice,int index,View view) {
        if(!choice.semanticFingerprint().equals(view.semantic()) || !RecipeChoice.RUNTIME.equals(choice.runtimeCompatibility()))throw refuse(Reason.ARTIFACT_INCOMPATIBLE);
        if(choice.width()!=2 || choice.height()!=2 || !choice.remainders().isEmpty())throw refuse(Reason.UNSUPPORTED_PRIMITIVE);
        var d=RecipeProduction.choiceDefinition(choice.capability());var grid=input(choice,index);
        if(choice.batch(index).grid().stream().filter(i->!i.empty()).count()!=1
                || choice.batch(index).grid().stream().filter(i->!i.empty()).map(this::stack).anyMatch(s->!permittedInput(d,s))
                || !view.recipe().matches(grid,level))throw refuse(Reason.TARGET_INVALID);
        var result=view.recipe().assemble(grid);
        if(result.getCount()!=choice.resultCount() || !itemKey(level,result).equals(choice.result())
                || view.recipe().getRemainingItems(grid).stream().anyMatch(s->!s.isEmpty()))throw refuse(Reason.UNSUPPORTED_PRIMITIVE);
    }
    private ActorRef actor(ValidatedRequest request){if(!(request.request().arguments().get("actor") instanceof ActorValue a))throw refuse(Reason.REQUEST_INVALID);return a.value();}
    private ContainerRef container(ValidatedRequest request,String key){if(!(request.request().arguments().get(key) instanceof ContainerValue v))throw refuse(Reason.REQUEST_INVALID);return v.value();}
    private Villager nativeActor(ValidatedRequest request,UUID run) {
        var actor=actor(request);var v=world.recipeActor(actor);
        if(v==null || !v.isAlive() || !world.holds(actor,run))throw refuse(Reason.ACTOR_UNAVAILABLE);
        if(!v.onGround())throw refuse(Reason.ACTOR_UNAVAILABLE);return v;
    }
    private Container permitted(ValidatedRequest request,UUID run,ChoiceAuthority authority,ContainerRef ref,ChoicePhase phase,boolean reach) {
        if(authority==null || !authority.permits(request.context(),actor(request),ref,phase))throw refuse(Reason.AUTHORITY_DENIED);
        var box=world.recipeContainer(ref);if(box==null)throw refuse(Reason.TARGET_UNAVAILABLE);
        var pos=new BlockPos(ref.x(),ref.y(),ref.z());var v=world.recipeActor(actor(request));
        if(v==null || !level.mayInteract(v,pos) || reach && !world.recipeReach(v,pos))throw refuse(Reason.TARGET_UNAVAILABLE);
        if(box.getContainerSize()>128)throw refuse(Reason.UNSUPPORTED_PRIMITIVE);return box;
    }
    RecipeChoice select(ValidatedRequest request,UUID run,ChoiceAuthority authority) {
        thread();
        nativeActor(request,run);var cap=request.request().capability();var d=RecipeProduction.choiceDefinition(cap);var recipe=view(cap);
        if(!(request.request().arguments().get("amount") instanceof IntValue amount))throw refuse(Reason.REQUEST_INVALID);
        var quantities=d.quantities(amount.value(),BatchQuantities.Role.ROOT);
        var source=container(request,"source");var from=permitted(request,run,authority,source,ChoicePhase.DISCOVER,false);
        permitted(request,run,authority,container(request,"destination"),ChoicePhase.DISCOVER,false);
        var available=FabricRecipeTransactions.copy(from);var batches=new ArrayList<RecipeChoice.Batch>();RecipeChoice.ItemKey resultKey=null;
        for(int b=0;b<quantities.batches();b++) {
            int chosen=-1;
            for(int slot=0;slot<available.size();slot++) {
                var value=available.get(slot);if(value.isEmpty() || !permittedInput(d,value))continue;
                var probe=CraftingInput.of(2,2,List.of(value.copyWithCount(1),ItemStack.EMPTY,ItemStack.EMPTY,ItemStack.EMPTY));
                if(recipe.recipe().matches(probe,level)){chosen=slot;break;}
            }
            if(chosen<0)throw refuse(Reason.RESOURCE_MISSING);
            var actual=available.get(chosen);var key=itemKey(level,actual);
            var grid=List.of(key,RecipeChoice.ItemKey.EMPTY,RecipeChoice.ItemKey.EMPTY,RecipeChoice.ItemKey.EMPTY);
            var binding=new RecipeChoice.Binding(0,source,chosen,actual.getCount(),key);
            var nativeGrid=CraftingInput.of(2,2,List.of(actual.copyWithCount(1),ItemStack.EMPTY,ItemStack.EMPTY,ItemStack.EMPTY));
            var result=recipe.recipe().assemble(nativeGrid);var keyResult=itemKey(level,result);
            if(result.getCount()!=d.outputPerBatch() || !result.is(FabricResourceItems.item(d.output()))
                    || !FabricRecipeTransactions.plain(result,result.getItem()) || recipe.recipe().getRemainingItems(nativeGrid).stream().anyMatch(s->!s.isEmpty())
                    || resultKey!=null && !resultKey.equals(keyResult))throw refuse(Reason.UNSUPPORTED_PRIMITIVE);
            resultKey=keyResult;batches.add(new RecipeChoice.Batch(grid,List.of(binding)));
            available.set(chosen,actual.copyWithCount(actual.getCount()-1));
        }
        var choice=new RecipeChoice(cap,d.recipe(),recipe.semantic(),RecipeChoice.RUNTIME,recipe.epoch(),2,2,batches,resultKey,d.outputPerBatch(),amount.value(),List.of());
        wholeChoiceCapacity(request,run,authority,choice,recipe);return choice;
    }
    private void wholeChoiceCapacity(ValidatedRequest request,UUID run,ChoiceAuthority authority,RecipeChoice choice,View recipe) {
        var v=nativeActor(request,run);var source=container(request,"source");var target=container(request,"destination");
        var from=permitted(request,run,authority,source,ChoicePhase.DISCOVER,false);
        var to=permitted(request,run,authority,target,ChoicePhase.DISCOVER,false);
        var inputs=FabricRecipeTransactions.copy(from);var destination=source.equals(target)?inputs:FabricRecipeTransactions.copy(to);
        var held=FabricRecipeTransactions.copy(v.getInventory());
        for(int batch=0;batch<choice.batches().size();batch++) {
            nativeMatch(choice,batch,recipe);
            for(var binding:choice.batch(batch).bindings()) {
                var actual=inputs.get(binding.sourceSlot());
                if(actual.getCount()!=binding.expectedCount() || !itemKey(level,actual).equals(binding.item()))throw refuse(Reason.STALE_OBSERVATION);
                held=FabricRecipeTransactions.insert(held,v.getInventory(),actual.copyWithCount(1));if(held==null)throw refuse(Reason.TARGET_UNAVAILABLE);
                inputs.set(binding.sourceSlot(),actual.copyWithCount(actual.getCount()-1));
            }
            held=consumeHeld(held,choice,batch);var result=recipe.recipe().assemble(input(choice,batch));
            held=FabricRecipeTransactions.insert(held,v.getInventory(),result.copy());if(held==null)throw refuse(Reason.TARGET_UNAVAILABLE);
            int delivery=Math.toIntExact(choice.deliveryForBatch(batch));
            if(!FabricRecipeTransactions.remove(held,result.getItem(),delivery)
                    || FabricRecipeTransactions.insert(destination,to,result.copyWithCount(delivery))==null)throw refuse(Reason.TARGET_UNAVAILABLE);
        }
    }
    Context prepare(ValidatedRequest request,RecipeChoice choice,int batch,UUID run,ChoiceAuthority authority) {
        thread();
        var v=nativeActor(request,run);checkRequest(request,choice);var recipe=view(choice.capability());nativeMatch(choice,batch,recipe);
        var source=container(request,"source");var target=container(request,"destination");
        var from=permitted(request,run,authority,source,ChoicePhase.PREPARE,true);
        var to=permitted(request,run,authority,target,ChoicePhase.PREPARE,false);
        if(choice.batch(batch).bindings().stream().anyMatch(b->!b.source().equals(source)))throw refuse(Reason.AUTHORITY_DENIED);
        var sourceCopy=FabricRecipeTransactions.copy(from);var custody=FabricRecipeTransactions.copy(v.getInventory());
        for(var binding:choice.batch(batch).bindings()) {
            if(binding.sourceSlot()>=sourceCopy.size())throw refuse(Reason.STALE_OBSERVATION);
            var value=sourceCopy.get(binding.sourceSlot());
            if(value.getCount()!=binding.expectedCount() || !itemKey(level,value).equals(binding.item()))throw refuse(Reason.STALE_OBSERVATION);
            sourceCopy.set(binding.sourceSlot(),value.copyWithCount(value.getCount()-1));
            custody=FabricRecipeTransactions.insert(custody,v.getInventory(),value.copyWithCount(1));if(custody==null)throw refuse(Reason.TARGET_UNAVAILABLE);
        }
        var afterCraft=consumeHeld(custody,choice,batch);var output=recipe.recipe().assemble(input(choice,batch));
        if(FabricRecipeTransactions.insert(afterCraft,v.getInventory(),output.copy())==null)throw refuse(Reason.TARGET_UNAVAILABLE);
        var destinationCopy=source.equals(target)?sourceCopy:FabricRecipeTransactions.copy(to);
        if(FabricRecipeTransactions.insert(destinationCopy,to,output.copyWithCount(Math.toIntExact(choice.deliveryForBatch(batch))))==null)throw refuse(Reason.TARGET_UNAVAILABLE);
        return new Context(UUID.randomUUID().toString(),choice,batch,request,run,authority,recipe,
                fingerprint(v.getInventory()),fingerprint(from),
                FabricRecipeTransactions.identity(source,from),FabricRecipeTransactions.identity(target,to),ChoicePhase.WITHDRAW,v,from,to);
    }
    private void checkRequest(ValidatedRequest request,RecipeChoice choice) {
        if(!request.request().capability().equals(choice.capability()) || !(request.request().arguments().get("amount") instanceof IntValue amount)
                || amount.value()!=choice.demand())throw refuse(Reason.REQUEST_INVALID);
    }
    private List<ItemStack> consumeHeld(List<ItemStack> held,RecipeChoice choice,int batch) {
        var next=new ArrayList<ItemStack>();held.forEach(s->next.add(s.copy()));
        for(var key:choice.batch(batch).grid())if(!key.empty()) {
            int slot=-1;for(int i=0;i<next.size();i++)if(!next.get(i).isEmpty() && itemKey(level,next.get(i)).equals(key)){slot=i;break;}
            if(slot<0)throw refuse(Reason.RESOURCE_MISSING);var value=next.get(slot);next.set(slot,value.copyWithCount(value.getCount()-1));
        }
        return next;
    }
    private record State(Map<String,List<ObservedSlot>> participants){}
    private List<ObservedSlot> observe(Container c) {
        return observe(FabricRecipeTransactions.copy(c));
    }
    private List<ObservedSlot> observe(List<ItemStack> slots) {
        var rows=new ArrayList<ObservedSlot>();for(int i=0;i<slots.size();i++)rows.add(new ObservedSlot(i,itemKey(level,slots.get(i)),slots.get(i).getCount()));return List.copyOf(rows);
    }
    /** Full serialized components, not the legacy 32-bit ItemStack component hash. */
    private String fingerprint(Container box) {
        var rows=observe(box).stream().map(s->Map.of("slot",s.slot(),"item",s.item().value(),"count",s.count())).toList();
        return RecipeTransaction.fingerprint(StrictJson.canonical(rows));
    }
    private String participant(ContainerRef ref,Container box){return "container:"+StrictJson.canonical(RecipeChoice.container(ref))+":"+System.identityHashCode(box);}
    private State state(Context c) {
        var rows=new LinkedHashMap<String,List<ObservedSlot>>();var v=world.recipeActor(actor(c.request));
        if(v!=null)rows.put("actor:"+v.getUUID(),observe(v.getInventory()));
        for(String key:List.of("source","destination")){var ref=container(c.request,key);var box=world.recipeContainer(ref);
            if(box!=null)rows.put(participant(ref,box),observe(box));}
        return new State(Map.copyOf(rows));
    }
    private ChoiceObservation observation(Context c,ChoicePhase phase,ChoiceObservationKind kind,State before,State after,Reason reason,String basis) {
        var ids=new TreeSet<String>();ids.addAll(before.participants().keySet());ids.addAll(after.participants().keySet());
        var rows=ids.stream().map(id->new ObservedParticipant(id,before.participants().getOrDefault(id,List.of()),after.participants().getOrDefault(id,List.of()))).toList();
        return new ChoiceObservation(c.token,c.choice.digest(),c.batch,phase,kind,rows,reason,basis);
    }
    Result execute(Context c,ChoicePhase phase) {
        thread();
        State before=new State(Map.of());boolean writesStarted=false;
        try {
            if(c.next!=phase || c.used.get())throw refuse(Reason.STALE_OBSERVATION);
            // Revocation must refuse before observing newly private participant state.
            if(c.authority==null || !c.authority.permits(c.request.context(),actor(c.request),container(c.request,"source"),phase)
                    || !c.authority.permits(c.request.context(),actor(c.request),container(c.request,"destination"),phase))
                throw refuse(Reason.AUTHORITY_DENIED);
            var v=nativeActor(c.request,c.run);checkRequest(c.request,c.choice);
            if(v!=c.nativeActor)throw refuse(Reason.STALE_OBSERVATION);
            before=state(c);
            var current=view(c.choice.capability());nativeMatch(c.choice,c.batch,current);
            if(current.manager()!=c.recipe.manager() || current.recipe()!=c.recipe.recipe())throw refuse(Reason.ARTIFACT_INCOMPATIBLE);
            if(!fingerprint(v.getInventory()).equals(c.custody))throw refuse(Reason.STALE_OBSERVATION);
            var source=container(c.request,"source");var target=container(c.request,"destination");
            var from=permitted(c.request,c.run,c.authority,source,phase,phase==ChoicePhase.WITHDRAW);
            var to=permitted(c.request,c.run,c.authority,target,phase,phase==ChoicePhase.DEPOSIT);
            if(from!=c.nativeSource || to!=c.nativeDestination || !FabricRecipeTransactions.identity(source,from).equals(c.sourceIdentity)
                    || !FabricRecipeTransactions.identity(target,to).equals(c.destinationIdentity))throw refuse(Reason.STALE_OBSERVATION);
            var custody=FabricRecipeTransactions.copy(v.getInventory());var sourceCopy=FabricRecipeTransactions.copy(from);
            var expected=new HashMap<>(before.participants());
            if(phase==ChoicePhase.WITHDRAW) {
                if(!fingerprint(from).equals(c.source))throw refuse(Reason.STALE_OBSERVATION);
                for(var binding:c.choice.batch(c.batch).bindings()) {
                    var value=sourceCopy.get(binding.sourceSlot());
                    if(value.getCount()!=binding.expectedCount() || !itemKey(level,value).equals(binding.item()))throw refuse(Reason.STALE_OBSERVATION);
                    custody=FabricRecipeTransactions.insert(custody,v.getInventory(),value.copyWithCount(1));if(custody==null)throw refuse(Reason.TARGET_UNAVAILABLE);
                    sourceCopy.set(binding.sourceSlot(),value.copyWithCount(value.getCount()-1));
                }
                if(!c.used.compareAndSet(false,true))throw refuse(Reason.STALE_OBSERVATION);
                expected.put(participant(source,from),observe(sourceCopy));expected.put("actor:"+v.getUUID(),observe(custody));
                writesStarted=true;FabricRecipeTransactions.apply(from,sourceCopy);FabricRecipeTransactions.apply(v.getInventory(),custody);
            } else if(phase==ChoicePhase.CRAFT) {
                custody=consumeHeld(custody,c.choice,c.batch);var result=current.recipe().assemble(input(c.choice,c.batch));
                custody=FabricRecipeTransactions.insert(custody,v.getInventory(),result.copy());if(custody==null)throw refuse(Reason.TARGET_UNAVAILABLE);
                if(!c.used.compareAndSet(false,true))throw refuse(Reason.STALE_OBSERVATION);
                expected.put("actor:"+v.getUUID(),observe(custody));
                writesStarted=true;FabricRecipeTransactions.apply(v.getInventory(),custody);result.onCraftedBySystem(level);
            } else if(phase==ChoicePhase.DEPOSIT) {
                int quantity=Math.toIntExact(c.choice.deliveryForBatch(c.batch));var actual=stack(c.choice.result());
                if(!FabricRecipeTransactions.remove(custody,actual.getItem(),quantity))throw refuse(Reason.STALE_OBSERVATION);
                var destination=FabricRecipeTransactions.insert(FabricRecipeTransactions.copy(to),to,actual.copyWithCount(quantity));
                if(destination==null)throw refuse(Reason.TARGET_UNAVAILABLE);
                if(!c.used.compareAndSet(false,true))throw refuse(Reason.STALE_OBSERVATION);
                expected.put(participant(target,to),observe(destination));expected.put("actor:"+v.getUUID(),observe(custody));
                writesStarted=true;FabricRecipeTransactions.apply(v.getInventory(),custody);FabricRecipeTransactions.apply(to,destination);
            } else throw refuse(Reason.REQUEST_INVALID);
            State after=state(c);
            if(!expected.equals(after.participants()))return new Result(null,observation(c,phase,ChoiceObservationKind.PARTIAL_UNCERTAIN,before,after,
                    Reason.STALE_OBSERVATION,"Participant state diverged during the actual native call; no continuation or replay permission"));
            var next=phase==ChoicePhase.WITHDRAW?ChoicePhase.CRAFT:phase==ChoicePhase.CRAFT?ChoicePhase.DEPOSIT:null;
            var continuation=next==null?null:new Context(c.token,c.choice,c.batch,c.request,c.run,c.authority,c.recipe,
                    fingerprint(v.getInventory()),fingerprint(from),c.sourceIdentity,c.destinationIdentity,next,c.nativeActor,c.nativeSource,c.nativeDestination);
            return new Result(continuation,observation(c,phase,ChoiceObservationKind.APPLIED,before,after,null,"Actual native before/after slots; participant saves not certified"));
        } catch(Refused denied) {
            if(denied.reason==Reason.AUTHORITY_DENIED)return new Result(null,observation(c,phase,
                    writesStarted?ChoiceObservationKind.EXCEPTION_UNCERTAIN:ChoiceObservationKind.PRECHECK_NO_EFFECT,
                    new State(Map.of()),new State(Map.of()),denied.reason,"Authority refused; current participant contents withheld"));
            if(writesStarted)return new Result(null,observation(c,phase,ChoiceObservationKind.EXCEPTION_UNCERTAIN,before,state(c),denied.reason,"Refusal after a participant write; no replay permission"));
            if(before.participants().isEmpty())return new Result(null,observation(c,phase,ChoiceObservationKind.PRECHECK_NO_EFFECT,before,before,
                    denied.reason,"Refused before participant observation and the first host write"));
            State after=state(c);var kind=before.equals(after)?ChoiceObservationKind.PRECHECK_NO_EFFECT:ChoiceObservationKind.PARTIAL_UNCERTAIN;
            return new Result(null,observation(c,phase,kind,before,after,denied.reason,"All checks preceded the first host write; changed external state is not settled"));
        } catch(RuntimeException failure) {
            return new Result(null,observation(c,phase,ChoiceObservationKind.EXCEPTION_UNCERTAIN,before,
                    before.participants().isEmpty()?before:state(c),Reason.STORAGE_UNAVAILABLE,"Native exception; no absence-of-effect or save claim"));
        }
    }
}
