package dev.aivillages.fabric;

import java.util.Arrays;
import dev.aivillages.core.kernel.RecipeProduction;
import dev.aivillages.core.kernel.ResourceDelivery;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Closed native identities for registered resource descriptors. No tag or fallback material. */
final class FabricResourceItems {
    private FabricResourceItems() { }
    static Item item(ResourceDelivery.ItemIdentity identity) {
        return switch (identity.id()) {
            case "minecraft:oak_log" -> Items.OAK_LOG;
            case "minecraft:birch_log" -> Items.BIRCH_LOG;
            case "minecraft:oak_planks" -> Items.OAK_PLANKS;
            case "minecraft:birch_planks" -> Items.BIRCH_PLANKS;
            case "minecraft:stick" -> Items.STICK;
            case "minecraft:wooden_axe" -> Items.WOODEN_AXE;
            case "minecraft:crafting_table" -> Items.CRAFTING_TABLE;
            default -> additionalPlankItem(identity);
        };
    }
    private static Item additionalPlankItem(ResourceDelivery.ItemIdentity identity) {
        // This lookup is restricted to exact input/output identities of the finite recipe catalogue.
        if (Arrays.stream(RecipeProduction.PlankMaterial.values())
                .noneMatch(material -> material.inputs().contains(identity.id()) || material.output().equals(identity.id())))
            throw new IllegalArgumentException("Unregistered resource item");
        var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(identity.id()));
        if (item == Items.AIR) throw new IllegalArgumentException("Missing installed resource item");
        return item;
    }
    static Block log(ResourceDelivery.ItemIdentity identity) {
        return switch (identity.id()) {
            case "minecraft:oak_log" -> Blocks.OAK_LOG;
            case "minecraft:birch_log" -> Blocks.BIRCH_LOG;
            default -> throw new IllegalArgumentException("Unregistered log block");
        };
    }
}
