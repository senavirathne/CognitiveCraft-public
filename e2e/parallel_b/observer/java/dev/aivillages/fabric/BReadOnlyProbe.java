package dev.aivillages.fabric;

import java.nio.file.*;
import com.google.gson.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;

/** Bounded test-private native queries. Never loads chunks or mutates native/runtime state. */
public final class BReadOnlyProbe implements ModInitializer {
    private String last="";
    @Override public void onInitialize(){String directory=System.getenv("COGNITIVECRAFT_E2E_DIR");if(directory==null)return;var root=Path.of(directory);ServerTickEvents.END_SERVER_TICK.register(server->{var path=root.resolve("server/b-probe.json");if(!Files.exists(path))return;
        try{String raw=Files.readString(path);if(raw.length()>2048)throw new IllegalArgumentException("Probe bound");var request=JsonParser.parseString(raw).getAsJsonObject();String id=request.get("id").getAsString();if(id.equals(last))return;var positions=request.getAsJsonArray("positions");if(positions.size()>8)throw new IllegalArgumentException("Probe positions bound");var result=new JsonObject();result.addProperty("id",id);result.addProperty("gameTick",server.overworld().getGameTime());result.addProperty("worldEffects",0);var rows=new JsonArray();
            for(var p:positions){var a=p.getAsJsonArray();var pos=new BlockPos(a.get(0).getAsInt(),a.get(1).getAsInt(),a.get(2).getAsInt());var row=new JsonObject();row.add("position",a);boolean loaded=server.overworld().hasChunkAt(pos);row.addProperty("loaded",loaded);if(loaded){row.addProperty("block",server.overworld().getBlockState(pos).toString());var entity=server.overworld().getBlockEntity(pos);row.addProperty("identity",entity==null?"absent":entity.getClass().getName()+":"+System.identityHashCode(entity));if(entity instanceof Container c){row.addProperty("fingerprint",FabricRecipeTransactions.fingerprint(c));var counts=new JsonObject();for(int i=0;i<c.getContainerSize();i++){var stack=c.getItem(i);if(stack.isEmpty())continue;String key=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();counts.addProperty(key,(counts.has(key)?counts.get(key).getAsInt():0)+stack.getCount());}row.add("itemCounts",counts);}}rows.add(row);}result.add("positions",rows);var temp=root.resolve("server/b-probe-response.tmp");Files.writeString(temp,result.toString());Files.move(temp,root.resolve("server/b-probe-response.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);last=id;
        }catch(Exception e){throw new IllegalStateException("Required read-only B probe failed",e);}});}
}
