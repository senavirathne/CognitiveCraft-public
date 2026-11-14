package dev.aivillages.fabric.runtime.evidence;

/** Data-only native storage access, never a gameplay capability. */
public interface EntityStorageEvidenceOwner {
    EntityStorageEvidence cognitivecraft$entityEvidence();
}
