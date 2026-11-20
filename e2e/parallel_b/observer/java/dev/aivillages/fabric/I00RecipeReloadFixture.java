package dev.aivillages.fabric;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.aivillages.core.kernel.runtime.evidence.NativeRecipeJournal;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.util.InclusiveRange;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Native datapack fixture. No Job, claim, applied result, or participant receipt can be produced here. */
public final class I00RecipeReloadFixture implements ModInitializer {
    private static final Gson JSON=new Gson();
    private static final String RECIPE="minecraft:stick", PACK="i00-recipe-challenge";
    private static final AtomicReference<Trial> ARMED=new AtomicReference<>();
    private static final AtomicReference<FileChannel> ALLOCATED_SPACE=new AtomicReference<>();
    private record Trial(String id,String kind,String method,Path root,MinecraftServer server,CountDownLatch released) { }
    private String seen="";

    @Override public void onInitialize() {
        String directory=System.getenv("COGNITIVECRAFT_E2E_DIR");
        if(directory==null)return;
        Path root=Path.of(directory).resolve("server");
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{
            var filler=ALLOCATED_SPACE.getAndSet(null);
            if(filler!=null)try{filler.close();}catch(IOException failure){throw new IllegalStateException(failure);}
        });
        ServerTickEvents.END_SERVER_TICK.register(server->{
            Path request=root.resolve("i00-recipe-challenge.json");
            if(!Files.isRegularFile(request))return;
            try {
                if(Files.size(request)>2048)throw new IOException("Recipe challenge request exceeds fixture envelope");
                var value=JsonParser.parseString(Files.readString(request)).getAsJsonObject();
                String id=UUID.fromString(value.get("id").getAsString()).toString();
                if(seen.equals(id))return;
                seen=id;
                String kind=value.get("kind").getAsString();
                if(kind.equals("native-remainders")) {
                    var trial=new Trial(id,kind,"",root,server,new CountDownLatch(0));
                    change(trial,null);
                    return;
                }
                if(!List.of("same-catalogue-reload","changed-result-components","storage-pressure").contains(kind))
                    throw new IllegalArgumentException("Unknown controlled datapack challenge");
                String method=value.get("method").getAsString();
                if(!method.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Expected admitted method identity");
                var trial=new Trial(id,kind,method,root,server,new CountDownLatch(1));
                if(!ARMED.compareAndSet(null,trial))throw new IllegalStateException("One original prepared challenge per process");
                write(trial,"armed",Map.of("id",id,"kind",kind,"before",describe(server,false),"pid",ProcessHandle.current().pid()));
            } catch(Exception failure) {throw new IllegalStateException("Isolated native datapack challenge",failure);}
        });
    }

    public static void prepared(NativeRecipeJournal.Entry entry) {
        Trial trial=ARMED.get();
        if(trial==null || entry.result()!=null || !entry.intent().method().equals(trial.method())
                || !entry.intent().phase().name().equals("WITHDRAW") || !ARMED.compareAndSet(trial,null))return;
        if(trial.kind().equals("storage-pressure")) {
            occupyIsolatedJournalVolume(trial,entry);
            return;
        }
        trial.server().execute(()->change(trial,entry));
        try {
            if(!trial.released().await(12,TimeUnit.SECONDS))throw new IllegalStateException("Missed native datapack interruption bound");
        } catch(InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Original fixture writer stopped before native reload acknowledgement",stopped);
        }
    }

    private static void occupyIsolatedJournalVolume(Trial trial,NativeRecipeJournal.Entry prepared) {
        Path directory=trial.server().getWorldPath(LevelResource.ROOT).resolve("data/cognitivecraft");
        try {
            var store=Files.getFileStore(directory);
            long available=store.getUsableSpace(), maximum=16*1024*1024L;
            if(!store.type().equals("tmpfs") || store.getTotalSpace()!=maximum || available<4096 || available>maximum)
                throw new IOException("Refusing pressure outside the bounded dedicated journal filesystem");
            Path temp=Files.createTempFile(directory,"i00-pressure-",null);
            var filler=FileChannel.open(temp,StandardOpenOption.WRITE,StandardOpenOption.DELETE_ON_CLOSE);
            if(!ALLOCATED_SPACE.compareAndSet(null,filler)){filler.close();throw new IOException("Existing pressure allocation");}
            var block=ByteBuffer.allocate(4096);
            long allocated=0;
            String refusal="";
            try {
                while(allocated<=maximum) {
                    block.clear();
                    while(block.hasRemaining())allocated+=filler.write(block);
                }
            } catch(IOException full) {refusal=String.valueOf(full.getMessage());}
            if(refusal.isBlank() || store.getUsableSpace()!=0)throw new IOException("Dedicated native journal disk did not fill");
            write(trial,"pressure",Map.of("id",trial.id(),"intent",prepared.intent(),"volumeBytes",maximum,
                    "initialFreeBytes",available,"remainingBytes",store.getUsableSpace(),"allocatedBytes",allocated,
                    "systemRefusal",refusal,"time",System.currentTimeMillis(),"pid",ProcessHandle.current().pid()));
        } catch(IOException failure){throw new IllegalStateException("Native recipe journal pressure fixture failed",failure);}
    }

    private static void change(Trial trial,NativeRecipeJournal.Entry entry) {
        var server=trial.server();
        try {
            var packs=new ArrayList<>(server.getPackRepository().getSelectedIds());
            var before=describe(server,false);
            long started=System.currentTimeMillis();
            if(entry!=null)write(trial,"prepared",Map.of("id",trial.id(),"intent",entry.intent(),
                    "before",before,"started",started,"pid",ProcessHandle.current().pid()));
            if(!trial.kind().equals("same-catalogue-reload")) {
                install(server,trial.kind());
                server.getPackRepository().reload();
                packs.add("file/"+PACK);
            }
            server.reloadResources(packs).whenComplete((ignored,failure)->server.execute(()->{
                try {
                    if(failure!=null)throw new IllegalStateException("Native reload failed",failure);
                    write(trial,"finished",Map.of("id",trial.id(),"kind",trial.kind(),"before",before,"packs",packs,
                            "after",describe(server,trial.kind().equals("native-remainders")),"started",started,
                            "ended",System.currentTimeMillis(),"pid",ProcessHandle.current().pid()));
                } finally {trial.released().countDown();}
            }));
        } catch(Exception failure) {
            trial.released().countDown();
            throw new IllegalStateException("Could not apply vanilla datapack fixture",failure);
        }
    }

    private static CraftingRecipe recipe(MinecraftServer server) {
        var key=ResourceKey.create(Registries.RECIPE,Identifier.parse(RECIPE));
        return (CraftingRecipe)server.getRecipeManager().byKey(key).orElseThrow().value();
    }

    private static Map<String,Object> describe(MinecraftServer server,boolean remainder) {
        var recipe=recipe(server);
        var input=CraftingInput.of(1,2,List.of(new ItemStack(Items.OAK_PLANKS),
                new ItemStack(remainder?Items.HONEY_BOTTLE:Items.OAK_PLANKS)));
        if(!recipe.matches(input,server.overworld()))throw new IllegalStateException("Loaded native recipe does not match the fixture grid");
        var result=recipe.assemble(input);
        var residual=recipe.getRemainingItems(input).stream().filter(stack->!stack.isEmpty()).map(stack->Map.of(
                "item",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),"count",stack.getCount())).toList();
        var encoded=Recipe.DIRECT_CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE,server.overworld().registryAccess()),recipe).getOrThrow();
        var custom=result.get(DataComponents.CUSTOM_NAME);
        return Map.of("recipe",RECIPE,"output",BuiltInRegistries.ITEM.getKey(result.getItem()).toString(),"count",result.getCount(),
                "customName",custom==null?"":custom.getString(),"remainders",residual,"encoding",encoded);
    }

    private static void install(MinecraftServer server,String kind)throws IOException {
        Path pack=server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK);
        Path recipes=pack.resolve("data/minecraft/recipe");
        Files.createDirectories(recipes);
        var version=SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA);
        var metadata=new PackMetadataSection(Component.literal("Isolated I00 native recipe challenge"),new InclusiveRange<>(version,version));
        var wrapper=new JsonObject();
        wrapper.add("pack",PackMetadataSection.SERVER_TYPE.codec().encodeStart(JsonOps.INSTANCE,metadata).getOrThrow());
        Files.writeString(pack.resolve("pack.mcmeta"),wrapper.toString());
        var ops=RegistryOps.create(JsonOps.INSTANCE,server.overworld().registryAccess());
        var encoded=Recipe.DIRECT_CODEC.encodeStart(ops,recipe(server)).getOrThrow().getAsJsonObject();
        if(kind.equals("native-remainders")) {
            encoded=JsonParser.parseString("{\"type\":\"minecraft:crafting_shaped\",\"pattern\":[\"P\",\"H\"],"
                    +"\"key\":{\"P\":\"minecraft:oak_planks\",\"H\":\"minecraft:honey_bottle\"},"
                    +"\"result\":{\"id\":\"minecraft:stick\",\"count\":4}}").getAsJsonObject();
        } else {
            var named=new ItemStack(Items.STICK,4);
            named.set(DataComponents.CUSTOM_NAME,Component.literal("I00 changed recipe result"));
            encoded.add("result",ItemStack.CODEC.encodeStart(ops,named).getOrThrow());
        }
        Files.writeString(recipes.resolve("stick.json"),encoded.toString());
    }

    private static void write(Trial trial,String kind,Map<String,?> document) {
        try {
            Path target=trial.root().resolve("i00-recipe-challenge-"+kind+".json");
            Path staged=target.resolveSibling(target.getFileName()+".next");
            Files.writeString(staged,JSON.toJson(document));
            Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } catch(IOException failure) {throw new IllegalStateException("Retain native datapack boundary",failure);}
    }
}
