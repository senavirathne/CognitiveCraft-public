package dev.aivillages.fabric.runtime.evidence;

import dev.aivillages.core.kernel.StrictJson;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.Marker;
import dev.aivillages.core.kernel.runtime.evidence.NativeHarvest.Source;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

/** Bounded metadata in the source's own chunk save; live marks never claim a saved cohort. */
public final class HarvestSourceState {
    public static final String TAG="cognitivecraft_harvest_sources";
    public static final int MAX_SOURCES=64,MAX_TEXT=60_000;
    private static final String ORIGINAL=TAG+"_original";
    private record Position(int x,int y,int z) implements Comparable<Position> {
        @Override public int compareTo(Position b){int c=Integer.compare(x,b.x);if(c==0)c=Integer.compare(y,b.y);return c==0?Integer.compare(z,b.z):c;}
    }
    private static final class Tracked {
        final UUID incarnation;long revision;Marker marker;boolean restored;
        Tracked(UUID incarnation,long revision,Marker marker,boolean restored){this.incarnation=incarnation;this.revision=revision;this.marker=marker;this.restored=restored;}
    }
    private final NavigableMap<Position,Tracked> sources=new TreeMap<>();
    private boolean valid=true,quarantined;
    private String dimension;
    private int chunkX,chunkZ;
    private Thread gameThread;
    private CompoundTag original;
    public boolean valid(){return valid;}
    public int size(){return sources.size();}
    private void thread(){if(gameThread==null)gameThread=Thread.currentThread();if(gameThread!=Thread.currentThread())throw new IllegalStateException("Native source game-thread owner");}
    private void bind(String dim,ChunkPos chunk) {
        if(dim==null || dim.isBlank())throw new IllegalArgumentException("Source dimension");
        if(dimension==null){dimension=dim;chunkX=chunk.x();chunkZ=chunk.z();}
        if(!dimension.equals(dim) || chunkX!=chunk.x() || chunkZ!=chunk.z())throw new IllegalArgumentException("Native source chunk binding");
    }
    private Position position(BlockPos pos) {
        if(dimension==null || (pos.getX()>>4)!=chunkX || (pos.getZ()>>4)!=chunkZ)throw new IllegalArgumentException("Native source position binding");
        return new Position(pos.getX(),pos.getY(),pos.getZ());
    }
    /** First activation is explicit and bounded; legacy chunks acquire no metadata merely by loading. */
    public Source snapshot(String dim,ChunkPos chunk,BlockPos pos,String blockState,boolean activate) {
        thread();if(!valid)throw new IllegalStateException("Invalid native source metadata");bind(dim,chunk);Position key=position(pos);
        Tracked row=sources.get(key);
        if(row==null && activate) {
            if(sources.size()>=MAX_SOURCES)throw new IllegalStateException("Native source metadata quota");
            row=new Tracked(UUID.randomUUID(),0,null,false);sources.put(key,row);
        }
        return row==null?null:new Source(dim,key.x,key.y,key.z,row.incarnation,row.revision,blockState);
    }
    public Marker marker(BlockPos pos){Tracked row=sources.get(position(pos));return row==null?null:row.marker;}
    public boolean restored(BlockPos pos){Tracked row=sources.get(position(pos));return valid && row!=null && row.restored;}
    /** Called only for an actual LevelChunk block-state change, including replacement and restoration. */
    public void mutation(BlockPos pos) {
        if(sources.isEmpty())return;
        Position key=new Position(pos.getX(),pos.getY(),pos.getZ());Tracked row=sources.get(key);if(row==null)return;
        row.restored=false;
        try{thread();if(!valid || row.revision==Long.MAX_VALUE)throw new IllegalStateException("Source revision exhausted");row.revision++;}
        catch(RuntimeException invalid){valid=false;quarantined=true;}
    }
    public void mark(BlockPos pos,Marker marker,Runnable dirty) {
        thread();if(!valid)throw new IllegalStateException("Invalid native source metadata");
        Tracked row=Objects.requireNonNull(sources.get(position(pos)),"Untracked source");
        row.marker=Objects.requireNonNull(marker);row.restored=false;dirty.run();
    }
    /** Loading is permitted on Minecraft's storage worker; activation binds the later game thread. */
    public static HarvestSourceState load(CompoundTag metadata,boolean nativeStorage,String dimension,ChunkPos chunk) {
        var state=new HarvestSourceState();state.bind(dimension,chunk);
        if(!metadata.contains(TAG)){if(metadata.contains(ORIGINAL)){state.original=metadata.copy();state.valid=false;}return state;}
        state.original=metadata.copy();
        if(!nativeStorage){state.valid=false;state.quarantined=true;return state;}
        try {
            String text=metadata.getString(TAG).orElseThrow();if(text.length()>MAX_TEXT)throw new IllegalArgumentException("Native source metadata quota");
            Map<String,Object> root=StrictJson.object(text);
            if(!root.keySet().equals(Set.of("schema","dimension","chunkX","chunkZ","sources")) || !Long.valueOf(1).equals(root.get("schema"))
                    || !dimension.equals(root.get("dimension")) || !Long.valueOf(chunk.x()).equals(root.get("chunkX")) || !Long.valueOf(chunk.z()).equals(root.get("chunkZ"))
                    || !(root.get("sources") instanceof List<?> rows) || rows.isEmpty() || rows.size()>MAX_SOURCES)throw new IllegalArgumentException("Native source metadata envelope");
            for(Object value:rows) {
                if(!(value instanceof Map<?,?> row))throw new IllegalArgumentException("Native source row");
                var keys=row.containsKey("marker")?Set.of("x","y","z","incarnation","revision","marker"):Set.of("x","y","z","incarnation","revision");
                if(!row.keySet().equals(keys) || !(row.get("x") instanceof Long x) || !(row.get("y") instanceof Long y) || !(row.get("z") instanceof Long z)
                        || !(row.get("revision") instanceof Long revision) || revision<0 || !(row.get("incarnation") instanceof String id))throw new IllegalArgumentException("Native source row envelope");
                Position key=state.position(new BlockPos(Math.toIntExact(x),Math.toIntExact(y),Math.toIntExact(z)));UUID incarnation=UUID.fromString(id);
                if(!incarnation.toString().equals(id))throw new IllegalArgumentException("Native source incarnation");
                Marker marker=row.containsKey("marker")?NativeDeposit.markerJson((String)row.get("marker")):null;
                if(state.sources.putIfAbsent(key,new Tracked(incarnation,revision,marker,true))!=null)throw new IllegalArgumentException("Duplicate native source");
            }
            if(!state.text().equals(text))throw new IllegalArgumentException("Noncanonical native source metadata");
        }catch(StrictJson.Invalid|RuntimeException invalid){state.valid=false;state.sources.clear();}
        return state;
    }
    /** Detached immutable snapshot: later live mutations cannot alter the pending native save record. */
    public CompoundTag capture() {
        var out=new CompoundTag();
        if(!valid) {
            if(original!=null) {
                if(quarantined){out.putString(TAG,"{\"quarantined\":true,\"schema\":0}");out.put(ORIGINAL,original.copy());}
                else return original.copy();
            }else out.putString(TAG,"{\"quarantined\":true,\"schema\":0}");
        }else if(!sources.isEmpty())out.putString(TAG,text());
        return out;
    }
    public static CompoundTag metadata(CompoundTag root) {
        var out=new CompoundTag();for(String key:List.of(TAG,ORIGINAL))if(root.contains(key))out.put(key,Objects.requireNonNull(root.get(key)).copy());return out;
    }
    public static void append(CompoundTag root,CompoundTag metadata) {
        for(String key:List.of(TAG,ORIGINAL))if(metadata.contains(key))root.put(key,Objects.requireNonNull(metadata.get(key)).copy());
    }
    private String text() {
        var rows=new ArrayList<Map<String,Object>>(sources.size());
        for(var entry:sources.entrySet()) {
            Position p=entry.getKey();Tracked v=entry.getValue();var row=new LinkedHashMap<String,Object>();
            row.put("x",p.x);row.put("y",p.y);row.put("z",p.z);row.put("incarnation",v.incarnation.toString());row.put("revision",v.revision);
            if(v.marker!=null)row.put("marker",NativeDeposit.markerJson(v.marker));rows.add(row);
        }
        String text=StrictJson.canonical(Map.of("schema",1L,"dimension",dimension,"chunkX",chunkX,"chunkZ",chunkZ,"sources",rows));
        if(text.length()>MAX_TEXT)throw new IllegalStateException("Native source metadata quota");return text;
    }
}
