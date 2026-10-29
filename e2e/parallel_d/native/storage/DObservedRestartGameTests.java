package dev.aivillages.e2e.paralleld;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import dev.aivillages.core.kernel.logistics.RecipientDeliveryPolicy.Source;
import dev.aivillages.core.kernel.logistics.TransferObservation.Status;
/** Fresh native processes observe actual partial saves. No original owner is reconstructed. */
public final class DObservedRestartGameTests {
 static final BlockPos COURIER=new BlockPos(128,81,160),HANDOFF=new BlockPos(140,81,128);
 @GameTest(maxTicks=10000)
 public void observedNativeSavesRemainUnknownWithoutOriginalOwnerSettlement(GameTestHelper h){
  var level=h.getLevel();var root=Path.of(System.getProperty("cognitivecraft.parallelD.evidence"));var shared=Path.of(System.getProperty("cognitivecraft.parallelD.shared")).resolve("D10-observed");boolean read=System.getProperty("cognitivecraft.parallelD.phase").endsWith("read");
  for(int x=8;x<=10;x++)for(int z=8;z<=11;z++){level.setChunkForced(x,z,true);level.getChunkAt(new BlockPos(x*16,81,z*16));}
  if(read){h.runAfterDelay(40,()->{if(DComponentSaveCut.armed())throw new IllegalStateException("fresh process inherited save fault");var d=DObservedParticipants.load(shared.resolve("participants.json"));DObservedParticipants.capture(level,d,"FRESH_RESTORED_UNKNOWN",root);h.runAfterDelay(100,()->{var vectors=DObservedParticipants.capture(level,d,"NO_AUTOMATIC_COMPONENT_REPLAY",root);h.assertTrue(vectors.get("courier").equals(List.of(6,7,2)) && vectors.get("handoff").equals(List.of(5,1)),"actual independent native restored vectors");h.succeed();});});return;}
  var p=h.makeMockServerPlayerInLevel();h.runAfterDelay(40,()->DChestCourierScene.open(level,COURIER,p,shared.resolve("courier"),"D10-RESTART-COURIER",c->DChestCourierScene.open(level,HANDOFF,p,shared.resolve("handoff"),"D10-RESTART-HANDOFF",c,b->h.runAfterDelay(40,()->{
   var hand=new DVillagerHandoffScene(b,root,"D10-GT01");c.reset("D10",Items.OAK_PLANKS,4,10,3,2);hand.reset("D10",Source.EXISTING_CARRIER,9,false);var descriptor=DObservedParticipants.descriptor(c,hand);DComeScene.write(shared.resolve("participants.json"),descriptor);DComeScene.write(root.resolve("participants.json"),descriptor);level.getServer().saveEverything(false,true,true);DObservedParticipants.capture(level,descriptor,"SAVED_BASELINE",root);
   h.assertTrue(c.withdraw().status()==Status.LIVE_WITHDRAWN,"actual native pickup");h.assertTrue(hand.prepare().cargo()!=null && hand.handoff().status()==Status.LIVE_DEPOSITED,"actual native handoff");h.assertTrue(!c.arena.actor.chunkPosition().equals(hand.receiver.chunkPosition()) && !hand.base.arena.actor.chunkPosition().equals(hand.receiver.chunkPosition()),"fault targets only recipient entity chunk");DComponentSaveCut.arm(level,hand.receiver,root);level.getServer().saveEverything(false,true,true);h.assertTrue(DComponentSaveCut.withheld()>0,"native write boundary witnessed");DObservedParticipants.capture(level,descriptor,"LIVE_AFTER_NATIVE_SAVE_CUT",root);DComeScene.write(root.resolve("kill-ready.json"),Map.of("expected_disk_courier",List.of(6,7,2),"expected_disk_handoff",List.of(5,1),"receipt",false));
  }))));
 }
}
