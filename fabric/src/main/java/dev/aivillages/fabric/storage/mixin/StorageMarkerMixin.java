package dev.aivillages.fabric.storage.mixin;

import dev.aivillages.fabric.storage.*;
import java.util.UUID;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Verified 26.3 base serialization chain. Registered only by D's standalone test descriptor. */
@Mixin(BlockEntity.class)
public abstract class StorageMarkerMixin implements StorageMarkerAccess {
    @Unique private Marker cognitivecraft$marker;
    @Unique private boolean cognitivecraft$malformed;
    @Unique private long cognitivecraft$lifecycle;
    @Unique private boolean cognitivecraft$loadedFromTag;
    public long cognitivecraft$lifecycle() { return cognitivecraft$lifecycle; }
    public boolean cognitivecraft$loadedFromTag() { return cognitivecraft$loadedFromTag; }
    @Unique private void cognitivecraft$touch() { if(cognitivecraft$lifecycle<Long.MAX_VALUE)cognitivecraft$lifecycle++;StorageIncarnationAdapter.changed(cognitivecraft$self()); }
    @Unique private BlockEntity cognitivecraft$self() { return (BlockEntity)(Object)this; }
    public Marker cognitivecraft$marker() { return cognitivecraft$marker; }
    public boolean cognitivecraft$malformedMarker() { return cognitivecraft$malformed; }
    public void cognitivecraft$writeMarker(Marker marker,StorageIncarnationAdapter.WritePermit permit) {
        permit.check(cognitivecraft$self());cognitivecraft$loadedFromTag=false;cognitivecraft$touch();cognitivecraft$marker=marker;cognitivecraft$malformed=false;
    }
    @Inject(method="loadAdditional(Lnet/minecraft/world/level/storage/ValueInput;)V",at=@At("RETURN"))
    private void cognitivecraft$load(ValueInput input,CallbackInfo ci) {
        cognitivecraft$touch();cognitivecraft$marker=null;cognitivecraft$malformed=false;cognitivecraft$loadedFromTag=true;
        if (!StorageIncarnationAdapter.eligible(cognitivecraft$self())) return;
        var child=input.child("cognitivecraft_storage_v1");if(child.isPresent()) {
            try {
                var c=child.get();String id=c.getString("incarnation").orElseThrow();UUID uuid=UUID.fromString(id);
                if (!uuid.toString().equals(id) || c.getIntOr("version",0)!=1) throw new IllegalArgumentException("marker version/id");
                cognitivecraft$marker=new Marker(uuid,c.getLong("generation").orElseThrow());
            } catch (RuntimeException malformed) { cognitivecraft$malformed=true; }
        }
        StorageIncarnationAdapter.loadedMarker(cognitivecraft$self());
    }
    @Inject(method="saveAdditional(Lnet/minecraft/world/level/storage/ValueOutput;)V",at=@At("RETURN"))
    private void cognitivecraft$save(ValueOutput output,CallbackInfo ci) {
        if (!StorageIncarnationAdapter.eligible(cognitivecraft$self())) return;
        if (cognitivecraft$malformed) { output.child("cognitivecraft_storage_v1").putInt("version",-1);return; }
        if (cognitivecraft$marker!=null) { var c=output.child("cognitivecraft_storage_v1");c.putInt("version",1);c.putString("incarnation",cognitivecraft$marker.incarnation().toString());c.putLong("generation",cognitivecraft$marker.generation()); }
    }
    @Inject(method="preRemoveSideEffects(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",at=@At("HEAD"))
    private void cognitivecraft$replacement(CallbackInfo ci) { StorageIncarnationAdapter.replaced(cognitivecraft$self()); }
    @Inject(method="setRemoved()V",at=@At("HEAD"))
    private void cognitivecraft$remove(CallbackInfo ci) { cognitivecraft$touch(); }
    @Inject(method="setBlockState(Lnet/minecraft/world/level/block/state/BlockState;)V",at=@At("HEAD"))
    private void cognitivecraft$state(BlockState next,CallbackInfo ci) { if(StorageIncarnationAdapter.topologyChanged(cognitivecraft$self().getBlockState(),next))cognitivecraft$touch(); }
    @Inject(method={"setLevel(Lnet/minecraft/world/level/Level;)V","clearRemoved()V"},at=@At("RETURN"))
    private void cognitivecraft$available(CallbackInfo ci) { StorageIncarnationAdapter.loadedMarker(cognitivecraft$self()); }
}
