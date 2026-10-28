package dev.aivillages.e2e.paralleld;
import java.util.*;
import java.nio.file.*;
import dev.aivillages.core.kernel.logistics.*;
import dev.aivillages.core.kernel.logistics.MobilityPolicy.*;
import dev.aivillages.fabric.logistics.FollowObservationAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.npc.villager.Villager;
final class DFollowScene {
 final DComeScene arena;final ServerLevel level;final Villager actor;final ServerPlayer principal;final Path evidence;final String caseId;final Actor identity;
 final FollowObservationAdapter adapter;FollowObservationAdapter.Route route;boolean connected=true,authorized=true,reserved=false;long deadline;int ordinal=0;
 DFollowScene(ServerLevel level,BlockPos origin,ServerPlayer principal,Path evidence,String caseId,boolean mock){
  this.level=level;this.principal=principal;this.evidence=evidence;this.caseId=caseId;arena=new DComeScene(level,origin,principal,evidence,caseId);actor=arena.actor;
  identity=arena.identity;deadline=level.getGameTime()+1200;
  FollowObservationAdapter.Authority auth=(key,tick)->new FollowObservationAdapter.Control(identity,authorized,reserved,deadline,128);
  adapter=mock?new FollowObservationAdapter(level,actor,auth,id->connected?principal:null):new FollowObservationAdapter(level,actor,auth);
 }
 void start(){deadline=level.getGameTime()+1200;route=adapter.start(UUID.nameUUIDFromBytes((caseId+"-"+(++ordinal)).getBytes(java.nio.charset.StandardCharsets.UTF_8)),identity,principal);frame("ACCEPTED",0);}
 FollowState tick(){long start=System.nanoTime();var s=route.step();frame("STEP",System.nanoTime()-start);return s;}
 void stop(){route.cancel();frame("STOP_EVENT",0);}
 void frame(String event,long ns){var m=new TreeMap<String,Object>();m.put("case",caseId);m.put("event",event);m.put("tick",level.getGameTime());m.put("world",identity.world());m.put("principal",principal.getUUID());m.put("actor",actor.getUUID());m.put("seed",level.getSeed());m.put("native_feet",List.of(actor.getX(),actor.getY(),actor.getZ()));m.put("native_player",List.of(principal.getX(),principal.getY(),principal.getZ()));m.put("player_dimension",principal.level().dimension().identifier().toString());m.put("connected",caseId.equals("D07-GT01")?connected:level.getServer().getPlayerList().getPlayer(principal.getUUID())!=null);m.put("navigation_done",actor.getNavigation().isDone());m.put("state",route.state());m.put("path_nodes",route.pathNodes());m.put("path_length",route.pathLength());m.put("step_ns",ns);m.put("heap_used_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());m.put("forced_chunks",level.getChunkSource().getForceLoadedChunks().size());m.put("integration_root",null);m.put("save_proof",null);m.put("diagnostic",route.diagnostic());m.put("origin",List.of(arena.origin.getX(),arena.origin.getY(),arena.origin.getZ()));DComeScene.writeLine(evidence.resolve("follow.jsonl"),m);System.out.println("CC_D07_FRAME "+DComeScene.JSON.toJson(m));}
}
