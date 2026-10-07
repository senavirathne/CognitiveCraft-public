package dev.aivillages.fabric;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.util.*;
/** Server-thread crop leases. */
public final class Reservations {
    private record Lease(UUID owner,long until) { }
    private final Map<String,Lease> leases=new HashMap<>();
    public boolean acquire(ServerLevel level,BlockPos pos,UUID owner,long tick) {
        String key=level.dimension().identifier()+":"+pos.asLong();var existing=leases.get(key);
        if(existing!=null && existing.until()>tick && !existing.owner().equals(owner))return false;
        leases.put(key,new Lease(owner,tick+200));return true;
    }
    public void release(UUID owner){leases.values().removeIf(l->l.owner().equals(owner));}
}
