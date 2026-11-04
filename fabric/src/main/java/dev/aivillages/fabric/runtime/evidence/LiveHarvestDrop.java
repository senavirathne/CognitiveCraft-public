package dev.aivillages.fabric.runtime.evidence;

import java.util.function.BooleanSupplier;

/** Actual native drop's process-local custody; saved evidence cannot populate this owner. */
public interface LiveHarvestDrop {
    void cognitivecraft$liveCustody(BooleanSupplier owner);
    boolean cognitivecraft$liveCustody();
}
