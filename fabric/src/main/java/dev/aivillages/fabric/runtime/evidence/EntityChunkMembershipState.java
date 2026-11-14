package dev.aivillages.fabric.runtime.evidence;

import dev.aivillages.core.kernel.StrictJson;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.Marker;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.MarkerPhase;
import dev.aivillages.core.kernel.runtime.evidence.NativePickup;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

/** Complete, bounded participant trails captured in the actual native entity record. */
public final class EntityChunkMembershipState {
    public static final String TAG=EntityChunkRecord.MEMBERSHIP_TAG;
    public static final int MAX_ROWS=64,MAX_TEXT=60_000;
    public record Row(NativePickup.Role role,NativePickup.Trail trail,long revision,Marker marker,boolean present,Marker predecessor) {
        public Row {
            Objects.requireNonNull(role);Objects.requireNonNull(trail);Objects.requireNonNull(marker);
            if(revision<0 || marker.phase()!=MarkerPhase.RESULT || predecessor!=null && predecessor.phase()!=MarkerPhase.RESULT)
                throw new IllegalArgumentException("Native membership result");
        }
        public String key(){return trail.entity().toString();}
    }
    /** Live staged transitions may span both pickup and deposit between native writes. */
    static final class Pending {
        private static final int MAX_STAGES=4;
        private final List<Row> stages;
        Pending(Row row){stages=List.of(row);}
        private Pending(List<Row> stages){this.stages=List.copyOf(stages);}
        Row latest(){return stages.getLast();}
        Pending stage(Row row) {
            Row previous=latest();
            Row next=successor(previous,row);
            if(previous.equals(next))return this;
            var updates=new ArrayList<>(stages);
            if(previous.marker().equals(next.marker()))updates.set(updates.size()-1,next);
            else {
                if(updates.size()==MAX_STAGES)throw new IllegalArgumentException("Pending membership transition quota");
                updates.add(next);
            }
            return new Pending(updates);
        }
        Pending acknowledged(Marker marker) {
            for(int n=1;n<stages.size();n++)if(stages.get(n).marker().equals(marker))
                return new Pending(stages.subList(n,stages.size()));
            return this;
        }
        void apply(EntityChunkMembershipState state) {
            Row saved=state.rows.get(latest().key());
            int start=0;
            if(saved!=null)for(int n=0;n<stages.size();n++) {
                if(saved.marker().equals(stages.get(n).marker())){start=n;break;}
            }
            for(int n=start;n<stages.size();n++)state.record(stages.get(n));
        }
    }
    private final String dimension;private final ChunkPos chunk;
    private final NavigableMap<String,Row> rows=new TreeMap<>();
    private boolean valid=true;private CompoundTag original;
    private EntityChunkMembershipState(String dimension,ChunkPos chunk){this.dimension=dimension;this.chunk=chunk;}
    public boolean valid(){return valid;}
    public int size(){return rows.size();}
    public Row row(UUID entity){return valid?rows.get(entity.toString()):null;}
    Map<String,Row> rows(){return valid?Map.copyOf(rows):Map.of();}
    private NativePickup.Chunk location(){return new NativePickup.Chunk(dimension,chunk.x(),chunk.z());}
    private void check(Row row) {
        if(!row.trail().chunks().contains(location()) || row.present() && !row.trail().current().equals(location()))
            throw new IllegalArgumentException("Membership chunk binding");
    }
    /** Called only with detached declarations made by the native producer's live lifetime. */
    public void record(Row row) {
        if(!valid)throw new IllegalStateException("Unsupported membership metadata");check(row);Row before=rows.get(row.key());
        if(before!=null)row=successor(before,row);
        if(before==null && rows.size()==MAX_ROWS)throw new IllegalStateException("Native membership quota");
        rows.put(row.key(),row);
    }
    private static Row successor(Row before,Row row) {
        if(row.marker().equals(before.marker()) && row.predecessor()==null && before.predecessor()!=null)
            row=new Row(row.role(),row.trail(),row.revision(),row.marker(),row.present(),before.predecessor());
        if(!before.equals(row)) {
            if(!row.trail().incarnation().equals(before.trail().incarnation()) || row.role()!=before.role()
                    || !row.trail().chunks().containsAll(before.trail().chunks()) || row.revision()<before.revision()
                    || !(row.marker().equals(before.marker()) || before.marker().equals(row.predecessor())))
                throw new IllegalArgumentException("Unproved membership successor");
        }
        return row;
    }
    public CompoundTag capture(Set<UUID> members) {
        var out=new CompoundTag();
        if(!valid)return original.copy();
        if(rows.isEmpty())return out;
        if(rows.values().stream().anyMatch(r->members.contains(r.trail().entity())!=r.present())) {
            out.putString(TAG,"{\"quarantined\":true,\"schema\":0}");
            var saved=new CompoundTag();saved.putString(TAG,text());out.put(TAG+"_original",saved);return out;
        }
        out.putString(TAG,text());return out;
    }
    public static EntityChunkMembershipState load(CompoundTag metadata,String dimension,ChunkPos chunk,Set<UUID> members) {
        var state=new EntityChunkMembershipState(dimension,chunk);state.original=new CompoundTag();
        for(String key:List.of(TAG,TAG+"_original"))if(metadata.contains(key))state.original.put(key,Objects.requireNonNull(metadata.get(key)).copy());
        if(state.original.isEmpty())return state;
        try {
            if(state.original.contains(TAG+"_original"))throw new IllegalArgumentException("Quarantined membership");
            String text=state.original.getString(TAG).orElseThrow();if(text.length()>MAX_TEXT)throw new IllegalArgumentException("Membership text quota");
            var root=StrictJson.object(text);
            if(!root.keySet().equals(Set.of("schema","dimension","x","z","rows")) || !Long.valueOf(1).equals(root.get("schema"))
                    || !dimension.equals(root.get("dimension")) || !Long.valueOf(chunk.x()).equals(root.get("x")) || !Long.valueOf(chunk.z()).equals(root.get("z"))
                    || !(root.get("rows") instanceof List<?> values) || values.isEmpty() || values.size()>MAX_ROWS)throw new IllegalArgumentException("Membership frame binding");
            for(Object value:values) {
                if(!(value instanceof Map<?,?> r) || !r.keySet().equals(Set.of("role","entity","incarnation","chunks","current","revision","marker","present","predecessor")))throw new IllegalArgumentException("Membership row");
                var trail=new NativePickup.Trail(UUID.fromString((String)r.get("entity")),UUID.fromString((String)r.get("incarnation")),
                        ((List<?>)r.get("chunks")).stream().map(c->decodeChunk(c,dimension)).toList(),decodeChunk(r.get("current"),dimension));
                String predecessor=(String)r.get("predecessor");
                var row=new Row(NativePickup.Role.valueOf((String)r.get("role")),trail,(Long)r.get("revision"),NativeDeposit.markerJson((String)r.get("marker")),
                        (Boolean)r.get("present"),predecessor.isEmpty()?null:NativeDeposit.markerJson(predecessor));
                state.check(row);
                if(members.contains(trail.entity())!=row.present()) {
                    var retained=state.original;state.original=new CompoundTag();
                    state.original.putString(TAG,"{\"quarantined\":true,\"schema\":0}");state.original.put(TAG+"_original",retained);
                    throw new IllegalArgumentException("Native membership contradiction");
                }
                if(state.rows.putIfAbsent(row.key(),row)!=null)throw new IllegalArgumentException("Duplicate membership");
            }
            if(!state.text().equals(text))throw new IllegalArgumentException("Noncanonical native membership");
        }catch(StrictJson.Invalid|RuntimeException invalid){state.valid=false;state.rows.clear();}
        return state;
    }
    private static NativePickup.Chunk decodeChunk(Object value,String dimension) {
        if(!(value instanceof List<?> xy) || xy.size()!=2 || !(xy.get(0) instanceof Long x) || !(xy.get(1) instanceof Long z))throw new IllegalArgumentException("Membership coordinate");
        return new NativePickup.Chunk(dimension,Math.toIntExact(x),Math.toIntExact(z));
    }
    private String text() {
        var values=rows.values().stream().map(r->Map.of("role",r.role().name(),"entity",r.trail().entity().toString(),"incarnation",r.trail().incarnation().toString(),
                "chunks",r.trail().chunks().stream().map(c->List.of(c.x(),c.z())).toList(),"current",List.of(r.trail().current().x(),r.trail().current().z()),
                "revision",r.revision(),"marker",NativeDeposit.markerJson(r.marker()),"present",r.present(),"predecessor",r.predecessor()==null?"":NativeDeposit.markerJson(r.predecessor()))).toList();
        String text=StrictJson.canonical(Map.of("schema",1L,"dimension",dimension,"x",chunk.x(),"z",chunk.z(),"rows",values));
        if(text.length()>MAX_TEXT)throw new IllegalStateException("Membership text quota");return text;
    }
}
