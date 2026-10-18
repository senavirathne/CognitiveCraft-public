package dev.aivillages.fabric;

import dev.aivillages.core.kernel.ResourceDelivery;
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
            default -> throw new IllegalArgumentException("Unregistered resource item");
        };
    }
    static Block log(ResourceDelivery.ItemIdentity identity) {
        return switch (identity.id()) {
            case "minecraft:oak_log" -> Blocks.OAK_LOG;
            case "minecraft:birch_log" -> Blocks.BIRCH_LOG;
            default -> throw new IllegalArgumentException("Unregistered log block");
        };
    }
}
