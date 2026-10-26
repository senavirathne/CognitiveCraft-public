package dev.aivillages.fabric;

import com.google.gson.Gson;
import com.mojang.serialization.JsonOps;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.production.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.RecipeTransaction.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.util.InclusiveRange;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;

/** Real static native hosts. Explicit test authority, no connected player, Job, inference or recovery. */
public final class GeneralizedRecipeChoiceGameTests {
    private static final Gson JSON=new Gson();
    private static void evidence(String name,Object value){try{var root=Path.of(System.getProperty("cognitivecraft.parallelB.evidence"));Files.createDirectories(root);Files.writeString(root.resolve(name+".json"),JSON.toJson(value));}catch(java.io.IOException e){throw new IllegalStateException(e);}}
    @GameTest(maxTicks=2400,padding=16)
    public void loadedAlternativesMixedGridsFacilitiesObserversAndReload(GameTestHelper h) {
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)h.setBlock(x,0,z,Blocks.STONE);
        h.setBlock(2,1,2,Blocks.CHEST);h.setBlock(2,1,4,Blocks.CHEST);h.setBlock(1,1,3,Blocks.CHEST);h.setBlock(3,1,3,Blocks.CRAFTING_TABLE);
        var v=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,2,1,3);v.setNoAi(false);BootstrapGameTests.forceFixtureChunks(h,h.absolutePos(BlockPos.ZERO));
        String dim=h.getLevel().dimension().identifier().toString();var a=h.absolutePos(new BlockPos(2,1,2));var b=h.absolutePos(new BlockPos(2,1,4));var tablePos=h.absolutePos(new BlockPos(3,1,3));
        var source=new ContainerRef(dim,a.getX(),a.getY(),a.getZ());var destination=new ContainerRef(dim,b.getX(),b.getY(),b.getZ());var table=new FacilityRef(dim,tablePos.getX(),tablePos.getY(),tablePos.getZ());
        var extraPos=h.absolutePos(new BlockPos(1,1,3));var secondary=new ContainerRef(dim,extraPos.getX(),extraPos.getY(),extraPos.getZ());var allowSecondary=new AtomicBoolean(true);
        var actor=new ActorRef(UUID.randomUUID(),v.getUUID(),dim);UUID principal=UUID.randomUUID(),run=UUID.randomUUID();
        var owner=new TrustedContext(new PrincipalRef(principal),new ScopeRef(UUID.randomUUID(),principal));var world=new FabricGatewayWorld(h.getLevel());var host=new FabricRecipeTransactions(h.getLevel(),world);var allow=new AtomicBoolean(true);
        ChoiceAuthority authority=new ChoiceAuthority(){public boolean permits(TrustedContext c,ActorRef subject,ContainerRef target,ChoicePhase p){return allow.get() && c.equals(owner) && subject.equals(actor) && (target.equals(source)||target.equals(destination)||target.equals(secondary) && allowSecondary.get());}
            public boolean permitsFacility(TrustedContext c,ActorRef subject,FacilityRef target,ChoicePhase p){return allow.get() && c.equals(owner) && subject.equals(actor) && target.equals(table);}};
        var reports=new ArrayList<Object>();var precheckReports=new ArrayList<Object>();int[] phase={0};CompletableFuture<?>[] reload={null};List<String>[] originalPacks=new List[]{null};
        RecipeChoice[] savedChoice={null};ValidatedRequest[] savedRequest={null};FabricConcreteRecipeChoices.Context[] savedPreparation={null};
        class Fixture {
            Container from(){return (Container)h.getLevel().getBlockEntity(a);}Container to(){return (Container)h.getLevel().getBlockEntity(b);}
            Container extra(){return (Container)h.getLevel().getBlockEntity(extraPos);}
            void setup(List<ItemStack> input){from().clearContent();to().clearContent();v.getInventory().clearContent();from().setItem(0,new ItemStack(Items.COBBLESTONE,7));int[] slots={2,7,12,18,26};for(int i=0;i<input.size();i++)from().setItem(slots[i],input.get(i).copy());allow.set(true);}
            ValidatedRequest request(CapabilityId legacy,int amount,boolean versionTwo){var args=new HashMap<String,Value>();args.put("actor",new ActorValue(actor));args.put("source",new ContainerValue(source));args.put("destination",new ContainerValue(destination));args.put("amount",new IntValue(amount));if(legacy.equals(WoodenAxeProduction.ID))args.put("table",new FacilityValue(table));
                return new ValidatedRequest(new CapabilityRequest(new CapabilityId(legacy.name(),versionTwo?2:1),args),owner,new ObservationRef(UUID.randomUUID(),h.getLevel().getGameTime(),dim));}
            List<ObservedSlot> slots(Container c){var rows=new ArrayList<ObservedSlot>();for(int i=0;i<c.getContainerSize();i++)rows.add(new ObservedSlot(i,FabricConcreteRecipeChoices.itemKey(h.getLevel(),c.getItem(i)),c.getItem(i).getCount()));return List.copyOf(rows);}
            Object snapshot(){return Map.of("source",slots(from()),"secondary",slots(extra()),"destination",slots(to()),"actor",slots(v.getInventory()),"facilityBlock",h.getLevel().getBlockState(tablePos).toString());}
            void refuses(Runnable r,String reason){var before=snapshot();boolean refused=false;try{r.run();}catch(RuntimeException e){refused=e.getMessage()!=null && e.getMessage().contains(reason);}var after=snapshot();h.assertTrue(refused && before.equals(after),"Expected native no-effect refusal "+reason);precheckReports.add(Map.of("expectedReason",reason,"before",before,"after",after,"nativeHostOnly",true));}
            List<ChoiceObservation> execute(ValidatedRequest request,RecipeChoice choice){var rows=new ArrayList<ChoiceObservation>();for(int batch=0;batch<choice.batches().size();batch++){var c=host.prepareChoice(request,choice,batch,run,authority);
                for(var op:List.of(ChoicePhase.WITHDRAW,ChoicePhase.CRAFT,ChoicePhase.DEPOSIT)){var result=host.executeChoice(c,op);var observation=result.observation();h.assertTrue(observation.kind()==ChoiceObservationKind.APPLIED && observation.choiceDigest().equals(choice.digest()) && !observation.saveConfirmed(),"Native choice "+op+" failed: "+JSON.toJson(observation));rows.add(observation);c=result.continuation();}}
                return List.copyOf(rows);}
            void positive(CapabilityId id,int amount,List<ItemStack> stock,String label){setup(stock);var request=request(id,amount,true);var choice=host.selectChoice(request,run,authority);var copy=RecipeChoice.fromCanonical(choice.canonical());h.assertTrue(copy.equals(choice),"Native canonical choice changed");
                var plan=ResourceComposition.proposeChoice(UUID.randomUUID(),3,request,copy);h.assertTrue(plan.stock().stream().mapToLong(RecipeChoicePlan.StockProposal::units).sum()==stock.stream().mapToInt(ItemStack::getCount).sum(),"Selected grid input proposal differs");
                var observations=execute(request,choice);var d=RecipeProduction.of(id);var output=FabricResourceItems.item(d.output());int physical=Math.toIntExact(choice.quantities().physicalOutput());
                h.assertTrue(to().countItem(output)==amount && v.getInventory().countItem(output)==physical-amount && from().countItem(Items.COBBLESTONE)==7,"Native result/delivery/surplus mismatch");
                for(var item:stock)h.assertTrue(from().countItem(item.getItem())==0,"Bound native input remains after expected consumption");
                var row=Map.of("nativeHostOnly",true,"authoritativeJobs",false,"label",label,"choice",choice,"choiceCanonical",choice.canonical(),"choiceDigest",choice.digest(),"plan",plan,"observations",observations,
                        "actual",Map.of("consumed",stock.stream().mapToInt(ItemStack::getCount).sum(),"produced",physical,"delivered",amount,"surplus",physical-amount));reports.add(row);evidence(label,row);}
            void uniform(CapabilityId id,int q,Item plank,String label){var d=RecipeProduction.of(id);int batches=d.batches(q);var stock=new ArrayList<ItemStack>();stock.add(new ItemStack(plank,batches*d.inputPerBatch()));if(d.axe())stock.add(new ItemStack(Items.STICK,2*batches));positive(id,q,stock,label);}
            void multipleSources(){setup(List.of(new ItemStack(Items.BAMBOO_PLANKS)));extra().clearContent();extra().setItem(26,new ItemStack(Items.ACACIA_PLANKS));var sources=new RecipeSources(List.of(secondary,source));var request=request(StickProduction.ID,1,true);
                refuses(()->host.selectChoice(request,run,authority),"RESOURCE_MISSING");var choice=host.selectChoice(request,run,authority,sources);var plan=ResourceComposition.proposeChoice(UUID.randomUUID(),4,request,choice,sources);
                h.assertTrue(plan.stock().stream().map(RecipeChoicePlan.StockProposal::source).distinct().count()==2,"Selected mixed sources collapsed into one stock key");var initialChoice=choice;refuses(()->host.prepareChoice(request,initialChoice,0,run,authority),"AUTHORITY_DENIED");
                var before=snapshot();var context=host.prepareChoice(request,choice,0,run,authority,sources);var observations=new ArrayList<ChoiceObservation>();
                for(var op:List.of(ChoicePhase.WITHDRAW,ChoicePhase.CRAFT,ChoicePhase.DEPOSIT)){var result=host.executeChoice(context,op);h.assertTrue(result.observation().kind()==ChoiceObservationKind.APPLIED && result.observation().choiceDigest().equals(choice.digest()),"Mixed source native operation refused");observations.add(result.observation());context=result.continuation();}
                h.assertTrue(from().countItem(Items.BAMBOO_PLANKS)==0 && extra().countItem(Items.ACACIA_PLANKS)==0 && to().countItem(Items.STICK)==1 && v.getInventory().countItem(Items.STICK)==3,"Mixed source physical conservation failed");
                evidence("mixed-source-containers",Map.of("nativeHostOnly",true,"authoritativeJobs",false,"sources",sources,"choiceCanonical",choice.canonical(),"choiceDigest",choice.digest(),"plan",plan,"before",before,"after",snapshot(),"observations",observations));
                setup(List.of(new ItemStack(Items.BAMBOO_PLANKS)));extra().setItem(26,new ItemStack(Items.ACACIA_PLANKS));choice=host.selectChoice(request,run,authority,sources);var prepared=host.prepareChoice(request,choice,0,run,authority,sources);allowSecondary.set(false);var denied=host.executeChoice(prepared,ChoicePhase.WITHDRAW);allowSecondary.set(true);
                h.assertTrue(denied.observation().reason()==Reason.AUTHORITY_DENIED && denied.observation().participants().isEmpty() && from().countItem(Items.BAMBOO_PLANKS)==1 && extra().countItem(Items.ACACIA_PLANKS)==1,"Revoked second source wrote or disclosed state");
                prepared=host.prepareChoice(request,choice,0,run,authority,sources);h.getLevel().setBlock(extraPos,Blocks.AIR.defaultBlockState(),3);h.getLevel().setBlock(extraPos,Blocks.CHEST.defaultBlockState(),3);extra().setItem(26,new ItemStack(Items.ACACIA_PLANKS));var replaced=host.executeChoice(prepared,ChoicePhase.WITHDRAW);
                h.assertTrue(replaced.observation().reason()==Reason.STALE_OBSERVATION && from().countItem(Items.BAMBOO_PLANKS)==1 && extra().countItem(Items.ACACIA_PLANKS)==1,"Second source replacement consumed input");evidence("mixed-source-negatives",Map.of("revoked",denied.observation(),"replaced",replaced.observation(),"actual",snapshot()));extra().clearContent();}
            void legacyObservers(){var rows=new ArrayList<HostObservation>();
                for(int mode=0;mode<3;mode++){setup(List.of(new ItemStack(Items.OAK_PLANKS,2)));var request=request(StickProduction.ID,3,false);var captured=host.preflight(request);h.assertTrue(captured.reason()==null,"Legacy observer preflight refused");
                    HostObserver sink=mode==0?HostObserver.NONE:mode==1?rows::add:observation->{throw new IllegalStateException("Deliberately failing optional listener");};
                    var withdrawn=host.withdraw(actor,source,captured,captured.custodyFingerprint(),captured.sourceFingerprint(),sink);var crafted=host.craft(actor,captured,withdrawn.custodyAfter(),sink);var deposited=host.deposit(actor,destination,captured,crafted.custodyAfter(),3,sink);
                    h.assertTrue(withdrawn.reason()==null && crafted.reason()==null && deposited.reason()==null && to().countItem(Items.STICK)==3 && v.getInventory().countItem(Items.STICK)==1,"Optional listener changed legacy effect");}
                h.assertTrue(rows.size()==3 && rows.stream().allMatch(o->o.kind()==ChoiceObservationKind.APPLIED && !o.saveConfirmed()) && rows.get(1).reportedDeltas().get("produced")==4,"Legacy actual observation missing");
                var placement=new FabricTablePlacement(h.getLevel(),world);var targetPos=h.absolutePos(new BlockPos(4,1,3));var target=new FacilityRef(dim,targetPos.getX(),targetPos.getY(),targetPos.getZ());var placements=new ArrayList<HostObservation>();
                setup(List.of(new ItemStack(Items.CRAFTING_TABLE)));var occludedRequest=new ValidatedRequest(new CapabilityRequest(CraftingTablePlacement.ID,Map.of("actor",new ActorValue(actor),"source",new ContainerValue(source),"target",new FacilityValue(target),"amount",new IntValue(1))),owner,new ObservationRef(UUID.randomUUID(),0,dim));
                h.assertTrue(!world.recipeReach(v,targetPos),"Expected the existing axe table to occlude the placement center ray");var occluded=placement.preflight(occludedRequest);var held=placement.withdraw(actor,occluded,occluded.custodyFingerprint(),occluded.sourceFingerprint());var refused=placement.place(actor,occluded,held.custodyAfter(),held.sourceAfter());
                h.assertTrue(refused.reason()==Reason.TARGET_UNAVAILABLE && !refused.placed() && v.getInventory().countItem(Items.CRAFTING_TABLE)==1 && h.getLevel().getBlockState(targetPos).isAir(),"Occluded placement changed physical state");evidence("occluded-placement-control",Map.of("nativeHostOnly",true,"target",target,"occluder",table,"actorPosition",v.position().toString(),"reach",false,"withdrawn",held,"placement",refused,"after",snapshot()));
                h.getLevel().setBlock(tablePos,Blocks.AIR.defaultBlockState(),3);h.assertTrue(world.recipeReach(v,targetPos),"Removing only the occluder did not restore actual native reach");
                for(int mode=0;mode<2;mode++){h.getLevel().setBlock(targetPos,Blocks.AIR.defaultBlockState(),3);setup(List.of(new ItemStack(Items.CRAFTING_TABLE)));var request=new ValidatedRequest(new CapabilityRequest(CraftingTablePlacement.ID,Map.of("actor",new ActorValue(actor),"source",new ContainerValue(source),"target",new FacilityValue(target),"amount",new IntValue(1))),owner,new ObservationRef(UUID.randomUUID(),0,dim));
                    var captured=placement.preflight(request);h.assertTrue(captured.reason()==null && !captured.pending(),"Legacy placement preflight refused");HostObserver sink=mode==0?HostObserver.NONE:placements::add;
                    var withdrawn=placement.withdraw(actor,captured,captured.custodyFingerprint(),captured.sourceFingerprint(),sink);var placed=placement.place(actor,captured,withdrawn.custodyAfter(),withdrawn.sourceAfter(),sink);
                    evidence("legacy-placement-mode-"+mode,Map.of("nativeHostOnly",true,"mode",mode,"captured",captured,"withdrawn",withdrawn,"placed",placed,"after",snapshot(),"target",target,"actorPosition",v.position().toString()));
                    h.assertTrue(placed.reason()==null && placed.placed() && placed.consumed()==1 && from().countItem(Items.CRAFTING_TABLE)==0 && v.getInventory().countItem(Items.CRAFTING_TABLE)==0,"Optional placement observer changed effect");}
                h.assertTrue(placements.size()==2 && !placements.get(1).worldBefore().equals(placements.get(1).worldAfter()),"Placement world before/after observation missing");evidence("legacy-observers",Map.of("nativeHostOnly",true,"recipe",rows,"placement",placements,"throwingListenerNativeEffectRetained",true));}
        }
        var f=new Fixture();
        h.succeedWhen(()->{
            if(!v.onGround() || !world.loadedForDiscovery(a)){h.assertTrue(false,"Waiting for actual grounded native fixture");return;}
            if(phase[0]==0)try{
                h.assertTrue(world.acquire(actor,run),"Native gateway control unavailable");
                for(var material:new TreeSet<>(FabricConcreteRecipeChoices.PLANK_MATERIALS)){var item=BuiltInRegistries.ITEM.getValue(Identifier.parse("minecraft:"+material+"_planks"));h.assertTrue(item!=Items.AIR,"Declared plank alternative is absent");
                    f.uniform(StickProduction.ID,1,item,"sticks-"+material+"-q1");f.uniform(CraftingTableProduction.ID,1,item,"table-"+material+"-q1");f.uniform(WoodenAxeProduction.ID,1,item,"axe-"+material+"-q1");}
                f.uniform(StickProduction.ID,7,Items.ACACIA_PLANKS,"original-acacia-sticks-q7");f.uniform(StickProduction.ID,1,Items.ACACIA_PLANKS,"minimized-acacia-sticks-q1");
                f.positive(StickProduction.ID,1,List.of(new ItemStack(Items.BAMBOO_PLANKS),new ItemStack(Items.ACACIA_PLANKS)),"original-mixed-bamboo-acacia-q1");
                f.uniform(StickProduction.ID,7,Items.OAK_PLANKS,"canonical-oak-sticks-q7");f.uniform(StickProduction.ID,1,Items.OAK_PLANKS,"canonical-oak-sticks-q1");
                for(int q:new int[]{3,4,5,64})f.uniform(StickProduction.ID,q,Items.ACACIA_PLANKS,"acacia-boundary-q"+q);
                f.positive(CraftingTableProduction.ID,1,List.of(new ItemStack(Items.OAK_PLANKS),new ItemStack(Items.BAMBOO_PLANKS),new ItemStack(Items.ACACIA_PLANKS),new ItemStack(Items.BIRCH_PLANKS)),"mixed-table-four-slots");
                f.positive(WoodenAxeProduction.ID,1,List.of(new ItemStack(Items.BAMBOO_PLANKS),new ItemStack(Items.ACACIA_PLANKS),new ItemStack(Items.BIRCH_PLANKS),new ItemStack(Items.STICK),new ItemStack(Items.STICK)),"mixed-axe-five-slots");
                f.uniform(CraftingTableProduction.ID,4,Items.ACACIA_PLANKS,"table-maximum-q4");f.uniform(WoodenAxeProduction.ID,4,Items.ACACIA_PLANKS,"axe-maximum-q4");
                f.setup(List.of(new ItemStack(Items.OAK_PLANKS,3),new ItemStack(Items.STICK,2)));var request=f.request(WoodenAxeProduction.ID,1,true);var choice=host.selectChoice(request,run,authority);var prepared=host.prepareChoice(request,choice,0,run,authority);
                h.getLevel().setBlock(tablePos,Blocks.AIR.defaultBlockState(),3);var missing=host.executeChoice(prepared,ChoicePhase.WITHDRAW);h.assertTrue(missing.observation().reason()==Reason.FACILITY_MISSING && f.from().countItem(Items.OAK_PLANKS)==3,"Removed table consumed input");
                h.getLevel().setBlock(tablePos,Blocks.CRAFTING_TABLE.defaultBlockState(),3);f.refuses(()->host.prepareChoice(request,choice,0,run,authority),"STALE_OBSERVATION");
                var fresh=host.selectChoice(request,run,authority);var deniedFacility=host.prepareChoice(request,fresh,0,run,authority);allow.set(false);var denied=host.executeChoice(deniedFacility,ChoicePhase.WITHDRAW);allow.set(true);h.assertTrue(denied.observation().reason()==Reason.AUTHORITY_DENIED && denied.observation().participants().isEmpty(),"Revoked facility authority leaked or wrote");
                f.setup(List.of(new ItemStack(Items.OAK_PLANKS,2)));var sticks=f.request(StickProduction.ID,1,true);var selected=host.selectChoice(sticks,run,authority);
                var remainder=new RecipeChoice(selected.capability(),selected.recipe(),selected.semanticFingerprint(),selected.runtimeCompatibility(),selected.reloadEpoch(),2,2,selected.batches(),selected.result(),4,1,List.of(new RecipeChoice.ItemAmount(selected.result(),1)));
                f.refuses(()->host.prepareChoice(sticks,remainder,0,run,authority),"UNSUPPORTED_PRIMITIVE");
                var wrongGrid=new ArrayList<>(selected.batch(0).grid());Collections.swap(wrongGrid,1,2);var bindings=List.of(new RecipeChoice.Binding(0,source,2,2,wrongGrid.get(0)),new RecipeChoice.Binding(1,source,2,1,wrongGrid.get(1)));
                var invalid=new RecipeChoice(selected.capability(),selected.recipe(),selected.semanticFingerprint(),selected.runtimeCompatibility(),selected.reloadEpoch(),2,2,List.of(new RecipeChoice.Batch(wrongGrid,bindings)),selected.result(),4,1,List.of());f.refuses(()->host.prepareChoice(sticks,invalid,0,run,authority),"TARGET_INVALID");
                for(int i=0;i<f.to().getContainerSize();i++)f.to().setItem(i,new ItemStack(Items.STONE,64));f.refuses(()->host.selectChoice(sticks,run,authority),"TARGET_UNAVAILABLE");f.to().clearContent();
                var rules=h.getLevel().getGameRules();boolean old=rules.get(GameRules.MOB_GRIEFING);try{rules.set(GameRules.MOB_GRIEFING,false,h.getLevel().getServer());f.refuses(()->host.prepareChoice(sticks,selected,0,run,authority),"AUTHORITY_DENIED");}finally{rules.set(GameRules.MOB_GRIEFING,old,h.getLevel().getServer());}
                var componentPreparation=host.prepareChoice(sticks,selected,0,run,authority);var named=new ItemStack(Items.OAK_PLANKS,2);named.set(DataComponents.CUSTOM_NAME,Component.literal("Changed native components"));f.from().setItem(2,named);var components=host.executeChoice(componentPreparation,ChoicePhase.WITHDRAW);h.assertTrue(components.observation().reason()==Reason.STALE_OBSERVATION && v.getInventory().isEmpty(),"Changed components consumed");
                evidence("guard-negatives",Map.of("nativeHostOnly",true,"missingFacility",missing.observation(),"revokedAuthority",denied.observation(),"changedComponents",components.observation(),"prechecks",precheckReports));
                f.multipleSources();f.legacyObservers();f.setup(List.of(new ItemStack(Items.ACACIA_PLANKS,2)));savedRequest[0]=sticks;savedChoice[0]=host.selectChoice(sticks,run,authority);savedPreparation[0]=host.prepareChoice(sticks,savedChoice[0],0,run,authority);
                originalPacks[0]=List.copyOf(h.getLevel().getServer().getPackRepository().getSelectedIds());reload[0]=h.getLevel().getServer().reloadResources(originalPacks[0]);phase[0]=1;
            }catch(RuntimeException firstFailure){evidence("first-failure",Map.of("nativeHostOnly",true,"phase",phase[0],"failure",firstFailure.toString(),"physicalPositiveCases",reports.size()));throw new IllegalStateException("B04 first execution failed; no fixture retry",firstFailure);}
            if(phase[0]==1){if(!reload[0].isDone()){h.assertTrue(false,"Waiting for real static recipe reload");return;}reload[0].join();var stale=host.executeChoice(savedPreparation[0],ChoicePhase.WITHDRAW);h.assertTrue(stale.observation().reason()==Reason.ARTIFACT_INCOMPATIBLE,"Reload retained old preparation");
                var fresh=host.prepareChoice(savedRequest[0],savedChoice[0],0,run,authority);savedPreparation[0]=fresh;
                try{var server=h.getLevel().getServer();var pack=server.getWorldPath(LevelResource.DATAPACK_DIR).resolve("cc-b04-semantic");Files.createDirectories(pack.resolve("data/minecraft/recipe"));
                    var version=net.minecraft.SharedConstants.getCurrentVersion().packVersion(net.minecraft.server.packs.PackType.SERVER_DATA);var metadata=new PackMetadataSection(Component.literal("B04 isolated semantic fixture"),new InclusiveRange<>(version,version));var wrapper=new com.google.gson.JsonObject();wrapper.add("pack",PackMetadataSection.SERVER_TYPE.codec().encodeStart(JsonOps.INSTANCE,metadata).getOrThrow());Files.writeString(pack.resolve("pack.mcmeta"),wrapper.toString());
                    var recipe=server.getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE,Identifier.parse(StickProduction.RECIPE))).orElseThrow().value();var encoded=Recipe.DIRECT_CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE,h.getLevel().registryAccess()),recipe).getOrThrow().getAsJsonObject();encoded.getAsJsonObject("result").addProperty("count",3);Files.writeString(pack.resolve("data/minecraft/recipe/stick.json"),encoded.toString());
                    server.getPackRepository().reload();var packs=new ArrayList<>(originalPacks[0]);packs.add("file/cc-b04-semantic");reload[0]=server.reloadResources(packs);phase[0]=2;
                }catch(java.io.IOException e){throw new IllegalStateException(e);}evidence("unchanged-reload",Map.of("nativeHostOnly",true,"stale",stale.observation(),"originalChoiceDigest",savedChoice[0].digest(),"freshPreparationToken",fresh.token));
            }
            if(phase[0]==2){if(!reload[0].isDone()){h.assertTrue(false,"Waiting for real same-ID shaped recipe change");return;}reload[0].join();var changed=host.executeChoice(savedPreparation[0],ChoicePhase.WITHDRAW);h.assertTrue(changed.observation().reason()==Reason.ARTIFACT_INCOMPATIBLE && f.from().countItem(Items.ACACIA_PLANKS)==2,"Changed recipe consumed input");f.refuses(()->host.selectChoice(savedRequest[0],run,authority),"UNSUPPORTED_PRIMITIVE");evidence("semantic-reload",Map.of("nativeHostOnly",true,"refused",changed.observation(),"actualRemainingInputs",2));reload[0]=h.getLevel().getServer().reloadResources(originalPacks[0]);phase[0]=3;}
            if(phase[0]==3){if(!reload[0].isDone()){h.assertTrue(false,"Waiting for original native recipe restore");return;}reload[0].join();var restored=f.execute(savedRequest[0],savedChoice[0]);evidence("summary",Map.of("nativeHostOnly",true,"authoritativeJobs",false,"positiveCases",reports.size(),"permittedPlankMaterials",new TreeSet<>(FabricConcreteRecipeChoices.PLANK_MATERIALS),"restoredObservations",restored,"sameChoiceDigest",savedChoice[0].digest(),"autonomousGameplay",false,"saveConfirmed",false));world.release(actor,run);phase[0]=4;System.out.println("B04_HOST_COMPLETE physicalCases="+reports.size()+" mixed=true actualFacilities=true observers=true semanticReload=true");}
        });
    }
}
