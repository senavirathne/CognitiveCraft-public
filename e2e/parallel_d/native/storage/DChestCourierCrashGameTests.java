package dev.aivillages.e2e.paralleld;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.*;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.util.ProblemReporter;
public final class DChestCourierCrashGameTests {
 static final BlockPos ORIGIN=new BlockPos(128,81,128);
 @GameTest(maxTicks=10000)
 public void killedCarrierHasNoAutomaticRecoveryOrSaveReceipt(GameTestHelper h){var level=h.getLevel();var root=Path.of(System.getProperty("cognitivecraft.parallelD.evidence"));boolean read=System.getProperty("cognitivecraft.parallelD.phase").endsWith("read");
  for(int x=8;x<=9;x++)for(int z=8;z<=9;z++){level.setChunkForced(x,z,true);level.getChunkAt(new BlockPos(x*16,81,z*16));}
  if(read){h.runAfterDelay(40,()->{var entity=level.getEntityInAnyDimension(DChestCourierScene.actorId("D08-CRASH",ORIGIN));h.assertTrue(entity instanceof Villager,"saved carrier restored from actual native entity file");var actor=(Villager)entity;var source=(Container)level.getBlockEntity(ORIGIN.offset(2,0,7));var destination=(Container)level.getBlockEntity(ORIGIN.offset(17,0,7));h.assertTrue(source.getItem(0).getCount()==10 && actor.getInventory().getItem(0).getCount()==3 && actor.getInventory().getItem(1).isEmpty() && destination.getItem(0).getCount()==2,"observed saved baseline differs from pre-crash live custody");write(root,"RESTORED_UNKNOWN_CUSTODY",level,actor,source,destination);h.succeed();});return;}
  var player=h.makeMockServerPlayerInLevel();DChestCourierScene.open(level,ORIGIN,player,root,"D08-CRASH",s->h.runAfterDelay(20,()->{s.reset("CRASH",Items.OAK_PLANKS,4,10,3,2);level.getServer().saveEverything(false,true,true);write(root,"SAVED_BEFORE",level,s.arena.actor,s.source(),s.destination());var result=s.withdraw();h.assertTrue(result.status()==dev.aivillages.core.kernel.logistics.TransferObservation.Status.LIVE_WITHDRAWN,"real in-flight pickup before kill");write(root,"LIVE_IN_FLIGHT",level,s.arena.actor,s.source(),s.destination());DComeScene.write(root.resolve("kill-ready.json"),Map.of("cut","AFTER_LIVE_WITHDRAW_BEFORE_PARTICIPANT_SAVE","source",6,"carrier",7,"destination",2,"receipt",false));}));
 }
 static void write(Path root,String event,net.minecraft.server.level.ServerLevel level,Villager actor,Container source,Container destination){var output=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,level.registryAccess());if(!actor.save(output))throw new IllegalStateException("native entity serialization");var src=(net.minecraft.world.level.block.entity.BlockEntity)source;var dst=(net.minecraft.world.level.block.entity.BlockEntity)destination;var frame=new TreeMap<String,Object>();frame.put("event",event);frame.put("case","D08-GT01");frame.put("world",DTestAuthority.acquireTaggedWorld().world());frame.put("tick",level.getGameTime());frame.put("source",source.getItem(0).getCount());int n=0;for(int i=0;i<actor.getInventory().getContainerSize();i++)n+=actor.getInventory().getItem(i).getCount();frame.put("carrier",n);frame.put("destination",destination.getItem(0).getCount());frame.put("source_nbt",src.saveWithFullMetadata(level.registryAccess()).toString());frame.put("destination_nbt",dst.saveWithFullMetadata(level.registryAccess()).toString());frame.put("carrier_nbt",output.buildResult().toString());frame.put("actor",actor.getUUID());frame.put("native_navigation_active",!actor.getNavigation().isDone());frame.put("integration_root",null);frame.put("save_proof",null);DComeScene.writeLine(root.resolve("crash.jsonl"),frame);System.out.println("CC_D08_CRASH "+DComeScene.JSON.toJson(frame));}
}
