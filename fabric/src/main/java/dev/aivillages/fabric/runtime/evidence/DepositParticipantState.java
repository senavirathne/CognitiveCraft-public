package dev.aivillages.fabric.runtime.evidence;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import dev.aivillages.core.kernel.StrictJson;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;
import java.util.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Stored with exactly one native inventory. Serialization is not a multi-participant save. */
public final class DepositParticipantState {
    public static final String TAG="cognitivecraft_deposit_evidence";
    private static final String ORIGINAL=TAG+"_original",PREVIOUS=TAG+"_previous";
    private static final int MAX_TEXT=2048;
    private UUID incarnation;
    private long revision;
    private Marker marker;
    private volatile boolean active,valid=true,restored,quarantined;
    private Thread gameThread;
    private int metadataDepth;
    private Dynamic<?> original,originalExtra;
    private String previous="";
    public boolean active(){return active;}
    public boolean valid(){return valid;}
    public boolean restored(){return active && valid && restored;}
    public UUID incarnation(){return incarnation;}
    public long revision(){return revision;}
    public Marker marker(){return marker;}
    public void activate() {
        if(!valid)throw new IllegalStateException("Invalid or imported native deposit metadata");
        if(gameThread==null)gameThread=Thread.currentThread();
        if(gameThread!=Thread.currentThread())throw new IllegalStateException("Native deposit participant thread");
        if(!active){incarnation=UUID.randomUUID();revision=0;active=true;restored=false;}
    }
    public void track(List<ItemStack> slots){activate();DepositStackOwners.bind(this,slots);}
    /** Initial deserialization may use its native worker; do not bind the future game thread here. */
    public void trackLoaded(List<ItemStack> slots) {
        if(!active || !valid)return;
        try{DepositStackOwners.bind(this,slots);}catch(RuntimeException unsupported){valid=false;quarantined=true;restored=false;}
    }
    public void mutation() {
        if(!active || metadataDepth!=0)return;
        if(gameThread==null)gameThread=Thread.currentThread();
        restored=false;
        if(!valid || gameThread!=Thread.currentThread() || revision==Long.MAX_VALUE){valid=false;quarantined=true;return;}
        revision++;
    }
    public void mark(Marker next,Runnable nativeDirty) {
        activate();marker=Objects.requireNonNull(next);restored=false;
        metadataDepth++;try{nativeDirty.run();}finally{metadataDepth--;}
    }
    public void load(ValueInput input,Kind kind) {
        boolean wasActive=active;String old=wasActive && valid?text():"";
        original=input.read(TAG,Codec.PASSTHROUGH).orElse(null);originalExtra=input.read(ORIGINAL,Codec.PASSTHROUGH).orElse(null);
        previous=input.getStringOr(PREVIOUS,"");restored=false;gameThread=null;
        if(!DepositNativeLoad.saved(kind)) {
            if(original==null && !wasActive)return;
            active=true;valid=false;quarantined=true;if(!old.isEmpty())previous=old;return;
        }
        if(original==null){active=false;valid=true;incarnation=null;revision=0;marker=null;return;}
        active=true;
        try {
            String value=input.getString(TAG).orElseThrow();if(value.length()>MAX_TEXT)throw new IllegalArgumentException("Native deposit metadata quota");
            Map<String,Object> row=StrictJson.object(value);
            Set<String> keys=row.containsKey("marker")?Set.of("schema","incarnation","revision","marker"):Set.of("schema","incarnation","revision");
            if(!row.keySet().equals(keys) || !Long.valueOf(1).equals(row.get("schema")) || !(row.get("revision") instanceof Long count) || count<0
                    || !(row.get("incarnation") instanceof String id))throw new IllegalArgumentException("Native deposit metadata format");
            incarnation=UUID.fromString(id);if(!incarnation.toString().equals(id))throw new IllegalArgumentException("Native deposit incarnation");
            revision=count;marker=row.containsKey("marker")?NativeDeposit.markerJson((String)row.get("marker")):null;
            valid=true;quarantined=false;if(!text().equals(value))throw new IllegalArgumentException("Noncanonical native deposit metadata");restored=true;
        }catch(StrictJson.Invalid|RuntimeException invalid){valid=false;restored=false;}
    }
    public void save(ValueOutput output) {
        if(!active)return;
        if(!valid) {
            if(quarantined) {
                output.putString(TAG,"{\"quarantined\":true,\"schema\":0}");
                if(original!=null)output.store(ORIGINAL,Codec.PASSTHROUGH,original);
            }else if(original!=null)output.store(TAG,Codec.PASSTHROUGH,original);
            if(originalExtra!=null && !quarantined)output.store(ORIGINAL,Codec.PASSTHROUGH,originalExtra);
            if(!previous.isEmpty())output.putString(PREVIOUS,previous);return;
        }
        output.putString(TAG,text());
    }
    private String text() {
        var row=new LinkedHashMap<String,Object>();row.put("schema",1L);row.put("incarnation",incarnation.toString());row.put("revision",revision);
        if(marker!=null)row.put("marker",NativeDeposit.markerJson(marker));String value=StrictJson.canonical(row);
        if(value.length()>MAX_TEXT)throw new IllegalStateException("Native deposit metadata quota");return value;
    }
}
