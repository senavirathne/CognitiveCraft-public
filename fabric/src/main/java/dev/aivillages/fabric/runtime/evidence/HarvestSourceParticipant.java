package dev.aivillages.fabric.runtime.evidence;

/** Association with a real chunk. The state never loads a chunk or authorizes an effect. */
public interface HarvestSourceParticipant {
    HarvestSourceState cognitivecraft$harvestSources();
    void cognitivecraft$harvestSources(HarvestSourceState state);
}
