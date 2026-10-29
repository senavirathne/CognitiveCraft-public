package dev.aivillages.fabric.runtime.evidence;

import dev.aivillages.core.kernel.StrictJson;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.Marker;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.MarkerPhase;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

/**
 * Bounded data for a removed participant in its own native entity-chunk record.
 * Native EntityStorage retains this metadata through empty saves. Neither a live declaration
 * nor bare absence is saved proof; the still-inactive producer must bind every real departure
 * chunk, original authority, and actual pickup result.
 */
public final class EntityChunkRemovalState {
    public static final String TAG="cognitivecraft_entity_removals";
    public static final int MAX_REMOVALS=64,MAX_TEXT=60_000;
    private static final String ORIGINAL=TAG+"_original";
    public record Removal(UUID entity,UUID incarnation,long revision,Marker marker) {
        public Removal {
            Objects.requireNonNull(entity);Objects.requireNonNull(incarnation);Objects.requireNonNull(marker);
            if(revision<0 || marker.phase()!=MarkerPhase.RESULT)throw new IllegalArgumentException("Entity removal result binding");
        }
    }
    /** Defensive copies keep a detached native save frame independent of later live changes. */
    public record Capture(CompoundTag metadata,boolean supported) {
        public Capture {metadata=Objects.requireNonNull(metadata).copy();}
        @Override public CompoundTag metadata(){return metadata.copy();}
    }
    private final NavigableMap<String,Removal> rows=new TreeMap<>();
    private final Set<String> restored=new HashSet<>();
    private final String dimension;
    private final int chunkX,chunkZ;
    private boolean valid=true,quarantined;
    private CompoundTag original;
    private Thread gameThread;
    public EntityChunkRemovalState(String dimension,ChunkPos chunk) {
        if(dimension==null || dimension.length()>128 || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+"))throw new IllegalArgumentException("Entity chunk dimension");
        this.dimension=dimension;chunkX=Objects.requireNonNull(chunk).x();chunkZ=chunk.z();
    }
    private void thread(){if(gameThread==null)gameThread=Thread.currentThread();if(gameThread!=Thread.currentThread())throw new IllegalStateException("Entity removal game-thread owner");}
    public boolean valid(){return valid;}
    public int size(){return rows.size();}
    /** Descriptive declaration only; this method never marks a row as restored from native storage. */
    public void record(Removal removal) {
        thread();if(!valid)throw new IllegalStateException("Invalid native entity-chunk metadata");Objects.requireNonNull(removal);
        String key=removal.marker().effect();Removal prior=rows.get(key);
        if(prior!=null){if(!prior.equals(removal))throw new IllegalStateException("Changed original entity removal");return;}
        if(rows.size()==MAX_REMOVALS)throw new IllegalStateException("Native entity removal quota");
        rows.put(key,removal);restored.remove(key);
    }
    /** The future native port must supply the exact currently loaded chunk members, not a count. */
    public boolean restored(Removal expected,Set<UUID> currentMembers) {
        thread();Objects.requireNonNull(expected);Set<UUID> members=Set.copyOf(currentMembers);
        return valid && restored.contains(expected.marker().effect()) && expected.equals(rows.get(expected.marker().effect()))
                && !members.contains(expected.entity());
    }
    /** Capture runs with the actual native chunk member list before Minecraft queues its save. */
    public Capture capture(Set<UUID> nativeMembers) {
        thread();Set<UUID> members=Set.copyOf(nativeMembers);
        if(valid && rows.values().stream().anyMatch(r->members.contains(r.entity()))) {
            // Preserve the conflicting declaration while permitting the ordinary native entities
            // to be saved. A contradiction must not silently erase evidence or become proof.
            original=new CompoundTag();original.putString(TAG,text());valid=false;quarantined=true;restored.clear();
        }
        return new Capture(metadata(),valid);
    }
    private CompoundTag metadata() {
        var out=new CompoundTag();
        if(!valid) {
            if(!quarantined && original!=null)return original.copy();
            out.putString(TAG,"{\"quarantined\":true,\"schema\":0}");
            if(original!=null)out.put(ORIGINAL,original.copy());
        }else if(!rows.isEmpty())out.putString(TAG,text());
        return out;
    }
    /** The caller must be the real entity-chunk storage load and pass its exact UUID membership. */
    public static EntityChunkRemovalState load(CompoundTag metadata,boolean nativeStorage,String dimension,ChunkPos chunk,Set<UUID> savedMembers) {
        var state=new EntityChunkRemovalState(dimension,chunk);Set<UUID> members=Set.copyOf(savedMembers);
        CompoundTag evidence=metadata(metadata);
        if(!evidence.contains(TAG)){if(evidence.contains(ORIGINAL)){state.original=evidence;state.valid=false;}return state;}
        state.original=evidence;
        if(!nativeStorage){state.valid=false;state.quarantined=true;return state;}
        try {
            if(evidence.contains(ORIGINAL))throw new IllegalArgumentException("Native removal quarantine retained");
            String text=evidence.getString(TAG).orElseThrow();
            if(text.length()>MAX_TEXT)throw new IllegalArgumentException("Native removal metadata quota");
            Map<String,Object> root=StrictJson.object(text);
            if(!root.keySet().equals(Set.of("schema","dimension","chunkX","chunkZ","removals")) || !Long.valueOf(1).equals(root.get("schema"))
                    || !dimension.equals(root.get("dimension")) || !Long.valueOf(chunk.x()).equals(root.get("chunkX"))
                    || !Long.valueOf(chunk.z()).equals(root.get("chunkZ")) || !(root.get("removals") instanceof List<?> values)
                    || values.isEmpty() || values.size()>MAX_REMOVALS)throw new IllegalArgumentException("Native removal chunk binding");
            for(Object value:values) {
                if(!(value instanceof Map<?,?> row) || !row.keySet().equals(Set.of("entity","incarnation","revision","marker"))
                        || !(row.get("entity") instanceof String entity) || !(row.get("incarnation") instanceof String incarnation)
                        || !(row.get("revision") instanceof Long revision) || !(row.get("marker") instanceof String marker))throw new IllegalArgumentException("Native removal row");
                UUID id=UUID.fromString(entity),version=UUID.fromString(incarnation);
                if(!id.toString().equals(entity) || !version.toString().equals(incarnation))throw new IllegalArgumentException("Native removal UUID");
                var removal=new Removal(id,version,revision,NativeDeposit.markerJson(marker));String key=removal.marker().effect();
                if(members.contains(id)) {
                    // A contradictory native save must stay quarantined if that entity later
                    // disappears for an unrelated reason. Retaining a valid-looking declaration
                    // alone would let a later empty save launder the original contradiction.
                    state.quarantined=true;throw new IllegalArgumentException("Native removal membership conflict");
                }
                if(state.rows.putIfAbsent(key,removal)!=null)throw new IllegalArgumentException("Duplicate native removal");
            }
            if(!state.text().equals(text))throw new IllegalArgumentException("Noncanonical native removal metadata");
            state.restored.addAll(state.rows.keySet());
        }catch(StrictJson.Invalid|RuntimeException invalid){state.valid=false;state.rows.clear();state.restored.clear();}
        return state;
    }
    /** Copy just this feature's native metadata; the ordinary Entities list remains Minecraft-owned. */
    public static CompoundTag metadata(CompoundTag nativeRecord) {
        var copy=new CompoundTag();for(String key:List.of(TAG,ORIGINAL))if(nativeRecord.contains(key))copy.put(key,Objects.requireNonNull(nativeRecord.get(key)).copy());return copy;
    }
    public static void append(CompoundTag nativeRecord,Capture captured) {
        CompoundTag metadata=captured.metadata();
        for(String key:List.of(TAG,ORIGINAL))if(metadata.contains(key))nativeRecord.put(key,Objects.requireNonNull(metadata.get(key)).copy());
    }
    private String text() {
        var values=new ArrayList<Map<String,Object>>(rows.size());
        for(Removal r:rows.values())values.add(Map.of("entity",r.entity().toString(),"incarnation",r.incarnation().toString(),
                "revision",r.revision(),"marker",NativeDeposit.markerJson(r.marker())));
        String text=StrictJson.canonical(Map.of("schema",1L,"dimension",dimension,"chunkX",chunkX,"chunkZ",chunkZ,"removals",values));
        if(text.length()>MAX_TEXT)throw new IllegalStateException("Native removal metadata quota");return text;
    }
}
