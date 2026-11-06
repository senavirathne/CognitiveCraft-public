package dev.aivillages.fabric.runtime.evidence;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Original crop output with a descriptive origin and a separate process-local live owner. */
public interface LiveHarvestDrop {
    void cognitivecraft$liveCustody(UUID origin,BooleanSupplier owner);
    boolean cognitivecraft$blocksNativePickup(UUID actor);
}
