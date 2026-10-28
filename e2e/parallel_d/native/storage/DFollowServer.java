package dev.aivillages.e2e.paralleld;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
public final class DFollowServer implements ModInitializer {
 private DFollowScene scene;
 public void onInitialize(){DTestAuthority.acquireTaggedWorld();CommandRegistrationCallback.EVENT.register((dispatcher,context,env)->dispatcher.register(Commands.literal("d-follow")
 .then(Commands.literal("prepare").executes(c->{if(c.getSource().getEntity()!=null || scene!=null)throw new SecurityException("one test console arena");var server=c.getSource().getServer();var p=server.getPlayerList().getPlayerByName("PlayerD_A");if(p==null)throw new IllegalStateException("connected principal");scene=new DFollowScene(server.overworld(),new BlockPos(0,201,0),p,Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR")).resolve("server/d07"),"D07-CL01",false);DComeScene.write(scene.evidence.resolve("ready.json"),Map.of("actor",scene.actor.getUUID().toString(),"principal",p.getUUID().toString(),"seed",scene.level.getSeed()));return 1;}))
 .then(Commands.literal("start").executes(c->{if(c.getSource().getPlayerOrException()!=scene.principal || scene.route!=null && !scene.route.state().terminal())throw new SecurityException("new explicit request after terminal only");scene.start();DComeScene.write(scene.evidence.resolve("accepted-"+scene.ordinal+".json"),scene.route.goal());return 1;}))
 .then(Commands.literal("stop").executes(c->{if(c.getSource().getPlayerOrException()!=scene.principal)throw new SecurityException("same principal");scene.stop();DComeScene.write(scene.evidence.resolve("stopped.json"),scene.route.state());return 1;}))));
 ServerTickEvents.END_SERVER_TICK.register(server->{if(scene!=null && scene.route!=null){scene.tick();if(scene.route.state().terminal())DComeScene.write(scene.evidence.resolve("terminal-"+scene.ordinal+".json"),scene.route.state());}});}
}
