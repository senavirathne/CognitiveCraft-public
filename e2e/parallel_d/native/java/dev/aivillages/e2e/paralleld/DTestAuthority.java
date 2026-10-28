package dev.aivillages.e2e.paralleld;

import java.util.UUID;

/** Test-mod-only capability. There is no release factory and no Jobs/effect receipt. */
final class DTestAuthority {
    private final UUID world;
    private DTestAuthority(UUID world) { this.world = world; }
    static DTestAuthority acquireTaggedWorld() {
        String tag = System.getProperty("cognitivecraft.parallelD.fixtureWorld");
        if (tag == null) throw new SecurityException("explicit isolated D test-world tag required");
        return new DTestAuthority(UUID.fromString(tag));
    }
    UUID world() { return world; }
}
