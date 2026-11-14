package dev.aivillages.e2e.world;

import com.google.gson.*;
import dev.aivillages.e2e.ClientDriver;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import java.nio.file.*;
import java.util.*;

/** Agent-owned recovery controls. A respawn sends the normal connected-client packet; no server mutation. */
public final class WorldClient implements ClientModInitializer {
    private static final Gson JSON=new Gson();
    private final Path box=Path.of(System.getenv("COGNITIVECRAFT_E2E_DIR"))
            .resolve("clients/"+System.getenv("COGNITIVECRAFT_E2E_PLAYER"));
    private String last="";
    @Override public void onInitializeClient() {
        try { if(Files.exists(box.resolve("world-request.json")))
            last=JsonParser.parseString(Files.readString(box.resolve("world-request.json"))).getAsJsonObject().get("id").getAsString();
        } catch(Exception ignored) { }
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                Path path=box.resolve("world-request.json"); if(!Files.exists(path) || Files.size(path)>4096)return;
                var request=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                String id=request.get("id").getAsString(); if(id.equals(last))return; last=id;
                String action=request.get("action").getAsString();
                if(action.equals("respawn")) {
                    if(mc.player!=null && !mc.player.isAlive()) {
                        mc.player.connection.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                        ClientDriver.event("world-native-respawn-packet",Map.of("player",mc.player.getUUID().toString()));
                    }
                } else if(!action.equals("state"))throw new IllegalArgumentException("Bounded world client action");
                var result=Map.of("play",mc.player!=null,"alive",mc.player!=null && mc.player.isAlive(),
                        "health",mc.player==null?0:mc.player.getHealth());
                Path temporary=box.resolve("world-response.tmp");
                Files.writeString(temporary,JSON.toJson(Map.of("id",id,"result",result)));
                Files.move(temporary,box.resolve("world-response.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            } catch(Exception failure) { ClientDriver.event("world-client-control-error",failure.toString()); }
        });
    }
}
