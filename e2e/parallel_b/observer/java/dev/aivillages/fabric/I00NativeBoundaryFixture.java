package dev.aivillages.fabric;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.aivillages.core.kernel.Contracts.ContainerValue;
import dev.aivillages.core.kernel.Contracts.FacilityValue;
import dev.aivillages.core.kernel.Jobs;
import dev.aivillages.core.kernel.ResourceDelivery.Stage;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Bounded native clock control triggered by the read-only original Job and physical state. */
public final class I00NativeBoundaryFixture implements ModInitializer {
    private static final Gson JSON=new Gson();
    private static final String LOG="minecraft:oak_log", AXE="minecraft:wooden_axe", TABLE="minecraft:crafting_table";
    private static final Set<String> CUTS=Set.of("after-wood-collection","after-material-deposit","after-table-placement",
            "after-craft","at-final-delivery");
    private record Cut(UUID token,UUID root,UUID principal,String boundary) { }
    private Cut cut;
    private int idle;
    private static boolean stoppedAtMeasuredBoundary;

    public static boolean atMeasuredStop() {return stoppedAtMeasuredBoundary;}

    @Override public void onInitialize() {
        String directory=System.getenv("COGNITIVECRAFT_E2E_DIR");
        if(directory==null)return;
        Path root=Path.of(directory).resolve("server");
        Path request=root.resolve("i00-native-boundary.json"), reached=root.resolve("i00-native-boundary-reached.json");
        ServerTickEvents.END_SERVER_TICK.register(server->{
            try {
                if(Files.exists(reached))return;
                if(cut==null) {
                    // Keep the ordinary connected observer workload unchanged until specifically armed.
                    if((idle++ & 3)!=0 || !Files.isRegularFile(request))return;
                    if(Files.size(request)>1024)throw new IOException("Native cut request exceeds fixture envelope");
                    var row=JsonParser.parseString(Files.readString(request)).getAsJsonObject();
                    cut=new Cut(UUID.fromString(row.get("token").getAsString()),UUID.fromString(row.get("root").getAsString()),
                            UUID.fromString(row.get("principal").getAsString()),row.get("boundary").getAsString());
                    if(!CUTS.contains(cut.boundary()))throw new IllegalArgumentException("Unknown original walkthrough boundary");
                }
                var principal=server.getPlayerList().getPlayer(cut.principal());
                var kernel=AiVillages.kernel();
                if(principal==null || kernel==null)return;
                Jobs.Job job;
                try{job=kernel.queuedJob(principal,cut.root());}catch(SecurityException | IllegalStateException notYetVisible){return;}
                if(job.activity()==null || job.state().terminal() || !job.origin().principal().id().equals(cut.principal())
                        || !witnesses(server,cut,job))return;
                var clock=server.tickRateManager();
                if(clock.isFrozen() || clock.tickrate()!=20.0f)throw new IllegalStateException("Original native clock changed before the measured cut");
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"tick freeze");
                if(!clock.isFrozen() || !witnesses(server,cut,job))throw new IllegalStateException("Native boundary changed during freeze");
                // Native saves and client/console activity continue. The next
                // application tick belongs to the replacement JVM for this test.
                stoppedAtMeasuredBoundary=true;
                var evidence=Map.of("token",cut.token(),"root",cut.root(),"principal",cut.principal(),"boundary",cut.boundary(),
                        "tick",server.overworld().getGameTime(),"jobRevision",job.revision(),"rate",clock.tickrate(),
                        "frozen",clock.isFrozen(),"time",System.currentTimeMillis(),"pid",ProcessHandle.current().pid());
                Path pending=reached.resolveSibling(reached.getFileName()+".next");
                Files.writeString(pending,JSON.toJson(evidence));
                Files.move(pending,reached,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }catch(IOException failure){throw new IllegalStateException("Original native boundary evidence could not be retained",failure);}
        });
    }

    private static boolean witnesses(MinecraftServer server,Cut cut,Jobs.Job job) {
        var original=job.request().request().arguments();
        if(!(original.get("table") instanceof FacilityValue facility) || !(original.get("destination") instanceof ContainerValue destination))return false;
        var level=server.overworld();
        var target=destination.value();
        var table=facility.value();
        boolean placed=level.getBlockState(new BlockPos(table.x(),table.y(),table.z())).is(Blocks.CRAFTING_TABLE);
        var actor=level.getEntity(job.responsible().entityId());
        var recipient=level.getBlockEntity(new BlockPos(target.x(),target.y(),target.z()));
        if(!(actor instanceof Villager villager) || !(recipient instanceof Container chest))return false;
        long crafted=quantity(job,Stage.CRAFT,AXE), deposited=quantity(job,Stage.DEPOSIT,LOG);
        return switch(cut.boundary()) {
            case "after-wood-collection" -> quantity(job,Stage.HARVEST,LOG)==3 && quantity(job,Stage.PICKUP,LOG)==3
                    && deposited==2 && villager.getInventory().countItem(Items.OAK_LOG)==1 && !placed && crafted==0;
            case "after-material-deposit" -> deposited==3 && !placed && crafted==0;
            case "after-table-placement" -> {
                var attempt=job.attempts().stream().filter(a->a.resourceReceipts().stream().anyMatch(r->r.stage()==Stage.PLACE)).findFirst();
                yield placed && crafted==0 && quantity(job,Stage.PLACE,TABLE)==1 && attempt.isPresent()
                        && job.activity().checkpoints().stream().noneMatch(c->c.attempt().equals(attempt.get().id()));
            }
            case "after-craft" -> crafted==1 && villager.getInventory().countItem(Items.WOODEN_AXE)==1 && chest.countItem(Items.WOODEN_AXE)==0;
            case "at-final-delivery" -> crafted==1 && chest.countItem(Items.WOODEN_AXE)==1 && job.fulfilled()==0;
            default -> false;
        };
    }

    private static long quantity(Jobs.Job job,Stage stage,String item) {
        return job.attempts().stream().flatMap(attempt->attempt.resourceReceipts().stream())
                .filter(receipt->receipt.stage()==stage && receipt.item().id().equals(item)).mapToLong(r->r.quantity()).sum();
    }
}
