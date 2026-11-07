package dev.aivillages.fabric.runtime.evidence;

import java.util.*;
import net.minecraft.world.entity.item.ItemEntity;

/** Optional, bounded native damage observations; never supplies delivery or custody authority. */
public final class LifecycleItemDamage {
    private static final boolean ENABLED="true".equalsIgnoreCase(System.getenv("COGNITIVECRAFT_LIFECYCLE_TRACE"));
    public record Fact(UUID entity,String source,boolean accepted,boolean removed,String removal,long tick) { }
    private static final Map<UUID,List<Fact>> watched=new HashMap<>();
    private LifecycleItemDamage() { }
    public static void watch(Collection<UUID> entities) {
        if(!ENABLED)return;
        if(entities.size()>16)throw new IllegalArgumentException("Finite native damage watch");
        watched.clear();for(var id:entities)watched.put(id,new ArrayList<>());
    }
    public static void observed(ItemEntity entity,String source,boolean accepted) {
        if(!ENABLED)return;var rows=watched.get(entity.getUUID());if(rows==null||rows.size()==8)return;
        rows.add(new Fact(entity.getUUID(),source,accepted,entity.isRemoved(),String.valueOf(entity.getRemovalReason()),entity.level().getGameTime()));
    }
    public static List<Fact> facts() {
        return watched.values().stream().flatMap(Collection::stream).toList();
    }
}
