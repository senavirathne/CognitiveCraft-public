package dev.aivillages.fabric;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
/** Game-thread capture tokens only, never inventory or a recovery owner. */
public final class FabricFacilityIdentity {
    public static final int MAX_WATCHED=64;
    private static final Map<ServerLevel,Map<Long,String>> worlds=new WeakHashMap<>();
    private FabricFacilityIdentity() { }
    public static String capture(ServerLevel level,BlockPos pos) {
        if(!level.getServer().isSameThread())throw new IllegalStateException("Facility capture game thread");
        var rows=worlds.computeIfAbsent(level,key->new HashMap<>());long key=pos.asLong();
        if(!rows.containsKey(key)) {if(rows.size()>=MAX_WATCHED)return null;rows.put(key,"table:"+key+":"+UUID.randomUUID());}
        return rows.get(key);
    }
    public static void changed(ServerLevel level,BlockPos pos) {
        var rows=worlds.get(level);if(rows!=null && rows.containsKey(pos.asLong()))rows.put(pos.asLong(),"table:"+pos.asLong()+":"+UUID.randomUUID());
    }
}
