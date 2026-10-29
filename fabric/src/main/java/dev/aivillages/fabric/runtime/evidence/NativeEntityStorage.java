package dev.aivillages.fabric.runtime.evidence;

import java.util.*;
import net.minecraft.server.level.ServerLevel;

/** Registration by the actual level EntityStorage; never opens a second world writer. */
public final class NativeEntityStorage {
    private static final Map<ServerLevel,EntityStorageEvidence> LEVELS=new WeakHashMap<>();
    private NativeEntityStorage(){}
    public static synchronized void register(ServerLevel level,EntityStorageEvidence owner) {
        LEVELS.putIfAbsent(level,owner); // The level constructs its own storage before exposing itself.
    }
    public static synchronized EntityStorageEvidence get(ServerLevel level){return LEVELS.get(level);}
}
