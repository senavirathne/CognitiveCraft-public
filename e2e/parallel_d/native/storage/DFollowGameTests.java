package dev.aivillages.e2e.paralleld;
import java.nio.file.Path;
import java.util.*;
import dev.aivillages.core.kernel.logistics.FollowState.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
public final class DFollowGameTests {
 @GameTest(maxTicks=850,structure="cognitivecraft_parallel_d_tests:mobility")
 public void finiteFollowObstaclePauseOwnershipAndDimension(GameTestHelper h){var p=h.makeMockServerPlayerInLevel();var origin=h.absolutePos(new BlockPos(0,2,0));var s=new DFollowScene(h.getLevel(),origin,p,Path.of(System.getProperty("cognitivecraft.parallelD.evidence")),"D07-GT01",true);p.setPos(origin.getX()+14.5,origin.getY(),origin.getZ()+7.5);h.runAfterDelay(10,()->{s.start();step(h,s,0);});}
 private void step(GameTestHelper h,DFollowScene s,int n){
  if(n==230){s.principal.setPos(s.arena.origin.getX()+16.5,s.arena.origin.getY(),s.arena.origin.getZ()+18.5);s.frame("TARGET_MOVED",0);}
  var state=s.tick();h.assertTrue(!state.terminal(),"native positive follow: "+state+" "+s.route.diagnostic());
  if(n<480){h.runAfterDelay(1,()->step(h,s,n+1));return;}
  h.assertTrue(state.traveled()>12,"actual follow travel");h.assertTrue(s.actor.getX()>s.arena.origin.getX()+10,"around obstacle");
  s.connected=false;s.frame("DISCONNECT_EVENT",0);h.runAfterDelay(1,()->pause(h,s,1));
 }
 private void pause(GameTestHelper h,DFollowScene s,int n){var state=s.tick();h.assertTrue(s.actor.getNavigation().isDone(),"disconnect stops native navigation within one tick");if(n<100){h.assertTrue(!state.terminal(),"pause allowance");h.runAfterDelay(1,()->pause(h,s,n+1));return;}h.assertTrue(state.reason()==Reason.PAUSE_LIMIT,"100 paused ticks terminate");
  s.connected=true;s.principal.setPos(s.actor.getX()+1,s.actor.getY(),s.actor.getZ());s.start();s.reserved=true;h.assertTrue(s.tick().reason()==Reason.RESOURCE_RESERVED,"competing exclusive owner");s.reserved=false;
  s.start();var original=s.principal.level();s.principal.setServerLevel(s.level.getServer().getLevel(net.minecraft.world.level.Level.NETHER));h.assertTrue(s.tick().reason()==Reason.DIMENSION_CHANGED,"native dimension mismatch");s.principal.setServerLevel(original);
  s.start();s.authorized=false;h.assertTrue(s.tick().reason()==Reason.UNAUTHORIZED,"native authority revocation");s.authorized=true;
  s.start();s.principal.setHealth(0);h.assertTrue(s.tick().reason()==Reason.DEAD,"native player death");s.principal.setHealth(20);
  s.start();var far=new BlockPos(200000,s.arena.origin.getY(),200000);boolean before=s.level.hasChunkAt(far);h.assertTrue(!before,"native unloaded control absent");s.principal.setPos(far.getX()+.5,far.getY(),far.getZ()+.5);h.assertTrue(s.tick().reason()==Reason.UNLOADED && !s.level.hasChunkAt(far),"follow refuses without loading target");s.principal.setPos(s.actor.getX()+1,s.actor.getY(),s.actor.getZ());
  s.start();s.stop();h.assertTrue(s.route.state().reason()==Reason.CANCELLED && s.actor.getNavigation().isDone(),"STOP independent of provider");
  DComeScene.write(s.evidence.resolve("controls.json"),Map.of("competing_owner","RESOURCE_RESERVED","dimension","DIMENSION_CHANGED","revocation","UNAUTHORIZED","stop","CANCELLED","pause_ticks",100,"native_class",s.actor.getClass().getName(),"no_ai",s.actor.isNoAi(),"death","DEAD","unloaded","UNLOADED"));h.succeed();
 }
}
