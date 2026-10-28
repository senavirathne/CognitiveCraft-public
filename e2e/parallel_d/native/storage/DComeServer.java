package dev.aivillages.e2e.paralleld;

import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Only the selected isolated client-test mod registers these commands and its explicit test owner. */
public final class DComeServer implements ModInitializer {
    private DComeScene scene;
    @Override public void onInitialize(){DTestAuthority.acquireTaggedWorld();
        CommandRegistrationCallback.EVENT.register((dispatcher,context,env)->dispatcher.register(Commands.literal("d-come")
            .then(Commands.literal("prepare").executes(c->{if(c.getSource().getEntity()!=null)throw new SecurityException("test console construction only");if(scene!=null)throw new IllegalStateException("one immutable scene");var server=c.getSource().getServer();var p=server.getPlayerList().getPlayerByName("PlayerD_A");if(p==null)throw new IllegalStateException("real principal must be connected");var root=Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR")).resolve("server/d06");scene=new DComeScene(server.overworld(),new BlockPos(0,201,0),p,root,"D06-CL01");DComeScene.write(root.resolve("ready.json"),Map.of("actor",scene.actor.getUUID().toString(),"principal",p.getUUID().toString(),"seed",scene.level.getSeed()));return 1;}))
            .then(Commands.literal("come").executes(c->{var p=c.getSource().getPlayerOrException();if(scene==null || scene.route!=null)throw new IllegalStateException("one admitted route");scene.accept(p);c.getSource().sendSuccess(()->Component.literal("D06_ACCEPTED "+DComeScene.JSON.toJson(scene.route.goal().endpoint())),false);return 1;}))));
        ServerTickEvents.END_SERVER_TICK.register(server->{if(scene!=null && scene.route!=null && !scene.route.observation().terminal()){var result=scene.tick();if(result.terminal()){scene.finishEvidence();DComeScene.write(scene.evidence.resolve("terminal.json"),result);}}});
    }
}
