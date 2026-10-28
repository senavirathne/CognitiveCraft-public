package dev.aivillages.e2e.paralleld;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import com.google.gson.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import dev.aivillages.core.kernel.storage.*;
import dev.aivillages.fabric.storage.*;
import static dev.aivillages.core.kernel.storage.StorageRegistry.*;
import static dev.aivillages.core.kernel.storage.StorageBindingRules.*;

/** Explicit tagged-world TEST MOD ONLY. Uses real metadata/native objects and authenticated command sources. */
public final class DStorageObservationServer implements ModInitializer {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private static final UUID ADMIN=offline("PlayerD_A"),DOMAIN=UUID.fromString("d0500000-0000-4000-8000-000000000002");
    private static DStorageObservationServer active;
    private MinecraftServer server;private ServerLevel level;private UUID world;private Path root;
    private StorageMetadataStore store;private StorageRegistry registry;private StorageIncarnationAdapter identities;private MinecraftStorageAdapter reader;
    private final Map<String,UUID> ids=new LinkedHashMap<>();private final Map<String,BlockPos> points=Map.of("single",new BlockPos(6,201,8),"double",new BlockPos(10,201,8),"barrel",new BlockPos(14,201,8),"private",new BlockPos(18,201,8),"deposit",new BlockPos(2,201,8));
    private StorageBindingRules.Binding attested;private boolean ready;
    @Override public void onInitialize(){DTestAuthority.acquireTaggedWorld();
        CommandRegistrationCallback.EVENT.register((dispatcher,context,environment)->dispatcher.register(Commands.literal("d-storage")
            .then(Commands.literal("view").then(Commands.argument("target",com.mojang.brigadier.arguments.StringArgumentType.word()).then(Commands.argument("nonce",com.mojang.brigadier.arguments.StringArgumentType.word()).executes(c->{var player=c.getSource().getPlayerOrException();if(active==null || !active.ready)throw new IllegalStateException("fixture not ready");String name=com.mojang.brigadier.arguments.StringArgumentType.getString(c,"target"),nonce=com.mojang.brigadier.arguments.StringArgumentType.getString(c,"nonce");var projection=active.reader.observe(active.scope(player.getUUID()),active.ids.getOrDefault(name,UUID.fromString("d0500000-0000-4000-8000-000000999999")));String payload=projection instanceof StorageSnapshot.Visible?JSON.toJson(projection):"UNAVAILABLE";c.getSource().sendSuccess(()->Component.literal("D05_REPLY "+nonce+" "+payload),false);active.frame("OBSERVER",Map.of("principal",player.getUUID().toString(),"target",name,"nonce",nonce,"payload",payload));return 1;}))))
            .then(Commands.literal("mutate").executes(c->{if(c.getSource().getEntity()!=null)throw new SecurityException("console fixture setup only");active.mutate();return 1;}))
            .then(Commands.literal("split").executes(c->{if(c.getSource().getEntity()!=null)throw new SecurityException("console fixture setup only");active.split();return 1;}))));
        ServerLifecycleEvents.SERVER_STARTED.register(this::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(s->{if(identities!=null)identities.close();if(store!=null)store.closeAsync();});
    }
    private void start(MinecraftServer s){server=s;level=s.overworld();world=DTestAuthority.acquireTaggedWorld().world();root=Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR")).resolve("server");active=this;
        when(StorageMetadataStore.createNew(root.resolve("d05-metadata"),world),value->{store=value;registry=new StorageRegistry(store,new StorageRegistry.Authority(){
            public boolean controls(Caller c,Owner o){return c.world().equals(world) && c.principal().equals(ADMIN) && o.equals(new Owner(OwnerKind.PLAYER,ADMIN));}
            public boolean allows(Caller c,Action a,StorageRegistry.Record r){return c.world().equals(world) && c.principal().equals(ADMIN) && (a!=Action.COMMIT_BIND || attested!=null && attested.storage().equals(r.id()) && attested.generation()==r.generation());}
            public boolean mayGrant(Caller c,StorageRegistry.Record r,Grant g){return controls(c,r.owner()) && Set.of(Permission.INSPECT,Permission.DEPOSIT,Permission.WITHDRAW).containsAll(g.permissions());}
        });identities=new StorageIncarnationAdapter(level,world,(a,w,id,pos)->world.equals(w));identities.updateMetadata(registry.snapshot());var policy=DStorageAdaptersGameTests.policy(level,registry,identities,()->true);reader=new MinecraftStorageAdapter(level,identities,registry::snapshot,policy,(scope,id,op,pos)->scope.world().equals(world));
            for(int x=0;x<=21;x++)for(int z=0;z<=14;z++){level.setBlockAndUpdate(new BlockPos(x,200,z),Blocks.STONE.defaultBlockState());for(int y=201;y<205;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());}
            for(var e:points.entrySet())level.setBlockAndUpdate(e.getValue(),e.getKey().equals("barrel")?Blocks.BARREL.defaultBlockState():Blocks.CHEST.defaultBlockState());
            BlockPos pair=points.get("double");var state=Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH);level.setBlock(pair.east(),state.setValue(ChestBlock.TYPE,ChestType.RIGHT),2);level.setBlock(pair,state.setValue(ChestBlock.TYPE,ChestType.LEFT),2);
            add(new ArrayList<>(List.of("single","double","barrel","private","deposit")),0);
        });
    }
    private void add(List<String> names,int i){if(i==names.size()){contents();ready=true;frame("READY",Map.of("world",world.toString(),"ids",ids,"positions",points.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,e->List.of(e.getValue().getX(),e.getValue().getY(),e.getValue().getZ())))));write(root.resolve("d05-ready.json"),Map.of("world",world.toString(),"ids",ids));return;}
        String name=names.get(i);BlockPos pos=points.get(name);var grants=List.of(new Grant(name.equals("private") || name.equals("deposit")?new Owner(OwnerKind.PLAYER,ADMIN):new Owner(OwnerKind.PUBLIC,null),name.equals("deposit")?Set.of(Permission.DEPOSIT):Set.of(Permission.INSPECT,Permission.DEPOSIT,Permission.WITHDRAW)));String adapter=name.equals("double")?DOUBLE:name.equals("barrel")?BARREL:SINGLE;Caller caller=new Caller(world,ADMIN);
        when(registry.registerMetadata(caller,registry.snapshot().revision(),new Owner(OwnerKind.PLAYER,ADMIN),name,adapter,Set.of(),grants),a->{ack(a);UUID id=a.storage();ids.put(name,id);identities.updateMetadata(registry.snapshot());var plan=identities.plan(id,1,pos);var snap=registry.snapshot();when(registry.prepareBinding(caller,snap.revision(),id,snap.records().get(id).revision(),plan.intent().wire()),b->{ack(b);identities.updateMetadata(registry.snapshot());attested=identities.write(plan);var prepared=registry.snapshot();when(registry.acknowledgeBinding(caller,prepared.revision(),id,prepared.records().get(id).revision(),attested.wire()),c->{ack(c);identities.updateMetadata(registry.snapshot());add(names,i+1);});});});
    }
    private void contents(){var single=container("single");single.setItem(0,new ItemStack(Items.OAK_PLANKS,32));single.setItem(1,new ItemStack(Items.OAK_PLANKS,64));single.setItem(2,new ItemStack(Items.WOODEN_PICKAXE));single.setChanged();var left=container("double");left.setItem(0,new ItemStack(Items.OAK_PLANKS,32));left.setItem(1,new ItemStack(Items.STONE,64));var right=(BaseContainerBlockEntity)level.getBlockEntity(points.get("double").east());right.setItem(0,new ItemStack(Items.OAK_PLANKS,64));right.setItem(26,new ItemStack(Items.OAK_PLANKS,1));left.setChanged();right.setChanged();var barrel=container("barrel");barrel.setItem(0,new ItemStack(Items.OAK_PLANKS,63));barrel.setItem(1,new ItemStack(Items.STONE,64));barrel.setChanged();container("private").setItem(0,new ItemStack(Items.DIAMOND,7));container("private").setChanged();}
    private void mutate(){if(!ready)throw new IllegalStateException("fixture not ready");for(String name:List.of("single","double","barrel"))frame("BEFORE_MUTATION",Map.of("target",name,"snapshot",reader.observe(scope(ADMIN),ids.get(name))));container("single").setItem(0,new ItemStack(Items.OAK_PLANKS,48));container("double").setItem(0,new ItemStack(Items.OAK_PLANKS,1));container("barrel").setItem(0,new ItemStack(Items.OAK_PLANKS,64));for(String name:List.of("single","double","barrel")){container(name).setChanged();frame("AFTER_MUTATION",Map.of("target",name,"snapshot",reader.observe(scope(ADMIN),ids.get(name))));}write(root.resolve("d05-mutated.json"),Map.of("tick",level.getGameTime()));}
    private void split(){var p=points.get("double");level.setBlockAndUpdate(p.east(),Blocks.AIR.defaultBlockState());level.setBlockAndUpdate(p,Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH));frame("SPLIT",Map.of("oldBindingVisible",reader.observe(scope(ADMIN),ids.get("double")) instanceof StorageSnapshot.Visible,"first",level.getBlockState(p).toString(),"second",level.getBlockState(p.east()).toString()));write(root.resolve("d05-split.json"),Map.of("tick",level.getGameTime()));}
    private BaseContainerBlockEntity container(String name){return (BaseContainerBlockEntity)level.getBlockEntity(points.get(name));}
    private StorageAccessPolicy.Scope scope(UUID principal){return new StorageAccessPolicy.Scope(world,DOMAIN,principal,null);}
    private static void ack(StorageMetadataStore.Ack a){if(a.status()!=StorageMetadataStore.Status.ACKNOWLEDGED)throw new IllegalStateException("fixture metadata ack "+a);}
    private <T> void when(CompletionStage<T> future,Consumer<T> next){future.whenComplete((value,error)->server.execute(()->{if(error!=null)throw new CompletionException(error);next.accept(value);}));}
    private void frame(String event,Object value){writeLine(root.resolve("d05-observations.jsonl"),Map.of("case","D05-CL01","event",event,"tick",level.getGameTime(),"data",value));}
    private static void write(Path path,Object value){try{Files.createDirectories(path.getParent());Files.writeString(path,JSON.toJson(value)+"\n");}catch(Exception e){throw new RuntimeException(e);}}
    private static void writeLine(Path path,Object value){try{Files.createDirectories(path.getParent());Files.writeString(path,JSON.toJson(value)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception e){throw new RuntimeException(e);}}
    private static UUID offline(String name){return UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(StandardCharsets.UTF_8));}
}
