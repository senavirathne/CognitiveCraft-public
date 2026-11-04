package dev.aivillages.fabric.runtime.evidence;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Transient exclusion for a drop born in an original live run. Never serialized or restored. */
public final class LiveDropCustody {
    private BooleanSupplier owner;
    private boolean bound;

    public void bind(BooleanSupplier owner) {
        if(bound)throw new IllegalStateException("Drop already has an original live owner");
        this.owner=Objects.requireNonNull(owner);bound=true;
    }

    public boolean active() {
        if(owner==null)return false;
        if(owner.getAsBoolean())return true;
        owner=null;return false;
    }
}
