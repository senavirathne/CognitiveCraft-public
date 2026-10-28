package dev.aivillages.fabric.runtime.evidence;

import net.minecraft.nbt.CompoundTag;

/** Immutable metadata captured with the same SerializableChunkData as the source blocks. */
public interface HarvestSourceRecord {
    void cognitivecraft$harvestRecord(CompoundTag metadata,boolean nativeStorage);
}
