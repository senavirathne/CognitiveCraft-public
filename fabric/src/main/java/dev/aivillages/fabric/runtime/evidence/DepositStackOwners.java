package dev.aivillages.fabric.runtime.evidence;

import java.lang.ref.WeakReference;
import java.util.*;
import net.minecraft.world.item.ItemStack;

/** Bounded live pointer association, never a durable stack identity or causal proof. */
public final class DepositStackOwners {
    public static final int MAX_STACKS=4096;
    private static final IdentityHashMap<ItemStack,WeakReference<DepositParticipantState>> OWNERS=new IdentityHashMap<>();
    private static volatile boolean active;
    private DepositStackOwners() { }
    public static synchronized void bind(DepositParticipantState owner,List<ItemStack> slots) {
        OWNERS.entrySet().removeIf(e->e.getValue().get()==null);
        long previous=OWNERS.values().stream().filter(ref->ref.get()==owner).count();
        var actual=Collections.newSetFromMap(new IdentityHashMap<ItemStack,Boolean>());
        for(ItemStack stack:slots)if(!stack.isEmpty()) {
            if(!actual.add(stack))throw new IllegalStateException("Aliased native deposit slots are unsupported");
            var existing=OWNERS.get(stack);if(existing!=null && existing.get()!=null && existing.get()!=owner)throw new IllegalStateException("Aliased native deposit participants are unsupported");
        }
        if(OWNERS.size()-previous+actual.size()>MAX_STACKS)throw new IllegalStateException("Native deposit stack tracking quota");
        OWNERS.entrySet().removeIf(e->e.getValue().get()==owner);
        for(ItemStack stack:actual)OWNERS.put(stack,new WeakReference<>(owner));active=!OWNERS.isEmpty();
    }
    public static void changed(ItemStack stack) {
        if(!active)return;
        DepositParticipantState owner;
        synchronized(DepositStackOwners.class){var ref=OWNERS.get(stack);owner=ref==null?null:ref.get();if(ref!=null && owner==null)OWNERS.remove(stack);}
        if(owner!=null)owner.mutation();
    }
}
