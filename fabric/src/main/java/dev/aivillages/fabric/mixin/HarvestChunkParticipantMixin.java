package dev.aivillages.fabric.mixin;

import dev.aivillages.fabric.runtime.evidence.*;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.*;
import java.util.Objects;

@Mixin(ChunkAccess.class)
public abstract class HarvestChunkParticipantMixin implements HarvestSourceParticipant {
    @Unique private HarvestSourceState cognitivecraft$harvestSources;
    @Override public HarvestSourceState cognitivecraft$harvestSources(){
        if(cognitivecraft$harvestSources==null)cognitivecraft$harvestSources=new HarvestSourceState();return cognitivecraft$harvestSources;
    }
    @Override public void cognitivecraft$harvestSources(HarvestSourceState state){cognitivecraft$harvestSources=Objects.requireNonNull(state);}
}
