package dev.aivillages.fabric.storage;

import java.util.UUID;

/** Internal mixin bridge. A marker is identity data and confers no inventory access. */
public interface StorageMarkerAccess {
    record Marker(UUID incarnation,long generation) {
        public Marker { if (incarnation==null || generation<1 || generation>9007199254740991L) throw new IllegalArgumentException("marker"); }
    }
    Marker cognitivecraft$marker();
    long cognitivecraft$lifecycle();
    boolean cognitivecraft$loadedFromTag();
    boolean cognitivecraft$malformedMarker();
    void cognitivecraft$writeMarker(Marker marker,StorageIncarnationAdapter.WritePermit permit);
}
