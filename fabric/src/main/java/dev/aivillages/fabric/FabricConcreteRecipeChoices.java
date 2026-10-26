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
import net.minecraft.world.level.block.Blocks;

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
        final ChoiceAuthority authority;final View recipe;final String custody;final String destinationIdentity;final ChoicePhase next;
        final RecipeSources sources;final Map<ContainerRef,String> sourceFingerprints;
        final Villager nativeActor;final Map<ContainerRef,Container> nativeSources;final Container nativeDestination;
        final AtomicBoolean used=new AtomicBoolean();
        Context(String token,RecipeChoice choice,int batch,ValidatedRequest request,UUID run,ChoiceAuthority authority,
                View recipe,String custody,RecipeSources sources,Map<ContainerRef,String> sourceFingerprints,String destinationIdentity,ChoicePhase next,
                Villager nativeActor,Map<ContainerRef,Container> nativeSources,Container nativeDestination) {
            this.token=token;this.choice=choice;this.batch=batch;this.request=request;this.run=run;this.authority=authority;
            this.recipe=recipe;this.custody=custody;this.sources=sources;this.sourceFingerprints=Map.copyOf(sourceFingerprints);this.destinationIdentity=destinationIdentity;this.next=next;
            this.nativeActor=nativeActor;this.nativeSources=Map.copyOf(nativeSources);this.nativeDestination=nativeDestination;
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
        boolean planks=d.output().id().endsWith("_planks");
        var manager=level.getServer().getRecipeManager();
        var holder=manager.byKey(ResourceKey.create(Registries.RECIPE,Identifier.parse(d.recipe())));
        if(holder.isEmpty() || holder.get().value().getClass()!=(planks?ShapelessRecipe.class:ShapedRecipe.class))throw refuse(Reason.UNSUPPORTED_PRIMITIVE);
        var recipe=(CraftingRecipe)holder.get().value();var placement=recipe.placementInfo();
        if(recipe.isSpecial() || placement.isImpossibleToPlace() || placement.ingredients().isEmpty() || placement.ingredients().size()>d.totalInputs()
                || placement.slotsToIngredientIndex().stream().filter(i->i!=PlacementInfo.EMPTY_SLOT).count()!=d.totalInputs())
            throw refuse(Reason.UNSUPPORTED_PRIMITIVE);
        var expanded=new ArrayList<List<String>>();
        for(var ingredient:placement.ingredients()) {
            var options=ingredient.items().limit(33).toList();
            if(options.isEmpty() || options.size()>32 || options.stream().anyMatch(i->!permittedInput(d,new ItemStack(i))))throw refuse(Reason.UNSUPPORTED_PRIMITIVE);
            expanded.add(options.stream().map(i->i.unwrapKey().orElseThrow().identifier().toString()).sorted().toList());
        }
        if(recipe instanceof ShapedRecipe shaped && (shaped.getWidth()!=(d.axe() || d.table()?2:1) || shaped.getHeight()!=(d.axe()?3:2)))throw refuse(Reason.UNSUPPORTED_PRIMITIVE);
        var ops=RegistryOps.create(JsonOps.INSTANCE,level.registryAccess());
        var encoded=net.minecraft.world.item.crafting.Recipe.DIRECT_CODEC.encodeStart(ops,recipe).getOrThrow();
        String semantic=RecipeTransaction.fingerprint(StrictJson.canonical(Map.of("compatibility",RecipeChoice.RUNTIME,
                "recipe",d.recipe(),"class",recipe.getClass().getName(),"json",RecipeChoice.canonicalComponents(encoded.toString()),
                "expandedIngredients",planks?expanded.getFirst():expanded,"context",d.axe()?"table-3x3":"inventory-2x2")));
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
    static final Set<String> PLANK_MATERIALS=Set.of("oak","spruce","birch","jungle","acacia","dark_oak","mangrove","cherry","bamboo","crimson","warped","pale_oak","poplar");
    private boolean permittedInput(RecipeProduction.Definition definition,ItemStack value) {
        if(!FabricRecipeTransactions.plain(value,value.getItem()))return false;
        String id=BuiltInRegistries.ITEM.getKey(value.getItem()).toString();
        if(!definition.output().id().endsWith("_planks"))return definition.axe() && value.is(Items.STICK)
                || PLANK_MATERIALS.stream().anyMatch(material->id.equals("minecraft:"+material+"_planks"));
        String family=definition.output().id().replace("_planks","");
        String namespace=family.substring(0,family.indexOf(':')+1),material=family.substring(family.indexOf(':')+1);
        return Set.of(family+"_log",family+"_wood",namespace+"stripped_"+material+"_log",namespace+"stripped_"+material+"_wood").contains(id);
    }
    private record Cell(int gridSlot,Ingredient ingredient){}
    private List<Cell> cells(View view,RecipeProduction.Definition definition) {
        if(view.recipe() instanceof ShapelessRecipe)return List.of(new Cell(0,view.recipe().placementInfo().ingredients().getFirst()));
        var shaped=(ShapedRecipe)view.recipe();var rows=new ArrayList<Cell>();int width=definition.axe()?3:2;
        var ingredients=shaped.getIngredients();
        for(int i=0;i<ingredients.size();i++)if(ingredients.get(i).isPresent())rows.add(new Cell(i/shaped.getWidth()*width+i%shaped.getWidth(),ingredients.get(i).orElseThrow()));
        if(rows.size()!=definition.totalInputs())throw refuse(Reason.UNSUPPORTED_PRIMITIVE);return List.copyOf(rows);
    }
    private CraftingInput input(RecipeChoice choice,int index) {
        return CraftingInput.of(choice.width(),choice.height(),choice.batch(index).grid().stream().map(this::stack).toList());
    }
    private void nativeMatch(RecipeChoice choice,int index,View view) {
        if(!choice.semanticFingerprint().equals(view.semantic()) || !RecipeChoice.RUNTIME.equals(choice.runtimeCompatibility()))throw refuse(Reason.ARTIFACT_INCOMPATIBLE);
        var d=RecipeProduction.choiceDefinition(choice.capability());var grid=input(choice,index);int size=d.axe()?3:2;
        if(choice.width()!=size || choice.height()!=size || !choice.remainders().isEmpty())throw refuse(Reason.UNSUPPORTED_PRIMITIVE);
        if(choice.batch(index).grid().stream().filter(i->!i.empty()).count()!=d.totalInputs()
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
        if(v==null || !world.discoverable(actor(request),pos,phase==ChoicePhase.DISCOVER?Effect.OBSERVE:Effect.CRAFT))throw refuse(Reason.AUTHORITY_DENIED);
        if(reach && !world.recipeReach(v,pos))throw refuse(Reason.TARGET_UNAVAILABLE);
        if(box.getContainerSize()>128)throw refuse(Reason.UNSUPPORTED_PRIMITIVE);return box;
    }
    private RecipeChoice.FacilityBinding facility(ValidatedRequest request,ChoiceAuthority authority,ChoicePhase phase,
                                                 RecipeChoice.FacilityBinding expected,boolean reach) {
        if(!RecipeProduction.choiceDefinition(request.request().capability()).axe())return null;
        if(!(request.request().arguments().get("table") instanceof FacilityValue f))throw refuse(Reason.FACILITY_MISSING);
        var ref=f.value();var p=new BlockPos(ref.x(),ref.y(),ref.z());var v=world.recipeActor(actor(request));
        if(authority==null || !authority.permitsFacility(request.context(),actor(request),ref,phase))throw refuse(Reason.AUTHORITY_DENIED);
        if(!ref.dimension().equals(actor(request).dimension()) || !world.loadedForDiscovery(p) || !level.getBlockState(p).is(Blocks.CRAFTING_TABLE))throw refuse(Reason.FACILITY_MISSING);
        if(!world.discoverable(actor(request),p,Effect.CRAFT))throw refuse(Reason.AUTHORITY_DENIED);
        if(reach && !world.recipeReach(v,p))throw refuse(Reason.TARGET_UNAVAILABLE);
        String identity=FabricFacilityIdentity.capture(level,p);if(identity==null)throw refuse(Reason.BUDGET_EXHAUSTED);
        var actual=new RecipeChoice.FacilityBinding(ref,identity);
        if(expected!=null && !expected.equals(actual))throw refuse(Reason.STALE_OBSERVATION);return actual;
    }
    RecipeChoice select(ValidatedRequest request,UUID run,ChoiceAuthority authority) {
        return select(request,run,authority,RecipeSources.single(request));
    }
    private Map<ContainerRef,Container> sourceContainers(ValidatedRequest request,UUID run,ChoiceAuthority authority,RecipeSources sources,ChoicePhase phase,boolean reach) {
        sources.validate(request);var result=new LinkedHashMap<ContainerRef,Container>();
        for(var source:sources.containers())result.put(source,permitted(request,run,authority,source,phase,reach));return result;
    }
    private Map<ContainerRef,List<ItemStack>> copies(Map<ContainerRef,Container> boxes){var result=new HashMap<ContainerRef,List<ItemStack>>();boxes.forEach((ref,box)->result.put(ref,FabricRecipeTransactions.copy(box)));return result;}
    private Map<ContainerRef,String> fingerprints(Map<ContainerRef,Container> boxes){var result=new HashMap<ContainerRef,String>();boxes.forEach((ref,box)->result.put(ref,fingerprint(box)));return result;}
    RecipeChoice select(ValidatedRequest request,UUID run,ChoiceAuthority authority,RecipeSources sources) {
        thread();sources.validate(request);
        nativeActor(request,run);var cap=request.request().capability();var d=RecipeProduction.choiceDefinition(cap);var recipe=view(cap);
        if(!(request.request().arguments().get("amount") instanceof IntValue amount))throw refuse(Reason.REQUEST_INVALID);
        var quantities=d.quantities(amount.value(),BatchQuantities.Role.ROOT);
        var boxes=sourceContainers(request,run,authority,sources,ChoicePhase.DISCOVER,false);
        permitted(request,run,authority,container(request,"destination"),ChoicePhase.DISCOVER,false);
        var selectedFacility=facility(request,authority,ChoicePhase.DISCOVER,null,false);
        var available=copies(boxes);var batches=new ArrayList<RecipeChoice.Batch>();RecipeChoice.ItemKey resultKey=null;int width=d.axe()?3:2;
        for(int b=0;b<quantities.batches();b++) {
            var grid=new ArrayList<>(Collections.nCopies(width*width,RecipeChoice.ItemKey.EMPTY));var bindings=new ArrayList<RecipeChoice.Binding>();
            var nativeItems=new ArrayList<>(Collections.nCopies(width*width,ItemStack.EMPTY));
            for(var cell:cells(recipe,d)) {
                int chosen=-1;ContainerRef selectedSource=null;
                for(var source:sources.containers()){var slots=available.get(source);for(int slot=0;slot<slots.size();slot++){var value=slots.get(slot);if(!value.isEmpty() && permittedInput(d,value) && cell.ingredient().test(value)){chosen=slot;selectedSource=source;break;}}if(chosen>=0)break;}
                if(chosen<0)throw refuse(Reason.RESOURCE_MISSING);
                var slots=available.get(selectedSource);var actual=slots.get(chosen);var key=itemKey(level,actual);grid.set(cell.gridSlot(),key);nativeItems.set(cell.gridSlot(),actual.copyWithCount(1));
                bindings.add(new RecipeChoice.Binding(cell.gridSlot(),selectedSource,chosen,actual.getCount(),key));slots.set(chosen,actual.copyWithCount(actual.getCount()-1));
            }
            var nativeGrid=CraftingInput.of(width,width,nativeItems);if(!recipe.recipe().matches(nativeGrid,level))throw refuse(Reason.TARGET_INVALID);
            var result=recipe.recipe().assemble(nativeGrid);var keyResult=itemKey(level,result);
            if(result.getCount()!=d.outputPerBatch() || !result.is(FabricResourceItems.item(d.output()))
                    || !FabricRecipeTransactions.plain(result,result.getItem()) || recipe.recipe().getRemainingItems(nativeGrid).stream().anyMatch(s->!s.isEmpty())
                    || resultKey!=null && !resultKey.equals(keyResult))throw refuse(Reason.UNSUPPORTED_PRIMITIVE);
            resultKey=keyResult;batches.add(new RecipeChoice.Batch(grid,bindings));
        }
        var choice=new RecipeChoice(cap,d.recipe(),recipe.semantic(),RecipeChoice.RUNTIME,recipe.epoch(),width,width,batches,resultKey,d.outputPerBatch(),amount.value(),List.of(),selectedFacility);
        wholeChoiceCapacity(request,run,authority,choice,recipe,sources);return choice;
    }
    private void wholeChoiceCapacity(ValidatedRequest request,UUID run,ChoiceAuthority authority,RecipeChoice choice,View recipe,RecipeSources sources) {
        var v=nativeActor(request,run);var target=container(request,"destination");
        var boxes=sourceContainers(request,run,authority,sources,ChoicePhase.DISCOVER,false);
        var to=permitted(request,run,authority,target,ChoicePhase.DISCOVER,false);
        var inputCopies=copies(boxes);var destination=inputCopies.containsKey(target)?inputCopies.get(target):FabricRecipeTransactions.copy(to);
        var held=FabricRecipeTransactions.copy(v.getInventory());
        for(int batch=0;batch<choice.batches().size();batch++) {
            nativeMatch(choice,batch,recipe);
            for(var binding:choice.batch(batch).bindings()) {
                var inputs=inputCopies.get(binding.source());
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
        return prepare(request,choice,batch,run,authority,RecipeSources.single(request));
    }
    Context prepare(ValidatedRequest request,RecipeChoice choice,int batch,UUID run,ChoiceAuthority authority,RecipeSources sources) {
        thread();sources.validate(request);
        var v=nativeActor(request,run);checkRequest(request,choice);var recipe=view(choice.capability());nativeMatch(choice,batch,recipe);
        facility(request,authority,ChoicePhase.PREPARE,choice.facility(),false);
        var target=container(request,"destination");
        var boxes=sourceContainers(request,run,authority,sources,ChoicePhase.PREPARE,true);
        var to=permitted(request,run,authority,target,ChoicePhase.PREPARE,false);
        if(choice.batches().stream().flatMap(b->b.bindings().stream()).anyMatch(b->!sources.containers().contains(b.source())))throw refuse(Reason.AUTHORITY_DENIED);
        var sourceCopies=copies(boxes);var custody=FabricRecipeTransactions.copy(v.getInventory());
        for(var binding:choice.batch(batch).bindings()) {
            var sourceCopy=sourceCopies.get(binding.source());
            if(binding.sourceSlot()>=sourceCopy.size())throw refuse(Reason.STALE_OBSERVATION);
            var value=sourceCopy.get(binding.sourceSlot());
            if(value.getCount()!=binding.expectedCount() || !itemKey(level,value).equals(binding.item()))throw refuse(Reason.STALE_OBSERVATION);
            sourceCopy.set(binding.sourceSlot(),value.copyWithCount(value.getCount()-1));
            custody=FabricRecipeTransactions.insert(custody,v.getInventory(),value.copyWithCount(1));if(custody==null)throw refuse(Reason.TARGET_UNAVAILABLE);
        }
        var afterCraft=consumeHeld(custody,choice,batch);var output=recipe.recipe().assemble(input(choice,batch));
        if(FabricRecipeTransactions.insert(afterCraft,v.getInventory(),output.copy())==null)throw refuse(Reason.TARGET_UNAVAILABLE);
        var destinationCopy=sourceCopies.containsKey(target)?sourceCopies.get(target):FabricRecipeTransactions.copy(to);
        if(FabricRecipeTransactions.insert(destinationCopy,to,output.copyWithCount(Math.toIntExact(choice.deliveryForBatch(batch))))==null)throw refuse(Reason.TARGET_UNAVAILABLE);
        return new Context(UUID.randomUUID().toString(),choice,batch,request,run,authority,recipe,
                fingerprint(v.getInventory()),sources,fingerprints(boxes),
                FabricRecipeTransactions.identity(target,to),ChoicePhase.WITHDRAW,v,boxes,to);
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
        var refs=new LinkedHashSet<>(c.sources.containers());refs.add(container(c.request,"destination"));
        for(var ref:refs){var box=world.recipeContainer(ref);
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
            if(c.authority==null || c.sources.containers().stream().anyMatch(s->!c.authority.permits(c.request.context(),actor(c.request),s,phase))
                    || !c.authority.permits(c.request.context(),actor(c.request),container(c.request,"destination"),phase))
                throw refuse(Reason.AUTHORITY_DENIED);
            var v=nativeActor(c.request,c.run);checkRequest(c.request,c.choice);
            if(v!=c.nativeActor)throw refuse(Reason.STALE_OBSERVATION);
            before=state(c);
            var current=view(c.choice.capability());nativeMatch(c.choice,c.batch,current);
            facility(c.request,c.authority,phase,c.choice.facility(),phase==ChoicePhase.CRAFT);
            if(current.manager()!=c.recipe.manager() || current.recipe()!=c.recipe.recipe())throw refuse(Reason.ARTIFACT_INCOMPATIBLE);
            if(!fingerprint(v.getInventory()).equals(c.custody))throw refuse(Reason.STALE_OBSERVATION);
            var target=container(c.request,"destination");
            var boxes=sourceContainers(c.request,c.run,c.authority,c.sources,phase,phase==ChoicePhase.WITHDRAW);
            var to=permitted(c.request,c.run,c.authority,target,phase,phase==ChoicePhase.DEPOSIT);
            if(boxes.entrySet().stream().anyMatch(e->e.getValue()!=c.nativeSources.get(e.getKey())) || to!=c.nativeDestination
                    || !FabricRecipeTransactions.identity(target,to).equals(c.destinationIdentity))throw refuse(Reason.STALE_OBSERVATION);
            var custody=FabricRecipeTransactions.copy(v.getInventory());var sourceCopies=copies(boxes);
            var expected=new HashMap<>(before.participants());
            if(phase==ChoicePhase.WITHDRAW) {
                if(!fingerprints(boxes).equals(c.sourceFingerprints))throw refuse(Reason.STALE_OBSERVATION);
                for(var binding:c.choice.batch(c.batch).bindings()) {
                    var sourceCopy=sourceCopies.get(binding.source());
                    var value=sourceCopy.get(binding.sourceSlot());
                    if(value.getCount()!=binding.expectedCount() || !itemKey(level,value).equals(binding.item()))throw refuse(Reason.STALE_OBSERVATION);
                    custody=FabricRecipeTransactions.insert(custody,v.getInventory(),value.copyWithCount(1));if(custody==null)throw refuse(Reason.TARGET_UNAVAILABLE);
                    sourceCopy.set(binding.sourceSlot(),value.copyWithCount(value.getCount()-1));
                }
                if(!c.used.compareAndSet(false,true))throw refuse(Reason.STALE_OBSERVATION);
                for(var source:c.sources.containers())expected.put(participant(source,boxes.get(source)),observe(sourceCopies.get(source)));expected.put("actor:"+v.getUUID(),observe(custody));
                writesStarted=true;for(var source:c.sources.containers())FabricRecipeTransactions.apply(boxes.get(source),sourceCopies.get(source));FabricRecipeTransactions.apply(v.getInventory(),custody);
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
                    fingerprint(v.getInventory()),c.sources,fingerprints(boxes),c.destinationIdentity,next,c.nativeActor,c.nativeSources,c.nativeDestination);
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
