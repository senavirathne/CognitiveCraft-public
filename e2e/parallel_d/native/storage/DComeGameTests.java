package dev.aivillages.e2e.paralleld;

import java.nio.file.Path;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import dev.aivillages.core.kernel.logistics.MobilityObservation.*;

public final class DComeGameTests {
    @GameTest(maxTicks=650,structure="cognitivecraft_parallel_d_tests:mobility")
    public void capturedLocationAroundObstacleWithoutTeleportOrChunkLoads(GameTestHelper h){
        var player=h.makeMockServerPlayerInLevel();var origin=h.absolutePos(new BlockPos(0,2,0));
        var scene=new DComeScene(h.getLevel(),origin,player,Path.of(System.getProperty("cognitivecraft.parallelD.evidence")),"D06-GT01");
        h.runAfterDelay(10,()->{player.setPos(origin.getX()+14.5,origin.getY(),origin.getZ()+7.5);scene.accept(player);player.setPos(origin.getX()+14.5,origin.getY(),origin.getZ()+18.5);scene.frame("PLAYER_MOVED",null,0);h.runAfterDelay(1,()->step(h,scene,player));});
    }
    private void step(GameTestHelper h,DComeScene scene,net.minecraft.server.level.ServerPlayer player){
        var observation=scene.tick();
        if(!observation.terminal()){h.runAfterDelay(1,()->step(h,scene,player));return;}
        h.assertTrue(observation.status()==Status.ARRIVED,"permitted obstacle must be traversed: "+observation);
        h.assertTrue(observation.feet().distance(scene.route.goal().endpoint())<=1.5,"old captured endpoint");
        h.assertTrue(scene.actor.distanceToSqr(player)>25,"must not chase moved player");
        h.assertTrue(scene.level.getChunkSource().getForceLoadedChunks().size()==scene.forcedBefore,"no forced chunk ticket");
        // Real unloaded destination negative control, not a second scenario or a player teleport in production.
        var far=new BlockPos(200000,scene.origin.getY(),200000);boolean before=scene.level.hasChunkAt(far);h.assertTrue(!before,"unloaded control actually absent");player.setPos(far.getX()+.5,far.getY(),far.getZ()+.5);scene.accept(player);var unloaded=scene.tick();h.assertTrue(unloaded.blocker()==Blocker.UNLOADED,"exact unloaded blocker");h.assertTrue(!scene.level.hasChunkAt(far),"adapter did not load destination");
        DComeScene.write(scene.evidence.resolve("controls.json"),Map.of("unloaded_before",before,"unloaded_after",scene.level.hasChunkAt(far),"forced_before",scene.forcedBefore,"forced_after",scene.level.getChunkSource().getForceLoadedChunks().size(),"blocker",unloaded.blocker().name(),"actor_no_ai",scene.actor.isNoAi(),"native_class",scene.actor.getClass().getName()));player.setPos(scene.origin.getX()+14.5,scene.origin.getY(),scene.origin.getZ()+18.5);DComeStorageProof.run(h,scene,()->{scene.finishEvidence();h.succeed();});
    }
}
