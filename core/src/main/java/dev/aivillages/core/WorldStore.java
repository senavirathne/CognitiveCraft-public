package dev.aivillages.core;
import java.io.IOException;
import java.nio.file.*;

/** Atomic replacement with previous-generation backup; fails closed on corrupt state. */
public final class WorldStore {
    private final Path path;
    public WorldStore(Path path) { this.path = path; }
    public WorldData load() throws IOException {
        if (!Files.exists(path)) return new WorldData();
        try { return decode(Files.readString(path)); }
        catch (RuntimeException ex) { throw new IOException("Invalid state. Restore the .bak file before continuing", ex); }
    }
    private WorldData decode(String text) {
        WorldData d = Json.GSON.fromJson(Json.object(text, 8000000), WorldData.class);
        if (d.schemaVersion != 1 || d.agents == null || d.skills == null || d.usage == null || d.villageMemory == null || d.agents.size() > 64 || d.skills.size() > 64)
            throw new IllegalArgumentException("Unsupported or invalid world data");
        for (var entry : d.agents.entrySet()) {
            var a = entry.getValue();
            if (a == null || !entry.getKey().equals(a.id) || a.home == null || a.home.dimension() == null || a.name == null
                || a.memories == null || a.trust == null || a.form == null || a.profession == null || a.lastFoodEvidence == null)
                throw new IllegalArgumentException("Invalid agent");
            java.util.UUID.fromString(a.id); java.util.UUID.fromString(a.entityId); java.util.UUID.fromString(a.owner);
        }
        for (var s : d.skills.values()) PlanCodec.validate(s.plan);
        return d;
    }
    public void save(WorldData data) throws IOException { saveJson(Json.GSON.toJson(data)); }
    public synchronized void saveJson(String text) throws IOException {
        decode(text); Files.createDirectories(path.getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".tmp"); Files.writeString(temp, text);
        try (var channel = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
        if (Files.exists(path)) Files.copy(path, path.resolveSibling(path.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
    }
}
