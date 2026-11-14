package dev.aivillages.fabric.runtime.evidence;

import dev.aivillages.core.kernel.StrictJson;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit;
import dev.aivillages.core.kernel.runtime.evidence.NativePickup;
import java.util.*;

/** Descriptive native movement history. Decoding never mints an H/P execution capability. */
public record EntityTrailRecord(NativePickup.Trail trail,NativeDeposit.Marker marker,long revision,boolean complete,boolean membership) {
    public static final String TAG="cognitivecraft_entity_trail";
    public static final int MAX_TEXT=16_000;
    public EntityTrailRecord {
        Objects.requireNonNull(trail);Objects.requireNonNull(marker);
        if(revision<0 || marker.phase()!=NativeDeposit.MarkerPhase.RESULT)throw new IllegalArgumentException("Native trail result binding");
    }
    public String encode() {
        String value=StrictJson.canonical(Map.of("schema",1L,"entity",trail.entity().toString(),"incarnation",trail.incarnation().toString(),"dimension",trail.current().dimension(),
                "chunks",trail.chunks().stream().map(c->List.of(c.x(),c.z())).toList(),"current",List.of(trail.current().x(),trail.current().z()),
                "marker",NativeDeposit.markerJson(marker),"revision",revision,"complete",complete,"membership",membership));
        if(value.length()>MAX_TEXT)throw new IllegalArgumentException("Native trail quota");return value;
    }
    public static EntityTrailRecord decode(String text) {
        if(text==null || text.length()>MAX_TEXT)throw new IllegalArgumentException("Native trail text quota");
        try {
            var row=StrictJson.object(text);
            if(!row.keySet().equals(Set.of("schema","entity","incarnation","dimension","chunks","current","marker","revision","complete","membership"))
                    || !Long.valueOf(1).equals(row.get("schema")) || !(row.get("dimension") instanceof String dim) || !(row.get("chunks") instanceof List<?> chunks)
                    || !(row.get("complete") instanceof Boolean complete) || !(row.get("membership") instanceof Boolean membership)
                    || !(row.get("revision") instanceof Long revision))throw new IllegalArgumentException("Native trail fields");
            UUID entity=UUID.fromString((String)row.get("entity")),incarnation=UUID.fromString((String)row.get("incarnation"));
            var trail=new NativePickup.Trail(entity,incarnation,chunks.stream().map(c->chunk(dim,c)).toList(),chunk(dim,row.get("current")));
            var record=new EntityTrailRecord(trail,NativeDeposit.markerJson((String)row.get("marker")),revision,complete,membership);
            if(!record.encode().equals(text))throw new IllegalArgumentException("Noncanonical native trail");return record;
        }catch(StrictJson.Invalid invalid){throw new IllegalArgumentException("Invalid native trail",invalid);}
    }
    private static NativePickup.Chunk chunk(String dim,Object raw) {
        if(!(raw instanceof List<?> xy) || xy.size()!=2 || !(xy.get(0) instanceof Long x) || !(xy.get(1) instanceof Long z))throw new IllegalArgumentException("Native trail chunk");
        return new NativePickup.Chunk(dim,Math.toIntExact(x),Math.toIntExact(z));
    }
}
