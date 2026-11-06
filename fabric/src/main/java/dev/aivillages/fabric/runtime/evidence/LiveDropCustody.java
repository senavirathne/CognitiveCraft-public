package dev.aivillages.fabric.runtime.evidence;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Saved origin excludes foreign mobs. Only the original live callback fences every mob. */
public final class LiveDropCustody {
    private BooleanSupplier owner;
    private UUID origin;
    private boolean bound;

    public void bind(UUID origin,BooleanSupplier owner) {
        if(bound)throw new IllegalStateException("Drop already has an original live owner");
        Objects.requireNonNull(origin);
        Objects.requireNonNull(owner);
        this.origin=origin;this.owner=owner;bound=true;
    }

    public boolean active() {
        if(owner==null)return false;
        if(owner.getAsBoolean())return true;
        owner=null;return false;
    }

    public UUID origin(){return origin;}

    public boolean blocksNativePickup(UUID actor) {
        return active() || origin!=null && !origin.equals(actor);
    }

    public void restoreOrigin(UUID saved) { origin=saved;owner=null;bound=true; }
}
