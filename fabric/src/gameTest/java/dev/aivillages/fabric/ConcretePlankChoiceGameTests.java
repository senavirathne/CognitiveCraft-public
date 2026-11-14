package dev.aivillages.fabric;

import com.google.gson.*;
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
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.util.InclusiveRange;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

/** Real native transactions with explicit test-scope authority, not Jobs/autonomous/E2E evidence. */
public final class ConcretePlankChoiceGameTests {
    private static final Gson JSON=new Gson();
    private static void evidence(String name,Object value) {
        try {var root=Path.of(System.getProperty("cognitivecraft.parallelB.evidence"));Files.createDirectories(root);Files.writeString(root.resolve(name+".json"),JSON.toJson(value));}
        catch(java.io.IOException failure){throw new IllegalStateException("Native B evidence write",failure);}
    }
    @GameTest(maxTicks=2000,padding=16)
    public void concreteGridsPhysicalSurplusAuthorityComponentsAndSemanticReload(GameTestHelper h) {
        for(int x=0;x<7;x++)for(int z=0;z<7;z++)h.setBlock(x,0,z,Blocks.STONE);
        h.setBlock(2,1,2,Blocks.CHEST);h.setBlock(2,1,4,Blocks.CHEST);
        var v=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,2,1,3);v.setNoAi(false);
        BootstrapGameTests.forceFixtureChunks(h,h.absolutePos(BlockPos.ZERO));
        String dim=h.getLevel().dimension().identifier().toString();var a=h.absolutePos(new BlockPos(2,1,2));var b=h.absolutePos(new BlockPos(2,1,4));
        var source=new ContainerRef(dim,a.getX(),a.getY(),a.getZ());var destination=new ContainerRef(dim,b.getX(),b.getY(),b.getZ());
        var actor=new ActorRef(UUID.randomUUID(),v.getUUID(),dim);UUID principal=UUID.randomUUID(),run=UUID.randomUUID();
        var owner=new TrustedContext(new PrincipalRef(principal),new ScopeRef(UUID.randomUUID(),principal));
        var world=new FabricGatewayWorld(h.getLevel());var host=new FabricRecipeTransactions(h.getLevel(),world);
        var allow=new AtomicBoolean(true);
        ChoiceAuthority authority=(caller,subject,target,phase)->allow.get() && caller.equals(owner) && subject.equals(actor)
                && (target.equals(source)||target.equals(destination));
        var reports=new ArrayList<Object>();int[] phase={0};CompletableFuture<?>[] reloaded={null};
        FabricConcreteRecipeChoices.Context[] saved={null};RecipeChoice[] savedChoice={null};ValidatedRequest[] savedRequest={null};
        List<String>[] originalPacks=new List[]{null};String[] originalSemantic={null};
        class Fixture {
            Container from(){return (Container)h.getLevel().getBlockEntity(a);}
            Container to(){return (Container)h.getLevel().getBlockEntity(b);}
            void setup(Item input,int count) {
                from().clearContent();to().clearContent();v.getInventory().clearContent();allow.set(true);
                from().setItem(26,new ItemStack(input,count));from().setItem(0,new ItemStack(Items.COBBLESTONE,2));
            }
            ValidatedRequest request(boolean birch,int amount) {
                var id=birch?BirchPlankProduction.ID:OakPlankProduction.ID;
                return new ValidatedRequest(new CapabilityRequest(new CapabilityId(id.name(),2),Map.of("actor",new ActorValue(actor),"amount",new IntValue(amount),
                        "source",new ContainerValue(source),"destination",new ContainerValue(destination))),owner,new ObservationRef(UUID.randomUUID(),h.getLevel().getGameTime(),dim));
            }
            void refused(Runnable action,String reason) {
                boolean rejected=false;try{action.run();}catch(RuntimeException failure){rejected=failure.getMessage()!=null && failure.getMessage().contains(reason);}
                h.assertTrue(rejected,"Native preparation must refuse with "+reason);
            }
            List<ChoiceObservation> execute(ValidatedRequest request,RecipeChoice choice) {
                var observations=new ArrayList<ChoiceObservation>();String digest=choice.digest();
                for(int index=0;index<choice.batches().size();index++) {
                    var context=host.prepareChoice(request,choice,index,run,authority);
                    for(var operation:List.of(ChoicePhase.WITHDRAW,ChoicePhase.CRAFT,ChoicePhase.DEPOSIT)) {
                        var result=host.executeChoice(context,operation);var observed=result.observation();
                        h.assertTrue(observed.kind()==ChoiceObservationKind.APPLIED && observed.reason()==null,"Native "+operation+" failed: "+JSON.toJson(observed));
                        h.assertTrue(observed.choiceDigest().equals(digest) && !observed.saveConfirmed(),"Choice changed or live observation became a save certificate");
                        observations.add(observed);context=result.continuation();
                    }
                }
                return observations;
            }
            void positive(Item input,boolean birch,int amount,String label) {
                int batches=amount/4+(amount%4==0?0:1);setup(input,batches);var request=request(birch,amount);
                var selected=host.selectChoice(request,run,authority);var reopened=RecipeChoice.fromCanonical(selected.canonical());
                h.assertTrue(selected.equals(reopened) && selected.digest().equals(reopened.digest()),"Canonical choice roundtrip changed native inputs");
                var plan=new DeterministicPrerequisiteResolver(Prerequisites.Limits.defaults(),()->h.getLevel().getGameTime())
                        .describeChoice(UUID.randomUUID(),1,request,selected);
                h.assertTrue(plan.choiceDigest().equals(selected.digest()) && plan.stock().stream().mapToLong(RecipeChoicePlan.StockProposal::units).sum()==batches,
                        "Prerequisites/lease-key proposal did not use the exact selected grid");
                var observations=execute(request,selected);var output=birch?Items.BIRCH_PLANKS:Items.OAK_PLANKS;
                h.assertTrue(from().countItem(input)==0 && from().countItem(Items.COBBLESTONE)==2 && to().countItem(output)==amount
                        && v.getInventory().countItem(output)==4*batches-amount,"Native input/result/surplus conservation differs");
                var row=Map.of("label",label,"nativeHostOnly",true,"authoritativeJobs",false,"request",request,"choice",selected,
                        "choiceCanonical",selected.canonical(),"choiceDigest",selected.digest(),"plan",plan,"observations",observations,
                        "actual",Map.of("consumed",batches,"produced",4*batches,"delivered",amount,"surplus",4*batches-amount));
                reports.add(row);evidence(label,row);
                System.out.println("B03_HOST_CASE label="+label+" digest="+selected.digest()+" consumed="+batches+" produced="+4*batches+" delivered="+amount+" surplus="+(4*batches-amount));
            }
        }
        var f=new Fixture();
        h.succeedWhen(()->{
            if(!v.onGround() || !world.loadedForDiscovery(a)){h.assertTrue(false,"Waiting for grounded native recipe fixture");return;}
            if(phase[0]==0) {
                h.assertTrue(world.acquire(actor,run),"Existing physical gateway control not acquired");
                for(var input:List.of(Items.BIRCH_LOG,Items.BIRCH_WOOD,Items.STRIPPED_BIRCH_LOG,Items.STRIPPED_BIRCH_WOOD))f.positive(input,true,1,"birch-"+net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(input).getPath()+"-q1");
                for(var input:List.of(Items.OAK_LOG,Items.OAK_WOOD,Items.STRIPPED_OAK_LOG,Items.STRIPPED_OAK_WOOD))f.positive(input,false,1,"oak-"+net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(input).getPath()+"-q1");
                f.positive(Items.BIRCH_WOOD,true,11,"original-birch-wood-q11");f.positive(Items.STRIPPED_BIRCH_LOG,true,7,"original-stripped-birch-q7");
                f.positive(Items.BIRCH_LOG,true,11,"canonical-birch-log-q11");f.positive(Items.BIRCH_LOG,true,7,"canonical-birch-log-q7");
                f.positive(Items.OAK_LOG,false,3,"canonical-oak-q3");f.positive(Items.OAK_LOG,false,4,"canonical-oak-q4");
                f.setup(Items.BIRCH_WOOD,1);var request=f.request(true,1);var choice=host.selectChoice(request,run,authority);
                var prepared=host.prepareChoice(request,choice,0,run,authority);
                f.from().setItem(25,f.from().getItem(26));f.from().setItem(26,ItemStack.EMPTY);
                var changedSlot=host.executeChoice(prepared,ChoicePhase.WITHDRAW);
                h.assertTrue(changedSlot.observation().reason()==Reason.STALE_OBSERVATION && changedSlot.observation().kind()==ChoiceObservationKind.PRECHECK_NO_EFFECT
                        && f.from().countItem(Items.BIRCH_WOOD)==1 && v.getInventory().isEmpty(),"Changed source slot was consumed");
                f.setup(Items.BIRCH_WOOD,1);choice=host.selectChoice(request,run,authority);prepared=host.prepareChoice(request,choice,0,run,authority);
                var named=new ItemStack(Items.BIRCH_WOOD);named.set(DataComponents.CUSTOM_NAME,Component.literal("wrong-components"));f.from().setItem(26,named);
                var components=host.executeChoice(prepared,ChoicePhase.WITHDRAW);
                h.assertTrue(components.observation().reason()==Reason.STALE_OBSERVATION && v.getInventory().isEmpty(),"Changed components were consumed");
                f.refused(()->host.selectChoice(request,run,authority),"RESOURCE_MISSING");
                f.setup(Items.BIRCH_WOOD,1);choice=host.selectChoice(request,run,authority);prepared=host.prepareChoice(request,choice,0,run,authority);
                h.getLevel().setBlock(a,Blocks.AIR.defaultBlockState(),3);h.getLevel().setBlock(a,Blocks.CHEST.defaultBlockState(),3);f.setup(Items.BIRCH_WOOD,1);
                var replaced=host.executeChoice(prepared,ChoicePhase.WITHDRAW);
                h.assertTrue(replaced.observation().reason()==Reason.STALE_OBSERVATION && f.from().countItem(Items.BIRCH_WOOD)==1,"Same-count replacement container was accepted");
                choice=host.selectChoice(request,run,authority);prepared=host.prepareChoice(request,choice,0,run,authority);allow.set(false);
                var denied=host.executeChoice(prepared,ChoicePhase.WITHDRAW);
                h.assertTrue(denied.observation().reason()==Reason.AUTHORITY_DENIED && denied.observation().participants().isEmpty()
                        && f.from().countItem(Items.BIRCH_WOOD)==1,"Revoked authority wrote or disclosed current native state");allow.set(true);
                var originalChoice=choice;
                var wrongGrid=new ArrayList<>(choice.batch(0).grid());wrongGrid.set(1,wrongGrid.getFirst());
                var wrongBindings=List.of(new RecipeChoice.Binding(0,source,26,2,wrongGrid.getFirst()),new RecipeChoice.Binding(1,source,26,1,wrongGrid.getFirst()));
                var invalid=new RecipeChoice(choice.capability(),choice.recipe(),choice.semanticFingerprint(),choice.runtimeCompatibility(),choice.reloadEpoch(),2,2,
                        List.of(new RecipeChoice.Batch(wrongGrid,wrongBindings)),choice.result(),4,1,List.of());
                f.refused(()->host.prepareChoice(request,invalid,0,run,authority),"TARGET_INVALID");
                for(int i=0;i<v.getInventory().getContainerSize();i++)v.getInventory().setItem(i,new ItemStack(Items.STONE,64));
                v.getInventory().setItem(0,new ItemStack(Items.BIRCH_WOOD,63));
                f.refused(()->host.prepareChoice(request,originalChoice,0,run,authority),"TARGET_UNAVAILABLE");h.assertTrue(f.from().countItem(Items.BIRCH_WOOD)==1,"Capacity refusal consumed an input");
                f.setup(Items.BIRCH_WOOD,1);choice=host.selectChoice(request,run,authority);prepared=host.prepareChoice(request,choice,0,run,authority);
                var withdrawn=host.executeChoice(prepared,ChoicePhase.WITHDRAW);var duplicate=host.executeChoice(prepared,ChoicePhase.WITHDRAW);
                h.assertTrue(duplicate.observation().reason()==Reason.STALE_OBSERVATION && f.from().countItem(Items.BIRCH_WOOD)==0 && v.getInventory().countItem(Items.BIRCH_WOOD)==1,"Prepared operation replayed withdrawal");
                var crafted=host.executeChoice(withdrawn.continuation(),ChoicePhase.CRAFT);var deposited=host.executeChoice(crafted.continuation(),ChoicePhase.DEPOSIT);
                h.assertTrue(deposited.observation().kind()==ChoiceObservationKind.APPLIED,"Original operation did not complete after duplicate refusal");
                f.setup(Items.BIRCH_WOOD,1);savedRequest[0]=request;savedChoice[0]=host.selectChoice(request,run,authority);
                saved[0]=host.prepareChoice(request,savedChoice[0],0,run,authority);originalSemantic[0]=savedChoice[0].semanticFingerprint();
                evidence("guard-negatives",Map.of("nativeHostOnly",true,"changedSlot",changedSlot.observation(),"components",components.observation(),
                        "replacement",replaced.observation(),"revocation",denied.observation(),"duplicate",duplicate.observation()));
                originalPacks[0]=List.copyOf(h.getLevel().getServer().getPackRepository().getSelectedIds());
                reloaded[0]=h.getLevel().getServer().reloadResources(originalPacks[0]);phase[0]=1;
            }
            if(phase[0]==1) {
                if(!reloaded[0].isDone()){h.assertTrue(false,"Waiting for real unchanged recipe reload");return;}reloaded[0].join();
                var stale=host.executeChoice(saved[0],ChoicePhase.WITHDRAW);
                h.assertTrue(stale.observation().reason()==Reason.ARTIFACT_INCOMPATIBLE && f.from().countItem(Items.BIRCH_WOOD)==1,"Prepared snapshot survived actual reload");
                var fresh=host.prepareChoice(savedRequest[0],RecipeChoice.fromCanonical(savedChoice[0].canonical()),0,run,authority);
                h.assertTrue(!fresh.token.equals(saved[0].token),"New preparation reused the old token");
                // Change the same native recipe ID with an actual selected test datapack.
                try {
                    var server=h.getLevel().getServer();var pack=server.getWorldPath(LevelResource.DATAPACK_DIR).resolve("cc-b03-semantic");Files.createDirectories(pack);
                    var format=SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA);
                    var metadata=new PackMetadataSection(Component.literal("Isolated B03 semantic reload fixture"),new InclusiveRange<>(format,format));
                    var packJson=new JsonObject();packJson.add("pack",PackMetadataSection.SERVER_TYPE.codec().encodeStart(JsonOps.INSTANCE,metadata).getOrThrow());
                    Files.writeString(pack.resolve("pack.mcmeta"),packJson.toString());
                    var recipePath=pack.resolve("data/minecraft/recipe/birch_planks.json");Files.createDirectories(recipePath.getParent());
                    var recipe=server.getRecipeManager().byKey(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE,net.minecraft.resources.Identifier.parse(BirchPlankProduction.RECIPE))).orElseThrow().value();
                    var encoded=net.minecraft.world.item.crafting.Recipe.DIRECT_CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE,h.getLevel().registryAccess()),recipe).getOrThrow().getAsJsonObject();
                    encoded.getAsJsonObject("result").addProperty("count",3);Files.writeString(recipePath,encoded.toString());
                    server.getPackRepository().reload();var selected=new ArrayList<>(originalPacks[0]);selected.add("file/cc-b03-semantic");
                    h.assertTrue(server.getPackRepository().getAvailableIds().contains("file/cc-b03-semantic"),"Native test datapack was not discovered");
                    saved[0]=fresh;reloaded[0]=server.reloadResources(selected);phase[0]=2;
                    evidence("unchanged-reload",Map.of("nativeHostOnly",true,"oldPreparationRefusal",stale.observation(),"oldChoiceDigest",savedChoice[0].digest(),"newPreparationToken",fresh.token));
                } catch(java.io.IOException failure){throw new IllegalStateException("Native datapack fixture",failure);}
            }
            if(phase[0]==2) {
                if(!reloaded[0].isDone()){h.assertTrue(false,"Waiting for same-ID semantic recipe change");return;}reloaded[0].join();
                var changed=host.executeChoice(saved[0],ChoicePhase.WITHDRAW);
                h.assertTrue(changed.observation().reason()==Reason.ARTIFACT_INCOMPATIBLE && f.from().countItem(Items.BIRCH_WOOD)==1,"Same-ID changed recipe consumed an old choice");
                var request=savedRequest[0];f.refused(()->host.selectChoice(request,run,authority),"UNSUPPORTED_PRIMITIVE");
                evidence("semantic-reload",Map.of("nativeHostOnly",true,"originalSemanticFingerprint",originalSemantic[0],"sameIdRefusal",changed.observation(),"sourceCount",f.from().countItem(Items.BIRCH_WOOD)));
                reloaded[0]=h.getLevel().getServer().reloadResources(originalPacks[0]);phase[0]=3;
            }
            if(phase[0]==3) {
                if(!reloaded[0].isDone()){h.assertTrue(false,"Waiting for original recipe semantics restore");return;}reloaded[0].join();
                var observations=f.execute(savedRequest[0],RecipeChoice.fromCanonical(savedChoice[0].canonical()));
                h.assertTrue(f.to().countItem(Items.BIRCH_PLANKS)==1 && v.getInventory().countItem(Items.BIRCH_PLANKS)==3,"Persisted immutable choice did not revalidate after restore");
                world.release(actor,run);var prepared=saved[0];var revoked=host.executeChoice(prepared,ChoicePhase.WITHDRAW);
                h.assertTrue(revoked.observation().reason()==Reason.ACTOR_UNAVAILABLE,"Released native control performed an effect");
                evidence("summary",Map.of("status","PASS","nativeHostOnly",true,"autonomousGameplay",false,"durableSaveProof",false,
                        "positiveCases",reports.size(),"restoredChoiceDigest",savedChoice[0].digest(),"restoredObservations",observations,"revokedControl",revoked.observation()));
                System.out.println("B03_HOST_COMPLETE positives="+reports.size()+" semanticReload=true exactComponents=true staleSlots=true replacement=true capacity=true authority=true oldRouteUnchanged=true nativeHostOnly=true");phase[0]=4;
            }
        });
    }
}
