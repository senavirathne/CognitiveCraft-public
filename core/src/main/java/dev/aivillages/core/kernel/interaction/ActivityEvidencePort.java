package dev.aivillages.core.kernel.interaction;

import java.util.Optional;

/** Proposed read-only seam. J03 supplies the actual A adapter; C has no runtime writer or registration. */
@FunctionalInterface
public interface ActivityEvidencePort {
    Optional<BlockerProjection.Evidence> read(BlockerProjection.ActivityRef reference);
}
