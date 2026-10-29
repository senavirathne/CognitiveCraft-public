package dev.aivillages.e2e.paralleld;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.ChunkEntities;
/** Explicit test-only native entity-chunk write fault, never production save authority. */
public final class DComponentSaveCut {
 private static ServerLevel owner;private static UUID recipient;private static ChunkPos chunk;private static Path evidence;private static int withheld;
 public static void arm(ServerLevel level,Entity entity,Path path){DTestAuthority.acquireTaggedWorld();if(!level.getServer().isSameThread() || owner!=null || entity.level()!=level)throw new IllegalStateException("single native fixture cut");owner=level;recipient=entity.getUUID();chunk=entity.chunkPosition();evidence=path;}
 public static boolean withhold(ServerLevel level,ChunkEntities<Entity> entities){if(owner!=level || !entities.getPos().equals(chunk))return false;boolean found=entities.getEntities().anyMatch(e->e.getUUID().equals(recipient));if(!found)throw new IllegalStateException("native recipient absent at save boundary");var r=new TreeMap<String,Object>();r.put("event","NATIVE_ENTITY_CHUNK_WRITE_WITHHELD");r.put("recipient",recipient);r.put("chunk",List.of(chunk.x(),chunk.z()));r.put("tick",level.getGameTime());r.put("withheld",++withheld);r.put("integration_root",null);r.put("effect_id",null);r.put("save_proof",null);DComeScene.writeLine(evidence.resolve("save-cuts.jsonl"),r);System.out.println("CC_D10_SAVE_CUT "+DComeScene.JSON.toJson(r));return true;}
 public static int withheld(){return withheld;}public static boolean armed(){return owner!=null;}
}
