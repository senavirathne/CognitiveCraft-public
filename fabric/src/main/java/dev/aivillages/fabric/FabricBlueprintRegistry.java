package dev.aivillages.fabric;

import dev.aivillages.core.kernel.BlueprintValidator;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.Property;
import java.util.*;

/** Capture on the game thread; only immutable registry values cross into core/storage workers. */
public final class FabricBlueprintRegistry {
    private FabricBlueprintRegistry() { }
    public static BlueprintValidator.Registry capture() {
        var definitions=new TreeMap<String,BlueprintValidator.Definition>();
        add(definitions,Blocks.OAK_PLANKS,BlueprintValidator.Kind.SINGLE);
        add(definitions,Blocks.OAK_SLAB,BlueprintValidator.Kind.SLAB);
        add(definitions,Blocks.OAK_DOOR,BlueprintValidator.Kind.DOOR);
        add(definitions,Blocks.TORCH,BlueprintValidator.Kind.SINGLE);
        return new BlueprintValidator.Registry("minecraft-26.3",definitions);
    }
    private static void add(Map<String,BlueprintValidator.Definition> definitions,Block block,BlueprintValidator.Kind kind) {
        String id=BuiltInRegistries.BLOCK.getKey(block).toString();
        String item=BuiltInRegistries.ITEM.getKey(block.asItem()).toString();
        var values=new TreeMap<String,Set<String>>();var defaults=new TreeMap<String,String>();
        for(Property<?> property:block.getStateDefinition().getProperties())capture(property,block,values,defaults);
        String meaning=dev.aivillages.core.kernel.Blueprint.digest("minecraft-26.3:"+id+":"+block.getClass().getName()
                +":"+block.asItem().getClass().getName()+":placement-policy-"+BlueprintValidator.PLACEMENT_POLICY);
        definitions.put(id,new BlueprintValidator.Definition(id,item,kind,values,defaults,meaning));
    }
    private static <T extends Comparable<T>> void capture(Property<T> property,Block block,
            Map<String,Set<String>> values,Map<String,String> defaults) {
        var names=new TreeSet<String>();
        for(T value:property.getPossibleValues())names.add(property.getName(value));
        values.put(property.getName(),Set.copyOf(names));
        defaults.put(property.getName(),property.getName(block.defaultBlockState().getValue(property)));
    }
}
