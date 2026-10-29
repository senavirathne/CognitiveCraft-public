package dev.aivillages.e2e.paralleld;

import java.util.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import dev.aivillages.core.kernel.logistics.*;
import dev.aivillages.core.kernel.logistics.MobilityPolicy.*;
import dev.aivillages.fabric.logistics.MinecraftNavigationAdapter;

/** Isolated test movement owner. No release registration, production root, Jobs or save authority. */
final class DComeScene {
    static final Gson JSON=new GsonBuilder().serializeNulls().create();
    final ServerLevel level;final ServerPlayer principal;final Villager actor;final MinecraftNavigationAdapter adapter;final Actor identity;final BlockPos origin;final Path evidence;final String caseId;
    final Map<String,List<String>> rawFrames=new LinkedHashMap<>();boolean authorized=true;MinecraftNavigationAdapter.Route route;long deadline;final int forcedBefore;
    DComeScene(ServerLevel level,BlockPos origin,ServerPlayer principal,Path evidence,String caseId){
        this(level,origin,principal,evidence,caseId,UUID.fromString("d0600000-0000-4000-8000-000000000003"),UUID.fromString("d0600000-0000-4000-8000-000000000004"));
    }
    DComeScene(ServerLevel level,BlockPos origin,ServerPlayer principal,Path evidence,String caseId,UUID actorId,UUID citizenId){
        this.level=level;this.principal=principal;this.origin=origin;this.evidence=evidence;this.caseId=caseId;UUID world=DTestAuthority.acquireTaggedWorld().world();
        // Construction happens before admission. No forceload/ticket or actor-position command is used.
        for(int x=0;x<20;x++)for(int z=0;z<25;z++){level.setBlockAndUpdate(origin.offset(x,-1,z),Blocks.STONE.defaultBlockState());for(int y=0;y<4;y++)level.setBlockAndUpdate(origin.offset(x,y,z),Blocks.AIR.defaultBlockState());}
        for(int z=4;z<=10;z++)for(int y=0;y<3;y++)level.setBlockAndUpdate(origin.offset(8,y,z),Blocks.STONE.defaultBlockState());
        actor=new Villager(EntityTypes.VILLAGER,level);actor.setUUID(actorId);actor.setPos(origin.getX()+3.5,origin.getY(),origin.getZ()+7.5);actor.setPersistenceRequired();actor.removeFreeWill();level.addFreshEntity(actor);
        identity=new Actor(world,principal.getUUID(),citizenId,actor.getUUID(),1,1,level.dimension().identifier().toString());
        var walls=new ArrayList<Map<String,Object>>();var floor=new ArrayList<Map<String,Object>>();for(int x=0;x<20;x++)for(int z=0;z<25;z++){var p=origin.offset(x,-1,z);floor.add(Map.of("relative",List.of(x,-1,z),"state",level.getBlockState(p).toString()));}for(int z=4;z<=10;z++)for(int y=0;y<3;y++){var p=origin.offset(8,y,z);walls.add(Map.of("relative",List.of(8,y,z),"state",level.getBlockState(p).toString()));}write(evidence.resolve("arena.json"),Map.of("origin",List.of(origin.getX(),origin.getY(),origin.getZ()),"floor",floor,"wall",walls,"native_class",actor.getClass().getName()));
        deadline=level.getGameTime()+600;adapter=new MinecraftNavigationAdapter(level,actor,(key,tick)->new MinecraftNavigationAdapter.Control(identity,authorized,Limits.maximum(),deadline));forcedBefore=level.getChunkSource().getForceLoadedChunks().size();
    }
    void accept(ServerPlayer player){deadline=level.getGameTime()+600;route=adapter.come(identity,player);frame("ACCEPTED",null,0);write(evidence.resolve("capture.json"),route.goal().endpoint());}
    MobilityObservation tick(){long t=System.nanoTime();var result=route.step();frame(result.terminal()?"TERMINAL":"STEP",result,System.nanoTime()-t);return result;}
    void frame(String event,MobilityObservation observation,long nanos){var out=new TreeMap<String,Object>();out.put("case",caseId);out.put("mode",route==null?null:route.goal().mode().name());out.put("event",event);out.put("world",identity.world().toString());out.put("actor",actor.getUUID().toString());out.put("principal",identity.principal().toString());out.put("tick",level.getGameTime());out.put("seed",level.getSeed());out.put("native_player",List.of(principal.getX(),principal.getY(),principal.getZ()));out.put("native_feet",List.of(actor.getX(),actor.getY(),actor.getZ()));out.put("captured",route==null?null:route.goal().endpoint());out.put("accepted_tick",route==null?null:route.goal().acceptedTick());out.put("observation",observation);out.put("origin",List.of(origin.getX(),origin.getY(),origin.getZ()));out.put("authorized",authorized);out.put("forced_before",forcedBefore);out.put("forced_now",level.getChunkSource().getForceLoadedChunks().size());out.put("step_ns",nanos);out.put("heap_used_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());out.put("integration_root",null);out.put("save_proof",null);out.put("scheduled_tasks",0);
        var path=actor.getNavigation().getPath();var nodes=new ArrayList<List<Integer>>();if(path!=null)for(int i=0;i<path.getNodeCount();i++){var p=path.getNodePos(i);nodes.add(List.of(p.getX(),p.getY(),p.getZ()));}out.put("native_path",nodes);out.put("path_nodes",route==null?0:route.pathNodes());out.put("diagnostic",route==null?"":route.diagnostic());out.put("entity_ticks",actor.tickCount);out.put("on_ground",actor.onGround());out.put("path_length",route==null?0:route.pathLength());out.put("checked_chunks",route==null?0:route.checkedChunks());String file=route!=null && route.goal().mode()==MobilityPolicy.Mode.GO_NAMED_STORAGE?"storage-movement.jsonl":"movement.jsonl";String line=JSON.toJson(out)+"\n";rawFrames.computeIfAbsent(file,k->new ArrayList<>()).add(line);System.out.println("CC_D06_FRAME "+line.stripTrailing());}
    void finishEvidence(){try{for(var e:rawFrames.entrySet()){Path target=evidence.resolve(e.getKey()),temporary=evidence.resolve(e.getKey()+".complete");byte[] bytes=String.join("",e.getValue()).getBytes(java.nio.charset.StandardCharsets.UTF_8);try(var c=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){var b=ByteBuffer.wrap(bytes);while(b.hasRemaining())c.write(b);c.force(true);}Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);if(!Arrays.equals(bytes,Files.readAllBytes(target)))throw new IllegalStateException("raw native frame commit mismatch");}}catch(Exception e){throw new RuntimeException(e);}}
    static void write(Path path,Object value){try{Files.createDirectories(path.getParent());Files.writeString(path,JSON.toJson(value)+"\n");try(var c=FileChannel.open(path,StandardOpenOption.WRITE)){c.force(true);}}catch(Exception e){throw new RuntimeException(e);}}
    static void writeLine(Path path,Object value){try{Files.createDirectories(path.getParent());try(var c=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND)){var b=ByteBuffer.wrap((JSON.toJson(value)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));while(b.hasRemaining())c.write(b);c.force(true);}}catch(Exception e){throw new RuntimeException(e);}}
}
